/* Account-owned privacy journeys. Server responses decide state, deadlines and outcomes. */
(() => {
  "use strict";
  const UI = window.UniCaronaUI, L = window.UniCaronaLegal, esc = UI.escape;
  const titles = Object.freeze({ privacy: "Privacidade e dados", privacy_documents: "Documentos e meus aceites", privacy_requests: "Solicitações de privacidade", privacy_request: "Sua solicitação de privacidade", privacy_export: "Baixar meus dados", privacy_account: "Desativar ou reativar conta", privacy_close: "Excluir minha conta", privacy_identity: "Corrigir dados de acesso", privacy_location: "Localização e compartilhamento", privacy_retentions: "Retenções específicas" });
  const kindNames = { access: "Acesso aos dados", correction: "Correção", erasure: "Eliminação de dados", restriction: "Restrição de tratamento", opposition: "Oposição", portability: "Portabilidade", appeal: "Revisão de decisão", other: "Outro direito" };
  const states = { active: "Conta ativa", deactivated: "Conta desativada", suspended: "Conta em revisão", banned: "Conta com restrição", closing: "Encerramento em andamento", received: "Recebida", in_review: "Em análise", awaiting_information: "Aguardando informações", completed: "Concluída", denied: "Pedido não atendido", scheduled: "Exclusão programada", pending_obligations: "Aguardando operações em andamento", erasing: "Remoção em processamento", cancelled: "Pedido cancelado", review_due: "Revisão prevista", expired: "Prazo encerrado", released: "Preservação liberada" };
  const categories = { profile: "Perfil", files: "Arquivos", location: "Localização", contract_identity: "Identificação contratual", vehicle_identity: "Identificação do veículo", driver_application: "Solicitação de motorista", security_audit: "Evento de segurança", identity_change: "Correção de dados", account_decision: "Decisão sobre conta", privacy_request: "Solicitação de privacidade", consent: "Manifestação de preferência", legal: "Aceites contratuais", notifications: "Notificações" };
  const bases = { legal_obligation: "Obrigação legal específica", regular_exercise_of_rights: "Exercício regular de direitos", contract: "Execução de contrato", security: "Finalidade de segurança" };
  const balances = { passenger_credit: "Crédito do passageiro", passenger_debt: "Dívida do passageiro", driver_available: "Disponível ao motorista", driver_receivable: "A receber pelo motorista", future: "Valores de trechos futuros", blocked: "Valores em análise" };
  const uuid = value => typeof value === "string" && /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i.test(value);
  const box = html => '<div class="p-page">' + html + '</div>';
  const publicContact = () => '<p class="m-help">Contato de privacidade: <a href="mailto:' + esc(L.contact) + '">' + esc(L.contact) + '</a>. Não envie senhas ou códigos de pagamento.</p>';
  const stamp = value => esc(UI.dateTime(value));
  const retentionStates = { active: "Preservação ativa", review_due: "Revisão pendente", released: "Preservação liberada", expired: "Preservação vencida" };
  const badge = (status, labels = states) => '<span class="m-badge">' + esc(labels[status] || "Situação em atualização") + '</span>';
  const password = () => UI.field("password", "Confirme sua senha", "", "password", 'required maxlength="128" autocomplete="current-password"', "Sua senha confirma que esta ação foi solicitada por você.");
  function create({ request, navigate, refreshProfile, tell, navigationVersion = () => 0 }) {
    const fileUrls = new Map();
    const go = (text, view, ref = "") => UI.button(text, { "data-c-go": view, "data-ref": ref }, "secondary");
    const action = (text, operation, data = {}) => UI.button(text, { "data-c-action": operation, "data-input": JSON.stringify(data) }, "secondary");
    const section = (title, html) => '<section class="p-section"><h2>' + esc(title) + '</h2>' + html + '</section>';
    const linkRow = (title, description, route, destructive = false) => '<li><h3>' + esc(title) + '</h3><p class="m-muted">' + esc(description) + '</p>' + UI.button(title, { "data-c-go": route }, destructive ? "quiet p-destructive" : "secondary") + '</li>';
    const pageOffset = ref => /^\d{1,4}$/.test(ref || "") && Number(ref) <= 5000 ? Number(ref) : 0;
    const pager = (data, route, offset) => '<div class="m-actions">' + (offset ? go("Anteriores", route, String(Math.max(0, offset - 30))) : '') + (Number.isInteger(data.nextOffset) && data.nextOffset <= 5000 ? go("Próximos", route, String(data.nextOffset)) : '') + '</div>';
    const ownState = async () => request("/privacy/account");
    async function home() {
      const [account, prefs] = await Promise.all([ownState(), request("/privacy/preferences")]);
      return box('<p>Consulte seus documentos, controle comunicações e exerça seus direitos sobre os dados.</p>' + '<div class="p-state"><h2>' + esc(states[account.status] || "Sua conta") + '</h2><p>Desativação, restrição e exclusão têm efeitos diferentes. Seus valores e solicitações continuam sendo tratados.</p>' + (account.closure?.id && !["cancelled", "completed"].includes(account.closure.status) ? go("Acompanhar exclusão", "privacy_close") : '') + '</div>' +
        section("Seus dados e documentos", '<ul class="p-link-list">' + linkRow("Documentos e meus aceites", "Leia as versões publicadas e consulte os documentos vinculados aos seus aceites.", "privacy_documents") + linkRow("Corrigir dados de acesso", "Revise e-mail e nascimento com confirmação de senha. Outros dados podem ser editados no perfil.", "privacy_identity") + linkRow("Baixar meus dados", "Receba um arquivo com seus dados e documentos próprios. É necessário confirmar a senha.", "privacy_export") + linkRow("Solicitações de privacidade", "Peça acesso, correção, eliminação ou revisão e acompanhe a resposta pelo protocolo.", "privacy_requests") + '</ul>') +
        section("Comunicações", '<p>Mensagens necessárias de acesso, segurança, pagamento e carona continuam independentes desta preferência.</p>' + UI.form("privacyMarketing", UI.check("marketing", "Quero receber novidades e comunicações promocionais", prefs.marketing) + '<p class="m-help">' + esc(prefs.statement) + '</p>', "Salvar preferência", { statementVersion: prefs.statementVersion })) +
        section("Uso e preservação", '<ul class="p-link-list">' + linkRow("Localização e compartilhamento", "Entenda quando outra pessoa pode ver informações para combinar a viagem.", "privacy_location") + linkRow("Retenções específicas", "Consulte preservações por caso e seus prazos de revisão.", "privacy_retentions") + '</ul>') +
        section("Sua conta", '<ul class="p-link-list">' + linkRow("Desativar ou reativar conta", "Interrompa novas combinações preservando a conta e os registros.", "privacy_account") + linkRow("Excluir minha conta", "Consulte consequências, prazo e operações em andamento antes de confirmar.", "privacy_close", true) + '</ul>') + publicContact());
    }
    async function documents(ref) {
      const offset = pageOffset(ref);
      const [manifest, status, history] = await Promise.allSettled([L.manifest(true), request("/privacy/legal"), request("/privacy/legal/history?limit=30&offset=" + offset)]);
      for (const result of [manifest, status, history]) if (result.status === "rejected" && result.reason.status === 401) throw result.reason;
      let html = '<p>Seus aceites ficam vinculados à versão indicada. Ler uma página não registra aceite nem ativa marketing.</p>';
      if (manifest.status === "fulfilled" && status.status === "fulfilled") {
        const data = manifest.value;
        html += L.documentLinks(data, "service");
        const driverDoc = data.documents.find(d => d.reference.slug === "driver-terms");
        html += '<p><a href="' + esc(L.href(driverDoc.reference)) + '" target="_blank" rel="noopener">Ler os Termos do Motorista</a></p>';
        if (status.value.required.length || status.value.driverRequired.length) html += section("Registrar aceites", UI.form("privacyAccept", UI.check("terms", "Li e aceito os Termos de Uso e as regras de participação, pagamentos e segurança", false) + UI.check("privacyNotice", "Li a Política de Privacidade, Retenção e Cookies", false) + UI.check("driverTerms", "Também li e aceito os Termos do Motorista para oferecer caronas", false) + '<p class="m-help">O aceite do motorista é separado. Estes controles não autorizam comunicações promocionais.</p>', "Registrar estes aceites", { documents: L.references(data, "service"), driverDocument: driverDoc.reference }));
        else html += UI.notice("Você já possui os aceites atualmente exigidos para usar e oferecer o serviço.");
      } else html += UI.notice("Não foi possível consultar os documentos atuais. Tente novamente ou use o contato de privacidade.");
      html += section("Histórico dos meus aceites", history.status === "fulfilled" ? (history.value.items.length ? history.value.items.map(a => '<article class="p-result"><h3><a href="' + esc(L.href(a.document)) + '" target="_blank" rel="noopener">' + esc(L.titles[a.document.slug]) + '</a></h3><p class="p-protocol">Versão ' + esc(a.document.version) + '</p><p class="m-help">Registrado em ' + stamp(a.acceptedAt) + (a.kind === "privacy_notice" ? ' · Ciência do aviso de privacidade' : ' · Aceite contratual') + '</p></article>').join("") + pager(history.value, "privacy_documents", offset) : '<p>Nenhum aceite registrado. Isso não significa que documentos antigos foram aceitos por você.</p>') : '<p>Não foi possível carregar seu histórico. Tente novamente.</p>');
      return box(html + publicContact());
    }
    async function protocols(ref) {
      const offset = pageOffset(ref), data = await request("/privacy/requests?limit=30&offset=" + offset);
      return box('<p>Descreva o direito que deseja exercer. Não inclua senhas, códigos de acesso ou dados desnecessários de outras pessoas.</p>' + UI.form("privacyRequest", UI.select("kind", "O que você precisa?", Object.entries(kindNames), "access") + UI.area("details", "Explique sua solicitação"), "Enviar solicitação") + section("Meus protocolos", data.items.length ? data.items.map(item => '<article class="p-result"><h3>' + esc(kindNames[item.kind] || "Solicitação") + '</h3>' + badge(item.status) + '<p class="m-help">Recebida em ' + stamp(item.createdAt) + ' · Previsão de resposta: ' + stamp(item.dueAt) + '</p><p class="p-protocol">Protocolo ' + esc(item.id) + '</p>' + go("Ver resposta e detalhes", "privacy_request", item.id) + '</article>').join("") + pager(data, "privacy_requests", offset) : '<p>Quando você enviar um pedido, o protocolo e as respostas aparecerão aqui.</p>') + publicContact());
    }
    async function protocol(ref) {
      if (!uuid(ref)) throw Error("Protocolo inválido. Volte à lista de solicitações.");
      const data = await request("/privacy/requests/" + ref), item = data.request;
      return box('<h2>' + esc(kindNames[item.kind] || "Solicitação") + '</h2>' + badge(item.status) + '<p class="p-protocol">Protocolo ' + esc(item.id) + '</p><p class="m-help">Recebida em ' + stamp(item.createdAt) + ' · Previsão de resposta: ' + stamp(item.dueAt) + '</p>' + section("Seu pedido", '<p class="p-private-response">' + esc(data.details) + '</p>') + section("Respostas", data.responses.length ? data.responses.map(r => '<article class="p-result"><p class="m-help">' + stamp(r.createdAt) + '</p><p class="p-private-response">' + esc(r.message) + '</p></article>').join("") : '<p>Você ainda não recebeu uma resposta. Consulte este protocolo para acompanhar.</p>') + go("Ver minhas solicitações", "privacy_requests") + publicContact());
    }
    async function closure() {
      const c = await request("/privacy/closure"), open = c.id && ["scheduled", "pending_obligations", "erasing"].includes(c.status);
      let html = '<p>A exclusão remove o perfil e os dados dispensáveis. Contratos, valores e evidências que ainda precisam ser tratados seguem separados, com acesso restrito.</p>';
      if (c.id && c.status === "cancelled") html += '<div class="p-state"><h2>Pedido de exclusão cancelado</h2><p>Este pedido foi cancelado antes da remoção. Isso não reativa ofertas, renovação ou preferências nem altera restrições de segurança.</p><p class="p-protocol">Protocolo cancelado ' + esc(c.id) + '</p></div>';
      if (open) html += '<div class="p-state"><h2>' + esc(states[c.status] || "Pedido em andamento") + '</h2><p>Solicitado em ' + stamp(c.requestedAt) + '. Processamento a partir de ' + stamp(c.eraseAfter) + ', conforme as operações em andamento.</p><p class="p-protocol">Protocolo ' + esc(c.id) + '</p>' + (c.initiatedBy === "platform" ? '<p>Este encerramento foi iniciado pela plataforma. Você pode solicitar revisão pelo canal de privacidade.</p>' + go("Pedir revisão", "privacy_requests") : '') + '</div>';
      else html += '<ul class="p-details-list"><li>Seu perfil deixa de participar de novas combinações; ofertas e renovações são pausadas.</li><li>Há uma janela de segurança de ' + esc(c.graceDays) + ' dias. Você pode cancelar antes do processamento irreversível.</li><li>Viagens e cobranças em andamento precisam ser tratadas. Exclusão não quita dívida nem elimina seu direito a valores.</li><li>Documentos e dados necessários a uma obrigação ou caso específico podem ser preservados pelo prazo pertinente.</li></ul>';
      html += section("Operações e valores", (c.obligations.length ? '<ul class="p-details-list">' + c.obligations.map(o => '<li>' + esc(o.label) + ': ' + esc(o.count) + (o.delaysErasure ? '. Pode adiar o processamento da exclusão.' : '. Segue em análise separada.') + '</li>').join("") + '</ul>' : '<p>Nenhuma operação que adie o processamento foi indicada neste resumo.</p>') + '<dl class="p-summary">' + Object.entries(c.balances).map(([key, value]) => '<div><dt>' + esc(balances[key] || "Valor registrado em conta") + '</dt><dd>' + UI.money(value) + '</dd></div>').join("") + '</dl><p class="m-help">Valores e operações são conferidos novamente pelo servidor. Este resumo não é uma quitação financeira.</p><div class="m-actions">' + go("Minhas caronas", "minhas") + go("Carteira", "wallet") + '</div>');
      if (open && c.canCancel) html += section("Cancelar o pedido de exclusão", '<p>Isso preserva a conta. Restrições de segurança, ofertas pausadas e renovação automática não são reativadas por este cancelamento.</p>' + UI.form("privacyCancelClose", password(), "Cancelar pedido de exclusão"));
      if (!open && c.status !== "completed") html += section(c.status === "cancelled" ? "Solicitar uma nova exclusão" : "Confirmar exclusão", UI.form("privacyClose", password() + UI.check("acknowledged", "Li as consequências, o prazo e as operações e quero excluir minha conta", false), c.status === "cancelled" ? "Solicitar nova exclusão da conta" : "Solicitar exclusão da minha conta", { policyVersion: c.policyVersion }));
      if (c.status === "erasing") html += UI.notice("A remoção já começou e o pedido não pode mais ser cancelado. Arquivos com falha são tratados novamente antes da conclusão.");
      return box(html + '<div class="m-actions">' + go("Atualizar resumo", "privacy_close") + go("Voltar à privacidade", "privacy") + '</div>' + publicContact());
    }
    async function account() {
      const a = await ownState();let html = '<h2>' + esc(states[a.status] || "Sua conta") + '</h2>';
      if (a.status === "active") html += '<p>Desativar interrompe novas combinações e preserva seus dados. Viagens já pagas e valores continuam sendo tratados; você poderá solicitar reativação.</p>' + UI.form("privacyDeactivate", password() + UI.check("acknowledged", "Entendi que desativar preserva minha conta e os registros existentes", false), "Desativar minha conta");
      else if (a.canReactivate) html += '<p>Reativar não retoma automaticamente ofertas pausadas, renovação ou marketing. Uma restrição administrativa continua exigindo análise.</p>' + UI.form("privacyReactivate", password(), "Reativar minha conta");
      else html += '<p>A situação atual não permite reativação por esta opção. Consulte o motivo e solicite revisão quando necessário.</p>' + go("Solicitar revisão", "privacy_requests");
      html += section("Decisões comunicadas", a.decisions.length ? a.decisions.map(d => '<article class="p-result"><p class="m-help">' + stamp(d.createdAt) + '</p><p class="p-private-response">' + esc(d.message) + '</p></article>').join("") : '<p>Nenhuma decisão administrativa foi informada nesta consulta.</p>');
      return box(html + '<div class="m-actions">' + go("Acompanhar ou solicitar exclusão", "privacy_close") + '</div>' + publicContact());
    }
    async function identity() {
      const [me, state] = await Promise.all([request("/auth/me"), request("/privacy/identity")]);
      return box('<p>Para nome, telefone e dados acadêmicos, use a edição do perfil. E-mail e nascimento exigem confirmação de senha.</p>' + go("Editar perfil", "perfil") + section("E-mail de acesso", '<p>Endereço atual: <strong>' + esc(me.email) + '</strong>.</p>' + (state.pendingEmail ? '<div class="p-state"><p>Troca solicitada para ' + esc(state.pendingEmail.maskedEmail) + '. O link vence em ' + stamp(state.pendingEmail.expiresAt) + '.</p><p>Abra o link no novo endereço para confirmar. O endereço atual continua valendo até a conclusão.</p>' + UI.form("privacyCancelEmail", password(), "Cancelar troca de e-mail") + '</div>' : '') + UI.form("privacyEmail", UI.field("email", "Novo e-mail", "", "email", 'required maxlength="254" autocomplete="email"') + password() + '<p class="m-help">O link enviado ao novo endereço dura 30 minutos. Ao concluir, as sessões serão encerradas.</p>', "Solicitar troca de e-mail")) + section("Data de nascimento", UI.form("privacyBirth", UI.field("birth", "Data de nascimento correta", me.birth || "", "date", 'required autocomplete="bday"') + password() + '<p class="m-help">Se a correção indicar menos de 18 anos, o uso das caronas será restringido. Valores e direitos continuarão sendo tratados.</p>', "Corrigir nascimento")) + publicContact());
    }
    async function retentions(ref) {
      const offset = pageOffset(ref), data = await request("/privacy/retentions?limit=30&offset=" + offset);
      return box('<p>Estas são preservações específicas por caso. A política também descreve os demais prazos de conta, contrato e registros operacionais.</p>' + (data.items.length ? data.items.map(r => '<article class="p-result"><h2>' + esc(categories[r.category] || "Dados do caso") + '</h2><p>' + esc(bases[r.legalBasis] || "Fundamento específico informado no atendimento") + '</p>' + badge(r.status, retentionStates) + '<dl class="p-summary"><div><dt>Revisão prevista</dt><dd>' + stamp(r.reviewAt) + '</dd></div><div><dt>Vencimento desta preservação</dt><dd>' + stamp(r.expiresAt) + '</dd></div></dl></article>').join("") + pager(data, "privacy_retentions", offset) : '<p>Nenhuma retenção específica foi informada nesta consulta. Isso não significa que todos os registros da conta já foram apagados.</p>') + '<a href="legal.html?document=privacy" target="_blank" rel="noopener">Ler a política de retenção</a>' + publicContact());
    }
    async function render(route, ref = "") {
      switch (route) {
        case "privacy": return home();
        case "privacy_documents": return documents(ref);
        case "privacy_requests": return protocols(ref);
        case "privacy_request": return protocol(ref);
        case "privacy_close": return closure();
        case "privacy_account": return account();
        case "privacy_identity": return identity();
        case "privacy_retentions": return retentions(ref);
        case "privacy_export": return box('<p>O arquivo ZIP reúne dados da sua conta e documentos próprios disponíveis. Dados de outras pessoas e credenciais não fazem parte da exportação comum.</p>' + UI.notice("O arquivo pode conter informações pessoais. Guarde-o em um local protegido e evite compartilhá-lo.") + UI.form("privacyExport", password(), "Baixar meus dados") + publicContact());
        case "privacy_location": return box('<h2>Combinar a viagem</h2><p>Você informa região e ponto de encontro. O aplicativo não implementa rastreamento GPS ao vivo da viagem.</p><p>A busca compara trajeto e horário. Como passageiro, você pode conferir o ponto exato e as observações antes de pagar, depois que o motorista aceitar sua solicitação e enquanto ela for válida.</p><p>Em uma reserva confirmada ou com embarque registrado, telefone e foto atual da outra pessoa ficam disponíveis para organizar a viagem. Para o passageiro, isso também inclui a placa do veículo. O acesso depende de a carona estar prevista ou em andamento e termina, no máximo, 48 horas após a saída prevista. Encerramento, cancelamento ou bloqueio entre vocês podem interrompê-lo antes.</p><p>Um contrato antigo não permite consultar indefinidamente o contato ou o perfil atual de outra pessoa.</p>' + section("Mapa e serviços externos", '<p>Ao carregar o mapa, seu navegador consulta o provedor de mapas, que recebe informações de rede, como IP. Isso não equivale ao acompanhamento contínuo do seu trajeto.</p><a href="legal.html?document=privacy" target="_blank" rel="noopener">Consultar uso, compartilhamento e retenção</a>') + publicContact());
        default: throw Error("Opção de privacidade não encontrada.");
      }
    }
    async function perform(operation, input) {
      const version = navigationVersion();
      const jump = (...args) => navigationVersion() === version ? navigate(...args) : undefined;
      const message = text => { if (navigationVersion() === version) tell(text); };
      const post = (path, body) => request(path, { method: "POST", body });
      const refresh = async () => { try { await refreshProfile(); } catch { message("A ação foi recebida. Atualize a consulta para conferir a situação da conta."); } };
      if (operation === "privacyMarketing") { await request("/privacy/preferences", { method: "PATCH", body: { marketing: !!input.marketing, statementVersion: input.statementVersion } });message("Preferência de comunicação salva.");return; }
      if (operation === "privacyRequest") { const result = await post("/privacy/requests", { kind: input.kind, details: input.details });return jump("privacy_request", result.id); }
      if (operation === "privacyAccept") {
        if (!input.terms || !input.privacyNotice) throw Error("Leia os documentos e marque os dois controles obrigatórios para registrar os aceites.");
        await post("/privacy/legal", { terms: true, privacyNotice: true, driverTerms: !!input.driverTerms, documents: input.driverTerms ? [...input.documents, input.driverDocument] : input.documents });message("Aceites registrados para as versões indicadas.");return jump("privacy_documents", "", true);
      }
      if (operation === "privacyExport") {
        const blob = await request("/privacy/export", { method: "POST", body: { password: input.password }, format: "blob" });
        if (blob.type.split(";")[0] !== "application/zip" || blob.size < 22) throw Error("O arquivo recebido não é uma exportação válida. Tente novamente.");
        const signature = new Uint8Array(await blob.slice(0, 4).arrayBuffer());
        if (signature[0] !== 0x50 || signature[1] !== 0x4b || !((signature[2] === 3 && signature[3] === 4) || (signature[2] === 5 && signature[3] === 6))) throw Error("O arquivo recebido não é uma exportação válida. Tente novamente.");
        if (navigationVersion() !== version) return;
        const url = URL.createObjectURL(blob), anchor = document.createElement("a");
        fileUrls.set(url, setTimeout(() => { URL.revokeObjectURL(url);fileUrls.delete(url); }, 60000));
        anchor.href = url;anchor.download = "unicarona-meus-dados.zip";
        try { document.body.append(anchor);anchor.click(); } finally { anchor.remove(); }
        message("Arquivo preparado. Confira os downloads do navegador.");return;
      }
      if (operation === "privacyClose") {
        if (!input.acknowledged) throw Error("Leia as consequências e confirme sua intenção de excluir a conta.");
        const result = await post("/privacy/closure", { password: input.password, acknowledged: true, policyVersion: input.policyVersion });message("Pedido de exclusão recebido. Protocolo " + result.id + ".");await refresh();return jump("privacy_close", "", true);
      }
      if (operation === "privacyCancelClose") { await post("/privacy/closure/cancel", { password: input.password });await refresh();message("Pedido de exclusão cancelado. Consulte as restrições e preferências da conta.");return jump("privacy_close", "", true); }
      if (operation === "privacyDeactivate") {
        if (!input.acknowledged) throw Error("Confirme que desativar preserva a conta e seus registros.");
        await post("/privacy/account/deactivate", { password: input.password, acknowledged: true });await refresh();return jump("privacy_account", "", true);
      }
      if (operation === "privacyReactivate") { await post("/privacy/account/reactivate", { password: input.password });await refresh();return jump("privacy_account", "", true); }
      if (operation === "privacyEmail") { const result = await post("/privacy/identity/email", { email: input.email, password: input.password });message(result.message);return jump("privacy_identity", "", true); }
      if (operation === "privacyCancelEmail") { const result = await post("/privacy/identity/email/cancel", { password: input.password });message(result.message);return jump("privacy_identity", "", true); }
      if (operation === "privacyBirth") { const result = await post("/privacy/identity/birth", { birth: input.birth, password: input.password });await refresh();message(result.message);return jump("privacy_identity", "", true); }
      throw Error("Ação de privacidade indisponível. Atualize a página.");
    }
    const destroy = () => { for (const [url, timer] of fileUrls) { clearTimeout(timer);URL.revokeObjectURL(url); }fileUrls.clear(); };
    return Object.freeze({ render, perform, destroy });
  }
  window.UniCaronaPrivacy = Object.freeze({ create, titles });
})();
