require("node:fs").mkdirSync("docs/previews", { recursive: true });
const {
  chromium,
} = require("playwright");
(async () => {
  const b = await chromium.launch({
    executablePath: "C:/Program Files/Google/Chrome/Application/chrome.exe",
  });
  const p = await b.newPage();
  const errors = [];
  p.on("pageerror", (e) => errors.push(e.message));
  await p.goto("http://127.0.0.1:4173");
  for (const width of [320, 390, 768, 900, 1024, 1440]) {
    await p.setViewportSize({ width, height: 900 });
    if (
      await p.evaluate(() => document.documentElement.scrollWidth > innerWidth)
    )
      throw Error("Overflow " + width);
    if ([390, 1440].includes(width))
      await p.screenshot({
        path: `docs/previews/unicarona-copy-${width}.png`,
        fullPage: true,
      });
  }
  await p.locator("#ride-search button[type=submit]").click();
  if (
    (await p.locator("#origin").evaluate((e) => e.validationMessage)) !==
    "Informe de onde você vai sair."
  )
    throw Error("Origin validation");
  await p.locator("#origin").fill("uniceplac");
  await p.locator("#ride-search button[type=submit]").click();
  if (
    (await p.locator("#destination").evaluate((e) => e.validationMessage)) !==
    "Informe locais diferentes para testar o trajeto."
  )
    throw Error("Same route validation");
  await p.locator("#origin").fill("Lago Azul");
  await p.locator("[data-type=mensal]").click();
  await p.locator("#ride-search button[type=submit]").click();
  await p
    .getByRole("heading", { name: "Demonstração de busca mensal" })
    .waitFor();
  if (
    !(await p.locator("#modal-content").textContent()).includes(
      "Lago Azul → UNICEPLAC",
    )
  )
    throw Error("Route");
  await p.keyboard.press("Escape");
  for (const id of [
    "avulsa",
    "monthly",
    "monthlyRules",
    "oferecer",
    "minhas",
    "perfil",
    "notifications",
    "safety",
    "terms",
    "privacy",
    "help",
  ]) {
    const trigger = p.locator(`[data-modal="${id}"]:visible`).first();
    await trigger.click();
    await p.locator("dialog[open]").waitFor();
    if (id === "monthly" || id === "monthlyRules") {
      if (
        !(await p.locator("#modal-content").textContent()).includes(
          "sem repasse ao motorista",
        )
      )
        throw Error("Missing billing condition");
    }
    await p.keyboard.press("Escape");
  }
  for (const selector of [".header-signup", ".section-actions .underlined"]) {
    await p.locator(selector).click();
    await p.waitForURL("**/login.html#cadastro");
    await p
      .getByRole("heading", { name: "Vamos criar seu acesso.", exact: true })
      .waitFor();
    await p.goto("http://127.0.0.1:4173/index.html");
  }
  await p.locator('.desktop-nav [data-modal="oferecer"]').click();
  await p
    .getByRole("button", { name: "Conhecer o cadastro", exact: true })
    .click();
  await p.waitForURL("**/login.html#cadastro");
  await p
    .getByRole("heading", { name: "Vamos criar seu acesso.", exact: true })
    .waitFor();
  await p.goto("http://127.0.0.1:4173/index.html");
  await p.getByText("Já posso reservar?", { exact: true }).click();
  if (
    !(await p
      .getByText(
        "Você pode testar cadastro, reservas e pagamentos fictícios no painel. Esta versão não oferece viagens nem movimenta dinheiro real.",
        { exact: true },
      )
      .isVisible())
  )
    throw Error("FAQ");
  await p.locator("[data-search-focus]").click();
  if (
    !(await p.locator("#origin").evaluate((e) => document.activeElement === e))
  )
    throw Error("Search focus");
  await p.setViewportSize({ width: 390, height: 844 });
  await p.locator(".bottom-nav [data-modal=minhas]").click();
  await p.getByRole("button", {name:"Entrar para acompanhar",exact:true}).click();
  await p.getByRole("heading", {name:"Entre na sua conta.",exact:true}).waitFor();
  await p.locator("#email").fill("aluno@example.com");
  await p.locator("#password").fill("CaronaDemo18!");
  await p.getByRole("button", {name:"Entrar",exact:true}).click();
  await p.getByRole("heading", {name:"Buscar uma carona",exact:true}).waitFor();
  await p.getByRole("button", {name:"Ver cuidados",exact:true}).click();
  await p.getByRole("heading", {name:"Segurança e ocorrências",exact:true}).waitFor();
  await p.goto("http://127.0.0.1:4173");
  await p.getByRole("link", { name: "Entrar", exact: true }).click();
  await p
    .getByRole("heading", { name: "Entre na sua conta.", exact: true })
    .waitFor();
  if (errors.length) throw Error(errors.join(";"));
  console.log(
    "PASS: 6 widths, dialog destinations, billing visibility, validation, search, FAQ, focus, trip safety. Zero JS errors.",
  );
  await b.close();
})().catch((e) => {
  console.error(e);
  process.exit(1);
});
