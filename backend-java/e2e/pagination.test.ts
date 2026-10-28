import {test} from 'node:test';
import assert from 'node:assert/strict';
import {existsSync} from 'node:fs';
import {chromium,type Page} from 'playwright';
import {setup} from './helpers.js';
import {one} from './helpers.js';
import {notify} from './helpers.js';

test('browser pagination reads real offers and private notices beyond the first page and keeps history/reload state',async t=>{
 const origin='http://127.0.0.1:3001',env=await setup({APP_ORIGIN:origin,PORT:3001});t.after(env.close);await env.app.listen({host:'127.0.0.1',port:3001});
 const executable=process.env.E2E_BROWSER_PATH||'C:/Program Files/Google/Chrome/Application/chrome.exe',browser=await chromium.launch(existsSync(executable)?{executablePath:executable}:{});t.after(()=>browser.close());
 const driver=await (await browser.newContext({locale:'pt-BR'})).newPage(),student=await (await browser.newContext({locale:'pt-BR',viewport:{width:390,height:844}})).newPage();
 const errors:string[]=[];for(const page of [driver,student]){page.setDefaultTimeout(10000);page.on('pageerror',e=>errors.push(e.message));await page.route('https://*.openstreetmap.org/**',r=>r.abort());}
 async function login(page:Page,email:string){await page.goto(origin+'/login.html');await page.locator('#email').fill(email);await page.locator('#password').fill(process.env.SEED_PASSWORD!);await page.getByRole('button',{name:'Entrar',exact:true}).click();await page.getByRole('button',{name:'Oferecer',exact:true}).waitFor();}
 await login(driver,'carolina@example.com');await login(student,'ana@example.com');
 const date=(await one(env.pool,"SELECT ((now() AT TIME ZONE 'America/Sao_Paulo')::date+1)::text AS date"))!.date;
 // Fixtures are published through the authenticated API; no fabricated response.
 const ids=await driver.evaluate(async date=>{const ids:string[]=[];for(let i=0;i<31;i++){const result=await (window as any).UniCaronaAPI.request('/rides',{method:'POST',body:{region:'Paginação sintética',meeting:'Encontro do teste',direction:'ida',date,time:'20:'+String(i).padStart(2,'0'),capacity:3,distance:10,automatic:false}});ids.push(result.id);}return ids;},date);
 await driver.getByRole('button',{name:'Oferecer',exact:true}).click();await driver.getByRole('heading',{name:'Seus próximos trechos',exact:true}).waitFor();assert.equal(await driver.getByRole('button',{name:'Gerenciar trecho',exact:true}).count(),30);
 assert.equal(await driver.locator('[data-c-go=passengers]').filter({hasText:'Gerenciar trecho'}).first().getAttribute('data-ref'),ids[0],'nearest departure appears first');
 await driver.getByRole('navigation',{name:'Trechos',exact:true}).getByRole('button',{name:'Próxima página',exact:true}).click();await driver.getByRole('navigation',{name:'Trechos',exact:true}).getByRole('button',{name:'Página anterior',exact:true}).waitFor();assert.equal(await driver.getByRole('button',{name:'Gerenciar trecho',exact:true}).count(),1);assert.equal(await driver.locator('[data-c-go=passengers]').filter({hasText:'Gerenciar trecho'}).first().getAttribute('data-ref'),ids[30]);
 await student.locator('#dashboard-origin').fill('Paginação sintética');await student.locator('.d-schedule summary').click();await student.locator('#dashboard-date').fill(date);await student.getByRole('button',{name:'Buscar caronas',exact:true}).click();await student.getByRole('navigation',{name:'Resultados',exact:true}).getByRole('button',{name:'Próxima página',exact:true}).waitFor();assert.equal(await student.getByRole('button',{name:'Ver carona',exact:true}).count(),30);
 await student.getByRole('navigation',{name:'Resultados',exact:true}).getByRole('button',{name:'Próxima página',exact:true}).click();await student.getByRole('navigation',{name:'Resultados',exact:true}).getByRole('button',{name:'Página anterior',exact:true}).waitFor();assert.equal(await student.getByRole('button',{name:'Ver carona',exact:true}).count(),1);assert.equal(new URL(student.url()).searchParams.get('page_results'),'30');
 await student.reload();await student.getByRole('navigation',{name:'Resultados',exact:true}).getByRole('button',{name:'Página anterior',exact:true}).waitFor();assert.equal(await student.getByRole('button',{name:'Ver carona',exact:true}).count(),1);await student.goBack();await student.getByRole('navigation',{name:'Resultados',exact:true}).getByRole('button',{name:'Próxima página',exact:true}).waitFor();assert.equal(await student.getByRole('button',{name:'Ver carona',exact:true}).count(),30);
 const studentId=(await one(env.pool,"SELECT id FROM users WHERE email='ana@example.com'"))!.id;
 for(let i=0;i<31;i++)await notify(env.pool,studentId,'Aviso sintético '+String(i).padStart(2,'0'),'Registro privado para verificar páginas.','notifications');
 await student.getByRole('button',{name:'Mais',exact:true}).click();await student.getByRole('navigation',{name:'Outras páginas da sua conta'}).getByRole('button',{name:'Notificações',exact:true}).click();await student.getByRole('navigation',{name:'Avisos',exact:true}).getByRole('button',{name:'Próxima página',exact:true}).waitFor();assert.equal(await student.locator('.m-row').count(),30);
 await student.getByRole('navigation',{name:'Avisos',exact:true}).getByRole('button',{name:'Próxima página',exact:true}).click();await student.getByRole('navigation',{name:'Avisos',exact:true}).getByRole('button',{name:'Página anterior',exact:true}).waitFor();assert.equal(await student.locator('.m-row').count(),1);await student.getByRole('button',{name:'Marcar como lido',exact:true}).click();await student.getByText('30 aviso(s) não lido(s).',{exact:true}).waitFor();assert.equal((await one(env.pool,'SELECT count(*)::int AS count FROM notifications WHERE user_id=$1 AND read_at IS NOT NULL',[studentId]))!.count,1);
 const requestIds=await student.evaluate(async ids=>{const results:string[]=[];for(const rideId of ids){const q=await (window as any).UniCaronaAPI.request('/ride-requests',{method:'POST',body:{type:'avulsa',rideId}});results.push(q.id);}return results;},ids);
 await driver.evaluate(async id=>{await (window as any).UniCaronaAPI.request('/ride-requests/'+id+'/respond',{method:'POST',body:{accept:false}});},requestIds[30]);
 const pending=await driver.evaluate(async()=>await (window as any).UniCaronaAPI.request('/ride-requests?side=driver&status=pending&limit=30'));
 assert.equal(pending.items.length,30);assert.equal(pending.nextOffset,null);assert.ok(pending.items.some((q:any)=>q.id===requestIds[0]));assert.ok(pending.items.every((q:any)=>q.status==='pending'));
 assert.equal((await driver.evaluate(async()=>await (window as any).UniCaronaAPI.request('/notifications?offset=30&limit=30'))).items.some((n:any)=>n.title.startsWith('Aviso sintético')),false,'pagination does not reveal another user notices');
 await student.goto(origin+'/dashboard.html?view=notifications');
 const refusal=student.locator('.m-row').filter({has:student.getByRole('heading',{name:'Solicitação recusada',exact:true})});
 await refusal.getByRole('button',{name:'Abrir aviso',exact:true}).click();
 await student.getByRole('heading',{name:'Sua solicitação',exact:true}).waitFor();
 assert.equal(new URL(student.url()).searchParams.get('ref'),requestIds[30],'real notification opens its own request');
 await student.getByRole('button',{name:'Minhas caronas',exact:true}).click();await student.getByRole('button',{name:'Pedidos',exact:true}).click();await student.getByRole('navigation',{name:'Pedidos',exact:true}).getByRole('button',{name:'Próxima página',exact:true}).waitFor();assert.equal(await student.getByRole('button',{name:'Ver pedido',exact:true}).count(),30);
 await student.getByRole('navigation',{name:'Pedidos',exact:true}).getByRole('button',{name:'Próxima página',exact:true}).click();await student.getByRole('navigation',{name:'Pedidos',exact:true}).getByRole('button',{name:'Página anterior',exact:true}).waitFor();assert.equal(await student.getByRole('button',{name:'Ver pedido',exact:true}).count(),1);await student.reload();await student.getByRole('navigation',{name:'Pedidos',exact:true}).getByRole('button',{name:'Página anterior',exact:true}).waitFor();assert.equal(await student.getByRole('button',{name:'Ver pedido',exact:true}).count(),1);
 assert.ok(await student.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1));assert.deepEqual(errors,[]);
});
