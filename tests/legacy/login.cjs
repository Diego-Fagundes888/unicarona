const {
  chromium,
} = require("playwright");
const assert = require("node:assert/strict");
require("node:fs").mkdirSync("docs/previews", { recursive: true });
(async () => {
  const browser = await chromium.launch({
    executablePath: "C:/Program Files/Google/Chrome/Application/chrome.exe",
  });
  try {
    const page = await browser.newPage();
    page.setDefaultTimeout(5000);
    const errors = [];
    const sent = [];
    page.on("pageerror", (e) => errors.push(e.message));
    page.on("request", (r) => {
      if (!["GET", "HEAD"].includes(r.method())) sent.push(r.url());
    });
    await page.goto("http://127.0.0.1:4173/login.html");
    await page
      .getByRole("heading", { name: "Entre na sua conta.", exact: true })
      .waitFor();
    await page.getByRole("button", { name: "Entrar", exact: true }).click();
    assert.equal(
      await page.locator("#email").getAttribute("aria-invalid"),
      "true",
    );
    await page.locator("#email").fill("aluno@example.com");
    await page.locator("#password").fill("errada");
    await page
      .getByRole("button", { name: "Mostrar senha", exact: true })
      .click();
    assert.equal(await page.locator("#password").getAttribute("type"), "text");
    await page
      .getByRole("button", { name: "Ocultar senha", exact: true })
      .click();
    await page.getByRole("button", { name: "Entrar", exact: true }).click();
    await page
      .getByText(
        "E-mail ou senha incorretos. Confira os dados e tente novamente.",
        { exact: true },
      )
      .waitFor();
    await page.locator("#password").fill("CaronaDemo18!");
    await page.getByRole("button", { name: "Entrar", exact: true }).click();
    await page
      .getByRole("heading", { name: "Buscar uma carona", exact: true })
      .waitFor();
    assert.equal(await page.evaluate(() => location.hash), "#painel");
    assert.equal(await page.locator("#password").inputValue(), "");
    await page.goto("http://127.0.0.1:4173/login.html");
    await page.getByText("Testar os estados do login", { exact: true }).click();
    await page.locator("#demo-scenario").selectOption("restricted");
    await page
      .getByRole("button", { name: "Preencher dados fictícios", exact: true })
      .click();
    await page.getByRole("button", { name: "Entrar", exact: true }).click();
    await page
      .getByRole("heading", {
        name: "Sua conta está com acesso restrito.",
        exact: true,
      })
      .waitFor();
    await page.goto("http://127.0.0.1:4173/login.html");
    await page.getByText("Testar os estados do login", { exact: true }).click();
    await page.locator("#demo-scenario").selectOption("offline");
    await page
      .getByRole("button", { name: "Preencher dados fictícios", exact: true })
      .click();
    await page.getByRole("button", { name: "Entrar", exact: true }).click();
    await page
      .getByText("Não foi possível conectar. Tente novamente em instantes.", {
        exact: true,
      })
      .waitFor();
    await page
      .getByRole("link", { name: "Esqueci minha senha", exact: true })
      .click();
    await page
      .getByRole("heading", { name: "Esqueceu sua senha?", exact: true })
      .waitFor();
    await page.locator("#email").fill("aluno@example.com");
    await page
      .getByRole("button", { name: "Enviar instruções", exact: true })
      .click();
    await page
      .getByRole("heading", { name: "Confira as instruções.", exact: true })
      .waitFor();
    await page
      .getByRole("button", { name: "Abrir link de demonstração", exact: true })
      .click();
    await page.locator("#password").fill("NovaSenhaFicticia!");
    await page.locator("#confirm").fill("diferente");
    await page
      .getByRole("button", { name: "Salvar nova senha", exact: true })
      .click();
    await page
      .getByText("As senhas não são iguais.", { exact: true })
      .waitFor();
    await page.locator("#confirm").fill("NovaSenhaFicticia!");
    await page
      .getByRole("button", { name: "Salvar nova senha", exact: true })
      .click();
    await page
      .getByRole("heading", { name: "Redefinição demonstrada.", exact: true })
      .waitFor();
    await page.goto("http://127.0.0.1:4173/login.html#nova-senha");
    await page
      .getByRole("heading", {
        name: "Este link não está disponível.",
        exact: true,
      })
      .waitFor();
    await page.goto("http://127.0.0.1:4173/login.html");
    await page.getByRole("link", { name: "Criar conta", exact: true }).click();
    await page
      .getByRole("heading", { name: "Vamos criar seu acesso.", exact: true })
      .waitFor();
    assert.deepEqual(
      await page.locator(".step-bar-fill").evaluate((fill) => ({
        width: fill.style.width,
        transitionProperty: getComputedStyle(fill).transitionProperty,
        markerContent: getComputedStyle(fill, "::after").content,
      })),
      {
        width: "33%",
        transitionProperty: "width",
        markerContent: "none",
      },
    );
    await page.setViewportSize({ width: 320, height: 700 });
    await page.emulateMedia({ reducedMotion: "no-preference" });
    await page.evaluate(() => {
      window.signupScrollRequests = [];
      window.signupScrollPositions = [];
      const originalScrollIntoView = Element.prototype.scrollIntoView;
      Element.prototype.scrollIntoView = function (options) {
        if (
          this === document.querySelector("#signup-course")?.closest(".field") ||
          this.id === "auth-title"
        ) {
          window.signupScrollRequests.push({
            target:
              this.id === "auth-title" ? "auth-title" : "signup-course-field",
            block: options?.block ?? "start",
            behavior: options?.behavior ?? "auto",
          });
        }
        return originalScrollIntoView.call(this, options);
      };
      window.addEventListener(
        "scroll",
        () => window.signupScrollPositions.push(window.scrollY),
        { passive: true },
      );
    });
    assert.deepEqual(
      await page.evaluate(() => ({
        checkboxWidth: getComputedStyle(document.querySelector("#signup-truth")).width,
        checkboxHeight: getComputedStyle(document.querySelector("#signup-truth")).height,
        labelFontSize: getComputedStyle(document.querySelector("#signup-truth").closest("label")).fontSize,
        labelWiderThanCheckbox:
          document.querySelector("#signup-truth").closest("label").getBoundingClientRect().width >
          document.querySelector("#signup-truth").getBoundingClientRect().width,
        noHorizontalOverflow: document.documentElement.scrollWidth === innerWidth,
      })),
      {
        checkboxWidth: "16px",
        checkboxHeight: "16px",
        labelFontSize: "13px",
        labelWiderThanCheckbox: true,
        noHorizontalOverflow: true,
      },
    );
    await page.getByRole("button", { name: "Continuar", exact: true }).click();
    await page.getByText("Informe seu nome completo.", { exact: true }).waitFor();
    assert.deepEqual(await page.evaluate(() => window.signupScrollRequests), []);

    await page.locator("#signup-name").fill("Ana Souza");
    await page.locator("#signup-email").fill("ana.souza@example.com");
    await page.locator("#signup-password").fill("SenhaDeDemonstracao123!");
    await page.locator("#signup-confirm").fill("SenhaDeDemonstracao123!");
    await page.locator("#signup-birth").fill("1995-05-01");
    await page.locator("#signup-truth").check();
    await page.locator("#signup-terms").check();
    await page.locator("#signup-step1 button[type=submit]").scrollIntoViewIfNeeded();
    const firstStepScrollStart = await page.evaluate(() => {
      window.signupScrollRequests = [];
      window.signupScrollPositions = [];
      window.signupScrollStartY = window.scrollY;
      return window.signupScrollStartY;
    });
    assert.ok(firstStepScrollStart > 0, "test needs a scrolled registration page");
    await page.getByRole("button", { name: "Continuar", exact: true }).click();
    await page.getByRole("heading", { name: "Seu perfil acadêmico.", exact: true }).waitFor();
    await page.waitForFunction(() => {
      const field = document.querySelector("#signup-course")?.closest(".field");
      return field && Math.abs(field.getBoundingClientRect().top - 32) <= 2;
    });
    const firstStepScroll = await page.evaluate(() => ({
      requests: window.signupScrollRequests,
      positions: window.signupScrollPositions,
      startY: window.signupScrollStartY,
      courseFieldTop: document
        .querySelector("#signup-course")
        .closest(".field")
        .getBoundingClientRect().top,
      courseLabelTop: document
        .querySelector('label[for="signup-course"]')
        .getBoundingClientRect().top,
      courseInputBottom: document
        .querySelector("#signup-course")
      .getBoundingClientRect().bottom,
    }));
    assert.deepEqual(firstStepScroll.requests, [
      { target: "signup-course-field", block: "start", behavior: "smooth" },
    ]);
    assert.ok(
      firstStepScroll.positions.some(
        (position) => position > 0 && position < firstStepScroll.startY,
      ),
    );
    assert.ok(Math.abs(firstStepScroll.courseFieldTop - 32) <= 2);
    assert.ok(firstStepScroll.courseLabelTop >= 0);
    assert.ok(firstStepScroll.courseInputBottom <= 700);
    assert.equal(
      await page.evaluate(() => document.activeElement.id),
      "signup-course",
    );
    assert.equal(
      await page.evaluate(() => document.documentElement.scrollWidth === innerWidth),
      true,
    );
    await page.screenshot({ path: "docs/previews/signup-course-target-320.png" });

    await page.emulateMedia({ reducedMotion: "reduce" });
    const selectionCatalog = await page.evaluate(() => {
      const data = window.UniCaronaSelectionData;
      const normalized = (value) =>
        value.normalize("NFD").replace(/[\u0300-\u036f]/g, "").toLocaleLowerCase("pt-BR");
      return {
        courseCount: data.courses.length,
        courseIdsUnique: new Set(data.courses.map((course) => course.id)).size,
        courseNamesUnique: new Set(data.courses.map((course) => normalized(course.label))).size,
        dfRegionCount: data.regions.filter((region) => region.kind === "df-ra").length,
        rideCityCount: data.regions.filter((region) => region.kind === "ride-city").length,
        regionIdsUnique: new Set(data.regions.map((region) => region.id)).size,
        regionLabelsUnique: new Set(data.regions.map((region) => normalized(region.label))).size,
      };
    });
    assert.deepEqual(selectionCatalog, {
      courseCount: 21,
      courseIdsUnique: 21,
      courseNamesUnique: 21,
      dfRegionCount: 35,
      rideCityCount: 33,
      regionIdsUnique: 68,
      regionLabelsUnique: 68,
    });
    assert.equal(await page.locator("#signup-period").isDisabled(), true);
    assert.equal(await page.locator("#signup-shift").isDisabled(), true);
    assert.equal(await page.locator("#signup-class").isDisabled(), true);
    assert.equal(await page.locator("#signup-period").inputValue(), "");
    const academicStatus = await page.locator("#signup-academic-status").evaluate((status) => ({
      text: status.textContent,
      beforeSelectors: Boolean(
        status.compareDocumentPosition(document.querySelector(".academic-selection")) &
          Node.DOCUMENT_POSITION_FOLLOWING,
      ),
    }));
    assert.match(academicStatus.text, /informe seu período e o horário das aulas/i);
    assert.equal(academicStatus.beforeSelectors, true);
    await page.getByText(/Essas informações são declaradas por você e não foram verificadas pela faculdade/).waitFor();

    const scrollRequestsBeforeEmptyCourse = await page.evaluate(
      () => window.signupScrollRequests.length,
    );
    await page.getByRole("button", { name: "Continuar", exact: true }).click();
    await page.getByText("Escolha um curso válido da lista.", { exact: true }).waitFor();
    assert.equal(
      await page.evaluate(() => window.signupScrollRequests.length),
      scrollRequestsBeforeEmptyCourse,
    );
    await page.locator("#signup-course").fill("Curso inventado");
    assert.equal(await page.locator("#signup-course-status").textContent(), "Nenhuma opção encontrada.");
    assert.equal(await page.locator("#signup-course-options [role=option]").count(), 0);
    assert.equal(await page.locator("#signup-course-options .combobox-empty").isVisible(), true);
    assert.equal(
      await page.locator("#signup-course-options .combobox-empty").textContent(),
      "Nenhuma opção encontrada.",
    );
    await page.screenshot({
      path: "docs/previews/signup-course-empty-320.png",
      fullPage: true,
      timeout: 15000,
    });
    const scrollRequestsBeforeInvalidCourse = await page.evaluate(
      () => window.signupScrollRequests.length,
    );
    await page.getByRole("button", { name: "Continuar", exact: true }).click();
    await page.getByText("Escolha um curso válido da lista.", { exact: true }).waitFor();
    assert.equal(await page.locator("#signup-course").getAttribute("aria-invalid"), "true");
    assert.equal(await page.locator("#signup-course").getAttribute("aria-expanded"), "false");
    assert.equal(
      (await page.evaluate(() => window.signupScrollRequests.length)),
      scrollRequestsBeforeInvalidCourse,
    );

    await page.locator("#signup-course").fill("ANALISE E DESENVOLVIMENTO");
    assert.equal(await page.locator("#signup-course").getAttribute("aria-expanded"), "true");
    await page.locator("#signup-course").press("ArrowDown");
    await page.locator("#signup-course").press("Enter");
    assert.equal(await page.locator("#signup-course").inputValue(), "Análise e Desenvolvimento de Sistemas");
    assert.equal(
      await page.locator("#signup-course-selected").textContent(),
      "Selecionado: Análise e Desenvolvimento de Sistemas",
    );
    assert.equal(
      await page.locator("#signup-course").getAttribute("data-selected-id"),
      "analise-desenvolvimento-sistemas",
    );

    await page.locator("#signup-course").fill("engenharia");
    assert.deepEqual(
      await page.locator("#signup-course-options [role=option]").allTextContents(),
      ["Engenharia Civil", "Engenharia de Software"],
    );
    assert.match(await page.locator("#signup-course-status").textContent(), /^2 opções encontradas\./);
    const scrollRequestsBeforeDirectEnter = await page.evaluate(
      () => window.signupScrollRequests.length,
    );
    await page.locator("#signup-course").press("Enter");
    assert.equal(await page.locator("#signup-course").getAttribute("data-selected-id"), "");
    assert.equal(
      await page.locator("#signup-course").getAttribute("aria-activedescendant"),
      await page.locator("#signup-course-options [role=option]").first().getAttribute("id"),
    );
    assert.equal(await page.locator("#signup-course").getAttribute("aria-invalid"), null);
    assert.equal(await page.evaluate(() => location.hash), "#cadastro-perfil");
    assert.equal(
      await page.evaluate(() => window.signupScrollRequests.length),
      scrollRequestsBeforeDirectEnter,
    );
    await page.locator("#signup-course").press("Enter");
    assert.equal(await page.locator("#signup-course").inputValue(), "Engenharia Civil");
    assert.equal(await page.locator("#signup-course").getAttribute("data-selected-id"), "engenharia-civil");
    await page.locator("#signup-course").fill("ANALISE E DESENVOLVIMENTO");
    await page.locator("#signup-course").press("ArrowDown");
    await page.locator("#signup-course").press("Enter");
    assert.equal(
      await page.locator("#signup-course").getAttribute("data-selected-id"),
      "analise-desenvolvimento-sistemas",
    );

    const scrollRequestsBeforeMissingRegion = await page.evaluate(
      () => window.signupScrollRequests.length,
    );
    await page.getByRole("button", { name: "Continuar", exact: true }).click();
    await page.getByText("Escolha uma região ou cidade válida da lista.", { exact: true }).waitFor();
    assert.equal(await page.locator("#signup-neighborhood").getAttribute("aria-invalid"), "true");
    assert.equal(
      await page.evaluate(() => window.signupScrollRequests.length),
      scrollRequestsBeforeMissingRegion,
    );
    assert.equal(await page.locator("#signup-neighborhood").getAttribute("aria-expanded"), "false");
    await page.locator("#signup-neighborhood").fill("Bairro inventado");
    assert.equal(await page.locator("#signup-neighborhood-status").textContent(), "Nenhuma opção encontrada.");
    assert.equal(await page.locator("#signup-neighborhood-options [role=option]").count(), 0);
    const scrollRequestsBeforeInvalidRegion = await page.evaluate(
      () => window.signupScrollRequests.length,
    );
    await page.getByRole("button", { name: "Continuar", exact: true }).click();
    await page.getByText("Escolha uma região ou cidade válida da lista.", { exact: true }).waitFor();
    assert.equal(await page.locator("#signup-neighborhood").getAttribute("aria-invalid"), "true");
    assert.equal(
      await page.evaluate(() => window.signupScrollRequests.length),
      scrollRequestsBeforeInvalidRegion,
    );
    await page.screenshot({
      path: "docs/previews/signup-profile-error-320.png",
      fullPage: true,
      timeout: 15000,
    });
    await page.locator("#signup-neighborhood").fill("AGUA QUENTE");
    assert.equal(
      await page.getByRole("option", { name: "Água Quente · RA XXXV · DF", exact: true }).count(),
      1,
    );
    await page.locator("#signup-neighborhood").fill("planaltina");
    assert.match(await page.locator("#signup-neighborhood-status").textContent(), /^2 opções encontradas\./);
    assert.deepEqual(
      await page.locator("#signup-neighborhood-options [role=option]").allTextContents(),
      ["Planaltina · RA VI · DF", "Planaltina · GO · RIDE-DF"],
    );
    await page.locator("#signup-neighborhood").fill("taguatinga");
    await page.locator("#signup-neighborhood").press("ArrowDown");
    await page.locator("#signup-neighborhood").press("Enter");
    assert.equal(await page.locator("#signup-neighborhood").inputValue(), "Taguatinga · RA III · DF");
    assert.equal(
      await page.locator("#signup-neighborhood").getAttribute("data-selected-id"),
      "df-ra-03",
    );

    await page.locator("#signup-period").fill("3");
    await page.locator("#signup-shift").selectOption("night");

    for (const width of [320, 390, 768, 1024, 1440]) {
      await page.setViewportSize({ width, height: 900 });
      await page.locator("#signup-course").click();
      assert.equal(
        await page.locator("#signup-course-selected").isVisible(),
        width <= 600,
        `the full course label should supplement the input only on narrow screens (${width}px)`,
      );
      const courseListBounds = await page.locator("#signup-course-options").evaluate((list) => {
        const { left, right } = list.getBoundingClientRect();
        return { left, right, viewportWidth: innerWidth };
      });
      assert.ok(courseListBounds.left >= 0 && courseListBounds.right <= width);
      const hintAvoidsPopup = await page.evaluate(() => {
        const hint = document.querySelector("#signup-course-hint").getBoundingClientRect();
        const popup = document.querySelector("#signup-course-options").getBoundingClientRect();
        return hint.bottom <= popup.top || hint.top >= popup.bottom;
      });
      assert.equal(hintAvoidsPopup, true, `course keyboard help must remain visible at ${width}px`);
      assert.equal(
        await page.evaluate(() => document.documentElement.scrollWidth === innerWidth),
        true,
      );
      if ([320, 768, 1440].includes(width))
        await page.screenshot({
          path: `docs/previews/signup-profile-${width}.png`,
          fullPage: true,
          timeout: 15000,
        });
      await page.locator("#signup-course").press("Escape");
    }
    await page.setViewportSize({ width: 320, height: 700 });
    assert.equal(
      await page.locator("#signup-course").getAttribute("data-selected-id"),
      "analise-desenvolvimento-sistemas",
      "closing the suggestion list must preserve the selected course",
    );
    assert.equal(
      await page.locator("#signup-neighborhood").getAttribute("data-selected-id"),
      "df-ra-03",
      "closing the suggestion list must preserve the selected region",
    );

    await page.locator("#signup-phone").fill("(61) 99999-9999");
    await page.locator("#signup-photo").setInputFiles({
      name: "perfil.png",
      mimeType: "image/png",
      buffer: Buffer.from("test image bytes"),
    });
    const scrollCountBeforeDraftBack = await page.evaluate(
      () => window.signupScrollRequests.length,
    );
    await page.getByRole("link", { name: "← Voltar", exact: true }).click();
    await page.getByRole("heading", { name: "Vamos criar seu acesso.", exact: true }).waitFor();
    assert.equal(
      (await page.evaluate(() => window.signupScrollRequests.length)),
      scrollCountBeforeDraftBack,
      "going back must not trigger the forward scroll",
    );
    await page.getByRole("button", { name: "Continuar", exact: true }).click();
    await page.getByRole("heading", { name: "Seu perfil acadêmico.", exact: true }).waitFor();
    assert.equal(await page.locator("#signup-course").getAttribute("data-selected-id"), "analise-desenvolvimento-sistemas");
    assert.equal(await page.locator("#signup-neighborhood").getAttribute("data-selected-id"), "df-ra-03");
    assert.equal(await page.locator("#signup-phone").inputValue(), "(61) 99999-9999");
    assert.equal(await page.locator("#signup-photo").inputValue(), "C:\\fakepath\\perfil.png");

    await page.locator("#signup-neighborhood").fill("Bairro inventado");
    const scrollCountBeforeInvalidDraftBack = await page.evaluate(
      () => window.signupScrollRequests.length,
    );
    await page.getByRole("link", { name: "← Voltar", exact: true }).click();
    await page.getByRole("heading", { name: "Vamos criar seu acesso.", exact: true }).waitFor();
    assert.equal(
      (await page.evaluate(() => window.signupScrollRequests.length)),
      scrollCountBeforeInvalidDraftBack,
      "going back with an invalid draft must not trigger the forward scroll",
    );
    await page.getByRole("button", { name: "Continuar", exact: true }).click();
    await page.getByRole("heading", { name: "Seu perfil acadêmico.", exact: true }).waitFor();
    assert.equal(await page.locator("#signup-neighborhood").inputValue(), "Bairro inventado");
    assert.equal(await page.locator("#signup-neighborhood").getAttribute("data-selected-id"), "");
    assert.equal(await page.locator("#signup-course").getAttribute("data-selected-id"), "analise-desenvolvimento-sistemas");
    assert.equal(await page.locator("#signup-phone").inputValue(), "(61) 99999-9999");
    await page.getByRole("button", { name: "Continuar", exact: true }).click();
    await page.getByText("Escolha uma região ou cidade válida da lista.", { exact: true }).waitFor();
    await page.locator("#signup-neighborhood").fill("taguatinga");
    await page.locator("#signup-neighborhood").press("ArrowDown");
    await page.locator("#signup-neighborhood").press("Enter");
    assert.equal(
      await page.locator("#signup-neighborhood-selected").textContent(),
      "Selecionado: Taguatinga · RA III · DF",
    );
    await page.locator("#signup-step2 button[type=submit]").scrollIntoViewIfNeeded();
    assert.ok(await page.evaluate(() => window.scrollY > 0));
    const beforeReducedAdvance = await page.evaluate(
      () => window.signupScrollRequests.length,
    );
    await page.evaluate(() => {
      window.signupScrollPositions = [];
    });
    await page.getByRole("button", { name: "Continuar", exact: true }).click();
    await page.getByRole("heading", { name: "Confirme seu e-mail.", exact: true }).waitFor();
    await page.waitForFunction(() => {
      const heading = document.querySelector("#auth-title");
      return heading && heading.getBoundingClientRect().top >= 0;
    });
    const reducedAdvance = await page.evaluate(() => ({
      requests: window.signupScrollRequests,
      headingTop: document.querySelector("#auth-title").getBoundingClientRect().top,
      reducedMotion: matchMedia("(prefers-reduced-motion: reduce)").matches,
      scrollBehavior: getComputedStyle(document.documentElement).scrollBehavior,
    }));
    assert.deepEqual(reducedAdvance.requests.slice(beforeReducedAdvance), [
      { target: "auth-title", block: "start", behavior: "auto" },
    ]);
    assert.equal(reducedAdvance.reducedMotion, true);
    assert.equal(reducedAdvance.scrollBehavior, "auto");
    assert.ok(reducedAdvance.headingTop >= 0 && reducedAdvance.headingTop < 700);
    assert.equal(await page.evaluate(() => document.activeElement.id), "auth-title");
    await page.screenshot({
      path: "docs/previews/signup-email-title-reduced-320.png",
    });
    await page.getByRole("link", { name: "← Voltar ao perfil" }).click();
    await page.getByRole("heading", { name: "Seu perfil acadêmico.", exact: true }).waitFor();
    assert.equal(
      await page.locator("#signup-course").getAttribute("data-selected-id"),
      "analise-desenvolvimento-sistemas",
    );
    assert.equal(
      await page.locator("#signup-neighborhood").getAttribute("data-selected-id"),
      "df-ra-03",
    );
    assert.equal(await page.locator("#signup-phone").inputValue(), "(61) 99999-9999");
    assert.equal(
      (await page.evaluate(() => window.signupScrollRequests)).length,
      beforeReducedAdvance + 1,
    );
    await page.locator("#signup-step2 button[type=submit]").scrollIntoViewIfNeeded();
    await page.getByRole("button", { name: "Continuar", exact: true }).click();
    await page.getByRole("heading", { name: "Confirme seu e-mail.", exact: true }).waitFor();
    const beforeEmailCorrection = await page.evaluate(
      () => window.signupScrollRequests.length,
    );
    await page.locator("#correct-signup-email").click();
    await page.getByRole("heading", { name: "Vamos criar seu acesso.", exact: true }).waitFor();
    assert.equal(
      (await page.evaluate(() => window.signupScrollRequests)).length,
      beforeEmailCorrection,
    );

    await page.getByRole("button", { name: "Continuar", exact: true }).click();
    await page.getByRole("heading", { name: "Seu perfil acadêmico.", exact: true }).waitFor();
    assert.equal(await page.locator("#signup-course").getAttribute("data-selected-id"), "analise-desenvolvimento-sistemas");
    assert.equal(await page.locator("#signup-photo").inputValue(), "C:\\fakepath\\perfil.png");
    await page.evaluate(() => {
      window.dispatchEvent(new PageTransitionEvent("pagehide", { persisted: true }));
    });
    const clearedProfileDom = await page.evaluate(() => ({
      courseValue: document.querySelector("#signup-course").value,
      courseId: document.querySelector("#signup-course").getAttribute("data-selected-id"),
      courseSummary: document.querySelector("#signup-course-selected").textContent,
      courseSummaryHidden: document.querySelector("#signup-course-selected").hidden,
      regionId: document.querySelector("#signup-neighborhood").getAttribute("data-selected-id"),
      phone: document.querySelector("#signup-phone").value,
      photoName: document.querySelector("#signup-photo-name").textContent,
      liveCourseStatus: document.querySelector("#signup-course-status").textContent,
    }));
    assert.deepEqual(clearedProfileDom, {
      courseValue: "",
      courseId: null,
      courseSummary: "",
      courseSummaryHidden: true,
      regionId: null,
      phone: "",
      photoName: "",
      liveCourseStatus: "",
    });
    await page.evaluate(() => {
      window.dispatchEvent(new PageTransitionEvent("pageshow", { persisted: true }));
    });
    await page.getByRole("heading", { name: "Vamos criar seu acesso.", exact: true }).waitFor();
    assert.equal(await page.evaluate(() => location.hash), "#cadastro");
    assert.equal(await page.locator("#signup-name").inputValue(), "");
    assert.equal(await page.locator("#signup-email").inputValue(), "");
    assert.equal(await page.locator("#signup-password").inputValue(), "");
    assert.equal(await page.locator("#signup-truth").isChecked(), false);

    await page.locator("#signup-name").fill("Ana Souza");
    await page.locator("#signup-email").fill("ana.souza@example.com");
    await page.locator("#signup-password").fill("SenhaDeDemonstracao123!");
    await page.locator("#signup-confirm").fill("SenhaDeDemonstracao123!");
    await page.locator("#signup-birth").fill("1995-05-01");
    await page.locator("#signup-truth").check();
    await page.locator("#signup-terms").check();
    await page.getByRole("button", { name: "Continuar", exact: true }).click();
    await page.getByRole("heading", { name: "Seu perfil acadêmico.", exact: true }).waitFor();
    await page.locator("#signup-course").fill("ANALISE E DESENVOLVIMENTO");
    await page.locator("#signup-course").press("ArrowDown");
    await page.locator("#signup-course").press("Enter");
    await page.locator("#signup-neighborhood").fill("Taguatinga");
    await page.locator("#signup-neighborhood").press("ArrowDown");
    await page.locator("#signup-neighborhood").press("Enter");
    await page.locator("#signup-phone").fill("(61) 99999-9999");
    await page.locator("#signup-period").fill("3");
    await page.locator("#signup-shift").selectOption("night");
    await page.getByRole("button", { name: "Continuar", exact: true }).click();
    await page.getByRole("heading", { name: "Confirme seu e-mail.", exact: true }).waitFor();
    assert.equal(await page.locator("#signup-email-address").textContent(), "ana.souza@example.com");
    await page.evaluate(() => {
      window.dispatchEvent(new PageTransitionEvent("pagehide", { persisted: true }));
    });
    assert.equal(await page.locator("#signup-email-address").textContent(), "");
    await page.evaluate(() => {
      window.dispatchEvent(new PageTransitionEvent("pageshow", { persisted: true }));
    });
    await page.getByRole("heading", { name: "Vamos criar seu acesso.", exact: true }).waitFor();
    assert.equal(await page.evaluate(() => location.hash), "#cadastro");
    assert.equal(await page.locator("#signup-email").inputValue(), "");

    await require("./academic-profile.cjs")(browser);

    await page.goto("http://127.0.0.1:4173/login.html");
    for (const width of [320, 390, 768, 1024, 1440]) {
      await page.setViewportSize({ width, height: 900 });
      assert.equal(
        await page.evaluate(
          () => document.documentElement.scrollWidth > innerWidth,
        ),
        false,
      );
      if ([390, 1440].includes(width))
        await page.screenshot({
          path: `docs/previews/login-${width}.png`,
          fullPage: true,
          timeout: 15000,
        });
    }
    assert.equal(
      await page.evaluate(() => localStorage.length + sessionStorage.length),
      0,
    );
    assert.deepEqual(sent, []);
    assert.deepEqual(errors, []);
    console.log(
      "PASS: signup target scrolling, reduced motion, keyboard and live combobox statuses, required academic fields, login, recovery, and 5 widths.",
    );
  } finally {
    await browser.close();
  }
})().catch((e) => {
  console.error(e);
  process.exit(1);
});
