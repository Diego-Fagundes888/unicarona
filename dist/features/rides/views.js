/* rides: explicit dependencies and session-owned state. */
(() => {
  "use strict";
  function create({
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
  }) {
    async function results() {
      const params = new URLSearchParams(pageQuery("results"));
      params.set("direction", connectedState.query.direction);
      if (connectedState.query.region)
        params.set("region", connectedState.query.region);
      if (connectedState.query.time)
        params.set("time", connectedState.query.time);
      const monthly = connectedState.query.type === "mensal";
      if (!monthly) {
        params.set("date", connectedState.query.date);
        for (const key of ["automatic", "onlyAvailable", "maxPrice"])
          if (connectedState.query[key])
            params.set(key, String(connectedState.query[key]));
      } else if (connectedState.query.compatibleOnly)
        params.set("compatibleOnly", "true");
      const data = await request(
        (monthly ? "/routines" : "/rides") + "?" + params,
      );
      connectedState.current = data;
      const filters = form(
        "filters",
        monthly
          ? check(
              "compatibleOnly",
              "Somente da minha turma",
              connectedState.query.compatibleOnly,
            )
          : check(
              "onlyAvailable",
              "Somente com vagas",
              connectedState.query.onlyAvailable,
            ) +
              check(
                "automatic",
                "Aceite automático",
                connectedState.query.automatic,
              ) +
              field(
                "maxPrice",
                "Valor máximo por trecho em R$",
                connectedState.query.maxPrice
                  ? connectedState.query.maxPrice / 100
                  : "",
                "number",
                'min="0" step="0.01"',
              ),
        "Aplicar filtro",
      );
      const campus = connectedState.me.university || "Faculdade",
        origin =
          connectedState.query.direction === "ida"
            ? connectedState.query.region
            : campus,
        destination =
          connectedState.query.direction === "ida"
            ? campus
            : connectedState.query.region;
      const rows = data.items.length
        ? listMarkup(data.items, (r) =>
            monthly
              ? '<article class="m-row m-journey-row"><div><h3>' +
                esc(r.region) +
                " ↔ " +
                esc(campus) +
                "</h3><p>" +
                esc(r.driver.name) +
                '</p><p class="m-help">' +
                esc(academic(r.driver)) +
                '</p><p class="m-help">' +
                r.weekdays.map((d) => dayNames[d]).join(", ") +
                '</p><span class="m-badge ' +
                (r.compatible ? "good" : "") +
                '">' +
                (r.compatible ? "Mesma turma" : "Turma diferente") +
                "</span></div>" +
                go("Ver mensal", "monthly", r.id, "secondary") +
                "</article>"
              : rideRow(r),
          )
        : UI.empty(
            "Nenhuma carona encontrada",
            "Tente outra data ou retire um filtro para ampliar a busca.",
            go("Ajustar trajeto", "buscar", "", "secondary"),
          );
      return (
        '<div class="m-search-context"><h2>' +
        esc(origin) +
        " → " +
        esc(destination) +
        "</h2><p>" +
        esc(
          monthly ? "Rotinas mensais" : dateLabel(connectedState.query.date),
        ) +
        (connectedState.query.time
          ? " · a partir de " + esc(connectedState.query.time)
          : "") +
        "</p>" +
        go("Ajustar trajeto", "buscar", "", "quiet") +
        '</div><div class="m-grid m-results"><aside>' +
        UI.disclosure(
          "Filtrar caronas",
          filters,
          "data-responsive-details open",
          "m-filter-panel",
        ) +
        "</aside><div>" +
        rows +
        pages(data, "results") +
        "</div></div>"
      );
    }

    async function rideDetail() {
      const r = await request("/rides/" + connectedState.ref);
      connectedState.current = r;
      return (
        '<div class="m-grid m-ride-detail"><div>' +
        badge(r.status) +
        '<section class="m-trip-essential"><h2>' +
        esc(r.time) +
        " · " +
        esc(dateLabel(r.date)) +
        "</h2>" +
        UI.itinerary(r.origin, r.destination, r.meeting) +
        '</section><section class="m-section">' +
        UI.person(r.driver.name, academic(r.driver)) +
        "</section>" +
        summary([
          ["Carro", r.vehicle.model + " · " + r.vehicle.color],
          ["Placa", r.vehicle.plate],
          ["Vagas disponíveis", r.available],
        ]) +
        (r.notes
          ? '<section class="m-section"><h2>Sobre esta carona</h2><p>' +
            esc(r.notes) +
            "</p></section>"
          : "") +
        '</div><aside class="m-booking-panel"><h2>Contribuição por pessoa</h2><p class="m-price">' +
        cash(r.priceCents) +
        '</p><p class="m-help">Valor deste trecho.</p>' +
        notice(
          r.automatic
            ? "Aceite automático. A vaga será verificada novamente na confirmação do pagamento."
            : "O motorista precisa aceitar seu pedido antes do pagamento.",
        ) +
        (r.available && r.status === "planned"
          ? action("Solicitar vaga", "requestRide", { rideId: r.id })
          : notice("Esta oferta não tem uma vaga disponível.", "warning")) +
        '<div class="m-actions">' +
        go("Relatar ocorrência", "report", r.driverId, "quiet") +
        "</div></aside></div>"
      );
    }
    return { results, rideDetail };
  }
  window.UniCaronaFeatures ||= {};
  window.UniCaronaFeatures.rides = Object.freeze({ create });
})();
