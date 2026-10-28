import {test} from 'node:test';
import assert from 'node:assert/strict';
import {existsSync} from 'node:fs';
import {mkdir} from 'node:fs/promises';
import {chromium,type Page} from 'playwright';
import {setup} from './helpers.js';
import {one} from './helpers.js';
import {paymentFailure} from './helpers.js';

test('browser failures: lost committed response, payment recovery, offline retry and visible loading/empty states',async t=>{
 const origin='http://127.0.0.1:3001',env=await setup({APP_ORIGIN:origin,PORT:3001,PAYMENT_PROVIDER_TIMEOUT_MS:100});t.after(env.close);
 await env.app.listen({host:'127.0.0.1',port:3001});
 const executable=process.env.E2E_BROWSER_PATH||'C:/Program Files/Google/Chrome/Application/chrome.exe';
 const browser=await chromium.launch(existsSync(executable)?{executablePath:executable}:{});t.after(()=>browser.close());
 const driver=await (await browser.newContext({locale:'pt-BR'})).newPage(),context=await browser.newContext({locale:'pt-BR',viewport:{width:390,height:844}}),passenger=await context.newPage();
 const errors:string[]=[];for(const page of [driver,passenger]){page.setDefaultTimeout(10000);page.on('pageerror',error=>errors.push(error.message));await page.route('https://*.openstreetmap.org/**',route=>route.abort());}

 await mkdir('docs/previews/java-connected',{recursive:true});
 async function login(page:Page,email:string){await page.goto(origin+'/login.html');await page.locator('#email').fill(email);await page.locator('#password').fill(process.env.SEED_PASSWORD!);await page.getByRole('button',{name:'Entrar',exact:true}).click();await page.waitForURL('**/dashboard.html');await page.getByRole('button',{name:'Oferecer',exact:true}).waitFor();}
 await login(driver,'carolina@example.com');
 // The public form carries its real search intent through authentication.
 await passenger.goto(origin+'/');await passenger.locator('#origin').fill('Gama');await passenger.getByRole('button',{name:'Buscar caronas',exact:true}).click();await passenger.waitForURL('**/login.html?next=*#login');
 await passenger.locator('#email').fill('ana@example.com');await passenger.locator('#password').fill(process.env.SEED_PASSWORD!);await passenger.getByRole('button',{name:'Entrar',exact:true}).click();await passenger.waitForURL('**/dashboard.html?*');await passenger.locator('#dashboard-origin').waitFor();assert.equal(await passenger.locator('#dashboard-origin').inputValue(),'Gama');assert.equal(new URL(passenger.url()).searchParams.get('view'),'buscar');
 const tomorrow=(await one(env.pool,"SELECT ((now() AT TIME ZONE 'America/Sao_Paulo')::date+1)::text AS date"))!.date;
 await driver.getByRole('button',{name:'Oferecer',exact:true}).click();await driver.getByRole('button',{name:'Publicar carona',exact:true}).click();
 await driver.locator('[data-c-form=publishOffer]').waitFor();
 assert.equal(await driver.locator('[data-c-form=publishOffer] [name=direction] option[value=ambos]').getAttribute('disabled'),'');
 await driver.locator('[name=type]').selectOption('mensal');await driver.locator('[name=direction]').selectOption('ambos');await driver.locator('[name=type]').selectOption('avulsa');
 assert.equal(await driver.locator('[name=direction]').inputValue(),'ida');
 const publish=driver.locator('[data-c-form=publishOffer]');await publish.locator('[name=region]').fill('Gama');await publish.locator('[name=meeting]').fill('Encontro sintético para falhas');await publish.locator('[name=date]').fill(tomorrow);await publish.locator('[name=time]').fill('20:00');await publish.locator('[name=distance]').fill('10');await publish.locator('[name=automatic]').check();
 await driver.getByRole('button',{name:'Publicar oferta',exact:true}).click();await driver.getByRole('heading',{name:'Gerenciar trecho',exact:true}).waitFor();const rideId=new URL(driver.url()).searchParams.get('ref')!;
 // Delay a real search response to inspect the actual loading UI.
 let release!:()=>void,started!:()=>void;const gate=new Promise<void>(resolve=>release=resolve),searchStarted=new Promise<void>(resolve=>started=resolve);
 await passenger.route('**/api/v1/rides?*',async route=>{started();await gate;await route.continue();},{times:1});
 await passenger.locator('#dashboard-origin').fill('Gama');await passenger.locator('.d-schedule summary').click();await passenger.locator('#dashboard-date').fill(tomorrow);await passenger.getByRole('button',{name:'Buscar caronas',exact:true}).click();await searchStarted;
 try{await passenger.getByRole('status').filter({hasText:'Carregando conteúdo…'}).waitFor();assert.equal(await passenger.locator('[data-c-content]').getAttribute('aria-busy'),'true');await passenger.screenshot({path:'docs/previews/java-connected/search-loading-mobile.png'});}finally{release();}
 await passenger.getByRole('button',{name:'Ver carona',exact:true}).click();
 // The server commits, but the client loses the response. Retry must reuse its key.
 const keys:string[]=[];passenger.on('request',request=>{if(request.url().endsWith('/api/v1/ride-requests')&&request.method()==='POST')keys.push(request.headers()['idempotency-key']);});
 await passenger.route('**/api/v1/ride-requests',async route=>{const committed=await route.fetch();assert.equal(committed.status(),200,await committed.text());await route.abort('connectionreset');},{times:1});
 await passenger.getByRole('button',{name:'Solicitar vaga',exact:true}).click();await passenger.getByRole('dialog').getByText('Não foi possível conectar. Confira sua conexão e tente novamente.',{exact:true}).waitFor();
 assert.equal((await one(env.pool,'SELECT count(*)::int AS count FROM ride_requests WHERE ride_id=$1',[rideId]))!.count,1);assert.equal((await one(env.pool,'SELECT count(*)::int AS count FROM ride_reservations WHERE ride_id=$1',[rideId]))!.count,0);
 await passenger.screenshot({path:'docs/previews/java-connected/request-network-error-mobile.png'});await passenger.getByRole('button',{name:'Entendi',exact:true}).click();await passenger.getByRole('button',{name:'Solicitar vaga',exact:true}).click();await passenger.getByRole('heading',{name:'Sua solicitação',exact:true}).waitFor();
 assert.equal(keys.length,2);assert.equal(keys[0],keys[1]);assert.equal((await one(env.pool,'SELECT count(*)::int AS count FROM ride_requests WHERE ride_id=$1',[rideId]))!.count,1);
 await passenger.getByRole('button',{name:'Ir para pagamento',exact:true}).click();const paymentId=new URL(passenger.url()).searchParams.get('ref')!;
 await paymentFailure(true);
 await passenger.getByRole('button',{name:'Continuar para pagamento',exact:true}).click();await passenger.locator('.m-error').getByText('Não foi possível conectar ao pagamento. Tente novamente; a mesma cobrança será reutilizada.',{exact:true}).waitFor();
 assert.equal((await one(env.pool,'SELECT status FROM payments WHERE id=$1',[paymentId]))!.status,'pending');
 await passenger.reload();await passenger.getByRole('button',{name:'Tentar abrir pagamento novamente',exact:true}).waitFor();await passenger.screenshot({path:'docs/previews/java-connected/payment-retry-mobile.png'});
 await paymentFailure(false);
 await passenger.getByRole('button',{name:'Tentar abrir pagamento novamente',exact:true}).click();await passenger.getByRole('link',{name:'Abrir pagamento',exact:true}).waitFor();
 assert.equal((await one(env.pool,'SELECT count(*)::int AS count FROM payments WHERE id=$1',[paymentId]))!.count,1);
 const popup=passenger.waitForEvent('popup');await passenger.getByRole('link',{name:'Abrir pagamento',exact:true}).click();const gateway=await popup;await gateway.getByRole('button',{name:'Testar pagamento aprovado',exact:true}).click();await gateway.getByText('Resultado de teste enviado. Confira o estado da reserva no UniCarona.',{exact:true}).waitFor();await gateway.close();
 await passenger.getByRole('button',{name:'Atualizar status',exact:true}).click();await passenger.getByRole('heading',{name:'Reserva confirmada',exact:true}).waitFor();assert.equal((await one(env.pool,'SELECT count(*)::int AS count FROM ride_reservations WHERE ride_id=$1',[rideId]))!.count,1);
 // The existing page must recover after the browser goes offline.
 await passenger.getByRole('button',{name:'Mais',exact:true}).click();await context.setOffline(true);await passenger.getByRole('button',{name:'Carteira',exact:true}).click();await passenger.getByText('Não foi possível conectar. Confira sua conexão e tente novamente.',{exact:true}).waitFor();await passenger.screenshot({path:'docs/previews/java-connected/wallet-offline-mobile.png'});
 assert.ok(await passenger.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1));await context.setOffline(false);await passenger.getByRole('button',{name:'Tentar novamente',exact:true}).click();await passenger.getByRole('heading',{name:'Transferências',exact:true}).waitFor();
 // A paid driver cancellation credits the actual parcel; no fake wallet balance.
 await driver.locator('summary').filter({hasText:'Cancelar este trecho'}).click();await driver.locator('[data-c-form=cancelRide] [name=reason]').fill('Cancelamento sintético depois de recuperar a conexão.');await driver.getByRole('button',{name:'Cancelar trecho e devolver as parcelas',exact:true}).click();await driver.getByRole('button',{name:'Confirmar',exact:true}).click();await driver.getByText('Cancelado',{exact:true}).first().waitFor();
 const parcel=(await one(env.pool,'SELECT status,cents FROM parcels WHERE reservation_id=(SELECT id FROM ride_reservations WHERE ride_id=$1)',[rideId]))!;assert.equal(parcel.status,'credited');await passenger.reload();await passenger.getByRole('button',{name:'Solicitar devolução',exact:true}).waitFor();
 await passenger.getByRole('button',{name:'Início',exact:true}).click();await passenger.locator('#dashboard-origin').fill('Região sintética sem ofertas');await passenger.locator('.d-schedule summary').click();await passenger.locator('#dashboard-date').fill(tomorrow);await passenger.getByRole('button',{name:'Buscar caronas',exact:true}).click();await passenger.getByText('Tente outra data ou retire um filtro para ampliar a busca.',{exact:true}).waitFor();await passenger.screenshot({path:'docs/previews/java-connected/search-empty-mobile.png',fullPage:true});assert.ok(await passenger.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1));
 await passenger.reload();await passenger.getByText('Tente outra data ou retire um filtro para ampliar a busca.',{exact:true}).waitFor();assert.equal(new URL(passenger.url()).searchParams.get('region'),'Região sintética sem ofertas');assert.equal(new URL(passenger.url()).searchParams.get('date'),tomorrow);
 await passenger.goto(origin+'/login.html?next='+encodeURIComponent('https://example.invalid/dashboard.html'));await passenger.locator('#email').fill('ana@example.com');await passenger.locator('#password').fill(process.env.SEED_PASSWORD!);await passenger.getByRole('button',{name:'Entrar',exact:true}).click();await passenger.waitForURL(origin+'/dashboard.html?view=inicio');
 assert.deepEqual(errors,[]);
});
