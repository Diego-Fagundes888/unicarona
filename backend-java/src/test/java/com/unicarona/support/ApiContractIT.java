package com.unicarona.support;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.unicarona.UniCaronaApplication;
import com.unicarona.seed.SeedService;
import java.net.*;
import java.net.http.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Fixed HTTP expectations plus live inventory, authorization and HEAD checks; no runtime oracle.
 */
class ApiContractIT {
  private static RealPostgres database;
  private static ServletWebServerApplicationContext app;
  private static JdbcTemplate sql;
  private static String server;
  private static JsonNode contract;
  @TempDir static Path storage;
  private final ObjectMapper json = new ObjectMapper();
  private final HttpClient http = HttpClient.newHttpClient();

  private record Session(String cookie, String csrf) {
    @Override
    public String toString() {
      return "Session[redacted]";
    }
  }

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
    app.getBean(SeedService.class).run();
    LegalTestFixtures.acceptedSeedBusinessUsers(app);
    server = "http://127.0.0.1:" + app.getWebServer().getPort();
    try (var input =
        ApiContractIT.class.getResourceAsStream("/api/development-status-contract.json")) {
      assertNotNull(input);
      contract = new ObjectMapper().readTree(input).path("roles");
    }
  }

  @AfterAll
  static void close() {
    if (app != null) app.close();
    if (database != null) database.close();
  }

  @Test
  void currentDevelopmentInventoryExactlyMatchesTheReviewedContract() throws Exception {
    var documentation = json.readTree(send("GET", "/api/docs/json", null, null, true).body());
    var actual = new TreeSet<String>();
    documentation
        .path("paths")
        .fields()
        .forEachRemaining(
            path ->
                path.getValue()
                    .fieldNames()
                    .forEachRemaining(
                        method -> {
                          if (Set.of("get", "post", "patch", "delete", "put", "head", "options")
                              .contains(method)) actual.add(key(method, path.getKey()));
                        }));
    var expected = new TreeSet<String>();
    contract
        .path("anonymous")
        .forEach(
            item -> expected.add(key(item.path("method").asText(), item.path("path").asText())));
    assertEquals(132, expected.size());
    assertEquals(expected, actual);
  }

  private static String key(String method, String path) {
    return method.toUpperCase(Locale.ROOT) + " " + path.replaceAll("\\{[^}]+\\}", "{}");
  }

  @Test
  void anonymousOperationsPreserveReviewedStatusAndErrorCodes() throws Exception {
    audit("anonymous", null);
  }

  @Test
  void passengerReadsPreserveReviewedStatusAndErrorCodes() throws Exception {
    audit("passenger", login("ana@example.com"));
  }

  @Test
  void administratorReadsPreserveReviewedStatusAndErrorCodes() throws Exception {
    audit("admin", login("admin@example.com"));
  }

  private void audit(String role, Session session) throws Exception {
    int count = 0;
    for (var expected : contract.path(role)) {
      String method = expected.path("method").asText(),
          path = readPath(expected.path("path").asText());
      sql.update("DELETE FROM rate_limits");
      var response = send(method, path, Map.of(), session, true);
      assertEquals(
          expected.path("status").asInt(), response.statusCode(), role + " " + method + " " + path);
      var body = json.readTree(response.body());
      assertNotNull(body);
      if (expected.has("code"))
        assertEquals(expected.path("code").asText(), body.path("code").asText(), path);
      else assertFalse(body.has("code"), path);
      count++;
    }
    assertEquals(role.equals("anonymous") ? 132 : 59, count);
  }

  private String readPath(String template) {
    String path = template.replaceAll("\\{[^}]+\\}", "00000000-0000-4000-8000-000000000001");
    if (Set.of("/api/v1/admin/pricing", "/api/v1/admin/calendar").contains(path)) {
      path +=
          "?universityId="
              + sql.queryForObject(
                  "SELECT id FROM universities WHERE slug='uniceplac'", UUID.class);
      if (template.endsWith("calendar")) path += "&startDate=2026-01-01&endDate=2026-12-31";
    }
    return path;
  }

  @Test
  void unknownPathsAndUnsupportedMethodsHaveExplicitNotFoundAndOriginContracts() throws Exception {
    for (Session session : Arrays.asList(null, login("ana@example.com"))) {
      for (var route :
          List.of(
              new String[] {"GET", "/api/v1/missing"},
              new String[] {"POST", "/api/v1/catalogs"},
              new String[] {"PATCH", "/api/v1/auth/me"},
              new String[] {"OPTIONS", "/api/v1/users/me"},
              new String[] {"GET", "/api/v1/auth/login"},
              new String[] {"POST", "/api/v1/missing"})) {
        var response = send(route[0], route[1], Map.of(), session, true);
        assertEquals(404, response.statusCode(), Arrays.toString(route));
        assertEquals("NOT_FOUND", json.readTree(response.body()).path("code").asText());
      }
    }
    var response = send("POST", "/api/v1/missing", Map.of(), null, false);
    assertEquals(403, response.statusCode());
    assertEquals("ORIGIN_INVALID", json.readTree(response.body()).path("code").asText());
  }

  @Test
  void everyImplicitHeadMatchesItsReviewedAuthorizationAndHasAnEmptyBody() throws Exception {
    for (String role : List.of("anonymous", "passenger", "admin")) {
      Session session =
          role.equals("anonymous")
              ? null
              : login(role.equals("admin") ? "admin@example.com" : "ana@example.com");
      for (var expected : contract.path(role)) {
        if (!"GET".equals(expected.path("method").asText())) continue;
        sql.update("DELETE FROM rate_limits");
        var response = send("HEAD", readPath(expected.path("path").asText()), null, session, true);
        assertEquals(
            expected.path(expected.has("headStatus") ? "headStatus" : "status").asInt(),
            response.statusCode(),
            role + " HEAD " + expected.path("path"));
        assertEquals("", response.body());
      }
      assertEquals(
          422, send("HEAD", "/api/v1/rides?limit=1&limit=2", null, session, true).statusCode());
    }
    UUID document =
        sql.queryForObject(
            "SELECT f.id FROM stored_files f JOIN users u ON u.id=f.owner_id WHERE u.email='carolina@example.com' AND f.purpose='identity'",
            UUID.class);
    for (Session session :
        Arrays.asList(null, login("carolina@example.com"), login("ana@example.com"))) {
      var get = send("GET", "/api/v1/files/" + document, null, session, true);
      var head = send("HEAD", "/api/v1/files/" + document, null, session, true);
      assertEquals(get.statusCode(), head.statusCode());
      assertEquals("", head.body());
    }
  }

  private HttpResponse<String> send(
      String method, String path, Object body, Session session, boolean origin) throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create(server + path))
            .header("Content-Type", "application/json");
    if (origin) request.header("Origin", AuthTestSettings.ORIGIN);
    if (session != null)
      request.header("Cookie", session.cookie()).header("x-csrf-token", session.csrf());
    request.method(
        method,
        body == null || Set.of("GET", "HEAD", "OPTIONS").contains(method)
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
    return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private Session login(String email) throws Exception {
    sql.update("DELETE FROM rate_limits");
    var response =
        send(
            "POST",
            "/api/v1/auth/login",
            Map.of("email", email, "password", AuthTestSettings.SEED_PASSWORD),
            null,
            true);
    assertEquals(200, response.statusCode());
    var cookies = new LinkedHashMap<String, String>();
    for (String header : response.headers().allValues("set-cookie")) {
      String[] pair = header.split(";", 2)[0].split("=", 2);
      cookies.put(pair[0], pair[1]);
    }
    return new Session(
        String.join(
            "; ", cookies.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).toList()),
        cookies.get("uc_csrf"));
  }
}
