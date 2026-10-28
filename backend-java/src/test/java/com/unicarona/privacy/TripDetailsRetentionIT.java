package com.unicarona.privacy;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.unicarona.UniCaronaApplication;
import com.unicarona.auth.config.AuthSettings;
import com.unicarona.file.config.StorageSettings;
import com.unicarona.payment.service.LedgerService;
import com.unicarona.privacy.config.*;
import com.unicarona.privacy.service.*;
import com.unicarona.ride.repository.RideReadRepository;
import com.unicarona.security.CryptoService;
import com.unicarona.support.*;
import com.unicarona.support.PaidReservationFixture.Paid;
import java.nio.file.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.support.TransactionTemplate;

class TripDetailsRetentionIT {
  public static class PrivateTemp implements org.junit.jupiter.api.io.TempDirFactory {
    public Path createTempDirectory(
        org.junit.jupiter.api.extension.AnnotatedElementContext e,
        org.junit.jupiter.api.extension.ExtensionContext c)
        throws java.io.IOException {
      return Files.createTempDirectory(
          Path.of(System.getProperty("java.io.tmpdir")), "trip-details-");
    }
  }

  @TempDir(factory = PrivateTemp.class)
  Path temporary;

  private static RealPostgres database;
  private ServletWebServerApplicationContext app;
  private JdbcTemplate sql;
  private String[] arguments;
  private Paid old, live;
  private UUID route;

  @BeforeAll
  static void database() {
    database = new RealPostgres();
  }

  @AfterAll
  static void databaseClose() {
    if (database != null) database.close();
  }

  @AfterEach
  void close() {
    if (app != null) {
      app.close();
      app = null;
    }
  }

  @BeforeEach
  void setup() throws Exception {
    database.resetSchema();
    var values = new ArrayList<>(List.of(AuthTestSettings.arguments(database.apiConnectionUrl())));
    values.addAll(
        List.of(
            "--STORAGE_PATH=" + temporary.resolve("files"),
            "--FRONTEND_PATH=" + temporary.resolve("public"),
            "--PRIVACY_OPERATIONS_ROOT=" + temporary.resolve("ops"),
            "--PRIVACY_ERASURE_JOURNAL_PATH=" + temporary.resolve("journal"),
            "--DEV_MAIL_PATH=" + temporary.resolve("mail")));
    arguments = values.toArray(String[]::new);
    var env = new MockEnvironment();
    for (String arg : arguments) {
      int at = arg.indexOf('=');
      env.withProperty(arg.substring(2, at), arg.substring(at + 1));
    }
    var json = new ObjectMapper();
    var crypto = new CryptoService();
    var auth = new AuthSettings(env);
    var files = new StorageSettings(env);
    new PrivacyBackupCatalog(
            new PrivacyOperationsSettings(env, files),
            new PrivacySettings(env),
            new PrivacySigningKeys(env, auth, crypto, json),
            json,
            Clock.systemUTC())
        .initialize();
    start();
    old = PaidReservationFixture.create(sql, -200, "Etc/UTC");
    live = PaidReservationFixture.create(sql, 1, "Etc/UTC");
    route = route(old);
    sql.update(
        "UPDATE ride_routes SET region='Synthetic private historical region',meeting='Synthetic private historical meeting' WHERE id=?",
        route);
    sql.update(
        "UPDATE rides SET notes='Synthetic private historical note',proposal_reason='Synthetic private proposal' WHERE id=?",
        old.ride());
    sql.update("UPDATE rides SET notes='Synthetic live note' WHERE id=?", live.ride());
  }

  private void start() {
    app =
        (ServletWebServerApplicationContext)
            new SpringApplicationBuilder(UniCaronaApplication.class).run(arguments);
    sql = app.getBean(JdbcTemplate.class);
  }

  private UUID route(Paid paid) {
    return sql.queryForObject("SELECT route_id FROM rides WHERE id=?", UUID.class, paid.ride());
  }

  private boolean retire(UUID route) throws Exception {
    return app.getBean(PrivacyTripDetailsRetention.class)
        .retire(new PrivacyTripDetailsRetention.Candidate(route), false);
  }

  private List<String> money() {
    return sql.queryForList(
        "SELECT 'payment:'||to_jsonb(p)::text FROM payments p UNION ALL SELECT 'parcel:'||to_jsonb(p)::text FROM parcels p UNION ALL SELECT 'journal:'||to_jsonb(p)::text FROM ledger_transactions p UNION ALL SELECT 'entry:'||to_jsonb(p)::text FROM ledger_entries p ORDER BY 1",
        String.class);
  }

  private void cleared() {
    assertTrue(
        sql.queryForObject(
            "SELECT notes='' AND proposal_reason IS NULL AND details_retired_at IS NOT NULL FROM rides WHERE id=?",
            Boolean.class,
            old.ride()));
    assertTrue(
        sql.queryForObject(
            "SELECT region='Região removida' AND meeting='Detalhes removidos' AND details_retired_at IS NOT NULL FROM ride_routes WHERE id=?",
            Boolean.class,
            route));
  }

  private UUID hold(String category, UUID resource) throws Exception {
    UUID id = UUID.randomUUID();
    String cipher =
        app.getBean(PrivacyEvidenceCrypto.class)
            .seal(
                PrivacyEvidenceCrypto.Field.HOLD_REASON,
                old.driver(),
                id,
                "Synthetic trip evidence case");
    app.getBean(TransactionTemplate.class)
        .executeWithoutResult(
            ignored -> {
              sql.update(
                  "INSERT INTO privacy_legal_holds(id,user_id,category,resource_type,resource_id,legal_basis,reason_ciphertext,created_by,review_at,expires_at) VALUES(?,?,?,'ride_route',?,'regular_exercise_of_rights',?,?,now()+interval '1 day',now()+interval '2 days')",
                  id,
                  old.driver(),
                  category,
                  resource,
                  cipher,
                  old.driver());
              app.getBean(PrivacyHoldRecovery.class).stage();
            });
    return id;
  }

  private void release(UUID hold) {
    app.getBean(TransactionTemplate.class)
        .executeWithoutResult(
            ignored -> {
              sql.update(
                  "UPDATE privacy_legal_holds SET released_at=now(),revision=revision+1,reviewed_at=now() WHERE id=?",
                  hold);
              app.getBean(PrivacyHoldRecovery.class).stage();
            });
  }

  private UUID routine(String status, int daysAgo) throws Exception {
    UUID
        course =
            sql.queryForObject(
                "INSERT INTO courses(university_id,slug,name) VALUES(?,?,'Synthetic trip cohort') RETURNING id",
                UUID.class,
                old.university(),
                "trip-" + UUID.randomUUID()),
        cohort =
            sql.queryForObject(
                "INSERT INTO classes(university_id,course_id,period_number,label) VALUES(?,?,3,'A') RETURNING id",
                UUID.class,
                old.university(),
                course);
    return sql.queryForObject(
        "INSERT INTO routines(route_id,driver_id,university_id,vehicle_id,class_id,weekdays,ida_time,ida_cents,capacity,pricing_version_id,status,notes,created_at,updated_at) SELECT route_id,driver_id,university_id,vehicle_id,?,ARRAY[1,3]::smallint[],'18:30',500,4,pricing_version_id,?,'Synthetic routine note',now()-(?*interval '1 day'),now()-(?*interval '1 day') FROM rides WHERE id=? RETURNING id",
        UUID.class,
        cohort,
        status,
        daysAgo,
        daysAgo,
        old.ride());
  }

  private JsonNode export(UUID owner) throws Exception {
    var output = new java.io.ByteArrayOutputStream();
    app.getBean(PrivacyExportService.class).write(owner, output);
    try (var zip =
        new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(output.toByteArray()))) {
      java.util.zip.ZipEntry entry;
      while ((entry = zip.getNextEntry()) != null)
        if (entry.getName().equals("dados.json"))
          return app.getBean(ObjectMapper.class).readTree(zip.readAllBytes());
    }
    throw new AssertionError("Missing owner data.");
  }

  @Test
  void originalDeadlineHidesExpiredTextBeforeTheWorkerAndKeepsCurrentTripText() {
    assertTrue(
        sql.queryForObject(
            "SELECT details_expires_at<now() FROM rides WHERE id=?", Boolean.class, old.ride()));
    var reader = app.getBean(RideReadRepository.class);
    var oldView = reader.detail(old.driver(), old.ride()).own();
    assertEquals("", oldView.notes());
    assertEquals("Região removida", oldView.region());
    assertEquals("Detalhes removidos", oldView.meeting());
    assertEquals("Synthetic live note", reader.detail(live.driver(), live.ride()).own().notes());
    assertEquals(
        "Synthetic private historical note",
        sql.queryForObject("SELECT notes FROM rides WHERE id=?", String.class, old.ride()));
  }

  @Test
  void finiteRetirementPreservesMoneyDistancesContractFactsAndLiveOffers() throws Exception {
    var before = money();
    var facts =
        sql.queryForMap(
            "SELECT price_cents,ride_date,departure_time,status,version FROM rides WHERE id=?",
            old.ride());
    assertTrue(retire(route));
    cleared();
    assertEquals(before, money());
    assertEquals(
        facts,
        sql.queryForMap(
            "SELECT price_cents,ride_date,departure_time,status,version FROM rides WHERE id=?",
            old.ride()));
    assertEquals(
        10000,
        sql.queryForObject("SELECT distance_m FROM ride_routes WHERE id=?", Integer.class, route));
    assertFalse(retire(route(live)));
    assertEquals(
        "Synthetic live note",
        sql.queryForObject("SELECT notes FROM rides WHERE id=?", String.class, live.ride()));
    assertEquals(0, app.getBean(LedgerService.class).reconciliation().difference());
  }

  @Test
  void locationCaseAndAnUnresolvedIncidentBlockErasureWithoutReopeningOrdinaryReads()
      throws Exception {
    UUID hold = hold("location", route);
    assertFalse(retire(route));
    assertEquals(
        "", app.getBean(RideReadRepository.class).detail(old.driver(), old.ride()).own().notes());
    release(hold);
    UUID incident =
        sql.queryForObject(
            "INSERT INTO occurrences(reporter_id,other_id,reservation_id,kind,reason) VALUES(?,?,?,'safety','Synthetic trip case') RETURNING id",
            UUID.class,
            old.passenger(),
            old.driver(),
            old.id());
    assertFalse(retire(route));
    sql.update(
        "UPDATE occurrences SET status='resolved',outcome='no_financial_change',decision='Synthetic resolved',reviewed_by=?,reviewed_at=now() WHERE id=?",
        old.driver(),
        incident);
    assertTrue(retire(route));
    cleared();
  }

  @Test
  void anotherRouteOrCategoryDoesNotPreserveAllOldOperationalText() throws Exception {
    UUID hold = hold("location", route(live));
    assertTrue(retire(route));
    release(hold);
    cleared();
  }

  @Test
  void anUnresolvedAccessRequestPreservesTheRequestedMaterialUntilHandled() throws Exception {
    var actor =
        new com.unicarona.security.Actor(
            old.passenger(), "passenger", "active", null, null, "", true, Set.of("ROLE_PASSENGER"));
    UUID request =
        app.getBean(PrivacyRightsService.class)
            .request(
                actor,
                new com.unicarona.privacy.dto.PrivacyDto.Request(
                    "access", "Synthetic access to retained trip details"),
                "trip-details-access-fixture")
            .id();
    assertFalse(retire(route));
    sql.update(
        "UPDATE privacy_requests SET status='completed',resolved_at=now() WHERE id=?", request);
    assertTrue(retire(route));
    cleared();
  }

  @Test
  void anActiveOrRecentlyPausedRoutineKeepsItsRouteButDoesNotRenewOldRideNotes() throws Exception {
    UUID routine = routine("active", 300);
    Timestamp deadline =
        sql.queryForObject(
            "SELECT details_expires_at FROM rides WHERE id=?", Timestamp.class, old.ride());
    assertTrue(retire(route));
    assertEquals(
        "", sql.queryForObject("SELECT notes FROM rides WHERE id=?", String.class, old.ride()));
    assertEquals(
        "Synthetic private historical meeting",
        sql.queryForObject("SELECT meeting FROM ride_routes WHERE id=?", String.class, route));
    sql.update("UPDATE routines SET status='paused' WHERE id=?", routine);
    assertFalse(retire(route));
    assertEquals(
        deadline,
        sql.queryForObject(
            "SELECT details_expires_at FROM rides WHERE id=?", Timestamp.class, old.ride()));
    assertThrows(
        RuntimeException.class,
        () ->
            sql.update(
                "UPDATE routines SET details_inactive_since=now()+interval '1 year' WHERE id=?",
                routine));
  }

  @Test
  void expiredPausedRoutineCannotReopenEvenBeforeItsPhysicalCleanup() throws Exception {
    UUID routine = routine("paused", 200);
    assertFalse(
        sql.queryForObject(
            "SELECT privacy_routine_details_visible(r) FROM routines r WHERE id=?",
            Boolean.class,
            routine));
    assertThrows(
        RuntimeException.class,
        () -> sql.update("UPDATE routines SET status='active' WHERE id=?", routine));
    assertTrue(retire(route));
    assertEquals(
        "", sql.queryForObject("SELECT notes FROM routines WHERE id=?", String.class, routine));
    assertNotNull(
        sql.queryForObject(
            "SELECT details_retired_at FROM routines WHERE id=?", Timestamp.class, routine));
    cleared();
  }

  @Test
  void retiredFieldsAndOriginalDeadlineCannotBeRestoredByARegularUpdate() throws Exception {
    Timestamp deadline =
        sql.queryForObject(
            "SELECT details_expires_at FROM rides WHERE id=?", Timestamp.class, old.ride());
    assertThrows(
        RuntimeException.class,
        () ->
            sql.update(
                "UPDATE rides SET details_expires_at=details_expires_at+interval '1 year' WHERE id=?",
                old.ride()));
    assertTrue(retire(route));
    for (String set :
        List.of(
            "notes='Reintroduced'", "proposal_reason='Reintroduced'", "details_retired_at=NULL"))
      assertThrows(
          RuntimeException.class,
          () -> sql.update("UPDATE rides SET " + set + " WHERE id=?", old.ride()));
    for (String set :
        List.of("meeting='Reintroduced'", "region='Reintroduced'", "details_retired_at=NULL"))
      assertThrows(
          RuntimeException.class,
          () -> sql.update("UPDATE ride_routes SET " + set + " WHERE id=?", route));
    assertEquals(
        deadline,
        sql.queryForObject(
            "SELECT details_expires_at FROM rides WHERE id=?", Timestamp.class, old.ride()));
  }

  @Test
  void ownerExportDistinguishesExpiredTextFromSpecificCaseMaterial() throws Exception {
    var data = export(old.driver());
    assertFalse(data.toString().contains("Synthetic private historical"));
    assertTrue(data.path("offeredRides").get(0).hasNonNull("details_expires_at"));
    UUID hold = hold("location", route);
    var held = export(old.driver());
    assertTrue(held.toString().contains("Synthetic private historical meeting"));
    assertTrue(held.toString().contains("Synthetic private historical note"));
    release(hold);
    assertFalse(export(old.driver()).toString().contains("Synthetic private historical"));
  }

  @Test
  void restoredOldTextIsSuppressedBeforeReadinessWithoutChangingItsDeadlineOrLedger()
      throws Exception {
    Timestamp deadline =
        sql.queryForObject(
            "SELECT details_expires_at FROM rides WHERE id=?", Timestamp.class, old.ride());
    var before = money();
    Path archive = temporary.resolve("old-trip.dump");
    database.dumpDatabaseArchive(archive);
    assertTrue(retire(route));
    app.close();
    app = null;
    database.resetSchema();
    database.restoreDatabaseArchive(archive);
    assertEquals(
        "Synthetic private historical note",
        new JdbcTemplate(database.dataSource())
            .queryForObject("SELECT notes FROM rides WHERE id=?", String.class, old.ride()));
    start();
    cleared();
    assertTrue(app.getBean(PrivacyReadiness.class).ready());
    assertEquals(
        deadline,
        sql.queryForObject(
            "SELECT details_expires_at FROM rides WHERE id=?", Timestamp.class, old.ride()));
    assertEquals(before, money());
  }

  @Test
  void aNewerIndependentHoldWinsBeforeRestoreCleanupAndItsReleaseDoesNotRenewText()
      throws Exception {
    Path archive = temporary.resolve("before-trip-hold.dump");
    var before = money();
    database.dumpDatabaseArchive(archive);
    UUID hold = hold("location", route);
    app.close();
    app = null;
    database.resetSchema();
    database.restoreDatabaseArchive(archive);
    start();
    assertTrue(app.getBean(PrivacyReadiness.class).ready());
    assertEquals(
        "Synthetic private historical meeting",
        sql.queryForObject("SELECT meeting FROM ride_routes WHERE id=?", String.class, route));
    assertEquals(before, money());
    release(hold);
    assertTrue(retire(route));
    app.close();
    app = null;
    database.resetSchema();
    database.restoreDatabaseArchive(archive);
    start();
    cleared();
    assertEquals(before, money());
  }

  @Test
  void busyRouteDoesNotMonopolizeTheMaintenanceBatch() throws Exception {
    var other = PaidReservationFixture.create(sql, -201, "Etc/UTC");
    UUID otherRoute = route(other);
    try (var connection = database.dataSource().getConnection()) {
      connection.setAutoCommit(false);
      try (var lock = connection.prepareStatement("SELECT privacy_trip_route_lock(?,false)")) {
        lock.setObject(1, route);
        lock.executeQuery().close();
      }
      assertEquals(1, app.getBean(PrivacyTripDetailsMaintenance.class).runOnce(100, () -> false));
      assertNotNull(
          sql.queryForObject(
              "SELECT details_retired_at FROM ride_routes WHERE id=?",
              Timestamp.class,
              otherRoute));
      assertNull(
          sql.queryForObject(
              "SELECT details_retired_at FROM ride_routes WHERE id=?", Timestamp.class, route));
      connection.rollback();
    }
    assertEquals(1, app.getBean(PrivacyTripDetailsMaintenance.class).runOnce(100, () -> false));
    cleared();
  }

  @Test
  void aConcurrentNewRideCannotAttachToARouteRetiredWhileItWaited() throws Exception {
    var executor = Executors.newSingleThreadExecutor();
    try (var connection = database.dataSource().getConnection()) {
      connection.setAutoCommit(false);
      try (var lock = connection.prepareStatement("SELECT privacy_trip_route_lock(?,false)")) {
        lock.setObject(1, route);
        lock.executeQuery().close();
      }
      var attempted = new CountDownLatch(1);
      var future =
          executor.submit(
              () -> {
                try (var writer = database.dataSource().getConnection()) {
                  try (var setup = writer.createStatement()) {
                    setup.execute("SET statement_timeout='5s'");
                  }
                  attempted.countDown();
                  try (var insert =
                      writer.prepareStatement(
                          "INSERT INTO rides(university_id,route_id,driver_id,vehicle_id,direction,ride_date,departure_time,capacity,price_cents,commission_bps,pricing_version_id) SELECT university_id,route_id,driver_id,vehicle_id,'ida',current_date+1,'19:00',4,500,1000,pricing_version_id FROM rides WHERE id=?")) {
                    insert.setObject(1, old.ride());
                    return insert.executeUpdate();
                  }
                }
              });
      assertTrue(attempted.await(2, TimeUnit.SECONDS));
      try (var update =
          connection.prepareStatement(
              "UPDATE ride_routes SET region='Região removida',meeting='Detalhes removidos',details_retired_at=clock_timestamp() WHERE id=?")) {
        update.setObject(1, route);
        update.executeUpdate();
      }
      connection.commit();
      assertThrows(ExecutionException.class, () -> future.get(6, TimeUnit.SECONDS));
      assertEquals(
          1,
          sql.queryForObject("SELECT count(*) FROM rides WHERE route_id=?", Integer.class, route));
    } finally {
      executor.shutdownNow();
    }
  }
}
