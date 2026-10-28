package com.unicarona.shared;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class IdempotencyExecutor {
  private final JdbcTemplate sql;
  private final ObjectMapper json;

  public IdempotencyExecutor(JdbcTemplate sql, ObjectMapper json) {
    this.sql = sql;
    this.json = json;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public <T> T execute(
      UUID actor, String key, String scope, Object input, Class<T> output, Supplier<T> work) {
    return executeStored(actor, key, scope, input, List.of(), output, work);
  }

  /**
   * New map-based financial scopes must survive Map.of's JVM-dependent order. Accept old key-order
   * hashes without accepting different fields or values. Legacy DTO/ordered-body scopes keep their
   * original fingerprints unchanged.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public <T> T executeUnorderedObject(
      UUID actor,
      String key,
      String scope,
      Map<String, ?> input,
      Class<T> output,
      Supplier<T> work) {
    if (input.size() < 2 || input.size() > 3)
      throw new IllegalArgumentException("Explicit two/three-field compatibility scope required");
    var canonical = new TreeMap<String, Object>();
    canonical.putAll(input);
    var alternatives = new ArrayList<Object>();
    orders(new ArrayList<>(canonical.keySet()), canonical, new LinkedHashMap<>(), alternatives);
    return executeStored(actor, key, scope, canonical, alternatives, output, work);
  }

  private static void orders(
      List<String> keys,
      Map<String, Object> values,
      LinkedHashMap<String, Object> current,
      List<Object> results) {
    if (current.size() == keys.size()) {
      results.add(new LinkedHashMap<>(current));
      return;
    }
    for (var key : keys)
      if (!current.containsKey(key)) {
        current.put(key, values.get(key));
        orders(keys, values, current, results);
        current.remove(key);
      }
  }

  private String fingerprint(Object input)
      throws JsonProcessingException, java.security.NoSuchAlgorithmException {
    return HexFormat.of()
        .formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(input)));
  }

  private <T> T executeStored(
      UUID actor,
      String key,
      String scope,
      Object input,
      List<Object> alternatives,
      Class<T> output,
      Supplier<T> work) {
    sql.execute("SET LOCAL lock_timeout='3s'");
    ApiException.require(
        key != null && key.matches("[a-zA-Z0-9_-]{16,128}"),
        "IDEMPOTENCY_REQUIRED",
        "Envie uma chave única para esta operação.",
        422);
    try {
      String hash = fingerprint(input);
      sql.update(
          "INSERT INTO idempotency_keys(actor_id,key,scope,body_hash,expires_at) VALUES(?,?,?,?,now()+interval '30 days') ON CONFLICT DO NOTHING",
          actor,
          key,
          scope,
          hash);
      var stored =
          sql.queryForMap(
              "SELECT body_hash,response::text FROM idempotency_keys WHERE actor_id=? AND key=? AND scope=? FOR UPDATE",
              actor,
              key,
              scope);
      boolean same = hash.equals(stored.get("body_hash"));
      if (!same)
        for (var candidate : alternatives)
          if (fingerprint(candidate).equals(stored.get("body_hash"))) {
            same = true;
            break;
          }
      ApiException.require(
          same, "IDEMPOTENCY_CONFLICT", "A chave já foi usada para outra operação.", 409);
      if (stored.get("response") != null)
        return json.readValue((String) stored.get("response"), output);
      T response = work.get();
      sql.update(
          "UPDATE idempotency_keys SET response=?::jsonb WHERE actor_id=? AND key=? AND scope=?",
          json.writeValueAsString(response),
          actor,
          key,
          scope);
      return response;
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Could not serialize idempotent operation.");
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable.");
    }
  }
}
