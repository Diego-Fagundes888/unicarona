const assert = require('node:assert/strict');
const D = require('./domain-model.cjs');
const fresh = () => { const s=D.createState({},'2026-10-01'); D.act(s,'demoProfile'); return s; };
const as = (s,id) => D.act(s,'actor',{userId:id});
function buy(s,rideId='avulsa-carol') { const r=D.act(s,'requestRide',{rideId}); const p=s.payments.find(x=>x.requestId===r.id); D.act(s,'pay',{paymentId:p.id}); return s.reservations.find(x=>x.requestId===r.id); }
function monthly(s) { const r=D.act(s,'requestMonthly',{routineId:'routine-carol',startDate:'2026-10-01',directions:'ambos',weekdays:[1,2,3,4,5]}); as(s,'carol'); D.act(s,'replyRequest',{requestId:r.id,accept:true}); as(s,'student'); D.act(s,'pay',{paymentId:s.payments.find(x=>x.requestId===r.id).id}); return s.contracts[0]; }
assert.equal(D.calculatePrice(20,fresh().params).cents,593);
{
 const s=fresh(),r=buy(s); assert.equal(D.wallet(s).future,593); as(s,'carol');
 assert.throws(()=>D.act(s,'board',{reservationId:r.id,pin:'0000'}),/Código/);
 D.act(s,'board',{reservationId:r.id,pin:r.pin}); assert.equal(D.wallet(s).available,0);
 D.act(s,'startRide',{rideId:r.rideId}); D.act(s,'finishRide',{rideId:r.rideId}); as(s,'student');
 D.act(s,'confirmFinal',{reservationId:r.id}); D.act(s,'confirmFinal',{reservationId:r.id}); as(s,'carol');
 assert.equal(D.wallet(s).available,534); assert.equal(D.reconciliation(s).commission,59);
 D.act(s,'settings',{notices:true,withdrawal:'monthly'});
 const w=D.act(s,'withdraw',{amount:5.34}); assert.equal(w.cadence,'monthly'); assert.equal(D.wallet(s).available,0); as(s,'admin');
 D.act(s,'reviewTransfer',{type:'withdraw',transferId:w.id,approve:true,reason:'Teste de repasse'});
 D.act(s,'reviewTransfer',{type:'withdraw',transferId:w.id,approve:true,reason:'Repetição'}); assert.equal(D.reconciliation(s).withdrawn,534); D.assertInvariants(s);
}
{
 const s=fresh(),r=buy(s); as(s,'carol'); D.act(s,'cancelRide',{rideId:r.rideId,reason:'Imprevisto'}); D.act(s,'cancelRide',{rideId:r.rideId,reason:'Repetição'}); as(s,'student');
 assert.equal(D.wallet(s).credit,593); assert.equal(D.reconciliation(s).commission,0);
 const r2=buy(s,'routine-carol-2026-10-01-ida'); assert.equal(r2.priceCents,600); D.assertInvariants(s);
}
{
 const s=fresh(),c=monthly(s); assert.equal(c.totalCents,21600); assert.equal(s.reservations.length,40);
 const ride=s.rides.find(x=>x.id===s.reservations[0].rideId); const opposite=s.reservations.find(x=>x.rideId!==ride.id);
 as(s,'carol'); D.act(s,'cancelRide',{rideId:ride.id,reason:'Veículo indisponível'}); as(s,'student'); assert.equal(D.wallet(s).credit,540); assert.equal(s.reservations.find(x=>x.id===opposite.id).status,'confirmed');
 D.act(s,'cancelMonthly',{contractId:c.id}); assert.equal(s.contracts[0].renew,false); assert.equal(s.reservations.filter(x=>x.status==='confirmed').length,39);
}
{
 const s=fresh(); monthly(s); const r=s.reservations[0]; as(s,'carol'); D.act(s,'noClass',{rideId:r.rideId,reason:'Aula cancelada posteriormente'}); as(s,'student');
 D.act(s,'contest',{reservationId:r.id,reason:'A aula aconteceu'}); as(s,'admin'); D.act(s,'tick',{hours:25}); assert.equal(D.reconciliation(s).blocked,540); assert.equal(D.reconciliation(s).retained,0);
 D.act(s,'reviewCase',{reportId:s.reports[0].id,outcome:'credited',reason:'Cancelamento do motorista comprovado'}); assert.equal(D.reconciliation(s).credit,540);
}
{
 const s=fresh(); monthly(s); const r=s.reservations[0]; as(s,'carol'); D.act(s,'noClass',{rideId:r.rideId,reason:'Sem aula'}); as(s,'admin'); D.act(s,'tick',{hours:25}); assert.equal(D.reconciliation(s).retained,540); assert.equal(D.reconciliation(s).commission,0);
}
{
 const s=fresh(); monthly(s); D.act(s,'report',{other:'carol',reason:'Veículo divergente no encontro',severe:true}); assert.equal(s.contracts[0].status,'interrupted'); assert.equal(D.search(s,{type:'mensal',region:'Lago Azul'}).length,0); assert.equal(D.reconciliation(s).blocked,21600);
 assert.throws(()=>D.act(s,'requestRide',{rideId:'avulsa-carol'}),/disponível|bloque/); assert.throws(()=>D.act(s,'reviewCase',{reportId:s.reports[0].id,outcome:'credited',reason:'x'}),/administração/);
 as(s,'admin'); D.act(s,'reviewCase',{reportId:s.reports[0].id,outcome:'credited',reason:'Interrupção confirmada'}); D.assertInvariants(s);
}
{
 const s=fresh(); const before=JSON.stringify(s); assert.throws(()=>D.act(s,'requestMonthly',{routineId:'routine-lucas',startDate:'2026-10-01',directions:'ambos',weekdays:[1]}),/mesmo curso/); assert.equal(JSON.stringify(s),before);
 const r=buy(s); as(s,'carol'); D.act(s,'proposeTime',{rideId:r.rideId,time:'20:00',reason:'Fim da aula'}); as(s,'student'); D.act(s,'replyTime',{reservationId:r.id,accept:false}); assert.equal(D.wallet(s).credit,593);
}
{
 const s=fresh(); monthly(s); as(s,'admin'); D.act(s,'tick',{hours:744}); assert.equal(s.contracts[0].status,'grace'); assert.ok(s.contracts[0].holdRideIds.length); D.act(s,'tick',{hours:72}); assert.equal(s.contracts[0].status,'expired'); assert.equal(s.contracts[0].holdRideIds.length,0);
}
console.log('Domain: price, PIN, final settlement, idempotency, cancellation, monthly agenda, disputes, no-class, safety, grace and ledger passed.');
{
 const s=fresh(); s.rides.find(x=>x.id==='avulsa-carol').capacity=1;
 const first=D.act(s,'requestRide',{rideId:'avulsa-carol'}); as(s,'lucas'); const second=D.act(s,'requestRide',{rideId:'avulsa-carol'});
 D.act(s,'pay',{paymentId:s.payments.find(x=>x.requestId===second.id).id}); as(s,'student'); const before=JSON.stringify(s);
 assert.throws(()=>D.act(s,'pay',{paymentId:s.payments.find(x=>x.requestId===first.id).id}),/vaga mudou/); assert.equal(JSON.stringify(s),before); assert.equal(D.reconciliation(s).received,593);
}
{
 const s=fresh(),r=buy(s);as(s,'carol');D.act(s,'cancelRide',{rideId:r.rideId,reason:'Imprevisto'});as(s,'student');const next=D.act(s,'requestRide',{rideId:'routine-carol-2026-10-01-ida'});
 D.act(s,'pay',{paymentId:s.payments.find(x=>x.requestId===next.id).id,useCredit:true});const p=s.payments.at(-1);assert.equal(p.externalCents,7);assert.equal(p.creditCents,593);assert.equal(D.reconciliation(s).received,600);assert.equal(D.wallet(s).credit,0);D.assertInvariants(s);
}
{
 const s=fresh();monthly(s);const r=s.reservations[0];D.act(s,'notifyAbsence',{reservationId:r.id,reason:'Compromisso avisado'});assert.equal(D.occupied(s,r.rideId),1);assert.equal(D.wallet(s).credit,0);
 as(s,'carol');D.act(s,'startRide',{rideId:r.rideId});D.act(s,'finishRide',{rideId:r.rideId,availability:true});assert.equal(s.reservations[0].boardedAt,'');assert.equal(s.reservations[0].absence,'notified_absence');as(s,'admin');D.act(s,'tick',{hours:25});assert.equal(D.reconciliation(s).driver,486);assert.equal(D.reconciliation(s).commission,54);
}
{
 const s=fresh();const c=monthly(s);as(s,'admin');D.act(s,'tick',{hours:744});as(s,'student');const request=D.act(s,'renewMonthly',{contractId:c.id});as(s,'carol');D.act(s,'replyRequest',{requestId:request.id,accept:true});as(s,'student');D.act(s,'pay',{paymentId:s.payments.find(x=>x.requestId===request.id).id});assert.equal(s.contracts[0].status,'renewed');assert.equal(s.contracts[0].holdRideIds.length,0);assert.equal(s.contracts[1].status,'active');D.assertInvariants(s);
}
{
 const s=fresh();monthly(s);const r=s.reservations[0];as(s,'carol');D.act(s,'board',{reservationId:r.id,pin:r.pin});as(s,'student');D.act(s,'report',{other:'carol',reservationId:r.id,reason:'Risco imediato no trecho',severe:true});assert.equal(s.reservations[0].status,'under_review');assert.equal(D.reconciliation(s).blocked,21600);as(s,'admin');D.act(s,'tick',{hours:25});assert.equal(D.reconciliation(s).driver,0);
}
{
 const s=fresh();monthly(s);D.act(s,'saveProfile',{...D.user(s),classLabel:'B'});assert.equal(s.contracts[0].status,'ended');assert.equal(D.wallet(s).credit,21600);assert.equal(D.reconciliation(s).future,0);
 const t=fresh();t.rides.find(x=>x.id==='routine-carol-2026-10-01-ida').capacity=1;as(t,'lucas');buy(t,'routine-carol-2026-10-01-ida');as(t,'student');assert.throws(()=>monthly(t),/todos os trechos/);assert.equal(t.contracts.length,0);D.assertInvariants(t);
}
console.log('Domain edge cases: last seat atomicity, credit reuse, absence notice without PIN, grace renewal, immediate safety and academic termination passed.');
{
 const s=fresh(); monthly(s); const reservations=D.clone(s.reservations);
 for(const r of reservations.slice(0,36)) {
   const ride=s.rides.find(x=>x.id===r.rideId); const hours=(new Date(`${ride.date}T09:00:00`)-new Date(s.now))/3600000;
   if(hours>0){as(s,'admin');D.act(s,'tick',{hours});}
   as(s,'carol');D.act(s,'board',{reservationId:r.id,pin:r.pin});D.act(s,'startRide',{rideId:r.rideId});D.act(s,'finishRide',{rideId:r.rideId});as(s,'student');D.act(s,'confirmFinal',{reservationId:r.id});
 }
 as(s,'carol');for(const r of reservations.slice(36,38))D.act(s,'cancelRide',{rideId:r.rideId,reason:'Cancelamento do motorista'});
 for(const r of reservations.slice(38))D.act(s,'noClass',{rideId:r.rideId,reason:'Aulas canceladas depois da contratação'});
 as(s,'admin');D.act(s,'tick',{hours:25});const ledger=D.reconciliation(s);
 assert.equal(ledger.received,21600);assert.equal(ledger.driver,17496);assert.equal(ledger.commission,1944);assert.equal(ledger.credit,1080);assert.equal(ledger.retained,1080);assert.equal(ledger.future,0);assert.equal(ledger.difference,0);
}
{
 const s=fresh();s.routines[0].prices={ida:593,volta:593};s.rides.filter(x=>x.routineId==='routine-carol').forEach(x=>x.priceCents=593);
 const p=D.monthlyPreview(s,{routineId:'routine-carol',startDate:'2026-10-01',directions:'ambos',weekdays:[1,2,3,4,5]});assert.equal(p.totalCents,21348);assert.equal(p.lines.reduce((n,x)=>n+x.cents,0),21348);assert.equal(p.lines.filter(x=>x.cents===534).length,28);
}
{
 const s=fresh();monthly(s);D.act(s,'block',{other:'carol'});assert.equal(s.reports.length,1);as(s,'admin');D.act(s,'reviewCase',{reportId:s.reports[0].id,outcome:'no_financial_change',reason:'Acesso separado da apuração financeira'});assert.equal(s.reports[0].status,'financial_pending');D.act(s,'reviewCase',{reportId:s.reports[0].id,outcome:'credited',reason:'Interrupção e crédito apurados'});assert.equal(s.reports[0].status,'resolved');assert.equal(D.reconciliation(s).credit,21600);
}
console.log('Document financial example: 216.00 = 174.96 driver + 19.44 commission + 10.80 credit + 10.80 no-class; cent allocation and separate pending financial review passed.');
{
 const s=fresh();as(s,'admin');D.act(s,'restrict',{userId:'student',suspend:true,reason:'Revisão preventiva demonstrativa'});as(s,'student');assert.throws(()=>D.act(s,'requestRide',{rideId:'avulsa-carol'}),/restrita/);D.act(s,'report',{other:'carol',reason:'Relato continua acessível durante revisão',severe:true});assert.equal(s.reports.length,1);
}
{
 const s=fresh();
 assert.equal(D.search(s,{type:'mensal'}).length,2);
 assert.equal(D.search(s,{type:'mensal',compatibleOnly:true}).length,1);
 assert.equal(D.search(s,{type:'mensal',direction:'ida',time:'19:00'}).length,0);
 assert.equal(D.search(s,{type:'mensal',direction:'volta',time:'19:00'}).length,1);
 D.act(s,'settings',{notices:false,withdrawal:'daily'});
 D.act(s,'waitlist',{routineId:'routine-carol'});assert.equal(s.notifications.length,0);
 as(s,'carol');D.act(s,'routineAvailability',{routineId:'routine-carol',active:false});D.act(s,'routineAvailability',{routineId:'routine-carol',active:true});assert.equal(s.notifications.length,0);
 as(s,'student');D.act(s,'settings',{notices:true,withdrawal:'daily'});
 as(s,'carol');D.act(s,'routineAvailability',{routineId:'routine-carol',active:false});D.act(s,'routineAvailability',{routineId:'routine-carol',active:true});
 assert.equal(s.notifications.filter(x=>x.userId==='student'&&x.title==='Rotina reaberta para pedidos').length,1);
 as(s,'student');D.act(s,'settings',{notices:false,withdrawal:'daily'});const request=D.act(s,'requestRide',{rideId:'avulsa-manual'});as(s,'lucas');D.act(s,'replyRequest',{requestId:request.id,accept:false,reason:'Agenda incompatível'});
 assert.ok(s.notifications.some(x=>x.userId==='student'&&x.ref===request.id));D.assertInvariants(s);
}
console.log('Monthly compatibility/time filters and optional interest notices preserve transactional alerts; withdrawal cadence recorded.');

