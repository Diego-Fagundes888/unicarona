import {test} from 'node:test';
import assert from 'node:assert/strict';
import {existsSync} from 'node:fs';
import {mkdir} from 'node:fs/promises';
import {chromium,type Route} from 'playwright';
import {setup} from './helpers.js';

test('campus map handles delayed/unavailable tiles, retry, keyboard controls and optional mobile loading without blocking search',async t=>{
 const origin='http://127.0.0.1:3001',env=await setup({APP_ORIGIN:origin,PORT:3001});t.after(env.close);await env.app.listen({host:'127.0.0.1',port:3001});
 const executable=process.env.E2E_BROWSER_PATH||'C:/Program Files/Google/Chrome/Application/chrome.exe';
 const browser=await chromium.launch(existsSync(executable)?{executablePath:executable}:{});t.after(()=>browser.close());
 const page=await browser.newPage({locale:'pt-BR',viewport:{width:390,height:844},reducedMotion:'reduce'});page.setDefaultTimeout(10000);
 await page.clock.install();
 const errors:string[]=[];page.on('pageerror',e=>errors.push(e.message));
 let mode:'held'|'blocked'|'available'='held',requests=0;const held:Route[]=[];
 const pixel=Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jB8sAAAAASUVORK5CYII=','base64');
 // No external maps are downloaded by this automated test. The tiny local
 // image tests recovery events, and is never presented as geographic evidence.
 await page.route('https://*.openstreetmap.org/**',async route=>{
  requests++;
  if(mode==='held'){held.push(route);return;}
  if(mode==='blocked')await route.abort();
  else await route.fulfill({status:200,contentType:'image/png',body:pixel});
 });
 await page.goto(origin+'/login.html');await page.locator('#email').fill('ana@example.com');await page.locator('#password').fill(process.env.SEED_PASSWORD!);await page.getByRole('button',{name:'Entrar',exact:true}).click();
 await page.getByRole('heading',{name:'Buscar uma carona',exact:true}).waitFor();
 assert.equal(requests,0,'collapsed mobile map does not make hidden tile requests');
 await page.setViewportSize({width:1440,height:900});
 await page.locator('.d-map[data-map-state=loading]').waitFor();
 await page.waitForFunction(()=>document.querySelectorAll('.leaflet-tile').length>0);
 await page.clock.fastForward(13000);
 await page.getByRole('heading',{name:'O mapa está demorando',exact:true}).waitFor();
 mode='blocked';await Promise.all(held.splice(0).map(route=>route.abort()));
 await page.getByRole('heading',{name:'Mapa indisponível',exact:true}).waitFor();
 assert.ok((await page.getByRole('link',{name:'Abrir mapa',exact:true}).getAttribute('href'))?.startsWith('https://www.openstreetmap.org/'));
 assert.equal(await page.locator('.d-map iframe').count(),0,'native broken-document surface is replaced');
 assert.equal(await page.locator('[data-map-canvas]').isVisible(),false,'failed tiles and controls are hidden');
 await mkdir('docs/previews/java-connected',{recursive:true});
 for(const [label,width,height] of [['desktop',1440,900],['notebook',1280,720],['tablet',768,1024],['mobile',390,844]] as const){
  await page.setViewportSize({width,height});
  if(width<=700)await page.locator('.d-map-disclosure summary').click();
  await page.locator('.d-map[data-map-state=error]').waitFor();
  assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1));
  await page.locator('.d-map').screenshot({path:'docs/previews/java-connected/map-offline-'+label+'.png'});
 }
 mode='available';await page.getByRole('button',{name:'Tentar novamente',exact:true}).click();
 await page.locator('.d-map[data-map-state=ready]').waitFor();
 assert.equal(await page.locator('[data-map-status]').isVisible(),false);
 await page.getByRole('button',{name:'Aproximar mapa',exact:true}).click();
 await page.locator('.d-map[data-map-state=ready]').waitFor();
 await page.locator('[data-map-canvas]').focus();await page.keyboard.press('ArrowRight');
 await page.locator('.d-map[data-map-state=ready]').waitFor();
 await page.locator('#dashboard-origin').fill('Região sintética sem ofertas');await page.getByRole('button',{name:'Buscar caronas',exact:true}).click();
 await page.getByRole('heading',{name:'Escolha uma carona',exact:true}).waitFor();
 assert.deepEqual(errors,[]);
});
