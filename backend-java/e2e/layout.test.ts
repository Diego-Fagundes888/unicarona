import {test} from 'node:test';
import assert from 'node:assert/strict';
import {existsSync} from 'node:fs';
import {mkdir} from 'node:fs/promises';
import {chromium} from 'playwright';
import {setup} from './helpers.js';

test('connected public and dashboard layouts preserve breakpoints, long text, empty states, navigation and keyboard focus',async t=>{
 const origin='http://127.0.0.1:3001',env=await setup({APP_ORIGIN:origin,PORT:3001});t.after(env.close);await env.app.listen({host:'127.0.0.1',port:3001});
 const executable=process.env.E2E_BROWSER_PATH||'C:/Program Files/Google/Chrome/Application/chrome.exe',browser=await chromium.launch(existsSync(executable)?{executablePath:executable}:{});t.after(()=>browser.close());
 const page=await browser.newPage({locale:'pt-BR'});page.setDefaultTimeout(10000);const errors:string[]=[];page.on('pageerror',e=>errors.push(e.message));await page.route('https://*.openstreetmap.org/**',r=>r.abort());await mkdir('docs/previews/java-connected',{recursive:true});
 await page.goto(origin+'/');
 assert.equal(await page.locator('.hero-art img').evaluate((image:HTMLImageElement)=>image.currentSrc.endsWith('/assets/campus.webp')),true,'modern browsers receive the compressed illustration');
 for(const width of [320,390,768,900,1024,1440]){await page.setViewportSize({width,height:900});assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1),'public width '+width);}
 await page.setViewportSize({width:390,height:900});await page.getByRole('button',{name:'Menu',exact:true}).click();await page.locator('#public-menu:popover-open').waitFor();assert.equal(await page.locator('#public-menu a').count(),4);await page.locator('#public-menu').getByRole('link',{name:'Mensal',exact:true}).click();assert.equal(await page.locator('#public-menu').isVisible(),false);assert.ok(page.url().endsWith('#mensal'));assert.equal(await page.getByRole('link',{name:'Acessar Minhas caronas',exact:true}).getAttribute('href'),'dashboard.html?view=minhas');await page.setViewportSize({width:1440,height:900});
 await page.locator('#ride-search button[type=submit]').click();assert.equal(await page.locator('#origin').evaluate((e:HTMLInputElement)=>e.validationMessage),'Informe de onde você vai sair.');await page.locator('#origin').fill('UNICEPLAC');await page.locator('#ride-search button[type=submit]').click();assert.equal(await page.locator('#destination').evaluate((e:HTMLInputElement)=>e.validationMessage),'Informe locais diferentes para buscar o trajeto.');
 for(const key of ['avulsa','monthly','monthlyRules','oferecer','safety','help']){
  const trigger=page.locator('[data-modal="'+key+'"]:visible').first();await trigger.click();await page.locator('dialog[open]').waitFor();assert.ok((await page.locator('#modal-content').innerText()).length>30);await page.keyboard.press('Escape');assert.equal(await trigger.evaluate(e=>e===document.activeElement),true);
 }
 for(const document of ['terms','privacy']){const link=page.locator('a[href="legal.html?document='+document+'"]').first();assert.ok(await link.count());const response=await page.request.get(origin+'/'+await link.getAttribute('href'));assert.equal(response.status(),200);assert.match(await response.text(),/legal.js/);}
 await page.goto(origin+'/login.html');await page.locator('#email').fill('ana@example.com');await page.locator('#password').fill(process.env.SEED_PASSWORD!);await page.getByRole('button',{name:'Entrar',exact:true}).click();await page.waitForURL('**/dashboard.html');await page.getByRole('heading',{name:'Buscar uma carona',exact:true}).waitFor();
 for(const width of [320,390,768,900,1024,1440]){
  await page.setViewportSize({width,height:900});await page.waitForFunction(desktop=>document.querySelector<HTMLDetailsElement>('.d-schedule')?.open===desktop,width>700);
  assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1),'home width '+width);assert.equal(await page.locator('.d-header-nav').isVisible(),width>1000);assert.equal(await page.locator('.d-bottom-nav').isVisible(),width<=1000);
  const layout=await page.evaluate(()=>{const map=document.querySelector('.d-map')!.getBoundingClientRect(),search=document.querySelector('.d-search-panel')!.getBoundingClientRect();return {map:{x:map.x,y:map.y,width:map.width},search:{x:search.x,y:search.y,width:search.width}};});
  await page.screenshot({path:'docs/previews/java-connected/layout-home-'+width+'.png',fullPage:true});
  assert.equal(await page.locator('.d-home-grid > .d-map-disclosure .d-map').count(),1,'map remains available inside the home layout');
  assert.equal(await page.locator('.d-map-disclosure').evaluate((e:HTMLDetailsElement)=>e.open),width>700,'mobile map is optional and desktop map is visible');
  if(width>700)assert.ok(layout.map.x>layout.search.x+layout.search.width,JSON.stringify({width,...layout}));else {const disclosure=await page.locator('.d-map-disclosure').boundingBox();assert.ok(disclosure&&disclosure.y>layout.search.y,JSON.stringify({width,...layout}));}
 }
 await page.setViewportSize({width:1280,height:720});const primary=await page.locator('.d-search-submit').boundingBox();assert.ok(primary&&primary.y+primary.height<=720);
 await page.setViewportSize({width:390,height:844});await page.waitForFunction(()=>!document.querySelector<HTMLDetailsElement>('.d-schedule')!.open);await page.locator('#dashboard-origin').fill('Gama');await page.locator('.d-schedule summary').click();await page.locator('#dashboard-date').fill('2000-01-01');await page.locator('.d-schedule summary').click();await page.getByRole('button',{name:'Buscar caronas',exact:true}).click();assert.equal(await page.locator('.d-schedule').evaluate((e:HTMLDetailsElement)=>e.open),true);assert.equal(await page.locator('#dashboard-date').evaluate(e=>e===document.activeElement),true);
 await page.getByRole('button',{name:'Perfil',exact:true}).click();await page.locator('[name=name]').waitFor();const longName='NomeMuitoLongo'.repeat(4)+' <img src=x onerror="window.injected=true">';await page.locator('[name=name]').fill(longName);await page.getByRole('button',{name:'Salvar perfil',exact:true}).click();await page.locator('.m-toast').getByText('Perfil salvo.',{exact:true}).waitFor();assert.equal(await page.locator('[name=name]').inputValue(),longName);assert.equal(await page.evaluate(()=>(window as any).injected),undefined);
 await page.locator('.m-toast').waitFor({state:'hidden'});
 const routes=['results','minhas','driver','wallet','perfil','settings','safety','notifications'];
 for(const width of [320,1440]){
  await page.setViewportSize({width,height:900});
  for(const view of routes){await page.goto(origin+'/dashboard.html?view='+view);await page.locator('[data-c-content]:not([aria-busy=true])').waitFor();assert.equal(await page.locator('[data-c-content]').getByRole('button',{name:'Tentar novamente',exact:true}).count(),0,'view failed '+view);assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1),view+' width '+width);assert.equal(await page.evaluate(()=>(window as any).injected),undefined);await page.screenshot({path:'docs/previews/java-connected/layout-'+view+'-'+width+'.png',fullPage:true});}
 }
 assert.deepEqual(errors,[]);
});
