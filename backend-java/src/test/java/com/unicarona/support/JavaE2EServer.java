package com.unicarona.support;

import com.fasterxml.jackson.databind.*;
import com.unicarona.UniCaronaApplication;
import com.unicarona.auth.config.AuthSettings;
import com.unicarona.job.service.*;
import com.unicarona.notification.config.EmailSettings;
import com.unicarona.notification.provider.*;
import com.unicarona.notification.service.NotificationService;
import com.unicarona.payment.config.BillingSettings;
import com.unicarona.payment.dto.*;
import com.unicarona.payment.provider.*;
import com.unicarona.payment.service.LedgerService;
import com.unicarona.security.CryptoService;
import com.unicarona.seed.SeedService;
import com.unicarona.shared.ApiException;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;

/**
 * Test classpath only: Playwright controls Java services over stdin, never through public HTTP
 * routes.
 */
public final class JavaE2EServer {
  @TestConfiguration(proxyBeanMethods = false)
  public static class FaultConfiguration {
    @Bean
    FaultEmail faultEmail(EmailSettings settings, ObjectMapper json) {
      return new FaultEmail(
          new DevelopmentEmailProvider(settings.developmentPath(), settings.publicRoot(), json));
    }

    @Bean
    FaultPayment faultPayment(
        BillingSettings billing, AuthSettings auth, WebhookAuthentication signing) {
      return new FaultPayment(new DevelopmentPaymentProvider(billing, auth, signing));
    }
  }

  static final class FaultEmail implements EmailProvider {
    final AtomicBoolean fail = new AtomicBoolean();
    private final EmailProvider delegate;

    FaultEmail(EmailProvider delegate) {
      this.delegate = delegate;
    }

    @Override
    public void send(Message message) throws Exception {
      if (fail.get()) throw new IOException("Synthetic delivery failure");
      delegate.send(message);
    }
  }

  static final class FaultPayment implements PaymentProvider {
    final AtomicBoolean fail = new AtomicBoolean();
    private final PaymentProvider delegate;

    FaultPayment(PaymentProvider delegate) {
      this.delegate = delegate;
    }

    @Override
    public String name() {
      return delegate.name();
    }

    @Override
    public Checkout createCheckout(Input input) throws Exception {
      if (fail.get()) throw new IOException("Synthetic checkout failure");
      return delegate.createCheckout(input);
    }

    @Override
    public void authenticate(PaymentDto.Event event, String timestamp, String signature) {
      delegate.authenticate(event, timestamp, signature);
    }
  }

  public static void main(String[] ignored) throws Exception {
    PrintStream wire = System.out;
    System.setOut(System.err);
    ObjectMapper protocol = new ObjectMapper();
    try (var database = new RealPostgres()) {
      database.resetSchema();
      var arguments =
          new ArrayList<>(List.of(AuthTestSettings.arguments(database.apiConnectionUrl())));
      String origin = System.getenv("JAVA_E2E_ORIGIN");
      if (origin == null || !origin.matches("http://127\\.0\\.0\\.1:[0-9]{1,5}"))
        throw new IllegalStateException("A loopback E2E origin is required.");
      arguments.removeIf(
          value -> value.startsWith("--APP_ORIGIN=") || value.startsWith("--server.port="));
      arguments.addAll(
          List.of(
              "--APP_ORIGIN=" + origin,
              "--server.port=" + java.net.URI.create(origin).getPort(),
              "--UNICARONA_MODE=api",
              "--HOST=127.0.0.1",
              "--SEED_PASSWORD=" + AuthTestSettings.SEED_PASSWORD,
              "--STORAGE_PATH=" + required("JAVA_E2E_STORAGE"),
              "--DEV_MAIL_PATH=" + required("JAVA_E2E_MAIL"),
              "--PAYMENT_PROVIDER_TIMEOUT_MS="
                  + System.getenv().getOrDefault("JAVA_E2E_PAYMENT_TIMEOUT", "10000")));
      var application = UniCaronaApplication.application();
      application.addPrimarySources(List.of(FaultConfiguration.class));
      try (var app = application.run(arguments.toArray(String[]::new));
          var input =
              new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
        app.getBean(SeedService.class).run();
        prepareLegalFixtures(app);
        wire.println(
            protocol.writeValueAsString(
                Map.of("ready", true, "databaseUrl", database.apiConnectionUrl())));
        wire.flush();
        String line;
        while ((line = input.readLine()) != null) {
          JsonNode request = protocol.readTree(line);
          boolean close = "close".equals(request.path("action").asText());
          var response = new LinkedHashMap<String, Object>();
          response.put("id", request.path("id").asLong());
          try {
            response.put("result", dispatch(app, request, protocol));
          } catch (Exception error) {
            response.put(
                "error", error instanceof ApiException api ? api.code() : "JAVA_E2E_ACTION_FAILED");
          }
          wire.println(protocol.writeValueAsString(response));
          wire.flush();
          if (close) break;
        }
      }
    } catch (Exception error) {
      wire.println(
          protocol.writeValueAsString(Map.of("ready", false, "error", "JAVA_E2E_START_FAILED")));
      wire.flush();
      System.exit(1);
    }
  }

  private static void prepareLegalFixtures(ConfigurableApplicationContext app) {
    LegalTestFixtures.acceptedSeedBusinessUsers(app);
  }

  private static String required(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank())
      throw new IllegalStateException("E2E private path is required.");
    return value;
  }

  private static Object dispatch(
      ConfigurableApplicationContext app, JsonNode request, ObjectMapper json) throws Exception {
    return switch (request.path("action").asText()) {
      case "close" -> true;
      case "maintenance" -> app.getBean(MaintenanceService.class).run();
      case "reconciliation" -> app.getBean(LedgerService.class).reconciliation();
      case "processOne" -> {
        var email = app.getBean(FaultEmail.class);
        email.fail.set(request.path("fail").asBoolean());
        try {
          yield app.getBean(OutboxProcessor.class).processOne();
        } finally {
          email.fail.set(false);
        }
      }
      case "paymentFailure" -> {
        app.getBean(FaultPayment.class).fail.set(request.path("fail").asBoolean());
        yield true;
      }
      case "decrypt" ->
          app.getBean(CryptoService.class)
              .decrypt(request.path("ciphertext").asText(), AuthTestSettings.KEY);
      case "authLink" -> {
        var sql = app.getBean(org.springframework.jdbc.core.JdbcTemplate.class);
        var owner =
            sql.queryForObject(
                "SELECT id FROM users WHERE email=?", UUID.class, request.path("email").asText());
        var row =
            sql.queryForMap(
                "SELECT event_key,payload::text FROM outbox_jobs WHERE type='email' AND payload->>'ownerId'=? AND payload->>'kind'=? ORDER BY created_at DESC,id DESC LIMIT 1",
                owner.toString(),
                request.path("kind").asText());
        var payload = json.readTree((String) row.get("payload"));
        yield app.getBean(com.unicarona.privacy.service.PrivacyEvidenceCrypto.class)
            .openMail(
                com.unicarona.privacy.service.PrivacyEvidenceCrypto.Field.MAIL_URL,
                owner,
                (String) row.get("event_key"),
                payload.path("urlCiphertext").asText());
      }
      case "transferSignature" ->
          app.getBean(WebhookAuthentication.class)
              .signature(
                  "transfer-v1",
                  request.path("timestamp").asText(),
                  json.treeToValue(request.path("event"), TransferDto.Event.class));
      case "notify" ->
          app.getBean(NotificationService.class)
              .notify(
                  UUID.fromString(request.path("user").asText()),
                  request.path("title").asText(),
                  request.path("body").asText(),
                  request.path("route").asText(),
                  request.hasNonNull("ref") ? UUID.fromString(request.path("ref").asText()) : null);
      default -> throw new IllegalArgumentException("Unsupported E2E action.");
    };
  }
}
