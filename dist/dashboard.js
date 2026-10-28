/* Dashboard shell. Domain flows use the authenticated API. */
(() => {
  "use strict";
  const icon = (name) =>
    `<span class="d-icon">${window.UniCaronaUI.icon(name)}</span>`;
  const brand = `<a class="d-brand" href="index.html" aria-label="UniCarona, página inicial"><img class="uc-brand-logo" src="assets/brand/logo-horizontal-color.svg" width="194" height="44" alt=""></a>`;
  const navItems = [
    ["inicio", "Início", "home"],
    ["buscar", "Buscar", "search"],
    ["oferecer", "Oferecer", "plus"],
    ["minhas", "Minhas caronas", "calendar"],
    ["perfil", "Perfil", "user"],
  ];
  const nav = () =>
    navItems
      .map(
        ([page, label, symbol]) =>
          `<button class="d-nav-item" type="button" data-page="${page}">${icon(symbol)}<span>${label}</span></button>`,
      )
      .join("");
  const template = `
    <a class="d-skip" href="#dashboard-content">Pular para o conteúdo</a>
    <div class="d-workspace">
      <header class="d-topbar">
        ${brand}
        <nav class="d-header-nav" aria-label="Navegação do painel">${nav()}</nav>
        <div class="d-topbar-actions">
          <button class="d-icon-button" type="button" data-info="notifications" aria-label="Notificações">${icon("bell")}</button>
          <button class="d-icon-button d-avatar" type="button" data-page="perfil" aria-label="Abrir meu perfil"><span data-profile-initials>UC</span></button>
        </div>
      </header>
      <main class="d-content" id="dashboard-content" tabindex="-1">
        <section data-view="inicio" aria-label="Início do painel">
          <div class="d-home-grid">
            <section class="d-panel d-search-panel" aria-labelledby="dashboard-search-title">
              <div class="d-page-heading">
                <h1 id="dashboard-title" tabindex="-1">Buscar uma carona</h1>
                <p class="d-page-intro" data-page-intro>Encontre uma carona para a UNICEPLAC ou compartilhe o caminho.</p>
              </div>
              <h2 class="d-sr-only" id="dashboard-search-title">Seu trajeto</h2>
              <form id="dashboard-search" novalidate>
                <div class="d-segmented" role="group" aria-label="Modalidade de carona">
                  <button type="button" data-ride-type="avulsa" aria-pressed="true">${icon("car")}Avulsa</button>
                  <button type="button" data-ride-type="mensal" aria-pressed="false">${icon("calendar")}Mensal</button>
                </div>
                <div class="d-directions" role="group" aria-label="Sentido do trajeto">
                  <button type="button" data-direction="ida" aria-pressed="true">Ida para a UNICEPLAC</button>
                  <button type="button" data-direction="volta" aria-pressed="false">Volta da UNICEPLAC</button>
                </div>
                <p class="d-monthly-hint" hidden>O mensal reúne pessoas do mesmo curso, período e turma. Confira os dias e os trajetos antes de combinar.</p>
                <div class="d-field d-route-field">
                  <label for="dashboard-origin">Origem</label>${icon("pin")}
                  <input id="dashboard-origin" name="origin" placeholder="De onde você sai?" maxlength="100" required autocomplete="off" aria-describedby="dashboard-origin-error">
                  <p class="d-field-error" id="dashboard-origin-error"></p>
                </div>
                <div class="d-field d-route-field">
                  <label for="dashboard-destination">Destino</label>${icon("pin")}
                  <input id="dashboard-destination" name="destination" value="UNICEPLAC" readonly maxlength="100" required autocomplete="off" aria-describedby="dashboard-destination-error">
                  <p class="d-field-error" id="dashboard-destination-error"></p>
                </div>
                <details class="d-schedule" open>
                  <summary>${icon("calendar")}<span data-schedule-label>Hoje · horário livre</span>${icon("arrow")}</summary>
                  <div class="d-date-grid">
                  <div class="d-field" data-date-field>
                    <label for="dashboard-date">Data da carona</label>
                    <input id="dashboard-date" name="date" type="date" required aria-describedby="dashboard-date-error dashboard-date-hint">
                    <p class="d-date-hint" id="dashboard-date-hint" data-home-date-label></p>
                    <p class="d-field-error" id="dashboard-date-error"></p>
                  </div>
                  <div class="d-field">
                    <label for="dashboard-time">A partir de <span>(opcional)</span></label>
                    <input id="dashboard-time" name="time" type="time" aria-describedby="dashboard-time-error">
                    <p class="d-field-error" id="dashboard-time-error"></p>
                  </div>
                  </div>
                </details>
                <button class="d-button d-search-submit" type="submit">Buscar caronas ${icon("arrow")}</button>
                <p class="d-caption">A vaga é confirmada após o pagamento e a verificação de disponibilidade.</p>
              </form>
            </section>
            <details class="d-map-disclosure" open>
              <summary>Mapa da região do campus</summary>
            <section class="d-map" aria-label="Mapa da região da UNICEPLAC">
              <div class="d-map-canvas" data-map-canvas role="group" aria-label="Mapa navegável da região da UNICEPLAC no Gama"></div>
              <div class="d-map-status" data-map-status role="status" aria-live="polite">
                ${icon("pin")}<h3 data-map-title>Mapa da região do campus</h3>
                <p data-map-copy>Consulte a região da UNICEPLAC no Gama. A busca não depende do mapa.</p>
                <button class="m-button secondary" type="button" data-map-retry>Tentar novamente</button>
              </div>
              <div class="d-map-campus">${icon("pin")}<span>UNICEPLAC<small>Gama, DF</small></span></div>
              <p class="d-map-note">© <a href="https://www.openstreetmap.org/copyright" target="_blank" rel="noopener noreferrer">OpenStreetMap contributors</a></p>
              <a class="d-map-link" href="https://www.openstreetmap.org/?mlat=-16.003049875168237&amp;mlon=-48.051869232920424#map=14/-16.00305/-48.05187" target="_blank" rel="noopener noreferrer">Abrir mapa</a>
            </section>
            </details>
          </div>
          <div class="d-support-grid">
            <div class="d-side-stack">
              <section class="d-panel d-next-trip">
                <h2>Sua próxima carona</h2>
                <div class="d-empty-compact">
                  <span class="d-empty-icon">${icon("calendar")}</span>
                  <h3>Nenhuma carona marcada</h3>
                  <p>Quando uma reserva for confirmada, o horário e os detalhes aparecerão aqui.</p>
                </div>
                <button class="d-button d-button-secondary" type="button" data-page="minhas">Ver minhas caronas</button>
              </section>
              <section class="d-profile-card">
                <div class="d-small-heading">${icon("user")}<h2>Seu perfil acadêmico</h2></div>
                <p class="d-course" data-profile-course>Seu curso aparece aqui</p>
                <p class="d-placement" data-profile-placement>Período, turno e turma do seu cadastro.</p>
                <button class="d-text-button" type="button" data-page="perfil">Ver meu perfil</button>
              </section>
            </div>
            <div class="d-quick-actions">
            <section class="d-action-card">${icon("calendar")}<div><h3>Mensal da sua turma</h3><p>Uma rotina de caronas com quem compartilha seus dias de aula.</p><button class="d-text-button" type="button" data-info="monthly">Conhecer o mensal</button></div></section>
            <section class="d-action-card">${icon("car")}<div><h3>Vai de carro?</h3><p>Seu trajeto pode levar mais alguém. Veja como começar a oferecer.</p><button class="d-text-button" type="button" data-page="oferecer">Quero oferecer carona</button></div></section>
            </div>
          </div>
          <div class="d-safety-strip">${icon("shield")}<p>Confira o motorista, o carro e o ponto de encontro antes de embarcar.</p><button class="d-text-button" type="button" data-info="safety">Ver cuidados</button></div>
        </section>
        <footer class="d-footer">UniCarona · Projeto independente, sem vínculo oficial com a UNICEPLAC.</footer>
      </main>
    </div>
    <nav class="d-bottom-nav" aria-label="Navegação do painel no celular">${nav()}</nav>`;
  const headings = {
    inicio: [
      "Buscar uma carona",
      "Encontre uma carona para a UNICEPLAC ou compartilhe o caminho.",
    ],
    buscar: [
      "Encontre uma carona.",
      "Escolha o sentido, a modalidade e o horário que combinam com você.",
    ],
  };
  let mountedRoot = null;
  let scheduleBreakpoint = null;
  let syncSchedule = null;
  let connected = null;
  let campusMap = null;
  function today() {
    const date = new Date();
    return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, "0")}-${String(date.getDate()).padStart(2, "0")}`;
  }
  function destroy() {
    campusMap?.destroy();
    campusMap = null;
    connected?.destroy();
    connected = null;
    if (scheduleBreakpoint && syncSchedule)
      scheduleBreakpoint.removeEventListener("change", syncSchedule);
    scheduleBreakpoint = null;
    syncSchedule = null;
    mountedRoot?.remove();
    mountedRoot = null;
    document.body.classList.remove("dashboard", "dashboard-mode");
  }
  function mount(profile) {
    if (!profile?.id || !window.UniCaronaConnectedUI?.attach)
      throw Error(
        "Não foi possível carregar a sessão e os recursos do painel.",
      );
    destroy();
    document.body.classList.add("dashboard");
    if (document.querySelector(".auth-main"))
      document.body.classList.add("dashboard-mode");
    const root = document.createElement("div");
    root.className = "d-shell";
    root.innerHTML = template; // Static markup only; profile and route values use textContent/value.
    document.body.append(root);
    mountedRoot = root;
    const $ = (selector) => root.querySelector(selector);
    const text = (selector, value) => {
      $(selector).textContent = value;
    };
    const name = profile.name || "";
    text(
      "[data-profile-initials]",
      name
        .split(/\s+/)
        .slice(0, 2)
        .map((part) => [...part][0] || "")
        .join("")
        .toLocaleUpperCase("pt-BR"),
    );
    const origin = $("#dashboard-origin");
    const destination = $("#dashboard-destination");
    const date = $("#dashboard-date");
    const time = $("#dashboard-time");
    origin.value = profile.region || "";
    date.value = today();
    date.min = today();
    let rideType = "avulsa";
    let direction = "ida";
    const pageHeading = $(".d-page-heading");
    const schedule = $(".d-schedule");
    scheduleBreakpoint = window.matchMedia("(max-width: 700px)");
    const mapDisclosure = $(".d-map-disclosure");
    syncSchedule = () => {
      schedule.open = !scheduleBreakpoint.matches;
      mapDisclosure.open = !scheduleBreakpoint.matches;
    };
    syncSchedule();
    scheduleBreakpoint.addEventListener("change", syncSchedule);
    campusMap = window.UniCaronaCampusMap?.mount($(".d-map"), mapDisclosure);
    function updateScheduleLabel() {
      const selectedDate =
        date.value === today()
          ? "Hoje"
          : date.value
            ? new Intl.DateTimeFormat("pt-BR", {
                day: "2-digit",
                month: "2-digit",
              }).format(new Date(`${date.value}T12:00:00`))
            : "Escolha a data";
      text(
        "[data-schedule-label]",
        `${rideType === "mensal" ? "Mensal" : selectedDate} · ${time.value || "horário livre"}`,
      );
      text(
        "[data-home-date-label]",
        date.value
          ? new Intl.DateTimeFormat("pt-BR", { dateStyle: "medium" }).format(
              new Date(`${date.value}T12:00:00`),
            )
          : "Selecione uma data.",
      );
    }
    function setView(page, focus = true) {
      if (connected)
        return connected.navigate(page === "oferecer" ? "driver" : page);
      homeSetView(page, focus);
    }
    function homeSetView(page, focus = true) {
      if (!headings[page]) return;
      const searching = page === "inicio" || page === "buscar";
      root.classList.toggle("d-searching", searching);
      if (searching) $(".d-search-panel").prepend(pageHeading);
      else $(".d-content").prepend(pageHeading);
      root.querySelectorAll("[data-view]").forEach((view) => {
        view.hidden =
          view.dataset.view !== (page === "buscar" ? "inicio" : page);
      });
      root.querySelectorAll(".d-nav-item[data-page]").forEach((button) => {
        if (button.dataset.page === page)
          button.setAttribute("aria-current", "page");
        else button.removeAttribute("aria-current");
      });
      text("#dashboard-title", headings[page][0]);
      text("[data-page-intro]", headings[page][1]);
      document.title = `${navItems.find(([key]) => key === page)[1]} | UniCarona`;
      if (focus) {
        window.scrollTo({ top: 0, behavior: "instant" });
        $("#dashboard-title").focus({ preventScroll: true });
        if (page === "buscar")
          (direction === "ida" ? origin : destination).focus();
      }
    }
    function clearErrors() {
      root.querySelectorAll(".d-field input").forEach((input) => {
        input.removeAttribute("aria-invalid");
        $(`#${input.id}-error`).textContent = "";
      });
    }
    function error(input, message) {
      if (input.closest(".d-schedule")) schedule.open = true;
      input.setAttribute("aria-invalid", "true");
      $(`#${input.id}-error`).textContent = message;
      input.focus();
    }
    function openInformation(key) {
      return connected.info(key);
    }
    root.addEventListener("click", (event) => {
      const page = event.target.closest("[data-page]");
      if (page) setView(page.dataset.page);
      const informationTrigger = event.target.closest("[data-info]");
      if (informationTrigger)
        openInformation(informationTrigger.dataset.info, informationTrigger);
      const type = event.target.closest("[data-ride-type]");
      if (type) {
        rideType = type.dataset.rideType;
        root
          .querySelectorAll("[data-ride-type]")
          .forEach((button) =>
            button.setAttribute("aria-pressed", String(button === type)),
          );
        $("[data-date-field]").hidden = rideType === "mensal";
        date.disabled = rideType === "mensal";
        updateScheduleLabel();
        $(".d-monthly-hint").hidden = rideType !== "mensal";
        clearErrors();
      }
      const directionTrigger = event.target.closest("[data-direction]");
      if (
        directionTrigger &&
        directionTrigger.dataset.direction !== direction
      ) {
        const region = direction === "ida" ? origin.value : destination.value;
        direction = directionTrigger.dataset.direction;
        origin.value = direction === "ida" ? region : "UNICEPLAC";
        destination.value = direction === "ida" ? "UNICEPLAC" : region;
        origin.readOnly = direction !== "ida";
        destination.readOnly = direction === "ida";
        destination.placeholder = "Para onde você vai?";
        root
          .querySelectorAll("[data-direction]")
          .forEach((button) =>
            button.setAttribute(
              "aria-pressed",
              String(button === directionTrigger),
            ),
          );
        clearErrors();
      }
    });
    $("#dashboard-search").addEventListener("input", () => {
      updateScheduleLabel();
      clearErrors();
    });
    $("#dashboard-search").addEventListener("submit", (event) => {
      event.preventDefault();
      clearErrors();
      if (!origin.value.trim())
        return error(origin, "Informe de onde você vai sair.");
      if (!destination.value.trim())
        return error(destination, "Informe para onde você vai.");
      const normalize = (value) => value.trim().toLocaleLowerCase("pt-BR");
      if (normalize(origin.value) === normalize(destination.value))
        return error(
          direction === "ida" ? origin : destination,
          "Escolha um local diferente da UNICEPLAC.",
        );
      if (
        rideType === "avulsa" &&
        (!date.value || !date.validity.valid || date.value < today())
      )
        return error(date, "Escolha uma data a partir de hoje.");
      if (!time.validity.valid)
        return error(time, "Informe um horário válido.");
      return connected.search({
        type: rideType,
        direction,
        region: (direction === "ida" ? origin.value : destination.value).trim(),
        date: date.value,
        time: time.value,
      });
    });
    setView("inicio", false);
    const intent = new URLSearchParams(location.search),
      region = intent.get("region")?.trim();
    if (region && region.length <= 100) {
      origin.value = region;
      root
        .querySelector(
          '[data-direction="' +
            (intent.get("direction") === "volta" ? "volta" : "ida") +
            '"]',
        )
        .click();
      root
        .querySelector(
          '[data-ride-type="' +
            (intent.get("type") === "mensal" ? "mensal" : "avulsa") +
            '"]',
        )
        .click();
    }
    connected = window.UniCaronaConnectedUI.attach({
      root,
      profile,
      home: homeSetView,
    });
    window.scrollTo({ top: 0, behavior: "instant" });
    $("#dashboard-title").focus({ preventScroll: true });
  }
  window.UniCaronaDashboard = Object.freeze({ mount, destroy });
  if (document.body.hasAttribute("data-dashboard-standalone")) {
    let loading = false;
    async function bootstrap() {
      if (loading) return;
      loading = true;
      destroy();
      const state = document.createElement("main");
      state.className = "m-app m-bootstrap";
      state.setAttribute("aria-busy", "true");
      state.innerHTML =
        '<a class="m-bootstrap-brand" href="index.html" aria-label="UniCarona, página inicial"><img class="uc-brand-logo" src="assets/brand/logo-horizontal-color.svg" width="194" height="44" alt=""></a><h1 tabindex="-1">Carregando seu painel…</h1>' +
        window.UniCaronaUI.loading("Consultando sua conta.");
      document.body.append(state);
      try {
        const profile = await window.UniCaronaAPI.request("/users/me");
        state.remove();
        mount(profile);
      } catch (error) {
        if (error.status === 401) {
          const next = location.pathname + location.search;
          location.replace(
            next === "/dashboard.html"
              ? "login.html#login"
              : "login.html?next=" + encodeURIComponent(next) + "#login",
          );
          return;
        }
        state.removeAttribute("aria-busy");
        state.innerHTML =
          '<h1 tabindex="-1">Não foi possível carregar o painel.</h1><p role="alert"></p><button class="m-button" type="button">Tentar novamente</button> <a href="login.html">Voltar para entrar</a>';
        state.querySelector("p").textContent = error.message;
        state.querySelector("h1").focus();
        state.querySelector("button").addEventListener("click", () => {
          state.remove();
          bootstrap();
        });
      } finally {
        loading = false;
      }
    }
    bootstrap();
    window.addEventListener("pagehide", destroy);
    window.addEventListener("pageshow", (event) => {
      if (event.persisted) bootstrap();
    });
  }
})();
