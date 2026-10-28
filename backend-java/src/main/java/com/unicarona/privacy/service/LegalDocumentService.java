package com.unicarona.privacy.service;

import com.fasterxml.jackson.databind.*;
import com.unicarona.privacy.dto.LegalDto;
import com.unicarona.security.Actor;
import com.unicarona.shared.*;
import com.unicarona.shared.repository.AuditRepository;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class LegalDocumentService {
  private final JdbcTemplate sql;
  private final ObjectMapper json;
  private final AuditRepository audit;
  private final IdempotencyExecutor keys;

  public LegalDocumentService(
      JdbcTemplate sql, ObjectMapper json, AuditRepository audit, IdempotencyExecutor keys) {
    this.sql = sql;
    this.json = json;
    this.audit = audit;
    this.keys = keys;
  }

  private static final Set<String> SLUGS =
      Set.of("terms", "privacy", "driver-terms", "participation", "payments", "safety");
  private static final String SELECT =
      "SELECT v.*,v.content::text AS content_text FROM legal_document_versions v ";

  @Transactional(readOnly = true)
  public LegalDto.Manifest manifest() {
    return new LegalDto.Manifest(current(false));
  }

  private List<LegalDto.Document> current(boolean lock) {
    var documents =
        sql.query(
            SELECT
                + "JOIN legal_document_heads h ON h.slug=v.slug AND h.version=v.version WHERE v.effective_at<=now() ORDER BY h.slug"
                + (lock ? " FOR SHARE OF h" : ""),
            (r, n) -> document(r));
    ApiException.require(
        documents.stream()
            .map(d -> d.reference().slug())
            .collect(java.util.stream.Collectors.toSet())
            .equals(SLUGS),
        "LEGAL_CATALOG_NOT_PUBLISHED",
        "Os documentos do serviço ainda estão em preparação. Tente novamente após a publicação.",
        503);
    return documents;
  }

  @Transactional(readOnly = true)
  public LegalDto.Document document(String slug, String version) {
    return sql
        .query(
            SELECT
                + (version == null
                    ? "JOIN legal_document_heads h ON h.slug=v.slug AND h.version=v.version WHERE v.slug=? AND v.effective_at<=now()"
                    : "WHERE v.slug=? AND v.version=? AND v.effective_at<=now()"),
            (r, n) -> document(r),
            version == null ? new Object[] {slug} : new Object[] {slug, version})
        .stream()
        .findFirst()
        .orElseThrow(
            () -> new ApiException("LEGAL_DOCUMENT_NOT_FOUND", "Documento não encontrado.", 404));
  }

  private LegalDto.Document document(java.sql.ResultSet r) throws java.sql.SQLException {
    try {
      JsonNode body = json.readTree(r.getString("content_text"));
      if (!body.isObject()
          || body.size() != 2
          || !body.path("body").isTextual()
          || !body.path("title").isTextual()
          || !body.path("title").asText().equals(r.getString("title")))
        throw new IllegalStateException("Published legal content format mismatch.");
      var canonical = new TreeMap<String, String>();
      canonical.put("body", body.path("body").asText());
      canonical.put("title", body.path("title").asText());
      String hash =
          HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(canonical)));
      if (!hash.equals(r.getString("content_sha256")))
        throw new IllegalStateException("Published legal content integrity mismatch.");
      var scopes = r.getArray("required_scopes");
      List<String> required;
      try {
        required = List.of((String[]) scopes.getArray());
      } finally {
        scopes.free();
      }
      return new LegalDto.Document(
          new LegalDto.Reference(r.getString("slug"), r.getString("version"), hash),
          r.getString("title"),
          r.getString("kind"),
          required,
          r.getBoolean("requires_acceptance"),
          r.getString("change_summary"),
          body.path("body").asText(),
          ApiDates.timestamp(r.getTimestamp("published_at").toInstant()),
          ApiDates.timestamp(r.getTimestamp("effective_at").toInstant()));
    } catch (java.io.IOException | java.security.GeneralSecurityException error) {
      throw new IllegalStateException("Could not verify published legal content.");
    }
  }

  @Transactional(readOnly = true)
  public LegalDto.Status status(Actor actor) {
    return status(actor.id(), current(false));
  }

  private LegalDto.Status status(UUID owner, List<LegalDto.Document> documents) {
    var accepted =
        sql.query(
            "SELECT DISTINCT ON(a.document_slug) a.* FROM legal_acceptances a JOIN legal_document_heads h ON h.slug=a.document_slug WHERE a.user_id=? ORDER BY a.document_slug,(a.document_version=h.version) DESC,a.accepted_at DESC,a.id",
            (r, n) ->
                new LegalDto.Acceptance(
                    new LegalDto.Reference(
                        r.getString("document_slug"),
                        r.getString("document_version"),
                        r.getString("content_sha256")),
                    r.getString("kind"),
                    r.getString("source"),
                    ApiDates.timestamp(r.getTimestamp("accepted_at").toInstant())),
            owner);
    var base = new ArrayList<LegalDto.Reference>();
    var driver = new ArrayList<LegalDto.Reference>();
    for (var doc : documents) {
      boolean exact = accepted.stream().anyMatch(a -> a.document().equals(doc.reference()));
      boolean earlier =
          accepted.stream().anyMatch(a -> a.document().slug().equals(doc.reference().slug()));
      if (exact || (!doc.materialChange() && earlier)) continue;
      if (doc.requiredFor().contains("service")) base.add(doc.reference());
      if (doc.requiredFor().contains("driver")) driver.add(doc.reference());
    }
    return new LegalDto.Status(List.copyOf(base), List.copyOf(driver), accepted);
  }

  @Transactional(readOnly = true)
  public List<LegalDto.Acceptance> history(UUID owner, int limit, int offset) {
    return sql.query(
        "SELECT document_slug,document_version,content_sha256,kind,source,accepted_at FROM legal_acceptances WHERE user_id=? ORDER BY accepted_at DESC,id LIMIT ? OFFSET ?",
        (r, n) ->
            new LegalDto.Acceptance(
                new LegalDto.Reference(
                    r.getString("document_slug"),
                    r.getString("document_version"),
                    r.getString("content_sha256")),
                r.getString("kind"),
                r.getString("source"),
                ApiDates.timestamp(r.getTimestamp("accepted_at").toInstant())),
        owner,
        limit,
        offset);
  }

  @Transactional
  public LegalDto.Status accept(Actor actor, LegalDto.Accept input, String key) {
    sql.execute("SET LOCAL lock_timeout='3s'");
    ApiException.require(
        !sql.queryForList(
                "SELECT id FROM users WHERE id=? AND deleted_at IS NULL FOR UPDATE", actor.id())
            .isEmpty(),
        "ACCOUNT_UNAVAILABLE",
        "A conta não está disponível para essa operação.",
        409);
    ApiException.require(
        Boolean.TRUE.equals(input.terms()) && Boolean.TRUE.equals(input.privacyNotice()),
        "LEGAL_CONFIRMATION_REQUIRED",
        "Confirme os Termos e a leitura da Política de Privacidade.",
        422);
    var ordered =
        input.documents().stream().sorted(Comparator.comparing(LegalDto.Reference::slug)).toList();
    return keys.execute(
        actor.id(),
        key,
        "legal:accept",
        Map.of(
            "documents",
            ordered,
            "terms",
            true,
            "privacyNotice",
            true,
            "driverTerms",
            Boolean.TRUE.equals(input.driverTerms())),
        LegalDto.Status.class,
        () -> {
          var documents = current(true);
          var required =
              documents.stream()
                  .filter(
                      d ->
                          d.requiredFor().contains("service")
                              || (Boolean.TRUE.equals(input.driverTerms())
                                  && d.requiredFor().contains("driver")))
                  .toList();
          validate(input.documents(), required);
          record(actor.id(), required, "terms_update");
          return status(actor.id(), documents);
        });
  }

  public void validate(List<LegalDto.Reference> supplied, List<LegalDto.Document> expected) {
    ApiException.require(
        supplied != null
            && supplied.size() == expected.size()
            && supplied.stream().map(LegalDto.Reference::slug).distinct().count()
                == supplied.size(),
        "LEGAL_DOCUMENTS_REQUIRED",
        "Leia os documentos indicados e confirme suas versões.",
        422);
    var actual = new HashSet<>(supplied);
    ApiException.require(
        actual.equals(
            expected.stream()
                .map(LegalDto.Document::reference)
                .collect(java.util.stream.Collectors.toSet())),
        "LEGAL_DOCUMENTS_CHANGED",
        "Os documentos foram atualizados. Abra as versões atuais e confirme novamente.",
        409);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public List<LegalDto.Document> registrationDocuments(List<LegalDto.Reference> supplied) {
    var expected =
        current(true).stream().filter(d -> d.requiredFor().contains("registration")).toList();
    validate(supplied, expected);
    return expected;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void record(UUID owner, List<LegalDto.Document> documents, String source) {
    for (var doc : documents) {
      int changed =
          sql.update(
              "INSERT INTO legal_acceptances(user_id,document_slug,document_version,kind,content_sha256,source,retain_until) VALUES(?,?,?,?,?,?,now()+interval '5 years') ON CONFLICT(user_id,document_slug,document_version,kind) DO NOTHING",
              owner,
              doc.reference().slug(),
              doc.reference().version(),
              doc.kind(),
              doc.reference().contentSha256(),
              source);
      if (changed == 1)
        audit.record(
            owner,
            "legal_document_accepted",
            "legal_document",
            null,
            Map.of(
                "slug",
                doc.reference().slug(),
                "version",
                doc.reference().version(),
                "kind",
                doc.kind(),
                "hash",
                doc.reference().contentSha256(),
                "source",
                source));
    }
  }

  @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = ApiException.class)
  public void requireCurrent(UUID owner, boolean driver) {
    var state = status(owner, current(true));
    ApiException.require(
        state.required().isEmpty() && (!driver || state.driverRequired().isEmpty()),
        "LEGAL_ACCEPTANCE_REQUIRED",
        "Leia e confirme os documentos atualizados antes de uma nova contratação.",
        428);
  }

  /**
   * Avoid exposing another participant's legal acceptance history. Caller holds ordered user locks
   * first.
   */
  @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = ApiException.class)
  public void requireCounterparty(UUID owner, boolean driver) {
    var state = status(owner, current(true));
    ApiException.require(
        state.required().isEmpty() && (!driver || state.driverRequired().isEmpty()),
        "COMBINATION_UNAVAILABLE",
        "Esta combinação não está disponível para uma nova contratação.",
        409);
  }
}
