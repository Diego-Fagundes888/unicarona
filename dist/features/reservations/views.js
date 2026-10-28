/* reservations: explicit dependencies and session-owned state. */
(() => {
  "use strict";
  function create({
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
  }) {
    async function requestView() {
      const q = await request("/ride-requests/" + connectedState.ref);
      connectedState.current = q;
      const mine = q.passengerId === connectedState.me.id;
      const primary =
        !mine && q.status === "pending"
          ? action("Aceitar pedido", "respondRequest", {
              id: q.id,
              accept: true,
            }) +
            action(
              "Recusar pedido",
              "respondRequest",
              { id: q.id, accept: false },
              "quiet",
            )
          : mine && q.paymentId && q.status === "accepted"
            ? go("Ir para pagamento", "checkout", q.paymentId)
            : q.status === "confirmed"
              ? go("Ver minhas reservas", "minhas", "", "secondary")
              : "";
      const explanations = {
        pending: mine
          ? "O pedido foi enviado. Aguarde o aceite do motorista antes de pagar."
          : "Confira a agenda e o valor antes de responder. A vaga só fica confirmada após o pagamento.",
        accepted: mine
          ? "Seu pedido foi aceito. Conclua o pagamento dentro do prazo para confirmar a vaga."
          : "O pedido foi aceito e aguarda o pagamento do passageiro.",
        confirmed:
          "O pagamento e a disponibilidade foram verificados. Consulte suas reservas para acompanhar os trechos.",
        cancelled:
          "Este pedido foi cancelado. Consulte suas caronas para continuar.",
        expired:
          "O prazo deste pedido terminou. Você pode buscar outra oferta.",
      };
      return (
        '<div class="m-grid"><div>' +
        badge(q.status) +
        '<section class="m-section">' +
        UI.person(
          q.otherName,
          q.type === "mensal" ? "Plano mensal" : "Carona avulsa",
        ) +
        "</section>" +
        summary([
          ["Total", cash(q.totalCents)],
          ["Prazo", UI.dateTime(q.expiresAt)],
        ]) +
        UI.disclosure(
          "Agenda solicitada · " + q.lines.length + " trecho(s)",
          listMarkup(
            q.lines,
            (l) =>
              '<div class="m-row"><span>' +
              esc(dateLabel(l.date)) +
              " · " +
              esc(l.time) +
              " · " +
              esc(l.direction === "ida" ? "Ida" : "Volta") +
              "</span><strong>" +
              cash(l.cents) +
              "</strong></div>",
          ),
          q.lines.length > 3 ? "" : "open",
        ) +
        '</div><aside class="m-booking-panel"><h2>Próximo passo</h2><p>' +
        esc(
          explanations[q.status] ||
            "Confira a situação do pedido antes de continuar.",
        ) +
        '</p><div class="m-actions">' +
        primary +
        "</div>" +
        (["pending", "accepted"].includes(q.status)
          ? UI.disclosure(
              "Cancelar solicitação",
              action(
                "Cancelar solicitação",
                "cancelRequest",
                { id: q.id },
                "quiet",
                "Encerrar este pedido antes da reserva?",
              ),
            )
          : "") +
        "</aside></div>"
      );
    }

    async function reservation() {
      const r = await request("/reservations/" + connectedState.ref);
      connectedState.current = r;
      const mine = r.passengerId === connectedState.me.id;
      return (
        '<div class="m-grid m-reservation-grid"><div>' +
        badge(r.status) +
        '<section class="m-trip-essential"><h2>' +
        esc(r.time) +
        " · " +
        esc(dateLabel(r.date)) +
        '</h2><p class="m-route">' +
        esc(journeyLabel(r)) +
        "</p><p>" +
        esc(r.meeting) +
        "</p>" +
        (r.pin
          ? '<div class="m-boarding"><h3>Código de embarque</h3><p class="m-pin">' +
            esc(r.pin) +
            '</p><p class="m-help">Apresente o PIN somente ao embarcar. Ele não confirma a conclusão nem libera o dinheiro.</p></div>'
          : "") +
        '</section><div class="m-person m-section">' +
        photo(r.photo, r.name) +
        "<div><h2>" +
        esc(r.name) +
        "</h2><p>" +
        esc(r.vehicle.model) +
        " · " +
        esc(r.vehicle.color) +
        " · " +
        esc(r.vehicle.plate) +
        '</p></div></div><details class="m-section"><summary>Detalhes do trecho e do valor</summary>' +
        summary([
          ["Trajeto", journeyLabel(r)],
          ["Data e horário", dateLabel(r.date) + " · " + r.time],
          ["Encontro", r.meeting],
          ["Com quem", r.name],
          ["Carro", r.vehicle.model + " · " + r.vehicle.color],
          ["Placa", r.vehicle.plate],
          ["Parcela", cash(r.priceCents)],
          ["Situação do valor", labels[r.parcelStatus] || r.parcelStatus],
          [
            "Disponibilidade",
            r.availability
              ? labels[r.availability] || r.availability
              : "Ainda não registrada",
          ],
          ...(r.contractId
            ? [
                [
                  "Ausência",
                  r.absence
                    ? labels[r.absence] || r.absence
                    : "Sem ausência registrada",
                ],
              ]
            : []),
        ]) +
        '</details></div><aside class="m-trip-actions"><h2>Acompanhe sua carona</h2><p class="m-help">Use as ações abaixo conforme a etapa da viagem.</p>' +
        (mine && r.status === "awaiting_confirmation"
          ? notice(
              r.availability === "no_class"
                ? "Sem aula informado após a contratação. Na confirmação ou após o prazo sem contestação, o valor integral volta ao seu crédito. Você pode contestar."
                : "Confira o registro final. A janela de confirmação ou contestação é de 24 horas.",
            ) + action("Confirmar registro final", "final", { id: r.id })
          : "") +
        (mine && r.contractId && r.status === "confirmed"
          ? UI.disclosure(
              "Avisar ausência",
              form(
                "absence",
                notice("O aviso mantém a vaga e o valor do trecho.") +
                  area("reason", "Motivo do aviso de ausência"),
                "Avisar ausência",
                { id: r.id },
              ),
            )
          : mine && !r.contractId && r.status === "confirmed"
            ? UI.disclosure(
                "Cancelar minha reserva",
                form(
                  "cancelPassenger",
                  area("reason", "Motivo do cancelamento"),
                  "Cancelar minha reserva",
                  { id: r.id },
                ),
              )
            : "") +
        (mine && ["future", "awaiting", "blocked"].includes(r.parcelStatus)
          ? UI.disclosure(
              "Contestar parcela",
              form(
                "contest",
                area("reason", "O que aconteceu com este trecho?"),
                "Contestar parcela",
                { id: r.id },
              ),
            )
          : "") +
        (mine && r.timeResponse === "pending"
          ? notice("Novo horário proposto: " + r.proposedTime) +
            action("Aceitar novo horário", "timeResponse", {
              id: r.id,
              accept: true,
            }) +
            action(
              "Não posso nesse horário",
              "timeResponse",
              { id: r.id, accept: false },
              "quiet",
            )
          : "") +
        '<div class="m-actions">' +
        action("Compartilhar detalhes", "share", { id: r.id }, "secondary") +
        action("Ver contato", "contact", { id: r.id }, "quiet") +
        go("Relatar ocorrência", "report", r.id, "quiet") +
        (r.contractId
          ? go("Gerenciar mensal", "contract", r.contractId, "quiet")
          : "") +
        "</div></aside></div>" +
        emergencyHelp()
      );
    }

    async function mine() {
      const tabs =
        '<nav class="m-tabs m-line-tabs" aria-label="Minhas caronas">' +
        [
          ["reservations", "Reservas"],
          ["requests", "Pedidos"],
          ["subscriptions", "Mensais"],
          ["offers", "Ofertas"],
        ]
          .map(([key, label]) =>
            UI.button(
              label,
              {
                "data-c-action": "filter",
                "data-input": JSON.stringify({ filter: key }),
                "aria-pressed": String(connectedState.filter === key),
              },
              "quiet",
            ),
          )
          .join("") +
        "</nav>";
      if (connectedState.filter === "requests") {
        const data = await request(
          "/ride-requests?" + pageQuery("mine_requests"),
        );
        return (
          tabs +
          listMarkup(
            data.items,
            (q) => requestRow(q),
            "Você ainda não solicitou uma carona. Busque um trajeto e envie seu primeiro pedido.",
          ) +
          (data.items.length ? "" : go("Buscar carona", "buscar")) +
          pages(data, "mine_requests")
        );
      }
      if (connectedState.filter === "subscriptions") {
        const data = await request(
          "/subscriptions?" + pageQuery("mine_subscriptions"),
        );
        return (
          tabs +
          listMarkup(
            data.items,
            (c) =>
              '<article class="m-row m-record-row"><div><h3>' +
              esc(c.region) +
              '</h3><p class="m-help">' +
              esc(c.otherName) +
              " · " +
              esc(dateLabel(c.startDate)) +
              " a " +
              esc(dateLabel(c.endDate)) +
              "</p>" +
              badge(c.status) +
              '</div><div class="m-record-choice">' +
              (c.totalCents != null
                ? "<strong>" + cash(c.totalCents) + "</strong>"
                : "") +
              go("Ver mensal", "contract", c.id, "secondary") +
              "</div></article>",
            "Seus planos mensais aparecerão aqui após o aceite e o pagamento.",
          ) +
          pages(data, "mine_subscriptions")
        );
      }
      if (connectedState.filter === "offers") {
        const data = await request("/rides/mine?" + pageQuery("mine_offers"));
        return (
          tabs +
          listMarkup(
            data.items,
            (r) => rideRow(r, true),
            "Suas ofertas aparecerão aqui depois da publicação.",
          ) +
          (data.items.length ? "" : go("Oferecer carona", "driver")) +
          pages(data, "mine_offers")
        );
      }
      const data = await request(
          "/reservations?" + pageQuery("mine_reservations"),
        ),
        active = data.items
          .filter(ongoing)
          .sort((a, b) => (a.date + a.time).localeCompare(b.date + b.time)),
        other = data.items.filter((r) => !ongoing(r));
      return (
        tabs +
        (data.items.length
          ? '<p class="m-help">Registros desta página, incluindo caronas programadas. Use o histórico para consultar por mês.</p>' +
            (active.length
              ? '<h2 class="m-section">Para acompanhar</h2>' +
                listMarkup(active, reservationRow)
              : "") +
            (other.length
              ? UI.disclosure(
                  "Outros registros desta página · " + other.length,
                  listMarkup(other, reservationRow),
                  active.length ? "" : "open",
                )
              : "") +
            pages(data, "mine_reservations")
          : UI.empty(
              "Sua próxima carona começa aqui",
              "Busque seu trajeto e confira o encontro, o horário e o motorista antes de solicitar.",
              go("Buscar carona", "buscar"),
            )) +
        '<div class="m-actions">' +
        go("Consultar histórico", "history", "", "quiet") +
        "</div>"
      );
    }

    async function historyView() {
      const data = await request(
        "/reservations?" + pageQuery("mine_reservations"),
      );
      return (
        '<div class="m-records"><p>Consulte valores e registros das suas reservas por mês, incluindo as programadas.</p>' +
        (data.items.length
          ? recordsByMonth(data.items)
          : UI.empty(
              "Seu histórico está começando",
              "As reservas confirmadas ficam registradas aqui para consulta.",
              go("Buscar carona", "buscar"),
            )) +
        pages(data, "mine_reservations") +
        "</div>"
      );
    }
    return { requestView, reservation, mine, historyView };
  }
  window.UniCaronaFeatures ||= {};
  window.UniCaronaFeatures.reservations = Object.freeze({ create });
})();
