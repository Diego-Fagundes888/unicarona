/* subscriptions: explicit dependencies and session-owned state. */
(() => {
  "use strict";
  function create({
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
  }) {
    async function monthly() {
      const renewal =
          connectedState.route === "renew"
            ? await request("/subscriptions/" + connectedState.ref)
            : null,
        r = await request(
          "/routines/" + (renewal ? renewal.routineId : connectedState.ref),
        );
      connectedState.current = r;
      const after = renewal ? new Date(renewal.endDate + "T12:00:00Z") : null;
      if (after) after.setUTCDate(after.getUTCDate() + 1);
      const start =
        after && after.toISOString().slice(0, 10) > today()
          ? after.toISOString().slice(0, 10)
          : today();
      const schedule =
        field(
          "startDate",
          "Data de início",
          start,
          "date",
          'required min="' + start + '"',
        ) +
        select(
          "directions",
          "Sentidos",
          r.times.ida && r.times.volta
            ? [
                ["ambos", "Ida e volta"],
                ["ida", "Só ida"],
                ["volta", "Só volta"],
              ]
            : [[r.times.ida ? "ida" : "volta", r.times.ida ? "Ida" : "Volta"]],
          renewal?.directions || "",
        ) +
        days(renewal?.weekdays || r.weekdays, r.weekdays);
      return (
        '<div class="m-grid m-monthly-layout"><div>' +
        UI.person(r.driver.name, academic(r.driver)) +
        '<section class="m-trip-essential"><h2>' +
        esc(r.region) +
        " ↔ " +
        esc(connectedState.me.university || "Faculdade") +
        '</h2><p class="m-meeting"><strong>Ponto de encontro</strong><br>' +
        esc(r.meeting) +
        "</p>" +
        summary([
          ...(r.times.ida
            ? [
                [
                  "Ida",
                  r.times.ida + " · " + cash(r.prices.ida) + " por trecho",
                ],
              ]
            : []),
          ...(r.times.volta
            ? [
                [
                  "Volta",
                  r.times.volta + " · " + cash(r.prices.volta) + " por trecho",
                ],
              ]
            : []),
          ["Dias oferecidos", r.weekdays.map((d) => dayNames[d]).join(", ")],
        ]) +
        "</section>" +
        notice(
          "O mensal exige o mesmo curso, período numérico e turma. A agenda inteira precisa ter vaga; o motorista dá um aceite específico.",
        ) +
        (r.notes
          ? UI.disclosure("Sobre esta rotina", "<p>" + esc(r.notes) + "</p>")
          : "") +
        '</div><aside class="m-monthly-configuration">' +
        (r.compatible
          ? form(
              "previewMonthly",
              UI.group("Escolha sua agenda", schedule),
              "Conferir agenda e valor",
              { routineId: r.id, previousContractId: renewal?.id },
            )
          : notice(
              "Seu perfil ainda não é compatível com esta turma. Confira seus dados antes de solicitar.",
              "warning",
            ) + go("Conferir meu perfil", "perfil", "", "secondary")) +
        UI.disclosure(
          "Registrar interesse nesta rotina",
          "<p>O interesse não ocupa nem garante vaga.</p>" +
            action(
              "Registrar interesse",
              "wait",
              { routineId: r.id },
              "secondary",
            ),
        ) +
        '</aside></div><section class="m-section m-monthly-preview" data-monthly-preview aria-live="polite"></section>'
      );
    }

    function agendaLines(lines, conflicts = []) {
      const blocked = new Set(conflicts),
        groups = new Map();
      for (const line of lines) {
        if (!groups.has(line.date)) groups.set(line.date, []);
        groups.get(line.date).push(line);
      }
      return [...groups]
        .map(
          ([date, rows]) =>
            '<section class="m-agenda-day"><h3>' +
            esc(dateLabel(date)) +
            "</h3>" +
            rows
              .map(
                (l) =>
                  '<div class="m-row"><span>' +
                  esc(l.direction === "ida" ? "Ida" : "Volta") +
                  " · " +
                  esc(l.time) +
                  (blocked.has(l.rideId)
                    ? '<span class="m-badge bad">Sem disponibilidade</span>'
                    : "") +
                  "</span><strong>" +
                  cash(l.cents) +
                  "</strong></div>",
              )
              .join("") +
            "</section>",
        )
        .join("");
    }

    function previewMarkup(p, input) {
      const conflicts = p.lines.filter((l) => p.conflicts.includes(l.rideId));
      return (
        "<h2>Sua agenda</h2><p>" +
        esc(dateLabel(p.startDate)) +
        " a " +
        esc(dateLabel(p.endDate)) +
        " · " +
        p.lines.length +
        ' trechos</p><div class="m-plan-total"><span>Total proporcional</span><strong>' +
        cash(p.totalCents) +
        "</strong></div>" +
        summary([
          ["Soma dos avulsos", cash(p.originalCents)],
          ["Desconto mensal", Math.round(p.discount * 100) + "%"],
        ]) +
        (p.excludedDates.length
          ? UI.disclosure(
              p.excludedDates.length + " data(s) fora da contratação",
              "<p>Estas datas não entram no valor do seu plano.</p>" +
                listMarkup(
                  p.excludedDates,
                  (d) =>
                    '<div class="m-row"><span>' +
                    esc(dateLabel(d.date)) +
                    "</span><span>" +
                    esc(d.title) +
                    "</span>",
                ),
            )
          : "") +
        (p.conflicts.length
          ? notice(
              "Há trechos sem disponibilidade. Ajuste a agenda; nenhum plano foi criado.",
              "warning",
            ) +
            UI.disclosure(
              "Conferir trechos sem disponibilidade",
              agendaLines(conflicts, p.conflicts),
              "open",
            )
          : '<div class="m-actions">' +
            action("Solicitar mensal ao motorista", "requestMonthly", input) +
            '</div><p class="m-help">O pedido será enviado ao motorista. O pagamento acontece após o aceite.</p>') +
        UI.disclosure(
          "Ver cada trecho e seu valor",
          agendaLines(p.lines, p.conflicts),
        )
      );
    }

    async function contract() {
      const c = await request("/subscriptions/" + connectedState.ref),
        side = c.driverId === connectedState.me.id ? "driver" : "passenger",
        data = await request(
          "/reservations?contractId=" + c.id + "&side=" + side + "&limit=100",
        );
      const rights =
        c.status === "renewed"
          ? await request("/subscriptions/" + c.id + "/rights").catch(
              (error) => {
                if (error.status === 404) return { openRights: false };
                throw error;
              },
            )
          : null;
      connectedState.current = c;
      const active = data.items
          .filter(ongoing)
          .sort((a, b) => (a.date + a.time).localeCompare(b.date + b.time)),
        attention = active.filter(needsAttention),
        scheduled = active.filter((r) => !needsAttention(r)),
        past = data.items.filter((r) => !ongoing(r)),
        mine = c.passengerId === connectedState.me.id;
      const controls =
        (mine &&
        c.renew &&
        ["active", "grace", "renew_conflict"].includes(c.status)
          ? '<div class="m-actions">' +
            go("Preparar renovação", "renew", c.id) +
            "</div>" +
            UI.disclosure(
              "Desativar renovação",
              notice("Os direitos do período já pago serão preservados.") +
                action(
                  "Desativar renovação",
                  "cancelRenewal",
                  { id: c.id },
                  "quiet",
                  "Desativar a renovação? Os direitos do período já pago serão preservados.",
                ),
            )
          : "") +
        (c.driverId === connectedState.me.id &&
        (["active", "grace", "cancelled_renewal", "renew_conflict"].includes(
          c.status,
        ) ||
          (c.status === "renewed" && rights?.openRights))
          ? UI.disclosure(
              "Encerrar vínculo mensal",
              notice(
                "Encerrar este vínculo inclui os trechos futuros dos períodos pagos ligados a ele. Valores em análise seguem revisão separada.",
              ) +
                form(
                  "removeMonthly",
                  area("reason", "Motivo do encerramento do vínculo"),
                  "Encerrar vínculo e creditar trechos futuros",
                  { id: c.id },
                ),
            )
          : "");
      return (
        badge(c.status) +
        (c.status === "renewed" && rights?.openRights
          ? notice(
              "O próximo período foi pago. As viagens deste período continuam reservadas. Encerrar o vínculo inclui os trechos futuros dos períodos pagos ligados a esta renovação.",
            )
          : "") +
        '<section class="m-trip-essential">' +
        UI.person(c.otherName, "Plano mensal · " + c.region) +
        "<p>" +
        esc(dateLabel(c.startDate)) +
        " a " +
        esc(dateLabel(c.endDate)) +
        '</p></section><div class="m-grid m-contract-layout"><div>' +
        (c.status === "grace"
          ? notice(
              "As vagas da próxima agenda estão retidas durante a carência. Elas não são reservas pagas e não permitem embarque. Prepare a renovação e aguarde o aceite do motorista.",
              "warning",
            )
          : "") +
        (attention.length
          ? '<h2 class="m-section">Precisam de atenção</h2>' +
            listMarkup(attention, reservationRow)
          : "") +
        '<h2 class="m-section">Próximos trechos</h2>' +
        listMarkup(
          scheduled.slice(0, 2),
          reservationRow,
          "Não há outros trechos programados neste plano.",
        ) +
        (scheduled.length > 2
          ? UI.disclosure(
              "Ver os demais trechos programados · " + (scheduled.length - 2),
              listMarkup(scheduled.slice(2), reservationRow),
            )
          : "") +
        (past.length
          ? UI.disclosure(
              "Trechos encerrados · " + past.length,
              recordsByMonth(past),
            )
          : "") +
        (data.nextOffset !== null
          ? notice(
              "Há mais registros deste plano. Consulte o histórico para continuar.",
            ) + go("Consultar histórico", "history", "", "secondary")
          : "") +
        '</div><aside class="m-contract-details"><h2>Seu contrato</h2><div class="m-plan-total"><span>Total contratado</span><strong>' +
        cash(c.totalCents) +
        "</strong></div>" +
        summary([
          ["Renovação", c.renew ? "Ativada" : "Desativada"],
          ["Ausências registradas", c.absenceCount],
          [
            "Carência",
            c.graceUntil ? UI.dateTime(c.graceUntil) : "Não iniciada",
          ],
          ...(c.status === "grace"
            ? [["Vagas retidas sem pagamento", c.heldLegs + " trecho(s)"]]
            : []),
        ]) +
        controls +
        notice(
          "Ausência avisada mantém a vaga e o valor. Desativar a renovação preserva os trechos pagos.",
        ) +
        "</aside></div>"
      );
    }
    return { monthly, agendaLines, previewMarkup, contract };
  }
  window.UniCaronaFeatures ||= {};
  window.UniCaronaFeatures.subscriptions = Object.freeze({ create });
})();
