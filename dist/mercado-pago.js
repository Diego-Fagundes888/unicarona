/* Official Card Payment Brick. Only its token is sent to our backend. */
(() => {
  "use strict";
  let sdkFlight;
  function deviceSessionId() {
    const value = window.MP_DEVICE_SESSION_ID;
    return typeof value === "string" && /^[\x21-\x7E]{8,1024}$/.test(value) ? value : undefined;
  }
  function collectDevice(nonce) {
    if (deviceSessionId() || document.querySelector("[data-unicarona-mp-device]")) return;
    // Official fallback when SDK profiling did not expose its device identifier.
    // Tracking failure must not block tokenization or create a second payment.
    const script = document.createElement("script");
    script.src = "https://www.mercadopago.com/v2/security.js";
    script.setAttribute("view", "checkout");
    script.setAttribute("data-unicarona-mp-device", "");
    script.async = true;
    if (nonce) script.nonce = nonce;
    document.head.append(script);
  }
  function sdk() {
    if (window.MercadoPago) return Promise.resolve();
    if (sdkFlight) return sdkFlight;
    sdkFlight = new Promise((resolve, reject) => {
      const script = document.createElement("script");
      script.src = "https://sdk.mercadopago.com/js/v2";
      script.async = true;
      const timer = setTimeout(() => {
        script.remove();
        reject(new Error("O formulário do cartão demorou para abrir. Tente novamente ou use Pix."));
      }, 15000);
      script.onload = () => { clearTimeout(timer); resolve(); };
      script.onerror = () => { clearTimeout(timer); script.remove(); reject(new Error("Não foi possível abrir o formulário seguro. Confira sua conexão e tente novamente.")); };
      document.head.append(script);
    }).catch(error => { sdkFlight = null; throw error; });
    return sdkFlight;
  }
  function mount(root, payment, config, gateway, refresh) {
    let stopped = false, controller, running = false, ready = false, initializationFailed = false, initializationTimer;
    const host = root.querySelector("[data-mp-card]"), feedback = root.querySelector("[data-mp-feedback]");
    const report = (message, error = false) => { if (!stopped && feedback) {feedback.textContent = message;feedback.classList.toggle("m-error", error);feedback.classList.toggle("m-help", !error);} };
    const challenge = root.querySelector("[data-mp-challenge]");
    function message(event) {
      if (stopped || !challenge || event.source !== challenge.contentWindow || event.origin !== new URL(challenge.src).origin) return;
      if (event.data?.status === "COMPLETE") refresh(); // This event never approves a payment.
    }
    if (challenge) window.addEventListener("message", message);
    if (host) (async () => {
      try {
        initializationTimer = setTimeout(() => {
          if (stopped || ready) return;
          initializationFailed = true;
          host.removeAttribute("aria-busy");
          report("O formulário seguro não conseguiu abrir. Atualize a página para tentar novamente.", true);
          try {controller?.unmount();} catch { /* A late SDK response is also unmounted below. */ }
        }, 30000);
        await sdk();
        if (stopped || initializationFailed) return;
        const nonce = document.querySelector('meta[name="unicarona-csp-nonce"]')?.content;
        const mp = new window.MercadoPago(config.publicKey, { locale: "pt-BR", ...(nonce ? {deviceProfileCspNonce: nonce} : {}) });
        controller = await mp.bricks().create("cardPayment", host.id, {
          initialization: { amount: payment.externalCents / 100, ...(config.environment === "test" ? {payer: {email: "test@testuser.com"}} : {}) },
          customization: { visual: { style: { theme: "default" } }, paymentMethods: { minInstallments: 1, maxInstallments: 1 } },
          callbacks: {
            onReady: () => { if (!stopped && !initializationFailed) {ready = true;clearTimeout(initializationTimer);host.removeAttribute("aria-busy"); collectDevice(nonce);report("");} },
            onSubmit: async (formData, additionalData) => {
              if (stopped || running || initializationFailed) throw new Error("Atualize a página para continuar com o pagamento.");
              running = true;
              report("Enviando ao Mercado Pago. Aguarde a confirmação.");
              try {
                await window.UniCaronaAPI.request("/payments/" + payment.id + "/card", {
                  method: "POST",
                  body: {
                    token: formData.token,
                    method: formData.payment_method_id,
                    type: additionalData?.paymentTypeId || "credit_card",
                    installments: formData.installments,
                    identificationType: formData.payer?.identification?.type,
                    identificationNumber: formData.payer?.identification?.number?.replace(/\D/g, ""),
                    ...(deviceSessionId() ? {deviceSessionId: deviceSessionId()} : {}),
                  },
                });
                if (!stopped) refresh();
              } catch (error) {
                report(error.message || "Não foi possível enviar o pagamento. Confira os dados e tente novamente.", true);
                // A lost response can leave a durable server command. Do not obtain a second token then.
                try {
                  const latest = await window.UniCaronaAPI.request("/payments/" + payment.id + "/gateway");
                  if (!stopped && latest.cardSubmitted) refresh();
                } catch { /* Keep the error visible and preserve the secure form. */ }
                throw error;
              } finally {running = false;}
            },
            onError: () => report("Confira os campos do cartão. Se o formulário não abrir, atualize a página para tentar novamente.", true),
          },
        });
        if (stopped || initializationFailed) controller.unmount();
      } catch (error) { clearTimeout(initializationTimer);if (host && !stopped) host.removeAttribute("aria-busy");report(error.message || "Não foi possível carregar o cartão. Tente novamente.", true); }
    })();
    return () => {
      stopped = true;
      clearTimeout(initializationTimer);
      window.removeEventListener("message", message);
      try {controller?.unmount();} catch { /* The screen may already have detached its container. */ }
    };
  }
  window.UniCaronaPayments = Object.freeze({ mount });
})();
