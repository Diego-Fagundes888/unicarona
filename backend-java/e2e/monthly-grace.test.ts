import {test} from 'node:test';
import assert from 'node:assert/strict';
import {existsSync} from 'node:fs';
import {mkdir} from 'node:fs/promises';
import {chromium,type Page} from 'playwright';
import {setup} from './helpers.js';
import {one} from './helpers.js';
import {maintenance} from './helpers.js';
import {reconciliation} from './helpers.js';

test('grace browser journey: unpaid holds are explained, cannot board, renew through acceptance/payment and expire without money movement',async t=>{
 const origin='http://127.0.0.1:3001',env=await setup({APP_ORIGIN:origin,PORT:3001});t.after(env.close);await env.app.listen({host:'127.0.0.1',port:3001});
 const executable=process.env.E2E_BROWSER_PATH||'C:/Program Files/Google/Chrome/Application/chrome.exe',browser=await chromium.launch(existsSync(executable)?{executablePath:executable}:{});t.after(()=>browser.close());
 const driver=await (await browser.newContext({locale:'pt-BR'})).newPage(),passenger=await (await browser.newContext({locale:'pt-BR'})).newPage();const errors:string[]=[];
 for(const page of [driver,passenger]){page.setDefaultTimeout(10000);page.on('pageerror',e=>errors.push(e.message));await page.route('https://*.openstreetmap.org/**',r=>r.abort());}
 async function login(page:Page,email:string){await page.goto(origin+'/login.html');await page.locator('#email').fill(email);await page.locator('#password').fill(process.env.SEED_PASSWORD!);await page.getByRole('button',{name:'Entrar',exact:true}).click();await page.waitForURL('**/dashboard.html');await page.getByRole('button',{name:'Oferecer',exact:true}).waitFor();}
 await login(driver,'carolina@example.com');await login(passenger,'ana@example.com');
 const routine=await driver.evaluate(async()=>await (window as any).UniCaronaAPI.request('/routines',{method:'POST',body:{region:'Carência Gama',meeting:'Encontro sintético',direction:'ambos',time:'23:00',returnTime:'23:30',weekdays:[0,1,2,3,4,5,6],capacity:1,distance:10,automatic:true}}));
 const driverId=(await one(env.pool,"SELECT id FROM users WHERE email='carolina@example.com'"))!.id,passengerId=(await one(env.pool,"SELECT id FROM users WHERE email='ana@example.com'"))!.id;
 async function expiredFixture(){
  // Only the past date boundary is a SQL fixture. The real worker calculates
  // grace and holds; browser/API checkout creates the new paid contract.
  return (await one(env.pool,`INSERT INTO subscriptions(routine_id,driver_id,passenger_id,class_id,start_date,end_date,weekdays,directions,total_cents,discount_bps,status)
   SELECT $1,$2,$3,class_id,(now() AT TIME ZONE 'America/Sao_Paulo')::date-2,(now() AT TIME ZONE 'America/Sao_Paulo')::date-1,ARRAY[0,1,2,3,4,5,6]::smallint[],'ambos',1000,1000,'active' FROM student_profiles WHERE user_id=$3 RETURNING id`,[routine.id,driverId,passengerId]))!;
 }
 const old=await expiredFixture();assert.equal((await maintenance(env.pool)).subscriptions,1);
 const held=(await one(env.pool,'SELECT ride_id FROM subscription_schedule WHERE subscription_id=$1 AND held ORDER BY ride_id LIMIT 1',[old.id]))!;assert.ok(held);
 assert.equal((await one(env.pool,'SELECT count(*)::int AS n FROM ride_reservations WHERE subscription_id=$1',[old.id]))!.n,0);assert.equal((await reconciliation(env.pool)).received,0);
 await driver.goto(origin+'/dashboard.html?view=passengers&ref='+held.ride_id);await driver.getByRole('heading',{name:'Gerenciar trecho',exact:true}).waitFor();assert.equal(await driver.getByRole('button',{name:'Registrar embarque',exact:true}).count(),0);
 await passenger.goto(origin+'/dashboard.html?view=contract&ref='+old.id);await passenger.getByText('Carência · falta pagamento',{exact:true}).waitFor();await passenger.getByText('As vagas da próxima agenda estão retidas durante a carência. Elas não são reservas pagas e não permitem embarque. Prepare a renovação e aguarde o aceite do motorista.',{exact:true}).waitFor();
 assert.ok((await passenger.locator('.m-summary').innerText()).includes('Vagas retidas sem pagamento'));assert.equal(await passenger.getByRole('button',{name:'Ver reserva',exact:true}).count(),0);
 await mkdir('docs/previews/java-connected',{recursive:true});await passenger.setViewportSize({width:1280,height:900});await passenger.screenshot({path:'docs/previews/java-connected/monthly-grace-desktop.png',fullPage:true});
 await passenger.setViewportSize({width:390,height:844});await passenger.screenshot({path:'docs/previews/java-connected/monthly-grace-mobile.png',fullPage:true});assert.ok(await passenger.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1));
 await passenger.getByRole('button',{name:'Preparar renovação',exact:true}).click();await passenger.getByRole('button',{name:'Conferir agenda e valor',exact:true}).click();await passenger.getByRole('button',{name:'Solicitar mensal ao motorista',exact:true}).click();await passenger.getByRole('heading',{name:'Sua solicitação',exact:true}).waitFor();const requestId=new URL(passenger.url()).searchParams.get('ref')!;
 await driver.goto(origin+'/dashboard.html?view=request&ref='+requestId);await driver.getByRole('button',{name:'Aceitar pedido',exact:true}).click();await driver.getByText('Aceito · falta pagamento',{exact:true}).waitFor();
 await passenger.reload();await passenger.getByRole('button',{name:'Ir para pagamento',exact:true}).click();await passenger.getByRole('button',{name:'Continuar para pagamento',exact:true}).click();
 const popup=passenger.waitForEvent('popup');await passenger.getByRole('link',{name:'Abrir pagamento',exact:true}).click();const gateway=await popup;gateway.on('pageerror',e=>errors.push(e.message));await gateway.getByRole('button',{name:'Testar pagamento aprovado',exact:true}).click();await gateway.getByText('Resultado de teste enviado. Confira o estado da reserva no UniCarona.',{exact:true}).waitFor();await gateway.close();
 await passenger.getByRole('button',{name:'Atualizar status',exact:true}).click();await passenger.getByRole('heading',{name:'Reserva confirmada',exact:true}).waitFor();const paymentId=new URL(passenger.url()).searchParams.get('ref')!;
 assert.equal((await one(env.pool,'SELECT status FROM subscriptions WHERE id=$1',[old.id]))!.status,'renewed');assert.equal((await one(env.pool,'SELECT count(*)::int AS n FROM subscription_schedule WHERE subscription_id=$1 AND held',[old.id]))!.n,0);assert.ok((await one(env.pool,'SELECT count(*)::int AS n FROM ride_reservations WHERE payment_id=$1',[paymentId]))!.n>1);
 // A second expired boundary cannot displace paid reservations. Its grace
 // expires via the actual job; receipts, ledger and paid seats stay unchanged.
 const missed=await expiredFixture();await maintenance(env.pool);assert.equal((await one(env.pool,'SELECT status FROM subscriptions WHERE id=$1',[missed.id]))!.status,'renew_conflict');
 const before=await reconciliation(env.pool),paid=(await one(env.pool,'SELECT count(*)::int AS n FROM ride_reservations WHERE payment_id=$1',[paymentId]))!.n;
 await env.pool.query("UPDATE subscriptions SET grace_until=now()-interval '1 second' WHERE id=$1",[missed.id]);await maintenance(env.pool);
 await passenger.goto(origin+'/dashboard.html?view=contract&ref='+missed.id);await passenger.getByText('Vencido',{exact:true}).waitFor();assert.equal(await passenger.getByRole('button',{name:'Preparar renovação',exact:true}).count(),0);
 assert.deepEqual(await reconciliation(env.pool),before);assert.equal((await one(env.pool,'SELECT count(*)::int AS n FROM ride_reservations WHERE payment_id=$1',[paymentId]))!.n,paid);assert.deepEqual(errors,[]);
});
