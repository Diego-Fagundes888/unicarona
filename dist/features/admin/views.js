/* admin: explicit dependencies and session-owned state. */
(() => {
  "use strict";
  function create({
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
  }) {
    async function admin() {
      if (connectedState.me.role !== "admin")
        return notice("Acesso administrativo necessário.");
      const section = connectedState.ref || "approvals",
        activeSection = section.startsWith("case:")
          ? "cases"
          : section.startsWith("finance:")
            ? "finance"
            : section;
      const tabs = "";
      if (section === "privacy") return adminPrivacy.render("admin_privacy");
      if (section === "jobs") {
        const data = await request(
          "/admin/jobs?status=" +
            connectedState.jobStatus +
            "&" +
            pageQuery("admin_jobs"),
        );
        const states = {
          pending: "Aguardando envio",
          processing: "Em processamento",
          done: "Envio concluído",
          dead: "Precisa de revisão",
        };
        return (
          tabs +
          summary(
            Object.entries(data.summary).map(([key, count]) => [
              states[key],
              count,
            ]),
          ) +
          form(
            "jobFilter",
            select(
              "status",
              "Mostrar tarefas",
              Object.entries(states),
              connectedState.jobStatus,
            ),
            "Filtrar tarefas",
          ) +
          notice(
            "Retomar tenta enviar a mesma tarefa novamente. Uma transferência só termina após a confirmação assinada do provedor. Links de e-mail podem ter vencido; o estudante pode solicitar um novo pelo acesso à conta.",
          ) +
          listMarkup(
            data.items,
            (j) =>
              '<section class="m-review-record"><h2>' +
              esc(
                j.type === "email"
                  ? "Entrega de e-mail"
                  : j.type === "transfer"
                    ? "Envio de transferência"
                    : j.type === "mercadopago_pix"
                      ? "Envio do pagamento com Pix"
                      : j.type === "mercadopago_card"
                        ? "Envio do pagamento com cartão"
                        : "Tarefa para revisão técnica",
              ) +
              "</h2><p>" +
              esc(states[j.status]) +
              "</p>" +
              summary([
                ["Tentativas registradas", j.attempts],
                ["Criada em", UI.dateTime(j.createdAt)],
                [
                  "Próximo envio",
                  j.status === "pending" ? UI.dateTime(j.availableAt) : "—",
                ],
              ]) +
              (j.errorCode
                ? notice(
                    "A tentativa anterior falhou. Confira a disponibilidade do serviço antes de retomar.",
                  )
                : "") +
              (j.status === "dead" && j.type !== "unknown"
                ? form(
                    "retryJob",
                    area("reason", "Justificativa para retomar o envio"),
                    "Retomar envio",
                    { id: j.id, expectedAttempts: j.attempts },
                  )
                : "") +
              "</section>",
            "Nenhuma tarefa neste estado.",
          ) +
          pages(data, "admin_jobs")
        );
      }
      if (["calendar", "pricing"].includes(section))
        return tabs + (await adminOperations(section));
      if (section === "accounts") {
        const offset = connectedState.adminOffsets.accounts || 0,
          params = new URLSearchParams({ limit: "30", offset: String(offset) });
        if (connectedState.accountFilter.q)
          params.set("q", connectedState.accountFilter.q);
        if (connectedState.accountFilter.status)
          params.set("status", connectedState.accountFilter.status);
        const data = await request("/admin/accounts?" + params);
        const filters = form(
          "accountFilter",
          field(
            "q",
            "Buscar por nome ou e-mail",
            connectedState.accountFilter.q,
            "search",
            'maxlength="80"',
          ) +
            select(
              "status",
              "Situação da conta",
              [
                ["", "Todas"],
                ["active", "Ativa"],
                ["suspended", "Em revisão"],
              ],
              connectedState.accountFilter.status,
            ),
          "Buscar contas",
        );
        return (
          tabs +
          notice(
            "Suspensão preventiva impede novas combinações. Trechos não iniciados são interrompidos e suas parcelas seguem análise separada. Restaurar o acesso não reativa ofertas canceladas ou contratos interrompidos.",
          ) +
          filters +
          listMarkup(
            data.items,
            (u) =>
              '<section class="m-review-record"><h2>' +
              esc(u.name) +
              "</h2><p>" +
              esc(u.email) +
              " · " +
              esc(u.university || "Sem faculdade") +
              '</p><p class="m-help">' +
              esc(u.course || "Perfil incompleto") +
              "</p>" +
              badge(u.status) +
              '<div class="m-actions">' +
              go(
                "Privacidade e situação da conta",
                "admin_privacy_account",
                u.id,
                "secondary",
              ) +
              "</div>" +
              '<details class="m-section"><summary>Analisar acesso desta conta</summary>' +
              form(
                "reviewAccount",
                select(
                  "status",
                  "Decisão",
                  [
                    ["suspended", "Suspender preventivamente"],
                    ["active", "Restaurar acesso"],
                  ],
                  u.status === "active" ? "suspended" : "active",
                ) +
                  area("reason", "Orientação e justificativa da decisão") +
                  '<p class="m-help">Esta orientação será enviada ao estudante. Explique a decisão sem identificar quem fez um relato.</p>',
                "Registrar análise",
                { id: u.id, expectedStatus: u.status },
              ) +
              "</details></section>",
            "Nenhuma conta encontrada para esses filtros.",
          ) +
          '<div class="m-actions">' +
          (offset
            ? action(
                "Anterior",
                "adminPage",
                { section, offset: Math.max(0, offset - 30) },
                "quiet",
              )
            : "") +
          (data.nextOffset !== null
            ? action(
                "Próxima página",
                "adminPage",
                { section, offset: data.nextOffset },
                "secondary",
              )
            : "") +
          "</div>"
        );
      }
      if (section === "approvals") {
        const data = await request(
          "/admin/drivers/applications?" + pageQuery("admin_approvals"),
        );
        return (
          tabs +
          listMarkup(
            data.items,
            (a) =>
              '<section class="m-review-record"><h2>' +
              esc(a.name) +
              "</h2>" +
              badge(a.status) +
              summary([
                ["Veículo", a.model + " · " + a.color],
                ["Placa", a.plate],
                ["Vagas", a.capacity],
              ]) +
              driverDocumentsNotice(a) +
              (a.documentsStatus === "available" &&
              a.identityFileId &&
              a.licenseFileId
                ? '<div class="m-actions"><a class="m-button quiet" href="/api/v1/files/' +
                  esc(a.identityFileId) +
                  '" target="_blank" rel="noopener">Identificação</a><a class="m-button quiet" href="/api/v1/files/' +
                  esc(a.licenseFileId) +
                  '" target="_blank" rel="noopener">Habilitação</a></div>'
                : "") +
              (a.status === "pending" && a.documentsStatus === "available"
                ? form(
                    "reviewDriver",
                    select("approve", "Decisão", [
                      ["true", "Aprovar motorista e veículo"],
                      ["false", "Rejeitar e solicitar ajuste"],
                    ]) + area("reason", "Justificativa"),
                    "Registrar decisão",
                    { id: a.id },
                  )
                : a.decision
                  ? notice(a.decision)
                  : ["approved", "rejected"].includes(a.status)
                    ? notice("Decisão registrada.")
                    : "") +
              "</section>",
          ) +
          pages(data, "admin_approvals")
        );
      }
      if (section.startsWith("case:")) {
        const id = section.slice(5),
          offset = connectedState.adminOffsets[section] || 0,
          o = await request(
            "/admin/occurrences/" + id + "?limit=30&offset=" + offset,
          );
        const related = (c) =>
          "<details><summary>" +
          esc(c.id === id ? "Este relato" : labels[c.status] || c.status) +
          " · " +
          esc(labels[c.outcome] || labels[c.kind] || c.kind) +
          "</summary><p>" +
          esc(c.reason) +
          "</p>" +
          (c.decision ? notice(c.decision) : "") +
          "</details>";
        return (
          tabs +
          go("Todas as ocorrências", "admin", "cases", "quiet") +
          '<section class="m-review-record"><h2>Detalhes do relato</h2>' +
          badge(o.status) +
          "<p>" +
          esc(o.reason) +
          "</p>" +
          summary([
            ["Registro feito por", o.participants.reporter.name],
            ["Outra pessoa envolvida", o.participants.other?.name || "—"],
          ]) +
          notice(
            "As decisões abaixo se aplicam aos trechos vinculados a este relato. Parcelas já encerradas preservam sua destinação. Outros casos pendentes mantêm o dinheiro bloqueado.",
          ) +
          listMarkup(
            o.items,
            (p) =>
              '<article class="m-review-record"><h3>' +
              esc(dateLabel(p.date)) +
              " · " +
              esc(p.time) +
              " · " +
              esc(p.direction === "ida" ? "Ida" : "Volta") +
              "</h3>" +
              summary([
                ["Passageiro", p.passengerName],
                ["Motorista", p.driverName],
                ["Tipo", p.monthly ? "Mensal" : "Avulsa"],
                ["Valor contratado", cash(p.cents)],
                ["Parcela", labels[p.status] || p.status],
                ["Trecho", labels[p.rideStatus] || p.rideStatus],
                [
                  "Disponibilidade",
                  labels[p.availability] || p.availability || "Não informada",
                ],
                ["Liberado ao motorista", cash(p.driverCents)],
                ["Comissão lançada", cash(p.commissionCents)],
              ]) +
              "<h4>Relatos e decisões deste trecho</h4>" +
              p.relatedCases.map(related).join("") +
              "</article>",
            "Este relato não possui parcelas vinculadas.",
          ) +
          '<div class="m-actions">' +
          (offset
            ? action(
                "Anterior",
                "adminPage",
                { section, offset: Math.max(0, offset - 30) },
                "quiet",
              )
            : "") +
          (o.nextOffset !== null
            ? action(
                "Próxima página",
                "adminPage",
                { section, offset: o.nextOffset },
                "secondary",
              )
            : "") +
          "</div>" +
          (["pending", "financial_pending"].includes(o.status)
            ? form(
                "reviewCase",
                select(
                  "outcome",
                  "Resultado financeiro",
                  o.items.length
                    ? [
                        ["credited", "Crédito integral ao passageiro"],
                        ["released", "Liberar parcela devida ao motorista"],
                        ["retained", "Retenção de Sem aula posterior"],
                      ]
                    : [["no_financial_change", "Sem alteração financeira"]],
                ) + area("reason", "Justificativa da apuração"),
                "Registrar decisão",
                { id: o.id },
              )
            : notice(o.decision || "Análise registrada.")) +
          "</section>"
        );
      }
      if (section === "cases") {
        const data = await request(
          "/admin/occurrences?" + pageQuery("admin_cases"),
        );
        return (
          tabs +
          listMarkup(
            data.items,
            (o) =>
              '<section class="m-review-record"><h2>Ocorrência · ' +
              esc(UI.dateTime(o.createdAt)) +
              "</h2>" +
              badge(o.status) +
              "<p>" +
              esc(o.reason) +
              "</p>" +
              summary([
                ["Registro feito por", o.participants?.reporter.name || "—"],
                ["Outra pessoa envolvida", o.participants?.other?.name || "—"],
                ["Parcelas afetadas", o.affectedParcels],
              ]) +
              go("Ver trechos e analisar", "admin", "case:" + o.id) +
              "</section>",
          ) +
          pages(data, "admin_cases")
        );
      }
      if (section === "academic") {
        const data = await request(
          "/admin/academic-verifications?" + pageQuery("admin_academic"),
        );
        return (
          tabs +
          listMarkup(
            data.items,
            (p) =>
              '<section class="m-review-record"><h2>' +
              esc(p.name) +
              "</h2><p>" +
              esc(p.university) +
              " · " +
              esc(p.course) +
              " · " +
              esc(p.periodNumber) +
              "º período · " +
              esc(p.classLabel) +
              "</p>" +
              form(
                "reviewAcademic",
                select("approve", "Decisão", [
                  ["true", "Aprovar análise administrativa"],
                  ["false", "Solicitar correção"],
                ]) + area("reason", "Justificativa"),
                "Registrar análise",
                { id: p.id },
              ) +
              "</section>",
          ) +
          pages(data, "admin_academic")
        );
      }
      const configuration = await request("/payments/config");
      if (
        configuration.provider === "mercadopago" &&
        connectedState.ref.startsWith("finance:")
      )
        return tabs + (await financialReviews());
      const r = await request("/admin/reconciliation");
      const reviews =
        configuration.provider === "mercadopago"
          ? await financialReviews()
          : "";
      return (
        tabs +
        notice(
          r.difference === 0
            ? "Os valores recebidos e distribuídos conferem."
            : "Há uma diferença de conciliação. Confira os valores abaixo.",
          r.difference === 0 ? "success" : "error",
        ) +
        summary(
          Object.entries({
            Recebido: cash(r.received),
            Distribuído: cash(r.allocated),
            Diferença: cash(r.difference),
            Futuro: cash(r.future),
            "Em análise": cash(r.blocked),
            "Disponível aos motoristas": cash(r.driver),
            Créditos: cash(r.credit),
            Comissão: cash(r.commission),
            "Retenção Sem aula": cash(r.retained),
            "Em processamento": cash(r.processing),
            Sacado: cash(r.withdrawn),
            Devolvido: cash(r.returned),
          }),
        ) +
        notice(
          "Uma diferença diferente de zero exige investigação. Comissão e retenção são categorias separadas; receita não equivale a lucro líquido.",
        ) +
        reviews
      );
    }

    async function financialReviews() {
      const reasons = (p) =>
        [
          p.chargebackReview && "Contestação de cartão",
          p.untrackedRefunds && "Devolução externa sem conciliação",
          p.historyIncomplete && "Histórico financeiro incompleto",
        ]
          .filter(Boolean)
          .join(" · ");
      const resolutionMarkup = (r, id) => {
        if (!r) return "";
        const messages = {
          NOT_REQUIRED: "Não há conciliação pendente neste pagamento.",
          NO_CONFIRMED_RECEIPT:
            "Não há recebimento confirmado para este ajuste.",
          RECHECK_REQUIRED:
            "Consulte o Mercado Pago para carregar as evidências verificadas.",
          PENDING_REFUND_COMMAND:
            "Há devoluções do passageiro em andamento. Aguarde a confirmação antes de concluir.",
          PENDING_EXTERNAL_REFUND:
            "Há uma devolução externa ainda em processamento.",
          REFUND_HISTORY_INCOMPLETE:
            "O histórico externo está incompleto. Confira todas as devoluções antes de concluir.",
          DEBT_RELEASE_NOT_CONFIRMED:
            "A revisão da dívida ainda não foi confirmada. Consulte novamente o provedor.",
          CREDIT_BALANCE_MISMATCH:
            "O crédito retido não corresponde ao saldo registrado. A divergência exige análise.",
          ADMINISTRATIVE_DECISION_REQUIRED:
            "Este caso exige decisão administrativa. O valor do motorista e a dívida do passageiro não serão alterados automaticamente.",
        };
        if (!r.available)
          return (
            "<h3>Conclusão da análise</h3>" +
            listMarkup(
              r.blockers,
              (code) =>
                "<p>" +
                esc(
                  messages[code] ||
                    "Confira as evidências antes de concluir este caso.",
                ) +
                "</p>",
              "Confira as evidências antes de concluir.",
            )
          );
        return (
          "<h3>Concluir conciliação</h3>" +
          summary(
            Object.entries({
              "Crédito que será ajustado por valor já devolvido": cash(
                r.heldCreditCents,
              ),
              "Dívida já registrada nesta origem": cash(r.registeredDebtCents),
              "Dívida atual do passageiro": cash(r.dueCents),
            }),
          ) +
          '<p class="m-help">O servidor verificará novamente o Mercado Pago. Concluir a análise não quita a dívida; outras pendências do passageiro podem permanecer.</p>' +
          form(
            "resolveFinancialReview",
            area("reason", "Justificativa da conciliação").replace(
              'maxlength="2000"',
              'maxlength="1000"',
            ),
            "Concluir conciliação",
            { id, evidenceKey: r.evidenceKey },
          )
        );
      };
      try {
        if (connectedState.ref.startsWith("finance:")) {
          const id = connectedState.ref.slice(8),
            data = await request("/admin/payment-reviews/" + id),
            p = data.payment;
          return (
            '<section class="m-section"><h2>Revisão do pagamento</h2>' +
            go("Voltar aos casos em análise", "admin", "finance", "secondary") +
            "<p>" +
            esc(p.passengerName) +
            " · " +
            esc(reasons(p) || "Sem bloqueio financeiro registrado") +
            "</p>" +
            notice(
              "Conferir o provedor atualiza as evidências. A consulta não encerra uma contestação nem decide a destinação de um prejuízo.",
            ) +
            summary(
              Object.entries({
                "Valor original da carona": cash(p.originalCents),
                "Recebimento externo original": cash(p.externalCents),
                "Devoluções externas confirmadas": cash(p.refundedCents),
                "Crédito atual do passageiro": cash(data.wallet.credit),
                "Saldo em processamento": cash(data.wallet.processing),
                "Comandos de devolução pendentes": data.pendingRefundCommands,
                "Envios anteriores sem conclusão": data.uncertainRefundCommands,
                "Estado externo verificado":
                  p.providerStatus + " · " + p.statusDetail,
                "Última consulta registrada": p.checkedAt
                  ? new Intl.DateTimeFormat("pt-BR", {
                      dateStyle: "short",
                      timeStyle: "short",
                    }).format(new Date(p.checkedAt))
                  : "Sem consulta registrada",
              }),
            ) +
            '<details class="m-section"><summary>Identificadores para conferência</summary>' +
            summary(
              Object.entries({
                Pagamento: p.paymentId,
                "Order Mercado Pago": p.orderReference,
                Passageiro: p.passengerId,
              }),
            ) +
            "</details>" +
            "<h3>Devoluções observadas no provedor</h3>" +
            listMarkup(
              data.refunds,
              (r) =>
                '<div class="m-row"><div><strong>' +
                cash(r.cents) +
                "</strong><p>" +
                esc(r.status) +
                " · " +
                (r.linked
                  ? "Vinculada a um pedido do app"
                  : r.reconciled
                    ? "Conciliada pela administração"
                    : "Sem vínculo com um pedido do app") +
                '</p><p class="m-help">' +
                esc(r.reference) +
                "</p></div></div>",
              "Nenhuma devolução externa registrada neste pagamento.",
            ) +
            (data.refundsTruncated
              ? notice(
                  "Este resumo mostra as primeiras 100 devoluções. O histórico completo precisa ser conferido na conciliação.",
                )
              : "") +
            (p.errorCode
              ? notice(
                  "A conciliação automática registrou uma falha. Consulte novamente para verificar o caso.",
                )
              : "") +
            (reasons(p)
              ? form(
                  "recheckFinancialReview",
                  area("reason", "Motivo da consulta").replace(
                    'maxlength="2000"',
                    'maxlength="1000"',
                  ),
                  "Consultar novamente no Mercado Pago",
                  { id: p.paymentId },
                )
              : notice("Não há bloqueio financeiro registrado neste caso.")) +
            resolutionMarkup(data.resolution, p.paymentId) +
            "</section>"
          );
        }
        const data = await request(
          "/admin/payment-reviews?" + pageQuery("admin_reviews"),
        );
        return (
          '<section class="m-section"><h2>Casos financeiros em análise</h2>' +
          '<p class="m-help">Confira divergências externas antes de decidir sobre crédito ou devolução. A conservação do ledger acima não comprova o saldo disponível no Mercado Pago.</p>' +
          listMarkup(
            data.items,
            (p) =>
              '<div class="m-row"><div><strong>' +
              esc(p.passengerName) +
              "</strong><p>" +
              esc(reasons(p)) +
              '</p><p class="m-help">Recebimento original: ' +
              cash(p.externalCents) +
              " · Devolvido no provedor: " +
              cash(p.refundedCents) +
              "</p></div>" +
              go(
                "Examinar caso",
                "admin",
                "finance:" + p.paymentId,
                "secondary",
              ) +
              "</div>",
            "Nenhum caso financeiro exige revisão neste momento.",
          ) +
          pages(data, "admin_reviews") +
          "</section>"
        );
      } catch (error) {
        return (
          '<section class="m-section"><h2>Revisões financeiras</h2>' +
          notice(
            connectedState.ref.startsWith("finance:")
              ? "Não foi possível consultar este caso financeiro. Tente novamente."
              : "Não foi possível consultar os casos financeiros. Tente novamente; os saldos acima vêm de uma consulta separada.",
          ) +
          action("Tentar consultar novamente", "reload", {}, "secondary") +
          "</section>"
        );
      }
    }

    async function adminOperations(section) {
      connectedState.catalog ||= await request("/catalogs");
      connectedState.adminUniversity ||=
        connectedState.catalog.universities[0]?.id;
      if (!connectedState.adminUniversity)
        return notice("Cadastre uma faculdade antes de configurar a operação.");
      const campus = form(
        "adminUniversity",
        select(
          "universityId",
          "Faculdade",
          connectedState.catalog.universities.map((u) => [u.id, u.name]),
          connectedState.adminUniversity,
        ),
        "Selecionar faculdade",
        { section },
      );
      const offset = connectedState.adminOffsets[section] || 0;
      function pages(data) {
        return (
          '<div class="m-actions">' +
          (offset
            ? action(
                "Anterior",
                "adminPage",
                { section, offset: Math.max(0, offset - 30) },
                "quiet",
              )
            : "") +
          (data.nextOffset !== null
            ? action(
                "Próxima página",
                "adminPage",
                { section, offset: data.nextOffset },
                "secondary",
              )
            : "") +
          "</div>"
        );
      }
      if (section === "pricing") {
        const [latest, history] = await Promise.all([
          request(
            "/admin/pricing?universityId=" +
              connectedState.adminUniversity +
              "&limit=1",
          ),
          request(
            "/admin/pricing?universityId=" +
              connectedState.adminUniversity +
              "&limit=30&offset=" +
              offset,
          ),
        ]);
        const p = latest.items[0];
        if (!p)
          return (
            campus +
            notice("Esta faculdade ainda não possui parâmetros iniciais.")
          );
        return (
          campus +
          notice(
            "Novas versões afetam novas publicações e edições de ofertas. As ofertas existentes e as parcelas já contratadas mantêm seus valores. O desconto mensal permanece conforme a regra vigente.",
          ) +
          '<div class="m-grid m-section"><section class="m-admin-form"><h2>Nova versão de parâmetros</h2>' +
          form(
            "savePricing",
            field(
              "fuel",
              "Combustível em R$/litro",
              p.fuelCents / 100,
              "number",
              'required min="0.10" max="100" step="0.01"',
            ) +
              field(
                "efficiency",
                "Consumo em km/l",
                p.efficiency,
                "number",
                'required min="1" max="100" step="0.001"',
              ) +
              field(
                "wear",
                "Desgaste em R$/km",
                p.wearCents / 100,
                "number",
                'required min="0" max="20" step="0.01"',
              ) +
              field(
                "referencePassengers",
                "Passageiros de referência",
                p.referencePassengers,
                "number",
                'required min="1" max="8" step="1"',
              ) +
              field(
                "commission",
                "Comissão em %",
                p.commissionBps / 100,
                "number",
                'required min="0" max="95" step="0.01"',
              ) +
              area("reason", "Justificativa da atualização"),
            "Salvar nova versão",
            {
              universityId: connectedState.adminUniversity,
              previousVersionId: p.id,
            },
          ) +
          "</section><section><h2>Histórico de parâmetros</h2>" +
          listMarkup(
            history.items,
            (v) =>
              '<article class="m-review-record"><h3>' +
              esc(UI.dateTime(v.createdAt)) +
              "</h3>" +
              summary([
                ["Combustível", cash(v.fuelCents) + "/litro"],
                ["Consumo", v.efficiency + " km/l"],
                ["Desgaste", cash(v.wearCents) + "/km"],
                ["Passageiros de referência", v.referencePassengers],
                ["Comissão", v.commissionBps / 100 + "%"],
                ["Desconto mensal", v.discountBps / 100 + "%"],
              ]) +
              "</article>",
          ) +
          pages(history) +
          "</section></div>"
        );
      }
      const params = new URLSearchParams({
          universityId: connectedState.adminUniversity,
          ...connectedState.calendarRange,
          limit: "30",
          offset: String(offset),
        }),
        data = await request("/admin/calendar?" + params);
      const courses = connectedState.catalog.courses
        .filter((c) => c.university_id === connectedState.adminUniversity)
        .map((c) => [c.id, c.name]);
      const creation = form(
        "addCalendar",
        field(
          "date",
          "Data sem aula",
          today(),
          "date",
          'required min="' + today() + '"',
        ) +
          field(
            "title",
            "Nome ou motivo da data",
            "",
            "text",
            'required maxlength="200"',
          ) +
          select("scope", "A quem se aplica?", [
            ["university", "Toda a faculdade"],
            ["class", "Uma turma específica"],
          ]) +
          "<div data-c-class-scope hidden>" +
          select("courseId", "Curso", courses) +
          field(
            "periodNumber",
            "Número do período",
            1,
            "number",
            'disabled required min="1" max="100" step="1"',
          ) +
          select("classLabel", "Turma", [
            ["A", "Turma A"],
            ["B", "Turma B"],
            ["C", "Turma C"],
          ]) +
          "</div>",
        "Cadastrar data",
        { universityId: connectedState.adminUniversity },
      );
      const range = form(
        "calendarRange",
        field(
          "startDate",
          "Consultar a partir de",
          connectedState.calendarRange.startDate,
          "date",
          "required",
        ) +
          field(
            "endDate",
            "Consultar até",
            connectedState.calendarRange.endDate,
            "date",
            "required",
          ),
        "Consultar calendário",
      );
      return (
        campus +
        notice(
          "Feriados e recessos conhecidos ficam fora de novas contratações mensais. Cadastrar uma data não altera contratos pagos nem registra automaticamente Sem aula posterior. Esse registro é feito por trecho, com aviso e contestação.",
        ) +
        '<div class="m-grid m-section"><section class="m-admin-form"><h2>Cadastrar data conhecida</h2>' +
        creation +
        "</section><section><h2>Calendário e histórico</h2>" +
        range +
        listMarkup(
          data.items,
          (e) =>
            '<article class="m-review-record"><h3>' +
            esc(dateLabel(e.date)) +
            " · " +
            esc(e.title) +
            "</h3><p>" +
            esc(
              e.classId
                ? e.course +
                    " · " +
                    e.periodNumber +
                    "º período · Turma " +
                    e.classLabel
                : "Toda a faculdade",
            ) +
            "</p>" +
            badge(e.active ? "active" : "ended") +
            (e.active
              ? '<details class="m-section"><summary>Desativar esta exclusão</summary>' +
                form(
                  "deactivateCalendar",
                  area("reason", "Justificativa da desativação"),
                  "Desativar data",
                  { id: e.id },
                ) +
                "</details>"
              : '<p class="m-help">O registro foi preservado no histórico.</p>') +
            "</article>",
          "Nenhuma data cadastrada nesse intervalo.",
        ) +
        pages(data) +
        "</section></div>"
      );
    }
    return { admin, financialReviews, adminOperations };
  }
  window.UniCaronaFeatures ||= {};
  window.UniCaronaFeatures.admin = Object.freeze({ create });
})();
