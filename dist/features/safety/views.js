/* safety: explicit dependencies and session-owned state. */
(() => {
  "use strict";
  function create({
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
  }) {
    async function safety() {
      const [reports, blocks] = await Promise.all([
          request("/occurrences?" + pageQuery("reports")),
          request("/blocks?" + pageQuery("blocks")),
        ]),
        submitted = reports.items.find((r) => r.id === connectedState.ref);
      const record = (r) =>
        '<article class="m-safety-record"><header class="m-row"><div><h3>' +
        esc(UI.dateTime(r.createdAt)) +
        "</h3>" +
        badge(r.status) +
        "</div></header><p>" +
        esc(r.reason) +
        '</p><p class="m-help m-protocol">Protocolo: ' +
        esc(r.id) +
        "</p>" +
        (r.decision
          ? "<h3>Resposta da análise</h3><p>" + esc(r.decision) + "</p>"
          : '<p class="m-help">O relato está registrado. A resposta aparecerá aqui quando houver uma decisão.</p>') +
        "</article>";
      return (
        '<div class="m-grid"><div>' +
        (submitted
          ? '<section class="m-report-receipt" role="status"><h2>Relato registrado</h2><p>Guarde o protocolo para acompanhar a análise nesta página.</p><p class="m-protocol">' +
            esc(submitted.id) +
            "</p></section>"
          : "") +
        "<p>Relate problemas antes, durante ou depois da viagem, inclusive com a conta em revisão.</p>" +
        go("Registrar ocorrência", "report") +
        '<h2 class="m-section">Seus relatos</h2>' +
        (reports.items.length
          ? reports.items.map(record).join("")
          : UI.empty(
              "Nenhum relato registrado",
              "Se precisar, abra uma ocorrência pelos detalhes da carona para indicar a pessoa e o trecho.",
            )) +
        pages(reports, "reports") +
        UI.disclosure(
          "Pessoas bloqueadas",
          listMarkup(
            blocks.items,
            (b) =>
              '<article class="m-row"><span>' +
              esc(b.name) +
              "</span>" +
              action(
                "Desbloquear",
                "unblock",
                { id: b.id },
                "quiet",
                "Retirar este bloqueio sem reativar vínculos encerrados?",
              ) +
              "</article>",
            "Nenhuma pessoa bloqueada.",
          ) + pages(blocks, "blocks"),
        ) +
        '</div><aside class="m-form-aside">' +
        emergencyHelp() +
        "</aside></div>"
      );
    }

    async function report() {
      let reservation = null,
        otherId = "",
        name = "";
      if (connectedState.ref) {
        try {
          reservation = await request("/reservations/" + connectedState.ref);
          otherId =
            reservation.passengerId === connectedState.me.id
              ? reservation.driverId
              : reservation.passengerId;
          name = reservation.name;
        } catch (error) {
          if (error.status !== 404) throw error;
          otherId = connectedState.ref;
        }
      }
      let choices = [];
      if (!otherId) {
        const data = await request("/ride-requests?limit=100");
        choices = [
          ...new Map(
            data.items.map((q) => [q.other.id, [q.other.id, q.other.name]]),
          ).values(),
        ];
        const received = await request("/ride-requests?side=driver&limit=100");
        for (const q of received.items)
          if (!choices.some((c) => c[0] === q.other.id))
            choices.push([q.other.id, q.other.name]);
      }
      if (!otherId && !choices.length)
        return UI.empty(
          "Selecione a carona envolvida",
          "Abra uma oferta, solicitação ou reserva e use Relatar ocorrência para indicar a pessoa e o trecho.",
          go("Ver minhas caronas", "minhas"),
        );
      return (
        '<div class="m-grid m-form-layout"><div><p>Descreva o que aconteceu e quando. Depois de enviar, acompanhe o protocolo e a resposta na página de segurança.</p>' +
        (name
          ? summary([
              ["Pessoa envolvida", name],
              ...(reservation
                ? [
                    ["Trecho", journeyLabel(reservation)],
                    [
                      "Quando",
                      dateLabel(reservation.date) + " · " + reservation.time,
                    ],
                  ]
                : []),
            ])
          : "") +
        '<div class="m-section">' +
        form(
          "report",
          (otherId ? "" : select("otherId", "Pessoa envolvida", choices)) +
            area("reason", "Relato do que aconteceu") +
            check("severe", "Há um risco grave de segurança") +
            check("block", "Bloquear novas combinações com essa pessoa"),
          "Enviar relato",
          { otherId: otherId || undefined, reservationId: reservation?.id },
        ) +
        '</div></div><aside class="m-form-aside"><h2>Como funciona o bloqueio</h2>' +
        notice(
          "Um bloqueio impede novas combinações. Parcelas futuras de mensais interrompidos ficam em análise separada. Em risco imediato, procure ajuda local adequada.",
        ) +
        "</aside></div>"
      );
    }
    return { safety, report };
  }
  window.UniCaronaFeatures ||= {};
  window.UniCaronaFeatures.safety = Object.freeze({ create });
})();
