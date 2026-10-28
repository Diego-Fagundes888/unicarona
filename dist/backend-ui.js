/* Connected journeys. Every mutation and financial state comes from the API. */
(() => {
  "use strict";
  const A = window.UniCaronaAPI;
  const UI = window.UniCaronaUI;
  const esc = UI.escape;
  const cash = UI.money;
  const photo = (source, name) =>
    /^\/api\/v1\/files\/[a-f0-9-]{36}$/.test(source || "")
      ? '<img class="m-person-photo" src="' +
        esc(source) +
        '" alt="Foto de ' +
        esc(name) +
        '" width="80" height="80">'
      : "";
  const dateLabel = UI.date;
  const today = () =>
    new Intl.DateTimeFormat("en-CA", {
      timeZone: "America/Sao_Paulo",
      year: "numeric",
      month: "2-digit",
      day: "2-digit",
    }).format(new Date());
  const shifts = [
    ["morning", "Manhã"],
    ["afternoon", "Tarde"],
    ["night", "Noite"],
    ["full-time", "O dia todo"],
    ["variable", "Horários variados"],
  ];
  const dayNames = ["Dom", "Seg", "Ter", "Qua", "Qui", "Sex", "Sáb"];
  const labels = {
    planned: "Programado",
    active: "Ativo",
    pending: "Aguardando",
    accepted: "Aceito · falta pagamento",
    confirmed: "Confirmada",
    approved: "Aprovado",
    rejected: "Rejeitado",
    replaced: "Substituído",
    failed: "Não aprovado",
    cancelled: "Cancelado",
    expired: "Vencido",
    boarded: "Embarque registrado",
    awaiting_confirmation: "Confirmação final pendente",
    completed: "Concluído",
    under_review: "Em análise",
    blocked: "Bloqueado",
    released: "Liberado",
    credited: "Crédito integral",
    retained: "Destinado ao app",
    future: "Futuro",
    awaiting: "Aguardando confirmação",
    cancelled_renewal: "Renovação desativada",
    grace: "Carência · falta pagamento",
    renew_conflict: "Agenda sem vaga completa",
    interrupted: "Interrompido por segurança",
    ended: "Encerrado",
    renewed: "Renovado",
    no_class: "Sem aula",
    not_requested: "Não solicitado",
    financial_pending: "Dinheiro em análise",
    partially_refunded: "Devolução parcial",
    resolved: "Análise concluída",
    waiting: "Interesse registrado",
    paused: "Novos planos pausados",
  };
  const badge = (status) =>
    '<span class="m-badge ' +
    (["active", "approved", "completed", "confirmed", "released"].includes(
      status,
    )
      ? "good"
      : ["cancelled", "failed", "blocked", "interrupted", "rejected"].includes(
            status,
          )
        ? "bad"
        : "warn") +
    '">' +
    esc(labels[status] || status) +
    "</span>";
  Object.assign(labels, {
    self_reported: "Informações declaradas",
    verified: "Vínculo validado",
    delinquent: "Pagamento em atraso",
    suspended: "Conta em revisão",
    safety: "Segurança",
    dispute: "Contestação",
    absence_review: "Revisão de faltas",
    no_financial_change: "Sem alteração financeira",
    notified: "Ausência avisada",
    notified_absence: "Ausência avisada e registrada",
    passenger_absent: "Ausência registrada pelo motorista",
    fulfilled: "Embarque e trecho registrados",
    fulfilled_absence: "Disponibilidade cumprida sem embarque",
  });
  const go = (label, route, ref = "", style = "") =>
    '<button type="button" class="m-button ' +
    style +
    '" data-c-go="' +
    esc(route) +
    '" data-ref="' +
    esc(ref) +
    '">' +
    esc(label) +
    "</button>";
  const action = (label, operation, data = {}, style = "", confirmation = "") =>
    '<button type="button" class="m-button ' +
    style +
    '" data-c-action="' +
    esc(operation) +
    '" data-input="' +
    esc(JSON.stringify(data)) +
    '"' +
    (confirmation ? ' data-confirm="' + esc(confirmation) + '"' : "") +
    ">" +
    esc(label) +
    "</button>";
  const field = UI.field;
  const select = UI.select;
  const check = UI.check;
  const area = UI.area;
  const form = UI.form;
  const notice = UI.notice;
  const driverDocumentsNotice = (application) => {
    if (!application) return "";
    if (application.documentsStatus === "available")
      return notice(
        (application.status === "pending"
          ? "Motorista e veículo aguardam análise. "
          : "") +
          "Consulta dos documentos até " +
          UI.dateTime(application.documentsExpiresAt) +
          ". Depois desse prazo, as cópias seguem as regras de retenção.",
      );
    if (application.status === "expired")
      return notice(
        "O prazo de 30 dias terminou antes da análise. Envie uma nova solicitação com os documentos para continuar.",
        "warning",
      );
    if (["expired", "retired"].includes(application.documentsStatus))
      return notice(
        "Consulta dos anexos encerrada. " +
          (application.status === "approved"
            ? "A aprovação registrada é mantida. "
            : "Para uma nova análise, envie outra solicitação. ") +
          "As cópias seguem eliminação; preservações específicas permanecem restritas ao caso.",
      );
    return notice(
      "Os anexos não estão disponíveis para análise. Confira o envio dos documentos ou procure o atendimento antes de registrar uma decisão.",
      "warning",
    );
  };
  const summary = UI.summary;
  const empty = (title, text) =>
    UI.empty(title, text, go("Ir para o início", "inicio"));
  const days = (
    selected = [1, 2, 3, 4, 5],
    available = [0, 1, 2, 3, 4, 5, 6],
  ) =>
    '<fieldset><legend>Dias da semana</legend><div class="m-days">' +
    available
      .map((d) => check("weekdays", dayNames[d], selected.includes(d), d))
      .join("") +
    "</div></fieldset>";
  const academic = (p) =>
    p.periodStatus === "protected"
      ? "Dados acadêmicos protegidos"
      : [
          p.course,
          p.periodStatus === "numbered"
            ? p.periodNumber + "º período"
            : "Período não definido",
          p.classLabel ? "Turma " + p.classLabel : "Turma não informada",
        ]
          .filter(Boolean)
          .join(" · ");
  const titles = {
    results: "Escolha uma carona",
    ride: "Detalhes da carona",
    monthly: "Mensal da sua turma",
    renew: "Renovar mensal",
    request: "Sua solicitação",
    checkout: "Pagamento",
    reservation: "Sua carona",
    contract: "Seu plano mensal",
    minhas: "Minhas caronas",
    driver: "Ofereça uma carona",
    offer: "Publicar carona",
    edit: "Editar oferta",
    passengers: "Gerenciar trecho",
    wallet: "Carteira",
    perfil: "Seu perfil",
    settings: "Configurações",
    safety: "Segurança e ocorrências",
    report: "Relatar uma ocorrência",
    notifications: "Notificações",
    admin: "Administração",
    help: "Como podemos ajudar?",
    history: "Histórico",
  };
  Object.assign(titles, window.UniCaronaPrivacy?.titles || {});
  Object.assign(titles, window.UniCaronaAdminPrivacy?.titles || {});

  function attach({ root, profile, home }) {
    const connectedState = {};

    const pageScopes = {
      results: "results",
      mine_requests: "minhas",
      mine_subscriptions: "minhas",
      mine_offers: "minhas",
      mine_reservations: "minhas",
      driver_offers: "driver",
      driver_routines: "driver",
      driver_requests: "driver",
      received_requests: "passengers",
      transfers: "wallet",
      notices: "notifications",
      reports: "safety",
      blocks: "safety",
      settings_blocks: "settings",
      admin_approvals: "admin",
      admin_cases: "admin",
      admin_academic: "admin",
      admin_jobs: "admin",
      admin_reviews: "admin",
    };
    const pageLabels = {
      results: "Resultados",
      mine_requests: "Pedidos",
      mine_subscriptions: "Mensais",
      mine_offers: "Ofertas",
      mine_reservations: "Reservas",
      driver_offers: "Trechos",
      driver_routines: "Rotinas",
      driver_requests: "Pedidos recebidos",
      received_requests: "Pedidos recebidos",
      transfers: "Transferências",
      notices: "Avisos",
      reports: "Relatos",
      blocks: "Bloqueios",
      settings_blocks: "Bloqueios",
      admin_approvals: "Cadastros de motorista",
      admin_cases: "Ocorrências",
      admin_academic: "Vínculos acadêmicos",
      admin_jobs: "Tarefas de envio",
      admin_reviews: "Revisões financeiras",
    };
    const initialURL = new URL(location.href),
      pageOffsets = {};
    for (const key of Object.keys(pageScopes)) {
      const value = initialURL.searchParams.get("page_" + key);
      if (/^\d{1,4}$/.test(value || "") && Number(value) <= 5000)
        pageOffsets[key] = Number(value);
    }
    const queryKeys = [
      "type",
      "region",
      "date",
      "direction",
      "time",
      "automatic",
      "onlyAvailable",
      "compatibleOnly",
      "maxPrice",
    ];
    function readQuery(url) {
      const p = url.searchParams,
        q = {
          type: p.get("type") === "mensal" ? "mensal" : "avulsa",
          region: (p.get("region") || profile.region || "").slice(0, 100),
          date: /^\d{4}-\d{2}-\d{2}$/.test(p.get("date") || "")
            ? p.get("date")
            : today(),
          direction: p.get("direction") === "volta" ? "volta" : "ida",
        };
      if (/^\d{2}:\d{2}$/.test(p.get("time") || "")) q.time = p.get("time");
      for (const key of ["automatic", "onlyAvailable", "compatibleOnly"])
        if (p.get(key) === "true") q[key] = true;
      if (/^\d{1,9}$/.test(p.get("maxPrice") || ""))
        q.maxPrice = Number(p.get("maxPrice"));
      return q;
    }
    ((connectedState.me = profile),
      (connectedState.route = "inicio"),
      (connectedState.ref = ""),
      (connectedState.query = readQuery(initialURL)),
      (connectedState.filter = [
        "reservations",
        "requests",
        "subscriptions",
        "offers",
      ].includes(initialURL.searchParams.get("list"))
        ? initialURL.searchParams.get("list")
        : "reservations"),
      (connectedState.preview = null),
      (connectedState.epoch = 0),
      (connectedState.toastTimer = undefined),
      (connectedState.pollTimer = undefined),
      (connectedState.paymentCleanup = undefined),
      (connectedState.disposed = false));
    ((connectedState.catalog = null),
      (connectedState.current = null),
      (connectedState.tripReviewChangedId = null));
    const navigationSession =
      typeof history.state?.ucSession === "string"
        ? history.state.ucSession
        : crypto.randomUUID();
    connectedState.navigationDepth =
      Number.isInteger(history.state?.ucDepth) && history.state.ucDepth >= 0
        ? history.state.ucDepth
        : 0;
    ((connectedState.adminUniversity = ""),
      (connectedState.calendarRange = {
        startDate: today(),
        endDate: today().slice(0, 4) + "-12-31",
      }),
      (connectedState.adminOffsets = { calendar: 0, pricing: 0 }));
    ((connectedState.accountFilter = { q: "", status: "" }),
      (connectedState.jobStatus = "dead"));
    const app = document.createElement("section");
    app.className = "m-app";
    app.hidden = true;
    root.querySelector(".d-footer").before(app);
    function centerAdminTab() {
      if (connectedState.route !== "admin") return;
      const selected = app.querySelector(
        '.m-admin-navigation [data-ref="' +
          (connectedState.ref.startsWith("case:")
            ? "cases"
            : connectedState.ref.startsWith("finance:")
              ? "finance"
              : connectedState.ref || "approvals") +
          '"]',
      );
      if (selected) selected.setAttribute("aria-current", "page");
    }
    const sizeObserver = new ResizeObserver(centerAdminTab);
    sizeObserver.observe(app);
    const filterBreakpoint = matchMedia("(max-width:700px)"),
      adminBreakpoint = matchMedia("(max-width:1000px)"),
      paymentBreakpoint = matchMedia("(max-width:800px)");
    function adaptPayment() {
      const details = app.querySelector("[data-payment-breakdown]");
      if (details) details.open = !paymentBreakpoint.matches;
    }
    paymentBreakpoint.addEventListener("change", adaptPayment);
    const adaptFilters = () =>
      app
        .querySelectorAll("[data-responsive-details]")
        .forEach((d) => (d.open = !filterBreakpoint.matches));
    filterBreakpoint.addEventListener("change", adaptFilters);
    const adaptAdmin = () =>
      app
        .querySelectorAll("[data-admin-navigation]")
        .forEach((d) => (d.open = !adminBreakpoint.matches));
    adminBreakpoint.addEventListener("change", adaptAdmin);
    const toolbar = document.createElement("div");
    toolbar.className = "m-demo-bar";
    toolbar.innerHTML =
      '<div class="m-campus-context"><span data-greeting>Olá, ' +
      esc(connectedState.me.name.split(/\s+/)[0]) +
      ".</span><span>" +
      esc(connectedState.me.university || "UniCarona") +
      '</span></div><button type="button" class="m-button quiet" popovertarget="uc-account-menu">Mais</button>';
    root.querySelector(".d-content").prepend(toolbar);
    const accountMenu = document.createElement("nav");
    accountMenu.id = "uc-account-menu";
    accountMenu.className = "m-account-menu";
    accountMenu.setAttribute("popover", "auto");
    accountMenu.setAttribute("aria-label", "Outras páginas da sua conta");
    accountMenu.innerHTML =
      go("Carteira", "wallet", "", "quiet") +
      go("Histórico", "history", "", "quiet") +
      go("Segurança", "safety", "", "quiet") +
      go("Configurações", "settings", "", "quiet") +
      go("Privacidade e dados", "privacy", "", "quiet") +
      go("Notificações", "notifications", "", "quiet") +
      go("Ajuda", "help", "", "quiet") +
      (connectedState.me.role === "admin"
        ? go("Administração", "admin", "", "quiet")
        : "");
    root.append(accountMenu);
    const restriction = document.createElement("div");
    restriction.className = "m-notice m-account-notice";
    restriction.hidden = connectedState.me.accountStatus === "active";
    function syncAccountState() {
      restriction.hidden = connectedState.me.accountStatus === "active";
      const text =
        {
          closing:
            "Seu encerramento está em andamento. Novas combinações foram interrompidas; viagens e valores existentes continuam sendo tratados.",
          deactivated:
            "Sua conta está desativada. Novas combinações estão pausadas e seus dados foram preservados.",
          banned:
            "Sua participação está restrita. Consulte as decisões da conta e solicite revisão quando necessário.",
          suspended:
            "Sua conta está em revisão. Você pode consultar seus registros, relatar ocorrências e exercer seus direitos de privacidade.",
        }[connectedState.me.accountStatus] ||
        "Consulte a situação da sua conta em Privacidade e dados.";
      restriction.innerHTML =
        "<p>" +
        esc(text) +
        '</p><div class="m-actions">' +
        go("Privacidade e conta", "privacy") +
        go("Segurança", "safety", "", "quiet") +
        "</div>";
    }
    syncAccountState();
    toolbar.after(restriction);
    const dialog = document.createElement("dialog");
    dialog.className = "m-dialog";
    root.append(dialog);
    connectedState.dialogReturnFocus = null;
    dialog.addEventListener("close", () => {
      const previous = connectedState.dialogReturnFocus;
      connectedState.dialogReturnFocus = null;
      if (previous?.isConnected && !previous.disabled) previous.focus();
    });
    function showDialog() {
      const title = dialog.querySelector("h2");
      if (title) {
        title.id = "uc-dialog-title";
        dialog.setAttribute("aria-labelledby", title.id);
      }
      if (!dialog.open) {
        connectedState.dialogReturnFocus ||= document.activeElement;
        dialog.showModal();
      }
    }
    const toast = document.createElement("div");
    toast.className = "m-toast";
    toast.hidden = true;
    toast.setAttribute("role", "status");
    root.append(toast);
    function tell(message) {
      toast.textContent = message;
      toast.hidden = false;
      clearTimeout(connectedState.toastTimer);
      connectedState.toastTimer = setTimeout(() => (toast.hidden = true), 4500);
    }
    function info(title, message) {
      dialog.innerHTML =
        "<h2>" +
        esc(title) +
        "</h2><p>" +
        esc(message) +
        '</p><button type="button" class="m-button" data-dialog-close>Entendi</button>';
      showDialog();
    }
    async function confirm(message) {
      if (!dialog.open)
        connectedState.dialogReturnFocus ||= document.activeElement;
      dialog.innerHTML =
        "<h2>Confirme a ação</h2><p>" +
        esc(message) +
        '</p><div class="m-actions"><button type="button" class="m-button" data-dialog-yes>Confirmar</button><button type="button" class="m-button quiet" data-dialog-close>Voltar</button></div>';
      showDialog();
      dialog.querySelector("[data-dialog-close]").focus();
      return new Promise((resolve) => {
        let accepted = false;
        dialog.querySelector("[data-dialog-yes]").onclick = () => {
          accepted = true;
          dialog.close();
        };
        dialog.addEventListener("close", () => resolve(accepted), {
          once: true,
        });
      });
    }
    async function request(path, options) {
      try {
        return await A.request(path, options);
      } catch (error) {
        if (error.code === "ACCOUNT_RESTRICTED") {
          try {
            const actual = await A.request("/auth/me");
            if (!connectedState.disposed) {
              connectedState.me = actual;
              syncAccountState();
            }
          } catch {}
        }
        throw error;
      }
    }
    function post(path, body = {}) {
      return request(path, { method: "POST", body });
    }
    const privacy = window.UniCaronaPrivacy?.create({
      request,
      navigate,
      tell,
      navigationVersion: () =>
        connectedState.disposed ? -1 : connectedState.epoch,
      refreshProfile: async () => {
        const actual = await request("/auth/me");
        if (!connectedState.disposed) {
          connectedState.me = actual;
          syncAccountState();
        }
      },
    });
    const adminPrivacy = window.UniCaronaAdminPrivacy?.create({
      request,
      navigate,
      tell,
      isAdmin: () =>
        !connectedState.disposed && connectedState.me.role === "admin",
      navigationVersion: () =>
        connectedState.disposed ? -1 : connectedState.epoch,
      reveal: (html) => {
        const target = app.querySelector("[data-a-private]");
        if (!target || connectedState.disposed) return;
        target.innerHTML = html;
        if (html) {
          const heading = target.querySelector("h2");
          if (heading) {
            heading.tabIndex = -1;
            heading.focus();
          }
        } else
          app
            .querySelector(
              '[data-c-form="aPrivacyOpenHold"] input[type="password"]',
            )
            ?.focus({ preventScroll: true });
      },
    });
    function listMarkup(
      items,
      render,
      emptyText = "Seus registros aparecerão aqui conforme você usar o UniCarona.",
    ) {
      return items.length
        ? '<div class="m-list">' + items.map(render).join("") + "</div>"
        : UI.empty("Nenhum registro", emptyText);
    }
    function pageQuery(key) {
      return "limit=30&offset=" + (pageOffsets[key] || 0);
    }
    function pages(data, key) {
      const offset = pageOffsets[key] || 0;
      return (
        '<nav class="m-actions" aria-label="' +
        esc(pageLabels[key]) +
        '">' +
        (offset
          ? action(
              "Página anterior",
              "listPage",
              { key, offset: Math.max(0, offset - 30) },
              "quiet",
            )
          : "") +
        (data.nextOffset !== null && data.nextOffset <= 5000
          ? action(
              "Próxima página",
              "listPage",
              { key, offset: data.nextOffset },
              "secondary",
            )
          : "") +
        "</nav>"
      );
    }
    function journeyLabel(r) {
      const campus = connectedState.me.university || "Faculdade";
      return r.direction === "volta"
        ? campus + " → " + r.region
        : r.region + " → " + campus;
    }
    function rideRow(r, own = false) {
      const campus = connectedState.me.university || "Faculdade",
        origin = r.direction === "ida" ? r.region : campus,
        destination = r.direction === "ida" ? campus : r.region;
      return (
        '<article class="m-row m-journey-row"><div><p class="m-journey-time">' +
        esc(r.time) +
        " <span>" +
        esc(dateLabel(r.date)) +
        "</span></p><h3>" +
        esc(origin) +
        " → " +
        esc(destination) +
        '</h3><p class="m-help">' +
        esc(r.driver.name) +
        " · " +
        esc(r.available) +
        " " +
        (r.available === 1 ? "vaga" : "vagas") +
        "</p>" +
        badge(r.status) +
        '</div><div class="m-journey-choice"><p class="m-price">' +
        cash(r.priceCents) +
        '</p><span class="m-help">por pessoa</span>' +
        go(
          own ? "Gerenciar trecho" : "Ver carona",
          own ? "passengers" : "ride",
          r.id,
          "secondary",
        ) +
        "</div></article>"
      );
    }

    function reservationRow(r) {
      return (
        '<article class="m-row m-record-row"><div><p class="m-record-date">' +
        esc(dateLabel(r.date)) +
        " · " +
        esc(r.time) +
        "</p><h3>" +
        esc(journeyLabel(r)) +
        '</h3><p class="m-help">' +
        esc(r.name) +
        " · " +
        (r.contractId ? "Mensal" : "Avulsa") +
        "</p>" +
        badge(r.status) +
        '</div><div class="m-record-choice"><strong>' +
        cash(r.priceCents) +
        "</strong>" +
        go("Ver reserva", "reservation", r.id, "secondary") +
        "</div></article>"
      );
    }
    function requestRow(q, received = false) {
      return (
        '<article class="m-row m-record-row"><div><h3>' +
        esc(q.other.name) +
        "</h3><p>" +
        esc(q.region || "Agenda mensal") +
        " · " +
        (q.type === "mensal" ? "Mensal" : "Avulsa") +
        "</p>" +
        (q.date
          ? '<p class="m-help">' +
            esc(dateLabel(q.date)) +
            " · " +
            esc(q.time || "") +
            "</p>"
          : "") +
        badge(q.status) +
        '</div><div class="m-record-choice">' +
        (q.totalCents != null
          ? "<strong>" + cash(q.totalCents) + "</strong>"
          : "") +
        go(
          received ? "Analisar pedido" : "Ver pedido",
          "request",
          q.id,
          "secondary",
        ) +
        "</div></article>"
      );
    }
    function recordsByMonth(items) {
      const groups = new Map();
      for (const item of items) {
        const key = item.date.slice(0, 7);
        if (!groups.has(key)) groups.set(key, []);
        groups.get(key).push(item);
      }
      return [...groups]
        .map(
          ([key, rows]) =>
            '<section class="m-record-month"><h2>' +
            esc(
              new Intl.DateTimeFormat("pt-BR", {
                month: "long",
                year: "numeric",
                timeZone: "America/Sao_Paulo",
              })
                .format(new Date(key + "-15T12:00:00Z"))
                .replace(/^./, (c) => c.toLocaleUpperCase("pt-BR")),
            ) +
            "</h2>" +
            listMarkup(rows, reservationRow) +
            "</section>",
        )
        .join("");
    }
    const ongoing = (r) =>
      [
        "confirmed",
        "boarded",
        "awaiting_confirmation",
        "under_review",
      ].includes(r.status);
    const needsAttention = (r) =>
      ["awaiting_confirmation", "under_review"].includes(r.status) ||
      r.timeResponse === "pending";
    async function updateHome() {
      if (connectedState.disposed) return;
      root.querySelector("[data-greeting]").textContent =
        "Olá, " + connectedState.me.name.split(/\s+/)[0] + ".";
      root.querySelector("[data-profile-course]").textContent =
        connectedState.me.course || "Área administrativa";
      root.querySelector("[data-profile-placement]").textContent = [
        connectedState.me.periodStatus === "numbered"
          ? connectedState.me.periodNumber + "º período"
          : "Período não definido",
        shifts.find(([value]) => value === connectedState.me.shift)?.[1],
        connectedState.me.classLabel
          ? "Turma " + connectedState.me.classLabel
          : "Turma não informada",
      ]
        .filter(Boolean)
        .join(" · ");
      root.querySelector(".d-caption").textContent =
        "A vaga é confirmada após o pagamento e a verificação de disponibilidade.";
      try {
        const next = await request("/reservations?upcoming=true&limit=1");
        if (connectedState.disposed) return;
        const r = next.items[0];
        root.querySelector(".d-next-trip .d-empty-compact").innerHTML = r
          ? "<h3>" +
            esc(r.time) +
            " · " +
            esc(dateLabel(r.date)) +
            "</h3><p>" +
            esc(journeyLabel(r)) +
            "</p>" +
            go("Ver reserva", "reservation", r.id, "secondary")
          : "<h3>Nenhuma carona marcada</h3><p>Busque seu trajeto e confirme uma reserva para acompanhar aqui.</p>";
      } catch (error) {
        if (error.status !== 401) tell(error.message);
      }
    }
    async function navigate(next = "inicio", id = "", replace = false) {
      connectedState.route = next === "oferecer" ? "driver" : next;
      connectedState.ref = id;
      connectedState.current = null;
      connectedState.preview = null;
      clearInterval(connectedState.pollTimer);
      connectedState.paymentCleanup?.();
      connectedState.paymentCleanup = null;
      const version = ++connectedState.epoch;
      const url = new URL(location.href);
      url.hash = "";
      url.searchParams.set("view", connectedState.route);
      id ? url.searchParams.set("ref", id) : url.searchParams.delete("ref");
      for (const key of queryKeys) {
        if (
          ["results", "buscar"].includes(connectedState.route) &&
          connectedState.query[key] !== undefined &&
          connectedState.query[key] !== ""
        )
          url.searchParams.set(key, String(connectedState.query[key]));
        else url.searchParams.delete(key);
      }
      for (const key of Object.keys(pageScopes)) {
        if (
          (pageScopes[key] === connectedState.route ||
            (pageScopes[key] === "minhas" &&
              connectedState.route === "history")) &&
          pageOffsets[key]
        )
          url.searchParams.set("page_" + key, String(pageOffsets[key]));
        else url.searchParams.delete("page_" + key);
      }
      if (["minhas", "history"].includes(connectedState.route))
        url.searchParams.set("list", connectedState.filter);
      else url.searchParams.delete("list");
      if (!replace) connectedState.navigationDepth++;
      history[replace ? "replaceState" : "pushState"](
        {
          route: connectedState.route,
          ref: id,
          ucSession: navigationSession,
          ucDepth: connectedState.navigationDepth,
        },
        "",
        url,
      );
      const isHome = ["inicio", "buscar"].includes(connectedState.route);
      root.querySelectorAll("[data-view]").forEach((v) => (v.hidden = true));
      root.querySelector(".d-page-heading").hidden = !isHome;
      app.hidden = isHome;
      root.querySelectorAll(".d-nav-item").forEach((button) => {
        if (
          button.dataset.page === connectedState.route ||
          (button.dataset.page === "oferecer" &&
            connectedState.route === "driver")
        )
          button.setAttribute("aria-current", "page");
        else button.removeAttribute("aria-current");
      });
      if (isHome) {
        home(connectedState.route, false);
        await updateHome();
        return;
      }
      root.classList.remove("d-searching");
      window.scrollTo({ top: 0, behavior: "instant" });
      document.title =
        (titles[connectedState.route] || "UniCarona") + " | UniCarona";
      const pageTitle =
        connectedState.route === "passengers" &&
        connectedState.ref === "requests"
          ? "Solicitações recebidas"
          : connectedState.route === "admin"
            ? connectedState.ref.startsWith("case:")
              ? "Apuração da ocorrência"
              : connectedState.ref.startsWith("finance:")
                ? "Conciliação de pagamento"
                : {
                    approvals: "Motoristas",
                    cases: "Ocorrências",
                    accounts: "Contas",
                    privacy: "Privacidade administrativa",
                    academic: "Vínculo acadêmico",
                    calendar: "Calendário",
                    pricing: "Parâmetros de contribuição",
                    finance: "Conciliação",
                    jobs: "Tarefas de envio",
                  }[connectedState.ref || "approvals"] || "Administração"
            : titles[connectedState.route] || "UniCarona";
      document.title = pageTitle + " | UniCarona";
      app.innerHTML =
        '<header class="m-page-header"><button type="button" class="m-back m-button quiet" data-c-action="back">' +
        UI.icon("arrowLeft") +
        'Voltar</button><h1 tabindex="-1">' +
        esc(pageTitle) +
        '</h1></header><div data-c-content aria-busy="true">' +
        UI.loading() +
        "</div>";
      app.querySelector("h1").focus({ preventScroll: true });
      try {
        const html = await view();
        if (version !== connectedState.epoch || connectedState.disposed) return;
        const content = app.querySelector("[data-c-content]");
        content.innerHTML = html;
        content.removeAttribute("aria-busy");
        content.removeAttribute("role");
        centerAdminTab();
        adaptFilters();
        adaptAdmin();
        adaptPayment();
        if (
          connectedState.route === "checkout" &&
          connectedState.current?.configuration?.provider === "mercadopago"
        )
          connectedState.paymentCleanup = window.UniCaronaPayments?.mount(
            content,
            connectedState.current,
            connectedState.current.configuration,
            connectedState.current.gateway,
            () => {
              if (!connectedState.disposed && version === connectedState.epoch)
                perform("syncPayment", { id: connectedState.current.id }).catch(
                  (error) => tell(error.message),
                );
            },
          );
      } catch (error) {
        if (version !== connectedState.epoch || connectedState.disposed) return;
        if (error.status === 401) {
          location.assign(
            "login.html?next=" +
              encodeURIComponent(location.pathname + location.search) +
              "#login",
          );
          return;
        }
        app.querySelector("[data-c-content]").innerHTML = UI.empty(
          "Não foi possível carregar",
          error.message,
          action("Tentar novamente", "reload", {}, "secondary"),
        );
        app.querySelector("[data-c-content]").removeAttribute("aria-busy");
      }
    }

    const emergencyHelp = () =>
      '<section class="m-panel m-section"><h2>Ajuda e emergência</h2><p>190: emergência policial. Ligue 180: orientação e atendimento sobre violência contra a mulher.</p><p class="m-help">O UniCarona não monitora emergências nem envia sua localização. Estes números são informativos; esta tela não inicia uma chamada.</p><a class="m-button quiet" href="https://www.gov.br/mulheres/pt-br/ligue180" target="_blank" rel="noopener noreferrer">Conhecer os canais oficiais</a></section>';

    async function adminView() {
      if (connectedState.me.role !== "admin") return admin();
      const section = connectedState.ref.startsWith("case:")
          ? "cases"
          : connectedState.ref.startsWith("finance:")
            ? "finance"
            : connectedState.ref || "approvals",
        sections = [
          ["approvals", "Motoristas"],
          ["cases", "Ocorrências"],
          ["accounts", "Contas"],
          ["privacy", "Privacidade"],
          ["academic", "Vínculo acadêmico"],
          ["calendar", "Calendário"],
          ["pricing", "Parâmetros"],
          ["finance", "Conciliação"],
          ["jobs", "Tarefas de envio"],
        ];
      const navigation =
        '<details class="m-admin-navigation" data-admin-navigation open><summary>Seções administrativas · ' +
        esc(sections.find(([key]) => key === section)?.[1] || "Administração") +
        '</summary><nav aria-label="Administração">' +
        sections
          .map(([key, label]) =>
            UI.button(
              label,
              {
                "data-c-go": "admin",
                "data-ref": key,
                ...(key === section ? { "aria-current": "page" } : {}),
              },
              "quiet",
            ),
          )
          .join("") +
        "</nav></details>";
      return (
        '<div class="m-admin-layout">' +
        navigation +
        '<div class="m-admin-content">' +
        (await admin()) +
        "</div></div>"
      );
    }

    async function view() {
      if (
        Object.hasOwn(
          window.UniCaronaPrivacy?.titles || {},
          connectedState.route,
        )
      )
        return privacy.render(connectedState.route, connectedState.ref);
      if (
        Object.hasOwn(
          window.UniCaronaAdminPrivacy?.titles || {},
          connectedState.route,
        )
      )
        return adminPrivacy.render(connectedState.route, connectedState.ref);
      return (
        {
          results,
          ride: rideDetail,
          monthly,
          renew: monthly,
          request: requestView,
          checkout,
          reservation,
          contract,
          minhas: mine,
          driver,
          offer: () => offer(),
          edit: () => offer(true),
          passengers,
          wallet,
          perfil: profileView,
          settings,
          safety,
          report,
          notifications,
          admin: adminView,
          history: historyView,
          help,
        }[connectedState.route]?.() ||
        empty("Tela não encontrada", "Volte ao início para continuar.")
      );
    }

    async function perform(operation, input) {
      if (operation.startsWith("privacy"))
        return privacy.perform(operation, input);
      if (operation.startsWith("aPrivacy"))
        return adminPrivacy.perform(operation, input);
      if (operation === "share") {
        const r = await request("/reservations/" + input.id),
          text = [
            "UniCarona · detalhes da carona",
            dateLabel(r.date) + " · " + r.time,
            r.region +
              " ↔ " +
              (connectedState.me.university || "Faculdade") +
              " · " +
              (r.direction === "ida" ? "Ida" : "Volta"),
            "Com quem: " + r.name,
            "Veículo: " +
              r.vehicle.model +
              " · " +
              r.vehicle.color +
              " · " +
              r.vehicle.plate,
            "Encontro: " + r.meeting,
            "Registro: " + (labels[r.status] || r.status),
            "Sem localização ao vivo.",
          ].join("\n");
        dialog.innerHTML =
          '<h2>Compartilhar detalhes</h2><p>Revise o resumo e escolha com quem compartilhar. O código de embarque e os contatos pessoais ficam fora dele.</p><label class="m-field"><span>Resumo da carona</span><textarea aria-label="Detalhes para compartilhar" readonly rows="8">' +
          esc(text) +
          '</textarea></label><p class="m-error" role="status" data-share-feedback></p><div class="m-actions">' +
          action("Copiar detalhes", "copyShare") +
          (navigator.share
            ? action("Compartilhar no aparelho", "systemShare", {}, "secondary")
            : "") +
          '<button class="m-button quiet" type="button" data-dialog-close>Fechar</button></div>';
        showDialog();
        return;
      }
      if (operation === "copyShare" || operation === "systemShare") {
        const field = dialog.querySelector(
            '[aria-label="Detalhes para compartilhar"]',
          ),
          feedback = dialog.querySelector("[data-share-feedback]");
        if (!field) return;
        try {
          if (operation === "systemShare")
            await navigator.share({
              title: "Carona UniCarona",
              text: field.value,
            });
          else if (navigator.clipboard) {
            await navigator.clipboard.writeText(field.value);
            feedback.textContent = "Detalhes copiados.";
          } else {
            field.focus();
            field.select();
            feedback.textContent =
              "Selecione e copie o resumo para compartilhar.";
          }
        } catch (error) {
          if (error.name !== "AbortError") {
            field.focus();
            field.select();
            feedback.textContent =
              "Não foi possível abrir o compartilhamento. Você pode copiar o resumo selecionado.";
          }
        }
        return;
      }
      if (operation === "back") {
        if (
          history.state?.ucSession === navigationSession &&
          history.state.ucDepth > 0
        ) {
          history.back();
          return;
        }
        return navigate("inicio", "", true);
      }
      if (operation === "reload")
        return navigate(connectedState.route, connectedState.ref, true);
      if (operation === "listPage") {
        if (
          !Object.hasOwn(pageScopes, input.key) ||
          !Number.isInteger(input.offset) ||
          input.offset < 0 ||
          input.offset > 5000
        )
          throw Error("Página inválida.");
        pageOffsets[input.key] = input.offset;
        return navigate(connectedState.route, connectedState.ref);
      }
      if (operation === "filter") {
        connectedState.filter = input.filter;
        pageOffsets["mine_" + connectedState.filter] = 0;
        return navigate("minhas", "", true);
      }
      if (operation === "filters") {
        pageOffsets.results = 0;
        connectedState.query = {
          ...connectedState.query,
          compatibleOnly: !!input.compatibleOnly,
          onlyAvailable: !!input.onlyAvailable,
          automatic: !!input.automatic,
          maxPrice: input.maxPrice
            ? Math.round(Number(input.maxPrice) * 100)
            : 0,
        };
        return navigate("results", "", true);
      }
      if (operation === "requestRide") {
        const q = await post("/ride-requests", {
          type: "avulsa",
          rideId: input.rideId,
        });
        return navigate("request", q.id);
      }
      if (operation === "previewMonthly") {
        const body = {
          routineId: input.routineId,
          startDate: input.startDate,
          directions: input.directions,
          weekdays: input.weekdays,
          previousContractId: input.previousContractId,
        };
        connectedState.preview = await post("/subscriptions/preview", body);
        app.querySelector("[data-monthly-preview]").innerHTML = previewMarkup(
          connectedState.preview,
          body,
        );
        app.querySelector("[data-monthly-preview]").scrollIntoView({
          block: "start",
          behavior: matchMedia("(prefers-reduced-motion: reduce)").matches
            ? "instant"
            : "smooth",
        });
        return;
      }
      if (operation === "requestMonthly") {
        const q = await post("/ride-requests", { type: "mensal", ...input });
        return navigate("request", q.id);
      }
      if (operation === "respondRequest") {
        await post("/ride-requests/" + input.id + "/respond", {
          accept: input.accept,
        });
        return navigate("request", input.id, true);
      }
      if (operation === "cancelRequest") {
        await post("/ride-requests/" + input.id + "/cancel");
        return navigate("request", input.id, true);
      }
      if (operation === "checkout" || operation === "retryCheckout")
        return submitCheckout(input);
      if (operation === "syncPayment") {
        await post("/payments/" + input.id + "/sync");
        return navigate("checkout", input.id, true);
      }
      if (operation === "payDebt") {
        const payment = await post("/wallet/debt/checkout");
        return navigate("wallet", "debt:" + payment.id, true);
      }
      if (operation === "syncDebt") {
        await post("/wallet/debt/payments/" + input.id + "/sync");
        return navigate("wallet", "debt:" + input.id, true);
      }
      if (operation === "recheckFinancialReview") {
        await post("/admin/payment-reviews/" + input.id + "/recheck", {
          reason: input.reason,
        });
        tell("Consulta registrada. Confira o estado e os bloqueios do caso.");
        return navigate("admin", "finance:" + input.id, true);
      }
      if (operation === "resolveFinancialReview") {
        if (
          !(await confirm(
            "Concluir a conciliação com as evidências exibidas? O servidor consultará o Mercado Pago novamente. A dívida existente não será quitada por esta ação.",
          ))
        )
          return;
        await post("/admin/payment-reviews/" + input.id + "/resolve", {
          reason: input.reason,
          evidenceKey: input.evidenceKey,
        });
        tell(
          "Conciliação registrada. Confira os saldos e eventuais outras pendências.",
        );
        return navigate("admin", "finance:" + input.id, true);
      }
      if (operation === "copyPix") {
        const field = app.querySelector("[data-pix-code]");
        if (!field) return;
        if (navigator.clipboard) {
          await navigator.clipboard.writeText(field.value);
          tell("Código Pix copiado. Cole no aplicativo do seu banco.");
        } else {
          field.focus();
          field.select();
          tell("Código selecionado. Copie e cole no aplicativo do seu banco.");
        }
        return;
      }
      if (operation === "publishOffer" || operation === "editOffer") {
        const common = {
          capacity: Number(input.capacity),
          distance: Number(input.distance),
          toll: input.toll || "0",
          automatic: !!input.automatic,
          notes: input.notes || "",
        };
        let result;
        if (operation === "editOffer")
          result = await request("/rides/" + input.id, {
            method: "PATCH",
            body: { ...common, version: input.version },
          });
        else if (input.type === "mensal")
          result = await post("/routines", {
            ...common,
            region: input.region,
            meeting: input.meeting,
            direction: input.direction,
            time: input.time,
            returnTime: input.returnTime || undefined,
            weekdays: input.weekdays,
          });
        else
          result = await post("/rides", {
            ...common,
            region: input.region,
            meeting: input.meeting,
            direction: input.direction,
            time: input.time,
            date: input.date,
          });
        tell("Oferta salva.");
        return navigate(
          input.type === "mensal" ? "driver" : "passengers",
          input.type === "mensal" ? "" : result.id,
        );
      }
      if (operation === "applyDriver") {
        for (const file of [input.identity, input.license])
          if (
            !(file instanceof File) ||
            !file.size ||
            file.size > 5 * 1024 * 1024
          )
            throw Error("Selecione documentos de até 5 MB.");
        const identity = await A.upload(input.identity, "identity"),
          license = await A.upload(input.license, "license");
        await post("/drivers/applications", {
          model: input.model,
          color: input.color,
          plate: input.plate,
          capacity: Number(input.capacity),
          identityFileId: identity.id,
          licenseFileId: license.id,
        });
        return navigate("driver", "", true);
      }
      if (operation === "routineAvailability") {
        await request("/routines/" + input.id + "/availability", {
          method: "PATCH",
          body: { enabled: input.enabled },
        });
        return navigate("driver", "", true);
      }
      const rideActions = {
        startRide: ["start", {}],
        finishRide: ["finish", { availability: !!input.availability }],
        cancelRide: ["cancel", { reason: input.reason }],
        noClass: ["no-class", { reason: input.reason }],
        proposeTime: [
          "time-proposal",
          { time: input.time, reason: input.reason },
        ],
        keepTime: ["keep-time", {}],
      };
      if (rideActions[operation]) {
        if (
          ["cancelRide", "noClass"].includes(operation) &&
          !(await confirm(
            operation === "cancelRide"
              ? "Cancelar este trecho e devolver integralmente suas parcelas pagas?"
              : "Informar Sem aula posterior ao contrato? Os mensalistas poderão confirmar ou contestar a retenção.",
          ))
        )
          return;
        await post(
          "/rides/" + input.id + "/" + rideActions[operation][0],
          rideActions[operation][1],
        );
        return navigate("passengers", input.id, true);
      }
      const reservationActions = {
        board: ["board", { pin: input.pin }],
        final: ["final", {}],
        contest: ["contest", { reason: input.reason }],
        cancelPassenger: ["cancel", { reason: input.reason }],
        absence: ["absence", { reason: input.reason }],
        timeResponse: ["time-response", { accept: input.accept }],
      };
      if (reservationActions[operation]) {
        const target =
          operation === "board" ? connectedState.current?.id : input.id;
        if (
          operation === "cancelPassenger" &&
          !(await confirm(
            "Cancelar esta reserva avulsa? Sua parcela seguirá análise financeira.",
          ))
        )
          return;
        await post(
          "/reservations/" + input.id + "/" + reservationActions[operation][0],
          reservationActions[operation][1],
        );
        if (target)
          return navigate(
            operation === "board" ? "passengers" : "reservation",
            target,
            true,
          );
        return;
      }
      if (operation === "contact") {
        const c = await request("/reservations/" + input.id + "/contact");
        return info(
          c.name,
          c.phone
            ? "Telefone informado: " + c.phone
            : "Esta pessoa não informou telefone.",
        );
      }
      if (operation === "cancelRenewal") {
        await post("/subscriptions/" + input.id + "/cancel-renewal");
        return navigate("contract", input.id, true);
      }
      if (operation === "removeMonthly") {
        if (
          !(await confirm(
            "Encerrar os trechos futuros dos períodos pagos ligados a este vínculo? O histórico consumido e os valores em análise serão preservados.",
          ))
        )
          return;
        await post("/subscriptions/" + input.id + "/remove", {
          reason: input.reason,
        });
        return navigate("contract", input.id, true);
      }
      if (operation === "wait") {
        await post("/waitlist", { routineId: input.routineId });
        tell("Interesse registrado. Este aviso não ocupa nem garante vaga.");
        return;
      }
      if (operation === "transfer") {
        await post("/transfers", {
          type: input.type,
          cents: Math.round(Number(input.cents) * 100),
        });
        return navigate("wallet", "", true);
      }
      if (operation === "profile") {
        const academic = {
          universityId: connectedState.me.universityId,
          courseId: input.courseId,
          region: input.region,
          periodStatus: input.periodStatus,
          periodNumber:
            input.periodStatus === "numbered"
              ? Number(input.periodNumber)
              : null,
          shift: input.shift,
          classLabel: input.classLabel || null,
        };
        const body = {
          name: input.name,
          phone: input.phone || "",
          academic,
          acknowledgeMonthlyImpact: false,
        };
        try {
          connectedState.me = await request("/users/me", {
            method: "PATCH",
            body,
          });
        } catch (error) {
          if (error.code !== "MONTHLY_IMPACT_REQUIRED") throw error;
          if (
            !(await confirm(
              "Esta mudança encerra os mensais incompatíveis e devolve em crédito as parcelas futuras. Salvar a alteração?",
            ))
          )
            return;
          connectedState.me = await request("/users/me", {
            method: "PATCH",
            body: { ...body, acknowledgeMonthlyImpact: true },
          });
        }
        if (input.photo?.size) await A.upload(input.photo, "photo");
        tell("Perfil salvo.");
        return navigate("perfil", "", true);
      }
      if (operation === "settings") {
        await request("/users/me/settings", {
          method: "PATCH",
          body: { notices: input.notices, withdrawal: input.withdrawal },
        });
        tell("Preferências salvas.");
        return;
      }
      if (operation === "password") {
        if (input.password !== input.confirm)
          throw Error("As senhas não são iguais.");
        await post("/auth/change-password", {
          currentPassword: input.currentPassword,
          password: input.password,
        });
        location.assign("login.html#senha-alterada");
        return;
      }
      if (operation === "academicVerification") {
        await post("/users/me/academic-verification");
        tell("Análise acadêmica solicitada.");
        return navigate("perfil", "", true);
      }
      if (operation === "report") {
        const result = await post("/occurrences", {
          otherId: input.otherId,
          reservationId: input.reservationId,
          reason: input.reason,
          severe: !!input.severe,
          block: !!input.block,
        });
        return navigate("safety", result.id);
      }
      if (operation === "unblock") {
        await post("/blocks/" + input.id + "/remove");
        return navigate(connectedState.route, connectedState.ref, true);
      }
      if (operation === "readNotice") {
        await post("/notifications/" + input.id + "/read");
        return navigate("notifications", "", true);
      }
      if (operation === "reviewDriver") {
        await post("/admin/drivers/applications/" + input.id + "/review", {
          approve: input.approve === "true",
          reason: input.reason,
        });
        return navigate("admin", "approvals", true);
      }
      if (operation === "reviewCase") {
        const body = { outcome: input.outcome, reason: input.reason },
          path = "/admin/occurrences/" + input.id + "/review";
        try {
          await post(path, body);
        } catch (error) {
          if (
            error.code !== "FINANCIAL_DECISION_CONFLICT" ||
            !error.details.conflictingDecisionIds?.length
          )
            throw error;
          const ids = error.details.conflictingDecisionIds,
            prior = await Promise.all(
              ids.map((id) => request("/admin/occurrences/" + id + "?limit=1")),
            );
          const explanation = prior
            .map(
              (o) =>
                (labels[o.outcome] || o.outcome) +
                ": " +
                (o.decision || o.reason),
            )
            .join("\n\n");
          if (
            !(await confirm(
              "Já existem decisões divergentes: " +
                explanation +
                "\n\nSubstituir essas decisões, para as parcelas ainda bloqueadas, por " +
                (labels[input.outcome] || input.outcome) +
                "? Sua justificativa será registrada: " +
                input.reason,
            ))
          )
            return;
          await post(path, { ...body, acknowledgedDecisionIds: ids });
        }
        return navigate("admin", "cases", true);
      }
      if (operation === "jobFilter") {
        connectedState.jobStatus = input.status;
        pageOffsets.admin_jobs = 0;
        return navigate("admin", "jobs", true);
      }
      if (operation === "retryJob") {
        if (
          !(await confirm(
            "Retomar o envio desta mesma tarefa? O saldo e o resultado financeiro continuam dependendo da confirmação do provedor.",
          ))
        )
          return;
        await post("/admin/jobs/" + input.id + "/retry", {
          expectedAttempts: input.expectedAttempts,
          reason: input.reason,
        });
        tell("Envio retomado; acompanhe o processamento pela fila.");
        return navigate("admin", "jobs", true);
      }
      if (operation === "reviewAcademic") {
        await post("/admin/academic-verifications/" + input.id + "/review", {
          approve: input.approve === "true",
          reason: input.reason,
        });
        return navigate("admin", "academic", true);
      }
      if (operation === "accountFilter") {
        connectedState.accountFilter = {
          q: input.q.trim(),
          status: input.status,
        };
        connectedState.adminOffsets.accounts = 0;
        return navigate("admin", "accounts", true);
      }
      if (operation === "reviewAccount") {
        if (
          !(await confirm(
            input.status === "suspended"
              ? "Suspender preventivamente esta conta e enviar os trechos não iniciados para análise financeira separada?"
              : "Restaurar o acesso a novas combinações? Os trechos já interrompidos permanecerão no histórico.",
          ))
        )
          return;
        await post("/admin/accounts/" + input.id + "/review", {
          status: input.status,
          expectedStatus: input.expectedStatus,
          reason: input.reason,
        });
        tell("Análise de acesso registrada.");
        return navigate("admin", "accounts", true);
      }
      if (operation === "adminUniversity") {
        connectedState.adminUniversity = input.universityId;
        connectedState.adminOffsets = { calendar: 0, pricing: 0 };
        return navigate("admin", input.section, true);
      }
      if (operation === "adminPage") {
        connectedState.adminOffsets[input.section] = input.offset;
        return navigate("admin", input.section, true);
      }
      if (operation === "calendarRange") {
        connectedState.calendarRange = {
          startDate: input.startDate,
          endDate: input.endDate,
        };
        connectedState.adminOffsets.calendar = 0;
        return navigate("admin", "calendar", true);
      }
      if (operation === "savePricing") {
        await post("/admin/pricing", {
          universityId: input.universityId,
          previousVersionId: input.previousVersionId,
          fuelCents: Math.round(Number(input.fuel) * 100),
          efficiency: Number(input.efficiency),
          wearCents: Math.round(Number(input.wear) * 100),
          referencePassengers: Number(input.referencePassengers),
          commissionBps: Math.round(Number(input.commission) * 100),
          reason: input.reason,
        });
        tell("Nova versão registrada.");
        return navigate("admin", "pricing", true);
      }
      if (operation === "addCalendar") {
        const body = {
          universityId: input.universityId,
          date: input.date,
          title: input.title,
          scope: input.scope,
        };
        if (input.scope === "class")
          Object.assign(body, {
            courseId: input.courseId,
            periodNumber: Number(input.periodNumber),
            classLabel: input.classLabel,
          });
        await post("/admin/calendar", body);
        tell("Data cadastrada para novas contratações.");
        return navigate("admin", "calendar", true);
      }
      if (operation === "deactivateCalendar") {
        await post("/admin/calendar/" + input.id + "/deactivate", {
          reason: input.reason,
        });
        tell("Exclusão desativada; histórico preservado.");
        return navigate("admin", "calendar", true);
      }
      if (operation === "logout") {
        await post("/auth/logout");
        location.assign("login.html#login");
        return;
      }
      throw Error("Ação indisponível. Atualize a página para continuar.");
    }
    async function click(event) {
      const close = event.target.closest("[data-dialog-close]");
      if (close) {
        dialog.close();
        return;
      }
      const link = event.target.closest("[data-c-go]");
      if (link) {
        event.preventDefault();
        event.stopImmediatePropagation();
        if (accountMenu.matches(":popover-open")) accountMenu.hidePopover();
        if (dialog.open) {
          connectedState.dialogReturnFocus = null;
          dialog.close();
        }
        return navigate(link.dataset.cGo, link.dataset.ref || "");
      }
      const button = event.target.closest("[data-c-action]");
      if (!button || button.disabled) return;
      event.preventDefault();
      event.stopImmediatePropagation();
      if (button.dataset.confirm && !(await confirm(button.dataset.confirm)))
        return;
      if (!dialog.open) connectedState.dialogReturnFocus = button;
      UI.busy(button, true);
      try {
        await perform(
          button.dataset.cAction,
          JSON.parse(button.dataset.input || "{}"),
        );
      } catch (error) {
        info("Não foi possível concluir", error.message);
        if (error.code === "LEGAL_ACCEPTANCE_REQUIRED")
          dialog
            .querySelector("p")
            .insertAdjacentHTML(
              "afterend",
              go("Revisar documentos", "privacy_documents"),
            );
      } finally {
        if (button.isConnected) UI.busy(button, false);
      }
    }
    async function submit(event) {
      const element = event.target;
      if (!element.matches("[data-c-form]")) return;
      event.preventDefault();
      event.stopImmediatePropagation();
      if (!element.reportValidity()) return;
      const data = new FormData(element),
        input = { ...JSON.parse(element.dataset.input || "{}") };
      for (const [name, value] of data)
        if (name !== "weekdays") input[name] = value;
      input.weekdays = data.getAll("weekdays").map(Number);
      for (const checkbox of element.querySelectorAll(
        "input[type=checkbox]:not([name=weekdays])",
      ))
        input[checkbox.name] = checkbox.checked;
      if (/^(privacy|aPrivacy)/.test(element.dataset.cForm))
        element.querySelectorAll('input[type="password"]').forEach((field) => {
          field.value = "";
        });
      const button = element.querySelector("[type=submit]");
      if (button.disabled) return;
      UI.busy(button, true, "Enviando…");
      element.setAttribute("aria-busy", "true");
      element.querySelector(".m-error").textContent = "";
      try {
        await perform(element.dataset.cForm, input);
      } catch (error) {
        if (element.isConnected) {
          const feedback = element.querySelector(".m-error");
          feedback.textContent = error.message;
          if (error.code === "LEGAL_ACCEPTANCE_REQUIRED")
            feedback.insertAdjacentHTML(
              "beforeend",
              " " + go("Revisar documentos", "privacy_documents"),
            );
          feedback.focus();
        }
      } finally {
        if (element.isConnected) {
          UI.busy(button, false);
          element.removeAttribute("aria-busy");
        }
      }
    }
    function change(event) {
      const formElement = event.target.closest("[data-c-form]");
      if (!formElement) return;
      if (formElement.dataset.cForm.startsWith("aPrivacy"))
        adminPrivacy?.change(event);
      if (event.target.type === "file") {
        const filename = event.target
          .closest(".m-field")
          .querySelector("[data-file-name]");
        if (filename)
          filename.textContent =
            event.target.files[0]?.name || "Nenhum selecionado";
      }
      if (event.target.type === "date") {
        const hint = event.target
          .closest(".m-field")
          .querySelector("[data-date-label]");
        if (hint)
          hint.textContent = event.target.value
            ? "Data selecionada: " + dateLabel(event.target.value)
            : "Selecione a data no calendário.";
      }
      if (event.target.name === "scope") {
        const box = formElement.querySelector("[data-c-class-scope]");
        if (box) {
          box.hidden = event.target.value !== "class";
          for (const control of box.querySelectorAll("input,select"))
            control.disabled = box.hidden;
        }
      }
      if (event.target.name === "type") {
        const monthly = event.target.value === "mensal",
          direction = formElement.querySelector("[name=direction]");
        direction.querySelector("[value=ambos]").disabled = !monthly;
        if (!monthly && direction.value === "ambos") direction.value = "ida";
        formElement.querySelector("[data-c-avulsa]").hidden = monthly;
        formElement.querySelector("[name=date]").disabled = monthly;
        formElement.querySelector("[data-c-monthly]").hidden = !monthly;
        formElement.querySelector("[name=returnTime]").required =
          monthly && direction.value === "ambos";
      }
      if (
        event.target.name === "direction" &&
        formElement.querySelector("[name=returnTime]")
      )
        formElement.querySelector("[name=returnTime]").required =
          formElement.querySelector("[name=type]").value === "mensal" &&
          event.target.value === "ambos";
      if (event.target.name === "periodStatus") {
        const period = formElement.querySelector("[name=periodNumber]");
        period.disabled = event.target.value !== "numbered";
        period.required = !period.disabled;
      }
    }
    function pop() {
      connectedState.navigationDepth =
        history.state?.ucSession === navigationSession
          ? history.state.ucDepth
          : 0;
      const url = new URL(location.href);
      connectedState.query = readQuery(url);
      connectedState.filter = [
        "reservations",
        "requests",
        "subscriptions",
        "offers",
      ].includes(url.searchParams.get("list"))
        ? url.searchParams.get("list")
        : "reservations";
      for (const key of Object.keys(pageScopes)) {
        const value = url.searchParams.get("page_" + key);
        pageOffsets[key] =
          /^\d{1,4}$/.test(value || "") && Number(value) <= 5000
            ? Number(value)
            : 0;
      }
      navigate(
        url.searchParams.get("view") || "inicio",
        url.searchParams.get("ref") || "",
        true,
      );
    }
    function destroy() {
      connectedState.disposed = true;
      connectedState.epoch++;
      clearTimeout(connectedState.toastTimer);
      clearInterval(connectedState.pollTimer);
      connectedState.paymentCleanup?.();
      sizeObserver.disconnect();
      privacy?.destroy();
      adminPrivacy?.destroy();
      filterBreakpoint.removeEventListener("change", adaptFilters);
      adminBreakpoint.removeEventListener("change", adaptAdmin);
      paymentBreakpoint.removeEventListener("change", adaptPayment);
      root.removeEventListener("click", click, true);
      root.removeEventListener("submit", submit, true);
      root.removeEventListener("change", change);
      window.removeEventListener("popstate", pop);
      app.remove();
      toolbar.remove();
      accountMenu.remove();
      restriction.remove();
      dialog.remove();
      toast.remove();
    }
    const { results, rideDetail } = window.UniCaronaFeatures.rides.create({
      state: connectedState,
      UI,
      academic,
      action,
      badge,
      cash,
      check,
      dateLabel,
      dayNames,
      esc,
      field,
      form,
      go,
      listMarkup,
      notice,
      pageQuery,
      pages,
      request,
      rideRow,
      summary,
    });
    const { monthly, agendaLines, previewMarkup, contract } =
      window.UniCaronaFeatures.subscriptions.create({
        state: connectedState,
        UI,
        academic,
        action,
        area,
        badge,
        cash,
        dateLabel,
        dayNames,
        days,
        esc,
        field,
        form,
        go,
        listMarkup,
        needsAttention,
        notice,
        ongoing,
        recordsByMonth,
        request,
        reservationRow,
        select,
        summary,
        today,
      });
    const { requestView, reservation, mine, historyView } =
      window.UniCaronaFeatures.reservations.create({
        state: connectedState,
        UI,
        action,
        area,
        badge,
        cash,
        dateLabel,
        emergencyHelp,
        esc,
        form,
        go,
        journeyLabel,
        labels,
        listMarkup,
        notice,
        ongoing,
        pageQuery,
        pages,
        photo,
        recordsByMonth,
        request,
        requestRow,
        reservationRow,
        rideRow,
        summary,
      });
    const { checkout, wallet, submitCheckout } =
      window.UniCaronaFeatures.payments.create({
        state: connectedState,
        UI,
        action,
        badge,
        cash,
        check,
        dateLabel,
        esc,
        field,
        form,
        go,
        listMarkup,
        navigate,
        notice,
        pageQuery,
        pages,
        post,
        request,
        select,
        summary,
        tell,
      });
    const { driver, offer, passengers } =
      window.UniCaronaFeatures.driver.create({
        state: connectedState,
        UI,
        action,
        area,
        badge,
        cash,
        check,
        dateLabel,
        days,
        driverDocumentsNotice,
        esc,
        field,
        form,
        go,
        labels,
        listMarkup,
        notice,
        pageQuery,
        pages,
        request,
        requestRow,
        rideRow,
        select,
        summary,
        today,
      });
    const { profileView, settings, notifications, help } =
      window.UniCaronaFeatures.account.create({
        state: connectedState,
        UI,
        action,
        badge,
        check,
        esc,
        field,
        form,
        go,
        listMarkup,
        notice,
        pageQuery,
        pages,
        photo,
        request,
        select,
        shifts,
        titles,
      });
    const { safety, report } = window.UniCaronaFeatures.safety.create({
      state: connectedState,
      UI,
      action,
      area,
      badge,
      check,
      dateLabel,
      emergencyHelp,
      esc,
      form,
      go,
      journeyLabel,
      listMarkup,
      notice,
      pageQuery,
      pages,
      request,
      select,
      summary,
    });
    const { admin, financialReviews, adminOperations } =
      window.UniCaronaFeatures.admin.create({
        state: connectedState,
        UI,
        action,
        adminPrivacy,
        area,
        badge,
        cash,
        dateLabel,
        driverDocumentsNotice,
        esc,
        field,
        form,
        go,
        labels,
        listMarkup,
        notice,
        pageQuery,
        pages,
        request,
        select,
        summary,
        today,
      });
    root.addEventListener("click", click, true);
    root.addEventListener("submit", submit, true);
    root.addEventListener("change", change);
    window.addEventListener("popstate", pop);
    const url = new URL(location.href);
    navigate(
      url.searchParams.get("view") || "inicio",
      url.searchParams.get("ref") || "",
      true,
    );
    return {
      navigate,
      search: (input) => {
        connectedState.query = { ...input };
        pageOffsets.results = 0;
        navigate("results");
      },
      destroy,
      info: (key) => {
        if (key === "monthly") {
          connectedState.query = {
            ...connectedState.query,
            type: "mensal",
            compatibleOnly: true,
          };
          pageOffsets.results = 0;
          return navigate("results");
        }
        return navigate(
          {
            notifications: "notifications",
            safety: "safety",
            settings: "settings",
            verification: "driver",
            driver: "driver",
            payments: "wallet",
          }[key] || "help",
        );
      },
    };
  }
  window.UniCaronaConnectedUI = Object.freeze({ attach, academic });
})();
