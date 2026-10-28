const assert = require("node:assert/strict");
const { chromium } = require("playwright");
require("node:fs").mkdirSync("docs/previews", { recursive: true });

(async () => {
  const browser = await chromium.launch({ executablePath: "C:/Program Files/Google/Chrome/Application/chrome.exe" });
  try {
    const page = await browser.newPage();
    page.setDefaultTimeout(5000);
    const errors = [];
    const sent = [];
    page.on("pageerror", (error) => errors.push(error.message));
    page.on("request", (request) => {
      if (!["GET", "HEAD"].includes(request.method())) sent.push(request.url());
    });
    const heading = (name) => page.getByRole("heading", { name, exact: true });
    const button = (name) => page.getByRole("button", { name, exact: true });
    await page.goto("http://127.0.0.1:4173/dashboard.html");
    await heading("Buscar uma carona").waitFor();
    assert.equal(await page.locator(".d-result").isVisible(), false);
    assert.equal(await page.locator("#dashboard-destination").inputValue(), "UNICEPLAC");
    assert.equal(await page.locator("#dashboard-destination").evaluate((field) => field.readOnly), true);

    for (const width of [320, 390, 768, 900, 1024, 1440]) {
      await page.setViewportSize({ width, height: 900 });
      await page.waitForFunction((desktop) => document.querySelector('.d-schedule').open === desktop, width > 700);
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false, `overflow at ${width}`);
      assert.equal(await page.locator(".d-header-nav").isVisible(), width > 1000);
      assert.equal(await page.locator(".d-bottom-nav").isVisible(), width <= 1000);
      assert.equal(await page.locator(".d-schedule").evaluate((schedule) => schedule.open), width > 700);
      const layout = await page.evaluate(() => {
        const map = document.querySelector('.d-map').getBoundingClientRect();
        const search = document.querySelector('.d-search-panel').getBoundingClientRect();
        return { map: { x: map.x, y: map.y, width: map.width }, search: { x: search.x, y: search.y, width: search.width } };
      });
      if (width > 700) assert.ok(layout.map.x > layout.search.x + layout.search.width, 'map must sit beside the search');
      else assert.ok(layout.map.y < layout.search.y && layout.map.width >= layout.search.width, 'mobile map must precede the search sheet');
      if ([390, 1440].includes(width)) {
        await page.screenshot({ path: `docs/previews/dashboard-${width}.png`, fullPage: true });
      }
    }
    await page.setViewportSize({ width: 1280, height: 720 });
    const primary = await page.locator(".d-search-submit").boundingBox();
    assert.ok(primary.y + primary.height <= 720, "primary search button must be visible at 1280 x 720");
    await page.screenshot({ path: "docs/previews/dashboard-desktop.png" });
    await page.setViewportSize({ width: 1440, height: 900 });

    await button("Buscar caronas").click();
    assert.equal(await page.locator("#dashboard-origin").getAttribute("aria-invalid"), "true");
    assert.equal(await page.locator("#dashboard-origin").evaluate((field) => field === document.activeElement), true);
    await page.locator("#dashboard-origin").fill("uniceplac");
    await button("Buscar caronas").click();
    await page.getByText("Escolha um local diferente da UNICEPLAC.", { exact: true }).waitFor();
    await page.locator("#dashboard-origin").fill("Lago Azul");
    await page.locator("#dashboard-date").fill("2000-01-01");
    await button("Buscar caronas").click();
    assert.equal(await page.locator("#dashboard-date").getAttribute("aria-invalid"), "true");
    await page.locator("#dashboard-date").fill(await page.locator("#dashboard-date").getAttribute("min"));
    await page.locator("#dashboard-time").fill("19:00");
    await button("Buscar caronas").click();
    await heading("Caronas para seu trajeto").waitFor();
    assert.match(await page.locator(".m-app").innerText(), /Lago Azul/);
    await button("Ajustar trajeto").click();
    await button("Volta da UNICEPLAC").click();
    assert.equal(await page.locator("#dashboard-origin").inputValue(), "UNICEPLAC");
    assert.equal(await page.locator("#dashboard-destination").inputValue(), "Lago Azul");
    await button("Mensal").click();
    assert.equal(await page.locator("#dashboard-date").isDisabled(), true);
    const untrusted = '<img src=x onerror="window.dashboardInjected=true">';
    await page.locator("#dashboard-destination").fill(untrusted);
    await button("Buscar caronas").click();
    await heading("Caronas para seu trajeto").waitFor();
    assert.ok((await page.locator(".m-app").textContent()).includes(untrusted));
    assert.equal(await page.locator(".m-app img").count(), 0);
    assert.equal(await page.evaluate(() => window.dashboardInjected), undefined);
    await page.locator('.d-nav-item[data-page="minhas"]:visible').click();
    await heading("Minhas caronas").waitFor();
    await heading("Nada por aqui ainda").waitFor();
    await page.locator('.d-nav-item[data-page="oferecer"]:visible').click();
    await heading("Ofereça uma carona").waitFor();
    await page.locator('.d-nav-item[data-page="perfil"]:visible').click();
    await heading("Seu perfil").waitFor();
    assert.equal(await page.getByLabel("Nome completo", {exact:true}).inputValue(), "Estudante");
    await button("Notificações").click();
    await heading("Você está em dia").waitFor();
    await page.locator('.d-nav-item[data-page="inicio"]:visible').click();
    await button("Conhecer o mensal").click();
    await heading("Mensal da sua turma").waitFor();
    await page.getByText("O mensal exige mesmo curso", {exact:false}).waitFor();
    await page.locator('.d-nav-item[data-page="inicio"]:visible').click();
    await button("Ver cuidados").click();
    await heading("Segurança e ocorrências").waitFor();
    await page.locator('.d-nav-item[data-page="inicio"]:visible').click();
    await page.setViewportSize({width:390,height:844});
    await button("Avulsa").click();
    await page.locator('.d-schedule summary').click();
    await page.locator('#dashboard-date').fill('2000-01-01');
    await button("Ida para a UNICEPLAC").click();
    await page.locator('#dashboard-origin').fill('Lago Azul');
    await page.locator('.d-schedule summary').click();
    await button('Buscar caronas').click();
    assert.equal(await page.locator('.d-schedule').evaluate(e=>e.open),true);
    assert.equal(await page.locator('.d-map iframe').getAttribute('referrerpolicy'),'no-referrer');

    // Login enters the workspace and clears the password field.
    await page.goto("http://127.0.0.1:4173/login.html");
    await page.locator("#email").fill("aluno@example.com");
    await page.locator("#password").fill("CaronaDemo18!");
    await button("Entrar").click();
    await heading("Buscar uma carona").waitFor();
    assert.equal(await page.evaluate(() => location.hash), "#painel");
    assert.equal(await page.locator(".auth-main").isVisible(), false);
    assert.equal(await page.locator("#password").inputValue(), "");
    await page.screenshot({ path: "docs/previews/dashboard-login-390.png", fullPage: true });

    // Signup confirmation opens the same workspace with its in-memory profile.
    await page.goto("http://127.0.0.1:4173/login.html#cadastro");
    await page.locator("#signup-name").fill("Ana Souza");
    await page.locator("#signup-email").fill("ana.souza@example.com");
    await page.locator("#signup-password").fill("SenhaDeDemonstracao123!");
    await page.locator("#signup-confirm").fill("SenhaDeDemonstracao123!");
    await page.locator("#signup-birth").fill("1995-05-01");
    await page.locator("#signup-truth").check();
    await page.locator("#signup-terms").check();
    await button("Continuar").click();
    await heading("Seu perfil acadêmico.").waitFor();
    await page.locator("#signup-course").fill("ANALISE E DESENVOLVIMENTO");
    await page.locator("#signup-course").press("ArrowDown");
    await page.locator("#signup-course").press("Enter");
    await page.locator("#signup-period").fill("3");
    await page.locator("#signup-shift").selectOption("night");
    await page.locator("#signup-class").selectOption("A");
    await page.locator("#signup-neighborhood").fill("Taguatinga");
    await page.locator("#signup-neighborhood").press("ArrowDown");
    await page.locator("#signup-neighborhood").press("Enter");
    await page.locator("#signup-phone").fill("(61) 99999-9999");
    await button("Continuar").click();
    await heading("Confirme seu e-mail.").waitFor();
    await page.locator("#confirm-email-demo").click();
    await heading("Buscar uma carona").waitFor();
    assert.equal(await page.locator("[data-greeting]").textContent(), "Olá, Ana.");
    assert.equal(await page.locator("[data-profile-course]").textContent(), "Análise e Desenvolvimento de Sistemas");
    assert.equal(await page.locator("[data-profile-placement]").textContent(), "3º período · Noite · Turma A");
    assert.equal(await page.locator("#dashboard-origin").inputValue(), "Taguatinga · RA III · DF");
    assert.equal(await page.locator(".d-welcome").isVisible(), true);
    assert.equal(await page.evaluate(() => signupAccessDraft.password + signupAccessDraft.confirmation), "");
    await page.screenshot({ path: "docs/previews/dashboard-signup-390.png", fullPage: true });
    await page.setViewportSize({ width: 1280, height: 800 });
    await page.screenshot({ path: "docs/previews/dashboard-signup-desktop.png" });
    await button("Perfil").click();
    await heading("Seu perfil").waitFor();
    assert.equal(await page.getByLabel("Nome completo", {exact:true}).inputValue(), "Ana Souza");
    assert.equal(await page.getByLabel("Turma (opcional)", {exact:true}).inputValue(), "A");
    await page.getByRole("button", {name:"Configurações",exact:true}).last().click();
    await page.getByRole("link", { name: "Sair da sessão", exact: true }).click();
    await heading("Entre na sua conta.").waitFor();
    assert.equal(await page.locator(".d-shell").count(), 0);
    await page.goto("http://127.0.0.1:4173/login.html#painel");
    await heading("Buscar uma carona").waitFor();
    assert.equal(await page.locator("[data-greeting]").textContent(), "Olá, estudante.");
    assert.equal(await page.locator("[data-profile-course]").textContent(), "Complete seu perfil acadêmico");
    assert.equal(await page.evaluate(() => localStorage.length + sessionStorage.length), 0);
    assert.deepEqual(errors, []);
    assert.deepEqual(sent, []);
    console.log("PASS: dashboard entry from login/signup, profile in memory, search validation, directions, monthly, empty states, dialogs, keyboard focus, logout and 6 widths. Zero JS errors or data submissions.");
  } finally {
    await browser.close();
  }
})().catch((error) => { console.error(error); process.exit(1); });
