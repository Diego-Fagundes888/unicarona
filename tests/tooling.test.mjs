import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import {
  assertApiOperations,
  expectedOperations,
  operations,
} from "../scripts/api-operations.mjs";

const json = (path) =>
  JSON.parse(readFileSync(new URL(path, import.meta.url), "utf8"));

test("development Swagger retains historical operations and the current privacy APIs", () => {
  const current = json("../docs/api/openapi-java.json");
  assertApiOperations(current, "development");
  const known = new Set(operations(current));
  for (const operation of operations(
    json("../docs/java-migration/api-baseline.json"),
  ))
    assert.ok(known.has(operation), operation);
  assert.ok(known.has("GET /api/v1/privacy/account"));
});

test("API audit rejects a missing route, an unexpected route and the wrong provider profile", () => {
  const current = json("../docs/api/openapi-java.json");
  delete current.paths["/api/v1/privacy/account"];
  assert.throws(() => assertApiOperations(current, "development"));
  const unexpected = json("../docs/api/openapi-java.json");
  unexpected.paths["/api/v1/unexpected"] = { get: {} };
  assert.throws(() => assertApiOperations(unexpected, "development"));
  assert.throws(() =>
    assertApiOperations(json("../docs/api/openapi-java.json"), "mercadopago"),
  );
  assert.throws(() => expectedOperations("unknown"));
});
