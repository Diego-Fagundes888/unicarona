(() => {
  "use strict";
  const api = window.UniCaronaAPI,
    id = new URL(location.href).searchParams.get("payment");
  const state = document.querySelector("#payment-state"),
    actions = document.querySelector("#payment-actions"),
    feedback = document.querySelector("#payment-error");
  if (!id || !/^[a-f0-9-]{36}$/i.test(id)) {
    state.textContent =
      "Cobrança inválida. Volte ao pagamento no UniCarona para abrir a cobrança correta.";
    document.querySelector("#payment-loading").hidden = true;
    return;
  }
  document.querySelector("#payment-back").href =
    "dashboard.html?view=checkout&ref=" + encodeURIComponent(id);
  function error(failure) {
    feedback.textContent = failure.message;
    feedback.focus();
  }
  async function load() {
    try {
      const runtime = await api.request("/runtime");
      if (runtime.paymentEnvironment !== "test") {
        state.textContent = "Este ambiente não permite testes de pagamento.";
        return;
      }
      const payment = await api.request("/payments/" + id);
      state.textContent =
        "Valor externo de teste: " +
        new Intl.NumberFormat("pt-BR", {
          style: "currency",
          currency: "BRL",
        }).format(payment.externalCents / 100) +
        ".";
      if (
        payment.status === "pending" &&
        payment.checkout?.environment === "test"
      )
        actions.hidden = false;
      else
        state.textContent +=
          " Esta cobrança já foi encerrada ou ainda não está preparada.";
    } catch (failure) {
      state.textContent =
        "Não foi possível carregar a cobrança. Volte ao UniCarona e tente abrir o pagamento novamente.";
      error(failure);
    } finally {
      document.querySelector("#payment-loading").hidden = true;
    }
  }
  actions.addEventListener("click", async (event) => {
    const button = event.target.closest("[data-result]");
    if (!button || button.disabled) return;
    const buttons = [...actions.querySelectorAll("button")];
    buttons.forEach((item) => (item.disabled = true));
    button.setAttribute("aria-busy", "true");
    const original = button.textContent;
    button.textContent = "Enviando resultado…";
    feedback.textContent = "";
    try {
      await api.request("/development/payments/" + id + "/result", {
        method: "POST",
        body: { status: button.dataset.result },
      });
      actions.hidden = true;
      state.textContent =
        "Resultado de teste enviado. Confira o estado da reserva no UniCarona.";
    } catch (failure) {
      error(failure);
      buttons.forEach((item) => (item.disabled = false));
    } finally {
      button.removeAttribute("aria-busy");
      button.textContent = original;
    }
  });
  load();
})();
