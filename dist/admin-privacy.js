/* Administrative privacy tasks. Sensitive evidence is revealed on demand, never persisted by this client. */
(() => {
  "use strict";
  const UI = window.UniCaronaUI, esc = UI.escape;
  const titles = Object.freeze({ admin_privacy: "Privacidade administrativa", admin_privacy_requests: "Solicitações de privacidade", admin_privacy_request: "Atender solicitação", admin_privacy_account: "Privacidade e situação da conta", admin_privacy_decision: "Registrar decisão sobre a conta", admin_privacy_holds: "Preservações por caso", admin_privacy_hold: "Evidência da preservação", admin_privacy_hold_new: "Preservar dados de um caso", admin_privacy_hold_review: "Revisar preservação" });
  const statuses = { received: "Recebida", in_review: "Em análise", awaiting_information: "Aguardando informações", completed: "Concluída", denied: "Não atendida", active: "Ativa", deactivated: "Desativada", suspended: "Em revisão", banned: "Banida", closing: "Encerramento em andamento", deleted: "Excluída", scheduled: "Exclusão programada", pending_obligations: "Aguardando operações", erasing: "Remoção iniciada", cancelled: "Cancelada", review_due: "Revisão pendente", released: "Liberada", expired: "Vencida" };
  const kinds = { access: "Acesso", correction: "Correção", erasure: "Eliminação", restriction: "Restrição", opposition: "Oposição", portability: "Portabilidade", appeal: "Revisão de decisão", other: "Outro direito" };
  const categories = { profile: "Perfil", files: "Arquivo privado", location: "Localização da rota", contract_identity: "Identificação contratual", vehicle_identity: "Veículo", driver_application: "Solicitação de motorista", security_audit: "Evento de segurança", identity_change: "Alteração de identidade", account_decision: "Decisão de conta", privacy_request: "Protocolo de privacidade", consent: "Manifestação de preferência", legal: "Aceite de documento", notifications: "Notificação" };
  const bases = { legal_obligation: "Obrigação legal específica", regular_exercise_of_rights: "Exercício regular de direitos", contract: "Execução de contrato", security: "Finalidade de segurança" };
  const reasons = { fraud: "Fraude", violence: "Violência ou ameaça", harassment: "Assédio", false_documents: "Documentação falsa", security: "Segurança", repeated_terms_breach: "Violação reiterada dos Termos" };
  const decisions = {
    ban: ["Registrar banimento", "Impede novas combinações, inicia o encerramento pela plataforma e encerra as sessões. A restrição de recadastro exige prazo proporcional. Valores e disputas continuam sendo tratados."],
    close: ["Encerrar pela plataforma", "Inicia o encerramento da conta, interrompe novas combinações e encerra as sessões. Operações em andamento e preservações específicas continuam sendo tratadas."],
    lift_ban: ["Revisar banimento", "Remove a restrição de recadastro e deixa a conta em revisão. Não cancela o encerramento nem retoma ofertas ou contratos interrompidos."],
    cancel_closure: ["Cancelar encerramento da plataforma", "Interrompe este pedido antes da remoção irreversível. Banimento, suspensão e ofertas interrompidas exigem suas próprias decisões."],
  };
  const uuid = value => typeof value === "string" && /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i.test(value);
  const requireId = value => { if (!uuid(value)) throw Error("Identificador inválido. Volte à lista e selecione o registro.");return value; };
  const textArea = (name, label, min = 20, max = 3000) => '<label class="m-field"><span>' + esc(label) + '</span><textarea name="' + esc(name) + '" required minlength="' + min + '" maxlength="' + max + '"></textarea></label>';
  const password = () => UI.field("password", "Confirme sua senha de administrador", "", "password", 'required maxlength="128" autocomplete="current-password"');
  const chooser = (name, label, options) => UI.select(name, label, [["", "Selecione uma opção", true], ...Object.entries(options)]).replace("<select ", "<select required ");
  const stamp = value => esc(UI.dateTime(value));
  const state = value => '<span class="m-badge">' + esc(statuses[value] || "Situação em atualização") + '</span>';
  const summary = pairs => '<dl class="p-summary">' + pairs.map(([label, value]) => '<div><dt>' + esc(label) + '</dt><dd class="p-protocol">' + esc(value ?? "—") + '</dd></div>').join("") + '</dl>';
  const toInstant = value => {
    if (typeof value !== "string" || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/.test(value)) throw Error("Informe data e hora completas para revisão e vencimento.");
    const instant = new Date(value + "-03:00");
    if (!Number.isFinite(instant.getTime()) || new Date(instant.getTime() - 10800000).toISOString().slice(0, 16) !== value) throw Error("Confira a data e a hora informadas.");
    return instant.toISOString();
  };
  const dates = () => UI.field("reviewAt", "Revisar em · horário UTC−03", "", "datetime-local", "required") + UI.field("expiresAt", "Vence em · horário UTC−03", "", "datetime-local", "required") + '<p class="m-help">Escolha revisão nos próximos 90 dias e vencimento em até 365 dias, a partir de agora. Vencimento deve ser igual ou posterior à revisão. Não há prorrogação automática.</p>';
  function create({ request, navigate, tell, isAdmin, reveal, navigationVersion = () => 0 }) {
    let requestFilter = { status: "received", offset: 0 }, holdFilter = { status: "review_due", userId: "", offset: 0 };
    const metadata = new Map(), downloads = new Map();let disposed = false;
    const check = () => { if (disposed || !isAdmin()) throw Error("Acesso administrativo necessário."); };
    const go = (label, route, ref = "") => UI.button(label, { "data-c-go": route, "data-ref": ref }, "secondary");
    const action = (label, operation, input = {}) => UI.button(label, { "data-c-action": operation, "data-input": JSON.stringify(input) }, "quiet");
    const back = () => '<div class="m-actions">' + go("Voltar à privacidade administrativa", "admin_privacy") + '</div>';
    const box = (html, includeBack = true) => '<div class="p-page">' + html + (includeBack ? back() : '') + '</div>';
    const pager = (data, filter, operation) => '<div class="m-actions">' + (filter.offset ? action("Anteriores", operation, { offset: Math.max(0, filter.offset - 30) }) : '') + (Number.isInteger(data.nextOffset) && data.nextOffset <= 5000 ? action("Próximos", operation, { offset: data.nextOffset }) : '') + '</div>';
    const section = (title, body) => '<section class="p-section"><h2>' + esc(title) + '</h2>' + body + '</section>';
    const row = (title, description, route) => '<li><h2 class="p-link-title">' + esc(title) + '</h2><p>' + esc(description) + '</p>' + go(title, route) + '</li>';
    const subject = (data, id) => {
      if (data.subject?.id !== id || typeof data.subject.name !== "string" || typeof data.subject.email !== "string") throw Error("Não foi possível conferir o titular desta conta. Atualize a consulta antes de decidir.");
      return '<div class="p-state"><h2>' + esc(data.subject.name) + '</h2>' + (data.subject.email ? '<p>' + esc(data.subject.email) + '</p>' : '<p>Contato removido do perfil.</p>') + '<p class="p-protocol">Conta ' + esc(id) + '</p></div>';
    };
    const allowedDecisions = account => [
      ...(["active", "deactivated", "suspended", "closing"].includes(account.status) ? ["ban"] : []),
      ...(["active", "deactivated", "suspended", "banned"].includes(account.status) && !["scheduled", "pending_obligations", "erasing"].includes(account.closure?.status) ? ["close"] : []),
      ...(account.status === "banned" ? ["lift_ban"] : []),
      ...(account.closure?.initiatedBy === "platform" && ["scheduled", "pending_obligations"].includes(account.closure.status) ? ["cancel_closure"] : []),
    ];
    async function queue() {
      const params = new URLSearchParams({ limit: "30", offset: String(requestFilter.offset) });if (requestFilter.status) params.set("status", requestFilter.status);
      const data = await request("/admin/privacy/requests?" + params);
      return box('<p>Atenda pelo protocolo. A fila é ordenada pelo prazo de resposta. O conteúdo detalhado tem acesso registrado.</p>' + UI.form("aPrivacyRequestFilter", UI.select("status", "Situação dos pedidos", [["", "Todas"], ...["received", "in_review", "awaiting_information", "completed", "denied"].map(s => [s, statuses[s]])], requestFilter.status), "Filtrar solicitações") + (data.items.length ? data.items.map(item => '<article class="p-result"><h2>' + esc(kinds[item.request.kind] || "Solicitação") + '</h2><p>' + esc(item.name) + ' · ' + esc(item.email) + '</p>' + state(item.request.status) + '<p class="m-help">Resposta prevista: ' + stamp(item.request.dueAt) + '</p><p class="p-protocol">Protocolo ' + esc(item.request.id) + '</p>' + go("Abrir atendimento", "admin_privacy_request", item.request.id) + '</article>').join("") : '<p>Nenhuma solicitação neste filtro. Altere a situação para consultar os demais pedidos.</p>') + pager(data, requestFilter, "aPrivacyRequestPage"));
    }
    async function protocol(ref) {
      const data = await request("/admin/privacy/requests/" + requireId(ref)), item = data.request, current = item.request;
      return box('<h2>' + esc(kinds[current.kind] || "Solicitação") + '</h2>' + state(current.status) + summary([["Titular", item.name], ["E-mail", item.email], ["Protocolo", current.id], ["Conta", item.userId], ["Resposta prevista", UI.dateTime(current.dueAt)]]) + section("Pedido do titular", '<p class="p-private-response">' + esc(data.details) + '</p>') + section("Respostas enviadas", data.responses.length ? data.responses.map(r => '<article class="p-result"><p class="m-help">' + stamp(r.createdAt) + '</p><p class="p-private-response">' + esc(r.message) + '</p></article>').join("") : '<p>Ainda não há resposta neste protocolo.</p>') +
        (!["completed", "denied"].includes(current.status) ? section("Responder ao titular", UI.notice("Registrar uma resposta não executa correção ou exclusão. Conclua a providência no fluxo correspondente antes de marcar o protocolo como concluído.") + UI.form("aPrivacyRespond", chooser("status", "Situação após a resposta", { in_review: statuses.in_review, awaiting_information: statuses.awaiting_information, completed: statuses.completed, denied: statuses.denied }) + textArea("message", "Resposta que será exibida ao titular") + UI.check("actionPerformed", "A providência descrita já foi executada; posso comprovar a conclusão", false), "Registrar e enviar resposta", { id: current.id, expectedStatus: current.status })) : UI.notice("Este protocolo está encerrado. Uma nova solicitação deve ter seu próprio registro.")) + '<div class="m-actions">' + go("Ver situação da conta", "admin_privacy_account", item.userId) + go("Voltar à fila", "admin_privacy_requests") + '</div>');
    }
    async function account(ref) {
      if (!ref) return box('<p>Escolha a pessoa pela busca administrativa. O identificador da conta também pode ser usado quando já estiver disponível no atendimento.</p>' + go("Buscar estudante nas contas", "admin", "accounts") + UI.form("aPrivacyFindAccount", UI.field("userId", "Identificador da conta", "", "text", 'required maxlength="36" autocomplete="off"'), "Consultar conta"));
      const data = await request("/admin/privacy/accounts/" + requireId(ref)), a = data.account;
      return box(subject(data, ref) + '<h2>Situação atual</h2>' + state(a.status) + (a.restrictionUntil ? '<p>Restrição de recadastro até ' + stamp(a.restrictionUntil) + '.</p>' : '') + (a.closure?.id ? section("Encerramento", state(a.closure.status) + summary([["Protocolo", a.closure.id], ["Iniciado por", a.closure.initiatedBy === "platform" ? "Plataforma" : "Titular"], ["Processamento a partir de", UI.dateTime(a.closure.eraseAfter)]]) + '<p>Operações pendentes podem alterar o momento de processamento. Este registro não é uma quitação financeira.</p>') : '') + section("Decisões disponíveis", '<div class="m-actions">' + allowedDecisions(a).map(key => go(decisions[key][0], "admin_privacy_decision", ref + ":" + key)).join("") + '</div><p class="m-help">Suspensão preventiva e restauração do serviço continuam na análise de acesso. Banimento e encerramento são decisões distintas.</p>' + go("Análise de acesso", "admin", "accounts")) + section("Histórico de decisões", data.decisions.length ? data.decisions.map(d => '<article class="p-result"><h3>' + esc(decisions[d.notice.action]?.[0] || "Decisão administrativa") + '</h3><p class="m-help">' + stamp(d.notice.createdAt) + '</p><h4>Comunicado ao titular</h4><p class="p-private-response">' + esc(d.notice.message) + '</p>' + UI.disclosure("Justificativa interna", '<p class="p-private-response">' + esc(d.internalReason) + '</p>') + '</article>').join("") : '<p>Nenhuma decisão deste fluxo foi registrada.</p>') + '<div class="m-actions">' + go("Preservar dados de um caso", "admin_privacy_hold_new", ref) + go("Consultar preservações", "admin_privacy_holds", ref) + '</div>');
    }
    async function decision(ref) {
      const [id, kind] = String(ref).split(":");requireId(id);if (!Object.hasOwn(decisions, kind)) throw Error("Escolha uma decisão disponível na conta.");
      const data = await request("/admin/privacy/accounts/" + id), a = data.account;
      if (!allowedDecisions(a).includes(kind)) return box(UI.notice("A situação atual não permite esta decisão. Consulte a conta antes de continuar.") + go("Consultar situação atual", "admin_privacy_account", id));
      return box(subject(data, id) + '<h2>' + esc(decisions[kind][0]) + '</h2><p>' + esc(decisions[kind][1]) + '</p>' + UI.form("aPrivacyDecide", chooser("reasonCode", "Motivo da decisão", reasons) + textArea("publicReason", "Motivo que será comunicado ao estudante", 10, 1000) + '<p class="m-help">Não revele quem fez um relato nem dados desnecessários de terceiros.</p>' + textArea("reason", "Justificativa interna e evidências consideradas") + (kind === "ban" ? UI.field("restrictionDays", "Dias de restrição de recadastro", "", "number", 'required min="1" max="365" step="1"') + '<p class="m-help">Escolha o menor prazo adequado ao risco. Uma restrição não deve ser indefinida.</p>' : '') + password() + UI.check("acknowledged", "Conferi a conta, os efeitos e os motivos desta decisão", false), "Registrar decisão", { id, action: kind, expectedStatus: a.status }) + go("Voltar à situação da conta", "admin_privacy_account", id));
    }
    const cache = item => { if (metadata.size >= 100) metadata.clear();metadata.set(item.id, item); };
    async function holds(ref) {
      if (ref) { holdFilter = { ...holdFilter, userId: requireId(ref), status: "", offset: 0 }; }
      const params = new URLSearchParams({ limit: "30", offset: String(holdFilter.offset) });if (holdFilter.status) params.set("status", holdFilter.status);if (holdFilter.userId) params.set("userId", holdFilter.userId);
      const data = await request("/admin/privacy/holds?" + params);data.items.forEach(cache);
      return box('<p>Preserve apenas o recurso necessário ao caso, com fundamento, revisão e vencimento. Motivo e histórico exigem senha para consulta.</p>' + go("Nova preservação por caso", "admin_privacy_hold_new", holdFilter.userId) + UI.form("aPrivacyHoldFilter", UI.select("status", "Situação da preservação", [["", "Todas"], ...["review_due", "active", "released", "expired"].map(s => [s, statuses[s]])], holdFilter.status) + UI.field("userId", "Identificador da conta · opcional", holdFilter.userId, "text", 'maxlength="36" autocomplete="off"'), "Filtrar preservações") + (data.items.length ? data.items.map(h => '<article class="p-result"><h2>' + esc(categories[h.category] || "Recurso do caso") + '</h2>' + state(h.status) + summary([["Conta", h.userId], ["Recurso", h.resourceId], ["Revisão prevista", UI.dateTime(h.reviewAt)], ["Vencimento", UI.dateTime(h.expiresAt)], ["Versão da decisão", h.revision]]) + go("Consultar evidência e revisar", "admin_privacy_hold", h.id) + '</article>').join("") : '<p>Nenhuma preservação neste filtro. Consulte outras situações antes de registrar um caso novamente.</p>') + pager(data, holdFilter, "aPrivacyHoldPage"));
    }
    function hold(ref) {
      requireId(ref);const h = metadata.get(ref);
      return box('<p class="p-protocol">Preservação ' + esc(ref) + '</p>' + (h ? '<h2>' + esc(categories[h.category] || "Recurso do caso") + '</h2>' + state(h.status) + summary([["Conta", h.userId], ["Recurso", h.resourceId], ["Revisão prevista", UI.dateTime(h.reviewAt)], ["Vencimento", UI.dateTime(h.expiresAt)]]) : '<p>Confirme sua senha para consultar esta preservação e sua versão atual.</p>') + UI.form("aPrivacyOpenHold", password() + '<p class="m-help">A leitura do motivo e do histórico fica registrada. Os dados são removidos desta tela ao sair dela.</p>', "Consultar motivo e histórico", { id: ref }) + '<div data-a-private aria-live="polite"></div>' + UI.disclosure("Baixar conteúdo preservado", '<p>Faça isso apenas quando necessário ao caso. O arquivo pode conter dados pessoais e não deve ser compartilhado fora da finalidade.</p>' + UI.form("aPrivacyDownloadHold", password(), "Baixar evidência privada", { id: ref })) + go("Voltar às preservações", "admin_privacy_holds"));
    }
    function evidence(data) {
      const h = data.hold;cache(h);
      return section("Motivo e histórico privado", '<p class="p-private-response">' + esc(data.reason) + '</p><p>Referência do caso: ' + esc(data.caseReference || "Não informada no registro original") + '</p><p class="m-help">Versão atual: ' + esc(h.revision) + ' · Revisão prevista: ' + stamp(h.reviewAt) + ' · Vencimento: ' + stamp(h.expiresAt) + '</p>' + data.history.map(event => '<article class="p-result"><h3>' + (event.action === "legacy_snapshot" ? 'Estado anterior conhecido' : 'Decisão registrada') + ' · ' + esc(event.revision) + '</h3><p class="m-help">' + stamp(event.createdAt) + '</p><p class="p-private-response">' + esc(event.reason) + '</p><p>Referência: ' + esc(event.caseReference || "Não informada") + '</p></article>').join("") + (["active", "review_due"].includes(h.status) ? '<div class="m-actions">' + go("Revisar prazos e fundamento", "admin_privacy_hold_review", h.id + ":review") + go("Liberar preservação", "admin_privacy_hold_review", h.id + ":release") + '</div>' : '<p>Este registro está encerrado. Nova preservação exige nova avaliação de dados ainda disponíveis.</p>') + action("Ocultar motivo e histórico", "aPrivacyForgetHold"));
    }
    function createHold(ref) {
      if (ref) requireId(ref);
      return box('<p>Uma preservação mantém somente o recurso identificado. Não recupera dados já apagados e não mantém o perfil público durante exclusão.</p>' + UI.form("aPrivacyCreateHold", UI.field("userId", "Identificador da conta", ref, "text", 'required maxlength="36" autocomplete="off"') + chooser("category", "Dados necessários ao caso", categories) + UI.field("resourceId", "Identificador do recurso", "", "text", 'required maxlength="36" autocomplete="off"') + '<p class="m-help" data-a-resource-help>Selecione a categoria para conferir qual identificador usar. Não informe CPF ou número de documento neste campo.</p>' + chooser("legalBasis", "Fundamento da preservação", bases) + textArea("reason", "Justificativa específica da necessidade") + UI.field("caseReference", "Referência interna do caso", "", "text", 'required minlength="3" maxlength="160"') + dates() + password() + UI.check("acknowledged", "Conferi necessidade, recurso, fundamento e prazos deste caso", false), "Registrar preservação"));
    }
    function reviewHold(ref) {
      const [id, kind] = String(ref).split(":");requireId(id);if (!["review", "release"].includes(kind)) throw Error("Escolha revisar ou liberar pela evidência do caso.");const h = metadata.get(id);
      if (!h) return box('<p>Consulte a evidência atual antes de revisar. Isso evita decidir com uma versão desconhecida do registro.</p>' + go("Consultar evidência atual", "admin_privacy_hold", id));
      if (!["active", "review_due"].includes(h.status)) return box(UI.notice("Esta preservação está encerrada e não pode ser reativada.") + go("Consultar evidência", "admin_privacy_hold", id));
      return box('<h2>' + (kind === "release" ? "Liberar preservação" : "Revisar prazos e fundamento") + '</h2>' + '<p class="p-protocol">Caso ' + esc(id) + ' · Versão ' + esc(h.revision) + '</p>' + (kind === "release" ? '<p>Encerra esta preservação específica. Dados sem outro fundamento podem ser removidos pelo processamento. Não apaga registros financeiros por esta ação.</p>' : '<p>Justifique novamente a necessidade. Não estenda o prazo apenas por precaução.</p>') + UI.form("aPrivacyReviewHold", textArea("reason", "Justificativa desta decisão") + UI.field("caseReference", "Referência interna do caso", "", "text", 'required minlength="3" maxlength="160"') + (kind === "review" ? dates() : '') + password() + UI.check("acknowledged", "Conferi o recurso, a decisão e suas consequências", false), "Registrar decisão do caso", { id, action: kind, expectedRevision: h.revision }) + go("Consultar versão atual", "admin_privacy_hold", id));
    }
    async function render(route, ref = "") {
      check();
      switch (route) {
        case "admin_privacy": return box('<p>Atenda os direitos do titular e registre decisões com evidência e prazo. Acesso e decisões sensíveis ficam no histórico de auditoria.</p><ul class="p-link-list">' + row("Solicitações de privacidade", "Consulte a fila por prazo e responda pelo protocolo.", "admin_privacy_requests") + row("Situação e decisões de conta", "Revise banimento e encerramento sem confundir valores, suspensão e exclusão.", "admin_privacy_account") + row("Preservações por caso", "Revise a necessidade de cada recurso e seus prazos.", "admin_privacy_holds") + '</ul>' + go("Voltar à Administração", "admin", "accounts"), false);
        case "admin_privacy_requests": return queue();case "admin_privacy_request": return protocol(ref);case "admin_privacy_account": return account(ref);case "admin_privacy_decision": return decision(ref);case "admin_privacy_holds": return holds(ref);case "admin_privacy_hold": return hold(ref);case "admin_privacy_hold_new": return createHold(ref);case "admin_privacy_hold_review": return reviewHold(ref);
        default: throw Error("Opção administrativa não encontrada.");
      }
    }
    async function perform(operation, input = {}) {
      check();const version = navigationVersion(), current = () => !disposed && isAdmin() && version === navigationVersion();
      const jump = (...args) => current() ? navigate(...args) : undefined;
      const message = value => { if (current()) tell(value); };
      const post = (path, body, format) => request(path, { method: "POST", body, ...(format ? { format } : {}) });
      if (operation === "aPrivacyRequestFilter") { if (!["", "received", "in_review", "awaiting_information", "completed", "denied"].includes(input.status)) throw Error("Escolha uma situação válida.");requestFilter = { status: input.status, offset: 0 };return jump("admin_privacy_requests"); }
      if (operation === "aPrivacyHoldFilter") { if (input.userId?.trim()) requireId(input.userId.trim());if (!["", "active", "review_due", "released", "expired"].includes(input.status)) throw Error("Escolha uma situação válida.");holdFilter = { status: input.status, userId: input.userId?.trim() || "", offset: 0 };return jump("admin_privacy_holds"); }
      if (["aPrivacyRequestPage", "aPrivacyHoldPage"].includes(operation)) { if (!Number.isInteger(input.offset) || input.offset < 0 || input.offset > 5000) throw Error("Página inválida.");if (operation === "aPrivacyRequestPage") requestFilter.offset = input.offset;else holdFilter.offset = input.offset;return jump(operation === "aPrivacyRequestPage" ? "admin_privacy_requests" : "admin_privacy_holds"); }
      if (operation === "aPrivacyFindAccount") return jump("admin_privacy_account", requireId(input.userId.trim()));
      if (operation === "aPrivacyRespond") { if (input.status === "completed" && !input.actionPerformed) throw Error("Confirme a providência executada antes de concluir o protocolo.");await post("/admin/privacy/requests/" + requireId(input.id) + "/respond", { expectedStatus: input.expectedStatus, status: input.status, message: input.message, actionPerformed: !!input.actionPerformed });message("Resposta registrada para o titular.");return jump("admin_privacy_request", input.id, true); }
      if (operation === "aPrivacyDecide") { if (!input.acknowledged) throw Error("Confira e confirme os efeitos da decisão.");if (!Object.hasOwn(decisions, input.action)) throw Error("Decisão inválida.");await post("/admin/privacy/accounts/" + requireId(input.id) + "/decision", { password: input.password, action: input.action, expectedStatus: input.expectedStatus, reasonCode: input.reasonCode, publicReason: input.publicReason, reason: input.reason, ...(input.action === "ban" ? { restrictionDays: Number(input.restrictionDays) } : {}) });message("Decisão registrada. Confira a situação atual.");return jump("admin_privacy_account", input.id, true); }
      if (operation === "aPrivacyOpenHold") { requireId(input.id);if (current()) reveal("");const data = await post("/admin/privacy/holds/" + input.id + "/evidence", { password: input.password });if (current()) { if (data.hold?.id !== input.id) throw Error("A evidência recebida não corresponde ao caso solicitado.");reveal(evidence(data)); }return; }
      if (operation === "aPrivacyForgetHold") { if (current()) reveal("");return; }
      if (["aPrivacyCreateHold", "aPrivacyReviewHold"].includes(operation)) {
        if (!input.acknowledged) throw Error("Confira e confirme recurso, fundamento e prazos.");
        const creating = operation === "aPrivacyCreateHold";
        if (!creating && !["review", "release"].includes(input.action)) throw Error("Escolha revisar ou liberar pela evidência do caso.");
        const body = { password: input.password, reason: input.reason, caseReference: input.caseReference };
        if (creating) Object.assign(body, { userId: requireId(input.userId.trim()), resourceId: requireId(input.resourceId.trim()), category: input.category, legalBasis: input.legalBasis });else Object.assign(body, { expectedRevision: input.expectedRevision, action: input.action });
        if (creating || input.action === "review") Object.assign(body, { reviewAt: toInstant(input.reviewAt), expiresAt: toInstant(input.expiresAt) });
        const result = await post(creating ? "/admin/privacy/holds" : "/admin/privacy/holds/" + requireId(input.id) + "/review", body);if (current()) { cache(result);if (creating) holdFilter = { status: "", userId: result.userId, offset: 0 }; }message("Decisão de preservação registrada.");return jump("admin_privacy_hold", result.id, true);
      }
      if (operation === "aPrivacyDownloadHold") {
        const blob = await post("/admin/privacy/holds/" + requireId(input.id) + "/data", { password: input.password }, "blob");
        const extension = { "application/json": "json", "application/pdf": "pdf", "image/png": "png", "image/jpeg": "jpg", "image/webp": "webp" }[blob.type.split(";")[0]];
        if (!extension || !blob.size) throw Error("O arquivo recebido não corresponde a uma evidência disponível.");if (!current()) return;
        const url = URL.createObjectURL(blob), anchor = document.createElement("a");downloads.set(url, setTimeout(() => { URL.revokeObjectURL(url);downloads.delete(url); }, 60000));anchor.href = url;anchor.download = "evidencia-privada." + extension;
        try { document.body.append(anchor);anchor.click(); } finally { anchor.remove(); }message("Arquivo preparado. Confira os downloads do navegador e proteja seu conteúdo.");return;
      }
      throw Error("Ação administrativa indisponível.");
    }
    function change(event) {
      if (!["category", "userId"].includes(event.target.name)) return;
      const form = event.target.closest("form"), help = form?.querySelector("[data-a-resource-help]");if (!help) return;
      const selected = form.querySelector('[name="category"]').value, resource = form.querySelector('[name="resourceId"]'), owner = form.querySelector('[name="userId"]').value.trim();
      if (["profile", "contract_identity"].includes(selected)) { resource.readOnly = true;resource.value = uuid(owner) ? owner : "";resource.dataset.ownerResource = "true"; }
      else { resource.readOnly = false;if (resource.dataset.ownerResource) { resource.value = "";delete resource.dataset.ownerResource; } }
      const hint = { profile: "Use o identificador da própria conta.", contract_identity: "Use o identificador da própria conta.", files: "Use o identificador do arquivo privado enviado por este titular.", location: "Use o identificador da rota pertencente ao titular.", vehicle_identity: "Use o identificador do veículo do titular.", driver_application: "Use o identificador da solicitação de motorista deste titular. A preservação não recupera dados já eliminados.", security_audit: "Use o identificador de um evento de segurança deste titular ainda dentro dos seis meses de uso operacional. A preservação guarda uma cópia privada para o caso.", identity_change: "Use o identificador da solicitação de alteração de e-mail.", account_decision: "Use o identificador da decisão sobre a conta.", privacy_request: "Use o protocolo da solicitação de privacidade.", consent: "Use o identificador da manifestação de preferência.", legal: "Use o identificador do recibo de aceite.", notifications: "Use o identificador da notificação do titular." };
      help.textContent = hint[selected] || "Escolha a categoria. Não informe CPF ou número de documento neste campo.";
    }
    function destroy() { disposed = true;metadata.clear();for (const [url, timer] of downloads) { clearTimeout(timer);URL.revokeObjectURL(url); }downloads.clear(); }
    return Object.freeze({ render, perform, change, destroy });
  }
  window.UniCaronaAdminPrivacy = Object.freeze({ create, titles, toInstant });
})();
