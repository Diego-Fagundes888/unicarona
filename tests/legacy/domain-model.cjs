/* Academic simulation only. Pure in-memory transitions; no external side effects. */
(function (root, factory) {
  const api = factory();
  if (typeof module === 'object' && module.exports) module.exports = api;
  else root.UniCaronaDomain = api;
})(typeof window === 'undefined' ? globalThis : window, function () {
  'use strict';
  const clone = (value) => typeof structuredClone === 'function' ? structuredClone(value) : JSON.parse(JSON.stringify(value));
  const localDate = (value = new Date()) => {
    const date = value instanceof Date ? value : new Date(`${value}T12:00:00`);
    return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;
  };
  const addDays = (value, amount) => { const date = new Date(`${value}T12:00:00`); date.setDate(date.getDate() + amount); return localDate(date); };
  const monthEnd = (month) => { const [year, number] = month.split('-').map(Number); return localDate(new Date(year, number, 0, 12)); };
  const nextMonth = (month) => { const [year, number] = month.split('-').map(Number); return localDate(new Date(year, number, 1, 12)).slice(0, 7); };
  const nowDate = (state) => localDate(new Date(state.now));
  const normalized = (value) => String(value || '').normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLocaleLowerCase('pt-BR').trim();
  const money = (cents) => new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL' }).format(cents / 100);
  function fail(message, field = '') { const error = new Error(message); error.field = field; throw error; }
  function required(value, label, field) { if (!String(value || '').trim()) fail(`Informe ${label}.`, field); return String(value).trim(); }
  function numeric(value, label, min, max, field) { const n = Number(String(value).replace(',', '.')); if (!Number.isFinite(n) || n < min || n > max) fail(`${label} deve estar entre ${min} e ${max}.`, field); return n; }
  function validDate(value, field = 'date') { if (!/^\d{4}-\d{2}-\d{2}$/.test(value || '') || localDate(value) !== value) fail('Escolha uma data válida.', field); return value; }
  function validTime(value, field = 'time') { if (!/^([01]\d|2[0-3]):[0-5]\d$/.test(value || '')) fail('Informe um horário válido.', field); return value; }
  const id = (state, prefix) => `${prefix}-${++state.sequence}`;
  const find = (list, value, label) => list.find((item) => item.id === value) || fail(`${label} não encontrado.`);
  const user = (state, value = state.actorId) => find(state.users, value, 'Perfil');
  const admin = (state) => { if (user(state).role !== 'admin') fail('Essa ação é exclusiva da administração demonstrativa.'); };
  const usable = (state, value = state.actorId) => { const person = user(state, value); if (person.accountStatus !== 'active') fail('Esta conta está restrita. Consulte a ocorrência e a análise.'); if (!person.adult) fail('É necessário ter 18 anos completos.'); return person; };
  const approved = (state) => { const person = usable(state); if (person.driverStatus !== 'approved') fail('Motorista e veículo precisam de aprovação antes de oferecer caronas.'); return person; };
  const sameClass = (a, b) => Boolean(a.course && b.course && a.periodStatus === 'numbered' && b.periodStatus === 'numbered' && a.periodNumber && b.periodNumber && a.classLabel && b.classLabel && normalized(a.course) === normalized(b.course) && Number(a.periodNumber) === Number(b.periodNumber) && normalized(a.classLabel) === normalized(b.classLabel));
  const blocked = (state, a, b) => state.blocks.some((entry) => entry.active && ((entry.by === a && entry.other === b) || (entry.by === b && entry.other === a)));
  const activeReservation = (reservation) => ['confirmed', 'boarded', 'awaiting_confirmation'].includes(reservation.status);
  const occupied = (state, rideId, exceptContract = '') => state.reservations.filter((entry) => entry.rideId === rideId && activeReservation(entry)).length + state.contracts.filter((entry) => entry.id !== exceptContract && entry.holdRideIds?.includes(rideId) && entry.status === 'grace').length;
  const available = (state, rideId, exceptContract = '') => Math.max(0, find(state.rides, rideId, 'Trecho').capacity - occupied(state, rideId, exceptContract));
  function event(state, type, label, refs = {}) { const entry = { id: id(state, 'evento'), type, label, at: state.now, actorId: state.actorId, ...refs }; state.events.push(entry); return entry; }
  function notify(state, personId, title, body, route, ref = '') { state.notifications.unshift({ id: id(state, 'aviso'), userId: personId, title, body, route, ref, at: state.now, read: false }); }
  function calculatePrice(distance, params, toll = 0) {
    const d = numeric(distance, 'Distância', .1, 2000, 'distance');
    const cost = d * (params.fuel / params.efficiency + params.wear) + numeric(toll, 'Pedágio', 0, 2000, 'toll');
    const share = cost / (1 + params.referencePassengers);
    return { cost, share, cents: Math.round(share / (1 - params.commission) * 100) };
  }
  function createState(profile = {}, date = localDate()) {
    const academic = profile.academicPlacement || {};
    const month = date.slice(0, 7);
    const student = { id: 'student', role: 'user', name: profile.name || 'Estudante', course: profile.course || '', periodStatus: academic.periodStatus || '', periodNumber: academic.periodNumber || '', shift: academic.shift || '', classLabel: academic.classLabel || '', region: profile.region || '', phone: profile.phone || '', email: profile.email || '', birth: profile.birth || '', photo: profile.photo || '', adult: true, accountStatus: 'active', driverStatus: 'not_requested', vehicle: null, settings: { notices: true, withdrawal: 'daily' } };
    const vehicle = { model: 'Volkswagen Polo', color: 'Prata', plate: 'UNI1A23', capacity: 3 };
    const carol = { id: 'carol', role: 'user', name: 'Carolina Martins', course: 'Análise e Desenvolvimento de Sistemas', periodStatus: 'numbered', periodNumber: 3, shift: 'night', classLabel: 'A', region: 'Lago Azul', phone: '(61) 00000-0000', photo: '', adult: true, accountStatus: 'active', driverStatus: 'approved', vehicle, settings: { notices: true, withdrawal: 'daily' } };
    const lucas = { ...clone(carol), id: 'lucas', name: 'Lucas Almeida', course: 'Medicina', periodNumber: 5, classLabel: 'B', region: 'Lago Azul', vehicle: { model: 'Chevrolet Onix', color: 'Branco', plate: 'UNI2B34', capacity: 3 } };
    const state = { version: 1, sequence: 100, actorId: 'student', now: `${date}T09:00:00`, baseDate: date, params: { fuel: 6, efficiency: 10, wear: .20, referencePassengers: 2, commission: .10, discount: .10 }, users: [student, carol, lucas, { id: 'admin', name: 'Administração de exemplo', role: 'admin', adult: true, accountStatus: 'active', settings: {} }], rides: [], routines: [], requests: [], reservations: [], contracts: [], payments: [], parcels: [], withdrawals: [], refunds: [], reports: [], applications: [], blocks: [], waitlist: [], notifications: [], events: [], calendar: [], ui: { network: 'normal', paymentResult: 'approved' } };
    state.routines = [
      { id: 'routine-carol', driverId: 'carol', region: 'Lago Azul', meeting: 'Entrada principal do bairro e portaria da UNICEPLAC', weekdays: [1,2,3,4,5], times: { ida: '18:10', volta: '22:10' }, prices: { ida: 600, volta: 600 }, distances: { ida: 20.25, volta: 20.25 }, capacity: 3, automatic: true, status: 'active', notes: 'Volta combinada com o término da aula. Sem desvio de rota garantido.' },
      { id: 'routine-lucas', driverId: 'lucas', region: 'Gama', meeting: 'Praça central e portaria da UNICEPLAC', weekdays: [1,2,3,4,5], times: { ida: '07:10', volta: '12:20' }, prices: { ida: 450, volta: 450 }, distances: { ida: 15.1875, volta: 15.1875 }, capacity: 3, automatic: false, status: 'active', notes: 'Rotina de Medicina, 5º período, turma B.' }
    ];
    // Demonstration calendar, not a fetched academic calendar.
    state.calendar.push({ id: 'holiday-demo', date: `${month}-12`, title: 'Feriado conhecido — exemplo', known: true }, { id: 'recess-demo', date: `${month}-28`, title: 'Recesso conhecido — exemplo', known: true });
    materialize(state, state.routines[0], date, addDays(date, 7), ['ida', 'volta']);
    materialize(state, state.routines[1], date, addDays(date, 7), ['ida', 'volta']);
    state.rides.push({ id: 'avulsa-carol', driverId: 'carol', routineId: '', region: 'Lago Azul', origin: 'Lago Azul', destination: 'UNICEPLAC', direction: 'ida', date, time: '18:30', meeting: 'Praça de Lago Azul, junto ao comércio', capacity: 3, priceCents: 593, commission: .10, automatic: true, distance: 20, toll: 0, status: 'planned', notes: 'Cálculo ilustrativo: 20 km, sem medição pelo mapa.', vehicle: clone(vehicle) });
    state.rides.push({ id: 'avulsa-manual', driverId: 'lucas', routineId: '', region: 'Lago Azul', origin: 'Lago Azul', destination: 'UNICEPLAC', direction: 'ida', date, time: '19:00', meeting: 'Ponto de encontro da praça', capacity: 2, priceCents: 593, commission: .10, automatic: false, distance: 20, toll: 0, status: 'planned', notes: 'A solicitação aguarda análise do motorista.', vehicle: clone(lucas.vehicle) });
    return state;
  }
  function materialize(state, routine, start, end, directions) {
    for (let date = start; date <= end; date = addDays(date, 1)) {
      if (!routine.weekdays.includes(new Date(`${date}T12:00:00`).getDay()) || state.calendar.some((day) => day.known && day.date === date)) continue;
      for (const direction of directions) {
        if (!routine.times[direction]) continue;
        const rideId = `${routine.id}-${date}-${direction}`;
        if (state.rides.some((ride) => ride.id === rideId)) continue;
        const person = user(state, routine.driverId);
        state.rides.push({ id: rideId, driverId: person.id, routineId: routine.id, region: routine.region, origin: direction === 'ida' ? routine.region : 'UNICEPLAC', destination: direction === 'ida' ? 'UNICEPLAC' : routine.region, direction, date, time: routine.times[direction], meeting: routine.meeting, capacity: routine.capacity, priceCents: routine.prices[direction], commission: state.params.commission, automatic: routine.automatic, distance: routine.distances[direction], toll: 0, status: 'planned', notes: routine.notes, vehicle: clone(person.vehicle) });
      }
    }
  }
  function search(state, query) {
    const actor = user(state);
    const region = normalized(query.region);
    if (query.type === 'mensal') return state.routines.filter((routine) => routine.status === 'active' && routine.driverId !== actor.id && user(state, routine.driverId).driverStatus === 'approved' && user(state, routine.driverId).accountStatus === 'active' && !blocked(state, actor.id, routine.driverId) && (!region || normalized(routine.region).includes(region) || region.includes(normalized(routine.region))) && (!query.direction || routine.times[query.direction]) && (!query.time || (routine.times[query.direction || 'ida'] || '') >= query.time))
      .map((routine) => ({ ...routine, compatible: sameClass(actor, user(state, routine.driverId)) })).filter((routine) => !query.compatibleOnly || routine.compatible);
    return state.rides.filter((ride) => ride.driverId !== actor.id && ride.status === 'planned' && ride.date === query.date && ride.direction === query.direction && user(state, ride.driverId).driverStatus === 'approved' && user(state, ride.driverId).accountStatus === 'active' && !blocked(state, actor.id, ride.driverId) && (!region || normalized(ride.region).includes(region) || region.includes(normalized(ride.region))) && (!query.time || ride.time >= query.time) && (!query.maxPrice || ride.priceCents <= query.maxPrice) && (!query.automatic || ride.automatic) && (!query.onlyAvailable || available(state, ride.id) > 0))
      .sort((a, b) => a.time.localeCompare(b.time));
  }
  function monthlyPreview(state, input, applicantId = state.actorId) {
    const routine = find(state.routines, input.routineId, 'Rotina');
    const applicant = usable(state, applicantId);
    const driver = user(state, routine.driverId);
    if (driver.id === applicantId || driver.driverStatus !== 'approved' || driver.accountStatus !== 'active' || routine.status !== 'active') fail('Esta rotina não está disponível para contratação.');
    if (blocked(state, applicantId, driver.id)) fail('Esta rotina não está disponível para uma nova combinação.');
    if (!sameClass(applicant, driver)) fail('O mensal exige o mesmo curso, período numérico e turma do motorista. Complete ou confira seu perfil.');
    const start = validDate(input.startDate, 'startDate');
    if (start < nowDate(state)) fail('O início precisa ser hoje ou uma data futura.', 'startDate');
    const end = monthEnd(start.slice(0,7));
    const directions = input.directions === 'ambos' ? ['ida','volta'] : [input.directions];
    if (!directions.every((direction) => ['ida','volta'].includes(direction) && routine.times[direction])) fail('Escolha um sentido disponível.', 'directions');
    const weekdays = [...new Set((input.weekdays || []).map(Number))];
    if (!weekdays.length || weekdays.some((day) => !routine.weekdays.includes(day))) fail('Selecione os dias em que a rotina está disponível.', 'weekdays');
    materialize(state, routine, start, end, directions);
    const rides = state.rides.filter((ride) => ride.routineId === routine.id && ride.date >= start && ride.date <= end && weekdays.includes(new Date(`${ride.date}T12:00:00`).getDay()) && directions.includes(ride.direction) && !state.calendar.some((day) => day.known && day.date === ride.date)).sort((a,b) => `${a.date}${a.time}`.localeCompare(`${b.date}${b.time}`));
    if (!rides.length) fail('Não há trechos elegíveis nessa agenda. Nenhuma mensalidade será emitida.');
    const conflicts = rides.filter((ride) => ride.status !== 'planned' || available(state, ride.id, input.exceptContract || '') < 1 || state.reservations.some((entry) => entry.passengerId === applicantId && entry.rideId === ride.id && activeReservation(entry)));
    const original = rides.reduce((sum, ride) => sum + ride.priceCents, 0);
    const totalCents = Math.round(original * (1 - state.params.discount));
    const cents = rides.map((ride) => Math.floor(ride.priceCents * (1 - state.params.discount)));
    let remainder = totalCents - cents.reduce((sum, amount) => sum + amount, 0);
    for (let index = 0; remainder > 0; index = (index + 1) % cents.length) { cents[index]++; remainder--; }
    return { routineId: routine.id, driverId: driver.id, startDate: start, endDate: end, directions: input.directions, weekdays, originalCents: original, discount: state.params.discount, totalCents, excludedDates: state.calendar.filter((day) => day.known && day.date >= start && day.date <= end).map(clone), lines: rides.map((ride, index) => ({ rideId: ride.id, date: ride.date, direction: ride.direction, time: ride.time, cents: cents[index], commission: ride.commission })), conflicts: conflicts.map((ride) => ride.id) };
  }
  function wallet(state, personId = state.actorId) {
    const owned = state.parcels.filter((parcel) => parcel.driverId === personId || parcel.passengerId === personId);
    const credits = state.parcels.filter((parcel) => parcel.passengerId === personId && parcel.status === 'credited').reduce((sum, parcel) => sum + parcel.cents, 0);
    const used = state.payments.filter((payment) => payment.passengerId === personId && payment.status === 'approved').reduce((sum, payment) => sum + payment.creditCents, 0);
    const refunds = state.refunds.filter((entry) => entry.userId === personId && ['pending','approved'].includes(entry.status)).reduce((sum, entry) => sum + entry.cents, 0);
    const released = state.parcels.filter((parcel) => parcel.driverId === personId && parcel.status === 'released').reduce((sum, parcel) => sum + parcel.driverCents, 0);
    const withdrawals = state.withdrawals.filter((entry) => entry.userId === personId && ['pending','approved'].includes(entry.status)).reduce((sum, entry) => sum + entry.cents, 0);
    return { future: owned.filter((parcel) => ['future','awaiting'].includes(parcel.status)).reduce((sum, parcel) => sum + parcel.cents, 0), blocked: owned.filter((parcel) => parcel.status === 'blocked').reduce((sum, parcel) => sum + parcel.cents, 0), available: released - withdrawals, credit: credits - used - refunds, withdrawn: state.withdrawals.filter((entry) => entry.userId === personId && entry.status === 'approved').reduce((sum, entry) => sum + entry.cents, 0), returned: state.refunds.filter((entry) => entry.userId === personId && entry.status === 'approved').reduce((sum, entry) => sum + entry.cents, 0), processing: state.withdrawals.concat(state.refunds).filter((entry) => entry.userId === personId && entry.status === 'pending').reduce((sum, entry) => sum + entry.cents, 0) };
  }
  function reconciliation(state) {
    const result = { received: state.payments.filter((entry) => entry.status === 'approved').reduce((sum, entry) => sum + entry.externalCents, 0), future: 0, blocked: 0, driver: 0, credit: 0, commission: 0, retained: 0, withdrawn: 0, returned: 0, processing: 0 };
    for (const parcel of state.parcels) {
      if (['future','awaiting'].includes(parcel.status)) result.future += parcel.cents;
      if (parcel.status === 'blocked') result.blocked += parcel.cents;
      if (parcel.status === 'released') { result.driver += parcel.driverCents; result.commission += parcel.commissionCents; }
      if (parcel.status === 'credited') result.credit += parcel.cents;
      if (parcel.status === 'retained') result.retained += parcel.cents;
    }
    result.credit -= state.payments.filter((entry) => entry.status === 'approved').reduce((sum, entry) => sum + entry.creditCents, 0);
    for (const withdrawal of state.withdrawals.filter((entry) => ['pending','approved'].includes(entry.status))) { result.driver -= withdrawal.cents; result[withdrawal.status === 'pending' ? 'processing' : 'withdrawn'] += withdrawal.cents; }
    for (const refund of state.refunds.filter((entry) => ['pending','approved'].includes(entry.status))) { result.credit -= refund.cents; result[refund.status === 'pending' ? 'processing' : 'returned'] += refund.cents; }
    result.allocated = Object.entries(result).filter(([key]) => key !== 'received').reduce((sum, [,amount]) => sum + amount, 0);
    result.difference = result.received - result.allocated;
    return result;
  }
  function settle(state, parcel, outcome, reason) {
    if (['released','credited','retained'].includes(parcel.status)) return parcel;
    if (!['future','awaiting','blocked'].includes(parcel.status)) fail('Parcela sem pagamento recebido.');
    parcel.status = outcome;
    parcel.reason = reason;
    parcel.settledAt = state.now;
    if (outcome === 'released') { parcel.commissionCents = Math.round(parcel.cents * parcel.commission); parcel.driverCents = parcel.cents - parcel.commissionCents; }
    event(state, 'settlement', reason, { parcelId: parcel.id, cents: parcel.cents, outcome });
    return parcel;
  }
  function addReservation(state, request, line, payment, contractId = '') {
    const reservationId = id(state, 'reserva');
    const pin = String(1000 + (state.sequence * 7919) % 9000);
    const reservation = { id: reservationId, requestId: request.id, rideId: line.rideId, passengerId: request.passengerId, driverId: request.driverId, contractId, paymentId: payment.id, priceCents: line.cents, status: 'confirmed', pin, boardedAt: '', finalResponse: 'pending', deadline: '', availability: '', absence: '', proposedTime: '', timeResponse: '', createdAt: state.now };
    const parcel = { id: id(state, 'parcela'), reservationId, paymentId: payment.id, passengerId: request.passengerId, driverId: request.driverId, cents: line.cents, commission: line.commission, status: 'future', commissionCents: 0, driverCents: 0, reason: '', settledAt: '' };
    state.reservations.push(reservation); state.parcels.push(parcel);
    return reservation;
  }
  const parcelFor = (state, reservation) => find(state.parcels, state.parcels.find((parcel) => parcel.reservationId === (typeof reservation === 'string' ? reservation : reservation.id))?.id, 'Parcela');
  function ownReservation(state, reservationId) { const reservation = find(state.reservations, reservationId, 'Reserva'); if (reservation.passengerId !== state.actorId) fail('Esta reserva pertence a outra pessoa.'); return reservation; }
  function ownRide(state, rideId) { approved(state); const ride = find(state.rides, rideId, 'Trecho'); if (ride.driverId !== state.actorId) fail('Somente o motorista desta oferta pode alterar o trecho.'); return ride; }
  function closeFuture(state, contract, reason, financial = 'credited') {
    contract.status = financial === 'blocked' ? 'interrupted' : 'ended'; contract.renew = false; contract.holdRideIds = [];
    for (const reservation of state.reservations.filter((entry) => entry.contractId === contract.id && activeReservation(entry))) {
      const ride = find(state.rides, reservation.rideId, 'Trecho');
      if (ride.status === 'completed' || reservation.status === 'boarded' || reservation.status === 'awaiting_confirmation') continue;
      reservation.status = 'cancelled'; reservation.cancelReason = reason;
      if (financial === 'blocked') { const parcel = parcelFor(state, reservation); if (['future','awaiting'].includes(parcel.status)) { parcel.status = 'blocked'; parcel.reason = reason; } }
      else settle(state, parcelFor(state, reservation), 'credited', reason);
    }
    event(state, 'contract_end', reason, { contractId: contract.id });
    notify(state, contract.passengerId, 'Seu vínculo mensal mudou', reason, 'contract', contract.id);
  }
  const operations = {};
  operations.actor = (state, input) => { const person = user(state, input.userId); state.actorId = person.id; return person; };
  operations.demoProfile = (state) => {
    if (state.actorId !== 'student') fail('Carregue o exemplo no perfil do aluno.');
    const person = user(state);
    operations.saveProfile(state, { name: 'Ana Souza', course: 'Análise e Desenvolvimento de Sistemas', periodStatus: 'numbered', periodNumber: 3, shift: 'night', classLabel: 'A', region: 'Lago Azul', email: 'ana@example.com', phone: '(61) 00000-0000' });
    Object.assign(person, { birth: '2000-05-01', adult: true });
    event(state, 'demo_profile', 'Perfil fictício carregado pelo apresentador'); return person;
  };
  operations.saveProfile = (state, input) => {
    const person = usable(state);
    if (person.role === 'admin') fail('O perfil administrativo de exemplo não é um cadastro de aluno.');
    const name = required(input.name, 'seu nome', 'name');
    const course = required(input.course, 'seu curso', 'course');
    const region = required(input.region, 'sua região ou cidade', 'region');
    if (!['numbered','irregular','undefined'].includes(input.periodStatus)) fail('Selecione a situação do período.', 'periodStatus');
    const periodNumber = input.periodStatus === 'numbered' ? numeric(input.periodNumber, 'Número do período', 1, 30, 'periodNumber') : '';
    if (periodNumber && !Number.isInteger(periodNumber)) fail('Use um número inteiro para o período.', 'periodNumber');
    if (!['morning','afternoon','night','full-time','variable'].includes(input.shift)) fail('Selecione o horário das aulas.', 'shift');
    if (input.classLabel && !['A','B','C'].includes(input.classLabel)) fail('Selecione turma A, B ou C.', 'classLabel');
    const phoneDigits = String(input.phone || '').replace(/\D/g, '');
    if (input.phone && (phoneDigits.length < 10 || phoneDigits.length > 15)) fail('Informe um telefone com DDD.', 'phone');
    if (input.email && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(input.email)) fail('Confira o endereço de e-mail.', 'email');
    const previous = clone(person);
    Object.assign(person, { name, course, region, periodStatus: input.periodStatus, periodNumber, shift: input.shift, classLabel: input.classLabel || '', phone: input.phone || '', email: input.email || person.email });
    if ('photo' in input) person.photo = input.photo;
    if (!sameClass(previous, person)) {
      for (const contract of state.contracts.filter((entry) => entry.passengerId === person.id && ['active','grace','cancelled_renewal','renew_conflict'].includes(entry.status))) {
        if (!sameClass(person, user(state, contract.driverId))) closeFuture(state, contract, 'Mudança de curso, período ou turma: parcelas futuras creditadas.');
      }
      for (const contract of state.contracts.filter((entry) => entry.driverId === person.id && ['active','grace','cancelled_renewal','renew_conflict'].includes(entry.status))) {
        if (!sameClass(person, user(state, contract.passengerId))) closeFuture(state, contract, 'Mudança acadêmica do motorista: vínculo futuro encerrado e parcelas creditadas.');
      }
    }
    event(state, 'profile', 'Perfil acadêmico atualizado', { userId: person.id }); return person;
  };
  operations.settings = (state, input) => { const person = usable(state); person.settings = { notices: Boolean(input.notices), withdrawal: input.withdrawal === 'monthly' ? 'monthly' : 'daily' }; event(state, 'settings', 'Preferências atualizadas'); return person.settings; };
  operations.applyDriver = (state, input) => {
    const person = usable(state);
    if (person.role === 'admin') fail('Use o perfil de um aluno para solicitar aprovação.');
    if (!person.course || !person.region) fail('Complete o perfil acadêmico antes de solicitar aprovação.');
    if (!input.documents) fail('Anexe o conjunto fictício de identificação e habilitação.', 'documents');
    const plate = required(input.plate, 'a placa fictícia', 'plate').toUpperCase().replace(/[-\s]/g, '');
    if (!/^[A-Z]{3}\d[A-Z\d]\d{2}$/.test(plate)) fail('Use uma placa no formato AAA1A23 ou AAA1234.', 'plate');
    const capacity = numeric(input.capacity, 'Vagas de passageiros', 1, 8, 'capacity');
    if (!Number.isInteger(capacity)) fail('Informe um número inteiro de vagas.', 'capacity');
    const application = { id: id(state, 'analise'), userId: person.id, vehicle: { model: required(input.model, 'o modelo', 'model'), color: required(input.color, 'a cor', 'color'), plate, capacity }, documentLabel: 'Identificação e CNH fictícias', status: 'pending', submittedAt: state.now, decision: '' };
    state.applications.filter((entry) => entry.userId === person.id && entry.status === 'pending').forEach((entry) => { entry.status = 'replaced'; });
    state.applications.push(application); person.driverStatus = 'pending';
    event(state, 'driver_application', 'Motorista e veículo enviados para análise fictícia', { applicationId: application.id });
    notify(state, person.id, 'Cadastro enviado', 'A publicação ficará disponível após a aprovação de motorista e veículo.', 'driver'); return application;
  };
  operations.reviewDriver = (state, input) => {
    admin(state); const application = find(state.applications, input.applicationId, 'Solicitação de aprovação');
    if (application.status !== 'pending') return application;
    const person = user(state, application.userId);
    const reason = required(input.reason, 'o motivo da decisão', 'reason');
    if (input.approve && state.rides.some((ride) => ride.driverId === person.id && ['planned','active'].includes(ride.status) && occupied(state, ride.id) > application.vehicle.capacity)) fail('O novo veículo não comporta reservas já confirmadas. Resolva os trechos antes de aprovar.');
    application.status = input.approve ? 'approved' : 'rejected'; application.decision = reason; application.reviewedAt = state.now;
    person.driverStatus = application.status;
    if (input.approve) {
      person.vehicle = clone(application.vehicle);
      for (const ride of state.rides.filter((entry) => entry.driverId === person.id && entry.status === 'planned')) { ride.vehicle = clone(person.vehicle); ride.capacity = Math.min(ride.capacity, person.vehicle.capacity); }
      for (const routine of state.routines.filter((entry) => entry.driverId === person.id)) routine.capacity = Math.min(routine.capacity, person.vehicle.capacity);
    }
    event(state, 'driver_review', reason, { applicationId: application.id, outcome: application.status });
    notify(state, person.id, input.approve ? 'Motorista e veículo aprovados no exemplo' : 'Cadastro precisa de revisão', reason, 'driver'); return application;
  };
  operations.createOffer = (state, input) => {
    const person = approved(state);
    const region = required(input.region, 'a região do trajeto', 'region');
    if (normalized(region) === 'uniceplac') fail('Informe um local diferente da faculdade.', 'region');
    const meeting = required(input.meeting, 'o ponto de encontro', 'meeting');
    const capacity = numeric(input.capacity, 'Vagas', 1, person.vehicle.capacity, 'capacity');
    if (!Number.isInteger(capacity)) fail('Informe vagas em número inteiro.', 'capacity');
    const distance = numeric(input.distance, 'Distância ilustrativa', .1, 2000, 'distance');
    const toll = numeric(input.toll || 0, 'Pedágio', 0, 2000, 'toll');
    const quoted = calculatePrice(distance, { ...state.params, referencePassengers: Math.min(state.params.referencePassengers, capacity) }, toll);
    if (input.type === 'mensal') {
      if (!sameClass(person, person)) fail('Curso, período numérico e turma são necessários para oferecer uma rotina mensal.');
      const weekdays = [...new Set((input.weekdays || []).map(Number))];
      if (!weekdays.length || weekdays.some((day) => day < 0 || day > 6)) fail('Selecione os dias da semana.', 'weekdays');
      const directions = input.direction === 'ambos' ? ['ida','volta'] : [input.direction];
      if (!directions.every((direction) => ['ida','volta'].includes(direction))) fail('Selecione ida, volta ou ambos.', 'direction');
      const routine = { id: id(state, 'rotina'), driverId: person.id, region, meeting, weekdays, times: {}, prices: {}, distances: {}, capacity, automatic: Boolean(input.automatic), status: 'active', notes: input.notes || '' };
      for (const direction of directions) { routine.times[direction] = validTime(input[direction === 'ida' ? 'time' : 'returnTime'] || input.time, direction === 'ida' ? 'time' : 'returnTime'); routine.prices[direction] = quoted.cents; routine.distances[direction] = distance; }
      state.routines.push(routine); materialize(state, routine, nowDate(state), monthEnd(nowDate(state).slice(0,7)), directions);
      event(state, 'publish', 'Rotina mensal publicada', { routineId: routine.id }); return routine;
    }
    if (!['ida','volta'].includes(input.direction)) fail('Selecione o sentido do trecho.', 'direction');
    const date = validDate(input.date); if (date < nowDate(state)) fail('Escolha uma data a partir de hoje.', 'date');
    const ride = { id: id(state, 'trecho'), driverId: person.id, routineId: '', region, origin: input.direction === 'ida' ? region : 'UNICEPLAC', destination: input.direction === 'ida' ? 'UNICEPLAC' : region, direction: input.direction, date, time: validTime(input.time), meeting, capacity, priceCents: quoted.cents, commission: state.params.commission, automatic: Boolean(input.automatic), distance, toll, status: 'planned', notes: input.notes || '', vehicle: clone(person.vehicle) };
    state.rides.push(ride); event(state, 'publish', 'Oferta avulsa publicada', { rideId: ride.id, priceCents: ride.priceCents }); return ride;
  };
  operations.editOffer = (state, input) => {
    const ride = ownRide(state, input.rideId);
    if (ride.status !== 'planned') fail('Só é possível editar ofertas que ainda não começaram.');
    const capacity = numeric(input.capacity, 'Vagas', Math.max(1, occupied(state, ride.id)), user(state).vehicle.capacity, 'capacity');
    if (!Number.isInteger(capacity)) fail('Informe vagas em número inteiro.', 'capacity');
    const distance = numeric(input.distance, 'Distância', .1, 2000, 'distance');
    const toll = numeric(input.toll || 0, 'Pedágio', 0, 2000, 'toll');
    ride.capacity = capacity; ride.distance = distance; ride.toll = toll; ride.notes = input.notes || '';
    ride.priceCents = calculatePrice(distance, { ...state.params, referencePassengers: Math.min(state.params.referencePassengers, capacity) }, toll).cents;
    ride.automatic = Boolean(input.automatic);
    event(state, 'offer_edit', 'Oferta editada; preços de reservas anteriores preservados', { rideId: ride.id }); return ride;
  };
  operations.requestRide = (state, input) => {
    const person = usable(state); const ride = find(state.rides, input.rideId, 'Trecho');
    if (ride.driverId === person.id || ride.status !== 'planned' || ride.date < nowDate(state) || user(state, ride.driverId).driverStatus !== 'approved' || user(state, ride.driverId).accountStatus !== 'active' || blocked(state, person.id, ride.driverId)) fail('Essa carona não está disponível para uma nova combinação.');
    const previous = state.requests.find((entry) => entry.passengerId === person.id && entry.rideId === ride.id && ['pending','accepted','confirmed'].includes(entry.status));
    if (previous) return previous;
    if (state.reservations.some((entry) => entry.passengerId === person.id && entry.rideId === ride.id && activeReservation(entry))) fail('Você já tem uma reserva confirmada neste trecho.');
    if (!available(state, ride.id)) fail('A última vaga já foi ocupada. Escolha outro trecho.');
    const request = { id: id(state, 'pedido'), type: 'avulsa', rideId: ride.id, driverId: ride.driverId, passengerId: person.id, status: ride.automatic ? 'accepted' : 'pending', totalCents: ride.priceCents, lines: [{ rideId: ride.id, cents: ride.priceCents, commission: ride.commission }], createdAt: state.now, reason: '' };
    state.requests.push(request);
    if (request.status === 'accepted') createPayment(state, request);
    event(state, 'request', 'Vaga solicitada', { requestId: request.id });
    notify(state, ride.driverId, 'Nova solicitação de vaga', `${person.name} solicitou o trecho ${ride.date} às ${ride.time}.`, 'requests', request.id);
    notify(state, person.id, request.status === 'accepted' ? 'Pedido aceito: falta o pagamento' : 'Aguardando o motorista', 'Uma solicitação sem pagamento aprovado ainda não é reserva confirmada.', 'request', request.id); return request;
  };
  operations.requestMonthly = (state, input) => {
    const preview = monthlyPreview(state, input);
    if (preview.conflicts.length) fail('Não há vaga em todos os trechos. Escolha outra agenda ou entre na lista de espera.');
    if (state.contracts.some((entry) => entry.passengerId === state.actorId && entry.routineId === preview.routineId && ['active','grace','cancelled_renewal'].includes(entry.status) && entry.endDate >= preview.startDate && !input.previousContractId)) fail('Você já tem um plano dessa rotina nesse período.');
    const previous = state.requests.find((entry) => entry.passengerId === state.actorId && entry.type === 'mensal' && entry.preview.routineId === preview.routineId && entry.preview.startDate === preview.startDate && ['pending','accepted','confirmed'].includes(entry.status));
    if (previous) return previous;
    const request = { id: id(state, 'pedido'), type: 'mensal', rideId: '', driverId: preview.driverId, passengerId: state.actorId, status: 'pending', totalCents: preview.totalCents, lines: clone(preview.lines), preview: clone(preview), previousContractId: input.previousContractId || '', createdAt: state.now, reason: '' };
    state.requests.push(request); event(state, 'monthly_request', 'Rotina mensal solicitada com aceite específico', { requestId: request.id });
    notify(state, request.driverId, 'Solicitação mensal', 'Confira turma, dias, sentidos e capacidade de todos os trechos.', 'requests', request.id);
    notify(state, state.actorId, 'Seu plano aguarda análise', 'O mensal exige um aceite específico do motorista antes do pagamento.', 'request', request.id); return request;
  };
  function createPayment(state, request) { let payment = state.payments.find((entry) => entry.requestId === request.id); if (!payment) { payment = { id: id(state, 'pagamento'), requestId: request.id, passengerId: request.passengerId, amountCents: request.totalCents, status: 'pending', externalCents: 0, creditCents: 0, method: 'pix', attempts: 0, createdAt: state.now }; state.payments.push(payment); } return payment; }
  operations.replyRequest = (state, input) => {
    approved(state); const request = find(state.requests, input.requestId, 'Pedido');
    if (request.driverId !== state.actorId) fail('Este pedido é de outro motorista.');
    if (request.status !== 'pending') return request;
    if (input.accept) {
      if (blocked(state, request.driverId, request.passengerId)) fail('Este pedido não está disponível para uma nova combinação.');
      usable(state, request.passengerId);
      if (request.type === 'mensal' && !sameClass(user(state, request.passengerId), user(state))) fail('A combinação acadêmica mudou. O plano não pode ser aceito.');
      if (request.lines.some((line) => !available(state, line.rideId, request.previousContractId || '') || find(state.rides, line.rideId, 'Trecho').status !== 'planned' || find(state.rides, line.rideId, 'Trecho').date < nowDate(state))) fail('Não há mais vaga em todos os trechos solicitados.');
      request.status = 'accepted'; createPayment(state, request);
    } else { request.status = 'rejected'; request.reason = required(input.reason, 'o motivo da recusa', 'reason'); }
    event(state, 'request_reply', input.accept ? 'Solicitação aceita, aguardando pagamento' : request.reason, { requestId: request.id });
    notify(state, request.passengerId, input.accept ? 'Seu pedido foi aceito' : 'Pedido não aceito', input.accept ? 'Confira o pagamento demonstrativo para confirmar a reserva.' : request.reason, 'request', request.id); return request;
  };
  operations.expireRequest = (state, input) => { approved(state); const request = find(state.requests, input.requestId, 'Pedido'); if (request.driverId !== state.actorId || !['pending','accepted'].includes(request.status)) fail('Este pedido não pode ser encerrado.'); request.status = 'expired'; request.reason = required(input.reason, 'o motivo do encerramento', 'reason'); state.payments.filter((payment) => payment.requestId === request.id && payment.status !== 'approved').forEach((payment) => { payment.status = 'expired'; }); notify(state, request.passengerId, 'Pedido encerrado', request.reason, 'request', request.id); event(state, 'request_expired', request.reason, { requestId: request.id }); return request; };
  operations.pay = (state, input) => {
    usable(state); const payment = find(state.payments, input.paymentId, 'Pagamento');
    if (payment.passengerId !== state.actorId) fail('Este pagamento pertence a outra pessoa.');
    if (payment.status === 'approved') return payment;
    const request = find(state.requests, payment.requestId, 'Pedido');
    if (request.status !== 'accepted') fail('O motorista ainda não aceitou ou esse pedido foi encerrado.');
    if (state.ui.network === 'offline' || input.outcome === 'offline') fail('Sem conexão. Nenhum pagamento foi recebido; tente novamente.');
    payment.attempts++; payment.method = input.method === 'card' ? 'card' : 'pix';
    if (input.outcome === 'failed') { payment.status = 'failed'; event(state, 'payment_failure', 'Pagamento fictício não aprovado', { paymentId: payment.id }); return payment; }
    if (input.outcome === 'pending') { payment.status = 'pending'; return payment; }
    const driver = user(state, request.driverId);
    if (driver.driverStatus !== 'approved' || driver.accountStatus !== 'active' || blocked(state, request.passengerId, driver.id)) fail('A oferta deixou de estar disponível. Nenhum valor foi recebido.');
    if (request.type === 'mensal' && !sameClass(user(state), driver)) fail('Os dados acadêmicos mudaram. Revise a elegibilidade antes de pagar.');
    const previousContract = request.previousContractId ? find(state.contracts, request.previousContractId, 'Plano anterior') : null;
    if (request.lines.some((line) => state.reservations.some((entry) => entry.rideId === line.rideId && entry.passengerId === state.actorId && activeReservation(entry)))) fail('Você já possui uma reserva para um dos trechos. Não criamos uma segunda vaga para a mesma pessoa.');
    if (previousContract && ['expired','ended','interrupted'].includes(previousContract.status)) fail('A renovação anterior foi encerrada. Solicite um novo plano.');
    if (request.lines.some((line) => !available(state, line.rideId, previousContract?.id || '') || find(state.rides, line.rideId, 'Trecho').status !== 'planned' || find(state.rides, line.rideId, 'Trecho').date < nowDate(state))) fail('A agenda ou a última vaga mudou. Nenhum pagamento foi recebido; faça uma nova busca.');
    payment.creditCents = input.useCredit ? Math.min(wallet(state).credit, payment.amountCents) : 0;
    payment.externalCents = payment.amountCents - payment.creditCents; payment.status = 'approved'; payment.paidAt = state.now;
    request.status = 'confirmed';
    let contract = null;
    if (request.type === 'mensal') {
      contract = { id: id(state, 'mensal'), requestId: request.id, routineId: request.preview.routineId, driverId: driver.id, passengerId: state.actorId, startDate: request.preview.startDate, endDate: request.preview.endDate, weekdays: clone(request.preview.weekdays), directions: request.preview.directions, totalCents: payment.amountCents, discount: request.preview.discount, status: 'active', renew: true, holdRideIds: [], previousContractId: request.previousContractId || '', createdAt: state.now, academicSnapshot: { course: user(state).course, periodNumber: user(state).periodNumber, classLabel: user(state).classLabel }, absenceCount: 0 };
      state.contracts.push(contract);
      if (previousContract) { previousContract.status = 'renewed'; previousContract.holdRideIds = []; previousContract.renew = false; }
    }
    for (const line of request.lines) addReservation(state, request, line, payment, contract?.id || '');
    event(state, 'payment', 'Pagamento fictício recebido e reservas confirmadas', { paymentId: payment.id, cents: payment.amountCents });
    notify(state, state.actorId, 'Reserva confirmada', 'Confira o encontro, o veículo e o código de embarque do seu trecho.', contract ? 'contract' : 'reservation', contract?.id || state.reservations.find((entry) => entry.requestId === request.id).id);
    notify(state, driver.id, 'Pagamento aprovado no exemplo', 'As vagas foram confirmadas; os valores futuros ainda não estão disponíveis para saque.', 'passengers', request.lines[0].rideId); return payment;
  };
  const deadline = (state, hours = 24) => { const date = new Date(state.now); date.setHours(date.getHours() + hours); return date.toISOString(); };
  operations.startRide = (state, input) => {
    const ride = ownRide(state, input.rideId);
    if (ride.status === 'active') return ride;
    if (ride.status !== 'planned' || ride.date !== nowDate(state)) fail('Inicie somente um trecho previsto para hoje.');
    ride.status = 'active'; event(state, 'start', 'Trecho iniciado', { rideId: ride.id }); return ride;
  };
  operations.board = (state, input) => {
    const reservation = find(state.reservations, input.reservationId, 'Reserva'); const ride = ownRide(state, reservation.rideId);
    if (!['planned','active'].includes(ride.status) || ride.date !== nowDate(state)) fail('O embarque está disponível no dia do trecho.');
    if (reservation.status === 'boarded') return reservation;
    if (reservation.status !== 'confirmed') fail('Esta reserva não permite embarque.');
    if (String(input.pin) !== reservation.pin) fail('Código incorreto. Confira o PIN desse passageiro e desse trecho.', 'pin');
    reservation.status = 'boarded'; reservation.boardedAt = state.now;
    event(state, 'boarding', 'PIN validado: embarque registrado, sem liberar pagamento', { reservationId: reservation.id }); return reservation;
  };
  function awaitFinal(state, reservation, kind) {
    const parcel = parcelFor(state, reservation.id);
    if (parcel.status !== 'future') return;
    reservation.status = 'awaiting_confirmation'; reservation.availability = kind; reservation.deadline = deadline(state);
    parcel.status = 'awaiting'; parcel.deadline = reservation.deadline;
    notify(state, reservation.passengerId, kind === 'no_class' ? 'Sem aula informado' : 'Confirme o trecho', 'Você tem 24 horas, nesta proposta de demonstração, para confirmar ou contestar esta parcela.', 'reservation', reservation.id);
  }
  function addCase(state, reservation, reason, kind = 'dispute') {
    const previous = state.reports.find((entry) => entry.reservationId === reservation?.id && entry.status === 'pending' && entry.kind === kind);
    if (previous) return previous;
    const report = { id: id(state, 'caso'), kind, by: state.actorId, other: reservation ? (reservation.passengerId === state.actorId ? reservation.driverId : reservation.passengerId) : '', reservationId: reservation?.id || '', reason, status: 'pending', at: state.now, decision: '' };
    state.reports.push(report); event(state, 'case', reason, { reportId: report.id }); return report;
  }
  operations.finishRide = (state, input) => {
    const ride = ownRide(state, input.rideId);
    if (ride.status === 'completed') return ride;
    if (ride.status !== 'active') fail('Inicie o trecho antes de declarar sua conclusão.');
    ride.status = 'completed'; ride.completedAt = state.now;
    for (const reservation of state.reservations.filter((entry) => entry.rideId === ride.id && activeReservation(entry))) {
      if (reservation.boardedAt) awaitFinal(state, reservation, 'fulfilled');
      else if (reservation.contractId && input.availability) {
        reservation.absence = reservation.absence === 'notified' ? 'notified_absence' : 'passenger_absent'; awaitFinal(state, reservation, 'fulfilled_absence');
        const contract = find(state.contracts, reservation.contractId, 'Plano'); contract.absenceCount++;
        if (contract.absenceCount === 3) { addCase(state, reservation, 'Três ausências no mensal: análise sem punição automática.', 'absence_review'); notify(state, reservation.passengerId, 'Ausências no mensal', 'O vínculo continua; confira sua rotina e o registro de disponibilidade.', 'contract', contract.id); }
      } else {
        reservation.status = 'under_review'; parcelFor(state, reservation.id).status = 'blocked';
        addCase(state, reservation, 'Sem registro de embarque ou de disponibilidade cumprida. Política financeira em análise.');
      }
    }
    event(state, 'finish', 'Conclusão declarada; confirmação final e dinheiro separados do PIN', { rideId: ride.id }); return ride;
  };
  operations.confirmFinal = (state, input) => {
    const reservation = ownReservation(state, input.reservationId); const parcel = parcelFor(state, reservation.id);
    if (reservation.finalResponse === 'confirmed') return reservation;
    if (reservation.status !== 'awaiting_confirmation' || parcel.status !== 'awaiting') fail('Aguarde a declaração do motorista ou a análise da contestação.');
    settle(state, parcel, reservation.availability === 'no_class' ? 'retained' : 'released', reservation.availability === 'no_class' ? 'Sem aula posterior: parcela destinada ao app' : 'Confirmação final do passageiro');
    reservation.finalResponse = 'confirmed'; reservation.status = 'completed'; return reservation;
  };
  operations.contest = (state, input) => {
    const reservation = ownReservation(state, input.reservationId); const parcel = parcelFor(state, reservation.id);
    const reason = required(input.reason, 'o que aconteceu', 'reason');
    if (['future','awaiting'].includes(parcel.status)) { parcel.status = 'blocked'; reservation.status = 'under_review'; reservation.finalResponse = 'contested'; }
    return addCase(state, reservation, reason, 'dispute');
  };
  operations.cancelPassenger = (state, input) => {
    const reservation = ownReservation(state, input.reservationId);
    if (reservation.status === 'cancelled') return reservation;
    if (reservation.contractId) fail('Use o gerenciamento do mensal. Não é possível revender a vaga por uma ausência.');
    if (reservation.status !== 'confirmed') fail('A reserva já começou ou foi encerrada.');
    const reason = required(input.reason, 'o motivo', 'reason'); reservation.status = 'cancelled'; parcelFor(state, reservation.id).status = 'blocked';
    return addCase(state, reservation, `Cancelamento pelo passageiro: ${reason}. Regra financeira pendente de análise.`);
  };
  operations.cancelRide = (state, input) => {
    const ride = ownRide(state, input.rideId);
    if (ride.status === 'cancelled') return ride;
    if (!['planned','active'].includes(ride.status)) fail('Este trecho já foi encerrado.');
    const reason = required(input.reason, 'o motivo do cancelamento', 'reason'); ride.status = 'cancelled';
    for (const reservation of state.reservations.filter((entry) => entry.rideId === ride.id && activeReservation(entry))) {
      reservation.status = 'cancelled'; settle(state, parcelFor(state, reservation.id), 'credited', `Cancelamento do motorista: ${reason}`);
      notify(state, reservation.passengerId, 'Trecho cancelado', 'A parcela paga desse trecho virou crédito integral, incluindo o desconto do mensal.', 'reservation', reservation.id);
    }
    for (const request of state.requests.filter((entry) => ['pending','accepted'].includes(entry.status) && entry.lines.some((line) => line.rideId === ride.id))) {
      request.status = 'cancelled'; state.payments.filter((entry) => entry.requestId === request.id && entry.status !== 'approved').forEach((entry) => { entry.status = 'expired'; });
    }
    state.contracts.forEach((contract) => { contract.holdRideIds = contract.holdRideIds.filter((rideId) => rideId !== ride.id); });
    event(state, 'cancel_ride', reason, { rideId: ride.id }); return ride;
  };
  operations.noClass = (state, input) => {
    const ride = ownRide(state, input.rideId);
    if (ride.status === 'no_class') return ride;
    if (ride.status !== 'planned') fail('Registre Sem aula antes do início do trecho.');
    const reason = required(input.reason, 'o motivo', 'reason');
    if (state.reservations.some((entry) => entry.rideId === ride.id && activeReservation(entry) && !entry.contractId)) fail('Há reservas avulsas. Cancele esse trecho com crédito integral ou resolva as reservas antes de registrar Sem aula.');
    ride.status = 'no_class';
    state.reservations.filter((entry) => entry.rideId === ride.id && activeReservation(entry)).forEach((entry) => awaitFinal(state, entry, 'no_class'));
    event(state, 'no_class', reason, { rideId: ride.id }); return ride;
  };
  operations.proposeTime = (state, input) => {
    const ride = ownRide(state, input.rideId); if (ride.status !== 'planned') fail('O trecho já começou ou foi encerrado.');
    ride.proposedTime = validTime(input.time); ride.timeProposalReason = required(input.reason, 'o motivo do novo horário', 'reason');
    const reservations = state.reservations.filter((entry) => entry.rideId === ride.id && activeReservation(entry));
    for (const entry of reservations) { entry.proposedTime = ride.proposedTime; entry.timeResponse = 'pending'; notify(state, entry.passengerId, 'Proposta de novo horário', `${ride.time} → ${ride.proposedTime}. Confirme se poderá acompanhar.`, 'reservation', entry.id); }
    if (!reservations.length) { ride.time = ride.proposedTime; ride.proposedTime = ''; }
    event(state, 'time_proposal', 'Alteração coletiva depende do acordo dos passageiros', { rideId: ride.id }); return ride;
  };
  operations.replyTime = (state, input) => {
    const reservation = ownReservation(state, input.reservationId); const ride = find(state.rides, reservation.rideId, 'Trecho');
    if (reservation.timeResponse !== 'pending') return reservation;
    if (!input.accept && reservation.contractId) { reservation.timeResponse = 'declined'; notify(state, ride.driverId, 'Horário sem consenso', 'Um mensalista não concordou. Preserve o horário ou cancele o trecho com crédito integral.', 'passengers', ride.id); }
    else if (!input.accept) { reservation.timeResponse = 'declined'; reservation.status = 'cancelled'; settle(state, parcelFor(state, reservation.id), 'credited', 'Novo horário recusado pelo avulso: cancelamento pelo motorista'); }
    else reservation.timeResponse = 'accepted';
    const remaining = state.reservations.filter((entry) => entry.rideId === ride.id && activeReservation(entry));
    if (remaining.every((entry) => entry.timeResponse === 'accepted')) { ride.time = ride.proposedTime; ride.proposedTime = ''; }
    return reservation;
  };
  operations.keepOriginalTime = (state, input) => {
    const ride = ownRide(state, input.rideId); if (ride.status !== 'planned') fail('O trecho já começou ou foi encerrado.');
    ride.proposedTime = '';
    for (const reservation of state.reservations.filter((entry) => entry.rideId === ride.id && activeReservation(entry))) {
      reservation.proposedTime = ''; reservation.timeResponse = 'withdrawn'; notify(state, reservation.passengerId, 'Horário original preservado', `A proposta foi retirada. O trecho continua às ${ride.time}.`, 'reservation', reservation.id);
    }
    event(state, 'time_kept', 'Proposta retirada; horário original mantido', { rideId: ride.id }); return ride;
  };
  operations.cancelMonthly = (state, input) => {
    const contract = find(state.contracts, input.contractId, 'Plano'); if (contract.passengerId !== state.actorId) fail('Este plano pertence a outra pessoa.');
    if (!['active','grace','cancelled_renewal','renew_conflict'].includes(contract.status)) return contract;
    contract.renew = false; contract.holdRideIds = []; contract.status = 'cancelled_renewal';
    event(state, 'stop_renewal', 'Renovação desativada; direitos do período pago preservados', { contractId: contract.id }); return contract;
  };
  operations.removeMonthly = (state, input) => {
    approved(state); const contract = find(state.contracts, input.contractId, 'Plano'); if (contract.driverId !== state.actorId) fail('Este vínculo pertence a outro motorista.');
    if (['ended','interrupted','expired'].includes(contract.status)) return contract;
    closeFuture(state, contract, required(input.reason, 'o motivo da remoção', 'reason'));
    state.waitlist.push({ id: id(state, 'espera'), userId: contract.passengerId, routineId: contract.routineId, status: 'waiting', priority: true, at: state.now }); return contract;
  };
  operations.renewMonthly = (state, input) => {
    const contract = find(state.contracts, input.contractId, 'Plano'); if (contract.passengerId !== state.actorId) fail('Este plano pertence a outra pessoa.');
    if (!contract.renew || !['active','grace','renew_conflict'].includes(contract.status)) fail('A renovação está encerrada. Busque um novo plano.');
    const nextStart = `${nextMonth(contract.endDate.slice(0,7))}-01`;
    const startDate = input.startDate || (nextStart < nowDate(state) ? nowDate(state) : nextStart);
    if (startDate <= contract.endDate) fail('A renovação precisa começar após o período contratado.');
    return operations.requestMonthly(state, { routineId: contract.routineId, startDate, directions: contract.directions, weekdays: contract.weekdays, previousContractId: contract.id, exceptContract: contract.id });
  };
  operations.waitlist = (state, input) => {
    const routine = find(state.routines, input.routineId, 'Rotina'); usable(state);
    if (!sameClass(user(state), user(state, routine.driverId)) || blocked(state, state.actorId, routine.driverId)) fail('Confira a compatibilidade acadêmica antes de entrar na lista.');
    const existing = state.waitlist.find((entry) => entry.userId === state.actorId && entry.routineId === routine.id && entry.status === 'waiting'); if (existing) return existing;
    const entry = { id: id(state, 'espera'), userId: state.actorId, routineId: routine.id, priority: false, status: 'waiting', at: state.now }; state.waitlist.push(entry); event(state, 'waitlist', 'Lista de interesse sem reserva ou garantia de vaga', { waitlistId: entry.id });
    if (user(state).settings.notices) notify(state, state.actorId, 'Interesse registrado', 'Você poderá receber avisos quando a rotina reabrir. Interesse não ocupa vaga nem garante disponibilidade.', 'monthly', routine.id); return entry;
  };
  operations.leaveWaitlist = (state, input) => { const entry = find(state.waitlist, input.waitlistId, 'Interesse'); if (entry.userId !== state.actorId) fail('Este interesse pertence a outra pessoa.'); entry.status = 'cancelled'; event(state, 'waitlist_end', 'Interesse retirado, sem alterar reservas'); return entry; };
  operations.notifyAbsence = (state, input) => {
    const reservation = ownReservation(state, input.reservationId); const ride = find(state.rides, reservation.rideId, 'Trecho');
    if (!reservation.contractId || reservation.status !== 'confirmed' || ride.status !== 'planned') fail('Avise ausência apenas de um trecho mensal confirmado que ainda não começou.');
    reservation.absence = 'notified'; reservation.absenceReason = required(input.reason, 'o motivo do aviso', 'reason');
    notify(state, reservation.driverId, 'Mensalista avisou ausência', 'A vaga continua reservada e não pode ser revendida. Registre separadamente a disponibilidade cumprida.', 'passengers', ride.id);
    event(state, 'absence_notice', 'Ausência mensal avisada; vaga e preço preservados', { reservationId: reservation.id }); return reservation;
  };
  operations.routineAvailability = (state, input) => {
    approved(state); const routine = find(state.routines, input.routineId, 'Rotina'); if (routine.driverId !== state.actorId) fail('Esta rotina pertence a outra pessoa.');
    const wasPaused = routine.status === 'paused'; routine.status = input.active ? 'active' : 'paused';
    if (wasPaused && input.active) for (const entry of state.waitlist.filter((entry) => entry.routineId === routine.id && entry.status === 'waiting')) {
      const person = user(state, entry.userId);
      if (person.settings.notices && person.accountStatus === 'active' && sameClass(person, user(state)) && !blocked(state, person.id, state.actorId)) notify(state, person.id, 'Rotina reaberta para pedidos', 'Confira toda a agenda antes de solicitar. Este aviso não garante vaga nem substituição.', 'monthly', routine.id);
    }
    event(state, 'routine_availability', input.active ? 'Novas contratações da rotina retomadas' : 'Novas contratações da rotina pausadas; reservas pagas mantidas', { routineId: routine.id }); return routine;
  };
  operations.block = (state, input) => {
    user(state); user(state, input.other); if (input.other === state.actorId) fail('Selecione outra pessoa.');
    const existing = state.blocks.find((entry) => entry.by === state.actorId && entry.other === input.other);
    if (existing) existing.active = true; else state.blocks.push({ id: id(state, 'bloqueio'), by: state.actorId, other: input.other, active: true });
    const before = new Set(state.parcels.filter((entry) => entry.status === 'blocked').map((entry) => entry.id));
    for (const contract of state.contracts.filter((entry) => ['active','grace','cancelled_renewal','renew_conflict'].includes(entry.status) && ((entry.driverId === input.other && entry.passengerId === state.actorId) || (entry.passengerId === input.other && entry.driverId === state.actorId)))) closeFuture(state, contract, 'Vínculo interrompido por segurança; parcelas futuras em análise.', 'blocked');
    const changed = state.parcels.filter((entry) => entry.status === 'blocked' && !before.has(entry.id)).map((entry) => entry.id);
    if (changed.length) {
      const report = input.reportId ? find(state.reports, input.reportId, 'Ocorrência') : addCase(state, null, 'Vínculo interrompido após bloqueio pessoal. Apurar parcelas futuras separadamente.', 'safety');
      report.other = input.other; report.parcelIds = [...new Set((report.parcelIds || []).concat(changed))];
    }
    event(state, 'block', 'Novas combinações bloqueadas; identidade de quem bloqueou não é notificada');
  };
  operations.unblock = (state, input) => { const entry = find(state.blocks, input.blockId, 'Bloqueio'); if (entry.by !== state.actorId) fail('Você só pode alterar seus bloqueios.'); entry.active = false; event(state, 'unblock', 'Bloqueio pessoal retirado; vínculos encerrados não são reativados'); return entry; };
  operations.report = (state, input) => {
    const person = user(state); const other = user(state, input.other); if (person.id === other.id || other.role === 'admin' || person.role === 'admin') fail('Selecione o perfil de um aluno e a pessoa envolvida.');
    let reservation = null;
    if (input.reservationId) { reservation = find(state.reservations, input.reservationId, 'Reserva'); if (![reservation.passengerId,reservation.driverId].includes(person.id) || ![reservation.passengerId,reservation.driverId].includes(other.id)) fail('Essa reserva não corresponde às pessoas envolvidas.'); }
    const report = addCase(state, reservation, required(input.reason, 'o relato', 'reason'), 'safety'); report.other = other.id; report.severe = Boolean(input.severe);
    if (reservation && input.severe) {
      const parcel = parcelFor(state, reservation);
      if (['future','awaiting'].includes(parcel.status)) { parcel.status = 'blocked'; reservation.status = 'under_review'; report.parcelIds = [parcel.id]; }
    }
    if (input.block || input.severe) operations.block(state, { other: other.id, reportId: report.id });
    return report;
  };
  operations.reviewCase = (state, input) => {
    admin(state); const report = find(state.reports, input.reportId, 'Ocorrência'); if (!['pending','financial_pending'].includes(report.status)) return report;
    report.decision = required(input.reason, 'a justificativa', 'reason'); report.status = 'resolved'; report.outcome = input.outcome; report.reviewedAt = state.now;
    const targets = report.reservationId ? [parcelFor(state, report.reservationId)] : [];
    if (!['released','credited','retained','no_financial_change'].includes(input.outcome)) fail('Selecione a decisão financeira.');
    if (input.outcome === 'no_financial_change' && state.parcels.some((entry) => entry.status === 'blocked' && (entry.reservationId === report.reservationId || report.parcelIds?.includes(entry.id)))) report.status = 'financial_pending';
    for (const parcel of targets) if (parcel.status === 'blocked' && input.outcome !== 'no_financial_change') { settle(state, parcel, input.outcome, report.decision); const reservation = find(state.reservations, parcel.reservationId, 'Reserva'); reservation.status = input.outcome === 'credited' ? 'cancelled' : 'completed'; }
    if (report.kind === 'safety' && input.outcome !== 'no_financial_change') {
      for (const parcel of state.parcels.filter((entry) => entry.status === 'blocked' && report.parcelIds?.includes(entry.id))) settle(state, parcel, input.outcome, report.decision);
    }
    notify(state, report.by, report.status === 'financial_pending' ? 'Análise financeira continua pendente' : 'Análise concluída no exemplo', report.decision, 'safety', report.id); event(state, 'review', report.decision, { reportId: report.id }); return report;
  };
  operations.restrict = (state, input) => {
    admin(state); const person = user(state, input.userId); if (person.role === 'admin') fail('Selecione uma conta de aluno.');
    const reason = required(input.reason, 'a justificativa', 'reason'); person.accountStatus = input.suspend ? 'suspended' : 'active';
    if (input.suspend) {
      const before = new Set(state.parcels.filter((entry) => entry.status === 'blocked').map((entry) => entry.id));
      state.contracts.filter((entry) => [entry.driverId,entry.passengerId].includes(person.id) && ['active','grace','cancelled_renewal','renew_conflict'].includes(entry.status)).forEach((entry) => closeFuture(state, entry, 'Suspensão preventiva: futuro bloqueado para análise.', 'blocked'));
      const changed = state.parcels.filter((entry) => entry.status === 'blocked' && !before.has(entry.id)).map((entry) => entry.id);
      if (changed.length) { const report = addCase(state, null, `Suspensão preventiva de ${person.name}: análise financeira separada.`, 'safety'); report.other = person.id; report.parcelIds = changed; }
    }
    event(state, 'account_review', reason, { userId: person.id, status: person.accountStatus }); return person;
  };
  operations.calendar = (state, input) => {
    admin(state); const date = validDate(input.date); if (date < nowDate(state)) fail('Cadastre uma data futura ou de hoje.');
    if (state.reservations.some((entry) => find(state.rides, entry.rideId, 'Trecho').date === date && entry.contractId)) fail('Já há mensal contratado nessa data. Registre Sem aula por trecho; o preço pago não muda.');
    const entry = { id: id(state, 'calendario'), date, title: required(input.title, 'o título', 'title'), known: true }; state.calendar.push(entry); event(state, 'calendar', 'Calendário conhecido atualizado antes de contratar', { date }); return entry;
  };
  operations.params = (state, input) => {
    admin(state); const params = { ...state.params };
    for (const [key,label,min,max] of [['fuel','Combustível',.1,100],['efficiency','Consumo',1,100],['wear','Desgaste',0,20],['referencePassengers','Passageiros de referência',1,8],['commission','Comissão',0,.95]]) params[key] = numeric(input[key], label, min, max, key);
    if (!Number.isInteger(params.referencePassengers)) fail('Use um número inteiro de passageiros.', 'referencePassengers');
    state.params = params; event(state, 'params', 'Parâmetros ilustrativos atualizados, sem recalcular parcelas contratadas'); return params;
  };
  operations.withdraw = (state, input) => {
    approved(state); const cents = Math.round(numeric(input.amount, 'Valor para saque', .01, wallet(state).available / 100, 'amount') * 100);
    const entry = { id: id(state, 'saque'), userId: state.actorId, cents, status: 'pending', cadence: user(state).settings.withdrawal, at: state.now }; state.withdrawals.push(entry); event(state, 'withdrawal', 'Saque fictício em processamento', { withdrawalId: entry.id, cents }); return entry;
  };
  operations.refund = (state, input) => {
    usable(state); const cents = Math.round(numeric(input.amount, 'Valor para devolução', .01, wallet(state).credit / 100, 'amount') * 100);
    const entry = { id: id(state, 'devolucao'), userId: state.actorId, cents, status: 'pending', at: state.now }; state.refunds.push(entry); event(state, 'refund', 'Devolução fictícia em processamento', { refundId: entry.id, cents }); return entry;
  };
  operations.reviewTransfer = (state, input) => {
    admin(state); const list = input.type === 'withdraw' ? state.withdrawals : state.refunds; const entry = find(list, input.transferId, 'Transferência');
    if (entry.status !== 'pending') return entry;
    entry.status = input.approve ? 'approved' : 'failed'; entry.decision = required(input.reason, 'a justificativa', 'reason'); event(state, 'transfer_review', entry.decision, { transferId: entry.id, outcome: entry.status }); return entry;
  };
  operations.readNotice = (state, input) => { const entry = find(state.notifications, input.noticeId, 'Aviso'); if (entry.userId !== state.actorId) fail('Aviso de outro perfil.'); entry.read = true; return entry; };
  operations.scenario = (state, input) => { state.ui.network = ['normal','offline','error'].includes(input.network) ? input.network : 'normal'; state.ui.paymentResult = ['approved','pending','failed'].includes(input.paymentResult) ? input.paymentResult : 'approved'; return state.ui; };
  operations.tick = (state, input) => {
    admin(state); const hours = numeric(input.hours, 'Horas', 1, 744, 'hours'); state.now = deadline(state, hours);
    for (const reservation of state.reservations.filter((entry) => entry.status === 'awaiting_confirmation' && entry.deadline && new Date(entry.deadline) <= new Date(state.now))) {
      const parcel = parcelFor(state, reservation.id); if (parcel.status !== 'awaiting') continue;
      settle(state, parcel, reservation.availability === 'no_class' ? 'retained' : 'released', 'Janela demonstrativa de 24h sem contestação'); reservation.status = 'completed'; reservation.finalResponse = 'timeout';
    }
    for (const contract of state.contracts) {
      if (contract.endDate >= nowDate(state)) continue;
      if (contract.status === 'cancelled_renewal') { contract.status = 'ended'; continue; }
      if (contract.status === 'active' && contract.renew) {
        contract.dueAt = `${addDays(contract.endDate,1)}T00:00:00`; const due = new Date(contract.dueAt); due.setHours(due.getHours()+72); contract.graceUntil = due.toISOString();
        try {
          const preview = monthlyPreview(state, { routineId: contract.routineId, startDate: nowDate(state), directions: contract.directions, weekdays: contract.weekdays }, contract.passengerId);
          contract.status = preview.conflicts.length ? 'renew_conflict' : 'grace'; contract.holdRideIds = preview.conflicts.length ? [] : preview.lines.map((line) => line.rideId);
        } catch (_) { contract.status = 'renew_conflict'; contract.holdRideIds = []; }
        notify(state, contract.passengerId, 'Período encerrado', 'Renove com pagamento. Carência demonstrativa de 72h, sem embarque novo sem pagamento.', 'contract', contract.id);
      }
      if (['grace','renew_conflict'].includes(contract.status) && new Date(contract.graceUntil) <= new Date(state.now)) {
        contract.status = 'expired'; contract.holdRideIds = [];
        for (const request of state.requests.filter((entry) => entry.previousContractId === contract.id && ['pending','accepted'].includes(entry.status))) { request.status = 'expired'; state.payments.filter((entry) => entry.requestId === request.id && entry.status !== 'approved').forEach((entry) => { entry.status = 'expired'; }); }
      }
    }
    event(state, 'clock', 'Relógio da demonstração avançado', { now: state.now }); return state.now;
  };
  function assertInvariants(state) {
    const ids = new Set();
    for (const list of [state.users,state.rides,state.routines,state.reservations,state.payments,state.parcels,state.contracts,state.requests,state.events]) for (const entry of list) { if (ids.has(entry.id)) fail('Identificador duplicado.'); ids.add(entry.id); }
    for (const ride of state.rides) if (occupied(state, ride.id) > ride.capacity) fail('A capacidade do trecho foi excedida.');
    for (const parcel of state.parcels) {
      if (!Number.isInteger(parcel.cents) || parcel.cents < 0) fail('Parcela inválida.');
      const payment = find(state.payments, parcel.paymentId, 'Pagamento'); if (payment.status !== 'approved') fail('Parcela sem pagamento aprovado.');
      if (parcel.status === 'released' && parcel.driverCents + parcel.commissionCents !== parcel.cents) fail('Repasse divergente.');
    }
    for (const payment of state.payments.filter((entry) => entry.status === 'approved')) if (state.parcels.filter((entry) => entry.paymentId === payment.id).reduce((sum,entry) => sum+entry.cents,0) !== payment.amountCents || payment.creditCents + payment.externalCents !== payment.amountCents) fail('Pagamento não conciliado com parcelas.');
    for (const person of state.users) { const balance = wallet(state, person.id); if (balance.credit < 0 || balance.available < 0) fail('Saldo negativo.'); }
    if (reconciliation(state).difference !== 0) fail('A conciliação do dinheiro fictício divergiu.');
    return true;
  }
  function act(state, name, input = {}) {
    if (!operations[name]) fail('Ação desconhecida.');
    const draft = clone(state); const result = operations[name](draft, input); assertInvariants(draft); Object.assign(state, draft); return clone(result);
  }
  return { createState, act, search, monthlyPreview: (state,input) => monthlyPreview(clone(state),input), wallet, reconciliation, calculatePrice, available, occupied, sameClass, blocked, user, money, localDate, addDays, monthEnd, nextMonth, nowDate, assertInvariants, clone };
});
