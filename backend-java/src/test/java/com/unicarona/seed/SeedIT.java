package com.unicarona.seed;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.unicarona.UniCaronaApplication;
import com.unicarona.file.storage.PrivateFileStorage;
import com.unicarona.payment.service.LedgerService;
import com.unicarona.security.CryptoService;
import com.unicarona.shared.ApiException;
import com.unicarona.support.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Dedicated real PostgreSQL and private synthetic files; CLI processes execute the packaged Java
 * jar.
 */
class SeedIT {
  private static RealPostgres database;
  private static ServletWebServerApplicationContext app;
  private static JdbcTemplate sql;
  private static SeedService seed;
  private static SeedScenarios scenarios;
  private static CryptoService crypto;
  private static PrivateFileStorage files;
  private static LedgerService ledger;
  private static TransactionTemplate tx;
  private static Map<String, Object> overrides;
  private static String server;
  @TempDir static Path storage;
  @TempDir Path processOutput;
  private final ObjectMapper json = new ObjectMapper();
  private final HttpClient http = HttpClient.newHttpClient();

  @BeforeAll
  static void start() throws Exception {
    database = new RealPostgres();
    database.resetSchema();
    var args = new ArrayList<>(List.of(AuthTestSettings.arguments(database.apiConnectionUrl())));
    args.add("--STORAGE_PATH=" + storage);
    args.add("--SEED_PASSWORD=" + AuthTestSettings.SEED_PASSWORD);
    app =
        (ServletWebServerApplicationContext)
            UniCaronaApplication.application().run(args.toArray(String[]::new));
    sql = app.getBean(JdbcTemplate.class);
    seed = app.getBean(SeedService.class);
    scenarios = app.getBean(SeedScenarios.class);
    crypto = app.getBean(CryptoService.class);
    files = app.getBean(PrivateFileStorage.class);
    ledger = app.getBean(LedgerService.class);
    tx = new TransactionTemplate(app.getBean(PlatformTransactionManager.class));
    server = "http://127.0.0.1:" + app.getWebServer().getPort();
    overrides = new HashMap<>();
    app.getEnvironment()
        .getPropertySources()
        .addFirst(new MapPropertySource("synthetic-seed-overrides", overrides));
  }

  @BeforeEach
  void cleanSyntheticData() throws Exception {
    // Immutable-history triggers remain enabled; recreate only the guarded disposable schema.
    database.resetSchema();
    app.getBean(org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy.class)
        .migrate(app.getBean(org.flywaydb.core.Flyway.class));
    try (var entries = Files.list(storage)) {
      entries
          .filter(p -> p.getFileName().toString().matches("[a-f0-9-]{36}"))
          .forEach(p -> files.remove(p.getFileName().toString()));
    }
    overrides.clear();
  }

  @AfterAll
  static void close() {
    if (app != null) app.close();
    if (database != null) database.close();
  }

  private int count(String query, Object... args) {
    return sql.queryForObject(query, Integer.class, args);
  }

  private UUID user(String email) {
    return sql.queryForObject("SELECT id FROM users WHERE email=?", UUID.class, email);
  }

  private long fileCount() throws Exception {
    try (var entries = Files.list(storage)) {
      return entries.filter(p -> p.getFileName().toString().matches("[a-f0-9-]{36}")).count();
    }
  }

  private List<Map<String, Object>> snapshot() {
    return sql.queryForList(
        "SELECT u.id,u.email,u.name,u.role,u.password_hash,u.account_status,s.university_id,s.course_id,s.class_id,s.period_number,s.shift,s.notices,d.status AS driver_status FROM users u LEFT JOIN student_profiles s ON s.user_id=u.id LEFT JOIN driver_profiles d ON d.user_id=u.id ORDER BY u.email");
  }

  private String login(String email, String password) throws Exception {
    var input =
        HttpRequest.newBuilder(URI.create(server + "/api/v1/auth/login"))
            .header("Origin", AuthTestSettings.ORIGIN)
            .header("Content-Type", "application/json")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    json.writeValueAsString(Map.of("email", email, "password", password))));
    var response = http.send(input.build(), HttpResponse.BodyHandlers.ofString());
    assertEquals(200, response.statusCode());
    return String.join(
        "; ",
        response.headers().allValues("set-cookie").stream().map(h -> h.split(";", 2)[0]).toList());
  }

  @Test
  void freshSeedCreatesMatchingCatalogAcademicRolesDriversAndPrivateSyntheticDocuments()
      throws Exception {
    var result = seed.run();
    assertEquals(4, result.users().size());
    assertEquals(21, count("SELECT count(*) FROM courses"));
    assertEquals(68, count("SELECT count(*) FROM regions"));
    assertEquals(3, count("SELECT count(*) FROM student_profiles"));
    assertEquals(2, count("SELECT count(*) FROM driver_profiles WHERE status='approved'"));
    assertEquals(2, count("SELECT count(*) FROM vehicles WHERE status='approved' AND capacity=3"));
    assertEquals(2, count("SELECT count(*) FROM driver_applications WHERE status='approved'"));
    assertEquals(4, count("SELECT count(*) FROM stored_files"));
    assertEquals(4, fileCount());
    assertEquals(0, count("SELECT count(*) FROM ledger_transactions"));
    assertEquals(0, count("SELECT count(*) FROM outbox_jobs"));
    assertEquals(0, ledger.reconciliation().difference());
    assertEquals(
        "admin",
        sql.queryForObject("SELECT role FROM users WHERE email='admin@example.com'", String.class));
    assertEquals(
        1,
        count(
            "SELECT count(DISTINCT class_id) FROM student_profiles WHERE user_id IN (?,?)",
            user("ana@example.com"),
            user("carolina@example.com")));
    assertEquals(
        5,
        sql.queryForObject(
            "SELECT period_number FROM student_profiles WHERE user_id=?",
            Integer.class,
            user("lucas@example.com")));
    assertEquals(
        "B",
        sql.queryForObject(
            "SELECT c.label FROM student_profiles s JOIN classes c ON c.id=s.class_id WHERE s.user_id=?",
            String.class,
            user("lucas@example.com")));
    for (var row : sql.queryForList("SELECT storage_key,sha256,byte_size FROM stored_files")) {
      byte[] data = files.get((String) row.get("storage_key"));
      assertEquals(row.get("byte_size"), data.length);
      assertEquals(
          row.get("sha256"),
          HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)));
    }
    for (String email : result.users().keySet()) {
      String hash =
          sql.queryForObject("SELECT password_hash FROM users WHERE email=?", String.class, email);
      assertTrue(hash.startsWith("$argon2id$"));
      assertTrue(crypto.verifyPassword(hash, AuthTestSettings.SEED_PASSWORD));
      String cookie = login(email, AuthTestSettings.SEED_PASSWORD);
      var response =
          http.send(
              HttpRequest.newBuilder(URI.create(server + "/api/v1/auth/me"))
                  .header("Cookie", cookie)
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode());
    }
  }

  @Test
  void repeatPreservesExistingPasswordsProfilesReviewsPricesAndFiles() throws Exception {
    var peer = seed.run();
    UUID ana = user("ana@example.com"), driver = user("carolina@example.com");
    String changed = crypto.hashPassword("Synthetic changed password 2026");
    sql.update(
        "UPDATE users SET name='Synthetic edited name',password_hash=?,account_status='suspended' WHERE id=?",
        changed,
        ana);
    sql.update(
        "UPDATE student_profiles SET notices=false,shift='morning',verification_status='verified',verification_source='admin' WHERE user_id=?",
        ana);
    sql.update("UPDATE driver_profiles SET status='rejected' WHERE user_id=?", driver);
    sql.update(
        "UPDATE driver_applications SET status='rejected',decision='Synthetic preserved review' WHERE user_id=?",
        driver);
    sql.update(
        "INSERT INTO pricing_versions(university_id,fuel_cents,efficiency,wear_cents,reference_passengers,commission_bps,discount_bps) VALUES(?,800,12,30,3,1500,1000)",
        peer.university());
    var before = snapshot();
    int prices = count("SELECT count(*) FROM pricing_versions");
    var second = seed.run();
    assertEquals(peer, second);
    assertEquals(before, snapshot());
    assertEquals(prices, count("SELECT count(*) FROM pricing_versions"));
    assertEquals(4, fileCount());
    assertEquals(4, count("SELECT count(*) FROM stored_files"));
    assertEquals(
        "Synthetic preserved review",
        sql.queryForObject(
            "SELECT decision FROM driver_applications WHERE user_id=?", String.class, driver));
  }

  @Test
  void concurrentSeedsCreateOneSetOfDriversFilesAndPriceVersion() throws Exception {
    try (var executor = Executors.newFixedThreadPool(2)) {
      var a = executor.submit(seed::run);
      var b = executor.submit(seed::run);
      assertEquals(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS));
    }
    assertEquals(4, count("SELECT count(*) FROM users"));
    assertEquals(2, count("SELECT count(*) FROM driver_applications"));
    assertEquals(4, fileCount());
    assertEquals(1, count("SELECT count(*) FROM pricing_versions"));
  }

  @Test
  void missingPasswordAndProductionLeaveDatabaseAndPrivateStorageUntouched() throws Exception {
    overrides.put("SEED_PASSWORD", "");
    assertEquals("SEED_PASSWORD_REQUIRED", assertThrows(ApiException.class, seed::run).code());
    overrides.put("NODE_ENV", "production");
    assertEquals("SEED_FORBIDDEN", assertThrows(ApiException.class, seed::run).code());
    assertEquals("SEED_FORBIDDEN", assertThrows(ApiException.class, scenarios::run).code());
    assertEquals(0, count("SELECT count(*) FROM universities"));
    assertEquals(0, fileCount());
  }

  @Test
  void nonDevelopmentScenarioGatewayIsRejectedBeforeBaseSeed() throws Exception {
    overrides.put("PAYMENT_PROVIDER", "mercadopago");
    assertEquals("SEED_FORBIDDEN", assertThrows(ApiException.class, scenarios::run).code());
    assertEquals(0, count("SELECT count(*) FROM users"));
    assertEquals(0, fileCount());
  }

  @Test
  void failedApplicationInsertRollsBackAllRowsAndRemovesAlreadyWrittenFiles() throws Exception {
    sql.execute(
        "CREATE FUNCTION synthetic_seed_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'Synthetic seed failure' USING ERRCODE='23514';END $$");
    sql.execute(
        "CREATE TRIGGER synthetic_seed_failure BEFORE INSERT ON driver_applications FOR EACH ROW EXECUTE FUNCTION synthetic_seed_failure()");
    try {
      assertThrows(RuntimeException.class, seed::run);
      assertEquals(0, count("SELECT count(*) FROM users"));
      assertEquals(0, count("SELECT count(*) FROM universities"));
      assertEquals(0, count("SELECT count(*) FROM stored_files"));
      assertEquals(0, fileCount());
    } finally {
      sql.execute("DROP TRIGGER synthetic_seed_failure ON driver_applications");
      sql.execute("DROP FUNCTION synthetic_seed_failure()");
    }
    seed.run();
    assertEquals(4, fileCount());
  }

  @Test
  void scenariosProducePendingRequestPaidAvulsaAndPaidMonthlyThroughRealDomainServices()
      throws Exception {
    acceptedScenarios();
    assertEquals(
        1, count("SELECT count(*) FROM ride_requests WHERE type='avulsa' AND status='pending'"));
    assertEquals(
        1, count("SELECT count(*) FROM ride_requests WHERE type='avulsa' AND status='confirmed'"));
    assertEquals(
        1, count("SELECT count(*) FROM ride_requests WHERE type='mensal' AND status='confirmed'"));
    assertEquals(2, count("SELECT count(*) FROM payments WHERE status='approved'"));
    assertEquals(1, count("SELECT count(*) FROM subscriptions WHERE status='active'"));
    assertTrue(
        count(
                "SELECT count(*) FROM ride_reservations WHERE subscription_id IS NOT NULL AND status='confirmed'")
            > 1);
    assertEquals(
        count("SELECT count(*) FROM parcels"),
        count("SELECT count(*) FROM parcels WHERE status='future'"));
    assertEquals(
        2,
        count(
            "SELECT count(*) FROM webhook_events WHERE event_id LIKE 'seed_payment_%' AND status='processed'"));
    assertTrue(ledger.reconciliation().received() > 0);
    assertEquals(0, ledger.reconciliation().difference());
    assertEquals(
        0, count("SELECT count(*) FROM ledger_transactions WHERE kind='synthetic_funding'"));
  }

  @Test
  void repeatedScenariosKeepEveryContractReservationParcelAndLedgerIdentity() {
    acceptedScenarios();
    var payments = sql.queryForList("SELECT id,status,amount_cents FROM payments ORDER BY id");
    var parcels =
        sql.queryForList("SELECT id,reservation_id,cents,status FROM parcels ORDER BY id");
    var journal = sql.queryForList("SELECT id,event_key FROM ledger_transactions ORDER BY id");
    var users = snapshot();
    acceptedScenarios();
    assertEquals(
        payments, sql.queryForList("SELECT id,status,amount_cents FROM payments ORDER BY id"));
    assertEquals(
        parcels,
        sql.queryForList("SELECT id,reservation_id,cents,status FROM parcels ORDER BY id"));
    assertEquals(
        journal, sql.queryForList("SELECT id,event_key FROM ledger_transactions ORDER BY id"));
    assertEquals(users, snapshot());
    assertEquals(0, ledger.reconciliation().difference());
  }

  @Test
  void independentSeedInstancesReplayWithoutReplacingDecisionsOrDocuments() throws Exception {
    try (var peer = new JavaApplicationPeer(app, database.apiConnectionUrl(), storage)) {
      assertEquals(200, peer.seed(false).path("status").asInt());
      var before = snapshot();
      var documents =
          sql.queryForList("SELECT id,storage_key,sha256 FROM stored_files ORDER BY id");
      seed.run();
      assertEquals(before, snapshot());
      assertEquals(
          documents,
          sql.queryForList("SELECT id,storage_key,sha256 FROM stored_files ORDER BY id"));
      assertEquals(200, peer.seed(false).path("status").asInt());
      assertEquals(before, snapshot());
      assertEquals(4, fileCount());
    }
  }

  @Test
  void javaScenarioReplaysThroughAnotherInstanceWithoutDuplicatePayments() throws Exception {
    acceptedScenarios();
    var before = sql.queryForList("SELECT id,event_key FROM ledger_transactions ORDER BY id");
    var parcels = sql.queryForList("SELECT id,cents,status FROM parcels ORDER BY id");
    try (var peer = new JavaApplicationPeer(app, database.apiConnectionUrl(), storage)) {
      var result = peer.seed(true);
      assertEquals(200, result.path("status").asInt(), result.path("body").path("code").asText());
      assertEquals(
          before, sql.queryForList("SELECT id,event_key FROM ledger_transactions ORDER BY id"));
      assertEquals(parcels, sql.queryForList("SELECT id,cents,status FROM parcels ORDER BY id"));
      acceptedScenarios();
      assertEquals(
          before, sql.queryForList("SELECT id,event_key FROM ledger_transactions ORDER BY id"));
    }
    assertEquals(0, ledger.reconciliation().difference());
  }

  @Test
  void peerScenarioReplaysThroughJavaWithoutDuplicatePayments() throws Exception {
    try (var peer = new JavaApplicationPeer(app, database.apiConnectionUrl(), storage)) {
      var result = peer.seed(true);
      assertEquals(200, result.path("status").asInt(), result.path("body").path("code").asText());
      var journal = sql.queryForList("SELECT id,event_key FROM ledger_transactions ORDER BY id");
      var parcels = sql.queryForList("SELECT id,cents,status FROM parcels ORDER BY id");
      var users = snapshot();
      acceptedScenarios();
      assertEquals(
          journal, sql.queryForList("SELECT id,event_key FROM ledger_transactions ORDER BY id"));
      assertEquals(parcels, sql.queryForList("SELECT id,cents,status FROM parcels ORDER BY id"));
      assertEquals(users, snapshot());
      assertEquals(0, ledger.reconciliation().difference());
    }
  }

  @Test
  void packagedSeedCommandStartsWithoutHttpAndMainClosesItsProcess() throws Exception {
    runJar("seed");
    assertEquals(4, count("SELECT count(*) FROM users"));
    assertEquals(0, count("SELECT count(*) FROM ledger_transactions"));
    assertEquals(4, fileCount());
  }

  @Test
  void packagedMigrationCommandValidatesSchemaAndExitsWithoutSeeding() throws Exception {
    runJar("migrate");
    assertEquals(
        app.getBean(org.flywaydb.core.Flyway.class).info().all().length,
        count("SELECT count(*) FROM flyway_schema_history WHERE success"));
    assertEquals(0, count("SELECT count(*) FROM users"));
    assertEquals(0, count("SELECT count(*) FROM outbox_jobs"));
    assertEquals(0, fileCount());
  }

  @Test
  void packagedScenarioCommandPaysSyntheticReceiptsAndExitsWithoutNodeRuntime() throws Exception {
    runJar("seed-scenarios");
    assertEquals(2, count("SELECT count(*) FROM payments WHERE status='approved'"));
    assertEquals(1, count("SELECT count(*) FROM subscriptions"));
    assertEquals(0, ledger.reconciliation().difference());
  }

  @Test
  void seedServicesRejectAmbientTransactionsBeforeAnySeeding() throws Exception {
    assertThrows(
        org.springframework.transaction.IllegalTransactionStateException.class,
        () -> tx.executeWithoutResult(status -> seed.run()));
    assertThrows(
        org.springframework.transaction.IllegalTransactionStateException.class,
        () -> tx.executeWithoutResult(status -> scenarios.run()));
    assertEquals(0, count("SELECT count(*) FROM users"));
    assertEquals(0, fileCount());
  }

  private void acceptedScenarios() {
    seed.run();
    LegalTestFixtures.acceptedSeedBusinessUsers(app);
    scenarios.run();
  }

  private void runJar(String mode) throws Exception {
    if ("seed-scenarios".equals(mode)) {
      seed.run();
      LegalTestFixtures.acceptedSeedBusinessUsers(app);
    }

    Path
        java =
            Path.of(
                System.getProperty("java.home"),
                "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java"),
        jar = Path.of("target/unicarona-backend-1.0.0-SNAPSHOT.jar").toAbsolutePath();
    var command = new ArrayList<>(List.of(java.toString(), "-jar", jar.toString()));
    command.addAll(List.of(AuthTestSettings.arguments(database.apiConnectionUrl())));
    command.add("--UNICARONA_MODE=" + mode);
    command.add("--SEED_PASSWORD=" + AuthTestSettings.SEED_PASSWORD);
    command.add("--STORAGE_PATH=" + storage);
    var process =
        new ProcessBuilder(command)
            .redirectErrorStream(true)
            .redirectOutput(processOutput.resolve("seed-process.log").toFile())
            .start();
    try {
      assertTrue(process.waitFor(45, TimeUnit.SECONDS), "Packaged seed command must exit");
      assertEquals(0, process.exitValue(), "Packaged seed command must succeed");
    } finally {
      if (process.isAlive()) process.destroyForcibly();
    }
  }
}
