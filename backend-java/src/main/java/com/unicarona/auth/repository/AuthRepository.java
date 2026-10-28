package com.unicarona.auth.repository;

import com.unicarona.auth.dto.AuthDto;
import com.unicarona.security.Actor;
import com.unicarona.shared.ApiException;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** PostgreSQL-specific atomic writes and locks share the JPA transaction/DataSource. */
@Repository
public class AuthRepository {
  private final JdbcTemplate sql;

  public AuthRepository(JdbcTemplate sql) {
    this.sql = sql;
  }

  private Optional<Map<String, Object>> one(String query, Object... args) {
    var rows = sql.queryForList(query, args);
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
  }

  public void lockTimeout() {
    sql.execute("SET LOCAL lock_timeout = '3s'");
  }

  public Instant databaseNow() {
    return sql.queryForObject("SELECT now()", java.sql.Timestamp.class).toInstant();
  }

  public Optional<UUID> insertUser(AuthDto.Register input, String hash) {
    return one(
            "INSERT INTO users(email,password_hash,name,birth_date,phone) VALUES(?,?,?,?::date,?) ON CONFLICT(email) DO NOTHING RETURNING id",
            input.email(),
            hash,
            input.name(),
            input.birth(),
            input.phone() == null || input.phone().isEmpty() ? null : input.phone())
        .map(r -> (UUID) r.get("id"));
  }

  public Optional<Map<String, Object>> lockUser(UUID id, boolean liveOnly) {
    return one(
        "SELECT id,email,password_hash,email_verified_at FROM users WHERE id=?"
            + (liveOnly ? " AND deleted_at IS NULL AND account_status<>'deleted'" : "")
            + " FOR UPDATE",
        id);
  }

  public Optional<Map<String, Object>> lockEmail(String email) {
    return one(
        "SELECT id,email,email_verified_at FROM users WHERE email=? AND deleted_at IS NULL AND account_status<>'deleted' FOR UPDATE",
        email);
  }

  public void saveAcademic(UUID user, AuthDto.Academic input) {
    var previous =
        one(
            "SELECT university_id,course_id,class_id,period_status,period_number,shift FROM student_profiles WHERE user_id=?",
            user);
    UUID university = UUID.fromString(input.universityId()),
        course = UUID.fromString(input.courseId());
    ApiException.require(
        one("SELECT id FROM courses WHERE id=? AND university_id=? AND active", course, university)
            .isPresent(),
        "INVALID_COURSE",
        "Selecione um curso válido da faculdade.",
        422);
    ApiException.require(
        one("SELECT id FROM universities WHERE id=? AND active", university).isPresent(),
        "INVALID_UNIVERSITY",
        "Faculdade indisponível.",
        422);
    UUID region = input.regionId() == null ? null : UUID.fromString(input.regionId());
    if (region != null)
      ApiException.require(
          one("SELECT id FROM regions WHERE id=?", region).isPresent(),
          "INVALID_REGION",
          "Selecione uma região válida.",
          422);
    UUID classId = null;
    Integer period = "numbered".equals(input.periodStatus()) ? input.periodNumber() : null;
    if (period != null && input.classLabel() != null) {
      sql.update(
          "INSERT INTO classes(university_id,course_id,period_number,label) VALUES(?,?,?,?) ON CONFLICT(course_id,period_number,label) DO NOTHING",
          university,
          course,
          period,
          input.classLabel());
      classId =
          sql.queryForObject(
              "SELECT id FROM classes WHERE course_id=? AND period_number=? AND label=?",
              UUID.class,
              course,
              period,
              input.classLabel());
    }
    UUID selectedClass = classId;
    boolean changed =
        previous.isEmpty()
            || previous
                .filter(
                    p ->
                        java.util.Objects.equals(p.get("university_id"), university)
                            && java.util.Objects.equals(p.get("course_id"), course)
                            && java.util.Objects.equals(p.get("class_id"), selectedClass)
                            && java.util.Objects.equals(
                                p.get("period_status"), input.periodStatus())
                            && java.util.Objects.equals(p.get("period_number"), period)
                            && java.util.Objects.equals(p.get("shift"), input.shift()))
                .isEmpty();
    sql.update(
        """
            INSERT INTO student_profiles(user_id,university_id,course_id,class_id,period_status,period_number,shift,region_id,region_label)
            VALUES(?,?,?,?,?,?,?,?,?)
            ON CONFLICT(user_id) DO UPDATE SET university_id=excluded.university_id,course_id=excluded.course_id,class_id=excluded.class_id,
            period_status=excluded.period_status,period_number=excluded.period_number,shift=excluded.shift,region_id=excluded.region_id,region_label=excluded.region_label,
            verification_status=CASE WHEN ? THEN 'self_reported' ELSE student_profiles.verification_status END,
            verification_source=CASE WHEN ? THEN NULL ELSE student_profiles.verification_source END,
            verified_at=CASE WHEN ? THEN NULL ELSE student_profiles.verified_at END,updated_at=now()
            """,
        user,
        university,
        course,
        classId,
        input.periodStatus(),
        period,
        input.shift(),
        region,
        input.region(),
        changed,
        changed,
        changed);
  }

  public void validateRegistrationAcademic(AuthDto.Academic input) {
    UUID university = UUID.fromString(input.universityId()),
        course = UUID.fromString(input.courseId());
    ApiException.require(
        one("SELECT id FROM courses WHERE id=? AND university_id=? AND active", course, university)
            .isPresent(),
        "INVALID_COURSE",
        "Selecione um curso válido da faculdade.",
        422);
    ApiException.require(
        one("SELECT id FROM universities WHERE id=? AND active", university).isPresent(),
        "INVALID_UNIVERSITY",
        "Faculdade indisponível.",
        422);
    if (input.regionId() != null)
      ApiException.require(
          one("SELECT id FROM regions WHERE id=?", UUID.fromString(input.regionId())).isPresent(),
          "INVALID_REGION",
          "Selecione uma região válida.",
          422);
  }

  public UUID issueEmailToken(UUID user, String kind, String digest, int minutes) {
    sql.update(
        "UPDATE auth_tokens SET used_at=now(),invalidated_at=now() WHERE user_id=? AND kind=? AND used_at IS NULL AND invalidated_at IS NULL",
        user,
        kind);
    return sql.queryForObject(
        "INSERT INTO auth_tokens(user_id,kind,token_hash,expires_at) VALUES(?,?,?,now()+?*interval '1 minute') RETURNING id",
        UUID.class,
        user,
        kind,
        digest,
        minutes);
  }

  public Optional<UUID> tokenUser(String digest, String kind) {
    return one("SELECT user_id FROM auth_tokens WHERE token_hash=? AND kind=?", digest, kind)
        .map(r -> (UUID) r.get("user_id"));
  }

  public Optional<Map<String, Object>> lockValidToken(String digest, String kind) {
    return one(
        "SELECT id,user_id FROM auth_tokens WHERE token_hash=? AND kind=? AND used_at IS NULL AND invalidated_at IS NULL AND expires_at>now() FOR UPDATE",
        digest,
        kind);
  }

  public Optional<Map<String, Object>> lockVerificationToken(String digest) {
    return one(
        "SELECT id,user_id,used_at,invalidated_at,expires_at<=now() AS expired FROM auth_tokens WHERE token_hash=? AND kind='verify_email' FOR UPDATE",
        digest);
  }

  public int reserveEmailCooldown(String hash, int seconds) {
    // A common bucket for all addresses prevents account-existence probes, even on unknown/verified
    // users.
    var rows =
        sql.queryForList(
            """
            INSERT INTO rate_limits(bucket,attempts,expires_at) VALUES(?,1,now()+?*interval '1 second')
            ON CONFLICT(bucket) DO UPDATE SET attempts=1,expires_at=excluded.expires_at
            WHERE rate_limits.expires_at<=now() RETURNING bucket
            """,
            hash,
            seconds);
    if (!rows.isEmpty()) return 0;
    return sql.queryForObject(
        "SELECT greatest(1,ceil(extract(epoch FROM expires_at-now())))::int FROM rate_limits WHERE bucket=?",
        Integer.class,
        hash);
  }

  public void verifyEmail(UUID token, UUID user) {
    sql.update("UPDATE auth_tokens SET used_at=now() WHERE id=?", token);
    sql.update(
        "UPDATE users SET email_verified_at=coalesce(email_verified_at,now()),updated_at=now() WHERE id=?",
        user);
  }

  public void resetPassword(UUID user, String hash) {
    sql.update(
        "UPDATE auth_tokens SET used_at=now() WHERE user_id=? AND kind='reset_password' AND used_at IS NULL",
        user);
    sql.update(
        "UPDATE users SET password_hash=?,password_changed_at=now(),updated_at=now() WHERE id=?",
        hash,
        user);
    revokeUser(user);
  }

  public boolean changePassword(UUID user, String expected, String replacement) {
    return sql.update(
            "UPDATE users SET password_hash=?,password_changed_at=now(),updated_at=now() WHERE id=? AND password_hash=?",
            replacement,
            user,
            expected)
        == 1;
  }

  public void revokeUser(UUID user) {
    sql.update("UPDATE sessions SET revoked_at=coalesce(revoked_at,now()) WHERE user_id=?", user);
  }

  public void revokeFamily(UUID family) {
    sql.update(
        "UPDATE sessions SET revoked_at=coalesce(revoked_at,now()) WHERE family_id=?", family);
  }

  public Optional<UUID> refreshUser(String hash) {
    return one("SELECT user_id FROM sessions WHERE refresh_hash=?", hash)
        .map(r -> (UUID) r.get("user_id"));
  }

  public Optional<Map<String, Object>> lockRefresh(String hash) {
    return one(
        "SELECT *,refresh_expires_at>now() AS valid_expiry FROM sessions WHERE refresh_hash=? FOR UPDATE",
        hash);
  }

  public void insertSession(
      UUID id,
      UUID user,
      UUID family,
      int generation,
      String accessHash,
      String refreshHash,
      String csrfHash,
      int minutes,
      int days) {
    sql.update(
        """
            INSERT INTO sessions(id,user_id,access_hash,refresh_hash,csrf_hash,family_id,generation,access_expires_at,refresh_expires_at)
            VALUES(?,?,?,?,?,?,?,now()+?*interval '1 minute',now()+?*interval '1 day')
            """,
        id,
        user,
        accessHash,
        refreshHash,
        csrfHash,
        family,
        generation,
        minutes,
        days);
  }

  public void replaceSession(UUID old, UUID next) {
    sql.update("UPDATE sessions SET revoked_at=now(),replaced_by=? WHERE id=?", next, old);
  }

  public Optional<Actor> authenticate(String digest) {
    var actors =
        sql.query(
            """
            SELECT s.id AS session_id,s.family_id,s.csrf_hash,u.id,u.role,u.account_status,u.email_verified_at,
            EXISTS(SELECT 1 FROM student_profiles p WHERE p.user_id=u.id) AS passenger,
            EXISTS(SELECT 1 FROM driver_profiles d WHERE d.user_id=u.id AND d.status='approved') AS driver
            FROM sessions s JOIN users u ON u.id=s.user_id WHERE s.access_hash=? AND s.revoked_at IS NULL
            AND s.access_expires_at>now() AND s.refresh_expires_at>now() AND u.deleted_at IS NULL
            """,
            (r, n) -> {
              var roles = new HashSet<String>();
              if ("admin".equals(r.getString("role"))) roles.add("ROLE_ADMIN");
              if (r.getBoolean("passenger")) roles.add("ROLE_PASSENGER");
              if (r.getBoolean("driver")) roles.add("ROLE_DRIVER");
              return new Actor(
                  r.getObject("id", UUID.class),
                  r.getString("role"),
                  r.getString("account_status"),
                  r.getObject("session_id", UUID.class),
                  r.getObject("family_id", UUID.class),
                  r.getString("csrf_hash"),
                  r.getTimestamp("email_verified_at") != null,
                  Set.copyOf(roles));
            },
            digest);
    return actors.isEmpty() ? Optional.empty() : Optional.of(actors.getFirst());
  }

  public int incrementRate(String hash, int seconds) {
    return sql.queryForObject(
        """
            INSERT INTO rate_limits(bucket,attempts,expires_at) VALUES(?,1,now()+?*interval '1 second')
            ON CONFLICT(bucket) DO UPDATE SET attempts=CASE WHEN rate_limits.expires_at<now() THEN 1 ELSE rate_limits.attempts+1 END,
            expires_at=CASE WHEN rate_limits.expires_at<now() THEN excluded.expires_at ELSE rate_limits.expires_at END RETURNING attempts
            """,
        Integer.class,
        hash,
        seconds);
  }

  public int rateRetryAfter(String hash) {
    return sql.queryForObject(
        "SELECT greatest(1,coalesce((SELECT ceil(extract(epoch FROM expires_at-now()))::integer FROM rate_limits WHERE bucket=?),1))",
        Integer.class,
        hash);
  }

  public void audit(UUID actor, String action, String metadata) {
    sql.update(
        "INSERT INTO audit_logs(actor_id,action,resource_type,resource_id,metadata) VALUES(?,?,'user',?,?::jsonb)",
        actor,
        action,
        actor,
        metadata);
  }

  public void enqueueEmail(UUID tokenId, String payload) {
    sql.update(
        "INSERT INTO outbox_jobs(type,event_key,payload) VALUES('email',?,?::jsonb) ON CONFLICT(event_key) DO NOTHING",
        "auth:" + tokenId,
        payload);
  }

  public boolean emailServiceDelayed() {
    // Preserve failed-job history, but do not report obsolete links as current delivery failures.
    return Boolean.TRUE.equals(
        sql.queryForObject(
            """
            SELECT EXISTS(
                SELECT 1 FROM outbox_jobs o
                LEFT JOIN auth_tokens t ON o.event_key='auth:'||t.id::text
                LEFT JOIN users u ON u.id=t.user_id
                WHERE o.type='email' AND o.status IN ('pending','processing','dead')
                    AND o.last_error_code IS NOT NULL AND o.created_at>now()-interval '1 hour'
                    AND (o.event_key NOT LIKE 'auth:%' OR (
                        t.id IS NOT NULL AND t.used_at IS NULL AND t.invalidated_at IS NULL AND t.expires_at>now()
                        AND u.deleted_at IS NULL AND (u.email=o.payload->>'to' OR (o.payload->>'ownerId'=u.id::text AND jsonb_exists(o.payload,'toCiphertext')) OR (t.kind='change_email' AND EXISTS(SELECT 1 FROM privacy_email_changes c WHERE c.token_id=t.id AND c.status='pending' AND c.eligible AND c.expires_at>now())))
                        AND (t.kind<>'verify_email' OR u.email_verified_at IS NULL)
                    ))
            )
            """,
            Boolean.class));
  }
}
