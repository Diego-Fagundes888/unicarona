package com.unicarona.support;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.unicarona.UniCaronaApplication;
import com.unicarona.file.config.StorageSettings;
import com.unicarona.job.service.*;
import com.unicarona.notification.provider.EmailProvider;
import com.unicarona.payment.ledger.*;
import com.unicarona.payment.service.*;
import com.unicarona.seed.*;
import com.unicarona.shared.ApiException;
import java.net.*;
import java.net.http.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A second Java application context for shared-database replay/session/worker tests. This is
 * deliberately not a reference oracle: fixed contracts and domain assertions establish expected
 * behavior; this peer checks persistence across independent instances.
 */
public final class JavaApplicationPeer implements AutoCloseable {
  private final ServletWebServerApplicationContext app;
  private final ObjectMapper json = new ObjectMapper();
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  private final String server;

  @TestConfiguration(proxyBeanMethods = false)
  public static class DeliveryConfig {
    @Bean
    CapturingEmail peerEmail() {
      return new CapturingEmail();
    }
  }

  public static final class CapturingEmail implements EmailProvider {
    final List<Message> messages = new CopyOnWriteArrayList<>();
    volatile boolean fail;

    @Override
    public void send(Message message) {
      if (fail) throw new IllegalStateException("Synthetic peer delivery failure");
      messages.add(message);
    }
  }

  public JavaApplicationPeer(ApplicationContext source, String connection) {
    this(source, connection, source.getBean(StorageSettings.class).root());
  }

  public JavaApplicationPeer(ApplicationContext source, String connection, Path storage) {
    URI target = URI.create(connection);
    if (!"/unicarona_java_test".equals(target.getPath())
        || !Set.of("127.0.0.1", "localhost").contains(target.getHost()))
      throw new IllegalArgumentException(
          "Java peers require the disposable loopback test database");
    var args = new ArrayList<>(List.of(AuthTestSettings.arguments(connection)));
    args.add("--STORAGE_PATH=" + storage);
    args.add("--FRONTEND_PATH=" + source.getBean(StorageSettings.class).publicRoot());
    args.add("--SEED_PASSWORD=" + AuthTestSettings.SEED_PASSWORD);
    for (String key :
        List.of(
            "DEV_MAIL_PATH",
            "PRIVACY_ERASURE_JOURNAL_PATH",
            "PRIVACY_OPERATIONS_ROOT",
            "STORAGE_USER_QUOTA_BYTES",
            "STORAGE_USER_MAX_FILES")) {
      String value = source.getEnvironment().getProperty(key);
      if (value != null) args.add("--" + key + "=" + value);
    }
    app =
        (ServletWebServerApplicationContext)
            new SpringApplicationBuilder(UniCaronaApplication.class, DeliveryConfig.class)
                .run(args.toArray(String[]::new));
    server = "http://127.0.0.1:" + app.getWebServer().getPort();
  }

  public JsonNode request(String method, String path, Object body, Map<String, String> headers)
      throws Exception {
    byte[] bytes =
        body == null || Set.of("GET", "HEAD", "OPTIONS").contains(method)
            ? null
            : json.writeValueAsBytes(body);
    var input = new LinkedHashMap<>(headers);
    if (bytes != null) input.putIfAbsent("content-type", "application/json");
    return binaryRequest(method, path, bytes, input);
  }

  public JsonNode binaryRequest(
      String method, String path, byte[] body, Map<String, String> headers) throws Exception {
    var request = HttpRequest.newBuilder(URI.create(server + path)).timeout(Duration.ofSeconds(20));
    headers.forEach(request::header);
    request.method(
        method,
        body == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofByteArray(body));
    var response = http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    var result = json.createObjectNode().put("status", response.statusCode());
    boolean binary =
        !response.headers().firstValue("content-type").orElse("").contains("application/json");
    if (!binary && response.body().length > 0) result.set("body", json.readTree(response.body()));
    else result.putNull("body");
    if (binary) result.put("binary", Base64.getEncoder().encodeToString(response.body()));
    var outputHeaders = result.putObject("headers");
    response
        .headers()
        .map()
        .forEach((name, values) -> outputHeaders.put(name, String.join(", ", values)));
    var cookies = result.putArray("cookies");
    for (String header : response.headers().allValues("set-cookie")) {
      String[] pair = header.split(";", 2)[0].split("=", 2);
      cookies.addObject().put("name", pair[0]).put("value", pair.length == 2 ? pair[1] : "");
    }
    return result;
  }

  private JsonNode operation(Supplier<?> action) {
    ObjectNode result = json.createObjectNode();
    try {
      Object value = action.get();
      result.put("status", 200);
      result.set("body", json.valueToTree(value));
    } catch (ApiException error) {
      result.put("status", error.status());
      result.putObject("body").put("code", error.code());
    }
    return result;
  }

  public JsonNode journal(Object body) {
    JsonNode input = json.valueToTree(body);
    var entries = new ArrayList<LedgerCommand.Posting>();
    for (var entry : input.path("entries"))
      entries.add(
          new LedgerCommand.Posting(
              uuid(entry, "owner"),
              AccountKind.valueOf(entry.path("kind").asText().toUpperCase(Locale.ROOT)),
              entry.path("cents").asLong()));
    var fields = new ArrayList<String>();
    input.fieldNames().forEachRemaining(fields::add);
    UUID payment = uuid(input, "paymentId"), parcel = uuid(input, "parcelId");
    var layout =
        parcel != null
            ? (fields.indexOf("parcelId") < fields.indexOf("paymentId")
                ? LedgerCommand.Layout.PARCEL_BLOCK
                : LedgerCommand.Layout.PARCEL_SETTLE)
            : payment != null
                ? (fields.indexOf("reason") < fields.indexOf("paymentId")
                    ? LedgerCommand.Layout.RECEIPT
                    : LedgerCommand.Layout.RECEIPT_CREDIT)
                : LedgerCommand.Layout.TRANSFER;
    var command =
        new LedgerCommand(
            input.path("key").asText(),
            input.path("kind").asText(),
            input.path("reason").asText(),
            payment,
            parcel,
            entries,
            layout);
    return operation(
        () -> {
          UUID id =
              new TransactionTemplate(app.getBean(PlatformTransactionManager.class))
                  .execute(status -> app.getBean(LedgerService.class).post(command));
          return Map.of("id", id);
        });
  }

  private static UUID uuid(JsonNode node, String name) {
    return node.hasNonNull(name) ? UUID.fromString(node.path(name).asText()) : null;
  }

  public JsonNode submitTransfer(UUID id) {
    return operation(
        () -> {
          app.getBean(TransferService.class).submit(id);
          return Map.of();
        });
  }

  public JsonNode maintenance(int batch) {
    return operation(() -> app.getBean(MaintenanceService.class).run(batch));
  }

  public JsonNode processOne(boolean fail) {
    var email = app.getBean(CapturingEmail.class);
    email.messages.clear();
    email.fail = fail;
    return operation(
        () ->
            Map.of(
                "processed",
                app.getBean(OutboxProcessor.class).processOne(),
                "messages",
                List.copyOf(email.messages)));
  }

  public JsonNode seed(boolean scenarios) {
    return operation(
        () -> {
          app.getBean(SeedService.class).run();
          if (scenarios) {
            LegalTestFixtures.acceptedSeedBusinessUsers(app);
            app.getBean(SeedScenarios.class).run();
          }
          return Map.of();
        });
  }

  @Override
  public void close() {
    app.close();
  }
}
