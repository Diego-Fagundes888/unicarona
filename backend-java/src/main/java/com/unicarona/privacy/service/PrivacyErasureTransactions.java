package com.unicarona.privacy.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.unicarona.privacy.config.PrivacySettings;
import com.unicarona.shared.repository.AuditRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PrivacyErasureTransactions {
  public record Task(
      UUID closure, UUID owner, Instant prepared, Instant suppressionUntil, String status) {}

  public record File(UUID id, String key) {}

  private final JdbcTemplate sql;
  private final PrivacyClosureTransactions closures;
  private final PrivacySettings privacy;
  private final PrivacyEvidenceCrypto evidence;
  private final ObjectMapper json;
  private final AuditRepository audit;
  private final PrivacyClosureMail mail;
  private final PrivacyHoldRecovery recovery;

  public PrivacyErasureTransactions(
      JdbcTemplate sql,
      PrivacyClosureTransactions closures,
      PrivacySettings privacy,
      PrivacyEvidenceCrypto evidence,
      ObjectMapper json,
      AuditRepository audit,
      PrivacyClosureMail mail,
      PrivacyHoldRecovery recovery) {
    this.sql = sql;
    this.closures = closures;
    this.privacy = privacy;
    this.evidence = evidence;
    this.json = json;
    this.audit = audit;
    this.mail = mail;
    this.recovery = recovery;
  }

  public List<UUID> due(int batch) {
    return sql.query(
        "SELECT id FROM privacy_account_closures WHERE status IN ('scheduled','pending_obligations') AND erase_after<=now() ORDER BY erase_after,id LIMIT ?",
        (r, n) -> r.getObject(1, UUID.class),
        batch);
  }

  @Transactional
  public boolean prepare(UUID closure) {
    var refs = sql.queryForList("SELECT user_id FROM privacy_account_closures WHERE id=?", closure);
    if (refs.isEmpty()) return false;
    UUID owner = (UUID) refs.getFirst().get("user_id");
    lockOwner(owner);
    var rows =
        sql.queryForList(
            "SELECT status,erase_after<=now() AS due FROM privacy_account_closures WHERE id=? FOR UPDATE",
            closure);
    var row = rows.getFirst();
    if (!Set.of("scheduled", "pending_obligations").contains(row.get("status"))
        || !Boolean.TRUE.equals(row.get("due"))) return false;
    if (closures.obligations(owner).stream()
        .anyMatch(com.unicarona.privacy.dto.PrivacyDto.Obligation::delaysErasure)) {
      sql.update(
          "UPDATE privacy_account_closures SET status='pending_obligations' WHERE id=?", closure);
      return false;
    }
    sql.update("UPDATE privacy_account_closures SET status='erasing' WHERE id=?", closure);
    sql.update("UPDATE users SET account_status='deleted',updated_at=now() WHERE id=?", owner);
    sql.update("UPDATE sessions SET revoked_at=coalesce(revoked_at,now()) WHERE user_id=?", owner);
    sql.update(
        "UPDATE auth_tokens SET used_at=coalesce(used_at,now()),invalidated_at=coalesce(invalidated_at,now()) WHERE user_id=?",
        owner);
    sql.update(
        "INSERT INTO privacy_erasure_tasks(closure_id,user_id,status,suppression_until) VALUES(?,?,'prepared',now()+(?+2)*interval '1 day') ON CONFLICT DO NOTHING",
        closure,
        owner,
        privacy.backupRetentionDays());
    mail.capture(owner, closure);
    audit.record(null, "account_erasure_started", "privacy_account_closure", closure, Map.of());
    return true;
  }

  public List<Task> tasks(int batch) {
    return sql.query(
        "SELECT t.* FROM privacy_erasure_tasks t WHERE t.status<>'completed' AND t.next_attempt_at<=now() ORDER BY t.next_attempt_at,t.closure_id LIMIT ?",
        (r, n) ->
            new Task(
                r.getObject("closure_id", UUID.class),
                r.getObject("user_id", UUID.class),
                r.getTimestamp("prepared_at").toInstant(),
                r.getTimestamp("suppression_until").toInstant(),
                r.getString("status")),
        batch);
  }

  public boolean needsProfileReplay(UUID owner) {
    return PrivacyProfileSuppression.needsScrub(sql, owner)
        || Boolean.TRUE.equals(
            sql.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM users u WHERE u.id=? AND (EXISTS(SELECT 1 FROM privacy_erasure_tasks t WHERE t.user_id=u.id AND t.status<>'completed') OR EXISTS(SELECT 1 FROM stored_files f WHERE f.owner_id=u.id AND (f.deleted_at IS NULL OR f.erasure_requested_at IS NULL OR ((f.erased_at IS NULL OR f.sha256<>'erased') AND NOT EXISTS(SELECT 1 FROM privacy_legal_holds h WHERE h.user_id=u.id AND h.category='files' AND h.released_at IS NULL AND h.expires_at>now() AND (h.resource_id IS NULL OR h.resource_id=f.id)))))))",
                Boolean.class,
                owner));
  }

  public List<String> erasedFileKeys(UUID owner) {
    return sql.query(
        "SELECT storage_key FROM stored_files WHERE owner_id=? AND erased_at IS NOT NULL",
        (r, n) -> r.getString(1),
        owner);
  }

  @Transactional
  public Task restore(PrivacyErasureJournal.Entry entry) {
    var users = sql.queryForList("SELECT id FROM users WHERE id=?", entry.owner());
    if (users.isEmpty()) return null;
    lockOwner(entry.owner());
    var existing =
        sql.queryForList(
            "SELECT user_id FROM privacy_account_closures WHERE id=?", entry.closure());
    if (!existing.isEmpty() && !entry.owner().equals(existing.getFirst().get("user_id")))
      throw new IllegalStateException("Erasure restore identity conflict.");
    // A later signed irreversible request supersedes an older request present in an old backup.
    sql.update(
        "UPDATE privacy_account_closures SET status='cancelled',cancelled_at=now(),obligations_snapshot=obligations_snapshot||jsonb_build_object('restoreSupersededBy',?::text) WHERE user_id=? AND id<>? AND status IN ('scheduled','pending_obligations','erasing')",
        entry.closure().toString(),
        entry.owner(),
        entry.closure());
    sql.update(
        "INSERT INTO privacy_account_closures(id,user_id,initiated_by,status,previous_account_status,requested_at,reauthenticated_at,erase_after,policy_version) VALUES(?,?,'platform','erasing','active',?,?,?,'restore-suppression-v1') ON CONFLICT(id) DO UPDATE SET status='erasing',completed_at=NULL,cancelled_at=NULL",
        entry.closure(),
        entry.owner(),
        Timestamp.from(entry.preparedAt()),
        Timestamp.from(entry.preparedAt()),
        Timestamp.from(entry.preparedAt()));
    mail.suppressRestored(entry.owner());
    boolean scrub = PrivacyProfileSuppression.needsScrub(sql, entry.owner());
    sql.update(
        "UPDATE users SET account_status='deleted',updated_at=now() WHERE id=?", entry.owner());
    sql.update(
        "UPDATE sessions SET revoked_at=coalesce(revoked_at,now()) WHERE user_id=?", entry.owner());
    sql.update(
        "INSERT INTO privacy_erasure_tasks(closure_id,user_id,status,prepared_at,suppression_until,journaled_at,scrubbed_at) VALUES(?,?,?, ?,?,now(),CASE WHEN ? THEN NULL ELSE now() END) ON CONFLICT(closure_id) DO UPDATE SET status=excluded.status,journaled_at=now(),scrubbed_at=excluded.scrubbed_at,completed_at=NULL,next_attempt_at=now()",
        entry.closure(),
        entry.owner(),
        scrub ? "prepared" : "scrubbed",
        Timestamp.from(entry.preparedAt()),
        Timestamp.from(entry.suppressionUntil()),
        scrub);
    // A file backup can reintroduce bytes even if its database snapshot already records erasure.
    sql.update(
        "UPDATE stored_files SET deleted_at=coalesce(deleted_at,now()),erasure_requested_at=coalesce(erasure_requested_at,now()),erased_at=NULL WHERE owner_id=?",
        entry.owner());
    audit.record(
        null,
        "privacy_restore_suppression_applied",
        "privacy_account_closure",
        entry.closure(),
        Map.of("profileScrubRequired", scrub));
    return new Task(
        entry.closure(),
        entry.owner(),
        entry.preparedAt(),
        entry.suppressionUntil(),
        scrub ? "prepared" : "scrubbed");
  }

  @Transactional
  public void scrub(Task task) {
    PrivacyRetentionLock.acquire(sql);
    lockOwner(task.owner());
    var rows =
        sql.queryForList(
            "SELECT status FROM privacy_erasure_tasks WHERE closure_id=? FOR UPDATE",
            task.closure());
    if (rows.isEmpty() || !"prepared".equals(rows.getFirst().get("status"))) return;
    boolean retained =
        Boolean.TRUE.equals(
            sql.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM payments WHERE passenger_id=? AND (paid_at IS NOT NULL OR status IN ('awaiting','pending','refund_pending')) UNION ALL SELECT 1 FROM parcels WHERE passenger_id=? OR driver_id=? UNION ALL SELECT 1 FROM transfers WHERE user_id=? UNION ALL SELECT 1 FROM occurrences WHERE reporter_id=? OR other_id=? UNION ALL SELECT 1 FROM privacy_requests WHERE user_id=? AND resolved_at IS NULL UNION ALL SELECT 1 FROM ledger_accounts a JOIN ledger_entries e ON e.account_id=a.id WHERE a.owner_id=? GROUP BY a.id HAVING sum(e.cents)<>0)",
                Boolean.class,
                task.owner(),
                task.owner(),
                task.owner(),
                task.owner(),
                task.owner(),
                task.owner(),
                task.owner(),
                task.owner()));
    if (retained) {
      Timestamp deadline =
          Timestamp.from(
              task.prepared().atOffset(java.time.ZoneOffset.UTC).plusYears(5).toInstant());
      var identity = sql.queryForMap("SELECT name,email,phone FROM users WHERE id=?", task.owner());
      if (!autonomousCopy(task.owner(), "contract_identity", task.owner())
          && (!"Conta excluída".equals(identity.get("name"))
              || !("deleted-" + task.owner() + "@deleted.invalid").equals(identity.get("email"))
              || identity.get("phone") != null))
        retain(
            task.owner(),
            "contract_identity",
            "user",
            task.owner(),
            identity,
            "Identificação mínima para obrigações financeiras, segurança e exercício regular de direitos.",
            deadline);
      for (var vehicle :
          sql.queryForList(
              "SELECT v.id,v.model,v.color,v.plate FROM vehicles v WHERE v.owner_id=? AND (v.model<>'Veículo indisponível' OR v.color<>'Indisponível' OR v.plate<>'XXX0X00') AND EXISTS(SELECT 1 FROM rides r JOIN ride_reservations rr ON rr.ride_id=r.id WHERE r.vehicle_id=v.id)",
              task.owner()))
        if (!autonomousCopy(task.owner(), "vehicle_identity", (UUID) vehicle.get("id")))
          retain(
              task.owner(),
              "vehicle_identity",
              "vehicle",
              (UUID) vehicle.get("id"),
              vehicle,
              "Identificação do veículo de contratos pagos para defesa e apuração de ocorrências.",
              deadline);
    }
    var identityHolds =
        sql.queryForList(
            "SELECT max(expires_at) AS until FROM privacy_legal_holds WHERE user_id=? AND category IN ('contract_identity','financial') AND released_at IS NULL AND expires_at>now() AND (resource_id IS NULL OR resource_id=?) HAVING count(*)>0",
            task.owner(),
            task.owner());
    if (!retained
        && !identityHolds.isEmpty()
        && !retainedCopy(task.owner(), "contract_identity", task.owner()))
      retain(
          task.owner(),
          "contract_identity",
          "user",
          task.owner(),
          sql.queryForMap("SELECT name,email,phone FROM users WHERE id=?", task.owner()),
          "Identificação mínima sob retenção específica, fundamentada e com vencimento.",
          (Timestamp) identityHolds.getFirst().get("until"),
          true);
    for (var vehicle :
        sql.queryForList(
            "SELECT v.id,v.model,v.color,v.plate,max(h.expires_at) AS privacy_until FROM vehicles v JOIN privacy_legal_holds h ON h.user_id=v.owner_id AND h.category='vehicle_identity' AND h.resource_id=v.id AND h.released_at IS NULL AND h.expires_at>now() WHERE v.owner_id=? AND NOT EXISTS(SELECT 1 FROM privacy_retained_records p WHERE p.user_id=v.owner_id AND p.category='vehicle_identity' AND p.resource_id=v.id) GROUP BY v.id",
            task.owner())) {
      var data = new LinkedHashMap<>(vehicle);
      Timestamp until = (Timestamp) data.remove("privacy_until");
      retain(
          task.owner(),
          "vehicle_identity",
          "vehicle",
          (UUID) vehicle.get("id"),
          data,
          "Identificação do veículo sob retenção específica e com vencimento.",
          until,
          true);
    }
    var profileHolds =
        sql.queryForList(
            "SELECT max(expires_at) AS until FROM privacy_legal_holds WHERE user_id=? AND category='profile' AND released_at IS NULL AND expires_at>now() AND (resource_id IS NULL OR resource_id=?) HAVING count(*)>0",
            task.owner(),
            task.owner());
    if (!profileHolds.isEmpty() && !retainedCopy(task.owner(), "profile", task.owner()))
      retain(
          task.owner(),
          "profile",
          "user",
          task.owner(),
          sql.queryForMap("SELECT name,email,birth_date,phone FROM users WHERE id=?", task.owner()),
          "Dados de identificação sob retenção específica, fundamentada e com vencimento.",
          (Timestamp) profileHolds.getFirst().get("until"),
          true);
    for (var route :
        sql.queryForList(
            """
            SELECT rt.*,
              CASE WHEN EXISTS(SELECT 1 FROM rides r JOIN ride_reservations rr ON rr.ride_id=r.id JOIN occurrences o ON o.reservation_id=rr.id WHERE r.route_id=rt.id AND o.status<>'resolved') THEN ?::timestamptz+interval '5 years' WHEN EXISTS(SELECT 1 FROM rides r JOIN ride_reservations rr ON rr.ride_id=r.id WHERE r.route_id=rt.id) THEN ?::timestamptz+interval '180 days' ELSE now()+interval '1 second' END AS privacy_until,
              NOT EXISTS(SELECT 1 FROM rides r JOIN ride_reservations rr ON rr.ride_id=r.id WHERE r.route_id=rt.id) AS privacy_hold_only
            FROM ride_routes rt WHERE rt.driver_id=? AND (rt.region<>'Região removida' OR rt.meeting<>'Detalhes removidos' OR rt.origin_address IS NOT NULL OR rt.destination_address IS NOT NULL OR rt.origin_lat IS NOT NULL OR rt.origin_lng IS NOT NULL OR rt.destination_lat IS NOT NULL OR rt.destination_lng IS NOT NULL) AND (
              EXISTS(SELECT 1 FROM rides r JOIN ride_reservations rr ON rr.ride_id=r.id WHERE r.route_id=rt.id) OR
              EXISTS(SELECT 1 FROM privacy_legal_holds h WHERE h.user_id=rt.driver_id AND h.category='location' AND h.released_at IS NULL AND h.expires_at>now() AND (h.resource_id IS NULL OR h.resource_id=rt.id)))
            """,
            Timestamp.from(task.prepared()),
            Timestamp.from(task.prepared()),
            task.owner())) {
      var evidence = new LinkedHashMap<>(route);
      Timestamp until = (Timestamp) evidence.remove("privacy_until");
      boolean holdOnly = Boolean.TRUE.equals(evidence.remove("privacy_hold_only"));
      if (holdOnly
          ? !retainedCopy(task.owner(), "location", (UUID) route.get("id"))
          : !autonomousCopy(task.owner(), "location", (UUID) route.get("id")))
        retain(
            task.owner(),
            "location",
            "ride_route",
            (UUID) route.get("id"),
            evidence,
            "Rota para apuração e defesa, com prazo próprio ou retenção específica quando houver disputa.",
            until,
            holdOnly);
    }
    // UUIDs in financial contracts remain pseudonymous, not falsely described as anonymous.
    sql.update(
        "UPDATE users SET name='Conta excluída',email='deleted-'||id::text||'@deleted.invalid',phone=NULL,photo_file_id=NULL,birth_date=NULL,voluntarily_deactivated_at=NULL,password_hash='disabled',deleted_at=coalesce(deleted_at,now()),account_status='deleted',updated_at=now() WHERE id=?",
        task.owner());
    sql.update("DELETE FROM student_profiles WHERE user_id=?", task.owner());
    sql.update("DELETE FROM driver_profiles WHERE user_id=?", task.owner());
    sql.update(
        "UPDATE stored_files SET deleted_at=coalesce(deleted_at,now()),erasure_requested_at=coalesce(erasure_requested_at,now()) WHERE owner_id=?",
        task.owner());
    sql.update(
        "UPDATE vehicles SET model='Veículo indisponível',color='Indisponível',plate='XXX0X00',status='retired',deleted_at=coalesce(deleted_at,now()),updated_at=now() WHERE owner_id=?",
        task.owner());
    sql.update(
        "UPDATE driver_applications SET decision=NULL,reviewed_by=NULL,identity_file_id=NULL,license_file_id=NULL,documents_retired_at=coalesce(documents_retired_at,now()),status=CASE WHEN status='pending' THEN 'expired' ELSE status END WHERE user_id=?",
        task.owner());
    sql.update(
        "UPDATE ride_routes SET region='Região removida',meeting='Detalhes removidos',origin_address=NULL,destination_address=NULL,origin_lat=NULL,origin_lng=NULL,destination_lat=NULL,destination_lng=NULL WHERE driver_id=?",
        task.owner());
    sql.update("UPDATE rides SET notes='',proposal_reason=NULL WHERE driver_id=?", task.owner());
    sql.update(
        "UPDATE routines SET notes='',status='ended',updated_at=now() WHERE driver_id=?",
        task.owner());
    // The server-generated monthly snapshot contains IDs, schedule and prices, not a public
    // profile.
    // Preserve that contract evidence; free-text reasons and profile/location fields are treated
    // separately.
    sql.update(
        "UPDATE ride_requests SET reason=NULL WHERE passenger_id=? OR driver_id=?",
        task.owner(),
        task.owner());
    sql.update(
        "UPDATE ride_reservations SET absence_reason=NULL,cancel_reason=NULL WHERE passenger_id=? OR driver_id=?",
        task.owner(),
        task.owner());
    sql.update(
        "UPDATE ratings SET comment=NULL WHERE by_id=? OR other_id=?", task.owner(), task.owner());
    for (var notice :
        sql.queryForList(
            "SELECT n.*,max(h.expires_at) AS privacy_until FROM notifications n JOIN privacy_legal_holds h ON h.user_id=n.user_id AND h.category='notifications' AND h.released_at IS NULL AND h.expires_at>now() AND (h.resource_id IS NULL OR h.resource_id=n.id) WHERE n.user_id=? GROUP BY n.id",
            task.owner())) {
      if (retainedCopy(task.owner(), "notifications", (UUID) notice.get("id"))) continue;
      var data = new LinkedHashMap<>(notice);
      Timestamp until = (Timestamp) data.remove("privacy_until");
      retain(
          task.owner(),
          "notifications",
          "notification",
          (UUID) notice.get("id"),
          data,
          "Aviso sob retenção específica e com vencimento.",
          until,
          true);
    }
    sql.update("DELETE FROM notifications WHERE user_id=?", task.owner());
    sql.update("DELETE FROM user_blocks WHERE by_id=? OR other_id=?", task.owner(), task.owner());
    sql.update("DELETE FROM waitlist WHERE user_id=?", task.owner());
    // Deleting the complete family in one statement preserves self-reference consistency.
    sql.update("DELETE FROM sessions WHERE user_id=?", task.owner());
    for (var change :
        sql.queryForList(
            "SELECT c.*, (SELECT max(h.expires_at) FROM privacy_legal_holds h WHERE h.user_id=c.user_id AND h.category='identity_change' AND h.released_at IS NULL AND h.expires_at>now() AND (h.resource_id IS NULL OR h.resource_id=c.id)) AS privacy_until FROM privacy_email_changes c WHERE c.user_id=? AND EXISTS(SELECT 1 FROM privacy_legal_holds h WHERE h.user_id=c.user_id AND h.category='identity_change' AND h.released_at IS NULL AND h.expires_at>now() AND (h.resource_id IS NULL OR h.resource_id=c.id))",
            task.owner())) {
      if (retainedCopy(task.owner(), "identity_change", (UUID) change.get("id"))) continue;
      var retainedIdentity = new LinkedHashMap<>(change);
      Timestamp until = (Timestamp) retainedIdentity.remove("privacy_until");
      retainedIdentity.remove("credential_hmac");
      retainedIdentity.remove("token_id");
      retainedIdentity.remove("eligible");
      Object target = retainedIdentity.remove("new_email_ciphertext");
      if (target != null)
        retainedIdentity.put(
            "new_email",
            evidence.open(
                PrivacyEvidenceCrypto.Field.EMAIL_TARGET,
                task.owner(),
                (UUID) change.get("id"),
                (String) target));
      retain(
          task.owner(),
          "identity_change",
          "identity_change",
          (UUID) change.get("id"),
          retainedIdentity,
          "Alteração de identificação sob retenção específica e com vencimento.",
          until,
          true);
    }
    sql.update(
        "UPDATE outbox_jobs SET payload='{}',status=CASE WHEN status IN ('pending','processing') THEN 'done' ELSE status END,completed_at=coalesce(completed_at,now()),leased_until=NULL WHERE type='email' AND payload->>'ownerId'=?",
        task.owner().toString());
    sql.update("DELETE FROM privacy_email_changes WHERE user_id=?", task.owner());
    sql.update(
        "UPDATE outbox_jobs o SET payload='{}',status=CASE WHEN status IN ('pending','processing') THEN 'done' ELSE status END,completed_at=coalesce(completed_at,now()),leased_until=NULL FROM auth_tokens t WHERE t.user_id=? AND o.event_key='auth:'||t.id::text",
        task.owner());
    sql.update("DELETE FROM auth_tokens WHERE user_id=?", task.owner());
    sql.update("DELETE FROM idempotency_keys WHERE actor_id=?", task.owner());
    sql.update("DELETE FROM privacy_preferences WHERE user_id=?", task.owner());
    sql.update(
        "UPDATE legal_acceptances SET retain_until=greatest(retain_until,?::timestamptz+interval '5 years') WHERE user_id=?",
        Timestamp.from(task.prepared()),
        task.owner());
    sql.update(
        "INSERT INTO privacy_erasure_events(user_id,closure_id,suppression_until,original_prepared_at) VALUES(?,?,?,?) ON CONFLICT(closure_id) DO NOTHING",
        task.owner(),
        task.closure(),
        Timestamp.from(task.suppressionUntil()),
        Timestamp.from(task.prepared()));
    sql.update(
        "UPDATE privacy_erasure_tasks SET status='scrubbed',journaled_at=now(),scrubbed_at=now(),last_error_code=NULL WHERE closure_id=?",
        task.closure());
    audit.record(
        null,
        "account_profile_erased",
        "privacy_account_closure",
        task.closure(),
        Map.of("financialIdentityRetained", retained));
    recovery.stage();
  }

  private void retain(
      UUID user, String category, String resourceType, UUID resource, Object value, String reason) {
    retain(user, category, resourceType, resource, value, reason, null);
  }

  private boolean retainedCopy(UUID owner, String category, UUID resource) {
    return Boolean.TRUE.equals(
        sql.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM privacy_retained_records WHERE user_id=? AND category=? AND resource_id=?)",
            Boolean.class,
            owner,
            category,
            resource));
  }

  private boolean autonomousCopy(UUID owner, String category, UUID resource) {
    return Boolean.TRUE.equals(
        sql.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM privacy_retained_records WHERE user_id=? AND category=? AND resource_id=? AND NOT hold_only)",
            Boolean.class,
            owner,
            category,
            resource));
  }

  private void retain(
      UUID user,
      String category,
      String resourceType,
      UUID resource,
      Object value,
      String reason,
      Timestamp until) {
    retain(user, category, resourceType, resource, value, reason, until, false);
  }

  private void retain(
      UUID user,
      String category,
      String resourceType,
      UUID resource,
      Object value,
      String reason,
      Timestamp until,
      boolean holdOnly) {
    if (PrivacyContractIdentityPurpose.applies(category, holdOnly)) {
      Timestamp last =
          sql.queryForObject(
              "SELECT max(happened) FROM privacy_identity_purpose_facts(?,?,?)",
              Timestamp.class,
              user,
              category,
              resource);
      if (last != null) {
        Timestamp defence =
            Timestamp.from(
                last.toInstant().atOffset(java.time.ZoneOffset.UTC).plusYears(5).toInstant());
        if (until == null || defence.after(until)) until = defence;
      }
      Timestamp held =
          sql.queryForObject(
              "SELECT max(expires_at) FROM privacy_legal_holds WHERE user_id=? AND released_at IS NULL AND expires_at>now() AND (category=? OR category='financial') AND (resource_id IS NULL OR resource_id=?)",
              Timestamp.class,
              user,
              category,
              resource);
      if (held != null && (until == null || held.after(until))) until = held;
      if (until != null
          && !until.after(sql.queryForObject("SELECT now()", Timestamp.class))
          && !Boolean.TRUE.equals(
              sql.queryForObject(
                  "SELECT privacy_identity_purpose_open(?,?,?)",
                  Boolean.class,
                  user,
                  category,
                  resource))) return;
    }
    try {
      UUID id = UUID.randomUUID();
      sql.update(
          "INSERT INTO privacy_retained_records(id,user_id,category,resource_type,resource_id,content_ciphertext,reason,legal_basis,delete_after,hold_only) VALUES(?,?,?,?,?,?,?,'regular_exercise_of_rights',coalesce(?::timestamptz,now()+interval '5 years'),?)",
          id,
          user,
          category,
          resourceType,
          resource,
          evidence.seal(
              PrivacyEvidenceCrypto.Field.RETAINED_CONTENT,
              user,
              id,
              json.writeValueAsString(value)),
          reason,
          until,
          holdOnly);
    } catch (JsonProcessingException error) {
      throw new IllegalStateException("Could not protect retained evidence.");
    }
  }

  public List<File> files(int batch) {
    return sql.query(
        "SELECT f.id,f.storage_key FROM stored_files f WHERE f.erasure_requested_at IS NOT NULL AND f.erased_at IS NULL AND f.erasure_retry_after<=now() AND NOT EXISTS(SELECT 1 FROM privacy_legal_holds h WHERE h.user_id=f.owner_id AND h.released_at IS NULL AND h.expires_at>now() AND (h.category='files' AND (h.resource_id IS NULL OR h.resource_id=f.id))) ORDER BY f.erasure_retry_after,f.id LIMIT ?",
        (r, n) -> new File(r.getObject("id", UUID.class), r.getString("storage_key")),
        batch);
  }

  public List<File> restoreFiles(UUID owner) {
    return sql.query(
        "SELECT f.id,f.storage_key FROM stored_files f WHERE f.owner_id=? AND f.erasure_requested_at IS NOT NULL AND f.erased_at IS NULL AND NOT EXISTS(SELECT 1 FROM privacy_legal_holds h WHERE h.user_id=f.owner_id AND h.released_at IS NULL AND h.expires_at>now() AND h.category='files' AND (h.resource_id IS NULL OR h.resource_id=f.id)) ORDER BY f.id",
        (r, n) -> new File(r.getObject("id", UUID.class), r.getString("storage_key")),
        owner);
  }

  public List<File> recordedErasedFiles(UUID after, int batch) {
    return sql.query(
        "SELECT id,storage_key FROM stored_files WHERE erased_at IS NOT NULL AND (?::uuid IS NULL OR id>?::uuid) ORDER BY id LIMIT ?",
        (r, n) -> new File(r.getObject(1, UUID.class), r.getString(2)),
        after,
        after,
        batch);
  }

  public List<File> recordedErasedFilesDue(int batch) {
    return sql.query(
        "SELECT id,storage_key FROM stored_files WHERE erased_at IS NOT NULL AND erasure_last_scan_at<now()-interval '1 hour' ORDER BY erasure_last_scan_at,id LIMIT ?",
        (r, n) -> new File(r.getObject(1, UUID.class), r.getString(2)),
        batch);
  }

  @Transactional
  public void recordedFileChecked(File file) {
    sql.update(
        "UPDATE stored_files SET erasure_last_scan_at=now() WHERE id=? AND erased_at IS NOT NULL",
        file.id());
  }

  @Transactional
  public void fileFailed(File file) {
    sql.update(
        "UPDATE stored_files SET erasure_attempts=erasure_attempts+1,erasure_retry_after=now()+interval '5 minutes' WHERE id=?",
        file.id());
    sql.update(
        "UPDATE privacy_erasure_tasks t SET last_error_code='FILE_ERASURE_RETRY' FROM stored_files f WHERE f.id=? AND t.user_id=f.owner_id AND t.status<>'completed'",
        file.id());
  }

  @Transactional
  public boolean complete(Task task) {
    PrivacyRetentionLock.acquire(sql);
    lockOwner(task.owner());
    var states =
        sql.queryForList(
            "SELECT status FROM privacy_erasure_tasks WHERE closure_id=? FOR UPDATE",
            task.closure());
    if (states.isEmpty() || !"scrubbed".equals(states.getFirst().get("status"))) return false;
    boolean pending =
        Boolean.TRUE.equals(
            sql.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM stored_files f WHERE f.owner_id=? AND f.erasure_requested_at IS NOT NULL AND f.erased_at IS NULL AND NOT EXISTS(SELECT 1 FROM privacy_legal_holds h WHERE h.user_id=f.owner_id AND h.released_at IS NULL AND h.expires_at>now() AND h.category='files' AND (h.resource_id IS NULL OR h.resource_id=f.id)))",
                Boolean.class,
                task.owner()));
    if (pending) return false;
    sql.update(
        "UPDATE privacy_erasure_tasks SET status='completed',completed_at=now(),last_error_code=NULL WHERE closure_id=?",
        task.closure());
    sql.update(
        "UPDATE privacy_account_closures SET status='completed',completed_at=now() WHERE id=? AND status='erasing'",
        task.closure());
    long retainedFiles =
        sql.queryForObject(
            "SELECT count(*) FROM stored_files WHERE owner_id=? AND erasure_requested_at IS NOT NULL AND erased_at IS NULL",
            Long.class,
            task.owner());
    audit.record(
        null,
        "account_erasure_completed",
        "privacy_account_closure",
        task.closure(),
        Map.of(
            "scope", "profile_and_unheld_files", "retainedFileCountAtCompletion", retainedFiles));
    return true;
  }

  @Transactional
  public void failed(UUID closure) {
    sql.update(
        "UPDATE privacy_erasure_tasks SET attempts=attempts+1,last_error_code='ERASURE_RETRY',next_attempt_at=now()+interval '5 minutes' WHERE closure_id=? AND status<>'completed'",
        closure);
  }

  private void lockOwner(UUID owner) {
    sql.execute("SET LOCAL lock_timeout='3s'");
    sql.queryForList("SELECT id FROM users WHERE id=? FOR UPDATE", owner);
  }
}
