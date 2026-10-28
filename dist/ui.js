/* Shared presentation components. Business rules and API calls stay in their flows. */
(() => {
  "use strict";
  let sequence = 0;
  const escape = (value) =>
    String(value ?? "").replace(
      /[&<>"']/g,
      (c) =>
        ({
          "&": "&amp;",
          "<": "&lt;",
          ">": "&gt;",
          '"': "&quot;",
          "'": "&#39;",
        })[c],
    );
  const money = (cents) =>
    new Intl.NumberFormat("pt-BR", {
      style: "currency",
      currency: "BRL",
    }).format((cents || 0) / 100);
  const date = (value) =>
    value
      ? new Intl.DateTimeFormat("pt-BR", { dateStyle: "medium" }).format(
          new Date(value.slice(0, 10) + "T12:00:00"),
        )
      : "—";
  const dateTime = (value) =>
    value
      ? new Intl.DateTimeFormat("pt-BR", {
          dateStyle: "medium",
          timeStyle: "short",
          timeZone: "America/Sao_Paulo",
        }).format(new Date(value))
      : "—";
  const paths = {
    swap: '<path d="M8 3v17m-4-4 4 4 4-4M16 21V4m-4 4 4-4 4 4"/>',
    home: '<path d="m3 10 9-7 9 7v10H3Z"/><path d="M9 20v-7h6v7"/>',
    search: '<circle cx="10.5" cy="10.5" r="6.5"/><path d="m16 16 5 5"/>',
    plus: '<path d="M12 5v14M5 12h14"/>',
    minus: '<path d="M5 12h14"/>',
    user: '<circle cx="12" cy="8" r="4"/><path d="M4 21v-2a8 8 0 0 1 16 0v2"/>',
    bell: '<path d="M6 9a6 6 0 0 1 12 0c0 7 3 7 3 9H3c0-2 3-2 3-9m4 12h4"/>',
    arrow: '<path d="M4 12h16m-6-6 6 6-6 6"/>',
    shield:
      '<path d="m12 3 8 3v6c0 5-8 9-8 9s-8-4-8-9V6Z"/><path d="m8 12 3 3 5-6"/>',
    car: '<path d="m5 7-2 7v6h3v-3h12v3h3v-6l-2-7ZM3 13h18M7 7l1-3h8l1 3M6 16h1m10 0h1"/>',
    pin: '<path d="M19 10c0 5-7 11-7 11S5 15 5 10a7 7 0 1 1 14 0Z"/><circle cx="12" cy="10" r="2"/>',
    cap: '<path d="m2 8 10-5 10 5-10 5Zm4 2v7c4 3 8 3 12 0v-7m4-2v8"/>',
    flask:
      '<path d="M9 3h6m-5 0v7l-6 9a1 1 0 0 0 1 2h14a1 1 0 0 0 1-2l-6-9V3M7 16h10"/>',

    mail: '<rect x="3" y="5" width="18" height="14" rx="2"/><path d="m3 6 9 7 9-7"/>',
    arrowLeft: '<path d="M20 12H4m6-6-6 6 6 6"/>',
    info: '<circle cx="12" cy="12" r="9"/><path d="M12 11v6m0-10v1"/>',
    check: '<path d="m5 12 4 4L19 6"/>',
    warning: '<path d="m12 3 10 18H2Z"/><path d="M12 9v5m0 3v1"/>',
    calendar:
      '<rect x="3" y="5" width="18" height="16" rx="2"/><path d="M7 3v4m10-4v4M3 10h18"/>',
    close: '<path d="m6 6 12 12M6 18 18 6"/>',
  };
  const icon = (name) =>
    '<svg class="uc-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' +
    (paths[name] || paths.info) +
    "</svg>";
  function button(label, attributes = {}, variant = "") {
    const attrs = Object.entries(attributes)
      .map(([key, value]) => " " + key + '="' + escape(value) + '"')
      .join("");
    return (
      '<button type="button" class="m-button ' +
      escape(variant) +
      '"' +
      attrs +
      ">" +
      escape(label) +
      "</button>"
    );
  }
  function field(
    name,
    label,
    value = "",
    type = "text",
    extra = "",
    help = "",
  ) {
    const id = "uc-field-" + ++sequence;
    const hint =
      type === "date"
        ? value
          ? "Data selecionada: " + date(value)
          : "Selecione a data no calendário."
        : help;
    const input =
      '<input id="' +
      id +
      '" aria-labelledby="' +
      id +
      '-label" name="' +
      escape(name) +
      '" type="' +
      escape(type) +
      '" value="' +
      escape(value) +
      '" ' +
      extra +
      (hint ? ' aria-describedby="' + id + '-hint"' : "") +
      ">";
    const control =
      type === "file"
        ? '<span class="m-file-control">' +
          input +
          '<span class="m-file-visual" aria-hidden="true"><strong>Selecionar arquivo</strong><span data-file-name>Nenhum selecionado</span></span></span>'
        : input;
    return (
      '<div class="m-field"><label class="m-field-label" for="' +
      id +
      '"><span class="m-field-caption" id="' +
      id +
      '-label">' +
      escape(label) +
      "</span>" +
      control +
      "</label>" +
      (hint
        ? '<small id="' +
          id +
          '-hint" class="m-help"' +
          (type === "date" ? " data-date-label" : "") +
          ">" +
          escape(hint) +
          "</small>"
        : "") +
      "</div>"
    );
  }
  function select(name, label, options, value = "") {
    const id = "uc-field-" + ++sequence;
    return (
      '<div class="m-field"><label class="m-field-caption" for="' +
      id +
      '">' +
      escape(label) +
      '</label><select id="' +
      id +
      '" name="' +
      escape(name) +
      '">' +
      options
        .map(
          ([key, text, disabled]) =>
            '<option value="' +
            escape(key) +
            '"' +
            (String(key) === String(value) ? " selected" : "") +
            (disabled ? " disabled" : "") +
            ">" +
            escape(text) +
            "</option>",
        )
        .join("") +
      "</select></div>"
    );
  }
  const check = (name, label, checked = false, value = "on") =>
    '<label class="m-check"><input type="checkbox" name="' +
    escape(name) +
    '" value="' +
    escape(value) +
    '"' +
    (checked ? " checked" : "") +
    "><span>" +
    escape(label) +
    "</span></label>";
  const area = (name, label) =>
    '<label class="m-field"><span>' +
    escape(label) +
    '</span><textarea name="' +
    escape(name) +
    '" required minlength="3" maxlength="2000"></textarea></label>';
  const form = (operation, fields, label = "Salvar", data = {}) =>
    '<form data-c-form="' +
    escape(operation) +
    '" data-input="' +
    escape(JSON.stringify(data)) +
    '">' +
    fields +
    '<p class="m-error" role="alert" tabindex="-1"></p><button type="submit" class="m-button">' +
    escape(label) +
    "</button></form>";
  const group = (title, fields, description = "") =>
    '<fieldset class="m-form-group"><legend>' +
    escape(title) +
    "</legend>" +
    (description ? '<p class="m-help">' + escape(description) + "</p>" : "") +
    fields +
    "</fieldset>";
  const disclosure = (title, content, attributes = "", className = "") =>
    '<details class="m-disclosure ' +
    escape(className) +
    '" ' +
    attributes +
    "><summary>" +
    escape(title) +
    '</summary><div class="m-disclosure-content">' +
    content +
    "</div></details>";
  const person = (name, description = "", image = "") =>
    '<div class="m-person">' +
    (image ||
      '<span class="m-avatar" aria-hidden="true">' +
        escape(
          String(name)
            .trim()
            .split(/\s+/)
            .slice(0, 2)
            .map((part) => [...part][0] || "")
            .join("")
            .toLocaleUpperCase("pt-BR"),
        ) +
        "</span>") +
    "<div><h2>" +
    escape(name) +
    "</h2>" +
    (description ? '<p class="m-help">' + escape(description) + "</p>" : "") +
    "</div></div>";
  const itinerary = (origin, destination, meeting = "") =>
    '<div class="m-itinerary"><p><span class="uc-sr-only">Origem: </span>' +
    escape(origin) +
    '</p><p><span class="uc-sr-only">Destino: </span>' +
    escape(destination) +
    "</p></div>" +
    (meeting
      ? '<p class="m-meeting"><strong>Ponto de encontro</strong><br>' +
        escape(meeting) +
        "</p>"
      : "");
  const notice = (text, tone = "info") =>
    '<div class="m-notice ' +
    escape(tone) +
    '">' +
    icon(
      tone === "error" || tone === "warning"
        ? "warning"
        : tone === "success"
          ? "check"
          : "info",
    ) +
    "<p>" +
    escape(text) +
    "</p></div>";
  const summary = (rows) =>
    '<dl class="m-summary">' +
    rows
      .map(
        ([label, value]) =>
          "<div><dt>" +
          escape(label) +
          "</dt><dd>" +
          escape(value ?? "—") +
          "</dd></div>",
      )
      .join("") +
    "</dl>";
  const empty = (title, text, actions = "") =>
    '<section class="m-empty">' +
    icon("calendar") +
    "<h2>" +
    escape(title) +
    "</h2><p>" +
    escape(text) +
    "</p>" +
    actions +
    "</section>";
  const loading = (label = "Carregando conteúdo…") =>
    '<div class="m-loading" role="status"><span class="uc-sr-only">' +
    escape(label) +
    '</span><div class="m-skeleton" aria-hidden="true"></div><div class="m-skeleton short" aria-hidden="true"></div><div class="m-skeleton" aria-hidden="true"></div></div>';
  function busy(button, active, label = "Aguarde…") {
    if (active) {
      button.dataset.ucLabel = button.textContent;
      button.textContent = label;
      button.disabled = true;
      button.setAttribute("aria-busy", "true");
    } else {
      if (button.dataset.ucLabel) button.textContent = button.dataset.ucLabel;
      delete button.dataset.ucLabel;
      button.disabled = false;
      button.removeAttribute("aria-busy");
    }
  }
  window.UniCaronaUI = Object.freeze({
    escape,
    money,
    date,
    dateTime,
    icon,
    button,
    field,
    select,
    check,
    area,
    form,
    group,
    disclosure,
    person,
    itinerary,
    notice,
    summary,
    empty,
    loading,
    busy,
  });
})();
