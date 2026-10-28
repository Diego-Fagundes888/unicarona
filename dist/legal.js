/* Published legal documents, safe reading and version-bound references. */
(() => {
  "use strict";
  const UI = window.UniCaronaUI, A = window.UniCaronaAPI, esc = UI.escape;
  const contact = document.querySelector?.('meta[name="unicarona-privacy-contact"]')?.getAttribute("content") || "privacy@example.invalid";
  const titles = Object.freeze({ terms: "Termos de Uso", privacy: "Privacidade, retenção e cookies", participation: "Passageiro, avulsa e mensal", payments: "Pagamentos e cancelamentos", safety: "Segurança e encerramento", "driver-terms": "Termos do Motorista" });
  const validVersion = value => typeof value === "string" && /^[A-Za-z0-9._-]{1,64}$/.test(value) && ![".", ".."].includes(value);
  const validReference = r => r && Object.hasOwn(titles, r.slug) && validVersion(r.version) && /^[a-f0-9]{64}$/.test(r.contentSha256);
  const href = r => {
    if (!validReference(r)) throw Error("A versão do documento não é válida. Atualize a página.");
    return "legal.html?document=" + encodeURIComponent(r.slug) + "&version=" + encodeURIComponent(r.version);
  };
  let flight;
  async function manifest(fresh = false) {
    if (fresh) flight = null;
    flight ||= A.request("/legal/documents").then(data => {
      if (!Array.isArray(data.documents) || data.documents.length !== 6 || new Set(data.documents.map(d => d.reference?.slug)).size !== 6 || data.documents.some(d => !validReference(d.reference) || !Array.isArray(d.requiredFor) || typeof d.title !== "string" || !d.title.trim())) throw Error("Não foi possível conferir os documentos. Tente novamente.");
      if (references(data).length !== 5 || references(data, "service").length !== 5 || references(data, "service", true).length !== 6) throw Error("O conjunto de documentos precisa ser atualizado. Tente novamente.");
      return data;
    }).catch(error => { flight = null; throw error; });
    return flight;
  }
  function references(data, scope = "registration", driver = false) {
    return data.documents.filter(d => d.requiredFor.includes(scope) || (driver && d.requiredFor.includes("driver"))).map(d => ({ ...d.reference }));
  }
  function documentLinks(data, scope = "registration") {
    return '<ul class="p-details-list">' + data.documents.filter(d => d.requiredFor.includes(scope)).map(d => '<li><a href="' + esc(href(d.reference)) + '" target="_blank" rel="noopener">' + esc(d.title) + '</a></li>').join("") + '</ul>';
  }
  function safeHref(value) {
    try {
      const url = new URL(value, location.origin);
      if (url.protocol === "https:") return url.href;
      if (url.origin === location.origin && url.pathname === "/legal.html") return url.pathname + url.search;
      if (url.protocol === "mailto:" && /^[^\s?&#<>]+@[^\s?&#<>]+$/.test(url.pathname)) return url.href;
    } catch {}
    return null;
  }
  function inline(text, depth = 0) {
    if (depth > 3) return esc(text);
    const pattern = /\[([^\]\n]+)\]\(([^\s)]+)\)|\*\*([^*\n]+)\*\*|`([^`\n]+)`/g;
    let output = "", from = 0;
    for (const match of text.matchAll(pattern)) {
      output += esc(text.slice(from, match.index));
      if (match[1]) { const url = safeHref(match[2]); output += url ? '<a rel="noopener noreferrer" href="' + esc(url) + '">' + esc(match[1]) + '</a>' : esc(match[1]); }
      else output += match[3] ? '<strong>' + inline(match[3], depth + 1) + '</strong>' : '<code>' + esc(match[4]) + '</code>';
      from = match.index + match[0].length;
    }
    return output + esc(text.slice(from));
  }
  function markdown(body) {
    if (typeof body !== "string" || body.length > 250000) throw Error("O documento não pôde ser carregado.");
    const lines = body.replace(/\r\n?/g, "\n").split("\n"), result = [];
    let paragraph = [], list = "", heading = 0, tables = 0;
    const flush = () => { if (paragraph.length) result.push('<p>' + inline(paragraph.join(" ")) + '</p>'); paragraph = []; if (list) { result.push('</' + list + '>'); list = ""; } };
    const cells = line => line.trim().replace(/^\||\|$/g, "").split("|").map(c => c.trim());
    for (let n = 0; n < lines.length; n++) {
      const line = lines[n].trim(), h = /^(#{1,4})\s+(.+)$/.exec(line), item = /^(?:[-*]|\d+\.)\s+(.+)$/.exec(line);
      if (!line) { flush(); continue; }
      if (h) { flush(); const level = Math.max(2, h[1].length); result.push('<h' + level + ' id="section-' + ++heading + '">' + inline(h[2]) + '</h' + level + '>'); continue; }
      if (line === "---") { flush(); result.push('<hr>'); continue; }
      if (line.includes("|") && n + 1 < lines.length && /^\|?\s*:?-{3,}/.test(lines[n + 1].trim())) {
        flush();const headers = cells(line);n++;
        const hint = 'document-table-hint-' + ++tables;
        let table = '<div class="p-table-scroll" role="region" aria-label="Tabela do documento" aria-describedby="' + hint + '" tabindex="0"><table><thead><tr>' + headers.map(v => '<th scope="col">' + inline(v) + '</th>').join("") + '</tr></thead><tbody>';
        while (n + 1 < lines.length && lines[n + 1].trim().includes("|")) { const values = cells(lines[++n]); table += '<tr>' + headers.map((_, i) => '<td>' + inline(values[i] || "") + '</td>').join("") + '</tr>'; }
        result.push('<p id="' + hint + '" class="p-table-hint" hidden>Deslize a tabela para os lados para ver todas as colunas. Com o teclado, selecione a tabela e use as setas.</p>' + table + '</tbody></table></div>');continue;
      }
      if (item) {
        const kind = /^\d/.test(line) ? "ol" : "ul";
        if (paragraph.length || (list && list !== kind)) flush();
        if (!list) { result.push('<' + kind + '>'); list = kind; }
        result.push('<li>' + inline(item[1]) + '</li>');continue;
      }
      if (list) flush();paragraph.push(line);
    }
    flush();return result.join("");
  }
  window.UniCaronaLegal = Object.freeze({ contact, titles, manifest, references, documentLinks, validReference, href, markdown });
  if (!document.body.hasAttribute("data-legal-reader")) return;
  const params = new URLSearchParams(location.search), slug = params.get("document") || "terms", version = params.get("version"), article = document.querySelector("#legal-document");
  const navigation = document.querySelector(".p-legal-nav"), compact = matchMedia("(max-width:900px)");
  const adaptTables = () => article.querySelectorAll('.p-table-scroll').forEach(region => {
    const hint = document.getElementById(region.getAttribute('aria-describedby'));
    if (hint) hint.hidden = region.scrollWidth <= region.clientWidth + 1;
  });
  window.addEventListener('resize', adaptTables);
  const adaptNavigation = () => { navigation.open = !compact.matches; };
  adaptNavigation();compact.addEventListener("change", adaptNavigation);
  document.querySelector("[data-legal-navigation]").innerHTML = Object.entries(titles).map(([key, title]) => '<a href="legal.html?document=' + key + '"' + (key === slug ? ' aria-current="page"' : '') + '>' + esc(title) + '</a>').join("");
  async function load() {
    const restoreFocus = article.contains(document.activeElement);
    article.setAttribute("aria-busy", "true");
    article.innerHTML = UI.loading("Carregando documento…");
    try {
      if (!Object.hasOwn(titles, slug) || (version !== null && !validVersion(version))) throw Error("Endereço de documento inválido. Escolha um documento no menu.");
      const data = await A.request("/legal/documents/" + slug + (version ? "/" + version : ""));
      if (!validReference(data.reference) || data.reference.slug !== slug || (version && data.reference.version !== version) || typeof data.title !== "string" || !data.title.trim() || typeof data.body !== "string") throw Error("A versão recebida não corresponde ao documento solicitado.");
      document.title = data.title + " | UniCarona";
      let body = data.body;
      if (body.startsWith("# " + data.title + "\n")) body = body.slice(body.indexOf("\n") + 1);
      article.innerHTML = '<h1>' + esc(data.title) + '</h1><p class="p-version">Versão ' + esc(data.reference.version) + ' · Publicada em ' + esc(UI.date(data.publishedAt)) + ' · Vigência em ' + esc(UI.date(data.effectiveAt)) + '</p>' + (version ? '<p>Esta é a versão indicada no endereço. <a href="legal.html?document=' + slug + '">Consultar a versão atual</a>.</p>' : '') + markdown(body) + '<details class="m-disclosure"><summary>Identificador desta versão</summary><p class="p-hash"><code>' + esc(data.reference.contentSha256) + '</code></p></details>';
    } catch (error) {
      document.title = "Documento indisponível | UniCarona";
      article.innerHTML = '<h1>Documento indisponível</h1><p role="alert">' + esc(error.message) + '</p>' + (version && Object.hasOwn(titles, slug) ? '<p><a href="legal.html?document=' + slug + '">Consultar a versão atual deste documento</a></p>' : '') + '<p>Contato de privacidade: <a href="mailto:' + esc(contact) + '">' + esc(contact) + '</a>.</p><button type="button" class="m-button" data-legal-retry>Tentar novamente</button>';
    } finally { article.removeAttribute("aria-busy");adaptTables();if (restoreFocus) article.focus({ preventScroll: true }); }
  }
  article.addEventListener("click", event => { if (event.target.closest("[data-legal-retry]")) load(); });
  load();
})();
