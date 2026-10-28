/* payments: explicit dependencies and session-owned state. */
(() => {
  "use strict";
  function create({
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
  }) {
    async function checkout() {
      const version = connectedState.epoch,
        id = connectedState.ref;
      const [p, configuration, details] = await Promise.all([
        request("/payments/" + id),
        request("/payments/config"),
        request("/payments/" + id + "/summary"),
      ]);
      const mp = configuration.provider === "mercadopago";
      if (connectedState.tripReviewChangedId !== p.id)
        connectedState.tripReviewChangedId = null;
      const pricesAvailable =
        details.trips?.length === details.rideCount &&
        details.trips.every(
          (trip) => Number.isSafeInteger(trip.cents) && trip.cents >= 0,
        ) &&
        details.trips.reduce((total, trip) => total + trip.cents, 0) ===
          p.amountCents;
      const reviewedTrip = pricesAvailable ? details.tripReview : null;
      const creditReview = mp ? await request("/wallet/refund-review") : null;
      const gateway = mp ? await request("/payments/" + id + "/gateway") : null;
      connectedState.current = {
        ...p,
        gateway,
        configuration,
        tripReview: details.tripReview,
      };
      const link = p.checkout && new URL(p.checkout.url, location.href);
      const safeLink = link && ["http:", "https:"].includes(link.protocol);
      const expiredWithOpenCharge =
        mp &&
        p.status === "expired" &&
        ["PENDING", "PROCESSING"].includes(gateway?.state);
      const checkoutDeadlineEnded =
        p.status === "pending" && Date.parse(p.expiresAt) <= Date.now();
      let checking = false;
      if (
        (p.status === "pending" || expiredWithOpenCharge) &&
        version === connectedState.epoch &&
        !connectedState.disposed
      )
        connectedState.pollTimer = setInterval(async () => {
          if (
            checking ||
            version !== connectedState.epoch ||
            connectedState.disposed
          )
            return;
          checking = true;
          try {
            const latest = await request("/payments/" + p.id);
            const external = mp
              ? await request("/payments/" + p.id + "/gateway")
              : null;
            if (version !== connectedState.epoch || connectedState.disposed)
              return;
            if (
              latest.status !== p.status ||
              latest.confirmed !== p.confirmed ||
              JSON.stringify(external) !== JSON.stringify(gateway) ||
              (!checkoutDeadlineEnded &&
                p.status === "pending" &&
                Date.parse(p.expiresAt) <= Date.now())
            )
              navigate("checkout", p.id, true);
          } catch (error) {
            if (version === connectedState.epoch && !connectedState.disposed)
              tell(error.message);
          } finally {
            checking = false;
          }
        }, 10000);
      const expiredBeforeCheckout =
        p.status === "awaiting" &&
        !details.tripReview &&
        Date.parse(p.expiresAt) <= Date.now();
      const state =
        expiredBeforeCheckout ||
        (p.status === "expired" && !expiredWithOpenCharge)
          ? "EXPIRED"
          : gateway?.state;
      const states = {
        CREATED: "Pagamento não iniciado",
        PENDING: "Aguardando pagamento",
        PROCESSING: "Pagamento em processamento",
        APPROVED: "Pagamento aprovado",
        REJECTED: "Pagamento recusado",
        CANCELLED: "Pagamento cancelado",
        EXPIRED: "Pagamento expirado",
        REFUNDED: "Pagamento reembolsado",
        PARTIALLY_REFUNDED: "Reembolso parcial",
        UNDER_REVIEW: "Pagamento em análise",
      };
      const terminal = [
        "REJECTED",
        "CANCELLED",
        "EXPIRED",
        "REFUNDED",
        "PARTIALLY_REFUNDED",
        "UNDER_REVIEW",
      ].includes(state);
      let controls;
      const update = mp
        ? action("Atualizar status", "syncPayment", { id: p.id }, "secondary")
        : action("Atualizar status", "reload", {}, "secondary");
      if (expiredWithOpenCharge) {
        controls =
          "<h2>Prazo da reserva encerrado</h2>" +
          notice(
            "A cobrança ainda está sendo verificada. Se o provedor aprovar o valor, ele será convertido em crédito para caronas. Acompanhe o status antes de iniciar outro pagamento.",
          ) +
          update +
          go("Ver meus pedidos", "minhas", "requests", "secondary") +
          go("Ver carteira", "wallet", "", "quiet");
      } else if (terminal) {
        const refusalMessages = {
          card_insufficient_amount:
            "O banco recusou por saldo ou limite insuficiente. Confira seu cartão e faça um novo pedido para tentar novamente.",
          insufficient_amount:
            "O banco recusou por saldo ou limite insuficiente. Confira seu cartão e faça um novo pedido para tentar novamente.",
          amount_limit_exceeded:
            "O valor ultrapassa o limite permitido para este cartão. Consulte seu banco ou escolha outra forma de pagamento em um novo pedido.",
          bad_filled_card_data:
            "Os dados do cartão não foram aceitos. Confira o número, a validade e o código de segurança antes de pagar um novo pedido.",
          "3ds_challenge_expired":
            "O prazo para autenticar o cartão terminou. Faça um novo pedido e conclua a confirmação solicitada pelo banco.",
        };
        const messages = {
          REJECTED:
            refusalMessages[gateway?.statusDetail] ||
            "O pagamento foi recusado. Confira os dados ou escolha outra forma de pagamento em um novo pedido.",
          CANCELLED:
            "Esta cobrança foi cancelada. Confira seus pedidos antes de iniciar outra.",
          EXPIRED:
            "O prazo para confirmar esta reserva terminou. Confira seus pedidos para solicitar uma nova carona.",
          REFUNDED:
            "O Mercado Pago confirmou a devolução. O prazo para aparecer na conta ou fatura depende da forma de pagamento.",
          PARTIALLY_REFUNDED:
            "Parte do pagamento foi devolvida. Consulte o registro financeiro e os trechos da sua carona.",
          UNDER_REVIEW: p.confirmed
            ? "O pagamento desta reserva está em análise. Confira os trechos e procure o suporte para acompanhar a revisão financeira."
            : "O pagamento precisa de análise. Acompanhe o status para saber se sua reserva foi confirmada ou procure o suporte.",
        };
        controls =
          notice(messages[state]) +
          go("Ver meus pedidos", "minhas", "requests", "secondary") +
          go("Ver carteira", "wallet", "", "quiet") +
          update;
      } else if (p.confirmed) {
        controls =
          "<h2>Reserva confirmada</h2><p>Pagamento confirmado pelo servidor. Confira os trechos e apresente o PIN somente ao embarcar.</p>" +
          go("Acompanhar minha carona", "minhas");
      } else if (p.status === "approved") {
        controls =
          notice(
            "O valor recebido voltou para seu crédito porque a vaga ficou indisponível.",
          ) + go("Ver carteira", "wallet");
      } else if (mp && checkoutDeadlineEnded) {
        controls =
          "<h2>Prazo deste checkout encerrado</h2>" +
          notice(
            "O prazo para usar o código ou o formulário terminou. Atualize o status para conferir o resultado de qualquer envio já iniciado.",
          ) +
          update +
          go("Ver meus pedidos", "minhas", "requests", "secondary") +
          go("Ver carteira", "wallet", "", "quiet");
      } else if (p.status === "awaiting") {
        controls = reviewedTrip
          ? form(
              "checkout",
              '<p class="m-help">Confira os trechos, o ponto de encontro e as observações antes de continuar.</p>' +
                select("method", "Forma de pagamento", [
                  ["pix", "Pix"],
                  ...(configuration.cardEnabled ? [["card", "Cartão"]] : []),
                ]) +
                (creditReview?.creditUnderReview
                  ? notice(
                      "Seu crédito aguarda uma conferência financeira. Você pode pagar esta carona sem usar o crédito ou procurar o suporte.",
                    )
                  : check("useCredit", "Usar meu crédito disponível")),
              "Continuar para pagamento",
              { id: p.id, tripReview: reviewedTrip },
            )
          : notice(
              "Os detalhes desta carona não estão disponíveis para iniciar o pagamento. Atualize o resumo ou confira seus pedidos.",
            ) +
            action("Atualizar resumo", "reload", {}, "secondary") +
            go("Ver meus pedidos", "minhas", "requests", "quiet");
      } else if (mp && p.status === "pending") {
        const expiry = new Intl.DateTimeFormat("pt-BR", {
          dateStyle: "short",
          timeStyle: "short",
        }).format(new Date(p.expiresAt));
        controls =
          (configuration.environment === "test"
            ? notice(
                "Ambiente de testes: nenhum dinheiro real será movimentado. A reserva aguarda a confirmação do provedor.",
              )
            : "") +
          '<p class="m-help">Prazo da reserva: ' +
          esc(expiry) +
          ". Abrir o pagamento ou copiar o código não confirma a reserva.</p>";
        if (
          gateway.statusDetail === "pending_challenge" &&
          gateway.challengeUrl
        ) {
          controls +=
            '<h2>Confirme com seu banco</h2><p>Conclua a verificação do cartão abaixo. Depois, aguarde o resultado do pagamento.</p><iframe class="m-payment-challenge" title="Verificação do cartão pelo banco" data-mp-challenge src="' +
            esc(gateway.challengeUrl) +
            '" referrerpolicy="no-referrer"></iframe>';
        } else if (p.method === "card" && !gateway.cardSubmitted) {
          controls +=
            '<h2>Pagar com cartão</h2><p class="m-help">Os dados do cartão são coletados pelo formulário seguro do Mercado Pago. Pagamento em uma parcela.</p><div id="mp-card-' +
            esc(p.id) +
            '" data-mp-card aria-busy="true"></div><p class="m-help" role="status" aria-live="polite" data-mp-feedback>Carregando formulário seguro…</p>';
        } else if (
          p.method === "pix" &&
          gateway.qrCode &&
          state !== "PROCESSING"
        ) {
          controls +=
            '<h2>Pagar com Pix</h2><div class="m-pix-instructions"><div>' +
            (gateway.qrBase64 && /^[A-Za-z0-9+/=]+$/.test(gateway.qrBase64)
              ? '<img class="m-pix-qr" width="224" height="224" alt="QR Code deste pagamento Pix" src="data:image/png;base64,' +
                gateway.qrBase64 +
                '">'
              : "") +
            '</div><div><p>No computador, escaneie o QR Code com o aplicativo do seu banco. No celular, copie o código abaixo e use a opção Pix copia e cola.</p><label class="m-field"><span>Código Pix copia e cola</span><textarea data-pix-code readonly rows="3">' +
            esc(gateway.qrCode) +
            "</textarea></label>" +
            action("Copiar código Pix", "copyPix") +
            "</div></div>";
        } else if (gateway.cardSubmitted || p.checkout) {
          controls += notice(
            "O Mercado Pago está verificando o pagamento. Aguarde a confirmação. Se o envio não foi confirmado, atualize o status para retomar a mesma cobrança.",
          );
        } else {
          controls +=
            notice(
              "A conexão com o pagamento não foi concluída. Tente novamente para abrir a mesma cobrança.",
            ) +
            action("Tentar abrir pagamento novamente", "retryCheckout", {
              id: p.id,
              method: p.method,
              useCredit: p.creditRequested,
            });
        }
        controls += update;
      } else if (p.status === "pending" && !p.checkout) {
        controls =
          notice(
            "A conexão com o pagamento não foi concluída. Tente novamente para abrir a mesma cobrança.",
          ) +
          action("Tentar abrir pagamento novamente", "retryCheckout", {
            id: p.id,
            method: p.method,
            useCredit: p.creditRequested,
          });
      } else if (p.status === "pending") {
        controls =
          notice(
            p.checkout?.environment === "test"
              ? "Ambiente de testes: nenhum dinheiro real será movimentado. A reserva aguarda o resultado do provedor."
              : "Conclua o pagamento no provedor. A reserva aguarda sua confirmação.",
          ) +
          (safeLink
            ? '<a class="m-button" href="' +
              esc(link.href) +
              '" target="_blank" rel="noopener noreferrer">Abrir pagamento</a>'
            : "") +
          update;
      } else
        controls = notice(
          "Esta cobrança foi encerrada. Confira seus pedidos para continuar.",
        );
      const tripGroups = new Map();
      for (const trip of details.trips || []) {
        const key = JSON.stringify([
          trip.direction,
          trip.origin,
          trip.destination,
          trip.meeting,
          trip.notes,
          trip.detailsAvailable,
        ]);
        if (!tripGroups.has(key)) tripGroups.set(key, []);
        tripGroups.get(key).push(trip);
      }
      const tripSections = [...tripGroups.values()]
        .map((trips) => {
          const trip = trips[0],
            multiple = details.trips.length > 1;
          return (
            "<article><h3>" +
            (multiple
              ? esc(trip.origin) + " → " + esc(trip.destination)
              : esc(dateLabel(trip.date)) + " · " + esc(trip.time)) +
            "</h3>" +
            (multiple
              ? ""
              : "<p>" +
                esc(trip.origin) +
                " → " +
                esc(trip.destination) +
                "</p>") +
            (multiple
              ? ""
              : "<p><strong>Valor deste trecho: " +
                esc(
                  Number.isSafeInteger(trip.cents)
                    ? cash(trip.cents)
                    : "indisponível",
                ) +
                "</strong></p>") +
            (trip.detailsAvailable
              ? "<p><strong>Ponto de encontro</strong><br>" +
                esc(trip.meeting) +
                "</p>" +
                (trip.notes
                  ? "<p><strong>Observações do motorista</strong><br>" +
                    esc(trip.notes) +
                    "</p>"
                  : '<p class="m-help">Sem observações adicionais do motorista.</p>')
              : '<p class="m-help">Detalhes do encontro protegidos. Confira a situação do pedido.</p>') +
            (multiple
              ? '<ul class="m-trip-times" aria-label="Datas, horários e valores deste trajeto">' +
                trips
                  .map(
                    (item) =>
                      "<li>" +
                      esc(dateLabel(item.date)) +
                      " · " +
                      esc(item.time) +
                      "<br><strong>" +
                      esc(
                        Number.isSafeInteger(item.cents)
                          ? cash(item.cents)
                          : "Valor indisponível",
                      ) +
                      "</strong></li>",
                  )
                  .join("") +
                "</ul>"
              : "") +
            "</article>"
          );
        })
        .join("");
      return (
        (connectedState.tripReviewChangedId === p.id &&
        p.status === "awaiting" &&
        details.tripReview
          ? '<div role="status">' +
            notice(
              "Detalhes atualizados. Confira o encontro e as observações antes de continuar.",
            ) +
            "</div>"
          : "") +
        '<div class="m-checkout m-payment-layout"><section>' +
        (expiredBeforeCheckout
          ? '<span class="m-badge warn">Prazo encerrado</span>'
          : p.status === "awaiting"
            ? '<span class="m-badge">Pagamento não iniciado</span>'
            : mp
              ? '<span class="m-badge">' +
                esc(states[state] || "Pagamento em análise") +
                "</span>"
              : badge(p.status)) +
        '<h2 class="m-section">' +
        (details.type === "mensal" ? "Seu período de caronas" : "Sua carona") +
        "</h2><p>Com " +
        esc(details.driverName) +
        " · " +
        details.rideCount +
        (details.rideCount === 1 ? " trecho" : " trechos") +
        "</p>" +
        ((p.status === "awaiting" || p.status === "pending") &&
        details.trips?.length
          ? '<section class="m-trip-review" aria-label="Trechos e encontro">' +
            (details.trips.length > 1
              ? '<p class="m-help">Valores contratados por trecho, com os descontos aplicáveis já incluídos.</p>'
              : "") +
            tripSections +
            "</section>"
          : "") +
        '<h2 class="m-section">Resumo do pagamento</h2>' +
        summary([
          ["Total contratado", cash(p.amountCents)],
          ["Taxa da plataforma incluída no total", cash(details.platformCents)],
        ]) +
        '<details class="m-payment-breakdown" data-payment-breakdown><summary>Detalhes do valor e do crédito</summary>' +
        summary([
          ["Carona antes do desconto", cash(details.subtotalCents)],
          ...(details.discountCents
            ? [["Desconto mensal", "− " + cash(details.discountCents)]]
            : []),
          [
            p.confirmed ? "Crédito usado" : "Crédito previsto na confirmação",
            cash(p.creditCents),
          ],
          [
            "Valor no provedor",
            p.status === "awaiting"
              ? "Definido na próxima etapa"
              : cash(p.externalCents),
          ],
        ]) +
        '</details></section><section class="m-payment-method" aria-label="Pagamento">' +
        controls +
        "</section></div>"
      );
    }

    async function wallet() {
      const version = connectedState.epoch;
      const [w, transfers, configuration, debt] = await Promise.all([
        request("/wallet"),
        request("/transfers?" + pageQuery("transfers")),
        request("/payments/config"),
        request("/wallet/debt"),
      ]);
      const debtPaymentId =
        configuration.provider === "mercadopago"
          ? connectedState.ref.startsWith("debt:")
            ? connectedState.ref.slice(5)
            : debt.activePaymentId
          : null;
      const debtPayment = debtPaymentId
        ? await request(
            "/wallet/debt/payments/" + encodeURIComponent(debtPaymentId),
          )
        : null;
      const refundDetails = new Map();
      const creditReview =
        configuration.provider === "mercadopago"
          ? await request("/wallet/refund-review")
          : null;
      if (configuration.provider === "mercadopago") {
        const results = await Promise.allSettled(
          transfers.items
            .filter((x) => x.type === "refund")
            .map(async (x) => [
              x.id,
              await request("/transfers/" + x.id + "/refunds"),
            ]),
        );
        for (const result of results)
          if (result.status === "fulfilled") refundDetails.set(...result.value);
      }
      if (
        configuration.provider === "mercadopago" &&
        version === connectedState.epoch &&
        !connectedState.disposed
      ) {
        let checking = false;
        connectedState.pollTimer = setInterval(async () => {
          if (
            checking ||
            document.hidden ||
            version !== connectedState.epoch ||
            connectedState.disposed
          )
            return;
          checking = true;
          try {
            const [
              latestWallet,
              latestTransfers,
              latestReview,
              latestDebt,
              latestDebtPayment,
            ] = await Promise.all([
              request("/wallet"),
              request("/transfers?" + pageQuery("transfers")),
              request("/wallet/refund-review"),
              request("/wallet/debt"),
              debtPaymentId
                ? request(
                    "/wallet/debt/payments/" +
                      encodeURIComponent(debtPaymentId),
                  )
                : null,
            ]);
            if (version !== connectedState.epoch || connectedState.disposed)
              return;
            if (
              JSON.stringify(latestWallet) !== JSON.stringify(w) ||
              JSON.stringify(latestTransfers) !== JSON.stringify(transfers) ||
              JSON.stringify(latestReview) !== JSON.stringify(creditReview) ||
              JSON.stringify(latestDebt) !== JSON.stringify(debt) ||
              JSON.stringify(latestDebtPayment) !== JSON.stringify(debtPayment)
            )
              navigate(
                "wallet",
                debtPaymentId ? "debt:" + debtPaymentId : "",
                true,
              );
          } catch (error) {
            if (version === connectedState.epoch && !connectedState.disposed)
              tell(error.message);
          } finally {
            checking = false;
          }
        }, 10000);
      }
      connectedState.current = w;
      const driver = connectedState.me.driverStatus === "approved";
      const balances = [
        [
          configuration.payoutEnabled
            ? "Disponível para saque"
            : "Saldo do motorista",
          w.available,
        ],
        [
          creditReview?.creditUnderReview
            ? "Crédito em análise"
            : "Crédito para caronas",
          w.credit,
        ],
        ["Parcelas futuras", w.future],
        ["Em análise", w.blocked],
        ["Pedidos em processamento", w.processing],
        ["Sacado", w.withdrawn],
        ["Devoluções concluídas", w.returned],
      ];
      const primary = driver ? balances[0] : balances[1];
      const debtOpen =
        debtPayment &&
        ["CREATED", "PENDING", "PROCESSING", "UNDER_REVIEW"].includes(
          debtPayment.state,
        );
      const debtPanel =
        debt.purchasesBlocked || debtPayment
          ? '<section class="m-section" aria-labelledby="debt-title"><h2 id="debt-title">' +
            (debt.purchasesBlocked
              ? "Regularize seu valor devido"
              : "Regularização") +
            "</h2>" +
            (debt.purchasesBlocked
              ? '<p class="m-balance-amount">' +
                cash(debt.dueCents) +
                "</p><p>Um pagamento que gerou crédito já utilizado foi estornado. Novas compras ficam bloqueadas até a confirmação da regularização.</p>"
              : '<p role="status">Você não tem valor devido. O bloqueio por dívida foi removido.</p>') +
            (debtPayment
              ? "<p><strong>Pix de regularização: " +
                cash(debtPayment.amountCents) +
                "</strong></p>" +
                (debtPayment.state === "APPROVED"
                  ? '<p role="status">Pagamento confirmado.</p>'
                  : debtOpen
                    ? "<p>" +
                      (debtPayment.state === "CREATED"
                        ? "A criação do Pix ainda não foi confirmada. Atualize a regularização para retomar a mesma cobrança."
                        : "Aguardando confirmação do pagamento. Abrir ou copiar o Pix não libera novas compras.") +
                      "</p>"
                    : "<p>" +
                      esc(
                        {
                          EXPIRED: "Este Pix expirou.",
                          REJECTED: "O pagamento não foi aprovado.",
                          CANCELLED: "Este Pix foi cancelado.",
                          REFUNDED: "A regularização foi estornada.",
                          PARTIALLY_REFUNDED:
                            "Parte da regularização foi estornada.",
                        }[debtPayment.state] ||
                          "Confira o estado da regularização.",
                      ) +
                      "</p>") +
                (debtOpen && debtPayment.qrCode
                  ? '<div class="m-pix-instructions"><div>' +
                    (debtPayment.qrBase64 &&
                    /^[A-Za-z0-9+/=]+$/.test(debtPayment.qrBase64)
                      ? '<img class="m-pix-qr" width="224" height="224" alt="QR Code do Pix de regularização" src="data:image/png;base64,' +
                        debtPayment.qrBase64 +
                        '">'
                      : "") +
                    '</div><div><p>Escaneie o QR Code com seu banco ou use Pix copia e cola.</p><label class="m-field"><span>Código Pix de regularização</span><textarea data-pix-code readonly rows="3">' +
                    esc(debtPayment.qrCode) +
                    "</textarea></label>" +
                    action("Copiar código Pix", "copyPix") +
                    "</div></div>"
                  : "") +
                (debtOpen
                  ? action(
                      "Atualizar regularização",
                      "syncDebt",
                      { id: debtPayment.id },
                      "secondary",
                    )
                  : "")
              : "") +
            (debt.purchasesBlocked &&
            !debtOpen &&
            configuration.provider === "mercadopago"
              ? '<p class="m-help">O valor é calculado pelo UniCarona, sem juros ou taxa adicional de regularização.</p>' +
                action("Regularizar com Pix", "payDebt")
              : "") +
            "</section>"
          : "";
      return (
        debtPanel +
        '<section class="m-balance"><h2>' +
        esc(primary[0]) +
        '</h2><p class="m-balance-amount">' +
        cash(primary[1]) +
        '</p><p class="m-help">' +
        (driver
          ? configuration.payoutEnabled
            ? "Recebimentos concluídos e liberados."
            : "Seu saldo está registrado. O saque ainda não está disponível."
          : creditReview?.creditUnderReview
            ? "O uso e a devolução deste crédito aguardam conferência financeira. Procure o suporte."
            : "Seu crédito pode ser usado na confirmação de uma carona.") +
        "</p></section>" +
        (driver && creditReview?.creditUnderReview
          ? notice(
              "O uso e a devolução do seu crédito aguardam conferência financeira. Procure o suporte.",
            )
          : "") +
        '<section class="m-section"><h2>Composição dos valores</h2>' +
        summary(
          balances
            .filter((row) => row !== primary)
            .map(([label, value]) => [label, cash(value)]),
        ) +
        "</section>" +
        notice(
          "Embarque não libera o valor. Somente parcelas concluídas e sem bloqueio ficam disponíveis; crédito e saldo do motorista são separados.",
        ) +
        '<div class="m-grid m-section">' +
        (w.available && configuration.payoutEnabled
          ? form(
              "transfer",
              field(
                "cents",
                "Valor do saque em R$",
                "",
                "number",
                'required min="0.01" step="0.01" max="' +
                  w.available / 100 +
                  '"',
              ),
              "Solicitar saque",
              { type: "withdraw" },
            )
          : "") +
        (w.credit &&
        configuration.refundEnabled &&
        !creditReview?.creditUnderReview
          ? form(
              "transfer",
              '<p class="m-help">A devolução será feita nos meios de pagamento de origem. O valor solicitado fica reservado enquanto é conferido.</p>' +
                field(
                  "cents",
                  "Valor da devolução em R$",
                  "",
                  "number",
                  'required min="0.01" step="0.01" max="' +
                    w.credit / 100 +
                    '"',
                ),
              "Solicitar devolução",
              { type: "refund" },
            )
          : "") +
        '</div><h2 class="m-section">Transferências</h2>' +
        listMarkup(
          transfers.items,
          (x) =>
            '<article class="m-row"><div><h3>' +
            esc(x.type === "withdraw" ? "Saque" : "Devolução") +
            " · " +
            cash(x.cents) +
            '</h3><p class="m-help">' +
            esc(UI.dateTime(x.createdAt)) +
            (x.cadence
              ? " · preferência " +
                (x.cadence === "monthly" ? "mensal" : "diária")
              : "") +
            "</p>" +
            (refundDetails.has(x.id)
              ? summary([
                  [
                    "Devolução confirmada",
                    cash(refundDetails.get(x.id).returnedCents),
                  ],
                  [
                    "Restaurado ao crédito",
                    cash(refundDetails.get(x.id).restoredCents),
                  ],
                  [
                    "Ainda em processamento",
                    cash(refundDetails.get(x.id).processingCents),
                  ],
                ])
              : configuration.provider === "mercadopago" && x.type === "refund"
                ? notice(
                    "Não foi possível consultar os valores desta devolução. Atualize para conferir o resultado.",
                  ) + action("Atualizar valores", "reload", {}, "secondary")
                : "") +
            "</div>" +
            badge(x.status) +
            "</article>",
        ) +
        pages(transfers, "transfers")
      );
    }

    async function submitCheckout(input) {
      try {
        await post("/payments/" + input.id + "/checkout", {
          method: input.method,
          useCredit: input.useCredit === true,
          ...(input.tripReview ? { tripReview: input.tripReview } : {}),
        });
        connectedState.tripReviewChangedId = null;
      } catch (error) {
        if (error.code === "TRIP_REVIEW_REQUIRED") {
          connectedState.tripReviewChangedId = input.id;
          await navigate("checkout", input.id, true);
          return;
        }
        throw error;
      }
      return navigate("checkout", input.id, true);
    }
    return { checkout, wallet, submitCheckout };
  }
  window.UniCaronaFeatures ||= {};
  window.UniCaronaFeatures.payments = Object.freeze({ create });
})();
