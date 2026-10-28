const assert = require("node:assert/strict");

async function testAcademicProfile(browser) {
  const page = await browser.newPage({ viewport: { width: 390, height: 844 } });
  page.setDefaultTimeout(5000);
  const errors = [];
  const sent = [];
  page.on("pageerror", (error) => errors.push(error.message));
  page.on("request", (request) => {
    if (!["GET", "HEAD"].includes(request.method())) sent.push(request.url());
  });
  const selectCourse = async (query) => {
    await page.locator("#signup-course").fill(query);
    await page.locator("#signup-course").press("ArrowDown");
    await page.locator("#signup-course").press("Enter");
  };
  const continueButton = () => page.getByRole("button", { name: "Continuar", exact: true });
  const submit = async () => {
    await continueButton().click();
    await page.getByRole("heading", { name: "Confirme seu e-mail.", exact: true }).waitFor();
    return page.evaluate(() => signupData.academicPlacement);
  };
  const back = async () => {
    await page.getByRole("link", { name: "← Voltar ao perfil", exact: true }).click();
    await page.getByRole("heading", { name: "Seu perfil acadêmico.", exact: true }).waitFor();
  };
  const invalid = async (id, message) => {
    await continueButton().click();
    assert.equal(await page.evaluate(() => location.hash), "#cadastro-perfil");
    assert.equal(await page.locator(`#signup-${id}`).getAttribute("aria-invalid"), "true");
    assert.equal(await page.locator(`#signup-${id}`).evaluate((field) => field === document.activeElement), true);
    assert.match(await page.locator(`#signup-${id}-error`).textContent(), message);
  };
  try {
    await page.goto("http://127.0.0.1:4173/login.html#cadastro");
    await page.locator("#signup-name").fill("Ana Souza");
    await page.locator("#signup-email").fill("ana.souza@example.com");
    await page.locator("#signup-password").fill("SenhaDeDemonstracao123!");
    await page.locator("#signup-confirm").fill("SenhaDeDemonstracao123!");
    await page.locator("#signup-birth").fill("1995-05-01");
    await page.locator("#signup-truth").check();
    await page.locator("#signup-terms").check();
    await continueButton().click();
    await page.getByRole("heading", { name: "Seu perfil acadêmico.", exact: true }).waitFor();
    for (const id of ["period", "shift", "class"]) {
      assert.equal(await page.locator(`#signup-${id}`).isDisabled(), true);
    }
    await selectCourse("ANALISE E DESENVOLVIMENTO");
    for (const id of ["period", "shift", "class"]) {
      assert.equal(await page.locator(`#signup-${id}`).isDisabled(), false);
    }
    assert.equal(await page.locator("#signup-period").evaluate((field) => field.required), true);
    assert.equal(await page.locator("#signup-shift").evaluate((field) => field.required), true);
    assert.equal(await page.locator("#signup-class").evaluate((field) => field.tagName), "SELECT");
    assert.equal(await page.locator("#signup-class").evaluate((field) => field.required), false);
    assert.deepEqual(await page.locator("#signup-class option").allTextContents(), ["Selecione sua turma", "Turma A", "Turma B", "Turma C"]);
    assert.deepEqual(await page.locator("#signup-shift option").allTextContents(), ["Selecione o horário", "Manhã", "Tarde", "Noite", "O dia todo", "Horários variados"]);

    // Advice starts collapsed and is available to keyboard users.
    assert.equal(await page.locator("#signup-class-help").evaluate((help) => help.open), false);
    assert.equal(await page.locator("#signup-class-help p").isVisible(), false);
    await page.locator("#signup-class-help summary").focus();
    await page.keyboard.press("Enter");
    assert.equal(await page.locator("#signup-class-help p").isVisible(), true);
    assert.match(await page.locator("#signup-class-help p").textContent(), /não houver divisão de turmas ou existir só uma, selecione Turma A/);
    await page.keyboard.press("Enter");
    assert.equal(await page.locator("#signup-class-help p").isVisible(), false);

    await page.locator("#signup-neighborhood").fill("Taguatinga");
    await page.locator("#signup-neighborhood").press("ArrowDown");
    await page.locator("#signup-neighborhood").press("Enter");
    await page.locator("#signup-phone").fill("(61) 99999-9999");
    await invalid("period", /Informe o número do seu período/);
    await page.locator("#signup-period").fill("3");
    await invalid("shift", /Selecione o horário das suas aulas/);
    await page.locator("#signup-shift").selectOption("night");
    assert.deepEqual(await submit(), {
      source: "self-reported", periodStatus: "numbered", periodNumber: 3,
      shift: "night", classLabel: null,
    });
    assert.equal((await page.locator(".state-note").textContent()).includes("autodeclaradas e opcionais"), false);
    await back();

    // Invalid numbers survive back navigation and never advance the form.
    await page.locator("#signup-period").fill("2.5");
    await page.locator("#signup-class").selectOption("B");
    await page.getByRole("link", { name: "← Voltar", exact: true }).click();
    await continueButton().click();
    await page.getByRole("heading", { name: "Seu perfil acadêmico.", exact: true }).waitFor();
    assert.equal(await page.locator("#signup-period").inputValue(), "2.5");
    assert.equal(await page.locator("#signup-shift").inputValue(), "night");
    assert.equal(await page.locator("#signup-class").inputValue(), "B");
    for (const value of ["0", "-1", "2.5", "abc", "1e2", "9007199254740992"]) {
      await page.locator("#signup-period").fill(value);
      await invalid("period", /inteiro positivo/);
    }
    await page.locator("#signup-period").fill("3");
    await page.locator("#signup-course").focus();
    await page.locator("#signup-course").press("Enter");
    assert.equal(await page.locator("#signup-period").inputValue(), "3");
    assert.equal(await page.locator("#signup-class").inputValue(), "B");
    for (const classLabel of ["A", "B", "C"]) {
      await page.locator("#signup-class").selectOption(classLabel);
      assert.deepEqual(await submit(), {
        source: "self-reported", periodStatus: "numbered", periodNumber: 3,
        shift: "night", classLabel,
      });
      await back();
      assert.equal(await page.locator("#signup-class").inputValue(), classLabel);
    }

    // Existing non-numbered situations remain explicit; shift is still required.
    for (const periodStatus of ["irregular", "undefined"]) {
      await page.locator("#signup-period-status").selectOption(periodStatus);
      assert.equal(await page.locator("#signup-period").isVisible(), false);
      assert.equal(await page.locator("#signup-period").evaluate((field) => field.required), false);
      await page.locator("#signup-shift").selectOption("");
      await invalid("shift", /Selecione o horário/);
      await page.locator("#signup-shift").selectOption("night");
      const placement = await submit();
      assert.equal(placement.periodStatus, periodStatus);
      assert.equal(placement.periodNumber, null);
      await back();
      assert.equal(await page.locator("#signup-period-status").inputValue(), periodStatus);
    }
    await page.locator("#signup-period-status").selectOption("numbered");
    assert.equal(await page.locator("#signup-period").inputValue(), "");
    await invalid("period", /Informe o número/);
    await page.locator("#signup-period").fill("4");
    for (const shift of ["morning", "afternoon", "night", "full-time", "variable"]) {
      await page.locator("#signup-shift").selectOption(shift);
      assert.equal((await submit()).shift, shift);
      await back();
    }

    // Reject values added outside the predefined lists, including HTML-looking text.
    for (const id of ["shift", "period-status", "class"]) {
      const previous = await page.locator(`#signup-${id}`).inputValue();
      await page.locator(`#signup-${id}`).evaluate((field) => {
        field.add(new Option("Inválido", '<img src=x onerror="window.academicInjected=true">'));
        field.value = '<img src=x onerror="window.academicInjected=true">';
      });
      await invalid(id, /Escolha|Selecione/);
      assert.equal(await page.locator(".academic-selection img").count(), 0);
      assert.equal(await page.evaluate(() => window.academicInjected), undefined);
      await page.locator(`#signup-${id}`).selectOption(previous);
    }

    await selectCourse("ENGENHARIA DE SOFTWARE");
    for (const id of ["period", "shift", "class"]) {
      assert.equal(await page.locator(`#signup-${id}`).inputValue(), "");
      assert.equal(await page.locator(`#signup-${id}`).isDisabled(), false);
    }
    await page.locator("#signup-class").selectOption("A");
    await page.locator("#signup-course").fill("");
    for (const id of ["period", "shift", "class"]) {
      assert.equal(await page.locator(`#signup-${id}`).inputValue(), "");
      assert.equal(await page.locator(`#signup-${id}`).isDisabled(), true);
    }
    await selectCourse("ENGENHARIA DE SOFTWARE");
    await invalid("period", /Informe o número/);

    await page.locator("#signup-period-status").focus();
    await page.keyboard.press("Tab");
    assert.equal(await page.locator("#signup-period").evaluate((field) => field === document.activeElement), true);
    await page.keyboard.type("4");
    await page.keyboard.press("Tab");
    assert.equal(await page.locator("#signup-shift").evaluate((field) => field === document.activeElement), true);
    await page.locator("#signup-shift").selectOption("night");
    await page.keyboard.press("Tab");
    assert.equal(await page.locator("#signup-class").evaluate((field) => field === document.activeElement), true);
    await page.locator("#signup-class").selectOption("A");
    await page.keyboard.press("Tab");
    assert.equal(await page.locator("#signup-class-help summary").evaluate((field) => field === document.activeElement), true);
    await page.keyboard.press("Enter");
    for (const width of [320, 390, 768, 1024, 1440]) {
      await page.setViewportSize({ width, height: 900 });
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false);
      for (const id of ["period-status", "period", "shift", "class"]) {
        assert.match(await page.locator(`#signup-${id}`).getAttribute("aria-describedby"), /signup-academic-status/);
      }
      if ([320, 1440].includes(width)) {
        await page.evaluate(() => window.scrollTo({ top: 0, behavior: "instant" }));
        await page.screenshot({ path: `docs/previews/signup-academic-${width}.png`, fullPage: true });
      }
    }
    assert.deepEqual(sent, []);
    assert.deepEqual(errors, []);
    assert.equal(await page.evaluate(() => localStorage.length + sessionStorage.length), 0);
    await page.reload();
    await page.getByRole("heading", { name: "Seu perfil acadêmico.", exact: true }).waitFor();
    assert.equal(await page.evaluate(() => signupData.academicPlacement), null);
    assert.equal(await page.locator("#signup-course").inputValue(), "");
    assert.equal(await page.locator("#signup-class").inputValue(), "");
    assert.equal(await page.locator("#signup-period").isDisabled(), true);
    console.log("PASS: required period and shift, predefined optional classes, collapsed advice, validation, drafts, course changes, keyboard and 5 widths.");
  } finally {
    await page.close();
  }
}

module.exports = testAcademicProfile;
if (require.main === module) {
  const { chromium } = require("playwright");
  require("node:fs").mkdirSync("docs/previews", { recursive: true });
  (async () => {
    const browser = await chromium.launch({ executablePath: "C:/Program Files/Google/Chrome/Application/chrome.exe" });
    try { await testAcademicProfile(browser); } finally { await browser.close(); }
  })().catch((error) => { console.error(error); process.exit(1); });
}
