import {test} from 'node:test';
import assert from 'node:assert/strict';
import {existsSync} from 'node:fs';
import {chromium} from 'playwright';
import {setup} from './helpers.js';
import {one} from './helpers.js';
import {authLink} from './helpers.js';

test('delayed real authentication responses do not override a later navigation',async t=>{
 const origin='http://127.0.0.1:3001',env=await setup({APP_ORIGIN:origin,PORT:3001});t.after(env.close);await env.app.listen({host:'127.0.0.1',port:3001});
 const executable=process.env.E2E_BROWSER_PATH||'C:/Program Files/Google/Chrome/Application/chrome.exe',browser=await chromium.launch(existsSync(executable)?{executablePath:executable}:{});t.after(()=>browser.close());
 const page=await browser.newPage({locale:'pt-BR'});page.setDefaultTimeout(10000);const errors:string[]=[];page.on('pageerror',e=>errors.push(e.message));
 async function delayed(path:string,action:()=>Promise<void>,destination:string,heading:string){
  let release!:()=>void,started!:()=>void;const gate=new Promise<void>(r=>{release=r;}),committed=new Promise<void>(r=>{started=r;});
  const url=origin+'/api/v1/'+path;
  await page.route(url,async route=>{const response=await route.fetch();assert.ok([200,202].includes(response.status()),await response.text());started();await gate;await route.fulfill({response});});
  try{
   const arrived=page.waitForResponse(r=>r.url()===url);await action();await committed;
   await page.evaluate(hash=>{location.hash=hash;},destination);await page.getByRole('heading',{name:heading,exact:true}).waitFor();
   release();await (await arrived).finished();await page.evaluate(()=>new Promise<void>(resolve=>requestAnimationFrame(()=>requestAnimationFrame(()=>resolve()))));
   assert.equal(new URL(page.url()).hash,'#'+destination);await page.getByRole('heading',{name:heading,exact:true}).waitFor();
  }finally{release();await page.unroute(url);}
 }
 await page.goto(origin+'/login.html');await page.locator('#email').fill('ana@example.com');await page.locator('#password').fill(process.env.SEED_PASSWORD!);
 await delayed('auth/login',()=>page.getByRole('button',{name:'Entrar',exact:true}).click(),'recuperar','Esqueceu sua senha?');
 await page.locator('#email').fill('ana@example.com');
 await delayed('auth/forgot-password',()=>page.getByRole('button',{name:'Enviar instruções',exact:true}).click(),'login','Entre na sua conta.');
 async function access(){await page.goto(origin+'/login.html#cadastro');await page.locator('#signup-name').fill('Estudante Navegação');await page.locator('#signup-email').fill('navigation@example.com');await page.locator('#signup-password').fill('Senha-navegacao-2026');await page.locator('#signup-confirm').fill('Senha-navegacao-2026');await page.locator('#signup-birth').fill('2000-01-01');await page.locator('#signup-truth').check();await page.locator('#signup-terms').check();await page.locator('#signup-privacy').check();}
 await access();await delayed('catalogs',()=>page.getByRole('button',{name:'Continuar',exact:true}).click(),'login','Entre na sua conta.');
 await access();await page.getByRole('button',{name:'Continuar',exact:true}).click();await page.getByRole('heading',{name:'Seu perfil acadêmico.',exact:true}).waitFor();
 await page.locator('#signup-course').fill('Análise');await page.getByRole('option',{name:'Análise e Desenvolvimento de Sistemas',exact:true}).click();await page.locator('#signup-period').fill('3');await page.locator('#signup-shift').selectOption('night');await page.locator('#signup-class').selectOption('A');await page.locator('#signup-neighborhood').fill('Gama');await page.getByRole('option',{name:'Gama',exact:true}).click();await page.locator('#signup-phone').fill('61999990000');
 await delayed('auth/register',()=>page.getByRole('button',{name:'Continuar',exact:true}).click(),'login','Entre na sua conta.');
 const account=(await one(env.pool,"SELECT id,email_verified_at FROM users WHERE email='navigation@example.com'"))!;assert.ok(account);assert.equal(account.email_verified_at,null);
 const link=await authLink(env.pool,'navigation@example.com','verify_email');
 await delayed('auth/verify-email',()=>page.goto(link).then(()=>{}),'login','Entre na sua conta.');
 assert.ok((await one(env.pool,'SELECT email_verified_at FROM users WHERE id=$1',[account.id]))!.email_verified_at);
 assert.equal(await page.locator('#password').inputValue(),'');assert.deepEqual(errors,[]);
});
