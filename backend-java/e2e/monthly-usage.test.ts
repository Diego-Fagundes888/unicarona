import {test} from 'node:test';
import assert from 'node:assert/strict';
import {existsSync} from 'node:fs';
import {mkdir} from 'node:fs/promises';
import {randomUUID} from 'node:crypto';
import {chromium,type Page} from 'playwright';
import {setup} from './helpers.js';
import {one} from './helpers.js';
import {processOne} from './helpers.js';
import {reconciliation} from './helpers.js';
import {transferSignature,type TransferEvent} from './helpers.js';

test('monthly browser journey: paid availability, absence, no class, credits, transfers and separately accepted paid renewal',async t=>{
 const origin='http://127.0.0.1:3001',env=await setup({APP_ORIGIN:origin,PORT:3001});t.after(env.close);
 await env.app.listen({host:'127.0.0.1',port:3001});
 const executable=process.env.E2E_BROWSER_PATH||'C:/Program Files/Google/Chrome/Application/chrome.exe';
 const browser=await chromium.launch(existsSync(executable)?{executablePath:executable}:{});t.after(()=>browser.close());
 const driver=await (await browser.newContext({locale:'pt-BR'})).newPage(),passenger=await (await browser.newContext({locale:'pt-BR'})).newPage();
 const errors:string[]=[];for(const page of [driver,passenger]){page.setDefaultTimeout(10000);page.on('pageerror',error=>errors.push(error.message));await page.route('https://*.openstreetmap.org/**',route=>route.abort());}
 async function login(page:Page,email:string){await page.goto(origin+'/login.html');await page.locator('#email').fill(email);await page.locator('#password').fill(process.env.SEED_PASSWORD!);await page.getByRole('button',{name:'Entrar',exact:true}).click();await page.waitForURL('**/dashboard.html');await page.getByRole('button',{name:'Oferecer',exact:true}).waitFor();}
 async function screen(page:Page,view:string,ref=''){await page.goto(origin+'/dashboard.html?view='+view+(ref?'&ref='+ref:''));await page.locator('[data-c-content]:not([aria-busy=true])').waitFor();}
 async function pay(){
  await passenger.getByRole('button',{name:'Ir para pagamento',exact:true}).click();
  await passenger.getByRole('button',{name:'Continuar para pagamento',exact:true}).click();
  const popup=passenger.waitForEvent('popup');await passenger.getByRole('link',{name:'Abrir pagamento',exact:true}).click();const gateway=await popup;
  gateway.on('pageerror',error=>errors.push(error.message));await gateway.getByRole('button',{name:'Testar pagamento aprovado',exact:true}).click();
  await gateway.getByText('Resultado de teste enviado. Confira o estado da reserva no UniCarona.',{exact:true}).waitFor();await gateway.close();
  await passenger.getByRole('button',{name:'Atualizar status',exact:true}).click();await passenger.getByRole('heading',{name:'Reserva confirmada',exact:true}).waitFor();
 }
 async function requestMonthly(){await passenger.getByRole('button',{name:'Conferir agenda e valor',exact:true}).click();await passenger.getByRole('button',{name:'Solicitar mensal ao motorista',exact:true}).click();await passenger.getByRole('heading',{name:'Sua solicitação',exact:true}).waitFor();return new URL(passenger.url()).searchParams.get('ref')!;}
 async function accept(id:string){await screen(driver,'request',id);await driver.getByRole('button',{name:'Aceitar pedido',exact:true}).click();await driver.getByText('Aceito · falta pagamento',{exact:true}).waitFor();await passenger.reload();await passenger.getByRole('button',{name:'Ir para pagamento',exact:true}).waitFor();}
 await login(driver,'carolina@example.com');await login(passenger,'ana@example.com');
 await driver.getByRole('button',{name:'Oferecer',exact:true}).click();await driver.getByRole('button',{name:'Publicar carona',exact:true}).click();
 await driver.locator('[name=type]').selectOption('mensal');await driver.locator('[name=region]').fill('Mensal Gama');await driver.locator('[name=meeting]').fill('Praça sintética da rotina');
 await driver.locator('[name=direction]').selectOption('ambos');await driver.locator('[data-c-form=publishOffer] [name=time]').fill('23:00');await driver.locator('[name=returnTime]').fill('23:30');await driver.locator('[name=distance]').fill('10');
 for(const day of [0,6])await driver.locator('[name=weekdays][value="'+day+'"]').check();
 const publication=driver.waitForResponse(r=>r.url().endsWith('/api/v1/routines')&&r.request().method()==='POST');await driver.getByRole('button',{name:'Publicar oferta',exact:true}).click();const routine=await publication;assert.equal(routine.status(),200,await routine.text());const routineId=(await routine.json()).id;
 await screen(passenger,'monthly',routineId);const requestId=await requestMonthly();await accept(requestId);await pay();
 const paymentId=new URL(passenger.url()).searchParams.get('ref')!;
 const contract=(await one(env.pool,'SELECT s.id,s.total_cents,s.start_date::text AS start,s.end_date::text AS end FROM subscriptions s JOIN ride_reservations rr ON rr.subscription_id=s.id WHERE rr.payment_id=$1 LIMIT 1',[paymentId]))!;assert.ok(contract);
 const legs=(await env.pool.query(`SELECT rr.id,rr.ride_id,r.ride_date::text AS date,r.direction,p.id AS parcel_id,p.cents FROM ride_reservations rr JOIN rides r ON r.id=rr.ride_id JOIN parcels p ON p.reservation_id=rr.id WHERE rr.subscription_id=$1 ORDER BY r.ride_date,r.departure_time`,[contract.id])).rows;
 assert.ok(legs.length>4);assert.equal(legs.reduce((sum,leg)=>sum+leg.cents,0),contract.total_cents);
 const absence=legs[0],noClass=legs[1],cancelled=legs[2];assert.equal(absence.date,contract.start);assert.equal(noClass.date,absence.date);
 await screen(passenger,'reservation',absence.id);await passenger.locator('details').filter({has:passenger.locator('[data-c-form=absence]')}).locator('summary').click();await passenger.locator('[data-c-form=absence] [name=reason]').fill('Aviso de ausência sintética; a vaga deve permanecer reservada.');
 const notified=passenger.waitForResponse(r=>r.url().endsWith('/absence')&&r.request().method()==='POST');await passenger.getByRole('button',{name:'Avisar ausência',exact:true}).click();assert.equal((await notified).status(),200);
 assert.equal((await one(env.pool,'SELECT status,absence FROM ride_reservations WHERE id=$1',[absence.id]))!.status,'confirmed');
 await screen(driver,'passengers',absence.ride_id);assert.equal(await driver.locator('[data-c-form=board]').count(),1,'absence notice must preserve seat');
 await driver.getByRole('button',{name:'Iniciar trecho',exact:true}).click();await driver.getByText('Ativo',{exact:true}).waitFor();
 await driver.locator('[data-c-form=finishRide] [name=availability]').check();await driver.getByRole('button',{name:'Declarar conclusão',exact:true}).click();await driver.getByText('Concluído',{exact:true}).waitFor();
 assert.equal((await one(env.pool,'SELECT status FROM parcels WHERE id=$1',[absence.parcel_id]))!.status,'awaiting');
 await passenger.reload();await passenger.getByRole('button',{name:'Confirmar registro final',exact:true}).click();await passenger.getByText('Concluído',{exact:true}).waitFor();
 const released=(await one(env.pool,'SELECT status,cents,driver_cents,commission_cents FROM parcels WHERE id=$1',[absence.parcel_id]))!;assert.equal(released.status,'released');assert.equal(released.cents,released.driver_cents+released.commission_cents);
 assert.equal((await one(env.pool,'SELECT absence FROM ride_reservations WHERE id=$1',[absence.id]))!.absence,'notified_absence');
 await screen(driver,'passengers',noClass.ride_id);await driver.locator('details').filter({has:driver.locator('[data-c-form=noClass]')}).locator('summary').click();
 await driver.locator('[data-c-form=noClass] [name=reason]').fill('Aula suspensa após a contratação; confirmação separada.');await driver.locator('[data-c-form=noClass] button[type=submit]').click();await driver.getByRole('button',{name:'Confirmar',exact:true}).click();await driver.getByText('Sem aula',{exact:true}).waitFor();
 await screen(passenger,'reservation',noClass.id);await passenger.getByText('Sem aula informado após a contratação. Na confirmação ou após o prazo sem contestação, o valor integral volta ao seu crédito. Você pode contestar.',{exact:true}).waitFor();assert.equal(await passenger.locator('.m-pin').count(),0,'no boarding code after no-class declaration');
 await mkdir('docs/previews/java-connected',{recursive:true});await passenger.setViewportSize({width:1280,height:900});await passenger.screenshot({path:'docs/previews/java-connected/monthly-no-class-desktop.png',fullPage:true});
 await passenger.setViewportSize({width:390,height:844});await passenger.screenshot({path:'docs/previews/java-connected/monthly-no-class-mobile.png',fullPage:true});assert.ok(await passenger.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1));
 await passenger.getByRole('button',{name:'Confirmar registro final',exact:true}).click();await passenger.getByText('Concluído',{exact:true}).waitFor();
 const noClassCredit=(await one(env.pool,'SELECT status,cents,driver_cents,commission_cents FROM parcels WHERE id=$1',[noClass.parcel_id]))!;assert.equal(noClassCredit.status,'credited');assert.equal(noClassCredit.driver_cents,0);assert.equal(noClassCredit.commission_cents,0);assert.equal((await reconciliation(env.pool)).retained,0);
 await screen(driver,'passengers',cancelled.ride_id);await driver.getByText('Cancelar este trecho',{exact:true}).click();await driver.locator('[data-c-form=cancelRide] [name=reason]').fill('Cancelamento do motorista com crédito integral.');await driver.getByRole('button',{name:'Cancelar trecho e devolver as parcelas',exact:true}).click();await driver.getByRole('button',{name:'Confirmar',exact:true}).click();await driver.getByText('Cancelado',{exact:true}).first().waitFor();
 assert.equal((await one(env.pool,'SELECT status FROM parcels WHERE id=$1',[cancelled.parcel_id]))!.status,'credited');
 async function transfer(page:Page,type:'refund'|'withdraw',cents:number){
  await screen(page,'wallet');const f=page.locator('[data-c-form=transfer][data-input*="'+type+'"]');await f.locator('[name=cents]').fill((cents/100).toFixed(2));
  const sent=page.waitForResponse(r=>r.url().endsWith('/api/v1/transfers')&&r.request().method()==='POST');await f.locator('button[type=submit]').click();const response=await sent;assert.equal(response.status(),200,await response.text());const id=(await response.json()).id;
  await page.getByText('Aguardando',{exact:true}).waitFor();assert.equal(await processOne(env.pool,env.config),true);
  const pending=(await one(env.pool,'SELECT status,provider_reference FROM transfers WHERE id=$1',[id]))!;assert.equal(pending.status,'pending');assert.ok(pending.provider_reference);
  const event:TransferEvent={eventId:randomUUID(),transferId:id,reference:pending.provider_reference,amountCents:cents,status:'approved'},timestamp=String(Math.floor(Date.now()/1000));
  const received=await page.context().request.post(origin+'/api/v1/webhooks/transfers',{headers:{'x-webhook-timestamp':timestamp,'x-webhook-signature':await transferSignature(env.config.PAYMENT_WEBHOOK_SECRET,timestamp,event)},data:event});assert.equal(received.status(),200);
  const repeated=await page.context().request.post(origin+'/api/v1/webhooks/transfers',{headers:{'x-webhook-timestamp':timestamp,'x-webhook-signature':await transferSignature(env.config.PAYMENT_WEBHOOK_SECRET,timestamp,event)},data:event});assert.equal((await repeated.json()).duplicate,true);
  await page.reload();await page.getByText('Aprovado',{exact:true}).waitFor();return id;
 }
 await transfer(passenger,'refund',cancelled.cents);await transfer(driver,'withdraw',released.driver_cents);
 await screen(passenger,'contract',contract.id);await passenger.getByRole('button',{name:'Preparar renovação',exact:true}).click();const renewalRequest=await requestMonthly();
 assert.equal((await one(env.pool,'SELECT status,previous_subscription_id FROM ride_requests WHERE id=$1',[renewalRequest]))!.previous_subscription_id,contract.id);
 assert.equal((await one(env.pool,'SELECT status FROM ride_requests WHERE id=$1',[renewalRequest]))!.status,'pending');await accept(renewalRequest);await pay();
 const renewalPayment=new URL(passenger.url()).searchParams.get('ref')!;
 const renewed=(await one(env.pool,'SELECT id,start_date::text AS start,end_date::text AS end FROM subscriptions WHERE id IN (SELECT subscription_id FROM ride_reservations WHERE payment_id=$1)',[renewalPayment]))!;assert.ok(renewed);assert.ok(renewed.start>contract.end);
 const prior=(await one(env.pool,'SELECT status,renew FROM subscriptions WHERE id=$1',[contract.id]))!;assert.equal(prior.status,'renewed');assert.equal(prior.renew,false);
 await screen(passenger,'contract',contract.id);assert.equal(await passenger.getByRole('button',{name:'Preparar renovação',exact:true}).count(),0);
 await screen(passenger,'contract',renewed.id);await passenger.locator('.m-app').getByText('Ativo',{exact:true}).waitFor();assert.ok(await passenger.getByRole('button',{name:'Ver reserva',exact:true}).count()>1);
 await passenger.screenshot({path:'docs/previews/java-connected/monthly-renewed-mobile.png',fullPage:true});assert.ok(await passenger.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1));
 const final=await reconciliation(env.pool);assert.equal(final.difference,0);assert.equal(final.returned,cancelled.cents);assert.equal(final.withdrawn,released.driver_cents);
 assert.deepEqual(errors,[]);
});
