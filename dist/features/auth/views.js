/* authViews: explicit dependencies and session-owned state. */
(() => {
  "use strict";
  function create({
    state: authState,
    UI,
    back,
    bindCatalogCombobox,
    checkEmailService,
    clearFieldFeedback,
    dashboardDestination,
    emailField,
    feedback,
    host,
    loadCatalogs,
    loadIdentityConfirmation,
    loadSignupDocuments,
    maskedEmail,
    passwordField,
    restoreAcademicFields,
    saveSignupProfileDraft,
    signupEmailField,
    signupNameField,
    stepBar,
    title,
    updatePeriodNumberField,
    updateResendCountdown,
    verifyEmail,
  }) {
    function signupProfileForm() {
      return `
    <form id="signup-step2" novalidate>
      <div class="fixed-school"><span>Faculdade</span><strong>UNICEPLAC</strong></div>
      <div class="field m-field">
        <label class="m-field-caption" for="signup-course">Curso</label>
        <p class="field-hint" id="signup-course-hint">Digite o nome e escolha seu curso na lista. Você também pode usar as setas e Enter.</p>
        <div class="search-combobox">
          <input id="signup-course" type="text" role="combobox" aria-autocomplete="list" aria-haspopup="listbox" aria-expanded="false" aria-controls="signup-course-options" autocomplete="off" spellcheck="false" placeholder="Busque seu curso" required aria-describedby="signup-course-hint signup-course-error">
          <div id="signup-course-options" class="combobox-options" role="listbox" aria-label="Cursos da UNICEPLAC" hidden></div>
          <p id="signup-course-status" class="visually-hidden" role="status" aria-live="polite" aria-atomic="true"></p>
        </div>
        <p id="signup-course-selected" class="field-selection" hidden></p>
        <p id="signup-course-error" class="field-error"></p>
      </div>
      <p id="signup-academic-status" class="field-hint academic-status">Informe seu período e o horário das aulas. Essas informações são declaradas por você e não foram verificadas pela faculdade.</p>
      <div class="academic-selection">
        <div class="field m-field">
          <label class="m-field-caption" for="signup-period-status">Como você cursa as matérias? <span class="optional">(opcional)</span></label>
          <select id="signup-period-status" disabled aria-describedby="signup-academic-status signup-period-status-hint signup-period-status-error">
            <option value="numbered">Um período por vez</option>
            <option value="irregular">Matérias de períodos diferentes</option>
            <option value="undefined">Ainda sem período definido</option>
          </select>
          <p id="signup-period-status-hint" class="field-hint">Se faz matérias de vários períodos ao mesmo tempo, escolha a segunda opção.</p>
          <p id="signup-period-status-error" class="field-error"></p>
        </div>
        <div class="field m-field" id="signup-period-number-field">
          <label class="m-field-caption" for="signup-period">Número do período <span class="required-label">(obrigatório)</span></label>
          <input id="signup-period" type="text" inputmode="numeric" autocomplete="off" placeholder="Ex.: 3" required disabled aria-describedby="signup-academic-status signup-period-hint signup-period-error">
          <p id="signup-period-hint" class="field-hint">É a etapa do seu curso. Ex.: digite 3 para o 3º período.</p>
          <p id="signup-period-error" class="field-error"></p>
        </div>
        <div class="field m-field">
          <label class="m-field-caption" for="signup-shift">Horário das aulas (turno) <span class="required-label">(obrigatório)</span></label>
          <select id="signup-shift" required disabled aria-describedby="signup-academic-status signup-shift-error">
            <option value="">Selecione o horário</option>
            <option value="morning">Manhã</option>
            <option value="afternoon">Tarde</option>
            <option value="night">Noite</option>
            <option value="full-time">O dia todo</option>
            <option value="variable">Horários variados</option>
          </select>
          <p id="signup-shift-error" class="field-error"></p>
        </div>
        <div class="field m-field">
          <label class="m-field-caption" for="signup-class">Turma <span class="optional">(opcional)</span></label>
          <select id="signup-class" disabled aria-describedby="signup-academic-status signup-class-help signup-class-error">
            <option value="">Selecione sua turma</option>
            <option value="A">Turma A</option>
            <option value="B">Turma B</option>
            <option value="C">Turma C</option>
          </select>
          <details id="signup-class-help" class="class-help">
            <summary>Não sabe qual turma escolher?</summary>
            <p>Se não houver divisão de turmas ou existir só uma, selecione <strong>Turma A</strong>.</p>
          </details>
          <p id="signup-class-error" class="field-error"></p>
        </div>
      </div>
      <div class="field m-field">
        <label class="m-field-caption" for="signup-neighborhood">Região ou cidade onde costuma embarcar</label>
        <p class="field-hint" id="signup-neighborhood-hint">Digite sua região ou cidade e escolha uma opção da lista.</p>
        <div class="search-combobox">
          <input id="signup-neighborhood" type="text" role="combobox" aria-autocomplete="list" aria-haspopup="listbox" aria-expanded="false" aria-controls="signup-neighborhood-options" autocomplete="off" spellcheck="false" placeholder="Busque uma região ou cidade" required aria-describedby="signup-neighborhood-hint signup-neighborhood-error">
          <div id="signup-neighborhood-options" class="combobox-options" role="listbox" aria-label="Regiões administrativas do DF e municípios da RIDE-DF" hidden></div>
          <p id="signup-neighborhood-status" class="visually-hidden" role="status" aria-live="polite" aria-atomic="true"></p>
        </div>
        <p id="signup-neighborhood-selected" class="field-selection" hidden></p>
        <p id="signup-neighborhood-error" class="field-error"></p>
      </div>
      <div class="field m-field">
        <label class="m-field-caption" for="signup-phone">Telefone ou WhatsApp <span class="m-help">(opcional)</span></label>
        <input id="signup-phone" type="tel" autocomplete="tel" inputmode="tel" placeholder="(61) 99999-9999" maxlength="25" aria-describedby="signup-phone-hint signup-phone-error">
        <p id="signup-phone-hint" class="field-hint">Você pode adicionar ou atualizar seu telefone depois, em Perfil.</p>
        <p id="signup-phone-error" class="field-error"></p>
      </div>
      <p class="field-hint">Depois de confirmar seu e-mail e entrar, você poderá adicionar uma foto em Perfil.</p>
      ${feedback}
      <button class="primary m-button" type="submit">Continuar</button>
    </form>`;
    }

    function render() {
      clearInterval(authState.resendTimer);
      authState.emailServiceTimers.forEach(clearTimeout);
      authState.emailServiceTimers = [];
      authState.revision++;
      const fragment = location.hash.slice(1).split("?");
      const requested = fragment[0] || "login";
      if (requested === "alterar-email" && fragment[1]) {
        const token = new URLSearchParams(fragment[1]).get("token") || "";
        authState.identityChangeToken = /^[A-Za-z0-9_-]{43}$/.test(token)
          ? token
          : "";
        history.replaceState(null, "", "#alterar-email");
      }
      if (requested === "verificado" && !authState.emailVerificationSucceeded) {
        location.replace("#login");
        return;
      }
      if (requested === "verificar") {
        const token = new URLSearchParams(fragment[1] || "").get("token") || "";
        history.replaceState(null, "", "#verificando");
        verifyEmail(token);
        return;
      }
      if (requested === "redefinir" && fragment[1]) {
        authState.resetToken =
          new URLSearchParams(fragment[1]).get("token") || "";
        history.replaceState(null, "", "#nova-senha");
      }
      const nextView =
        requested === "redefinir"
          ? authState.resetToken
            ? "nova-senha"
            : "link-expirado"
          : requested === "nova-senha" && !authState.resetToken
            ? "link-expirado"
            : ["acesso", "painel"].includes(requested)
              ? "painel"
              : requested;
      if (
        authState.activeView === "cadastro-perfil" &&
        nextView !== "cadastro-perfil"
      )
        saveSignupProfileDraft();
      authState.activeView = nextView;
      document.body.dataset.authView = authState.activeView;
      const shouldScrollToSignupStep =
        authState.pendingSignupScrollView === authState.activeView;
      if (authState.pendingSignupScrollView && !shouldScrollToSignupStep)
        authState.pendingSignupScrollView = "";

      if (authState.activeView === "painel") {
        authState.pendingSignupScrollView = "";
        location.replace(dashboardDestination());
        return;
      }

      // A refresh or direct link to step two must load the catalog too.
      if (
        authState.activeView === "cadastro-perfil" &&
        !authState.catalogReady
      ) {
        const currentRevision = authState.revision;
        host.innerHTML =
          title(
            "Seu perfil acadêmico.",
            "Carregando os cursos e regiões disponíveis…",
          ) + UI.loading("Carregando cursos e regiões…");
        host.setAttribute("aria-busy", "true");
        document.title = "Seu perfil acadêmico | UniCarona";
        loadCatalogs()
          .then(() => {
            if (
              authState.revision === currentRevision &&
              authState.activeView === "cadastro-perfil"
            )
              render();
          })
          .catch((failure) => {
            if (
              authState.revision !== currentRevision ||
              authState.activeView !== "cadastro-perfil"
            )
              return;
            host.removeAttribute("aria-busy");
            host.innerHTML =
              title(
                "Não foi possível carregar o cadastro.",
                "Tente novamente para consultar os cursos e regiões.",
              ) +
              UI.notice(failure.message, "error") +
              UI.button(
                "Tentar novamente",
                { "data-catalog-retry": "" },
                "primary",
              ) +
              '<a class="back-link" href="#cadastro">Voltar para seus dados</a>';
            host.querySelector("h1").focus();
          });
        return;
      }

      const states = {
        // --- Login flow ---
        login:
          title(
            "Entre na sua conta.",
            "Acesse suas caronas e os combinados para a faculdade.",
          ) +
          `<form id="login-form" novalidate>${emailField}${passwordField("password", "Senha", "current-password")}<div class="form-options"><a class="text-link" href="#recuperar">Esqueci minha senha</a></div>${feedback}<button class="primary m-button" type="submit">Entrar</button></form>` +
          `<p class="account-link">Ainda não tem conta? <a href="#cadastro">Criar conta</a></p>`,

        recuperar:
          back +
          title(
            "Esqueceu sua senha?",
            "Informe o e-mail da sua conta para receber as instruções de recuperação.",
          ) +
          `<form id="recovery-form" novalidate>${emailField}${feedback}<button class="primary m-button" type="submit">Enviar instruções</button></form>`,

        instrucoes:
          back +
          '<div class="state-mark" aria-hidden="true">' +
          UI.icon("mail") +
          "</div>" +
          title(
            "Confira as instruções.",
            "Se houver uma conta com esse e-mail, você receberá um link para definir uma nova senha.",
          ) +
          '<p class="state-note">Confira também a pasta de spam. O link tem prazo de validade e pode ser usado apenas uma vez.</p>' +
          '<a class="text-link" href="#recuperar">Informar outro e-mail</a>',

        "nova-senha":
          back +
          title(
            "Defina uma nova senha.",
            "Escolha uma senha diferente das que você usa em outros serviços.",
          ) +
          `<form id="reset-form" novalidate>${passwordField("password", "Nova senha", "new-password")}${passwordField("confirm", "Confirmar nova senha", "new-password")}${feedback}<button class="primary m-button" type="submit">Salvar nova senha</button></form>`,

        "senha-alterada":
          '<div class="state-mark" aria-hidden="true">' +
          UI.icon("check") +
          "</div>" +
          title("Senha alterada.", "Entre novamente com sua nova senha.") +
          '<p class="state-note">As sessões anteriores foram encerradas para proteger sua conta.</p>' +
          '<a class="primary m-button" href="#login">Voltar para entrar</a>',

        "link-expirado":
          '<div class="state-mark warning" aria-hidden="true">' +
          UI.icon("warning") +
          "</div>" +
          title(
            "Este link não está disponível.",
            "O link de recuperação pode ter expirado ou já ter sido usado. Solicite novas instruções para continuar.",
          ) +
          '<div class="stack"><a class="primary m-button" href="#recuperar">Solicitar novo link</a><a class="text-link" href="#login">Voltar para entrar</a></div>',

        // --- Signup flow (3 steps) ---
        cadastro:
          '<a class="back-link" href="index.html">' +
          UI.icon("arrowLeft") +
          "Voltar ao início</a>" +
          title(
            "Vamos criar seu acesso.",
            "Use um e-mail que você possa confirmar. Ele não precisa ser institucional.",
          ) +
          stepBar(1, 3) +
          `<form id="signup-step1" novalidate>${UI.group("Seus dados", signupNameField + signupEmailField)}${UI.group("Seu acesso", passwordField("signup-password", "Senha", "new-password") + passwordField("signup-confirm", "Confirmar senha", "new-password"))}<div class="field m-field"><label class="m-field-caption" for="signup-birth">Data de nascimento</label><input id="signup-birth" type="date" autocomplete="bday" required aria-describedby="signup-birth-error signup-birth-hint"><p id="signup-birth-error" class="field-error"></p><p class="field-hint" id="signup-birth-hint">A participação é permitida a partir dos 18 anos.</p></div><label class="check-field"><input type="checkbox" id="signup-truth" aria-describedby="signup-truth-error"><span>Declaro que as informações deste cadastro são verdadeiras.</span></label><p id="signup-truth-error" class="field-error"></p><p class="field-hint">Antes de aceitar, leia <a href="index.html#regras" target="_blank" rel="noopener">as regras de participação</a> e <a href="index.html#privacidade" target="_blank" rel="noopener">como seus dados são usados</a>.</p><label class="check-field"><input type="checkbox" id="signup-terms" aria-describedby="signup-terms-error"><span>Aceito as regras aplicáveis ao uso do UniCarona.</span></label><p id="signup-terms-error" class="field-error"></p>${feedback}<button class="primary m-button" type="submit">Continuar</button></form>` +
          `<p class="account-link">Já tem conta? <a href="#login">Entrar</a></p>`,

        "cadastro-perfil":
          '<a class="back-link" href="#cadastro">' +
          UI.icon("arrowLeft") +
          "Voltar</a>" +
          title(
            "Seu perfil acadêmico.",
            "Sua comunidade já está definida. Conte um pouco sobre seu curso e sua região.",
          ) +
          stepBar(2, 3) +
          signupProfileForm(),

        "cadastro-confirmar-email":
          back +
          '<div class="state-mark" aria-hidden="true">' +
          UI.icon("mail") +
          "</div>" +
          title(
            "Confirme seu e-mail.",
            authState.emailNoticeMode === "login"
              ? "Seu e-mail ainda não foi confirmado. Abra o link recebido ou solicite outro para:"
              : "Solicitação recebida. Se necessário, enviaremos um link para:",
          ) +
          (authState.emailNoticeMode === "registration" ? stepBar(3, 3) : "") +
          '<p class="email-address" id="signup-email-address"></p><p class="state-note">O envio pode levar alguns instantes. Confira também Spam ou Lixo eletrônico. O link vale por até 60 minutos; se pedir outro, use o e-mail mais recente.</p><div class="stack"><button class="primary m-button" id="resend-signup-email" type="button">Reenviar e-mail</button><a class="secondary m-button" href="#login">Voltar ao login</a><a class="text-link" href="#reenviar">Usar outro e-mail</a></div><p class="feedback" id="email-feedback" role="status" aria-live="polite"></p><p class="feedback" id="email-service-status" role="status" aria-live="polite" hidden></p><p class="field-hint">Confirmar o e-mail não verifica identidade, idade ou vínculo acadêmico.</p>',
        verificando:
          title(
            "Confirmando seu e-mail…",
            "Aguarde enquanto validamos o link.",
          ) + UI.loading("Confirmando o e-mail…"),
        verificado:
          '<div class="state-mark" aria-hidden="true">' +
          UI.icon("check") +
          "</div>" +
          title("E-mail confirmado.", "Agora você pode entrar na sua conta.") +
          '<a class="primary m-button" href="#login">Entrar</a>',
        "verificacao-expirada":
          title(
            "O link expirou.",
            "Por segurança, o link de confirmação tem prazo de validade. Solicite outro para continuar.",
          ) +
          '<a class="primary m-button" href="#reenviar">Solicitar novo link</a>' +
          back,
        "verificacao-utilizada":
          title(
            "Este link já foi utilizado.",
            "Seu e-mail provavelmente já foi confirmado. Tente entrar na sua conta.",
          ) +
          '<a class="primary m-button" href="#login">Ir para o login</a><a class="text-link" href="#reenviar">Solicitar outro link</a>',
        "verificacao-substituida":
          title(
            "Use o e-mail mais recente.",
            "Você solicitou outro link. Abra a última mensagem de confirmação recebida.",
          ) +
          '<a class="primary m-button" href="#login">Voltar ao login</a><a class="text-link" href="#reenviar">Solicitar outro link</a>',
        "verificacao-erro":
          title(
            "Não foi possível conectar.",
            "Sua confirmação não foi concluída nesta tentativa. Confira sua conexão e tente novamente.",
          ) +
          '<button type="button" class="primary m-button" id="retry-verification">Tentar novamente</button><a class="text-link" href="#reenviar">Solicitar outro link</a>',
        reenviar:
          title(
            "Receba outro link.",
            "Informe o e-mail do cadastro. Se a conta precisar de confirmação, enviaremos as instruções.",
          ) +
          '<form id="resend-form" novalidate>' +
          emailField +
          feedback +
          '<button class="primary m-button" type="submit">Solicitar novo link</button></form>' +
          back,
        ajuda:
          title(
            "Ajuda para acessar.",
            "Não recebeu a mensagem de confirmação?",
          ) +
          '<p class="state-note">Confira Spam e Lixo eletrônico, veja se digitou o endereço corretamente e aguarde alguns instantes. Um novo envio substitui o link anterior.</p><div class="stack"><a class="primary m-button" href="#reenviar">Solicitar novo link</a><a class="secondary m-button" href="#recuperar">Recuperar senha</a><a class="text-link" href="#login">Voltar ao login</a></div>',
        privacidade:
          title(
            "Privacidade no acesso.",
            "Como a confirmação do seu e-mail funciona.",
          ) +
          '<p class="state-note">O UniCarona usa o endereço informado para enviar links de confirmação e recuperação. Esses links são pessoais: não os compartilhe. A confirmação comprova acesso ao e-mail, sem verificar documentos ou matrícula.</p><p class="state-note">O envio utiliza um provedor de e-mail. Senhas e chaves privadas não são enviadas no corpo da mensagem. O acesso às funções da conta exige autenticação.</p><a class="primary m-button" href="legal.html?document=privacy">Ler a Política de Privacidade</a><a class="text-link" href="#login">Voltar ao login</a>',
        "alterar-email":
          title(
            "Confirme seu novo e-mail.",
            "O link comprova acesso ao novo endereço. A confirmação exige a senha da conta que pediu a troca e encerra suas sessões.",
          ) +
          '<div id="identity-confirm-state"><p role="status">Conferindo acesso à conta…</p></div>',
        "email-alterado":
          title(
            "E-mail atualizado.",
            "As sessões anteriores foram encerradas. Use o novo endereço para entrar.",
          ) +
          '<a class="primary m-button" href="#login">Entrar com o novo e-mail</a>',
        "verificacao-invalida":
          title(
            "Não foi possível confirmar.",
            "O link pode ter expirado ou já ter sido usado.",
          ) +
          '<form id="resend-form" novalidate>' +
          emailField +
          feedback +
          '<button class="primary m-button" type="submit">Solicitar outro link</button></form>' +
          back,
      };

      if (!states[authState.activeView]) {
        location.replace("#login");
        return;
      }
      // A reloaded confirmation screen has no signup draft. Let the student
      // request another link instead of showing a blank address and broken resend.
      if (
        authState.activeView === "cadastro-confirmar-email" &&
        !authState.signupData.email
      ) {
        states[authState.activeView] =
          back +
          title(
            "Confirme seu e-mail.",
            "Informe o e-mail do cadastro para receber outro link de confirmação.",
          ) +
          '<form id="resend-form" novalidate>' +
          emailField +
          feedback +
          '<button class="primary m-button" type="submit">Solicitar outro link</button></form>';
      }
      host.removeAttribute("aria-busy");
      host.inert = false;
      host.innerHTML = states[authState.activeView]; // Templates are static; user values never enter HTML.
      if (authState.activeView === "cadastro") {
        host
          .querySelector('a[href="index.html#regras"]')
          ?.closest("p")
          ?.remove();
        const label = host.querySelector("#signup-terms").closest("label");
        label.querySelector("span").textContent =
          "Li e aceito os Termos de Uso e as regras de participação, pagamentos e segurança.";
        label.insertAdjacentHTML(
          "beforebegin",
          '<div id="signup-legal" aria-live="polite"></div>',
        );
        host
          .querySelector("#signup-terms-error")
          .insertAdjacentHTML(
            "afterend",
            '<label class="check-field"><input type="checkbox" id="signup-privacy" aria-describedby="signup-privacy-error"><span>Li a Política de Privacidade, Retenção e Cookies.</span></label><p id="signup-privacy-error" class="field-error"></p>',
          );
        host.querySelector("#signup-privacy").checked =
          !!authState.signupAccessDraft.privacyNotice;
        loadSignupDocuments();
      }
      if (authState.activeView === "alterar-email") loadIdentityConfirmation();
      document.title = `${host.querySelector("h1").textContent.replace(/\.$/, "")} | UniCarona`;
      const heading = host.querySelector("h1");
      let signupScrollTarget = null;
      if (shouldScrollToSignupStep) {
        const focusTarget =
          authState.activeView === "cadastro-perfil"
            ? host.querySelector("#signup-course")
            : heading;
        signupScrollTarget =
          authState.activeView === "cadastro-perfil"
            ? focusTarget.closest(".field")
            : heading;
        authState.pendingSignupScrollView = "";
        focusTarget.focus({ preventScroll: true });
      } else {
        heading.focus({ preventScroll: true });
      }

      // Pre-fill preserved signup data on back navigation
      if (authState.activeView === "cadastro") {
        if (authState.signupData.name) {
          const n = host.querySelector("#signup-name");
          if (n) n.value = authState.signupData.name;
        }
        if (authState.signupData.email) {
          const e = host.querySelector("#signup-email");
          if (e) e.value = authState.signupData.email;
        }
      }
      if (authState.activeView === "cadastro") {
        const birth = host.querySelector("#signup-birth");
        if (birth && authState.signupData.birth)
          birth.value = authState.signupData.birth;
        host.querySelector("#signup-password").value =
          authState.signupAccessDraft.password;
        host.querySelector("#signup-confirm").value =
          authState.signupAccessDraft.confirmation;
        host.querySelector("#signup-truth").checked =
          authState.signupAccessDraft.truthDeclared;
        host.querySelector("#signup-terms").checked =
          authState.signupAccessDraft.rulesAccepted;
      }
      if (authState.activeView === "cadastro-perfil") {
        const courseInput = host.querySelector("#signup-course");
        const regionInput = host.querySelector("#signup-neighborhood");
        const draft = authState.signupProfileDraft || {
          courseId: authState.signupData.courseId,
          courseQuery: authState.signupData.course,
          regionId: authState.signupData.regionId,
          regionQuery: authState.signupData.region,
          academicDraft: authState.signupData.academicPlacement,
          phone: authState.signupData.phone,
          photo: authState.signupData.photo,
        };
        let academicCourseId = draft.courseId;
        const changeAcademicCourse = (courseId) => {
          if (courseId === academicCourseId) return;
          academicCourseId = courseId;
          restoreAcademicFields(courseId);
        };
        const courseCombobox = bindCatalogCombobox(
          courseInput,
          host.querySelector("#signup-course-options"),
          host.querySelector("#signup-course-status"),
          authState.selectionData.courses,
          (course) => changeAcademicCourse(course.id),
          () => changeAcademicCourse(""),
          host.querySelector("#signup-course-selected"),
        );
        const regionCombobox = bindCatalogCombobox(
          regionInput,
          host.querySelector("#signup-neighborhood-options"),
          host.querySelector("#signup-neighborhood-status"),
          authState.selectionData.regions,
          () => {},
          undefined,
          host.querySelector("#signup-neighborhood-selected"),
        );
        courseCombobox.setSelectedId(draft.courseId);
        if (!draft.courseId) courseInput.value = draft.courseQuery || "";
        regionCombobox.setSelectedId(draft.regionId);
        if (!draft.regionId) regionInput.value = draft.regionQuery || "";
        host.querySelector("#signup-phone").value = draft.phone || "";
        restoreAcademicFields(draft.courseId, draft.academicDraft || {});
        host
          .querySelector("#signup-period-status")
          .addEventListener("change", updatePeriodNumberField);
        if (draft.photo) {
          try {
            const transfer = new DataTransfer();
            transfer.items.add(draft.photo);
            host.querySelector("#signup-photo").files = transfer.files;
            host.querySelector("#signup-photo-name").textContent =
              `Foto selecionada: ${draft.photo.name}`;
          } catch {
            // Keep the rest of the in-memory draft usable if file restoration is unavailable.
          }
        }
      }
      if (authState.activeView === "cadastro-confirmar-email") {
        const address = host.querySelector("#signup-email-address");
        if (address)
          address.textContent = maskedEmail(authState.signupData.email);
        updateResendCountdown();
        checkEmailService();
      }

      // Keep error state synchronized with text inputs and dependent selects.
      host.querySelectorAll("input,select").forEach((field) => {
        field.addEventListener("input", () => clearFieldFeedback(field));
        field.addEventListener("change", () => clearFieldFeedback(field));
      });

      if (signupScrollTarget) {
        const reduceMotion = window.matchMedia(
          "(prefers-reduced-motion: reduce)",
        ).matches;
        signupScrollTarget.scrollIntoView({
          block: "start",
          behavior: reduceMotion ? "auto" : "smooth",
        });
      }
    }
    return { signupProfileForm, render };
  }
  window.UniCaronaFeatures ||= {};
  window.UniCaronaFeatures.authViews = Object.freeze({ create });
})();
