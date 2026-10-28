package com.unicarona.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import com.unicarona.shared.ApiException;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.*;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Preserves the original wire validation before authentication; DTOs still validate domain input.
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class RequestContractValidation {
  private static final int BODY_LIMIT = 256 * 1024;
  private final ObjectMapper mapper;
  private final List<Contract> contracts;

  private record Contract(
      String method, Pattern path, List<String> names, Schema body, Schema params, Schema query) {}

  public RequestContractValidation(ObjectMapper mapper) throws IOException {
    this.mapper = mapper;
    var registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_7);
    var compiled = new ArrayList<Contract>();
    try (var input = new ClassPathResource("api/request-contracts.json").getInputStream()) {
      for (var operation : mapper.readTree(input).path("operations")) {
        String template = operation.path("path").asText();
        var names = new ArrayList<String>();
        var regex = new StringBuilder("^");
        for (String segment : template.split("/", -1)) {
          if (regex.length() > 1) regex.append('/');
          if (segment.startsWith("{") && segment.endsWith("}")) {
            names.add(segment.substring(1, segment.length() - 1));
            regex.append("([^/]+)");
          } else regex.append(Pattern.quote(segment));
        }
        regex.append('$');
        compiled.add(
            new Contract(
                operation.path("method").asText(),
                Pattern.compile(regex.toString()),
                List.copyOf(names),
                compile(registry, operation.get("body")),
                compile(registry, operation.get("params")),
                compile(registry, operation.get("query"))));
      }
    }
    // Literal routes such as /rides/mine take precedence over /rides/{id}.
    compiled.sort(Comparator.comparingInt(contract -> contract.names().size()));
    contracts = List.copyOf(compiled);
  }

  private static Schema compile(SchemaRegistry registry, JsonNode source) {
    if (source == null) return null;
    JsonNode normalized = source.deepCopy();
    normalize(normalized);
    Schema schema = registry.getSchema(normalized);
    schema.initializeValidators();
    return schema;
  }

  private static void normalize(JsonNode node) {
    if (node.isObject()) {
      ObjectNode object = (ObjectNode) node;
      if (object.has("$ref"))
        throw new IllegalStateException("Request contracts must contain local, inline schemas");
      // The exported patterns reproduce AJV's email/UUID rules. A library format
      // checker can add restrictions (for example, an IANA TLD list rejecting .test).
      if (Set.of("email", "uuid").contains(object.path("format").asText())) {
        if (!object.path("pattern").isTextual())
          throw new IllegalStateException("Compatibility formats require their original pattern");
        object.remove("format");
      }
      if (object.path("nullable").asBoolean() && object.path("type").isTextual()) {
        String type = object.path("type").asText();
        object.putArray("type").add(type).add("null");
        // Swagger represents Zod's enum-or-null union as enum + nullable.
        if (object.path("enum").isArray()
            && !java.util.stream.StreamSupport.stream(object.path("enum").spliterator(), false)
                .anyMatch(JsonNode::isNull)) ((ArrayNode) object.path("enum")).addNull();
      }
      object.remove("nullable");
      object.elements().forEachRemaining(RequestContractValidation::normalize);
    } else if (node.isArray())
      node.elements().forEachRemaining(RequestContractValidation::normalize);
  }

  public HttpServletRequest validate(HttpServletRequest request) throws IOException {
    for (var contract : contracts) {
      if (!contract.method().equals(request.getMethod())
          && !("HEAD".equals(request.getMethod()) && "GET".equals(contract.method()))) continue;
      var match = contract.path().matcher(request.getRequestURI());
      if (!match.matches()) continue;
      boolean mutation = !Set.of("GET", "HEAD", "OPTIONS").contains(request.getMethod());
      JsonNode body = null;
      if (mutation
          && (request.getContentType() == null
              || !request.getContentType().toLowerCase(Locale.ROOT).startsWith("multipart/"))) {
        if (request.getContentLengthLong() > BODY_LIMIT) throw invalid(413);
        byte[] bytes = request.getInputStream().readNBytes(BODY_LIMIT + 1);
        if (bytes.length > BODY_LIMIT) throw invalid(413);
        request = new CachedBodyRequest(request, bytes);
        String contentType =
            Optional.ofNullable(request.getContentType())
                .orElse("")
                .split(";", 2)[0]
                .trim()
                .toLowerCase(Locale.ROOT);
        if (contentType.equals("application/json")) {
          if (bytes.length == 0) throw invalid(400);
          try {
            body =
                mapper
                    .reader()
                    .with(
                        com.fasterxml.jackson.databind.DeserializationFeature
                            .FAIL_ON_TRAILING_TOKENS)
                    .readTree(bytes);
          } catch (com.fasterxml.jackson.core.JacksonException error) {
            throw invalid(400);
          }
          if (body == null) throw invalid(400);
          // Normalize only authentication email inputs before their existing strict schemas.
          if (request.getRequestURI().startsWith("/api/v1/auth/")
              && body.isObject()
              && body.path("email").isTextual()) {
            ((ObjectNode) body)
                .put(
                    "email",
                    com.unicarona.shared.InputText.trim(body.path("email").asText())
                        .toLowerCase(Locale.ROOT));
            request = new CachedBodyRequest(request, mapper.writeValueAsBytes(body));
          }
        } else if (contentType.equals("text/plain"))
          body = mapper.getNodeFactory().textNode(new String(bytes, StandardCharsets.UTF_8));
        else if (bytes.length > 0) throw invalid(415);
      }
      check(contract.body(), body == null ? mapper.getNodeFactory().missingNode() : body);
      if (contract.params() != null) {
        var params = mapper.createObjectNode();
        for (int i = 0; i < contract.names().size(); i++) {
          try {
            params.put(
                contract.names().get(i),
                URLDecoder.decode(match.group(i + 1).replace("+", "%2B"), StandardCharsets.UTF_8));
          } catch (IllegalArgumentException error) {
            throw invalid(400);
          }
        }
        check(contract.params(), params);
      }
      if (contract.query() != null) {
        var query = mapper.createObjectNode();
        request
            .getParameterMap()
            .forEach(
                (name, values) -> {
                  if (values.length == 1) query.put(name, values[0]);
                  else {
                    var array = query.putArray(name);
                    for (String value : values) array.add(value);
                  }
                });
        check(contract.query(), query);
      }
      return request;
    }
    throw new ApiException("NOT_FOUND", "Recurso não encontrado.", 404);
  }

  private static void check(Schema schema, JsonNode value) {
    if (schema != null
        && !schema
            .validate(
                value,
                context -> context.executionConfig(config -> config.formatAssertionsEnabled(true)))
            .isEmpty())
      throw new ApiException("VALIDATION_ERROR", "Confira os campos informados.", 422);
  }

  private static ApiException invalid(int status) {
    return new ApiException("INVALID_REQUEST", "Requisição inválida.", status);
  }

  private static final class CachedBodyRequest extends HttpServletRequestWrapper {
    private final byte[] body;

    private CachedBodyRequest(HttpServletRequest request, byte[] body) {
      super(request);
      this.body = body;
    }

    @Override
    public int getContentLength() {
      return body.length;
    }

    @Override
    public long getContentLengthLong() {
      return body.length;
    }

    @Override
    public BufferedReader getReader() {
      return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
    }

    @Override
    public ServletInputStream getInputStream() {
      var input = new ByteArrayInputStream(body);
      return new ServletInputStream() {
        @Override
        public int read() {
          return input.read();
        }

        @Override
        public int read(byte[] buffer, int offset, int length) {
          return input.read(buffer, offset, length);
        }

        @Override
        public boolean isFinished() {
          return input.available() == 0;
        }

        @Override
        public boolean isReady() {
          return true;
        }

        @Override
        public void setReadListener(ReadListener listener) {
          Objects.requireNonNull(listener);
          try {
            if (!isFinished()) listener.onDataAvailable();
            if (isFinished()) listener.onAllDataRead();
          } catch (IOException error) {
            listener.onError(error);
          }
        }
      };
    }
  }
}
