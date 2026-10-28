/* authDocuments: explicit dependencies and session-owned state. */
(() => {
  "use strict";
  function create({
    state: authState,
    UI,
    api,
    feedback,
    host,
    legal,
    passwordField,
  }) {
    async function loadSignupDocuments() {
      const current = authState.revision,
        container = host.querySelector("#signup-legal"),
        button = host.querySelector("#signup-step1 [type=submit]");
      if (!container || !button) return;
      button.disabled = true;
      container.innerHTML =
        '<p class="field-hint" role="status">Carregando os documentos para leitura…</p>';
      try {
        const data = await legal.manifest(true);
        if (current !== authState.revision || !container.isConnected) return;
        const next = legal.references(data);
        const previous = authState.signupAccessDraft.documents.length
          ? authState.signupAccessDraft.documents
          : authState.signupLegalSnapshot;
        const referenceSet = (refs) =>
          JSON.stringify(
            [...refs]
              .sort((a, b) => a.slug.localeCompare(b.slug))
              .map(({ slug, version, contentSha256 }) => ({
                slug,
                version,
                contentSha256,
              })),
          );
        const changed =
          previous && referenceSet(previous) !== referenceSet(next);
        if (changed) {
          authState.signupAccessDraft.rulesAccepted = false;
          authState.signupAccessDraft.privacyNotice = false;
          authState.signupAccessDraft.documents = [];
          host.querySelector("#signup-terms").checked = false;
          host.querySelector("#signup-privacy").checked = false;
        }
        authState.signupLegalSnapshot = next;
        container.innerHTML =
          (changed
            ? '<p class="field-hint" role="status">Os documentos foram atualizados. Leia as novas versões e confirme novamente os controles abaixo.</p>'
            : "") +
          '<p class="field-hint">Leia os documentos destas versões antes de continuar. Os links abrem em outra aba.</p>' +
          legal.documentLinks(data);
        button.disabled = false;
      } catch (failure) {
        if (current !== authState.revision || !container.isConnected) return;
        authState.signupLegalSnapshot = null;
        container.innerHTML =
          '<p class="field-hint" role="alert">' +
          UI.escape(failure.message) +
          '</p><button type="button" class="m-button secondary" id="signup-legal-retry">Carregar documentos novamente</button><p class="field-hint"><a href="legal.html?document=privacy" target="_blank" rel="noopener">Consultar privacidade e contato</a></p>';
      }
    }

    async function loadIdentityConfirmation() {
      const current = authState.revision,
        state = host.querySelector("#identity-confirm-state");
      if (!state) return;
      if (!authState.identityChangeToken) {
        state.innerHTML =
          '<p class="state-note">Abra novamente o link enviado ao novo endereço. Se ele venceu, solicite outra troca em Privacidade e dados.</p><a class="primary m-button" href="dashboard.html?view=privacy_identity">Ir para Privacidade e dados</a>';
        return;
      }
      try {
        const me = await api.request("/auth/me");
        if (current !== authState.revision || !state.isConnected) return;
        state.innerHTML =
          '<p class="state-note">Conta conectada: <strong>' +
          UI.escape(me.email) +
          '</strong>.</p><form id="identity-email-confirm" novalidate>' +
          passwordField(
            "identity-password",
            "Confirme sua senha",
            "current-password",
          ) +
          feedback +
          '<button type="submit" class="primary m-button">Confirmar novo e-mail</button></form><a class="text-link" href="#login">Entrar com outra conta</a>';
      } catch (failure) {
        if (current !== authState.revision || !state.isConnected) return;
        state.innerHTML =
          failure.status === 401
            ? '<p class="state-note">Entre na conta que solicitou a troca. Depois você voltará para confirmar o novo e-mail.</p><a class="primary m-button" href="#login">Entrar para confirmar</a>'
            : '<p role="alert">' +
              UI.escape(failure.message) +
              '</p><button type="button" class="primary m-button" id="identity-confirm-retry">Tentar novamente</button>';
      }
    }
    return { loadSignupDocuments, loadIdentityConfirmation };
  }
  window.UniCaronaFeatures ||= {};
  window.UniCaronaFeatures.authDocuments = Object.freeze({ create });
})();
