package com.unicarona.support;

import com.unicarona.privacy.service.*;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Explicit synthetic publication and named business-fixture preconditions; never used by
 * application code.
 */
public final class LegalTestFixtures {
  private LegalTestFixtures() {}

  public static void publish(ApplicationContext app) {
    var draft = app.getBean(LegalDocumentTemplates.class).prepare();
    app.getBean(LegalPublicationService.class)
        .publish(new LegalDocumentTemplates.Bundle("published", draft.documents()));
  }

  public static Map<String, Object> registration(ApplicationContext app) {
    return Map.of(
        "privacyNotice",
        true,
        "documents",
        app.getBean(LegalDocumentService.class).manifest().documents().stream()
            .filter(d -> d.requiredFor().contains("registration"))
            .map(d -> d.reference())
            .toList());
  }

  /** Explicit preconditions for the four named fictional seed accounts in a disposable database. */
  public static void acceptedSeedBusinessUsers(ApplicationContext app) {
    var sql = app.getBean(JdbcTemplate.class);
    if (!"unicarona_java_test"
        .equals(sql.queryForObject("SELECT current_database()", String.class)))
      throw new IllegalStateException(
          "Seed acceptances require the disposable Java test database.");
    if (sql.queryForObject("SELECT count(*) FROM legal_document_heads", Integer.class) == 0)
      publish(app);
    var legal = app.getBean(LegalDocumentService.class);
    var documents = legal.manifest().documents();
    var owners =
        sql.queryForList(
            "SELECT id FROM users WHERE email IN ('ana@example.com','carolina@example.com','lucas@example.com','admin@example.com')",
            UUID.class);
    new org.springframework.transaction.support.TransactionTemplate(
            app.getBean(org.springframework.transaction.PlatformTransactionManager.class))
        .executeWithoutResult(
            status -> {
              for (var owner : owners) legal.record(owner, documents, "terms_update");
            });
  }

  /**
   * Existing business regression fixtures explicitly assume all six agreements before their
   * unrelated task.
   */
  public static void acceptedBusinessUser(ApplicationContext app, UUID owner) {
    var sql = app.getBean(JdbcTemplate.class);
    String email = sql.queryForObject("SELECT email FROM users WHERE id=?", String.class, owner);
    if (email == null || !email.endsWith("@example.test"))
      throw new IllegalArgumentException(
          "Only synthetic business fixtures may receive precondition receipts.");
    if (sql.queryForObject("SELECT count(*) FROM legal_document_heads", Integer.class) == 0)
      publish(app);
    var legal = app.getBean(LegalDocumentService.class);
    var documents = legal.manifest().documents();
    new org.springframework.transaction.support.TransactionTemplate(
            app.getBean(org.springframework.transaction.PlatformTransactionManager.class))
        .executeWithoutResult(status -> legal.record(owner, documents, "terms_update"));
  }
}
