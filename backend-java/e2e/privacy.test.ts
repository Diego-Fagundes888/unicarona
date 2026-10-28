import { test } from 'node:test';
import assert from 'node:assert/strict';
import { existsSync } from 'node:fs';
import { chromium, type Page } from 'playwright';
import { setup } from './helpers.js';

test('privacy navigation, communication preferences and administrative access use the real Java API', async t => {
  const origin = 'http://127.0.0.1:3001';
  const environment = await setup({ APP_ORIGIN: origin, PORT: 3001 });
  t.after(environment.close);
  const executablePath = process.env.E2E_BROWSER_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe';
  const browser = await chromium.launch(existsSync(executablePath) ? { executablePath } : {});
  t.after(() => browser.close());
  const errors: string[] = [];
  async function login(email: string): Promise<Page> {
    const context = await browser.newContext({ locale: 'pt-BR', viewport: { width: 390, height: 844 } });
    const page = await context.newPage();
    page.setDefaultTimeout(12000);
    page.on('pageerror', error => errors.push(error.message));
    await page.route('https://*.openstreetmap.org/**', route => route.abort());
    await page.goto(origin + '/login.html');
    await page.locator('#email').fill(email);
    await page.locator('#password').fill(process.env.SEED_PASSWORD!);
    await page.getByRole('button', { name: 'Entrar', exact: true }).click();
    await page.waitForURL('**/dashboard.html');
    return page;
  }

  const passenger = await login('ana@example.com');
  await passenger.goto(origin + '/dashboard.html?view=privacy');
  await passenger.getByRole('heading', { name: 'Privacidade e dados', exact: true }).waitFor();
  const preference = passenger.locator('input[name=marketing]');
  assert.equal(await preference.isChecked(), false);
  const saved = passenger.waitForResponse(response => response.url().endsWith('/api/v1/privacy/preferences') && response.request().method() === 'PATCH');
  await preference.check();
  await passenger.getByRole('button', { name: 'Salvar preferência', exact: true }).click();
  assert.equal((await saved).status(), 200);
  await passenger.reload();
  assert.equal(await passenger.locator('input[name=marketing]').isChecked(), true);
  await passenger.getByRole('button', { name: 'Documentos e meus aceites', exact: true }).click();
  await passenger.getByRole('heading', { name: 'Documentos e meus aceites', exact: true }).waitFor();
  await passenger.getByRole('link', { name: /Termos de Uso/ }).first().waitFor();
  await passenger.goto(origin + '/dashboard.html?view=admin_privacy');
  await passenger.getByText('Acesso administrativo necessário.', { exact: true }).waitFor();

  const administrator = await login('admin@example.com');
  await administrator.goto(origin + '/dashboard.html?view=admin_privacy');
  await administrator.getByRole('heading', { name: 'Privacidade administrativa', exact: true }).waitFor();
  await administrator.getByRole('button', { name: 'Solicitações de privacidade', exact: true }).click();
  await administrator.getByRole('heading', { name: 'Solicitações de privacidade', exact: true }).waitFor();
  assert.deepEqual(errors, []);
});
