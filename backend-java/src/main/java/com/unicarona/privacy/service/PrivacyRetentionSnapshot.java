package com.unicarona.privacy.service;

import static com.unicarona.privacy.service.PrivacyEvidenceCrypto.Field.*;

import com.fasterxml.jackson.databind.*;
import com.unicarona.file.storage.PrivateFileStorage;
import java.io.IOException;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * A closed recovery schema, never a bag of table names or executable SQL. Restores only
 * preservation decisions and private evidence; it cannot recreate an account or a session.
 */
@Service
public class PrivacyRetentionSnapshot {
  record Hold(
      UUID id,
      UUID owner,
      String category,
      String type,
      UUID resource,
      String basis,
      String cipher,
      UUID actor,
      Instant created,
      Instant review,
      Instant expires,
      Instant released,
      int revision,
      Instant reviewed,
      Instant until) {
    @Override
    public String toString() {
      return "Hold[redacted]";
    }
  }

  record Event(
      UUID id,
      UUID hold,
      UUID owner,
      int revision,
      String action,
      String cipher,
      UUID actor,
      Instant created,
      Instant until) {
    @Override
    public String toString() {
      return "HoldEvent[redacted]";
    }
  }

  record Retained(
      UUID id,
      UUID owner,
      String category,
      String type,
      UUID resource,
      String cipher,
      String reason,
      String basis,
      Instant created,
      Instant until,
      boolean holdOnly,
      PrivacyContractIdentityPurpose.Purpose purpose) {
    @Override
    public String toString() {
      return "RetainedEvidence[redacted]";
    }
  }

  record HeldFile(
      UUID id,
      UUID owner,
      String purpose,
      String key,
      String contentType,
      int bytes,
      String sha256,
      Instant created,
      Instant lastUploadedAt,
      Instant deleted,
      Instant requested,
      PrivacyRetainedFileBlobs.Reference blob) {
    @Override
    public String toString() {
      return "HeldFile[redacted]";
    }
  }

  record State(
      int format,
      String kind,
      List<Hold> holds,
      List<Event> events,
      List<Retained> retained,
      List<HeldFile> files) {
    @Override
    public String toString() {
      return "RetentionSnapshot[redacted]";
    }
  }

  private static final String KIND = "privacy-retention-snapshot";
  private static final Map<String, String> TYPES =
      Map.ofEntries(
          Map.entry("profile", "user"),
          Map.entry("contract_identity", "user"),
          Map.entry("vehicle_identity", "vehicle"),
          Map.entry("files", "stored_file"),
          Map.entry("location", "ride_route"),
          Map.entry("identity_change", "identity_change"),
          Map.entry("account_decision", "account_decision"),
          Map.entry("driver_application", "driver_application"),
          Map.entry("privacy_request", "privacy_request"),
          Map.entry("consent", "privacy_consent"),
          Map.entry("legal", "legal_acceptance"),
          Map.entry("notifications", "notification"),
          Map.entry("security_audit", "audit_log"));
  private final JdbcTemplate sql;
  private final PrivacyHoldControlJournal journal;
  private final PrivacyRetainedFileBlobs blobs;
  private final PrivateFileStorage storage;
  private final ObjectMapper json;
  private final PrivacyEvidenceCrypto evidence;
  private final PrivacyContractIdentityPurpose identity;
  private final ObjectReader typed;

  public PrivacyRetentionSnapshot(
      JdbcTemplate sql,
      PrivacyHoldControlJournal journal,
      PrivacyRetainedFileBlobs blobs,
      PrivateFileStorage storage,
      ObjectMapper json,
      PrivacyEvidenceCrypto evidence,
      PrivacyContractIdentityPurpose identity) {
    this.sql = sql;
    this.journal = journal;
    this.blobs = blobs;
    this.storage = storage;
    this.json = json;
    this.evidence = evidence;
    this.identity = identity;
    typed =
        json.copy()
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .readerFor(State.class)
            .with(
                DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,
                DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .without(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
  }

  private static Instant time(ResultSet r, String name) throws SQLException {
    var value = r.getTimestamp(name);
    return value == null ? null : value.toInstant();
  }

  private static Timestamp at(Instant value) {
    return value == null ? null : Timestamp.from(value);
  }

  private List<Hold> holds() {
    return sql.query(
        "SELECT * FROM privacy_legal_holds ORDER BY id LIMIT 10001",
        (r, n) ->
            new Hold(
                r.getObject("id", UUID.class),
                r.getObject("user_id", UUID.class),
                r.getString("category"),
                r.getString("resource_type"),
                r.getObject("resource_id", UUID.class),
                r.getString("legal_basis"),
                r.getString("reason_ciphertext"),
                r.getObject("created_by", UUID.class),
                time(r, "created_at"),
                time(r, "review_at"),
                time(r, "expires_at"),
                time(r, "released_at"),
                r.getInt("revision"),
                time(r, "reviewed_at"),
                time(r, "retain_until")));
  }

  private List<Event> events() {
    return sql.query(
        "SELECT * FROM privacy_hold_events ORDER BY hold_id,revision LIMIT 10001",
        (r, n) ->
            new Event(
                r.getObject("id", UUID.class),
                r.getObject("hold_id", UUID.class),
                r.getObject("user_id", UUID.class),
                r.getInt("revision"),
                r.getString("action"),
                r.getString("body_ciphertext"),
                r.getObject("created_by", UUID.class),
                time(r, "created_at"),
                time(r, "retain_until")));
  }

  private List<Retained> retained() {
    return sql.query(
        "SELECT * FROM privacy_retained_records ORDER BY id LIMIT 10001",
        (r, n) ->
            new Retained(
                r.getObject("id", UUID.class),
                r.getObject("user_id", UUID.class),
                r.getString("category"),
                r.getString("resource_type"),
                r.getObject("resource_id", UUID.class),
                r.getString("content_ciphertext"),
                r.getString("reason"),
                r.getString("legal_basis"),
                time(r, "created_at"),
                time(r, "delete_after"),
                r.getBoolean("hold_only"),
                identity.read(r.getString("financial_purpose"))));
  }

  private static boolean active(Hold h, Instant now) {
    return h.released() == null && h.expires().isAfter(now);
  }

  private static boolean covers(Hold h, Retained r, Instant now) {
    return active(h, now)
        && h.owner().equals(r.owner())
        && (h.category().equals(r.category())
            || ("financial".equals(h.category())
                && Set.of("contract_identity", "vehicle_identity").contains(r.category())))
        && (h.resource() == null || h.resource().equals(r.resource()));
  }

  private boolean needed(Retained r, List<Hold> holds, Instant now) {
    return (!r.holdOnly()
            && (r.until().isAfter(now)
                || (PrivacyContractIdentityPurpose.applies(r.category(), false)
                    && identity.required(r.owner(), r.category(), r.resource(), r.purpose()))))
        || holds.stream().anyMatch(h -> covers(h, r, now));
  }

  private static boolean covered(HeldFile f, List<Hold> holds, Instant now) {
    return holds.stream()
        .anyMatch(
            h ->
                active(h, now)
                    && h.category().equals("files")
                    && h.owner().equals(f.owner())
                    && (h.resource() == null || h.resource().equals(f.id())));
  }

  private Instant now() {
    return sql.queryForObject("SELECT now()", Timestamp.class).toInstant();
  }

  private void require(boolean condition) throws IOException {
    if (!condition)
      throw new IOException(
          "Typed preservation control is invalid or conflicts with its recovery dependencies.");
  }

  private State parse(JsonNode tree) throws IOException {
    JsonNode source = tree;
    require(tree != null && tree.isObject() && tree.path("format").isInt());
    // Format 1 did not record reuploads. Its creation time is the conservative
    // fallback, never the recovery time. Do not accept a mixed legacy shape.
    if (tree != null
        && tree.isObject()
        && tree.path("format").isInt()
        && tree.path("format").asInt() == 1) {
      var converted = ((com.fasterxml.jackson.databind.node.ObjectNode) tree).deepCopy();
      require(converted.path("files").isArray());
      for (JsonNode file : converted.path("files")) {
        require(file.isObject() && !file.has("lastUploadedAt"));
        ((com.fasterxml.jackson.databind.node.ObjectNode) file)
            .set("lastUploadedAt", file.get("created"));
      }
      converted.put("format", 2);
      source = converted;
    }
    if (source != null
        && source.isObject()
        && source.path("format").isInt()
        && source.path("format").intValue() == 2) {
      var converted = ((com.fasterxml.jackson.databind.node.ObjectNode) source).deepCopy();
      require(converted.path("retained").isArray());
      for (JsonNode value : converted.path("retained")) {
        require(value.isObject() && !value.has("purpose"));
        ((com.fasterxml.jackson.databind.node.ObjectNode) value).putNull("purpose");
      }
      converted.put("format", 3);
      source = converted;
    }
    try (var parser = json.treeAsTokens(source)) {
      State state = typed.readValue(parser);
      validate(state);
      return state;
    } catch (IOException failure) {
      throw failure;
    } catch (RuntimeException invalid) {
      throw new IOException("Typed preservation control cannot be read.");
    }
  }

  private void validate(State s) throws IOException {
    require(
        s != null
            && s.format() == 3
            && KIND.equals(s.kind())
            && s.holds() != null
            && s.events() != null
            && s.retained() != null
            && s.files() != null);
    require(
        s.holds().size() <= 10000
            && s.events().size() <= 10000
            && s.retained().size() <= 10000
            && s.files().size() <= 10000);
    var ids = new HashSet<UUID>();
    var holds = new HashMap<UUID, Hold>();
    for (var h : s.holds()) {
      require(
          h != null
              && h.id() != null
              && ids.add(h.id())
              && h.owner() != null
              && h.actor() != null
              && h.category() != null
              && h.type() != null
              && h.basis() != null
              && h.created() != null
              && h.review() != null
              && h.expires() != null
              && h.reviewed() != null
              && h.until() != null
              && h.revision() > 0);
      require(
          (TYPES.containsKey(h.category()) && TYPES.get(h.category()).equals(h.type()))
              || ("financial".equals(h.category()) && h.resource() == null));
      require(
          Set.of("legal_obligation", "regular_exercise_of_rights", "contract", "security")
                  .contains(h.basis())
              && h.review().isAfter(h.created())
              && !h.expires().isBefore(h.review())
              && h.until().isAfter(h.created())
              && (h.released() == null || !h.released().isBefore(h.created())));
      if ("user".equals(h.type()) && h.resource() != null) require(h.owner().equals(h.resource()));
      decrypt(HOLD_REASON, h.owner(), h.id(), h.cipher());
      holds.put(h.id(), h);
    }
    ids.clear();
    var revisions = new HashSet<String>();
    for (var e : s.events()) {
      require(
          e != null
              && e.id() != null
              && ids.add(e.id())
              && e.hold() != null
              && e.owner() != null
              && e.actor() != null
              && e.created() != null
              && e.until() != null
              && e.until().isAfter(e.created())
              && e.revision() > 0
              && e.action() != null);
      var h = holds.get(e.hold());
      require(
          h != null
              && h.owner().equals(e.owner())
              && e.revision() <= h.revision()
              && revisions.add(e.hold() + ":" + e.revision())
              && Set.of("created", "reviewed", "released", "legacy_snapshot").contains(e.action()));
      decrypt(HOLD_EVENT_REASON, e.owner(), e.id(), e.cipher());
    }
    ids.clear();
    for (var r : s.retained()) {
      require(
          r != null
              && r.id() != null
              && ids.add(r.id())
              && r.owner() != null
              && r.category() != null
              && TYPES.containsKey(r.category())
              && (Objects.equals(TYPES.get(r.category()), r.type())
                  || (r.type() == null && r.resource() == null))
              && r.created() != null
              && r.until() != null
              && validRetainedWindow(r, s.holds())
              && r.reason() != null
              && r.reason().length() <= 10000
              && r.basis() != null);
      decrypt(RETAINED_CONTENT, r.owner(), r.id(), r.cipher());
    }
    int factCount = 0;
    for (var r : s.retained())
      if (r.purpose() != null) {
        require(PrivacyContractIdentityPurpose.applies(r.category(), r.holdOnly()));
        identity.validate(r.purpose());
        factCount = Math.addExact(factCount, r.purpose().facts().size());
        require(factCount <= 50000);
        if (r.purpose().lastFact() != null)
          require(
              !r.until()
                  .isBefore(
                      r.purpose().lastFact().atOffset(ZoneOffset.UTC).plusYears(5).toInstant()));
      }
    ids.clear();
    for (var f : s.files()) {
      require(
          f != null
              && f.id() != null
              && ids.add(f.id())
              && f.owner() != null
              && f.id().toString().equals(f.key())
              && f.purpose() != null
              && Set.of("photo", "identity", "license", "vehicle").contains(f.purpose())
              && Set.of("image/png", "image/jpeg", "image/webp", "application/pdf")
                  .contains(f.contentType())
              && !("photo".equals(f.purpose()) && "application/pdf".equals(f.contentType()))
              && f.bytes() > 0
              && f.bytes() <= 5 * 1024 * 1024
              && f.sha256() != null
              && f.sha256().matches("[a-f0-9]{64}")
              && f.created() != null
              && f.lastUploadedAt() != null
              && !f.lastUploadedAt().isBefore(f.created())
              && f.blob() != null);
      require(
          f.id().equals(f.blob().file())
              && f.owner().equals(f.blob().owner())
              && f.bytes() == f.blob().bytes()
              && f.sha256().equals(f.blob().sha256()));
    }
    Instant now = now();
    for (var h : s.holds())
      if (active(h, now) && "files".equals(h.category()) && h.resource() != null)
        require(
            s.files().stream()
                .anyMatch(f -> f.id().equals(h.resource()) && f.owner().equals(h.owner())));
    // This category has always required a specific private copy. Do not
    // replace a valid independent head with a DB state that lost its copy.
    for (var h : s.holds())
      if (active(h, now) && "security_audit".equals(h.category()))
        require(
            h.resource() != null
                && s.retained().stream().anyMatch(r -> r.holdOnly() && covers(h, r, now)));
  }

  private boolean validRetainedWindow(Retained r, List<Hold> holds) throws IOException {
    if (r.until().isAfter(r.created())) return true;
    if (!PrivacyContractIdentityPurpose.applies(r.category(), r.holdOnly()) || r.purpose() == null)
      return false;
    // An overdue copy may move from open to closed before it is physically
    // removed. Its closed successor must reach the independent head first;
    // the purpose and business-date floor are validated separately below.
    identity.validate(r.purpose());
    return true;
  }

  private String decrypt(PrivacyEvidenceCrypto.Field field, UUID owner, UUID id, String value)
      throws IOException {
    try {
      return evidence.open(field, owner, id, value);
    } catch (RuntimeException invalid) {
      throw new IOException("Preserved evidence cannot be authenticated.");
    }
  }

  void verifyForCleanup(JsonNode tree) throws IOException {
    State state = parse(tree);
    for (var file : state.files()) blobs.read(file.blob());
  }

  /** Called inside the shared database retention transaction, after business mutations. */
  public JsonNode capture() throws IOException {
    PrivacyRetentionLock.acquire(sql);
    identity.refreshForSnapshot();
    Instant now = now();
    var h = holds();
    var e = events();
    // Include the closed successor of an autonomous copy until the expiry
    // transaction removes it. Filtering it out here would skip the durable
    // transition from pending to closed when its original clock is past.
    var retained =
        retained().stream()
            .filter(
                r ->
                    PrivacyContractIdentityPurpose.applies(r.category(), r.holdOnly())
                        || needed(r, h, now))
            .toList();
    Map<UUID, HeldFile> previous = new HashMap<>();
    var current = journal.current();
    if (current.isPresent()) {
      var oldState = parse(current.get().state());
      for (var f : oldState.files()) previous.put(f.id(), f);
      for (var old : oldState.retained())
        if (old.purpose() != null) {
          var next = retained.stream().filter(r -> r.id().equals(old.id())).findFirst();
          if (next.isPresent()) require(sameRetained(old, next.get()));
          else require(!needed(old, h, now));
        }
    }
    var files = new ArrayList<HeldFile>();
    var rows =
        sql.queryForList(
            "SELECT f.* FROM stored_files f WHERE EXISTS(SELECT 1 FROM privacy_legal_holds h WHERE h.user_id=f.owner_id AND h.category='files' AND h.released_at IS NULL AND h.expires_at>now() AND (h.resource_id IS NULL OR h.resource_id=f.id)) ORDER BY f.id LIMIT 10001");
    require(rows.size() <= 10000);
    for (var row : rows) {
      UUID id = (UUID) row.get("id"), owner = (UUID) row.get("owner_id");
      String key = (String) row.get("storage_key"), sha = (String) row.get("sha256");
      int size = ((Number) row.get("byte_size")).intValue();
      require(row.get("erased_at") == null && id.toString().equals(key));
      var old = previous.get(id);
      byte[] bytes = storage.get(key);
      var ref = blobs.capture(owner, id, size, sha, bytes, old == null ? null : old.blob());
      files.add(
          new HeldFile(
              id,
              owner,
              (String) row.get("purpose"),
              key,
              (String) row.get("content_type"),
              size,
              sha,
              ((Timestamp) row.get("created_at")).toInstant(),
              instant(row.get("last_uploaded_at")),
              instant(row.get("deleted_at")),
              instant(row.get("erasure_requested_at")),
              ref));
    }
    State next = new State(3, KIND, h, e, retained, files);
    validate(next);
    return json.valueToTree(next);
  }

  private static Instant instant(Object value) {
    return value == null ? null : ((Timestamp) value).toInstant();
  }

  /**
   * Called only for a verified, committed independent head newer than the restored DB marker. All
   * SQL is within one transaction; file writes are private, content-verified and retryable.
   */
  public void replay(JsonNode tree) throws IOException {
    State target = parse(tree);
    Instant now = now();
    var targetHolds = new HashMap<UUID, Hold>();
    for (var h : target.holds()) targetHolds.put(h.id(), h);
    var targetEvents = new HashMap<UUID, Event>();
    for (var e : target.events()) targetEvents.put(e.id(), e);
    var targetRetained = new HashMap<UUID, Retained>();
    for (var r : target.retained()) targetRetained.put(r.id(), r);
    // Never invent users, administrators, legal acceptances, or live transport contracts.
    var users = new HashSet<UUID>();
    for (var h : target.holds()) {
      users.add(h.owner());
      users.add(h.actor());
    }
    for (var e : target.events()) {
      users.add(e.owner());
      users.add(e.actor());
    }
    for (var r : target.retained()) if (needed(r, target.holds(), now)) users.add(r.owner());
    for (var f : target.files()) if (covered(f, target.holds(), now)) users.add(f.owner());
    for (UUID user : users)
      require(
          Boolean.TRUE.equals(
              sql.queryForObject(
                  "SELECT EXISTS(SELECT 1 FROM users WHERE id=?)", Boolean.class, user)));
    var oldHolds = holds();
    var oldEvents = events();
    var oldRetained = retained();
    for (var old : oldHolds) {
      var next = targetHolds.get(old.id());
      if (next == null) require(!active(old, now) && !old.until().isAfter(now));
      else {
        require(
            sameHoldIdentity(old, next)
                && old.revision() <= next.revision()
                && (old.released() == null || Objects.equals(old.released(), next.released())));
        if (old.revision() == next.revision()) require(sameHold(old, next));
      }
    }
    for (var old : oldEvents) {
      var next = targetEvents.get(old.id());
      if (next == null) {
        var h = oldHolds.stream().filter(v -> v.id().equals(old.hold())).findFirst().orElseThrow();
        require(!active(h, now) && !old.until().isAfter(now));
      } else require(sameEvent(old, next));
    }
    for (var old : oldRetained) {
      var next = targetRetained.get(old.id());
      if (next == null) require(!needed(old, target.holds(), now));
      else require(sameRetained(old, next));
    }
    for (var f : target.files())
      if (covered(f, target.holds(), now)) {
        blobs.read(f.blob());
        var existing =
            sql.queryForList(
                "SELECT * FROM stored_files WHERE id=? OR storage_key=?", f.id(), f.key());
        for (var old : existing)
          require(
              f.id().equals(old.get("id"))
                  && f.owner().equals(old.get("owner_id"))
                  && f.key().equals(old.get("storage_key"))
                  && f.purpose().equals(old.get("purpose"))
                  && f.contentType().equals(old.get("content_type"))
                  && f.bytes() == ((Number) old.get("byte_size")).intValue()
                  && (f.sha256().equals(old.get("sha256")) || "erased".equals(old.get("sha256"))));
      }
    // Validate dependencies for resources that have no private evidence copy in this schema.
    for (var h : target.holds())
      if (active(h, now)
          && h.resource() != null
          && !Set.of("user", "stored_file").contains(h.type())
          && !target.retained().stream().anyMatch(r -> covers(h, r, now))) {
        String query =
            switch (h.type()) {
              case "vehicle" -> "SELECT EXISTS(SELECT 1 FROM vehicles WHERE id=? AND owner_id=?)";
              case "ride_route" ->
                  "SELECT EXISTS(SELECT 1 FROM ride_routes WHERE id=? AND driver_id=?)";
              case "identity_change" ->
                  "SELECT EXISTS(SELECT 1 FROM privacy_email_changes WHERE id=? AND user_id=?)";
              case "account_decision" ->
                  "SELECT EXISTS(SELECT 1 FROM privacy_account_decisions WHERE id=? AND user_id=?)";
              case "driver_application" ->
                  "SELECT EXISTS(SELECT 1 FROM driver_applications WHERE id=? AND user_id=?)";
              case "privacy_request" ->
                  "SELECT EXISTS(SELECT 1 FROM privacy_requests WHERE id=? AND user_id=?)";
              case "privacy_consent" ->
                  "SELECT EXISTS(SELECT 1 FROM privacy_consent_events WHERE id=? AND user_id=?)";
              case "legal_acceptance" ->
                  "SELECT EXISTS(SELECT 1 FROM legal_acceptances WHERE id=? AND user_id=?)";
              case "notification" ->
                  "SELECT EXISTS(SELECT 1 FROM notifications WHERE id=? AND user_id=?)";
              case "audit_log" ->
                  "SELECT EXISTS(SELECT 1 FROM audit_logs a WHERE a.id=? AND a.actor_id=? AND privacy_security_audit_deadline(a) IS NOT NULL)";
              default -> null;
            };
        require(
            query != null
                && Boolean.TRUE.equals(
                    sql.queryForObject(query, Boolean.class, h.resource(), h.owner())));
      }
    // Metadata with an elapsed original deadline is retired, not granted a new lifetime.
    for (var old : oldEvents)
      if (!targetEvents.containsKey(old.id()))
        sql.update("DELETE FROM privacy_hold_events WHERE id=?", old.id());
    for (var old : oldHolds)
      if (!targetHolds.containsKey(old.id()))
        sql.update("DELETE FROM privacy_legal_holds WHERE id=?", old.id());
    for (var h : target.holds()) {
      sql.update(
          "INSERT INTO privacy_legal_holds(id,user_id,category,resource_type,resource_id,legal_basis,reason_ciphertext,created_by,created_at,review_at,expires_at,released_at,revision,reviewed_at,retain_until) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET reason_ciphertext=excluded.reason_ciphertext,review_at=excluded.review_at,expires_at=excluded.expires_at,released_at=excluded.released_at,revision=excluded.revision,reviewed_at=excluded.reviewed_at,retain_until=excluded.retain_until",
          h.id(),
          h.owner(),
          h.category(),
          h.type(),
          h.resource(),
          h.basis(),
          h.cipher(),
          h.actor(),
          at(h.created()),
          at(h.review()),
          at(h.expires()),
          at(h.released()),
          h.revision(),
          at(h.reviewed()),
          at(h.until()));
    }
    sql.execute("SET LOCAL unicarona.privacy_key_rotation='on'");
    for (var e : target.events())
      sql.update(
          "INSERT INTO privacy_hold_events(id,hold_id,user_id,revision,action,body_ciphertext,created_by,created_at,retain_until) VALUES(?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET body_ciphertext=excluded.body_ciphertext WHERE privacy_hold_events.body_ciphertext IS DISTINCT FROM excluded.body_ciphertext",
          e.id(),
          e.hold(),
          e.owner(),
          e.revision(),
          e.action(),
          e.cipher(),
          e.actor(),
          at(e.created()),
          at(e.until()));
    for (var old : oldRetained)
      if (!targetRetained.containsKey(old.id())
          || !needed(targetRetained.get(old.id()), target.holds(), now))
        sql.update("DELETE FROM privacy_retained_records WHERE id=?", old.id());
    for (var r : target.retained())
      if (needed(r, target.holds(), now))
        sql.update(
            "INSERT INTO privacy_retained_records(id,user_id,category,resource_type,resource_id,content_ciphertext,reason,legal_basis,created_at,delete_after,hold_only,financial_purpose) VALUES(?,?,?,?,?,?,?,?,?,?,?,?::jsonb) ON CONFLICT(id) DO UPDATE SET content_ciphertext=excluded.content_ciphertext,delete_after=excluded.delete_after,financial_purpose=excluded.financial_purpose",
            r.id(),
            r.owner(),
            r.category(),
            r.type(),
            r.resource(),
            r.cipher(),
            r.reason(),
            r.basis(),
            at(r.created()),
            at(r.until()),
            r.holdOnly(),
            identity.encode(r.purpose()));
    for (var f : target.files())
      if (covered(f, target.holds(), now)) {
        byte[] bytes = blobs.read(f.blob());
        if (storage.existsVerified(f.key())) require(Arrays.equals(bytes, storage.get(f.key())));
        else storage.put(f.key(), bytes);
        // Recovery never republishes a photo or document through an ordinary file endpoint.
        sql.update(
            "INSERT INTO stored_files(id,owner_id,purpose,storage_key,content_type,byte_size,sha256,created_at,last_uploaded_at,deleted_at,erasure_requested_at) VALUES(?,?,?,?,?,?,?,?,?,coalesce(?::timestamptz,now()),coalesce(?::timestamptz,now())) ON CONFLICT(id) DO UPDATE SET sha256=excluded.sha256,erased_at=NULL,last_uploaded_at=greatest(stored_files.last_uploaded_at,excluded.last_uploaded_at),deleted_at=coalesce(stored_files.deleted_at,excluded.deleted_at),erasure_requested_at=coalesce(stored_files.erasure_requested_at,excluded.erasure_requested_at)",
            f.id(),
            f.owner(),
            f.purpose(),
            f.key(),
            f.contentType(),
            f.bytes(),
            f.sha256(),
            at(f.created()),
            at(f.lastUploadedAt()),
            at(f.deleted()),
            at(f.requested()));
      }
  }

  private boolean sameHoldIdentity(Hold a, Hold b) {
    return Objects.equals(a.owner(), b.owner())
        && Objects.equals(a.category(), b.category())
        && Objects.equals(a.type(), b.type())
        && Objects.equals(a.resource(), b.resource())
        && Objects.equals(a.basis(), b.basis())
        && Objects.equals(a.actor(), b.actor())
        && Objects.equals(a.created(), b.created());
  }

  private boolean sameHold(Hold a, Hold b) throws IOException {
    return Objects.equals(a.review(), b.review())
        && Objects.equals(a.expires(), b.expires())
        && Objects.equals(a.reviewed(), b.reviewed())
        && Objects.equals(a.until(), b.until())
        && Objects.equals(a.released(), b.released())
        && decrypt(HOLD_REASON, a.owner(), a.id(), a.cipher())
            .equals(decrypt(HOLD_REASON, b.owner(), b.id(), b.cipher()));
  }

  private boolean sameEvent(Event a, Event b) throws IOException {
    return Objects.equals(a.hold(), b.hold())
        && Objects.equals(a.owner(), b.owner())
        && a.revision() == b.revision()
        && Objects.equals(a.action(), b.action())
        && Objects.equals(a.actor(), b.actor())
        && Objects.equals(a.created(), b.created())
        && Objects.equals(a.until(), b.until())
        && decrypt(HOLD_EVENT_REASON, a.owner(), a.id(), a.cipher())
            .equals(decrypt(HOLD_EVENT_REASON, b.owner(), b.id(), b.cipher()));
  }

  private String retainedContent(Retained r) throws IOException {
    String clear = decrypt(RETAINED_CONTENT, r.owner(), r.id(), r.cipher());
    if (!"identity_change".equals(r.category())) return clear;
    try {
      JsonNode parsed = json.readTree(clear);
      if (parsed instanceof com.fasterxml.jackson.databind.node.ObjectNode value) {
        var nested = value.remove("new_email_ciphertext");
        if (nested != null && !nested.isNull()) {
          require(nested.isTextual() && r.resource() != null);
          value.put("new_email", decrypt(EMAIL_TARGET, r.owner(), r.resource(), nested.asText()));
        }
        return json.writeValueAsString(value);
      }
      return clear;
    } catch (RuntimeException invalid) {
      throw new IOException("Retained identity content cannot be compared.");
    }
  }

  private boolean sameRetained(Retained a, Retained b) throws IOException {
    return Objects.equals(a.owner(), b.owner())
        && Objects.equals(a.category(), b.category())
        && Objects.equals(a.type(), b.type())
        && Objects.equals(a.resource(), b.resource())
        && Objects.equals(a.reason(), b.reason())
        && Objects.equals(a.basis(), b.basis())
        && Objects.equals(a.created(), b.created())
        && (Objects.equals(a.until(), b.until())
            || (PrivacyContractIdentityPurpose.applies(a.category(), a.holdOnly())
                && b.purpose() != null
                && !b.until().isBefore(a.until())))
        && a.holdOnly() == b.holdOnly()
        && identity.successor(a.purpose(), b.purpose())
        && retainedContent(a).equals(retainedContent(b));
  }
}
