// Authentication uses the API and HttpOnly session cookies.
"use strict";

const authState = {};
const api = window.UniCaronaAPI;
const UI = window.UniCaronaUI;
const legal = window.UniCaronaLegal;
const host = document.querySelector("#auth-content");
function dashboardDestination() {
  const next = new URLSearchParams(location.search).get("next");
  if (next) {
    try {
      const url = new URL(next, location.origin);
      if (url.origin === location.origin && url.pathname === "/dashboard.html")
        return url.pathname + url.search;
    } catch {}
  }
  return "dashboard.html";
}
authState.revision = 0;
authState.resetToken = "";
authState.identityChangeToken = "";
authState.signupLegalSnapshot = null;
authState.verificationRetryToken = "";
authState.emailVerificationSucceeded = false;
authState.emailNoticeMode = "resend";
authState.resendTimer = null;
authState.emailServiceTimers = [];
function checkEmailService() {
  authState.emailServiceTimers.forEach(clearTimeout);
  authState.emailServiceTimers = [];
  const current = authState.revision;
  const check = async () => {
    try {
      const status = await api.request("/auth/email-service-status");
      const node =
        current === authState.revision &&
        host.querySelector("#email-service-status");
      if (node) {
        node.textContent = status.message;
        node.hidden = !status.delayed;
      }
    } catch {
      // This is a general service signal, not a delivery/existence query for this address.
      const node =
        current === authState.revision &&
        host.querySelector("#email-service-status");
      if (node) {
        node.hidden = false;
        node.textContent =
          "Não foi possível consultar o serviço de envio agora. Confira sua conexão. Se a mensagem não chegar, solicite outro link após o prazo indicado.";
      }
    }
  };
  [0, 5000, 15000, 30000].forEach((delay) =>
    authState.emailServiceTimers.push(setTimeout(check, delay)),
  );
}
authState.resendAfter = 0;
try {
  authState.resendAfter =
    Number(sessionStorage.getItem("uc_email_resend_after")) || 0;
} catch {}
function emailCooldown(seconds = 60) {
  authState.resendAfter =
    Date.now() + Math.max(1, Math.min(3600, Number(seconds) || 60)) * 1000;
  try {
    sessionStorage.setItem(
      "uc_email_resend_after",
      String(authState.resendAfter),
    );
  } catch {}
}
function updateResendCountdown() {
  clearInterval(authState.resendTimer);
  const button = host.querySelector("#resend-signup-email");
  if (!button) return;
  const tick = () => {
    if (!button.isConnected) {
      clearInterval(authState.resendTimer);
      return;
    }
    const remaining = Math.max(
      0,
      Math.ceil((authState.resendAfter - Date.now()) / 1000),
    );
    button.disabled = remaining > 0;
    button.textContent = remaining
      ? `Reenviar em ${remaining}s`
      : "Reenviar e-mail";
    if (!remaining) clearInterval(authState.resendTimer);
  };
  authState.resendTimer = setInterval(tick, 1000);
  tick();
}
function maskedEmail(email) {
  const [local, domain] = email.split("@");
  return domain
    ? `${local.slice(0, Math.min(2, local.length))}***@${domain}`
    : "o e-mail informado";
}
authState.activeView = "";
authState.pendingSignupScrollView = "";
authState.signupProfileDraft = null;
authState.signupAccessDraft = {
  password: "",
  confirmation: "",
  truthDeclared: false,
  rulesAccepted: false,
  privacyNotice: false,
  documents: [],
};
authState.signupData = {
  name: "",
  email: "",
  birth: "",
  courseId: "",
  course: "",
  academicPlacement: null,
  regionId: "",
  region: "",
  phone: "",
  photo: null,
  photoName: "",
  emailConfirmed: false,
};

// --- Template helpers ---
const passwordField = (id, label, autocomplete) => {
  const showHint =
    autocomplete === "new-password" &&
    (label === "Senha" || label === "Nova senha");
  return `<div class="field m-field"><label class="m-field-caption" for="${id}">${label}</label><div class="input-wrap"><input id="${id}" class="password-input" type="password" autocomplete="${autocomplete}" required maxlength="128" aria-describedby="${id}-error${showHint ? ` ${id}-hint` : ""}"><button type="button" class="visibility" data-reveal="${id}" aria-label="Mostrar ${label.toLowerCase()}" aria-pressed="false">Mostrar</button></div><p id="${id}-error" class="field-error"></p>${showHint ? `<p class="field-hint" id="${id}-hint">Use de 12 a 128 caracteres. Você pode usar uma frase.</p>` : ""}</div>`;
};
const emailField = `<div class="field m-field"><label class="m-field-caption" for="email">E-mail</label><input id="email" type="email" autocomplete="username" inputmode="email" autocapitalize="none" spellcheck="false" placeholder="voce@exemplo.com" required maxlength="254" aria-describedby="email-error"><p id="email-error" class="field-error"></p></div>`;
const feedback =
  '<div id="feedback" class="feedback" role="alert" tabindex="-1"></div>';
const back =
  '<a class="back-link" href="#login">' +
  UI.icon("arrowLeft") +
  "Voltar para entrar</a>";
const title = (heading, intro) =>
  `<h1 id="auth-title" tabindex="-1">${heading}</h1><p class="intro">${intro}</p>`;

// --- Signup-specific templates ---
const signupNameField = `<div class="field m-field"><label class="m-field-caption" for="signup-name">Nome completo</label><input id="signup-name" type="text" autocomplete="name" placeholder="Seu nome completo" required maxlength="100" aria-describedby="signup-name-error"><p id="signup-name-error" class="field-error"></p></div>`;
const signupEmailField = `<div class="field m-field"><label class="m-field-caption" for="signup-email">E-mail</label><input id="signup-email" type="email" autocomplete="email" inputmode="email" autocapitalize="none" spellcheck="false" placeholder="voce@exemplo.com" required maxlength="254" aria-describedby="signup-email-error"><p id="signup-email-error" class="field-error"></p></div>`;

const stepBar = (current, total) =>
  `<div class="step-bar" aria-label="Passo ${current} de ${total}"><div class="step-bar-track"><div class="step-bar-fill" style="width:${Math.round((current / total) * 100)}%"></div></div><span class="step-label">Passo ${current} de ${total}</span></div>`;

authState.selectionData = { courses: [], regions: [] };
authState.catalogPromise = undefined;
authState.catalogReady = false;

function navigate(view) {
  if (location.hash === `#${view}`) render();
  else location.hash = view;
}

const {
  loadCatalogs,
  normalizeSearch,
  clearFieldFeedback,
  bindCatalogCombobox,
  readAcademicDraft,
  updatePeriodNumberField,
  restoreAcademicFields,
  validateAcademicPlacement,
  saveSignupProfileDraft,
} = window.UniCaronaFeatures.authCatalog.create({
  state: authState,
  api,
  error,
  host,
});
const { loadSignupDocuments, loadIdentityConfirmation } =
  window.UniCaronaFeatures.authDocuments.create({
    state: authState,
    UI,
    api,
    feedback,
    host,
    legal,
    passwordField,
  });
const { signupProfileForm, render } = window.UniCaronaFeatures.authViews.create(
  {
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
  },
);
host.addEventListener("click", (event) => {
  if (event.target.closest("[data-catalog-retry]")) render();
});

function advanceSignupStep(view) {
  if (authState.pendingSignupScrollView) return;
  if (!host.querySelector(".step-bar")) {
    navigate(view);
    return;
  }

  authState.pendingSignupScrollView = view;
  host.inert = true;
  host.setAttribute("aria-busy", "true");
  navigate(view);
}

function error(id, message) {
  const input = document.querySelector(`#${id}`);
  if (!input) return false;
  input.setAttribute("aria-invalid", "true");
  const errEl = document.querySelector(`#${id}-error`);
  if (errEl) errEl.textContent = message;
  input.focus();
  const controlledList = input.getAttribute("aria-controls");
  if (controlledList) {
    const listbox = document.getElementById(controlledList);
    if (listbox) listbox.hidden = true;
    input.setAttribute("aria-expanded", "false");
    input.removeAttribute("aria-activedescendant");
  }
  return false;
}

function validEmail() {
  const email = document.querySelector("#email");
  email.value = email.value.trim();
  if (!email.value) return error("email", "Informe seu e-mail.");
  if (!email.validity.valid)
    return error("email", "Informe um e-mail válido, como voce@exemplo.com.");
  return true;
}

async function busy(form, label, next) {
  const current = authState.revision;
  const button = form.querySelector("[type=submit]");
  if (button.disabled) return;
  const original = button.textContent;
  const controls = [
    ...form.querySelectorAll("input,select,button[type=button]"),
  ];
  const previousDisabledStates = controls.map((el) => [el, el.disabled]);
  button.disabled = true;
  button.textContent = label;
  form.setAttribute("aria-busy", "true");
  controls.forEach((el) => (el.disabled = true));
  const stillCurrent = () => current === authState.revision && form.isConnected;
  try {
    await next(stillCurrent);
  } catch (failure) {
    if (current !== authState.revision || !form.isConnected) return;
    const msg = form.querySelector("#feedback");
    if (form.id === "signup-step2" && /^LEGAL_/.test(failure.code || "")) {
      authState.signupLegalSnapshot = null;
      authState.signupAccessDraft.rulesAccepted = false;
      authState.signupAccessDraft.privacyNotice = false;
      authState.signupAccessDraft.documents = [];
      msg.textContent =
        failure.message +
        " Volte à primeira etapa para ler e confirmar os documentos.";
    } else msg.textContent = failure.message;
    msg.focus();
  } finally {
    if (form.isConnected) {
      button.disabled = false;
      button.textContent = original;
      form.removeAttribute("aria-busy");
      previousDisabledStates.forEach(
        ([el, wasDisabled]) => (el.disabled = wasDisabled),
      );
    }
  }
}

async function verifyEmail(token) {
  authState.emailVerificationSucceeded = false;
  authState.verificationRetryToken = token;
  render();
  const current = authState.revision;
  try {
    await api.request("/auth/verify-email", {
      method: "POST",
      body: { token },
    });
    if (current === authState.revision) {
      authState.emailVerificationSucceeded = true;
      authState.verificationRetryToken = "";
      navigate("verificado");
    }
  } catch (failure) {
    if (current !== authState.revision) return;
    const states = {
      TOKEN_EXPIRED: "verificacao-expirada",
      TOKEN_USED: "verificacao-utilizada",
      TOKEN_REPLACED: "verificacao-substituida",
    };
    const recoverable =
      failure.status >= 500 || failure.status === 0 || failure.status === 429;
    if (!recoverable) authState.verificationRetryToken = "";
    navigate(
      states[failure.code] ||
        (recoverable ? "verificacao-erro" : "verificacao-invalida"),
    );
  }
}

function calcAge(isoDate) {
  const birth = new Date(isoDate + "T00:00:00");
  const today = new Date();
  return (
    today.getFullYear() -
    birth.getFullYear() -
    (today < new Date(today.getFullYear(), birth.getMonth(), birth.getDate())
      ? 1
      : 0)
  );
}

// --- Form submission ---
host.addEventListener("submit", (event) => {
  event.preventDefault();
  const form = event.target;

  if (form.id === "login-form") {
    if (!validEmail()) return;
    const password = document.querySelector("#password");
    if (!password.value) {
      error("password", "Informe sua senha.");
      return;
    }
    const credentials = {
      email: document.querySelector("#email").value,
      password: password.value,
    };
    busy(form, "Entrando…", async (stillCurrent) => {
      try {
        await api.request("/auth/login", { method: "POST", body: credentials });
        if (stillCurrent()) {
          if (authState.identityChangeToken) navigate("alterar-email");
          else location.assign(dashboardDestination());
        }
      } catch (failure) {
        if (failure.code === "EMAIL_UNVERIFIED" && stillCurrent()) {
          authState.signupData.email = credentials.email.trim().toLowerCase();
          authState.emailNoticeMode = "login";
          authState.emailVerificationSucceeded = false;
          navigate("cadastro-confirmar-email");
        } else throw failure;
      } finally {
        password.value = "";
        credentials.password = "";
      }
    });
  } else if (form.id === "identity-email-confirm") {
    const password = document.querySelector("#identity-password");
    if (!authState.identityChangeToken || !password.value) {
      error(
        "identity-password",
        "Abra o link recebido e informe sua senha para confirmar.",
      );
      return;
    }
    const entered = password.value,
      token = authState.identityChangeToken;
    password.value = "";
    busy(form, "Confirmando…", async (stillCurrent) => {
      await api.request("/privacy/identity/email/confirm", {
        method: "POST",
        body: { password: entered, token },
      });
      if (authState.identityChangeToken === token)
        authState.identityChangeToken = "";
      if (stillCurrent()) navigate("email-alterado");
    });
  } else if (form.id === "recovery-form") {
    if (validEmail())
      busy(form, "Enviando…", async (stillCurrent) => {
        await api.request("/auth/forgot-password", {
          method: "POST",
          body: { email: document.querySelector("#email").value },
        });
        if (stillCurrent()) navigate("instrucoes");
      });
  } else if (form.id === "resend-form") {
    if (validEmail())
      busy(form, "Enviando…", async (stillCurrent) => {
        authState.signupData.email = document.querySelector("#email").value;
        authState.emailNoticeMode = "resend";
        authState.emailVerificationSucceeded = false;
        try {
          const result = await api.request("/auth/resend-verification", {
            method: "POST",
            body: { email: authState.signupData.email },
          });
          emailCooldown(result.retryAfterSeconds);
          if (stillCurrent()) navigate("cadastro-confirmar-email");
        } catch (failure) {
          if (failure.code === "EMAIL_COOLDOWN" && stillCurrent()) {
            emailCooldown(failure.details.retryAfterSeconds);
            navigate("cadastro-confirmar-email");
          } else throw failure;
        }
      });
  } else if (form.id === "reset-form") {
    if (!authState.resetToken) {
      navigate("link-expirado");
      return;
    }
    const password = document.querySelector("#password");
    if (password.value.length < 12) {
      error("password", "Use pelo menos 12 caracteres.");
      return;
    }
    if (password.value !== document.querySelector("#confirm").value) {
      error("confirm", "As senhas não são iguais.");
      return;
    }
    busy(form, "Salvando…", async (stillCurrent) => {
      await api.request("/auth/reset-password", {
        method: "POST",
        body: { token: authState.resetToken, password: password.value },
      });
      if (!stillCurrent()) return;
      authState.resetToken = "";
      form.reset();
      navigate("senha-alterada");
    });
  } else if (form.id === "signup-step1") {
    // Step 1: access details, age declaration, and rules
    const name = document.querySelector("#signup-name");
    const email = document.querySelector("#signup-email");
    const pass = document.querySelector("#signup-password");
    const confirm = document.querySelector("#signup-confirm");
    const birth = document.querySelector("#signup-birth");
    name.value = name.value.trim();
    if (!name.value) {
      error("signup-name", "Informe seu nome completo.");
      return;
    }
    if (name.value.split(/\s+/).filter(Boolean).length < 2) {
      error("signup-name", "Informe nome e sobrenome.");
      return;
    }
    email.value = email.value.trim();
    if (!email.value) {
      error("signup-email", "Informe seu e-mail.");
      return;
    }
    if (!email.validity.valid) {
      error("signup-email", "Informe um e-mail válido, como voce@exemplo.com.");
      return;
    }
    if (pass.value.length < 12) {
      error("signup-password", "Use pelo menos 12 caracteres.");
      return;
    }
    if (pass.value !== confirm.value) {
      error("signup-confirm", "As senhas não são iguais.");
      return;
    }
    if (!birth.value) {
      error("signup-birth", "Informe sua data de nascimento.");
      return;
    }
    const age = calcAge(birth.value);
    if (isNaN(age) || age > 120 || new Date(birth.value) > new Date()) {
      error("signup-birth", "Informe uma data de nascimento válida.");
      return;
    }
    if (age < 18) {
      error("signup-birth", "O cadastro é permitido a partir dos 18 anos.");
      return;
    }
    if (!document.querySelector("#signup-truth").checked) {
      document.querySelector("#signup-truth-error").textContent =
        "Confirme que as informações são verdadeiras.";
      document.querySelector("#signup-truth").focus();
      return;
    }
    if (!document.querySelector("#signup-terms").checked) {
      document.querySelector("#signup-terms-error").textContent =
        "Você precisa aceitar as regras para continuar.";
      document.querySelector("#signup-terms").focus();
      return;
    }
    if (
      !authState.signupLegalSnapshot ||
      !document.querySelector("#signup-privacy").checked
    ) {
      document.querySelector("#signup-privacy-error").textContent =
        authState.signupLegalSnapshot
          ? "Leia a Política de Privacidade e confirme a leitura."
          : "Aguarde ou carregue os documentos para continuar.";
      document.querySelector("#signup-privacy").focus();
      return;
    }
    authState.signupData.name = name.value;
    authState.signupData.email = email.value;
    authState.signupData.birth = birth.value;
    authState.signupAccessDraft = {
      password: pass.value,
      confirmation: confirm.value,
      truthDeclared: document.querySelector("#signup-truth").checked,
      rulesAccepted: document.querySelector("#signup-terms").checked,
      privacyNotice: document.querySelector("#signup-privacy").checked,
      documents: authState.signupLegalSnapshot.map((r) => ({ ...r })),
    };
    busy(form, "Carregando cursos…", async (stillCurrent) => {
      await loadCatalogs();
      if (stillCurrent()) advanceSignupStep("cadastro-perfil");
    });
  } else if (form.id === "signup-step2") {
    const courseInput = document.querySelector("#signup-course");
    const course = authState.selectionData.courses.find(
      (item) =>
        item.id === courseInput.dataset.selectedId &&
        item.label === courseInput.value,
    );
    if (!course) {
      error("signup-course", "Escolha um curso válido da lista.");
      return;
    }

    const regionInput = document.querySelector("#signup-neighborhood");
    const region = authState.selectionData.regions.find(
      (item) =>
        item.id === regionInput.dataset.selectedId &&
        item.label === regionInput.value,
    );
    if (!region) {
      error(
        "signup-neighborhood",
        "Escolha uma região ou cidade válida da lista.",
      );
      return;
    }

    const academic = validateAcademicPlacement();
    if (!academic.valid) return;

    const phoneInput = document.querySelector("#signup-phone");
    phoneInput.value = phoneInput.value.trim();
    const phone = phoneInput.value.replace(/\D/g, "");
    if (phoneInput.value && (phone.length < 10 || phone.length > 15)) {
      error("signup-phone", "Informe um telefone com DDD válido.");
      return;
    }
    authState.signupData.courseId = course.id;
    authState.signupData.course = course.label;
    authState.signupData.academicPlacement = academic.placement;
    authState.signupData.regionId = region.id;
    authState.signupData.region = region.label;
    authState.signupData.phone = phoneInput.value;
    authState.signupData.emailConfirmed = false;
    busy(form, "Criando conta…", async (stillCurrent) => {
      if (!authState.signupAccessDraft.password)
        throw Error("Volte à primeira etapa para informar sua senha.");
      const registration = await api.request("/auth/register", {
        method: "POST",
        body: {
          name: authState.signupData.name,
          email: authState.signupData.email,
          password: authState.signupAccessDraft.password,
          birth: authState.signupData.birth,
          phone: authState.signupData.phone,
          truth: authState.signupAccessDraft.truthDeclared,
          terms: authState.signupAccessDraft.rulesAccepted,
          legal: {
            privacyNotice: authState.signupAccessDraft.privacyNotice,
            documents: authState.signupAccessDraft.documents,
          },
          academic: {
            universityId: authState.selectionData.universityId,
            courseId: course.id,
            regionId: region.id,
            region: region.label,
            periodStatus: academic.placement.periodStatus,
            periodNumber: academic.placement.periodNumber,
            shift: academic.placement.shift,
            classLabel: academic.placement.classLabel || null,
          },
        },
      });
      emailCooldown(registration.retryAfterSeconds);
      authState.emailNoticeMode = "registration";
      authState.emailVerificationSucceeded = false;
      if (!stillCurrent()) return;
      authState.signupAccessDraft = {
        password: "",
        confirmation: "",
        truthDeclared: false,
        rulesAccepted: false,
        privacyNotice: false,
        documents: [],
      };
      advanceSignupStep("cadastro-confirmar-email");
    });
  }
});

// --- Click delegation ---
host.addEventListener("click", (event) => {
  if (event.target.closest("#signup-legal-retry")) {
    loadSignupDocuments();
    return;
  }
  if (event.target.closest("#identity-confirm-retry")) {
    loadIdentityConfirmation();
    return;
  }
  if (event.target.closest("#retry-verification")) {
    if (!authState.verificationRetryToken) {
      navigate("reenviar");
      return;
    }
    history.replaceState(null, "", "#verificando");
    verifyEmail(authState.verificationRetryToken);
    return;
  }
  // Password visibility toggle
  const toggle = event.target.closest("[data-reveal]");
  if (toggle) {
    const input = document.querySelector(`#${toggle.dataset.reveal}`);
    const showing = input.type === "password";
    input.type = showing ? "text" : "password";
    toggle.textContent = showing ? "Ocultar" : "Mostrar";
    toggle.setAttribute("aria-pressed", String(showing));
    const lbl = document.querySelector(`label[for=${input.id}]`);
    if (lbl)
      toggle.setAttribute(
        "aria-label",
        `${showing ? "Ocultar" : "Mostrar"} ${lbl.textContent.toLowerCase()}`,
      );
  }

  if (event.target.closest("#resend-signup-email")) {
    const button = event.target.closest("button");
    const feedback = document.querySelector("#email-feedback");
    if (button.disabled) return;
    button.disabled = true;
    api
      .request("/auth/resend-verification", {
        method: "POST",
        body: { email: authState.signupData.email },
      })
      .then((result) => {
        emailCooldown(result.retryAfterSeconds);
        checkEmailService();
        feedback.textContent = result.message;
      })
      .catch((error) => {
        if (error.code === "EMAIL_COOLDOWN")
          emailCooldown(error.details.retryAfterSeconds);
        feedback.textContent = error.message;
      })
      .finally(() => {
        updateResendCountdown();
      });
  }

  // Terms error clear on label click
  const termsErr = document.querySelector("#signup-terms-error");
  if (termsErr && event.target.closest(".check-field"))
    termsErr.textContent = "";
  const truthErr = document.querySelector("#signup-truth-error");
  if (truthErr && event.target.closest(".check-field"))
    truthErr.textContent = "";
});

host.addEventListener("change", (event) => {
  if (event.target.id === "signup-photo") {
    const photo = event.target.files[0];
    const message = document.querySelector("#signup-photo-name");
    if (message)
      message.textContent = photo ? `Foto selecionada: ${photo.name}` : "";
  }
});

window.addEventListener("hashchange", render);
window.addEventListener("pageshow", (event) => {
  if (!event.persisted) return;
  authState.pendingSignupScrollView = "";
  if (
    ["#cadastro-perfil", "#cadastro-confirmar-email"].includes(location.hash)
  ) {
    location.hash = "cadastro";
    return;
  }
  render();
});
window.addEventListener("pagehide", () => {
  authState.revision++;
  host.querySelectorAll("form").forEach((form) => form.reset());
  host
    .querySelectorAll("[data-selected-id]")
    .forEach((field) => field.removeAttribute("data-selected-id"));
  host
    .querySelectorAll("input,select")
    .forEach((field) => field.removeAttribute("aria-invalid"));
  host.querySelectorAll('[role="combobox"]').forEach((input) => {
    input.setAttribute("aria-expanded", "false");
    input.removeAttribute("aria-activedescendant");
  });
  host.querySelectorAll('[role="listbox"]').forEach((listbox) => {
    listbox.hidden = true;
    listbox.replaceChildren();
  });
  host
    .querySelectorAll(
      ".field-error,.feedback,.field-selection,.file-name,#signup-email-address,#email-feedback,#signup-course-status,#signup-neighborhood-status",
    )
    .forEach((element) => {
      element.textContent = "";
      if (element.matches(".field-selection")) element.hidden = true;
    });
  host
    .querySelectorAll(".status-dot")
    .forEach((status) => status.classList.remove("pending", "confirmed"));
  host.removeAttribute("aria-busy");
  host.inert = false;
  authState.pendingSignupScrollView = "";
  authState.signupProfileDraft = null;
  authState.signupAccessDraft = {
    password: "",
    confirmation: "",
    truthDeclared: false,
    rulesAccepted: false,
    privacyNotice: false,
    documents: [],
  };
  authState.signupData = {
    name: "",
    email: "",
    birth: "",
    courseId: "",
    course: "",
    academicPlacement: null,
    regionId: "",
    region: "",
    phone: "",
    photo: null,
    photoName: "",
    emailConfirmed: false,
  };
  authState.resetToken = "";
  authState.identityChangeToken = "";
  authState.signupLegalSnapshot = null;
});
render();
