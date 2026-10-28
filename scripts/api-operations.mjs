import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

const contracts = JSON.parse(
  readFileSync(
    new URL(
      "../backend-java/src/main/resources/api/request-contracts.json",
      import.meta.url,
    ),
    "utf8",
  ),
).operations;
const methods = new Set([
  "get",
  "post",
  "patch",
  "put",
  "delete",
  "head",
  "options",
]);
const normalize = (path) => path.replace(/\{[^}]+\}/g, "{}");
const key = (method, path) => method.toUpperCase() + " " + normalize(path);

// These controllers are conditional on PAYMENT_PROVIDER. Shared routes remain
// required in both profiles, including the financial and privacy history APIs.
const mercadoPagoOnly = new Set([
  "GET /api/v1/payments/{}/gateway",
  "POST /api/v1/payments/{}/card",
  "POST /api/v1/payments/{}/sync",
  "POST /api/v1/webhooks/mercadopago",
  "GET /api/v1/transfers/{}/refunds",
  "GET /api/v1/wallet/refund-review",
  "GET /api/v1/admin/payment-reviews",
  "GET /api/v1/admin/payment-reviews/{}",
  "POST /api/v1/admin/payment-reviews/{}/recheck",
  "POST /api/v1/admin/payment-reviews/{}/resolve",
  "POST /api/v1/wallet/debt/checkout",
  "GET /api/v1/wallet/debt/payments/{}",
  "POST /api/v1/wallet/debt/payments/{}/sync",
]);
const developmentOnly = "POST /api/v1/development/payments/{}/result";

export function operations(document) {
  return Object.entries(document.paths)
    .flatMap(([path, item]) =>
      Object.keys(item)
        .filter((method) => methods.has(method))
        .map((method) => key(method, path)),
    )
    .sort();
}

export function expectedOperations(provider) {
  assert.ok(
    ["development", "mercadopago"].includes(provider),
    "Unknown payment profile",
  );
  return contracts
    .map((operation) => key(operation.method, operation.path))
    .filter((operation) =>
      provider === "mercadopago"
        ? operation !== developmentOnly
        : !mercadoPagoOnly.has(operation),
    )
    .sort();
}

export function assertApiOperations(document, provider) {
  const actual = operations(document);
  assert.deepEqual(
    actual,
    expectedOperations(provider),
    `Swagger must match the complete ${provider} API contract`,
  );
  return actual.length;
}
