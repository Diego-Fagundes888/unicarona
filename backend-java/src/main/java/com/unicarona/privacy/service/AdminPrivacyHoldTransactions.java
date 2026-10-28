package com.unicarona.privacy.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unicarona.auth.config.AuthSettings;
import com.unicarona.file.storage.PrivateFileStorage;
import com.unicarona.privacy.dto.PrivacyHoldDto;
import com.unicarona.security.*;
import com.unicarona.shared.*;
import com.unicarona.shared.repository.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A hold preserves an identified resource; it neither restores erased data nor blocks account
 * erasure wholesale.
 */
@Service
public class AdminPrivacyHoldTransactions {
  public record HeldData(byte[] data, String contentType, String extension) {
    @Override
    public String toString() {
      return "HeldPrivacyData[redacted]";
    }
  }

  private static final String STATE =
      "CASE WHEN released_at IS NOT NULL THEN 'released' WHEN expires_at<=now() THEN 'expired' WHEN review_at<=now() THEN 'review_due' ELSE 'active' END";
  private final JdbcTemplate sql;
  private final ActorsRepository actors;
  private final PrivacyEvidenceCrypto evidence;
  private final PrivateFileStorage storage;
  private final ObjectMapper json;
  private final CryptoService crypto;
  private final AuthSettings auth;
  private final AuditRepository audit;
  private final IdempotencyExecutor keys;
  private final PrivacyHoldRecovery recovery;

  public AdminPrivacyHoldTransactions(
      JdbcTemplate sql,
      ActorsRepository actors,
      PrivacyEvidenceCrypto evidence,
      PrivateFileStorage storage,
      ObjectMapper json,
      CryptoService crypto,
      AuthSettings auth,
      AuditRepository audit,
      IdempotencyExecutor keys,
      PrivacyHoldRecovery recovery) {
    this.sql = sql;
    this.actors = actors;
    this.evidence = evidence;
    this.storage = storage;
    this.json = json;
    this.crypto = crypto;
    this.auth = auth;
    this.audit = audit;
    this.keys = keys;
    this.recovery = recovery;
  }

  public static void admin(Actor actor) {
    ApiException.require(
        "admin".equals(actor.role())
            && "active".equals(actor.accountStatus())
            && actor.authorities().contains("ROLE_ADMIN"),
        "FORBIDDEN",
        "Acesso administrativo necessário.",
        403);
  }

  @Transactional(readOnly = true)
  public List<PrivacyHoldDto.Item> list(
      Actor actor, UUID owner, String state, int limit, int offset) {
    admin(actor);
    return sql.query(
        "SELECT *,"
            + STATE
            + " AS hold_status FROM privacy_legal_holds WHERE (?::uuid IS NULL OR user_id=?::uuid) AND (?='' OR ("
            + STATE
            + ")=?) ORDER BY CASE WHEN released_at IS NULL AND expires_at>now() THEN 0 ELSE 1 END,review_at,id LIMIT ? OFFSET ?",
        (r, n) -> item(r),
        owner,
        owner,
        state,
        state,
        limit,
        offset);
  }

  @Transactional(readOnly = true)
  public List<PrivacyHoldDto.OwnRetention> own(Actor actor, int limit, int offset) {
    return sql.query(
        "SELECT id,category,legal_basis,review_at,expires_at,"
            + STATE
            + " AS hold_status FROM privacy_legal_holds WHERE user_id=? AND released_at IS NULL AND expires_at>now() ORDER BY expires_at,id LIMIT ? OFFSET ?",
        (r, n) ->
            new PrivacyHoldDto.OwnRetention(
                r.getObject("id", UUID.class),
                r.getString("category"),
                r.getString("legal_basis"),
                date(r, "review_at"),
                date(r, "expires_at"),
                r.getString("hold_status")),
        actor.id(),
        limit,
        offset);
  }

  @Transactional
  public PrivacyHoldDto.Item create(
      Actor actor, String expectedHash, PrivacyHoldDto.Create input, String key) {
    admin(actor);
    PrivacyRetentionLock.acquire(sql);
    lock(actor, expectedHash, input.userId());
    var fingerprint = new TreeMap<String, Object>();
    fingerprint.put("user", input.userId());
    fingerprint.put("category", input.category());
    fingerprint.put("resource", input.resourceId());
    fingerprint.put("basis", input.legalBasis());
    fingerprint.put("review", input.reviewAt().toString());
    fingerprint.put("expires", input.expiresAt().toString());
    fingerprint.put("reasonHmac", privateDigest(input.reason(), input.caseReference()));
    return keys.execute(
        actor.id(),
        key,
        "privacy:hold:create",
        fingerprint,
        PrivacyHoldDto.Item.class,
        () -> {
          windows(input.reviewAt(), input.expiresAt());
          String type = resource(input.userId(), input.category(), input.resourceId());
          boolean duplicate =
              Boolean.TRUE.equals(
                  sql.queryForObject(
                      "SELECT EXISTS(SELECT 1 FROM privacy_legal_holds WHERE user_id=? AND category=? AND resource_id=? AND released_at IS NULL AND expires_at>now())",
                      Boolean.class,
                      input.userId(),
                      input.category(),
                      input.resourceId()));
          ApiException.require(
              !duplicate,
              "PRIVACY_HOLD_EXISTS",
              "Já existe uma retenção ativa para esse recurso. Revise a retenção existente.",
              409);
          UUID id = UUID.randomUUID();
          String body =
              body(input.reason(), input.caseReference(), input.reviewAt(), input.expiresAt());
          sql.update(
              "INSERT INTO privacy_legal_holds(id,user_id,category,resource_type,resource_id,legal_basis,reason_ciphertext,created_by,review_at,expires_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
              id,
              input.userId(),
              input.category(),
              type,
              input.resourceId(),
              input.legalBasis(),
              evidence.seal(PrivacyEvidenceCrypto.Field.HOLD_REASON, input.userId(), id, body),
              actor.id(),
              Timestamp.from(input.reviewAt()),
              Timestamp.from(input.expiresAt()));
          snapshot(
              input.userId(),
              input.category(),
              type,
              input.resourceId(),
              input.legalBasis(),
              input.expiresAt());
          event(id, input.userId(), 1, "created", actor.id(), body);
          audit.record(
              actor.id(),
              "privacy_hold_created",
              "privacy_hold",
              id,
              Map.of("category", input.category(), "scope", "specific_resource", "revision", 1));
          recovery.stage();
          return get(id);
        });
  }

  @Transactional
  public PrivacyHoldDto.Item review(
      Actor actor, String expectedHash, UUID id, PrivacyHoldDto.Review input, String key) {
    admin(actor);
    PrivacyRetentionLock.acquire(sql);
    var row = find(id);
    UUID owner = (UUID) row.get("user_id");
    lock(actor, expectedHash, owner);
    var fingerprint = new TreeMap<String, Object>();
    fingerprint.put("hold", id);
    fingerprint.put("revision", input.expectedRevision());
    fingerprint.put("action", input.action());
    fingerprint.put("review", input.reviewAt() == null ? null : input.reviewAt().toString());
    fingerprint.put("expires", input.expiresAt() == null ? null : input.expiresAt().toString());
    fingerprint.put("reasonHmac", privateDigest(input.reason(), input.caseReference()));
    return keys.execute(
        actor.id(),
        key,
        "privacy:hold:review",
        fingerprint,
        PrivacyHoldDto.Item.class,
        () -> {
          ApiException.require(
              ((Number) row.get("revision")).intValue() == input.expectedRevision(),
              "PRIVACY_HOLD_CHANGED",
              "A retenção mudou. Recarregue antes de decidir.",
              409);
          ApiException.require(
              row.get("released_at") == null
                  && ((Timestamp) row.get("expires_at")).toInstant().isAfter(now()),
              "PRIVACY_HOLD_INACTIVE",
              "Uma retenção encerrada não pode ser reativada. Avalie uma nova retenção apenas sobre dados ainda disponíveis.",
              409);
          baseline(row, actor.id());
          int revision = input.expectedRevision() + 1;
          boolean release = "release".equals(input.action());
          if (release) {
            ApiException.require(
                input.reviewAt() == null && input.expiresAt() == null,
                "VALIDATION_ERROR",
                "A liberação não recebe novas datas de retenção.",
                422);
          } else windows(input.reviewAt(), input.expiresAt());
          Instant
              review = release ? ((Timestamp) row.get("review_at")).toInstant() : input.reviewAt(),
              expires =
                  release ? ((Timestamp) row.get("expires_at")).toInstant() : input.expiresAt();
          String body = body(input.reason(), input.caseReference(), review, expires);
          sql.update(
              "UPDATE privacy_legal_holds SET revision=?,reviewed_at=now(),review_at=?,expires_at=?,released_at=CASE WHEN ? THEN now() ELSE NULL END,reason_ciphertext=?,retain_until=now()+interval '5 years' WHERE id=?",
              revision,
              Timestamp.from(review),
              Timestamp.from(expires),
              release,
              evidence.seal(PrivacyEvidenceCrypto.Field.HOLD_REASON, owner, id, body),
              id);
          event(id, owner, revision, release ? "released" : "reviewed", actor.id(), body);
          audit.record(
              actor.id(),
              release ? "privacy_hold_released" : "privacy_hold_reviewed",
              "privacy_hold",
              id,
              Map.of("revision", revision, "automaticExtension", false));
          recovery.stage();
          return get(id);
        });
  }

  @Transactional
  public PrivacyHoldDto.Evidence detail(Actor actor, String expectedHash, UUID id) {
    admin(actor);
    PrivacyRetentionLock.acquire(sql);
    var row = find(id);
    UUID owner = (UUID) row.get("user_id");
    lock(actor, expectedHash, owner);
    Map<String, String> clear =
        read(
            evidence.open(
                PrivacyEvidenceCrypto.Field.HOLD_REASON,
                owner,
                id,
                (String) row.get("reason_ciphertext")));
    var history =
        sql.query(
            "SELECT * FROM privacy_hold_events WHERE hold_id=? ORDER BY revision",
            (r, n) -> {
              var value =
                  read(
                      evidence.open(
                          PrivacyEvidenceCrypto.Field.HOLD_EVENT_REASON,
                          owner,
                          r.getObject("id", UUID.class),
                          r.getString("body_ciphertext")));
              return new PrivacyHoldDto.Event(
                  r.getObject("id", UUID.class),
                  r.getInt("revision"),
                  r.getString("action"),
                  date(r, "created_at"),
                  r.getObject("created_by", UUID.class),
                  value.get("reason"),
                  value.get("caseReference"),
                  value.get("reviewAt"),
                  value.get("expiresAt"));
            },
            id);
    audit.record(
        actor.id(),
        "privacy_hold_evidence_accessed",
        "privacy_hold",
        id,
        Map.of("historyCount", history.size()));
    return new PrivacyHoldDto.Evidence(
        get(id), clear.get("reason"), clear.get("caseReference"), history);
  }

  @Transactional
  public HeldData heldData(Actor actor, String expectedHash, UUID id) {
    admin(actor);
    PrivacyRetentionLock.acquire(sql);
    var hold = find(id);
    UUID owner = (UUID) hold.get("user_id");
    lock(actor, expectedHash, owner);
    ApiException.require(
        hold.get("released_at") == null
            && ((Timestamp) hold.get("expires_at")).toInstant().isAfter(now()),
        "PRIVACY_HOLD_INACTIVE",
        "A retenção já não autoriza acesso a essa evidência.",
        409);
    String category = (String) hold.get("category");
    UUID resource = (UUID) hold.get("resource_id");
    try {
      if ("files".equals(category)) {
        ApiException.require(
            resource != null,
            "PRIVACY_SPECIFIC_RESOURCE_REQUIRED",
            "Selecione um recurso específico antes de acessar a evidência.",
            409);
        var files =
            sql.queryForList(
                "SELECT storage_key,content_type FROM stored_files WHERE id=? AND owner_id=? AND erased_at IS NULL",
                resource,
                owner);
        ApiException.require(
            !files.isEmpty(),
            "PRIVACY_RESOURCE_UNAVAILABLE",
            "A evidência já não está disponível.",
            409);
        var file = files.getFirst();
        String type = (String) file.get("content_type");
        String extension =
            switch (type) {
              case "image/png" -> "png";
              case "image/jpeg" -> "jpg";
              case "image/webp" -> "webp";
              case "application/pdf" -> "pdf";
              default -> null;
            };
        ApiException.require(
            extension != null,
            "PRIVACY_RESOURCE_UNAVAILABLE",
            "Formato de evidência indisponível.",
            409);
        byte[] data = storage.get((String) file.get("storage_key"));
        audit.record(
            actor.id(),
            "privacy_held_data_accessed",
            "privacy_hold",
            id,
            Map.of("category", category, "records", 1));
        return new HeldData(data, type, extension);
      }
      var records =
          sql.queryForList(
              "SELECT id,category,content_ciphertext,created_at,delete_after FROM privacy_retained_records WHERE user_id=? AND category=? AND resource_id=? ORDER BY created_at,id LIMIT 101",
              owner,
              category,
              resource);
      ApiException.require(
          !records.isEmpty(),
          "PRIVACY_RETAINED_COPY_UNAVAILABLE",
          "Não existe cópia desse recurso no cofre. O recurso ativo deve ser consultado no fluxo administrativo apropriado.",
          409);
      ApiException.require(
          records.size() <= 100,
          "PRIVACY_CASE_SCOPE_REQUIRED",
          "Há muitas cópias para esta consulta. Delimite o caso no atendimento de privacidade.",
          409);
      var values = new ArrayList<Object>();
      for (var record : records) {
        String clear =
            evidence.open(
                PrivacyEvidenceCrypto.Field.RETAINED_CONTENT,
                owner,
                (UUID) record.get("id"),
                (String) record.get("content_ciphertext"));
        values.add(
            Map.of(
                "recordId",
                record.get("id").toString(),
                "category",
                category,
                "createdAt",
                ((Timestamp) record.get("created_at")).toInstant().toString(),
                "data",
                json.readTree(clear)));
      }
      byte[] data =
          json.writeValueAsBytes(
              Map.of(
                  "holdId",
                  id.toString(),
                  "resourceType",
                  hold.get("resource_type"),
                  "records",
                  values));
      audit.record(
          actor.id(),
          "privacy_held_data_accessed",
          "privacy_hold",
          id,
          Map.of("category", category, "records", values.size()));
      return new HeldData(data, "application/json", "json");
    } catch (java.io.IOException unavailable) {
      throw new ApiException(
          "PRIVACY_EVIDENCE_UNAVAILABLE",
          "Não foi possível ler a evidência privada. Nenhuma cópia pública foi criada.",
          503);
    }
  }

  private String resource(UUID owner, String category, UUID id) {
    String type, query;
    switch (category) {
      case "profile", "contract_identity" -> {
        type = "user";
        query = "SELECT EXISTS(SELECT 1 FROM users WHERE id=? AND id=? AND deleted_at IS NULL)";
      }
      case "files" -> {
        type = "stored_file";
        query =
            "SELECT EXISTS(SELECT 1 FROM stored_files WHERE owner_id=? AND id=? AND erased_at IS NULL)";
      }
      case "location" -> {
        type = "ride_route";
        query =
            "SELECT EXISTS(SELECT 1 FROM ride_routes rt JOIN users u ON u.id=rt.driver_id WHERE rt.driver_id=? AND rt.id=? AND u.deleted_at IS NULL AND rt.details_retired_at IS NULL)";
      }
      case "vehicle_identity" -> {
        type = "vehicle";
        query =
            "SELECT EXISTS(SELECT 1 FROM vehicles v JOIN users u ON u.id=v.owner_id WHERE v.owner_id=? AND v.id=? AND u.deleted_at IS NULL AND v.deleted_at IS NULL)";
      }
      case "identity_change" -> {
        type = "identity_change";
        query =
            "SELECT EXISTS(SELECT 1 FROM privacy_email_changes WHERE user_id=? AND id=? AND new_email_ciphertext IS NOT NULL)";
      }
      case "account_decision" -> {
        type = "account_decision";
        query = "SELECT EXISTS(SELECT 1 FROM privacy_account_decisions WHERE user_id=? AND id=?)";
      }
      case "driver_application" -> {
        type = "driver_application";
        query = "SELECT EXISTS(SELECT 1 FROM driver_applications WHERE user_id=? AND id=?)";
      }
      case "privacy_request" -> {
        type = "privacy_request";
        query = "SELECT EXISTS(SELECT 1 FROM privacy_requests WHERE user_id=? AND id=?)";
      }
      case "consent" -> {
        type = "privacy_consent";
        query = "SELECT EXISTS(SELECT 1 FROM privacy_consent_events WHERE user_id=? AND id=?)";
      }
      case "legal" -> {
        type = "legal_acceptance";
        query = "SELECT EXISTS(SELECT 1 FROM legal_acceptances WHERE user_id=? AND id=?)";
      }
      case "notifications" -> {
        type = "notification";
        query = "SELECT EXISTS(SELECT 1 FROM notifications WHERE user_id=? AND id=?)";
      }
      case "security_audit" -> {
        type = "audit_log";
        query =
            "SELECT EXISTS(SELECT 1 FROM audit_logs a WHERE a.actor_id=? AND a.id=? AND privacy_security_audit_deadline(a)>clock_timestamp())";
      }
      default -> throw new ApiException("VALIDATION_ERROR", "Categoria de retenção inválida.", 422);
    }
    boolean exists = Boolean.TRUE.equals(sql.queryForObject(query, Boolean.class, owner, id));
    if (!exists
        && Set.of(
                "profile",
                "contract_identity",
                "location",
                "vehicle_identity",
                "identity_change",
                "notifications")
            .contains(category))
      exists =
          Boolean.TRUE.equals(
              sql.queryForObject(
                  "SELECT EXISTS(SELECT 1 FROM privacy_retained_records WHERE user_id=? AND category=? AND resource_id=?)",
                  Boolean.class,
                  owner,
                  category,
                  id));
    if (!exists && Set.of("driver_application", "security_audit").contains(category))
      exists =
          Boolean.TRUE.equals(
              sql.queryForObject(
                  "SELECT EXISTS(SELECT 1 FROM privacy_retained_records p WHERE p.user_id=? AND p.category=? AND p.resource_id=? AND EXISTS(SELECT 1 FROM privacy_legal_holds h WHERE h.user_id=p.user_id AND h.category=p.category AND h.resource_id=p.resource_id AND h.released_at IS NULL AND h.expires_at>now()))",
                  Boolean.class,
                  owner,
                  category,
                  id));
    ApiException.require(
        exists,
        "PRIVACY_RESOURCE_UNAVAILABLE",
        "O recurso não pertence a esta conta ou seus dados já não estão disponíveis. Uma retenção não recupera dados apagados.",
        409);
    if ("files".equals(category))
      try {
        String key =
            sql.queryForObject("SELECT storage_key FROM stored_files WHERE id=?", String.class, id);
        ApiException.require(
            storage.existsVerified(key),
            "PRIVACY_RESOURCE_UNAVAILABLE",
            "O conteúdo desse arquivo já não está disponível para retenção.",
            409);
      } catch (java.io.IOException unavailable) {
        throw new ApiException(
            "PRIVACY_STORAGE_UNAVAILABLE",
            "Não foi possível confirmar a disponibilidade do arquivo. Tente novamente.",
            503);
      }
    return type;
  }

  private void snapshot(
      UUID owner, String category, String type, UUID resource, String basis, Instant expires) {
    String query =
        switch (category) {
          case "profile" ->
              "SELECT name,email,birth_date,phone FROM users WHERE id=? AND id=? AND deleted_at IS NULL";
          case "contract_identity" ->
              "SELECT name,email,phone FROM users WHERE id=? AND id=? AND deleted_at IS NULL";
          case "vehicle_identity" ->
              "SELECT v.id,v.model,v.color,v.plate FROM vehicles v JOIN users u ON u.id=v.owner_id WHERE v.owner_id=? AND v.id=? AND v.deleted_at IS NULL AND u.deleted_at IS NULL";
          case "location" ->
              "SELECT rt.id,rt.region,rt.meeting,rt.origin_address,rt.destination_address,rt.origin_lat,rt.origin_lng,rt.destination_lat,rt.destination_lng,rt.distance_m FROM ride_routes rt JOIN users u ON u.id=rt.driver_id WHERE rt.driver_id=? AND rt.id=? AND u.deleted_at IS NULL AND rt.details_retired_at IS NULL";
          case "notifications" ->
              "SELECT id,title,body,route,created_at FROM notifications WHERE user_id=? AND id=?";
          case "driver_application" ->
              "SELECT a.id,a.vehicle_id,a.status,CASE WHEN "
                  + PrivacyDriverApplicationRetention.REVIEW_CURRENT
                  + " THEN a.decision END AS decision,CASE WHEN "
                  + PrivacyDriverApplicationRetention.REVIEW_CURRENT
                  + " THEN a.reviewed_by END AS reviewed_by,a.created_at::text AS created_at,a.reviewed_at::text AS reviewed_at FROM driver_applications a WHERE a.user_id=? AND a.id=?";
          case "security_audit" ->
              "SELECT a.id,a.actor_id,a.action,a.resource_type,a.resource_id,a.request_id,a.metadata::text AS metadata_json,a.created_at::text AS created_at FROM audit_logs a WHERE a.actor_id=? AND a.id=? AND privacy_security_audit_deadline(a)>clock_timestamp()";
          default -> null;
        };
    if (query == null) return;
    var values = sql.queryForList(query, owner, resource);
    if (values.isEmpty()) return;
    UUID record = UUID.randomUUID();
    sql.update(
        "INSERT INTO privacy_retained_records(id,user_id,category,resource_type,resource_id,content_ciphertext,reason,legal_basis,delete_after,hold_only) VALUES(?,?,?,?,?,?,'Cópia delimitada do recurso disponível ao iniciar retenção específica.',?,?,true)",
        record,
        owner,
        category,
        type,
        resource,
        evidence.seal(
            PrivacyEvidenceCrypto.Field.RETAINED_CONTENT, owner, record, encode(values.getFirst())),
        basis,
        Timestamp.from(expires));
  }

  private void baseline(Map<String, Object> row, UUID administrator) {
    UUID id = (UUID) row.get("id");
    if (Boolean.TRUE.equals(
        sql.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM privacy_hold_events WHERE hold_id=?)", Boolean.class, id)))
      return;
    UUID owner = (UUID) row.get("user_id");
    String clear =
        evidence.open(
            PrivacyEvidenceCrypto.Field.HOLD_REASON,
            owner,
            id,
            (String) row.get("reason_ciphertext"));
    var original = read(clear);
    var value = new LinkedHashMap<String, Object>();
    value.put("reason", original.get("reason"));
    value.put("caseReference", original.get("caseReference"));
    value.put("reviewAt", ((Timestamp) row.get("review_at")).toInstant().toString());
    value.put("expiresAt", ((Timestamp) row.get("expires_at")).toInstant().toString());
    value.put("originalCreatedAt", ((Timestamp) row.get("created_at")).toInstant().toString());
    event(
        id,
        owner,
        ((Number) row.get("revision")).intValue(),
        "legacy_snapshot",
        administrator,
        encode(value));
  }

  private void event(
      UUID hold, UUID owner, int revision, String action, UUID administrator, String body) {
    UUID id = UUID.randomUUID();
    sql.update(
        "INSERT INTO privacy_hold_events(id,hold_id,user_id,revision,action,body_ciphertext,created_by) VALUES(?,?,?,?,?,?,?)",
        id,
        hold,
        owner,
        revision,
        action,
        evidence.seal(PrivacyEvidenceCrypto.Field.HOLD_EVENT_REASON, owner, id, body),
        administrator);
  }

  private Map<String, Object> find(UUID id) {
    return sql.queryForList("SELECT * FROM privacy_legal_holds WHERE id=?", id).stream()
        .findFirst()
        .orElseThrow(
            () -> new ApiException("PRIVACY_HOLD_NOT_FOUND", "Retenção não encontrada.", 404));
  }

  private PrivacyHoldDto.Item get(UUID id) {
    return sql.queryForObject(
        "SELECT *," + STATE + " AS hold_status FROM privacy_legal_holds WHERE id=?",
        (r, n) -> item(r),
        id);
  }

  private void lock(Actor actor, String expectedHash, UUID owner) {
    actors.lockUsers(List.of(actor.id(), owner));
    ApiException.require(
        Boolean.TRUE.equals(
            sql.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM users u JOIN sessions s ON s.user_id=u.id WHERE u.id=? AND u.role='admin' AND u.account_status='active' AND u.deleted_at IS NULL AND u.password_hash=? AND s.id=? AND s.revoked_at IS NULL AND s.access_expires_at>now())",
                Boolean.class,
                actor.id(),
                expectedHash,
                actor.sessionId())),
        "REAUTHENTICATION_EXPIRED",
        "A sessão ou a segurança da conta mudou. Entre novamente.",
        409);
  }

  private void windows(Instant review, Instant expires) {
    Instant at = now();
    ApiException.require(
        review != null
            && expires != null
            && review.isAfter(at)
            && !review.isAfter(at.plus(Duration.ofDays(90)))
            && !expires.isBefore(review)
            && !expires.isAfter(at.plus(Duration.ofDays(365))),
        "PRIVACY_HOLD_DATES",
        "Defina a revisão nos próximos 90 dias e o vencimento em até 365 dias, depois da revisão. A extensão exige nova decisão fundamentada.",
        422);
  }

  private Instant now() {
    return sql.queryForObject("SELECT now()", Timestamp.class).toInstant();
  }

  private String privateDigest(String reason, String reference) {
    return crypto.hmac("privacy-hold-v1|" + encode(List.of(reason, reference)), auth.pepper());
  }

  private String body(String reason, String reference, Instant review, Instant expires) {
    return encode(
        Map.of(
            "reason",
            reason,
            "caseReference",
            reference,
            "reviewAt",
            review.toString(),
            "expiresAt",
            expires.toString()));
  }

  private String encode(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (Exception invalid) {
      throw new IllegalStateException("Could not protect hold evidence.");
    }
  }

  private Map<String, String> read(String value) {
    try {
      var parsed = json.readTree(value);
      if (parsed.isObject() && parsed.path("reason").isTextual()) {
        var result = new HashMap<String, String>();
        for (String field : List.of("reason", "caseReference", "reviewAt", "expiresAt"))
          result.put(field, parsed.path(field).isTextual() ? parsed.path(field).asText() : null);
        return result;
      }
    } catch (Exception ignored) {
    }
    var legacy = new HashMap<String, String>();
    legacy.put("reason", value);
    legacy.put("caseReference", null);
    return legacy;
  }

  private static PrivacyHoldDto.Item item(ResultSet r) throws SQLException {
    return new PrivacyHoldDto.Item(
        r.getObject("id", UUID.class),
        r.getObject("user_id", UUID.class),
        r.getString("category"),
        r.getString("resource_type"),
        r.getObject("resource_id", UUID.class),
        r.getString("legal_basis"),
        r.getInt("revision"),
        r.getString("hold_status"),
        date(r, "created_at"),
        date(r, "reviewed_at"),
        date(r, "review_at"),
        date(r, "expires_at"),
        date(r, "released_at"),
        "review_due".equals(r.getString("hold_status")));
  }

  private static String date(ResultSet r, String column) throws SQLException {
    Timestamp value = r.getTimestamp(column);
    return value == null ? null : ApiDates.timestamp(value.toInstant());
  }
}
