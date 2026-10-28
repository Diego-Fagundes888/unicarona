// Functional tests in isolated JS contexts. These do not replace browser/visual QA.
import test from "node:test";
import assert from "node:assert/strict";
import vm from "node:vm";
import { readFileSync } from "node:fs";
import { webcrypto } from "node:crypto";

const source = (name) =>
  readFileSync(new URL("../dist/" + name, import.meta.url), "utf8");
const plain = (value) => JSON.parse(JSON.stringify(value));
function context(extra = {}) {
  const window = {};
  return vm.createContext({
    window,
    document: {
      cookie: "uc_csrf=proof%2Bcsrf",
      body: { hasAttribute: () => false },
    },
    location: { origin: "http://127.0.0.1:3005" },
    URL,
    URLSearchParams,
    Blob,
    FormData,
    TextEncoder,
    Uint8Array,
    crypto: webcrypto,
    setTimeout,
    clearTimeout,
    ...extra,
  });
}
const run = (ctx, name) =>
  vm.runInContext(source(name), ctx, { filename: name });
const response = (status, data) => ({
  ok: status >= 200 && status < 300,
  status,
  json: async () => data,
  blob: async () => data,
});
const errorResponse = (status, code) =>
  response(status, { code, message: "Falha de teste" });
function deferred() {
  let resolve;
  const promise = new Promise((r) => {
    resolve = r;
  });
  return { promise, resolve };
}
test("academic privacy is explicit and does not render hidden or stale academic values", () => {
  const ctx = context();
  run(ctx, "ui.js");
  run(ctx, "backend-ui.js");
  const academic = ctx.window.UniCaronaConnectedUI.academic;
  assert.equal(
    academic({
      course: "Private stale course",
      periodStatus: "protected",
      periodNumber: 8,
      classLabel: "C",
    }),
    "Dados acadêmicos protegidos",
  );
  assert.equal(
    academic({
      course: "Engenharia",
      periodStatus: "numbered",
      periodNumber: 3,
      classLabel: "A",
    }),
    "Engenharia · 3º período · Turma A",
  );
  assert.equal(
    academic({
      course: "",
      periodStatus: "undefined",
      periodNumber: null,
      classLabel: "",
    }),
    "Período não definido · Turma não informada",
  );
});
const slugs = [
  "terms",
  "privacy",
  "participation",
  "payments",
  "safety",
  "driver-terms",
];
function catalog(version = "1.0") {
  return {
    documents: slugs.map((slug) => ({
      reference: { slug, version, contentSha256: "a".repeat(64) },
      title: "Documento " + slug,
      requiredFor:
        slug === "driver-terms" ? ["driver"] : ["registration", "service"],
    })),
  };
}
function legalContext(request) {
  const ctx = context();
  run(ctx, "ui.js");
  ctx.window.UniCaronaAPI = { request };
  run(ctx, "legal.js");
  return ctx;
}
function privacyContext(request, options = {}) {
  const actions = [],
    messages = [],
    downloads = [],
    revocations = [],
    timers = new Map();
  let timer = 0,
    epoch = 0;
  class TestURL extends URL {
    static createObjectURL(blob) {
      downloads.push({ blob });
      return "blob:private-" + downloads.length;
    }
    static revokeObjectURL(url) {
      revocations.push(url);
    }
  }
  const ctx = legalContext(request);
  ctx.URL = TestURL;
  ctx.setTimeout = (callback) => {
    timers.set(++timer, callback);
    return timer;
  };
  ctx.clearTimeout = (id) => timers.delete(id);
  ctx.document.createElement = (tag) => {
    assert.equal(tag, "a");
    return {
      click() {
        downloads.at(-1).clicked = true;
        downloads.at(-1).filename = this.download;
      },
      remove() {},
    };
  };
  ctx.document.body.append = () => {};
  run(ctx, "privacy.js");
  const flow = ctx.window.UniCaronaPrivacy.create({
    request,
    navigate: (...args) => actions.push(args),
    tell: (text) => messages.push(text),
    refreshProfile: options.refreshProfile || (async () => {}),
    navigationVersion: () => epoch,
  });
  return {
    ctx,
    flow,
    actions,
    messages,
    downloads,
    revocations,
    timers,
    leave: () => epoch++,
  };
}

test("API retries preserve an idempotency key; the cache retains a digest instead of credentials", async () => {
  const keys = [],
    fingerprints = [];
  class ObservedMap extends Map {
    set(key, value) {
      fingerprints.push(key);
      return super.set(key, value);
    }
  }
  let attempt = 0;
  const ctx = context({
    Map: ObservedMap,
    fetch: async (url, init) => {
      assert.equal(url, "/api/v1/privacy/closure");
      assert.equal(init.credentials, "same-origin");
      assert.equal(init.headers["x-csrf-token"], "proof+csrf");
      keys.push(init.headers["idempotency-key"]);
      if (++attempt === 1) throw Error("offline");
      return attempt === 2
        ? errorResponse(503, "UNAVAILABLE")
        : response(200, { accepted: true });
    },
  });
  run(ctx, "api.js");
  const api = ctx.window.UniCaronaAPI;
  const options = {
    method: "POST",
    body: { password: "private-password-synthetic", acknowledged: true },
  };
  await assert.rejects(
    api.request("/privacy/closure", options),
    (e) => e.code === "NETWORK_ERROR",
  );
  await assert.rejects(
    api.request("/privacy/closure", options),
    (e) => e.status === 503,
  );
  await api.request("/privacy/closure", options);
  await api.request("/privacy/closure", options);
  assert.equal(keys[0], keys[1]);
  assert.equal(keys[1], keys[2]);
  assert.notEqual(keys[2], keys[3]);
  assert.ok(
    fingerprints.every((key) =>
      /^POST:\/privacy\/closure:[a-f0-9]{64}$/.test(key),
    ),
  );
  assert.ok(fingerprints.every((key) => !key.includes("private-password")));
});

test("API wrong-password 401 stays an error and never refreshes a session or retries the password", async () => {
  const calls = [],
    ctx = context({
      fetch: async (path, init) => {
        calls.push([path, init]);
        return errorResponse(401, "INVALID_CREDENTIALS");
      },
    });
  run(ctx, "api.js");
  await assert.rejects(
    ctx.window.UniCaronaAPI.request("/privacy/export", {
      method: "POST",
      body: { password: "wrong" },
      format: "blob",
    }),
    (e) => e.code === "INVALID_CREDENTIALS",
  );
  assert.equal(calls.length, 1);
  assert.equal(calls[0][0], "/api/v1/privacy/export");
});

test("API concurrent expired sessions share one refresh and retry with their original operation keys", async () => {
  const gate = deferred(),
    started = deferred(),
    calls = [];
  let refreshed = false;
  const ctx = context({
    fetch: async (path, init) => {
      calls.push([path, init.headers["idempotency-key"]]);
      if (path === "/api/v1/auth/refresh") {
        started.resolve();
        await gate.promise;
        refreshed = true;
        return response(200, {});
      }
      return refreshed
        ? response(200, { accepted: true })
        : errorResponse(401, "SESSION_EXPIRED");
    },
  });
  run(ctx, "api.js");
  const api = ctx.window.UniCaronaAPI;
  const a = api.request("/privacy/preferences", {
    method: "PATCH",
    body: { marketing: false },
  });
  const b = api.request("/privacy/account", { method: "GET" });
  await started.promise;
  // Wait until both independently started requests reached the refresh boundary.
  while (!calls.some(([path]) => path === "/api/v1/privacy/preferences"))
    await new Promise((resolve) => setImmediate(resolve));
  gate.resolve();
  await Promise.all([a, b]);
  assert.equal(
    calls.filter(([path]) => path === "/api/v1/auth/refresh").length,
    1,
  );
  const keys = calls
    .filter(([path]) => path === "/api/v1/privacy/preferences")
    .map(([, key]) => key);
  assert.equal(keys.length, 2);
  assert.equal(keys[0], keys[1]);
});

test("API reads a successful ZIP as Blob, but parses JSON errors even when Blob was requested", async () => {
  const zip = new Blob(["synthetic"], { type: "application/zip" });
  let ok = true;
  const ctx = context({
    fetch: async () =>
      ok ? response(200, zip) : errorResponse(422, "VALIDATION_ERROR"),
  });
  run(ctx, "api.js");
  assert.equal(
    await ctx.window.UniCaronaAPI.request("/privacy/export", {
      method: "POST",
      format: "blob",
    }),
    zip,
  );
  ok = false;
  await assert.rejects(
    ctx.window.UniCaronaAPI.request("/privacy/export", {
      method: "POST",
      format: "blob",
    }),
    (e) => e.code === "VALIDATION_ERROR",
  );
});

test("legal renderer escapes raw HTML and text and rejects executable links", () => {
  const {
    window: { UniCaronaLegal: legal },
  } = legalContext(async () => catalog());
  const html = legal.markdown(
    "# Título\n\n<script>alert(1)</script>\n\n[x](javascript:alert) [y](data:text/html,test)\n\n**Texto <img src=x onerror=alert>**\n\n| Campo | Valor |\n| --- | --- |\n| <svg> | `código` |",
  );
  assert.ok(
    !html.includes("<script>") &&
      !html.includes("<img") &&
      !html.includes("<svg>"),
  );
  assert.ok(
    !html.includes('href="javascript:') && !html.includes('href="data:'),
  );
  assert.match(html, /&lt;script&gt;/);
  assert.match(html, /<strong>Texto &lt;img/);
  assert.match(html, /<th scope="col">Campo/);
  assert.throws(() => legal.markdown("x".repeat(250001)));
  const safe = legal.markdown(
    "[Lei](https://www.planalto.gov.br/lei) [Contato](mailto:privacy@example.org) [Versão](/legal.html?document=terms&version=1.0)",
  );
  assert.match(safe, /href="https:\/\/www.planalto.gov.br\/lei"/);
  assert.match(safe, /href="mailto:privacy@example.org"/);
  assert.match(safe, /version=1.0/);
});

test("legal manifest validates its complete document set and freezes references by value across updates", async () => {
  let version = "1.0",
    calls = 0;
  const ctx = legalContext(async () => {
      calls++;
      return catalog(version);
    }),
    legal = ctx.window.UniCaronaLegal;
  const first = await legal.manifest();
  const frozen = legal.references(first);
  assert.equal(frozen.length, 5);
  assert.equal(legal.references(first, "service", true).length, 6);
  await legal.manifest();
  assert.equal(calls, 1);
  version = "2.0";
  await legal.manifest(true);
  assert.equal(frozen[0].version, "1.0");
  assert.match(legal.href(frozen[0]), /version=1.0/);
  first.documents[0].reference.version = "changed";
  assert.equal(frozen[0].version, "1.0");
  for (const version of ["..", ".", "../x", "x/y", ""])
    assert.equal(
      Boolean(legal.validReference({ ...frozen[0], version })),
      false,
    );
  assert.throws(() => legal.href({ ...frozen[0], slug: "__proto__" }));
  assert.match(
    legal.documentLinks({
      documents: [{ ...first.documents[0], title: "<img onerror=x>" }],
    }),
    /&lt;img onerror=x&gt;/,
  );
});

function publicReader(
  request,
  {
    search = "?document=terms&version=missing",
    contact = "privacy+rights@example.test",
    regions = [],
    hints = {},
  } = {},
) {
  const done = deferred(),
    resize = new Map();
  const article = {
    innerHTML: "",
    contains: () => false,
    setAttribute() {},
    removeAttribute: (name) => {
      if (name === "aria-busy") done.resolve();
    },
    querySelectorAll: () => regions,
    addEventListener() {},
  };
  const navigation = { open: false };
  const ctx = context({
    location: { origin: "http://127.0.0.1:3005", search },
    matchMedia: () => ({ matches: false, addEventListener() {} }),
  });
  run(ctx, "ui.js");
  ctx.window.UniCaronaAPI = { request };
  ctx.window.addEventListener = (event, callback) =>
    resize.set(event, callback);
  ctx.document.body.hasAttribute = () => true;
  ctx.document.getElementById = (id) => hints[id];
  ctx.document.querySelector = (selector) =>
    selector.includes("unicarona-privacy-contact")
      ? { getAttribute: () => contact }
      : selector === "#legal-document"
        ? article
        : selector === ".p-legal-nav"
          ? navigation
          : { innerHTML: "" };
  run(ctx, "legal.js");
  return { ctx, article, resize, ready: done.promise };
}

test("public reader keeps the requested version failure explicit and offers its current document with configured contact", async () => {
  const calls = [],
    reader = publicReader(async (path, options) => {
      calls.push([path, options]);
      throw { status: 404, message: "Documento não encontrado." };
    });
  await reader.ready;
  assert.equal(reader.ctx.document.title, "Documento indisponível | UniCarona");
  assert.deepEqual(calls, [["/legal/documents/terms/missing", undefined]]);
  assert.match(reader.article.innerHTML, /Documento não encontrado/);
  assert.match(reader.article.innerHTML, /href="legal.html\?document=terms"/);
  assert.match(reader.article.innerHTML, /privacy\+rights@example.test/);
  assert.doesNotMatch(
    reader.article.innerHTML,
    /diego\.fagundes|Versão atual carregada/,
  );
  const invalid = publicReader(
    async () => {
      throw Error("Must not request invalid slug");
    },
    { search: "?document=unknown" },
  );
  await invalid.ready;
  assert.doesNotMatch(
    invalid.article.innerHTML,
    /Consultar a versão atual deste documento/,
  );
});

test("public reader exposes the table scroll instruction only while the table overflows and retains its semantic structure", async () => {
  const hint = { hidden: true },
    region = {
      scrollWidth: 470,
      clientWidth: 343,
      getAttribute: () => "document-table-hint-1",
    };
  const reader = publicReader(
    async () => ({
      reference: { slug: "terms", version: "1", contentSha256: "a".repeat(64) },
      title: "Termos",
      body: "| Categoria | Regra |\n| --- | --- |\n| Conta | Exclusão |",
    }),
    {
      search: "?document=terms",
      regions: [region],
      hints: { "document-table-hint-1": hint },
    },
  );
  await reader.ready;
  assert.equal(hint.hidden, false);
  assert.match(reader.article.innerHTML, /<table>/);
  assert.match(reader.article.innerHTML, /<th scope="col">Categoria/);
  assert.match(
    reader.article.innerHTML,
    /aria-describedby="document-table-hint-1"/,
  );
  assert.match(reader.article.innerHTML, /tabindex="0"/);
  assert.ok(
    reader.article.innerHTML.indexOf('class="p-table-hint"') <
      reader.article.innerHTML.indexOf('class="p-table-scroll"'),
    "The scroll instruction precedes the long table, where the reader needs it",
  );
  region.clientWidth = 600;
  reader.resize.get("resize")();
  assert.equal(hint.hidden, true);
  region.clientWidth = 300;
  reader.resize.get("resize")();
  assert.equal(hint.hidden, false);
});

test("legal invalid catalog fails without inventing acceptance references and a later request can recover", async () => {
  let invalid = true;
  const ctx = legalContext(async () =>
    invalid ? { documents: catalog().documents.slice(0, 5) } : catalog(),
  );
  await assert.rejects(ctx.window.UniCaronaLegal.manifest(), /conferir/);
  invalid = false;
  assert.equal(
    (await ctx.window.UniCaronaLegal.manifest()).documents.length,
    6,
  );
});

test("privacy read views use own endpoints, escape protocol content and never mutate on render", async () => {
  const calls = [],
    id = "11111111-1111-4111-8111-111111111111";
  const { flow } = privacyContext(async (path, options) => {
    calls.push([path, options]);
    return {
      request: { id, kind: "access", status: "received" },
      details: "<img src=x onerror=x>",
      responses: [{ message: "<script>attack</script>" }],
    };
  });
  const html = await flow.render("privacy_request", id);
  assert.match(html, /&lt;img/);
  assert.match(html, /&lt;script&gt;/);
  assert.ok(!html.includes("<script>"));
  assert.deepEqual(calls, [["/privacy/requests/" + id, undefined]]);
  await assert.rejects(
    flow.render("privacy_request", "../someone"),
    /inválido/,
  );
  assert.equal(calls.length, 1);
});

test("closure follows server deadlines, obligations and cancellation rights, including platform decisions", async () => {
  let state = {
    status: "none",
    graceDays: 11,
    policyVersion: "policy-2",
    obligations: [{ label: "<viagem>", count: 1, delaysErasure: true }],
    balances: { passenger_debt: 300 },
  };
  const { flow } = privacyContext(async () => state);
  const first = await flow.render("privacy_close");
  assert.match(first, /11 dias/);
  assert.match(first, /&lt;viagem&gt;/);
  assert.match(first, /Pode adiar/);
  assert.match(first, /policy-2/);
  state = {
    ...state,
    id: "private-protocol",
    status: "pending_obligations",
    initiatedBy: "platform",
    canCancel: false,
  };
  const platform = await flow.render("privacy_close");
  assert.match(platform, /iniciado pela plataforma/);
  assert.match(platform, /Pedir revisão/);
  assert.ok(!platform.includes('data-c-form="privacyCancelClose"'));
  assert.ok(!platform.includes('data-c-form="privacyClose"'));
  state = { ...state, initiatedBy: "user", canCancel: true };
  assert.match(
    await flow.render("privacy_close"),
    /data-c-form="privacyCancelClose"/,
  );
});

test("retention state and account state do not reuse the same active label", async () => {
  const calls = [],
    { flow } = privacyContext(async (path) => {
      calls.push(path);
      if (path === "/privacy/account")
        return { status: "active", decisions: [] };
      if (path.startsWith("/privacy/retentions"))
        return {
          items: [
            {
              category: "profile",
              status: "active",
              legalBasis: "regular_exercise_of_rights",
            },
          ],
          total: 1,
          limit: 20,
          offset: 0,
        };
      throw Error("Unexpected read");
    });
  const account = await flow.render("privacy_account");
  const retained = await flow.render("privacy_retentions");
  assert.match(account, /Conta ativa/);
  assert.doesNotMatch(account, /Preservação ativa/);
  assert.match(retained, /Preservação ativa/);
  assert.doesNotMatch(retained, /Conta ativa/);
  assert.equal(calls.length, 2);
});

test("cancelled closure keeps its protocol and requires a fresh unchecked request", async () => {
  const calls = [],
    { flow } = privacyContext(async (path, options) => {
      calls.push([path, options]);
      return {
        id: "<cancelled-proof>",
        status: "cancelled",
        graceDays: 7,
        policyVersion: "policy-2",
        obligations: [],
        balances: {},
      };
    });
  const html = await flow.render("privacy_close");
  assert.match(html, /Pedido de exclusão cancelado/);
  assert.match(html, /Protocolo cancelado &lt;cancelled-proof&gt;/);
  assert.match(html, /Solicitar nova exclusão da conta/);
  assert.match(html, /data-c-form="privacyClose"/);
  assert.doesNotMatch(html, /data-c-form="privacyCancelClose"| checked/);
  assert.deepEqual(calls, [["/privacy/closure", undefined]]);
});

test("a specific security-event preservation has a readable name in the owners retention list", async () => {
  const { flow } = privacyContext(async (path) => {
    assert.ok(path.startsWith("/privacy/retentions"));
    return {
      items: [
        {
          category: "security_audit",
          status: "active",
          legalBasis: "regular_exercise_of_rights",
          expiresAt: "2026-12-01T12:00:00Z",
        },
      ],
      total: 1,
      limit: 20,
      offset: 0,
    };
  });
  const html = await flow.render("privacy_retentions");
  assert.match(html, /Evento de segurança/);
  assert.doesNotMatch(html, /security_audit/);
});

test("marketing stays separate and legal checkboxes start unchecked; history survives catalog outage", async () => {
  const ctx = privacyContext(async (path) => {
    if (path === "/privacy/account") return { status: "active" };
    if (path === "/privacy/preferences")
      return {
        marketing: false,
        statementVersion: "m1",
        statement: "Texto da preferência",
      };
    if (path === "/legal/documents") return catalog();
    if (path === "/privacy/legal")
      return { required: [{}], driverRequired: [{}] };
    if (path.startsWith("/privacy/legal/history"))
      return {
        items: [
          { document: catalog().documents[0].reference, kind: "contract" },
        ],
      };
    throw Error("unexpected " + path);
  });
  const home = await ctx.flow.render("privacy");
  assert.match(home, /name="marketing"/);
  assert.ok(!home.includes(" checked"));
  const docs = await ctx.flow.render("privacy_documents");
  assert.ok(!docs.includes(" checked"));
  assert.match(docs, /name="driverTerms"/);
  assert.match(docs, /version=1.0/);
  const offline = privacyContext(async (path) => {
    if (path === "/legal/documents") throw Error("offline");
    if (path === "/privacy/legal") return { required: [], driverRequired: [] };
    return {
      items: [{ document: catalog().documents[0].reference, kind: "contract" }],
    };
  });
  const history = await offline.flow.render("privacy_documents");
  assert.match(history, /Não foi possível consultar/);
  assert.match(history, /version=1.0/);
  assert.ok(!history.includes('data-c-form="privacyAccept"'));
});

test("sensitive commands require acknowledgement and send only whitelisted own-account fields", async () => {
  const calls = [],
    ctx = privacyContext(async (path, options) => {
      calls.push([path, plain(options)]);
      return { id: "protocol" };
    });
  await assert.rejects(
    ctx.flow.perform("privacyClose", { acknowledged: false }),
    /confirme/,
  );
  await assert.rejects(
    ctx.flow.perform("privacyDeactivate", { acknowledged: false }),
    /Confirme/,
  );
  await assert.rejects(
    ctx.flow.perform("privacyAccept", { terms: true, privacyNotice: false }),
    /obrigatórios/,
  );
  assert.equal(calls.length, 0);
  await ctx.flow.perform("privacyClose", {
    password: "synthetic",
    acknowledged: true,
    policyVersion: "p2",
    userId: "someone-else",
    status: "completed",
  });
  assert.deepEqual(calls, [
    [
      "/privacy/closure",
      {
        method: "POST",
        body: {
          password: "synthetic",
          acknowledged: true,
          policyVersion: "p2",
        },
      },
    ],
  ]);
  const docs = catalog().documents.map((x) => x.reference);
  await ctx.flow.perform("privacyAccept", {
    terms: true,
    privacyNotice: true,
    driverTerms: true,
    documents: docs.slice(0, 5),
    driverDocument: docs[5],
  });
  assert.equal(calls[1][1].body.documents.length, 6);
  assert.ok(!Object.hasOwn(calls[1][1].body, "marketing"));
});

test("a successful sensitive command is not retried or reported as failed when secondary profile refresh fails", async () => {
  let posts = 0;
  const { flow, messages, actions } = privacyContext(
    async () => {
      posts++;
      return { id: "receipt" };
    },
    {
      refreshProfile: async () => {
        throw Error("profile offline");
      },
    },
  );
  await flow.perform("privacyClose", {
    acknowledged: true,
    password: "synthetic",
    policyVersion: "p1",
  });
  assert.equal(posts, 1);
  assert.ok(messages.some((x) => x.includes("Protocolo receipt")));
  assert.equal(actions[0][0], "privacy_close");
});

test("leaving the view while a command is pending prevents late navigation and notices", async () => {
  const wait = deferred(),
    ctx = privacyContext(() => wait.promise);
  const action = ctx.flow.perform("privacyRequest", {
    kind: "access",
    details: "Meu pedido",
  });
  ctx.leave();
  wait.resolve({ id: "new" });
  await action;
  assert.equal(ctx.actions.length, 0);
  assert.equal(ctx.messages.length, 0);
});

function zip() {
  return new Blob([Uint8Array.from([0x50, 0x4b, 5, 6, ...Array(18).fill(0)])], {
    type: "application/zip",
  });
}
test("export rejects invalid MIME, tiny payloads and spoofed ZIP headers before a download", async () => {
  for (const blob of [
    new Blob(["<html>error</html>"]),
    new Blob(["PK"], { type: "application/zip" }),
    new Blob(["x".repeat(25)], { type: "application/zip" }),
  ]) {
    const ctx = privacyContext(async () => blob);
    await assert.rejects(
      ctx.flow.perform("privacyExport", { password: "synthetic" }),
      /exportação válida/,
    );
    assert.equal(ctx.downloads.length, 0);
  }
});

test("valid export is a temporary local Blob URL, revoked on timeout or flow destruction", async () => {
  const ctx = privacyContext(async () => zip());
  await ctx.flow.perform("privacyExport", { password: "synthetic" });
  assert.equal(ctx.downloads.length, 1);
  assert.equal(ctx.downloads[0].clicked, true);
  assert.equal(ctx.downloads[0].filename, "unicarona-meus-dados.zip");
  [...ctx.timers.values()][0]();
  assert.deepEqual(ctx.revocations, ["blob:private-1"]);
  await ctx.flow.perform("privacyExport", { password: "synthetic" });
  ctx.flow.destroy();
  assert.deepEqual(ctx.revocations, ["blob:private-1", "blob:private-2"]);
});

test("late export after navigation does not download private data into the new view", async () => {
  const wait = deferred(),
    ctx = privacyContext(() => wait.promise);
  const action = ctx.flow.perform("privacyExport", { password: "synthetic" });
  ctx.leave();
  wait.resolve(zip());
  await action;
  assert.equal(ctx.downloads.length, 0);
});

// Exercise the actual async signup document loader without simulating a browser.
function signupLoader() {
  const nodes = {
    "#signup-legal": { innerHTML: "", isConnected: true },
    "#signup-step1 [type=submit]": { disabled: false },
    "#signup-terms": { checked: true },
    "#signup-privacy": { checked: true },
  };
  let answer = async () => catalog();
  const ctx = legalContext(() => answer());
  ctx.legal = ctx.window.UniCaronaLegal;
  ctx.UI = ctx.window.UniCaronaUI;
  ctx.host = { querySelector: (selector) => nodes[selector] };
  ctx.revision = 1;
  ctx.signupLegalSnapshot = null;
  ctx.signupAccessDraft = {
    rulesAccepted: true,
    privacyNotice: true,
    documents: plain(
      catalog()
        .documents.slice(0, 5)
        .map((d) => d.reference),
    ),
  };
  run(ctx, "features/auth/documents.js");
  Object.assign(
    ctx,
    ctx.window.UniCaronaFeatures.authDocuments.create({
      state: ctx,
      legal: ctx.legal,
      UI: ctx.UI,
      host: ctx.host,
    }),
  );
  return {
    ctx,
    nodes,
    answer: (fn) => {
      answer = fn;
    },
    load: () => ctx.loadSignupDocuments(),
  };
}

test("signup outage followed by new document versions clears the old checked acceptances", async () => {
  const ctx = signupLoader();
  ctx.answer(async () => {
    throw Error("offline");
  });
  await ctx.load();
  assert.equal(ctx.nodes["#signup-step1 [type=submit]"].disabled, true);
  assert.equal(ctx.ctx.signupLegalSnapshot, null);
  ctx.answer(async () => catalog("2.0"));
  await ctx.load();
  assert.equal(ctx.nodes["#signup-terms"].checked, false);
  assert.equal(ctx.nodes["#signup-privacy"].checked, false);
  assert.equal(ctx.ctx.signupAccessDraft.documents.length, 0);
  assert.match(
    ctx.nodes["#signup-legal"].innerHTML,
    /documentos foram atualizados/,
  );
  assert.equal(ctx.nodes["#signup-step1 [type=submit]"].disabled, false);
});

test("signup treats reordered identical references as the same version but ignores a response from an old view", async () => {
  const ctx = signupLoader();
  ctx.answer(async () => ({ documents: catalog().documents.reverse() }));
  await ctx.load();
  assert.equal(ctx.nodes["#signup-terms"].checked, true);
  assert.equal(ctx.nodes["#signup-privacy"].checked, true);
  const wait = deferred();
  ctx.answer(() => wait.promise);
  const loading = ctx.load();
  ctx.ctx.revision++;
  wait.resolve(catalog("3.0"));
  await loading;
  assert.equal(ctx.ctx.signupLegalSnapshot[0].version, "1.0");
  assert.equal(ctx.nodes["#signup-terms"].checked, true);
});

test("opening an email change confirmation only reads the session and requires a password to proceed", async () => {
  const state = { innerHTML: "", isConnected: true },
    calls = [],
    ctx = context();
  run(ctx, "ui.js");
  ctx.UI = ctx.window.UniCaronaUI;
  ctx.host = { querySelector: () => state };
  ctx.revision = 1;
  ctx.identityChangeToken = "a".repeat(43);
  ctx.passwordField = () =>
    '<input type="password" autocomplete="current-password">';
  ctx.feedback = '<p role="alert"></p>';
  ctx.api = {
    request: async (path) => {
      calls.push(path);
      return { email: "<img src=x>@example.test" };
    },
  };
  run(ctx, "features/auth/documents.js");
  Object.assign(
    ctx,
    ctx.window.UniCaronaFeatures.authDocuments.create({
      state: ctx,
      UI: ctx.UI,
      host: ctx.host,
      api: ctx.api,
      passwordField: ctx.passwordField,
      feedback: ctx.feedback,
    }),
  );
  await ctx.loadIdentityConfirmation();
  assert.deepEqual(calls, ["/auth/me"]);
  assert.match(state.innerHTML, /type="password"/);
  assert.match(state.innerHTML, /&lt;img src=x&gt;/);
  ctx.identityChangeToken = "";
  await ctx.loadIdentityConfirmation();
  assert.equal(calls.length, 1);
  assert.match(state.innerHTML, /Abra novamente o link/);
  ctx.identityChangeToken = "b".repeat(43);
  ctx.api.request = async () => {
    throw { status: 401 };
  };
  await ctx.loadIdentityConfirmation();
  assert.match(state.innerHTML, /Entre na conta que solicitou/);
  assert.ok(!state.innerHTML.includes("identity-email-confirm"));
});

function checkoutReader(override = {}) {
  const callbacks = [],
    ctx = context({
      setInterval: (callback) => {
        callbacks.push(callback);
        return callbacks.length;
      },
      clearInterval: () => {},
    });
  ctx.checkoutPollCallbacks = callbacks;
  run(ctx, "ui.js");
  const ui = ctx.window.UniCaronaUI;
  Object.assign(ctx, {
    epoch: 1,
    ref: "synthetic-payment",
    disposed: false,
    pollTimer: null,
    current: null,
    tripReviewChangedId: null,
    location: { href: "http://localhost:4190/dashboard.html" },
    esc: ui.escape,
    cash: ui.money,
    dateLabel: ui.date,
    form: ui.form,
    select: ui.select,
    check: ui.check,
    notice: ui.notice,
    summary: ui.summary,
    action: (label) => "<button>" + ui.escape(label) + "</button>",
    go: (label) => "<button>" + ui.escape(label) + "</button>",
    badge: () => "",
    tell: () => {},
    navigate: async () => {},
  });
  const payment = {
    id: "synthetic-payment",
    status: "awaiting",
    amountCents: 600,
    creditCents: 0,
    externalCents: 600,
    confirmed: false,
    checkout: null,
    ...override.payment,
  };
  const details = {
    type: "avulsa",
    driverName: "Pessoa S.",
    rideCount: 1,
    subtotalCents: 600,
    discountCents: 0,
    platformCents: 60,
    tripReview: "a".repeat(64),
    trips: [
      {
        date: "2026-11-01",
        time: "18:30",
        origin: "Região sintética",
        destination: "Universidade",
        meeting: "<img src=x onerror=alert(1)>",
        notes: "<script>private()</script>",
        cents: 600,
        detailsAvailable: true,
      },
    ],
    ...override.details,
  };
  ctx.request = async (path) =>
    path.endsWith("/summary")
      ? details
      : path.endsWith("/config")
        ? override.configuration || {
            provider: "development",
            cardEnabled: true,
          }
        : path.endsWith("/gateway")
          ? override.gateway || { state: "PENDING" }
          : path.endsWith("/refund-review")
            ? {}
            : payment;
  run(ctx, "features/payments/views.js");
  Object.assign(
    ctx,
    ctx.window.UniCaronaFeatures.payments.create({
      ...ctx,
      state: ctx,
      navigate: (...args) => ctx.navigate(...args),
      tell: (...args) => ctx.tell(...args),
    }),
  );
  return ctx;
}
test("checkout displays the actual trip before the financial summary, escapes instructions, and binds its review to the form", async () => {
  const ctx = checkoutReader();
  const html = await ctx.checkout();
  assert.ok(
    html.indexOf("Ponto de encontro") < html.indexOf("Resumo do pagamento"),
  );
  assert.match(html, /Observações do motorista/);
  assert.match(html, /&lt;img src=x onerror=alert\(1\)&gt;/);
  assert.match(html, /&lt;script&gt;private\(\)&lt;\/script&gt;/);
  assert.ok(!html.includes("<script>private"));
  assert.match(html, /data-c-form="checkout"/);
  assert.match(
    html,
    new RegExp("&quot;tripReview&quot;:&quot;" + "a".repeat(64)),
  );
  assert.equal(ctx.current.tripReview, "a".repeat(64));
  assert.ok(html.includes("Valor deste trecho: " + ctx.cash(600)));
});
test("checkout without an authorized trip review offers recovery without a first-payment form or protected instructions", async () => {
  const ctx = checkoutReader({
    details: {
      tripReview: null,
      trips: [
        {
          date: "2026-11-01",
          time: "18:30",
          origin: "Região",
          destination: "Universidade",
          meeting: "must not appear",
          notes: "private note must not appear",
          detailsAvailable: false,
        },
      ],
    },
  });
  const html = await ctx.checkout();
  assert.ok(!html.includes('data-c-form="checkout"'));
  assert.ok(!html.includes("must not appear"));
  assert.match(html, /Atualizar resumo/);
  assert.match(html, /Detalhes do encontro protegidos/);
});
test("monthly checkout groups identical instructions while retaining every date and a distinct last-leg instruction", async () => {
  const common = {
    time: "18:30",
    direction: "ida",
    origin: "Região",
    destination: "Universidade",
    meeting: "Encontro comum",
    notes: "Instrução comum",
    detailsAvailable: true,
  };
  const ctx = checkoutReader({
    payment: { amountCents: 1674, externalCents: 1674 },
    details: {
      type: "mensal",
      rideCount: 3,
      subtotalCents: 1860,
      discountCents: 186,
      trips: [
        { ...common, date: "2026-11-01", cents: 537 },
        { ...common, date: "2026-11-02", cents: 538 },
        {
          ...common,
          date: "2026-11-03",
          cents: 599,
          notes: "Instrução diferente no último trecho",
        },
      ],
    },
  });
  const html = await ctx.checkout();
  assert.equal(html.split("Instrução comum").length - 1, 1);
  assert.equal(
    html.split("Datas, horários e valores deste trajeto").length - 1,
    2,
  );
  assert.equal((html.match(/<li>/g) || []).length, 3);
  assert.match(html, /Instrução diferente no último trecho/);
  assert.match(html, /1 de nov/);
  assert.match(html, /2 de nov/);
  assert.match(html, /3 de nov/);
  for (const amount of [537, 538, 599])
    assert.ok(html.includes("<strong>" + ctx.cash(amount) + "</strong>"));
  assert.match(html, /descontos aplicáveis já incluídos/);
  assert.match(html, /data-c-form="checkout"/);
});
test("initial checkout fails closed when authoritative leg amounts are absent or disagree with the payment", async () => {
  for (const cents of [undefined, 599, 600.5, -1]) {
    const ctx = checkoutReader({
        details: {
          trips: [
            {
              date: "2026-11-01",
              time: "18:30",
              origin: "Região",
              destination: "Universidade",
              cents,
              detailsAvailable: true,
            },
          ],
        },
      }),
      html = await ctx.checkout();
    assert.ok(!html.includes('data-c-form="checkout"'));
    assert.match(html, /Atualizar resumo/);
    assert.ok(!html.includes("NaN"));
  }
});
test("a stale trip review refreshes the checkout but never automatically resubmits the payment", async () => {
  const calls = [],
    navigation = [],
    messages = [],
    ctx = context();
  ctx.post = async (path, body) => {
    calls.push({ path, body });
    throw { code: "TRIP_REVIEW_REQUIRED" };
  };
  ctx.navigate = async (...args) => navigation.push(args);
  ctx.tell = (text) => messages.push(text);
  run(ctx, "features/payments/views.js");
  ctx.attempt = ctx.window.UniCaronaFeatures.payments.create({
    ...ctx,
    state: ctx,
  }).submitCheckout;
  await ctx.attempt({
    id: "synthetic-payment",
    method: "pix",
    useCredit: false,
    tripReview: "b".repeat(64),
  });
  assert.equal(calls.length, 1);
  assert.equal(calls[0].body.tripReview, "b".repeat(64));
  assert.deepEqual(plain(navigation), [
    ["checkout", "synthetic-payment", true],
  ]);
  assert.equal(messages.length, 0);
  assert.equal(ctx.tripReviewChangedId, "synthetic-payment");
  const rendered = checkoutReader();
  rendered.tripReviewChangedId = ctx.tripReviewChangedId;
  const html = await rendered.checkout();
  assert.match(html, /role="status"/);
  assert.match(html, /Detalhes atualizados\. Confira/);
  assert.equal(rendered.tripReviewChangedId, "synthetic-payment");
});
test("an expired first checkout has an explicit deadline state and directs to requests without a refresh loop", async () => {
  const ctx = checkoutReader({
    payment: { expiresAt: "2000-01-01T00:00:00Z" },
    details: { tripReview: null, trips: [] },
  });
  const html = await ctx.checkout();
  assert.match(html, /Prazo encerrado/);
  assert.match(html, /O prazo para confirmar esta reserva terminou/);
  assert.match(html, /Ver meus pedidos/);
  assert.ok(!html.includes("Atualizar resumo"));
  assert.ok(!html.includes('data-c-form="checkout"'));
});
test("an expired pending checkout stops collecting card data or displaying stale Pix without declaring a financial cancellation", async () => {
  for (const method of ["card", "pix"]) {
    const ctx = checkoutReader({
        payment: {
          status: "pending",
          method,
          expiresAt: "2000-01-01T00:00:00Z",
        },
        configuration: {
          provider: "mercadopago",
          cardEnabled: true,
          environment: "test",
        },
        gateway: {
          state: "PENDING",
          statusDetail: "pending_challenge",
          challengeUrl: "https://www.mercadopago.com.br/stale",
          qrCode: "stale-private-qr",
          cardSubmitted: false,
        },
      }),
      html = await ctx.checkout();
    assert.match(html, /Prazo deste checkout encerrado/);
    assert.match(html, /resultado de qualquer envio já iniciado/);
    assert.match(html, /Atualizar status/);
    assert.ok(!html.includes("data-mp-card"));
    assert.ok(!html.includes("data-mp-challenge"));
    assert.ok(!html.includes("stale-private-qr"));
    assert.ok(!html.includes("Esta cobrança foi cancelada"));
    assert.equal(ctx.current.gateway.state, "PENDING");
  }
});
test("checkout redraws at the deadline even when provider fields have not changed, without an expired-view refresh loop", async () => {
  let now = Date.parse("2026-01-01T12:00:00Z");
  const fixture = {
      payment: {
        status: "pending",
        method: "card",
        expiresAt: "2026-01-01T12:01:00Z",
      },
      configuration: {
        provider: "mercadopago",
        cardEnabled: true,
        environment: "test",
      },
      gateway: { state: "CREATED", cardSubmitted: false },
    },
    ctx = checkoutReader(fixture),
    navigations = [];
  ctx.Date = class extends Date {
    static now() {
      return now;
    }
  };
  ctx.navigate = async (...args) => navigations.push(args);
  assert.match(await ctx.checkout(), /data-mp-card/);
  now = Date.parse("2026-01-01T12:01:01Z");
  await ctx.checkoutPollCallbacks[0]();
  assert.deepEqual(plain(navigations), [
    ["checkout", "synthetic-payment", true],
  ]);
  const expired = checkoutReader(fixture),
    repeats = [];
  expired.Date = ctx.Date;
  expired.navigate = async (...args) => repeats.push(args);
  assert.ok(!(await expired.checkout()).includes("data-mp-card"));
  await expired.checkoutPollCallbacks[0]();
  assert.equal(repeats.length, 0);
});
