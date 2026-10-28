package com.unicarona.shared;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unicarona.UniCaronaApplication;
import com.unicarona.support.RealPostgres;
import com.unicarona.support.AuthTestSettings;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;

class FoundationHttpIT {
    private static RealPostgres database;
    private static ServletWebServerApplicationContext app;
    private static String origin;
    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();
    @BeforeAll static void start() throws Exception {
        database = new RealPostgres(); database.resetSchema();
        // Command-line property takes precedence over the private local environment.
        app = (ServletWebServerApplicationContext) new SpringApplicationBuilder(UniCaronaApplication.class)
            .run(AuthTestSettings.arguments(database.apiConnectionUrl()));
        origin = "http://127.0.0.1:" + app.getWebServer().getPort();
        var sql = app.getBean(JdbcTemplate.class);
        sql.update("INSERT INTO universities(slug,name,city,state_code) VALUES('active','Active University','Gama','DF'),('inactive','Inactive University','Gama','DF')");
        sql.update("UPDATE universities SET active=false WHERE slug='inactive'");
        sql.update("INSERT INTO courses(university_id,slug,name) SELECT id,'course','Course '||slug FROM universities");
        sql.update("INSERT INTO regions(slug,name,state_code) VALUES('gama','Gama','DF')");
    }
    @AfterAll static void close() { if (app != null) app.close(); if (database != null) database.close(); }
    private HttpResponse<String> get(String path) throws Exception { return http.send(HttpRequest.newBuilder(URI.create(origin + path)).GET().build(), HttpResponse.BodyHandlers.ofString()); }
    @Test void healthAndReadinessUseCompatibleBodiesAndRequestIds() throws Exception {
        var response = get("/health"); assertEquals(200, response.statusCode()); assertEquals("{\"status\":\"ok\"}", response.body());
        assertTrue(response.headers().firstValue("x-request-id").isPresent());
        assertEquals("{\"status\":\"ready\"}", get("/health/ready").body());
    }
    @Test void repositoriesFilterInactiveUniversitiesWithoutExposingEntities() throws Exception {
        var response = get("/api/v1/catalogs"); assertEquals(200, response.statusCode());
        var catalog = json.readTree(response.body());
        assertEquals(1, catalog.get("universities").size()); assertEquals(1, catalog.get("courses").size());
        assertEquals("DF", catalog.get("universities").get(0).get("state_code").asText());
        assertTrue(catalog.get("courses").get(0).has("university_id"));
        assertFalse(response.body().contains("timeZone")); assertFalse(response.body().contains("password"));
        assertEquals("no-store", response.headers().firstValue("cache-control").orElse(""));
    }
    @Test void staticFrontendAndSwaggerAreServedByJava() throws Exception {
        assertEquals(200, get("/dashboard.html").statusCode());
        assertTrue(get("/dashboard.html").body().contains("UniCarona"));
        var docs = get("/api/docs/json"); assertEquals(200, docs.statusCode());
        assertTrue(json.readTree(docs.body()).get("paths").has("/api/v1/catalogs"));
        assertEquals("{\"paymentEnvironment\":\"test\",\"emailEnvironment\":\"local\"}", get("/api/v1/runtime").body());
    }
    @Test void dashboardNonceMatchesItsPolicyRotatesAndIsNotReusedThroughConditionalCaching() throws Exception {
        var first=get("/dashboard.html");var second=get("/dashboard.html");
        var pattern=java.util.regex.Pattern.compile("<meta name=\"unicarona-csp-nonce\" content=\"([A-Za-z0-9_-]{32})\">");
        var a=pattern.matcher(first.body());var b=pattern.matcher(second.body());assertTrue(a.find());assertTrue(b.find());assertNotEquals(a.group(1),b.group(1));
        String policy=first.headers().firstValue("content-security-policy").orElseThrow();
        assertTrue(policy.contains("'nonce-"+a.group(1)+"'"));assertFalse(policy.substring(policy.indexOf("script-src"),policy.indexOf("; style-src")).contains("unsafe-inline"));assertFalse(policy.contains("unsafe-eval"));
        assertTrue(policy.contains("https://secure-fields.mercadopago.com"));assertTrue(policy.contains("https://api-static.mercadopago.com"));assertEquals("no-store",first.headers().firstValue("cache-control").orElseThrow());
        var conditional=http.send(HttpRequest.newBuilder(URI.create(origin+"/dashboard.html")).header("If-Modified-Since","Sat, 10 Oct 2099 00:00:00 GMT").header("If-None-Match","\"cached\"").GET().build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(200,conditional.statusCode());var c=pattern.matcher(conditional.body());assertTrue(c.find());assertNotEquals(a.group(1),c.group(1));assertTrue(conditional.headers().firstValue("content-security-policy").orElseThrow().contains("'nonce-"+c.group(1)+"'"));
        assertFalse(get("/api/v1/runtime").headers().firstValue("content-security-policy").orElseThrow().contains("nonce-"));
    }
    @Test void checksumChangeMakesReadinessFailWithoutBreakingLiveness() throws Exception {
        var sql = app.getBean(JdbcTemplate.class);
        String original = sql.queryForObject("SELECT checksum FROM schema_migrations WHERE version='013_payment_transfer_guards.sql'", String.class);
        try {
            sql.update("UPDATE schema_migrations SET checksum='changed' WHERE version='013_payment_transfer_guards.sql'");
            var response = get("/health/ready"); assertEquals(503, response.statusCode()); assertEquals("{\"status\":\"not_ready\"}", response.body());
            assertEquals(200, get("/health").statusCode());
        } finally { sql.update("UPDATE schema_migrations SET checksum=? WHERE version='013_payment_transfer_guards.sql'", original); }
    }
    @Test void protectedOperationsStayClosedAndReturnTheApiErrorEnvelope() throws Exception {
        var response = get("/api/v1/users/me"); assertEquals(401, response.statusCode());
        var error = json.readTree(response.body());
        assertEquals("UNAUTHENTICATED", error.get("code").asText());
        assertEquals("Entre na sua conta para continuar.", error.get("message").asText());
        assertEquals(0, error.get("details").size());
        assertEquals(response.headers().firstValue("x-request-id").orElseThrow(), error.get("requestId").asText());
        assertFalse(response.body().contains("stack")); assertFalse(response.body().contains("password"));
    }
    @Test void unknownStaticResourcesReturnSanitizedNotFoundWithRequestId() throws Exception {
        var response = get("/missing-file.html"); assertEquals(404, response.statusCode());
        var error = json.readTree(response.body()); assertEquals("NOT_FOUND", error.get("code").asText());
        assertEquals(response.headers().firstValue("x-request-id").orElseThrow(), error.get("requestId").asText());
    }
}
