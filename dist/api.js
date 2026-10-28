/* Same-origin API client. Session credentials remain in HttpOnly cookies. */
(() => {
  "use strict";
  let refreshFlight = null;
  const pendingKeys = new Map();
  class APIError extends Error {
    constructor(message, code, status, details = {}) {
      super(message);
      this.code = code;
      this.status = status;
      this.details = details;
    }
  }
  function csrf() {
    const value = document.cookie
      .split("; ")
      .find((x) => x.startsWith("uc_csrf="));
    try {
      return value ? decodeURIComponent(value.slice(8)) : "";
    } catch {
      return "";
    }
  }
  async function request(path, options = {}, retried = false) {
    const method = options.method || "GET",
      mutation = !["GET", "HEAD"].includes(method);
    const form = options.body instanceof FormData;
    const headers = {
      ...(mutation ? { "x-csrf-token": csrf() } : {}),
      ...(options.body !== undefined && !form
        ? { "Content-Type": "application/json" }
        : {}),
    };
    const critical = mutation && !path.startsWith("/auth/") && !form;
    const bodyDigest = critical
      ? Array.from(new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(JSON.stringify(options.body || {}))))).map(x => x.toString(16).padStart(2, "0")).join("")
      : "";
    const fingerprint =
      method +
      ":" +
      path +
      ":" +
      bodyDigest;
    if (critical) {
      if (!pendingKeys.has(fingerprint))
        pendingKeys.set(fingerprint, crypto.randomUUID());
      headers["idempotency-key"] = options.key || pendingKeys.get(fingerprint);
    }
    let response;
    try {
      response = await fetch("/api/v1" + path, {
        method,
        credentials: "same-origin",
        headers,
        signal: options.signal,
        ...(options.body !== undefined
          ? { body: form ? options.body : JSON.stringify(options.body) }
          : {}),
      });
    } catch (error) {
      if (error.name === "AbortError") throw error;
      throw new APIError(
        "Não foi possível conectar. Confira sua conexão e tente novamente.",
        "NETWORK_ERROR",
        0,
      );
    }
    let result;
    try {
      result = response.ok && options.format === "blob" ? await response.blob() : await response.json();
    } catch {
      throw new APIError(
        "O serviço não está disponível. Tente novamente.",
        "SERVICE_UNAVAILABLE",
        503,
      );
    }
    if (response.status === 401 && ["UNAUTHENTICATED", "SESSION_EXPIRED"].includes(result.code) && !retried && !path.startsWith("/auth/")) {
      refreshFlight ||= request(
        "/auth/refresh",
        { method: "POST", body: {} },
        true,
      ).finally(() => {
        refreshFlight = null;
      });
      await refreshFlight;
      return request(path, options, true);
    }
    if (!response.ok) {
      if (response.status < 500) pendingKeys.delete(fingerprint);
      throw new APIError(
        result.message || "Não foi possível concluir.",
        result.code,
        response.status,
        result.details,
      );
    }
    pendingKeys.delete(fingerprint);
    return result;
  }
  async function upload(file, purpose) {
    const body = new FormData();
    body.append("purpose", purpose);
    body.append("file", file);
    return request("/files", { method: "POST", body });
  }
  window.UniCaronaAPI = Object.freeze({ request, upload, APIError });
})();
