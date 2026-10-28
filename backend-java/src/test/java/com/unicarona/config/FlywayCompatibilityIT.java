package com.unicarona.config;

import static org.junit.jupiter.api.Assertions.*;

import com.unicarona.support.RealPostgres;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FlywayCompatibilityIT {
  private static RealPostgres database;
  private static LegacyMigrationManifest manifest;

  @BeforeAll
  static void start() throws Exception {
    database = new RealPostgres();
    manifest = new LegacyMigrationManifest();
  }

  @AfterAll
  static void close() {
    if (database != null) database.close();
  }

  @BeforeEach
  void reset() throws Exception {
    database.resetSchema();
  }

  private Flyway flyway() {
    return Flyway.configure()
        .dataSource(database.dataSource())
        .locations("classpath:db/migration")
        .cleanDisabled(true)
        .load();
  }

  private void migrate() {
    new LegacyFlywayMigrationStrategy(database.dataSource(), manifest).migrate(flyway());
  }

  private java.util.List<String> definitions(String sql) throws Exception {
    try (var connection = database.dataSource().getConnection();
        var statement = connection.createStatement();
        var result = statement.executeQuery(sql)) {
      java.util.List<String> values = new java.util.ArrayList<>();
      while (result.next()) values.add(result.getString(1));
      return values.stream().sorted().toList();
    }
  }

  private String originalObjects(String query) throws Exception {
    return definitions(query).stream()
        .map(name -> "'" + name.replace("'", "''") + "'")
        .collect(java.util.stream.Collectors.joining(","));
  }

  private long count(String sql) throws Exception {
    try (var connection = database.dataSource().getConnection();
        var statement = connection.createStatement();
        var result = statement.executeQuery(sql)) {
      result.next();
      return result.getLong(1);
    }
  }

  private void legacySchema(int versions) throws Exception {
    try (var connection = database.dataSource().getConnection();
        var statement = connection.createStatement()) {
      statement.execute(
          "CREATE TABLE schema_migrations(version text PRIMARY KEY,checksum text NOT NULL,applied_at timestamptz NOT NULL DEFAULT now())");
      for (var entry : manifest.entries().subList(0, versions)) {
        connection.setAutoCommit(false);
        statement.execute(new String(manifest.sql(entry), StandardCharsets.UTF_8));
        try (var insert =
            connection.prepareStatement(
                "INSERT INTO schema_migrations(version,checksum) VALUES(?,?)")) {
          insert.setString(1, entry.legacyVersion());
          insert.setString(2, entry.checksum());
          insert.executeUpdate();
        }
        connection.commit();
        connection.setAutoCommit(true);
      }
    }
  }

  @Test
  void freshSchemaAndRepeatApplyLegacyAndMercadoPagoMigrations() throws Exception {
    migrate();
    migrate();
    assertEquals(13, count("SELECT count(*) FROM schema_migrations"));
    assertEquals(
        47, count("SELECT count(*) FROM flyway_schema_history WHERE type='SQL' AND success"));
    assertEquals(0, count("SELECT count(*) FROM privacy_orphan_file_observations"));
    assertEquals(
        1,
        count(
            "SELECT count(*) FROM privacy_hold_control_state WHERE singleton AND sequence=0 AND operation_id IS NULL AND NOT needs_confirmation"));
    assertEquals(
        1,
        count(
            "SELECT count(*) FROM pg_indexes WHERE schemaname='public' AND indexname='stored_files_unused_scan'"));
    assertEquals(
        1,
        count(
            "SELECT count(*) FROM information_schema.columns WHERE table_schema='public' AND table_name='stored_files' AND column_name='last_uploaded_at' AND is_nullable='NO' AND column_default='now()'"));
    assertEquals(
        2,
        count(
            "SELECT count(*) FROM pg_tables WHERE schemaname='public' AND tablename IN ('mercado_pago_review_resolutions','mercado_pago_resolved_refunds')"));
    assertEquals(
        1,
        count(
            "SELECT count(*) FROM pg_trigger WHERE tgname='mercado_pago_resolution_ledger' AND tgdeferrable"));
    assertEquals(
        5,
        count(
            "SELECT count(*) FROM pg_tables WHERE schemaname='public' AND tablename IN ('mercado_pago_orders','mercado_pago_refunds','mercado_pago_card_commands','mercado_pago_pix_commands','mercado_pago_observed_refunds')"));
    assertTrue(flyway().validateWithResult().validationSuccessful);
    assertEquals(1, count("SELECT count(*) FROM pg_trigger WHERE tgname='payment_ledger'"));
    assertEquals(1, count("SELECT count(*) FROM pg_trigger WHERE tgname='reservation_capacity'"));
  }

  @Test
  void upgradingV36BackfillsTheLastUploadFromCreationWithoutInventingNewActivity()
      throws Exception {
    Flyway.configure()
        .dataSource(database.dataSource())
        .locations("classpath:db/migration")
        .target("36")
        .cleanDisabled(true)
        .load()
        .migrate();
    var sql = new org.springframework.jdbc.core.JdbcTemplate(database.dataSource());
    UUID owner =
        sql.queryForObject(
            "INSERT INTO users(email,password_hash,name,birth_date) VALUES('migration-file@example.test','synthetic-test-only','Synthetic migration user','2000-01-01') RETURNING id",
            UUID.class);
    UUID file = UUID.randomUUID();
    sql.update(
        "INSERT INTO stored_files(id,owner_id,purpose,storage_key,content_type,byte_size,sha256,created_at) VALUES(?,?,'identity',?,'application/pdf',1,?,now()-interval '2 days')",
        file,
        owner,
        file.toString(),
        "a".repeat(64));
    flyway().migrate();
    assertTrue(
        sql.queryForObject(
            "SELECT last_uploaded_at=created_at AND last_uploaded_at<now()-interval '1 day' AND deleted_at IS NULL AND erased_at IS NULL FROM stored_files WHERE id=?",
            Boolean.class,
            file));
    assertEquals(
        1, sql.queryForObject("SELECT count(*) FROM stored_files WHERE id=?", Integer.class, file));
    assertTrue(flyway().validateWithResult().validationSuccessful);
  }

  @Test
  void upgradingV38AddsNoInventedOrphanObservationsAndPreservesExistingFileMetadata()
      throws Exception {
    Flyway.configure()
        .dataSource(database.dataSource())
        .locations("classpath:db/migration")
        .target("38")
        .cleanDisabled(true)
        .load()
        .migrate();
    var sql = new org.springframework.jdbc.core.JdbcTemplate(database.dataSource());
    UUID
        owner =
            sql.queryForObject(
                "INSERT INTO users(email,password_hash,name,birth_date) VALUES('migration-orphan@example.test','synthetic-test-only','Synthetic orphan migration owner','2000-01-01') RETURNING id",
                UUID.class),
        file = UUID.randomUUID();
    sql.update(
        "INSERT INTO stored_files(id,owner_id,purpose,storage_key,content_type,byte_size,sha256,created_at,last_uploaded_at) VALUES(?,?,'identity',?,'application/pdf',3,?,now()-interval '2 days',now()-interval '2 days')",
        file,
        owner,
        file.toString(),
        "a".repeat(64));
    String before =
        sql.queryForObject(
            "SELECT to_jsonb(stored_files)::text FROM stored_files WHERE id=?", String.class, file);
    flyway().migrate();
    assertEquals(
        before,
        sql.queryForObject(
            "SELECT to_jsonb(stored_files)::text FROM stored_files WHERE id=?",
            String.class,
            file));
    assertEquals(
        0,
        sql.queryForObject("SELECT count(*) FROM privacy_orphan_file_observations", Integer.class));
    assertTrue(flyway().validateWithResult().validationSuccessful);
  }

  @Test
  void upgradingV39PreservesApprovalAndUsesOriginalDatesForFiniteDocumentWindows()
      throws Exception {
    Flyway.configure()
        .dataSource(database.dataSource())
        .locations("classpath:db/migration")
        .target("39")
        .cleanDisabled(true)
        .load()
        .migrate();
    var sql = new org.springframework.jdbc.core.JdbcTemplate(database.dataSource());
    UUID owner =
        sql.queryForObject(
            "INSERT INTO users(email,password_hash,name,birth_date) VALUES('migration-driver-docs@example.test','synthetic-only','Synthetic migration driver','2000-01-01') RETURNING id",
            UUID.class);
    UUID identity = UUID.randomUUID(), license = UUID.randomUUID();
    for (UUID file : java.util.List.of(identity, license))
      sql.update(
          "INSERT INTO stored_files(id,owner_id,purpose,storage_key,content_type,byte_size,sha256) VALUES(?,?,?,?,'application/pdf',3,?)",
          file,
          owner,
          file.equals(identity) ? "identity" : "license",
          file.toString(),
          "a".repeat(64));
    UUID vehicle =
        sql.queryForObject(
            "INSERT INTO vehicles(owner_id,model,color,plate,capacity,status) VALUES(?,'Synthetic','Black','ABC1D23',4,'approved') RETURNING id",
            UUID.class,
            owner);
    UUID application =
        sql.queryForObject(
            "INSERT INTO driver_applications(user_id,vehicle_id,identity_file_id,license_file_id,status,created_at,reviewed_at) VALUES(?,?,?,?,'approved',now()-interval '80 days',now()-interval '40 days') RETURNING id",
            UUID.class,
            owner,
            vehicle,
            identity,
            license);
    String fileBefore =
        sql.queryForObject(
            "SELECT to_jsonb(stored_files)::text FROM stored_files WHERE id=?",
            String.class,
            identity);
    flyway().migrate();
    assertTrue(
        sql.queryForObject(
            "SELECT status='approved' AND identity_file_id=? AND license_file_id=? AND documents_retired_at IS NULL AND documents_expires_at=reviewed_at+interval '30 days' AND documents_expires_at<now() AND documents_policy_version='driver-documents-v1' FROM driver_applications WHERE id=?",
            Boolean.class,
            identity,
            license,
            application));
    assertEquals(
        fileBefore,
        sql.queryForObject(
            "SELECT to_jsonb(stored_files)::text FROM stored_files WHERE id=?",
            String.class,
            identity));
    assertEquals(
        "approved",
        sql.queryForObject("SELECT status FROM vehicles WHERE id=?", String.class, vehicle));
    assertEquals(
        2,
        sql.queryForObject(
            "SELECT count(*) FROM information_schema.columns WHERE table_schema='public' AND table_name='driver_applications' AND column_name IN ('identity_file_id','license_file_id') AND is_nullable='YES'",
            Integer.class));
    assertThrows(
        org.springframework.dao.DataIntegrityViolationException.class,
        () ->
            sql.update(
                "UPDATE driver_applications SET documents_retired_at=now() WHERE id=?",
                application));
    assertTrue(flyway().validateWithResult().validationSuccessful);
  }

  @Test
  void existingTypeScriptSchemaKeepsRowsTriggersAndIndexes() throws Exception {
    legacySchema(13);
    UUID universityId = UUID.randomUUID();
    try (var connection = database.dataSource().getConnection();
        var insert =
            connection.prepareStatement(
                "INSERT INTO universities(id,slug,name,city,state_code) VALUES(?,'preserved','Preserved University','Gama','DF')")) {
      insert.setObject(1, universityId);
      insert.executeUpdate();
    }
    String constraintSql =
        "SELECT c.relname||':'||co.conname||':'||pg_get_constraintdef(co.oid) FROM pg_constraint co JOIN pg_class c ON c.oid=co.conrelid WHERE co.connamespace='public'::regnamespace AND co.conname NOT IN ('transfers_status_check','mercado_pago_transfer_refunds') AND c.relname NOT IN ('flyway_schema_history','mercado_pago_orders','mercado_pago_refunds','mercado_pago_card_commands','mercado_pago_pix_commands','mercado_pago_observed_refunds')";
    String indexSql =
        "SELECT tablename||':'||indexdef FROM pg_indexes WHERE schemaname='public' AND indexname NOT IN ('subscriptions_previous_period_idx','reservation_subscription_confirmed_idx','auth_tokens_current_verification_idx','stored_files_unused_scan') AND tablename NOT IN ('flyway_schema_history','mercado_pago_orders','mercado_pago_refunds','mercado_pago_card_commands','mercado_pago_pix_commands','mercado_pago_observed_refunds')";
    String triggerSql =
        "SELECT pg_get_triggerdef(oid) FROM pg_trigger WHERE NOT tgisinternal AND tgname NOT IN ('mercado_pago_refund_state','mercado_pago_refund_no_delete','mercado_pago_refund_batch','mercado_pago_transfer_refunds','mercado_pago_observed_refund_state','mercado_pago_observed_refund_no_delete')";
    String columnSql =
        "SELECT c.relname||':'||a.attname||':'||format_type(a.atttypid,a.atttypmod)||':'||a.attnotnull||':'||coalesce(pg_get_expr(d.adbin,d.adrelid),'') FROM pg_attribute a JOIN pg_class c ON c.oid=a.attrelid LEFT JOIN pg_attrdef d ON d.adrelid=a.attrelid AND d.adnum=a.attnum WHERE c.relnamespace='public'::regnamespace AND c.relkind='r' AND c.relname NOT IN ('flyway_schema_history','mercado_pago_orders','mercado_pago_refunds','mercado_pago_card_commands','mercado_pago_pix_commands','mercado_pago_observed_refunds') AND a.attnum>0 AND NOT a.attisdropped AND NOT (c.relname='auth_tokens' AND a.attname IN ('invalidated_at','sent_at','last_delivery_error_at'))";
    String functionSql =
        "SELECT pg_get_functiondef(p.oid) FROM pg_proc p WHERE p.pronamespace='public'::regnamespace AND p.prokind='f' AND p.proname NOT IN ('subscription_state_guard','transfer_ledger_guard','mercado_pago_refund_guard','mercado_pago_refund_batch_guard','mercado_pago_observed_refund_guard')";
    // Compare every original object, while separately checking the explicit
    // receivable-account extension. New financial tables are not legacy objects.
    String legacyTables =
        definitions("SELECT tablename FROM pg_tables WHERE schemaname='public'").stream()
            .map(n -> "'" + n.replace("'", "''") + "'")
            .collect(java.util.stream.Collectors.joining(","));
    String legacyFunctions =
        definitions(
                "SELECT proname FROM pg_proc WHERE pronamespace='public'::regnamespace AND prokind='f'")
            .stream()
            .map(n -> "'" + n.replace("'", "''") + "'")
            .collect(java.util.stream.Collectors.joining(","));
    var oldAccountKinds =
        definitions(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname='ledger_accounts_kind_check'")
            .getFirst();
    var oldAccountStates =
        definitions(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname='users_account_status_check'")
            .getFirst();
    constraintSql +=
        " AND c.relname IN ("
            + legacyTables
            + ") AND co.conname NOT IN ('users_adult','auth_tokens_kind_check','ledger_accounts_kind_check','users_account_status_check','users_birth_date_not_null','users_live_birth_required','stored_file_erasure_state','stored_files_erasure_retry_after_not_null','stored_files_erasure_attempts_not_null','stored_files_erasure_attempts_check','stored_files_erasure_last_scan_at_not_null','stored_files_last_uploaded_at_not_null','driver_applications_status_check','driver_documents_retired_links','driver_applications_documents_expires_at_not_null','driver_applications_documents_policy_version_not_null','driver_applications_identity_file_id_not_null','driver_applications_license_file_id_not_null','privacy_neutral_entry_complete','privacy_neutral_journal_complete','privacy_neutral_transfer_complete','reservation_pin_pair','ride_reservations_pin_ciphertext_not_null','ride_reservations_pin_hash_not_null','ride_reservations_pin_expires_at_not_null')";
    indexSql +=
        " AND tablename IN ("
            + legacyTables
            + ") AND indexname NOT IN ('stored_files_pending_erasure','stored_files_suppression_scan','driver_documents_expiry','stored_files_erased_metadata_expiry','privacy_security_audit_expiry','privacy_verified_payment_facts','privacy_unsubmitted_request_scan','privacy_unsubmitted_payment_journal','privacy_unsubmitted_observation_payment','privacy_unsubmitted_observation_payment_id','privacy_failed_transfer_scan','privacy_failed_transfer_journal_entries','privacy_failed_transfer_receipt_id','privacy_failed_transfer_legacy_receipt_id','privacy_boarding_pin_expiry')";
    triggerSql +=
        " AND tgrelid IN (SELECT oid FROM pg_class WHERE relnamespace='public'::regnamespace AND relname IN ("
            + legacyTables
            + ")) AND tgname NOT IN ('audit_immutable','privacy_audit_delete_coordination','payment_no_delete','privacy_request_retirement','privacy_unsubmitted_request_coordination','privacy_unsubmitted_payment_coordination','ledger_transaction_immutable','ledger_entry_immutable','transfer_no_delete','privacy_neutral_entry_complete','privacy_neutral_journal_complete','privacy_neutral_transfer_complete','privacy_financial_entry_coordination','privacy_financial_journal_coordination','privacy_financial_transfer_coordination','privacy_financial_entry_no_truncate','privacy_financial_journal_no_truncate','privacy_financial_transfer_no_truncate','privacy_ride_pin_invalidation')";
    columnSql +=
        " AND c.relname IN ("
            + legacyTables
            + ") AND NOT (c.relname='ride_reservations' AND a.attname IN ('pin_ciphertext','pin_hash','pin_expires_at','pin_erased_at')) AND NOT (c.relname='users' AND a.attname IN ('birth_date','voluntarily_deactivated_at')) AND NOT (c.relname='stored_files' AND a.attname IN ('erasure_requested_at','erased_at','erasure_retry_after','erasure_attempts','erasure_last_scan_at','last_uploaded_at')) AND NOT (c.relname='driver_applications' AND a.attname IN ('identity_file_id','license_file_id','documents_expires_at','documents_retired_at','documents_policy_version'))";
    functionSql +=
        " AND p.proname IN ("
            + legacyFunctions
            + ") AND p.proname NOT IN ('nonnegative_ledger_account','reservation_state_guard')";
    // Compare original identities, so additive migrations do not need a growing exclusion list.
    constraintSql +=
        " AND c.relname||':'||co.conname IN ("
            + originalObjects(
                "SELECT c.relname||':'||co.conname FROM pg_constraint co JOIN pg_class c ON c.oid=co.conrelid WHERE co.connamespace='public'::regnamespace")
            + ")";
    indexSql +=
        " AND indexname IN ("
            + originalObjects("SELECT indexname FROM pg_indexes WHERE schemaname='public'")
            + ")";
    triggerSql +=
        " AND tgname IN ("
            + originalObjects("SELECT tgname FROM pg_trigger WHERE NOT tgisinternal")
            + ")";
    columnSql +=
        " AND c.relname||':'||a.attname IN ("
            + originalObjects(
                "SELECT c.relname||':'||a.attname FROM pg_attribute a JOIN pg_class c ON c.oid=a.attrelid WHERE c.relnamespace='public'::regnamespace AND c.relkind='r' AND a.attnum>0 AND NOT a.attisdropped")
            + ")";
    var constraints = definitions(constraintSql);
    var indexes = definitions(indexSql);
    var triggers = definitions(triggerSql);
    var columns = definitions(columnSql);
    var functions = definitions(functionSql);
    migrate();
    migrate();
    assertEquals(1, count("SELECT count(*) FROM universities WHERE slug='preserved'"));
    assertEquals(
        1,
        count("SELECT count(*) FROM flyway_schema_history WHERE type='BASELINE' AND version='13'"));
    assertEquals(47 - 13, count("SELECT count(*) FROM flyway_schema_history WHERE type='SQL'"));
    assertEquals(
        5,
        count(
            "SELECT count(*) FROM pg_tables WHERE schemaname='public' AND tablename IN ('mercado_pago_orders','mercado_pago_refunds','mercado_pago_card_commands','mercado_pago_pix_commands','mercado_pago_observed_refunds')"));
    assertEquals(constraints, definitions(constraintSql));
    assertEquals(indexes, definitions(indexSql));
    assertEquals(triggers, definitions(triggerSql));
    assertEquals(columns, definitions(columnSql));
    assertEquals(functions, definitions(functionSql));
    String newAccountKinds =
        definitions(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname='ledger_accounts_kind_check'")
            .getFirst();
    var kinds = java.util.regex.Pattern.compile("'([^']+)'").matcher(oldAccountKinds);
    while (kinds.find()) assertTrue(newAccountKinds.contains("'" + kinds.group(1) + "'"));
    assertTrue(newAccountKinds.contains("'passenger_receivable'"));
    String newAccountStates =
        definitions(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname='users_account_status_check'")
            .getFirst();
    var states = java.util.regex.Pattern.compile("'([^']+)'").matcher(oldAccountStates);
    while (states.find()) assertTrue(newAccountStates.contains("'" + states.group(1) + "'"));
    assertTrue(newAccountStates.contains("'closing'"));
    assertTrue(newAccountStates.contains("'deactivated'"));
    assertTrue(newAccountStates.contains("'banned'"));
    assertEquals(
        1,
        count(
            "SELECT count(*) FROM information_schema.columns WHERE table_schema='public' AND table_name='users' AND column_name='voluntarily_deactivated_at' AND data_type='timestamp with time zone' AND is_nullable='YES' AND column_default IS NULL"));
    assertEquals(
        1,
        count(
            "SELECT count(*) FROM pg_constraint WHERE conname='users_live_birth_required' AND pg_get_constraintdef(oid) LIKE '%deleted_at IS NOT NULL%birth_date IS NOT NULL%'"));
    assertEquals(
        1, count("SELECT count(*) FROM pg_constraint WHERE conname='stored_file_erasure_state'"));
    String balanceGuard =
        definitions(
                "SELECT pg_get_functiondef(oid) FROM pg_proc WHERE proname='nonnegative_ledger_account'")
            .getFirst();
    assertTrue(balanceGuard.contains("k='passenger_receivable' AND total>0"));
    assertTrue(balanceGuard.contains("AND total<0"));
    assertEquals(
        2,
        count(
            "SELECT count(*) FROM pg_indexes WHERE schemaname='public' AND indexname IN ('subscriptions_previous_period_idx','reservation_subscription_confirmed_idx')"));
    assertEquals(
        1,
        count(
            "SELECT count(*) FROM pg_views WHERE schemaname='public' AND viewname='subscriptions_with_open_rights'"));
    var status =
        definitions(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname='transfers_status_check'")
            .getFirst();
    for (String allowed : java.util.List.of("pending", "approved", "failed", "partially_refunded"))
      assertTrue(status.contains("'" + allowed + "'"));
    var guard =
        definitions(
                "SELECT pg_get_functiondef(oid) FROM pg_proc WHERE proname='transfer_ledger_guard'")
            .getFirst();
    assertTrue(guard.contains("Transfer requires matching reserved ledger"));
    assertTrue(guard.contains("Transfer result requires matching ledger"));
    assertTrue(guard.contains("Partial refund requires exact completed/restored ledger"));
  }

  @Test
  void upgradingV41PreservesAuditRowsAndChangesOnlyEligibleDeletionNotHistoryUpdates()
      throws Exception {
    Flyway.configure()
        .dataSource(database.dataSource())
        .locations("classpath:db/migration")
        .target("41")
        .cleanDisabled(true)
        .load()
        .migrate();
    var sql = new org.springframework.jdbc.core.JdbcTemplate(database.dataSource());
    UUID owner =
        sql.queryForObject(
            "INSERT INTO users(email,password_hash,name,birth_date) VALUES('migration-security@example.test','synthetic-only','Synthetic security owner','2000-01-01') RETURNING id",
            UUID.class);
    UUID expired =
        sql.queryForObject(
            "INSERT INTO audit_logs(actor_id,action,resource_type,resource_id,created_at) VALUES(?,'password_changed','user',?,now()-interval '7 months') RETURNING id",
            UUID.class,
            owner,
            owner);
    UUID financial =
        sql.queryForObject(
            "INSERT INTO audit_logs(actor_id,action,resource_type,resource_id,created_at) VALUES(?,'payment_event_processed','payment',?,now()-interval '7 years') RETURNING id",
            UUID.class,
            owner,
            UUID.randomUUID());
    String before =
        sql.queryForObject(
            "SELECT to_jsonb(a)::text FROM audit_logs a WHERE id=?", String.class, expired);
    flyway().migrate();
    assertEquals(
        before,
        sql.queryForObject(
            "SELECT to_jsonb(a)::text FROM audit_logs a WHERE id=?", String.class, expired));
    assertEquals(2, sql.queryForObject("SELECT count(*) FROM audit_logs", Integer.class));
    assertThrows(
        org.springframework.dao.DataIntegrityViolationException.class,
        () -> sql.update("UPDATE audit_logs SET metadata='{}' WHERE id=?", expired));
    assertThrows(
        org.springframework.dao.DataIntegrityViolationException.class,
        () -> sql.update("DELETE FROM audit_logs WHERE id=?", financial));
    assertTrue(
        sql.queryForObject(
            "SELECT privacy_security_audit_deadline(a)<now() FROM audit_logs a WHERE id=?",
            Boolean.class,
            expired));
    assertEquals(
        1,
        count(
            "SELECT count(*) FROM pg_trigger WHERE tgname='audit_immutable' AND pg_get_triggerdef(oid) LIKE '%protect_audit_lifecycle%'"));
    assertEquals(
        1,
        count(
            "SELECT count(*) FROM pg_trigger WHERE tgname='ledger_transaction_immutable' AND pg_get_triggerdef(oid) LIKE '%protect_financial_retirement%'"));
    assertTrue(flyway().validateWithResult().validationSuccessful);
  }

  @Test
  void upgradingV40PreservesFinancialRowsAndAllowsOnlyPurposeBoundContactRemoval()
      throws Exception {
    Flyway.configure()
        .dataSource(database.dataSource())
        .locations("classpath:db/migration")
        .target("40")
        .cleanDisabled(true)
        .load()
        .migrate();
    var sql = new org.springframework.jdbc.core.JdbcTemplate(database.dataSource());
    UUID owner =
        sql.queryForObject(
            "INSERT INTO users(email,password_hash,name,birth_date) VALUES('migration-checkout@example.test','synthetic-only','Synthetic checkout owner','2000-01-01') RETURNING id",
            UUID.class);
    UUID repayment =
        sql.queryForObject(
            "INSERT INTO passenger_debt_payments(passenger_id,amount_cents,payer_email,state,order_id,qr_code) VALUES(?,1000,'payer@example.test','PENDING','ORDTST-SYNTHETIC-MIGRATION','synthetic-qr') RETURNING id",
            UUID.class,
            owner);
    String before =
        sql.queryForObject(
            "SELECT to_jsonb(p)::text FROM passenger_debt_payments p WHERE id=?",
            String.class,
            repayment);
    flyway().migrate();
    assertEquals(
        before,
        sql.queryForObject(
            "SELECT to_jsonb(p)::text FROM passenger_debt_payments p WHERE id=?",
            String.class,
            repayment));
    assertEquals(
        2,
        count(
            "SELECT count(*) FROM information_schema.columns WHERE table_schema='public' AND table_name IN ('passenger_debt_payments','mercado_pago_pix_commands') AND column_name='payer_email' AND is_nullable='YES'"));
    sql.update(
        "UPDATE passenger_debt_payments SET provider_version=provider_version+1 WHERE id=?",
        repayment);
    assertNull(
        sql.queryForObject(
            "SELECT payer_email FROM passenger_debt_payments WHERE id=?", String.class, repayment));
    assertEquals(
        1000,
        sql.queryForObject(
            "SELECT amount_cents FROM passenger_debt_payments WHERE id=?",
            Integer.class,
            repayment));
    assertEquals(
        "PENDING",
        sql.queryForObject(
            "SELECT state FROM passenger_debt_payments WHERE id=?", String.class, repayment));
    assertThrows(
        org.springframework.dao.DataIntegrityViolationException.class,
        () ->
            sql.update(
                "UPDATE passenger_debt_payments SET payer_email='again@example.test' WHERE id=?",
                repayment));
    assertThrows(
        org.springframework.dao.DataIntegrityViolationException.class,
        () ->
            sql.update("UPDATE passenger_debt_payments SET amount_cents=1 WHERE id=?", repayment));
    assertTrue(flyway().validateWithResult().validationSuccessful);
  }

  @Test
  void upgradingV42PreservesMinimumEvidenceAndMoneyWithoutBulkErasure() throws Exception {
    Flyway.configure()
        .dataSource(database.dataSource())
        .locations("classpath:db/migration")
        .target("42")
        .cleanDisabled(true)
        .load()
        .migrate();
    var sql = new org.springframework.jdbc.core.JdbcTemplate(database.dataSource());
    UUID owner =
        sql.queryForObject(
            "INSERT INTO users(email,password_hash,name,birth_date) VALUES('migration-purpose@example.test','synthetic-only','Synthetic claimant','2000-01-01') RETURNING id",
            UUID.class);
    UUID record =
        sql.queryForObject(
            "INSERT INTO privacy_retained_records(user_id,category,resource_type,resource_id,content_ciphertext,reason,legal_basis,created_at,delete_after,hold_only) VALUES(?,'contract_identity','user',?,'synthetic-cipher-only','Synthetic obligation','regular_exercise_of_rights',now()-interval '7 years',now()-interval '2 years',false) RETURNING id",
            UUID.class,
            owner,
            owner);
    String before =
        sql.queryForObject(
            "SELECT to_jsonb(r)::text FROM privacy_retained_records r WHERE id=?",
            String.class,
            record);
    UUID protocol =
        sql.queryForObject(
            "INSERT INTO privacy_requests(user_id,kind,details_ciphertext,status,created_at,due_at,resolved_at) VALUES(?,'other','synthetic-retained-migration-text','completed',now()-interval '7 years',now()-interval '7 years'+interval '15 days',now()-interval '6 years') RETURNING id",
            UUID.class,
            owner);
    String protocolBefore =
        sql.queryForObject(
            "SELECT to_jsonb(p)::text FROM privacy_requests p WHERE id=?", String.class, protocol);
    try (var connection = database.dataSource().getConnection()) {
      connection.setAutoCommit(false);
      var txSql =
          new org.springframework.jdbc.core.JdbcTemplate(
              new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection, true));
      UUID
          clearing =
              txSql.queryForObject(
                  "INSERT INTO ledger_accounts(kind) VALUES('provider_clearing') RETURNING id",
                  UUID.class),
          credit =
              txSql.queryForObject(
                  "INSERT INTO ledger_accounts(owner_id,kind) VALUES(?,'passenger_credit') RETURNING id",
                  UUID.class,
                  owner);
      UUID tx =
          txSql.queryForObject(
              "INSERT INTO ledger_transactions(event_key,kind,reason,body_hash) VALUES('synthetic-migration-purpose','synthetic_credit','Synthetic migration credit',?) RETURNING id",
              UUID.class,
              "a".repeat(64));
      txSql.update(
          "INSERT INTO ledger_entries(transaction_id,account_id,cents) VALUES(?,?,-500),(?,?,500)",
          tx,
          clearing,
          tx,
          credit);
      connection.commit();
    }
    var money = definitions("SELECT to_jsonb(e)::text FROM ledger_entries e");
    var guards =
        definitions(
            "SELECT pg_get_triggerdef(oid) FROM pg_trigger WHERE tgname IN ('payment_ledger','transfer_ledger','parcel_ledger')");
    flyway().migrate();
    assertEquals(
        before,
        sql.queryForObject(
            "SELECT (to_jsonb(r)-'financial_purpose')::text FROM privacy_retained_records r WHERE id=?",
            String.class,
            record));
    assertNull(
        sql.queryForObject(
            "SELECT financial_purpose FROM privacy_retained_records WHERE id=?",
            String.class,
            record));
    assertEquals(
        protocolBefore,
        sql.queryForObject(
            "SELECT (to_jsonb(p)-'details_retired_at')::text FROM privacy_requests p WHERE id=?",
            String.class,
            protocol));
    assertNull(
        sql.queryForObject(
            "SELECT details_retired_at FROM privacy_requests WHERE id=?",
            java.sql.Timestamp.class,
            protocol));
    assertThrows(
        org.springframework.dao.DataIntegrityViolationException.class,
        () ->
            sql.update(
                "INSERT INTO privacy_retained_records(user_id,category,content_ciphertext,reason,legal_basis,created_at,delete_after) VALUES(?,'profile','synthetic-only','Synthetic invalid window','regular_exercise_of_rights',now(),now()-interval '1 day')",
                owner));
    assertEquals(money, definitions("SELECT to_jsonb(e)::text FROM ledger_entries e"));
    assertEquals(
        guards,
        definitions(
            "SELECT pg_get_triggerdef(oid) FROM pg_trigger WHERE tgname IN ('payment_ledger','transfer_ledger','parcel_ledger')"));
    assertThrows(
        org.springframework.dao.DataIntegrityViolationException.class,
        () -> sql.update("DELETE FROM privacy_retained_records WHERE id=?", record));
    assertTrue(flyway().validateWithResult().validationSuccessful);
  }

  @Test
  void upgradingV43PreservesExpiredQuotesAndOriginalSubmissionMarkersWithoutBulkDeletion()
      throws Exception {
    Flyway.configure()
        .dataSource(database.dataSource())
        .locations("classpath:db/migration")
        .target("43")
        .cleanDisabled(true)
        .load()
        .migrate();
    var sql = new org.springframework.jdbc.core.JdbcTemplate(database.dataSource());
    UUID owner =
        sql.queryForObject(
            "INSERT INTO users(email,password_hash,name,birth_date) VALUES('migration-submit@example.test','synthetic-only','Synthetic submit owner','2000-01-01') RETURNING id",
            UUID.class);
    UUID passenger =
        sql.queryForObject(
            "INSERT INTO users(email,password_hash,name,birth_date) VALUES('migration-quote@example.test','synthetic-only','Synthetic quote owner','2000-01-01') RETURNING id",
            UUID.class);
    UUID
        university =
            sql.queryForObject(
                "INSERT INTO universities(slug,name,city,state_code) VALUES('migration-quote','Synthetic migration University','Gama','DF') RETURNING id",
                UUID.class),
        vehicle =
            sql.queryForObject(
                "INSERT INTO vehicles(owner_id,model,color,plate,capacity,status) VALUES(?,'Synthetic','Black','ABC1D23',4,'approved') RETURNING id",
                UUID.class,
                owner);
    UUID
        route =
            sql.queryForObject(
                "INSERT INTO ride_routes(university_id,driver_id,region,meeting,distance_m) VALUES(?,?,'Synthetic region','Synthetic meeting',10000) RETURNING id",
                UUID.class,
                university,
                owner),
        price =
            sql.queryForObject(
                "INSERT INTO pricing_versions(university_id,fuel_cents,efficiency,wear_cents,reference_passengers,commission_bps,discount_bps) VALUES(?,600,10,10,3,1000,0) RETURNING id",
                UUID.class,
                university);
    UUID ride =
        sql.queryForObject(
            "INSERT INTO rides(university_id,route_id,driver_id,vehicle_id,direction,ride_date,departure_time,capacity,price_cents,commission_bps,pricing_version_id) VALUES(?,?,?,?,'ida',current_date-120,'18:30',4,100,1000,?) RETURNING id",
            UUID.class,
            university,
            route,
            owner,
            vehicle,
            price);
    UUID request =
        sql.queryForObject(
            "INSERT INTO ride_requests(passenger_id,driver_id,ride_id,type,status,preview,expires_at,created_at,updated_at) VALUES(?,?,?,'avulsa','accepted','{\"synthetic\":true}',now()-interval '120 days',now()-interval '140 days',now()-interval '120 days') RETURNING id",
            UUID.class,
            passenger,
            owner,
            ride);
    sql.update(
        "INSERT INTO request_lines(request_id,ride_id,cents,commission_bps) VALUES(?,?,100,1000)",
        request,
        ride);
    UUID payment =
        sql.queryForObject(
            "INSERT INTO payments(request_id,passenger_id,amount_cents,provider,status,expires_at,created_at) VALUES(?,?,100,'mercadopago','awaiting',now()-interval '120 days',now()-interval '130 days') RETURNING id",
            UUID.class,
            request,
            passenger);
    String
        quoteBefore =
            sql.queryForObject(
                "SELECT to_jsonb(q)::text FROM ride_requests q WHERE id=?", String.class, request),
        paymentBefore =
            sql.queryForObject(
                "SELECT to_jsonb(p)::text FROM payments p WHERE id=?", String.class, payment);
    UUID debt =
        sql.queryForObject(
            "INSERT INTO passenger_debt_payments(passenger_id,amount_cents,payer_email,state,started_at,created_at,expires_at) VALUES(?,100,'synthetic-payer@example.test','EXPIRED',now()-interval '130 days',now()-interval '140 days',now()-interval '120 days') RETURNING id",
            UUID.class,
            owner);
    String before =
        sql.queryForObject(
            "SELECT to_jsonb(d)::text FROM passenger_debt_payments d WHERE id=?",
            String.class,
            debt);
    var ledgerGuards =
        definitions(
            "SELECT pg_get_triggerdef(oid) FROM pg_trigger WHERE tgname IN ('payment_ledger','transfer_ledger','parcel_ledger')");
    flyway().migrate();
    assertEquals(
        before,
        sql.queryForObject(
            "SELECT to_jsonb(d)::text FROM passenger_debt_payments d WHERE id=?",
            String.class,
            debt));
    assertEquals(
        quoteBefore,
        sql.queryForObject(
            "SELECT to_jsonb(q)::text FROM ride_requests q WHERE id=?", String.class, request));
    assertEquals(
        paymentBefore,
        sql.queryForObject(
            "SELECT to_jsonb(p)::text FROM payments p WHERE id=?", String.class, payment));
    assertTrue(
        sql.queryForObject(
            "SELECT privacy_unsubmitted_request_expired(q) FROM ride_requests q WHERE id=?",
            Boolean.class,
            request));
    assertEquals(
        ledgerGuards,
        definitions(
            "SELECT pg_get_triggerdef(oid) FROM pg_trigger WHERE tgname IN ('payment_ledger','transfer_ledger','parcel_ledger')"));
    assertThrows(
        org.springframework.dao.DataIntegrityViolationException.class,
        () -> sql.update("UPDATE passenger_debt_payments SET started_at=NULL WHERE id=?", debt));
    assertThrows(
        org.springframework.dao.DataIntegrityViolationException.class,
        () -> sql.update("DELETE FROM passenger_debt_payments WHERE id=?", debt));
    assertEquals(
        1,
        sql.queryForObject(
            "SELECT count(*) FROM pg_trigger WHERE tgname='payment_no_delete' AND pg_get_triggerdef(oid) LIKE '%protect_unsubmitted_payment_retirement%'",
            Integer.class));
    assertTrue(flyway().validateWithResult().validationSuccessful);
  }

  @Test
  void partialLegacyHistoryAppliesOnlyPendingMigrations() throws Exception {
    legacySchema(11);
    migrate();
    assertEquals(13, count("SELECT count(*) FROM schema_migrations"));
    assertEquals(47 - 11, count("SELECT count(*) FROM flyway_schema_history WHERE type='SQL'"));
    assertEquals(1, count("SELECT count(*) FROM pg_trigger WHERE tgname='payment_ledger'"));
  }

  @Test
  void version19BackfillsVerifiedObservationsAndPreservesAnUnprovenLegacyHold() throws Exception {
    var before =
        Flyway.configure()
            .dataSource(database.dataSource())
            .locations("classpath:db/migration")
            .cleanDisabled(true)
            .target("18")
            .load();
    new LegacyFlywayMigrationStrategy(database.dataSource(), manifest).migrate(before);
    var sql = new org.springframework.jdbc.core.JdbcTemplate(database.dataSource());
    UUID university =
        sql.queryForObject(
            "INSERT INTO universities(slug,name,city,state_code) VALUES('history','History fixture','Gama','DF') RETURNING id",
            UUID.class);
    UUID owner =
        sql.queryForObject(
            "INSERT INTO users(email,password_hash,name,birth_date) VALUES('history-owner@example.test','synthetic','History owner','2000-01-01') RETURNING id",
            UUID.class);
    UUID driver =
        sql.queryForObject(
            "INSERT INTO users(email,password_hash,name,birth_date) VALUES('history-driver@example.test','synthetic','History driver','2000-01-01') RETURNING id",
            UUID.class);
    UUID vehicle =
        sql.queryForObject(
            "INSERT INTO vehicles(owner_id,model,color,plate,capacity,status) VALUES(?,'Synthetic','Black','ABC1D23',1,'approved') RETURNING id",
            UUID.class,
            driver);
    UUID route =
        sql.queryForObject(
            "INSERT INTO ride_routes(university_id,driver_id,region,meeting,distance_m) VALUES(?,?,'Gama','Synthetic stop',10000) RETURNING id",
            UUID.class,
            university,
            driver);
    UUID pricing =
        sql.queryForObject(
            "INSERT INTO pricing_versions(university_id,fuel_cents,efficiency,wear_cents,reference_passengers,commission_bps,discount_bps) VALUES(?,600,10,10,3,1000,1000) RETURNING id",
            UUID.class,
            university);
    var payments = new java.util.ArrayList<UUID>();
    // Minimal legacy metadata fixtures; no receipt or money settlement is simulated here.
    for (int i = 0; i < 2; i++) {
      UUID ride =
          sql.queryForObject(
              "INSERT INTO rides(university_id,route_id,driver_id,vehicle_id,direction,ride_date,departure_time,capacity,price_cents,commission_bps,pricing_version_id) VALUES(?,?,?,?,'ida',current_date+3+?,'18:30',1,100,1000,?) RETURNING id",
              UUID.class,
              university,
              route,
              driver,
              vehicle,
              i,
              pricing);
      UUID request =
          sql.queryForObject(
              "INSERT INTO ride_requests(passenger_id,driver_id,ride_id,type,status,expires_at) VALUES(?,?,?,'avulsa','accepted',now()+interval '1 hour') RETURNING id",
              UUID.class,
              owner,
              driver,
              ride);
      sql.update(
          "INSERT INTO request_lines(request_id,ride_id,cents,commission_bps) VALUES(?,?,100,1000)",
          request,
          ride);
      UUID payment =
          sql.queryForObject(
              "INSERT INTO payments(request_id,passenger_id,amount_cents,provider,status,expires_at) VALUES(?,?,100,'mercadopago','awaiting',now()+interval '1 hour') RETURNING id",
              UUID.class,
              request,
              owner);
      payments.add(payment);
      sql.update(
          "INSERT INTO mercado_pago_orders(payment_id,order_id,state,provider_status,status_detail,untracked_refunds) VALUES(?,?,'UNDER_REVIEW','created','unknown',true)",
          payment,
          "ORD" + payment.toString().replace("-", "").toUpperCase(java.util.Locale.ROOT));
    }
    sql.update(
        "INSERT INTO mercado_pago_card_commands(payment_id,body_hash,payload_ciphertext,started_at) VALUES(?,'synthetic','synthetic-ciphertext',now())",
        payments.getFirst());
    sql.update(
        "INSERT INTO mercado_pago_pix_commands(payment_id,amount_cents,payer_email,expiration_seconds) VALUES(?,100,'synthetic@example.test',1800)",
        payments.get(1));
    String reference = "REF" + "A".repeat(32), transaction = "PAY" + "B".repeat(32);
    var json = new com.fasterxml.jackson.databind.ObjectMapper();
    for (String status : java.util.List.of("processed", "processing")) {
      String payload =
          json.writeValueAsString(
              java.util.Map.of(
                  "payment",
                  payments.getFirst(),
                  "refunds",
                  java.util.List.of(
                      java.util.Map.of(
                          "id",
                          reference,
                          "transactionId",
                          transaction,
                          "cents",
                          50,
                          "status",
                          status))));
      sql.update(
          "INSERT INTO webhook_events(provider,event_id,payload_hash,payload,status) VALUES('mercadopago',?,'synthetic',?::jsonb,'processed')",
          status,
          payload);
    }
    String unverified =
        json.writeValueAsString(
            java.util.Map.of(
                "payment",
                payments.get(1),
                "refunds",
                java.util.List.of(
                    java.util.Map.of(
                        "id",
                        "REF" + "C".repeat(32),
                        "transactionId",
                        transaction,
                        "cents",
                        50,
                        "status",
                        "processed"))));
    sql.update(
        "INSERT INTO webhook_events(provider,event_id,payload_hash,payload,status) VALUES('mercadopago','unverified','synthetic',?::jsonb,'pending')",
        unverified);
    sql.update(
        "UPDATE mercado_pago_orders SET provider_status='charged_back',status_detail='in_process' WHERE payment_id=?",
        payments.get(1));
    migrate();
    migrate();
    assertEquals(1, count("SELECT count(*) FROM mercado_pago_observed_refunds"));
    assertEquals(
        1,
        sql.queryForObject(
            "SELECT attempts FROM mercado_pago_card_commands WHERE payment_id=?",
            Integer.class,
            payments.getFirst()));
    assertEquals(
        0,
        sql.queryForObject(
            "SELECT attempts FROM mercado_pago_pix_commands WHERE payment_id=?",
            Integer.class,
            payments.get(1)));
    assertEquals(
        "processed",
        sql.queryForObject(
            "SELECT status FROM mercado_pago_observed_refunds WHERE provider_reference=?",
            String.class,
            reference));
    assertEquals(
        50,
        sql.queryForObject(
            "SELECT amount_cents FROM mercado_pago_observed_refunds WHERE provider_reference=?",
            Integer.class,
            reference));
    assertFalse(
        sql.queryForObject(
            "SELECT refund_history_incomplete FROM mercado_pago_orders WHERE payment_id=?",
            Boolean.class,
            payments.getFirst()));
    assertTrue(
        sql.queryForObject(
            "SELECT refund_history_incomplete FROM mercado_pago_orders WHERE payment_id=?",
            Boolean.class,
            payments.get(1)));
    assertTrue(
        sql.queryForObject(
            "SELECT untracked_refunds FROM mercado_pago_orders WHERE payment_id=?",
            Boolean.class,
            payments.get(1)));
    assertTrue(
        sql.queryForObject(
            "SELECT chargeback_review FROM mercado_pago_orders WHERE payment_id=?",
            Boolean.class,
            payments.get(1)));
    assertFalse(
        sql.queryForObject(
            "SELECT chargeback_review FROM mercado_pago_orders WHERE payment_id=?",
            Boolean.class,
            payments.getFirst()));
    assertThrows(
        RuntimeException.class,
        () ->
            sql.update(
                "DELETE FROM mercado_pago_observed_refunds WHERE provider_reference=?", reference));
  }

  @Test
  void changedLegacyChecksumStopsBeforeBaseline() throws Exception {
    legacySchema(13);
    try (var connection = database.dataSource().getConnection();
        var statement = connection.createStatement()) {
      statement.execute(
          "UPDATE schema_migrations SET checksum='invalid' WHERE version='013_payment_transfer_guards.sql'");
    }
    assertThrows(IllegalStateException.class, this::migrate);
    assertEquals(
        0,
        count(
            "SELECT count(*) FROM pg_tables WHERE schemaname='public' AND tablename='flyway_schema_history'"));
  }

  @Test
  void gapInHistoryStopsBeforeChangingSchema() throws Exception {
    legacySchema(13);
    try (var connection = database.dataSource().getConnection();
        var statement = connection.createStatement()) {
      statement.execute("DELETE FROM schema_migrations WHERE version='005_mobility_indexes.sql'");
    }
    assertThrows(IllegalStateException.class, this::migrate);
    assertEquals(
        0,
        count(
            "SELECT count(*) FROM pg_tables WHERE schemaname='public' AND tablename='flyway_schema_history'"));
  }

  @Test
  void upgradingV44DoesNotRewriteOrDeleteExistingMoneyAndKeepsUpdatesForbidden() throws Exception {
    Flyway.configure()
        .dataSource(database.dataSource())
        .locations("classpath:db/migration")
        .target("44")
        .cleanDisabled(true)
        .load()
        .migrate();
    var sql = new org.springframework.jdbc.core.JdbcTemplate(database.dataSource());
    UUID owner =
        sql.queryForObject(
            "INSERT INTO users(email,password_hash,name,birth_date) VALUES('migration-v45@example.test','synthetic-only','Synthetic migration ledger','2000-01-01') RETURNING id",
            UUID.class);
    try (var connection = database.dataSource().getConnection();
        var statement = connection.createStatement()) {
      connection.setAutoCommit(false);
      statement.execute(
          "INSERT INTO ledger_accounts(owner_id,kind) VALUES('"
              + owner
              + "','passenger_credit');INSERT INTO ledger_accounts(kind) VALUES('provider_clearing')");
      statement.execute(
          "INSERT INTO ledger_transactions(event_key,kind,reason,body_hash,created_at) VALUES('synthetic-v45-preserved','synthetic_credit','Synthetic paid history','"
              + "a".repeat(64)
              + "',now()-interval '7 years')");
      statement.execute(
          "INSERT INTO ledger_entries(transaction_id,account_id,cents,created_at) SELECT t.id,a.id,CASE WHEN a.owner_id IS NULL THEN -500 ELSE 500 END,now()-interval '7 years' FROM ledger_transactions t CROSS JOIN ledger_accounts a");
      connection.commit();
    }
    var before = definitions("SELECT to_jsonb(e)::text FROM ledger_entries e");
    var headers = definitions("SELECT to_jsonb(j)::text FROM ledger_transactions j");
    flyway().migrate();
    assertEquals(before, definitions("SELECT to_jsonb(e)::text FROM ledger_entries e"));
    assertEquals(headers, definitions("SELECT to_jsonb(j)::text FROM ledger_transactions j"));
    assertEquals(
        500,
        count(
            "SELECT sum(cents) FROM ledger_entries e JOIN ledger_accounts a ON a.id=e.account_id WHERE a.owner_id IS NOT NULL"));
    assertThrows(
        org.springframework.dao.DataIntegrityViolationException.class,
        () -> sql.update("DELETE FROM ledger_entries"));
    assertThrows(
        org.springframework.dao.DataIntegrityViolationException.class,
        () -> sql.update("UPDATE ledger_transactions SET reason='changed'"));
    assertTrue(flyway().validateWithResult().validationSuccessful);
  }

  @Test
  void upgradingV45FreezesOriginalBoardingDeadlinesWithoutBulkErasureOrChangingMoney()
      throws Exception {
    Flyway.configure()
        .dataSource(database.dataSource())
        .locations("classpath:db/migration")
        .target("45")
        .cleanDisabled(true)
        .load()
        .migrate();
    var sql = new org.springframework.jdbc.core.JdbcTemplate(database.dataSource());
    var expired = com.unicarona.support.PaidReservationFixture.create(sql, -1, "America/Sao_Paulo");
    var future = com.unicarona.support.PaidReservationFixture.create(sql, 1, "America/Sao_Paulo");
    var entries = definitions("SELECT to_jsonb(e)::text FROM ledger_entries e");
    var payments = definitions("SELECT to_jsonb(p)::text FROM payments p");
    var parcels = definitions("SELECT to_jsonb(p)::text FROM parcels p");
    flyway().migrate();
    assertEquals(entries, definitions("SELECT to_jsonb(e)::text FROM ledger_entries e"));
    assertEquals(payments, definitions("SELECT to_jsonb(p)::text FROM payments p"));
    assertEquals(parcels, definitions("SELECT to_jsonb(p)::text FROM parcels p"));
    assertEquals(
        expired.cipher(),
        sql.queryForObject(
            "SELECT pin_ciphertext FROM ride_reservations WHERE id=?", String.class, expired.id()),
        "Migration freezes dates; coordinated maintenance performs physical suppression.");
    assertTrue(
        sql.queryForObject(
            "SELECT rr.pin_expires_at=((r.ride_date+1)::timestamp AT TIME ZONE u.time_zone) AND rr.pin_expires_at<now() FROM ride_reservations rr JOIN rides r ON r.id=rr.ride_id JOIN universities u ON u.id=r.university_id WHERE rr.id=?",
            Boolean.class,
            expired.id()));
    assertThrows(
        org.springframework.dao.DataIntegrityViolationException.class,
        () ->
            sql.update(
                "UPDATE ride_reservations SET pin_ciphertext=NULL,pin_hash=NULL WHERE id=?",
                future.id()));
    assertThrows(
        org.springframework.dao.DataIntegrityViolationException.class,
        () ->
            sql.update(
                "UPDATE ride_reservations SET status='boarded',boarded_at=now() WHERE id=?",
                expired.id()));
    assertEquals(
        1,
        sql.update(
            "UPDATE ride_reservations SET pin_ciphertext=NULL,pin_hash=NULL WHERE id=?",
            expired.id()));
    assertNotNull(
        sql.queryForObject(
            "SELECT pin_erased_at FROM ride_reservations WHERE id=?",
            java.sql.Timestamp.class,
            expired.id()));
    assertEquals(entries, definitions("SELECT to_jsonb(e)::text FROM ledger_entries e"));
    assertTrue(flyway().validateWithResult().validationSuccessful);
  }

  @Test
  void unknownMigrationStopsBeforeBaseline() throws Exception {
    legacySchema(13);
    try (var connection = database.dataSource().getConnection();
        var statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO schema_migrations(version,checksum) VALUES('014_unknown.sql','unknown')");
    }
    assertThrows(IllegalStateException.class, this::migrate);
    assertEquals(
        0,
        count(
            "SELECT count(*) FROM pg_tables WHERE schemaname='public' AND tablename='flyway_schema_history'"));
  }
}
