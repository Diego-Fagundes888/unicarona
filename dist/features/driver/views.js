/* driver: explicit dependencies and session-owned state. */
(() => {
  "use strict";
  function create({
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
  }) {
    async function driver() {
      connectedState.me = await request("/users/me");
      const applications = await request("/drivers/applications?limit=1");
      const latest = applications.items[0];
      const approved = connectedState.me.driverStatus === "approved";
      const enrollment = form(
        "applyDriver",
        UI.group(
          "Seu veículo",
          field(
            "model",
            "Modelo do veículo",
            "",
            "text",
            'required maxlength="80"',
          ) +
            '<div class="m-two">' +
            field("color", "Cor", "", "text", 'required maxlength="40"') +
            field("plate", "Placa", "", "text", 'required maxlength="8"') +
            "</div>" +
            field(
              "capacity",
              "Vagas para passageiros",
              3,
              "number",
              'required min="1" max="8" step="1"',
              "Informe apenas os lugares disponíveis para passageiros.",
            ),
        ) +
          UI.group(
            "Documentos para análise",
            field(
              "identity",
              "Documento de identificação",
              "",
              "file",
              'required accept="image/png,image/jpeg,image/webp,application/pdf"',
            ) +
              field(
                "license",
                "Carteira de habilitação",
                "",
                "file",
                'required accept="image/png,image/jpeg,image/webp,application/pdf"',
              ),
            "PNG, JPG, WebP ou PDF. Até 5 MB por documento. Solicitação válida por 30 dias; arquivos enviados sem concluir uma solicitação têm janela de 24 horas.",
          ),
        "Enviar para análise",
      );
      if (connectedState.me.role === "admin")
        return (
          notice(
            "A administração acompanha aprovações e ocorrências. Use seu acesso administrativo para continuar.",
          ) + go("Abrir administração", "admin")
        );
      if (!approved)
        return (
          badge(
            latest?.status === "expired"
              ? "expired"
              : connectedState.me.driverStatus,
          ) +
          (latest
            ? (latest.status !== "expired" && latest.decision
                ? notice(latest.decision)
                : "") + driverDocumentsNotice(latest)
            : "") +
          '<div class="m-grid"><div>' +
          enrollment +
          '</div><aside class="m-panel"><h2>Antes de publicar</h2><p>Seu veículo e seus documentos precisam ser aprovados. A solicitação fica disponível para análise por até 30 dias.</p><p>Após a decisão, as cópias ficam disponíveis por mais 30 dias. A eliminação dos anexos não cancela uma aprovação registrada. Preservações específicas seguem acesso restrito.</p><p class="m-help">Fotos ou PDF de até 5 MB por documento.</p></aside></div>'
        );
      const [offers, routines, requests] = await Promise.all([
        request("/rides/mine?upcoming=true&" + pageQuery("driver_offers")),
        request("/routines/mine?" + pageQuery("driver_routines")),
        request(
          "/ride-requests?side=driver&status=pending&" +
            pageQuery("driver_requests"),
        ),
      ]);
      const section = ["offers", "requests", "routines"].includes(
        connectedState.ref,
      )
        ? connectedState.ref
        : "offers";
      const tabs =
        '<div class="m-tabs" aria-label="Área do motorista">' +
        [
          ["offers", "Seus trechos"],
          ["requests", "Solicitações"],
          ["routines", "Rotinas mensais"],
        ]
          .map(([key, label]) =>
            UI.button(
              label,
              {
                "data-c-go": "driver",
                "data-ref": key,
                ...(section === key ? { "aria-current": "page" } : {}),
              },
              section === key ? "" : "quiet",
            ),
          )
          .join("") +
        "</div>";
      const vehicle = latest
        ? UI.disclosure(
            "Seu veículo e documentos",
            summary([
              ["Veículo", latest.model + " · " + latest.color],
              ["Placa", latest.plate],
              ["Vagas", latest.capacity],
            ]) + driverDocumentsNotice(latest),
          )
        : "";
      const content =
        section === "requests"
          ? '<h2 class="m-section">Solicitações recebidas</h2>' +
            listMarkup(
              requests.items.filter((q) => q.status === "pending"),
              (q) => requestRow(q, true),
              "Nenhum pedido aguardando análise.",
            ) +
            pages(requests, "driver_requests")
          : section === "routines"
            ? '<h2 class="m-section">Rotinas mensais</h2>' +
              listMarkup(
                routines.items,
                (r) =>
                  '<article class="m-row"><div><h3>' +
                  esc(r.region) +
                  "</h3>" +
                  badge(r.status) +
                  "</div>" +
                  action(
                    r.status === "active"
                      ? "Pausar novos planos"
                      : "Retomar novos planos",
                    "routineAvailability",
                    { id: r.id, enabled: r.status !== "active" },
                    "quiet",
                  ) +
                  "</article>",
              ) +
              pages(routines, "driver_routines")
            : '<h2 class="m-section">Seus próximos trechos</h2>' +
              listMarkup(
                offers.items.filter((r) =>
                  ["planned", "active"].includes(r.status),
                ),
                (r) => rideRow(r, true),
                "Suas ofertas publicadas aparecerão aqui.",
              ) +
              pages(offers, "driver_offers");
      return (
        '<div class="m-actions">' +
        go("Publicar carona", "offer") +
        go("Analisar solicitações", "driver", "requests", "secondary") +
        go("Recebimentos", "wallet", "", "quiet") +
        "</div>" +
        vehicle +
        tabs +
        content +
        UI.disclosure(
          "Atualizar veículo e documentos",
          notice(
            "As alterações exigem nova análise. Reservas existentes precisam caber no veículo aprovado.",
          ) + enrollment,
        )
      );
    }

    async function offer(edit = false) {
      let r = null;
      if (edit) {
        r = await request("/rides/" + connectedState.ref);
        connectedState.current = r;
      } else connectedState.me = await request("/users/me");
      if (connectedState.me.driverStatus !== "approved")
        return (
          notice("Aguarde a aprovação de motorista e veículo para publicar.") +
          go("Cadastro de motorista", "driver")
        );
      const costs = UI.group(
        "Vagas e contribuição",
        field(
          "capacity",
          "Vagas para passageiros",
          r?.capacity || 3,
          "number",
          'required min="1" max="8" step="1"',
        ) +
          '<div class="m-two">' +
          field(
            "distance",
            "Distância do trecho em km",
            r?.distance || "",
            "number",
            'required min="0.1" max="2000" step="0.1"',
          ) +
          field(
            "toll",
            "Pedágio em R$",
            r?.toll || 0,
            "number",
            'min="0" max="2000" step="0.01"',
          ) +
          "</div>",
        "O UniCarona calcula a contribuição com estes valores e os parâmetros cadastrados.",
      );
      const preferences = UI.group(
        "Como combinar",
        check(
          "automatic",
          "Aceitar caronas avulsas automaticamente",
          r?.automatic || false,
        ) +
          '<p class="m-help">No mensal, cada pedido exige seu aceite específico.</p>' +
          field(
            "notes",
            "Observações (opcional)",
            r?.notes || "",
            "text",
            'maxlength="1200"',
          ),
      );
      const routeFields = edit
        ? ""
        : UI.group(
            "Seu trajeto",
            select("type", "Modalidade", [
              ["avulsa", "Carona avulsa"],
              ["mensal", "Rotina mensal"],
            ]) +
              field(
                "region",
                "Região",
                connectedState.me.region || "",
                "text",
                'required maxlength="100"',
              ) +
              field(
                "meeting",
                "Ponto de encontro",
                "",
                "text",
                'required maxlength="200"',
                "Escolha um local fácil de reconhecer. Evite informar dados pessoais.",
              ) +
              select("direction", "Sentido", [
                ["ida", "Ida para a faculdade"],
                ["volta", "Volta da faculdade"],
                ["ambos", "Ida e volta — mensal", true],
              ]),
          );
      const schedule = edit
        ? ""
        : UI.group(
            "Quando você sai",
            "<div data-c-avulsa>" +
              field(
                "date",
                "Data da carona",
                today(),
                "date",
                'required min="' + today() + '"',
              ) +
              "</div>" +
              field("time", "Horário de saída", "", "time", "required") +
              "<div data-c-monthly hidden>" +
              field("returnTime", "Horário da volta", "", "time") +
              days() +
              "</div>",
          );
      return (
        '<div class="m-grid m-form-layout"><div>' +
        form(
          edit ? "editOffer" : "publishOffer",
          routeFields + schedule + costs + preferences,
          edit ? "Salvar oferta" : "Publicar oferta",
          edit ? { id: r.id, version: r.version } : {},
        ) +
        '</div><aside class="m-form-aside"><h2>Antes de publicar</h2><p>Confira o encontro, as vagas e os horários. Estas informações orientam quem vai viajar com você.</p>' +
        notice(
          edit
            ? "O novo preço se aplica a novas contratações. As parcelas já pagas permanecem congeladas."
            : "A reserva é confirmada depois do aceite, do pagamento e da verificação de disponibilidade.",
        ) +
        "</aside></div>"
      );
    }

    async function passengers() {
      if (connectedState.ref === "requests") {
        const data = await request(
          "/ride-requests?side=driver&" + pageQuery("received_requests"),
        );
        return (
          "<p>Confira a agenda e responda aos pedidos recebidos. O pagamento confirma a vaga.</p>" +
          listMarkup(
            data.items,
            (q) => requestRow(q, true),
            "Nenhum passageiro solicitou uma carona ainda.",
          ) +
          pages(data, "received_requests")
        );
      }
      const [r, data] = await Promise.all([
        request("/rides/" + connectedState.ref),
        request(
          "/reservations?side=driver&rideId=" +
            connectedState.ref +
            "&limit=100",
        ),
      ]);
      connectedState.current = r;
      const canBoard =
        r.date === today() && ["planned", "active"].includes(r.status);
      const passenger = (x) =>
        '<article class="m-passenger-record"><div class="m-row"><div><h3>' +
        esc(x.name) +
        '</h3><p class="m-help">' +
        (x.contractId ? "Mensalista" : "Passageiro avulso") +
        " · " +
        cash(x.priceCents) +
        "</p>" +
        badge(x.status) +
        "</div>" +
        go("Ver registro", "reservation", x.id, "secondary") +
        "</div>" +
        (x.absence ? notice(labels[x.absence] || "Ausência registrada") : "") +
        (canBoard && x.status === "confirmed"
          ? form(
              "board",
              field(
                "pin",
                "PIN do passageiro",
                "",
                "text",
                'required pattern="[0-9]{4}" maxlength="4" inputmode="numeric" autocomplete="off"',
              ),
              "Registrar embarque",
              { id: x.id },
            )
          : "") +
        "</article>";
      const operating =
        r.status === "planned"
          ? r.date === today()
            ? action("Iniciar trecho", "startRide", { id: r.id })
            : notice(
                "O início e o registro de embarque ficam disponíveis no dia da carona.",
              )
          : r.status === "active"
            ? form(
                "finishRide",
                check(
                  "availability",
                  "Cumpri a disponibilidade combinada para mensalistas que não embarcaram",
                ),
                "Declarar conclusão",
                { id: r.id },
              )
            : notice(
                "Este trecho foi encerrado. Os registros de passageiros continuam disponíveis.",
              );
      const adjustments =
        (r.status === "planned"
          ? UI.disclosure(
              "Editar contribuição e vagas",
              go("Editar oferta", "edit", r.id, "secondary"),
            ) +
            UI.disclosure(
              "Combinar novo horário",
              form(
                "proposeTime",
                field("time", "Novo horário", "", "time", "required") +
                  area("reason", "Motivo do novo horário"),
                "Enviar proposta",
                { id: r.id },
              ) +
                action(
                  "Preservar horário original",
                  "keepTime",
                  { id: r.id },
                  "quiet",
                ),
            ) +
            UI.disclosure(
              "Informar Sem aula",
              notice(
                "Use somente para aula suspensa após um contrato mensal. Não substitui cancelamento do motorista e não se aplica a avulsas confirmadas.",
              ) +
                form(
                  "noClass",
                  area("reason", "Motivo de Sem aula"),
                  "Informar Sem aula",
                  { id: r.id },
                ),
            )
          : "") +
        (["planned", "active"].includes(r.status)
          ? UI.disclosure(
              "Cancelar este trecho",
              form(
                "cancelRide",
                area("reason", "Motivo do cancelamento"),
                "Cancelar trecho e devolver as parcelas",
                { id: r.id },
              ),
            )
          : "");
      return (
        '<div class="m-grid"><div>' +
        badge(r.status) +
        '<section class="m-trip-essential"><h2>' +
        esc(r.time) +
        " · " +
        esc(dateLabel(r.date)) +
        "</h2>" +
        UI.itinerary(
          r.direction === "ida" ? r.region : connectedState.me.university,
          r.direction === "ida" ? connectedState.me.university : r.region,
          r.meeting,
        ) +
        '</section><h2 class="m-section">Passageiros</h2>' +
        summary([["Vagas disponíveis", r.available]]) +
        (data.items.length
          ? data.items.map(passenger).join("")
          : UI.empty(
              "Nenhum passageiro confirmado",
              "Os passageiros aparecem aqui após a confirmação da reserva. Você pode acompanhar os pedidos recebidos.",
              go("Ver solicitações", "driver", "requests", "secondary"),
            )) +
        '</div><aside class="m-trip-actions"><h2>Acompanhar trecho</h2>' +
        operating +
        adjustments +
        "</aside></div>"
      );
    }
    return { driver, offer, passengers };
  }
  window.UniCaronaFeatures ||= {};
  window.UniCaronaFeatures.driver = Object.freeze({ create });
})();
