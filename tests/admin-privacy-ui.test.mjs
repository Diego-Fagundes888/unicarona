// Client behavior with synthetic data; browser inspection is a separate requirement.
import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import { readFileSync } from 'node:fs';
const source = name => readFileSync(new URL('../dist/' + name, import.meta.url), 'utf8');
const plain = value => JSON.parse(JSON.stringify(value));
const id = '11111111-1111-4111-8111-111111111111', owner = '22222222-2222-4222-8222-222222222222';
const item = () => ({ id, userId: owner, resourceId: owner, category: 'profile', status: 'active', revision: 3, reviewAt: '2026-11-10T12:00:00Z', expiresAt: '2026-12-10T12:00:00Z' });
const deferred = () => { let resolve;const promise = new Promise(r => { resolve = r; });return { promise, resolve }; };
function setup(request, admin = true) {
  const calls = [], navigation = [], messages = [], panes = [], downloads = [], revoked = [], timers = new Map();let epoch = 0, sequence = 0;
  class LocalURL extends URL { static createObjectURL(blob) { downloads.push(blob);return 'blob:test-' + downloads.length; }static revokeObjectURL(url) { revoked.push(url); } }
  const context = vm.createContext({ window: {}, URL: LocalURL, URLSearchParams, Blob, document: { body: { append() {} }, createElement: () => ({ click() {}, remove() {} }) }, setTimeout: callback => { timers.set(++sequence, callback);return sequence; }, clearTimeout: key => timers.delete(key) });
  vm.runInContext(source('ui.js'), context);vm.runInContext(source('admin-privacy.js'), context);
  const flow = context.window.UniCaronaAdminPrivacy.create({ request: async (path, options) => { calls.push([path, options ? plain(options) : null]);return request(path, options); }, navigate: (...args) => navigation.push(args), tell: message => messages.push(message), isAdmin: () => admin, reveal: html => panes.push(html), navigationVersion: () => epoch });
  return { flow, calls, navigation, messages, panes, downloads, revoked, timers, context, leave: () => epoch++ };
}

test('non-administrators cannot read or execute the module even with a direct route', async () => {
  const ctx = setup(async () => {}, false);await assert.rejects(ctx.flow.render('admin_privacy_holds'), /administrativo/);await assert.rejects(ctx.flow.perform('aPrivacyOpenHold', { id, password: 'synthetic' }), /administrativo/);assert.equal(ctx.calls.length, 0);
});
test('request queue escapes identity and keeps filtering and pagination as reads', async () => {
  const ctx = setup(async () => ({ items: [{ name: '<img onerror=x>', email: '<svg>@example.test', userId: owner, request: { id, kind: 'access', status: 'received' } }], nextOffset: 30 }));
  const html = await ctx.flow.render('admin_privacy_requests');assert.match(html, /&lt;img/);assert.ok(!html.includes('<svg>'));assert.match(ctx.calls[0][0], /status=received/);
  await ctx.flow.perform('aPrivacyRequestPage', { offset: 30 });await ctx.flow.render('admin_privacy_requests');assert.match(ctx.calls[1][0], /offset=30/);assert.ok(ctx.calls.every(([, options]) => options === null));
  await assert.rejects(ctx.flow.perform('aPrivacyRequestPage', { offset: -1 }), /inválida/);
});
test('request detail separates replies and does not claim a response performs erasure', async () => {
  const ctx = setup(async () => ({ request: { name: 'Synthetic', email: 'test@example.test', userId: owner, request: { id, kind: 'erasure', status: 'in_review' } }, details: '<script>private</script>', responses: [{ message: '<img>sent response' }] }));
  const html = await ctx.flow.render('admin_privacy_request', id);assert.match(html, /não executa correção ou exclusão/);assert.match(html, /&lt;script&gt;/);assert.match(html, /&lt;img&gt;/);assert.ok(!html.includes(' checked'));assert.match(html, /<select required /);
  await assert.rejects(ctx.flow.perform('aPrivacyRespond', { id, status: 'completed', actionPerformed: false }), /providência/);assert.equal(ctx.calls.length, 1);
  await ctx.flow.perform('aPrivacyRespond', { id, expectedStatus: 'in_review', status: 'completed', message: 'Synthetic completed measure', actionPerformed: true, userId: 'unrelated' });
  assert.deepEqual(ctx.calls[1][1].body, { expectedStatus: 'in_review', status: 'completed', message: 'Synthetic completed measure', actionPerformed: true });
});
test('closed protocols have no response form and malformed identifiers make no API call', async () => {
  const ctx = setup(async () => ({ request: { userId: owner, request: { id, status: 'completed' } }, details: 'Done', responses: [] }));
  assert.ok(!(await ctx.flow.render('admin_privacy_request', id)).includes('data-c-form="aPrivacyRespond"'));
  await assert.rejects(ctx.flow.render('admin_privacy_request', '../other'), /inválido/);assert.equal(ctx.calls.length, 1);
});
test('account actions distinguish a ban, an open platform closure and the irreversible stage', async () => {
  let account = { status: 'banned', closure: { id, status: 'pending_obligations', initiatedBy: 'platform' } };
  const ctx = setup(async () => ({ subject: { id: owner, name: 'Synthetic owner', email: 'owner@example.test' }, account, decisions: [{ notice: { action: 'ban', message: '<img>public' }, internalReason: '<script>internal</script>' }] }));
  const html = await ctx.flow.render('admin_privacy_account', owner);assert.match(html, /Revisar banimento/);assert.match(html, /Cancelar encerramento da plataforma/);assert.match(html, /&lt;script&gt;internal/);assert.ok(!html.includes('data-ref="' + owner + ':ban"'));
  account = { ...account, closure: { ...account.closure, status: 'erasing' } };const irreversible = await ctx.flow.render('admin_privacy_account', owner);assert.ok(!irreversible.includes('Cancelar encerramento da plataforma'));
});
test('account decision carries its expected state and sends neither acknowledgement nor unrelated fields', async () => {
  const ctx = setup(async () => ({}));await assert.rejects(ctx.flow.perform('aPrivacyDecide', { acknowledged: false }), /confirme/);assert.equal(ctx.calls.length, 0);
  await ctx.flow.perform('aPrivacyDecide', { id: owner, password: 'synthetic', action: 'ban', expectedStatus: 'suspended', reasonCode: 'security', publicReason: 'Synthetic public message', reason: 'Synthetic evidence assessment', restrictionDays: '14', acknowledged: true, marketing: true });
  assert.deepEqual(ctx.calls[0][1].body, { password: 'synthetic', action: 'ban', expectedStatus: 'suspended', reasonCode: 'security', publicReason: 'Synthetic public message', reason: 'Synthetic evidence assessment', restrictionDays: 14 });
  await ctx.flow.perform('aPrivacyDecide', { id: owner, password: 'synthetic', action: 'lift_ban', expectedStatus: 'banned', acknowledged: true, restrictionDays: '365' });assert.ok(!Object.hasOwn(ctx.calls[1][1].body, 'restrictionDays'));
});
test('hold evidence is fetched only by password and escaped before an explicit reveal', async () => {
  const ctx = setup(async () => ({ hold: item(), reason: '<script>reason</script>', caseReference: '<img>case', history: [{ revision: 1, reason: '<svg>history', caseReference: 'Synthetic' }] }));
  const html = await ctx.flow.render('admin_privacy_hold', id);assert.equal(ctx.calls.length, 0);assert.match(html, /Confirme sua senha/);
  await ctx.flow.perform('aPrivacyOpenHold', { id, password: 'synthetic', userId: 'other' });assert.equal(ctx.calls[0][0], '/admin/privacy/holds/' + id + '/evidence');assert.deepEqual(ctx.calls[0][1].body, { password: 'synthetic' });
  assert.equal(ctx.panes[0], '');assert.match(ctx.panes[1], /&lt;script&gt;reason/);assert.match(ctx.panes[1], /&lt;svg&gt;history/);assert.ok(!ctx.panes[1].includes('<img>'));
  await ctx.flow.perform('aPrivacyForgetHold');assert.equal(ctx.panes.at(-1), '');
});
test('failed reauthentication clears a previously revealed pane; mismatched evidence is never revealed', async () => {
  const failed = setup(async () => { throw Error('wrong password'); });await assert.rejects(failed.flow.perform('aPrivacyOpenHold', { id, password: 'wrong' }), /wrong/);assert.deepEqual(failed.panes, ['']);
  const mismatch = setup(async () => ({ hold: { ...item(), id: owner } }));await assert.rejects(mismatch.flow.perform('aPrivacyOpenHold', { id, password: 'synthetic' }), /não corresponde/);assert.deepEqual(mismatch.panes, ['']);
});
test('review without a known revision requires consulting evidence rather than inventing version 1', async () => {
  const ctx = setup(async () => {});const html = await ctx.flow.render('admin_privacy_hold_review', id + ':review');assert.match(html, /versão desconhecida/);assert.ok(!html.includes('data-c-form="aPrivacyReviewHold"'));assert.equal(ctx.calls.length, 0);
});
test('hold creation requires acknowledgement, IDs and explicit deadlines in UTC minus three', async () => {
  const ctx = setup(async () => item());await assert.rejects(ctx.flow.perform('aPrivacyCreateHold', { acknowledged: false }), /confirme/);assert.equal(ctx.calls.length, 0);
  await ctx.flow.perform('aPrivacyCreateHold', { password: 'synthetic', userId: owner, resourceId: owner, category: 'profile', legalBasis: 'regular_exercise_of_rights', reason: 'Synthetic specific case', caseReference: 'Synthetic-1', reviewAt: '2026-11-10T09:00', expiresAt: '2026-12-10T09:00', acknowledged: true, expectedRevision: 7 });
  const body = ctx.calls[0][1].body;assert.equal(body.reviewAt, '2026-11-10T12:00:00.000Z');assert.equal(body.expiresAt, '2026-12-10T12:00:00.000Z');assert.ok(!Object.hasOwn(body, 'expectedRevision'));assert.ok(!Object.hasOwn(body, 'acknowledged'));
  const before = ctx.calls.length;await assert.rejects(ctx.flow.perform('aPrivacyCreateHold', { acknowledged: true, userId: '../x', resourceId: owner }), /inválido/);assert.equal(ctx.calls.length, before);
});
test('datetime conversion rejects normalized impossible dates and incomplete times', () => {
  const convert = setup(async () => {}).context.window.UniCaronaAdminPrivacy.toInstant;
  for (const value of ['2026-02-30T09:00', '2026-11-10', '2026-13-01T00:00', '2026-01-01T25:00', '']) assert.throws(() => convert(value));
  assert.equal(convert('2028-02-29T09:05'), '2028-02-29T12:05:00.000Z');
});
test('releasing a hold omits extension dates and carries the exact revision the operator reviewed', async () => {
  const ctx = setup(async () => item());await ctx.flow.perform('aPrivacyReviewHold', { id, action: 'release', expectedRevision: 3, password: 'synthetic', reason: 'Synthetic release evidence', caseReference: 'Synthetic-1', acknowledged: true, reviewAt: '2026-11-01T09:00', expiresAt: '2027-11-01T09:00' });
  assert.deepEqual(ctx.calls[0][1].body, { password: 'synthetic', reason: 'Synthetic release evidence', caseReference: 'Synthetic-1', action: 'release', expectedRevision: 3 });
});
test('leaving the view prevents late private evidence, navigation and downloads', async () => {
  const evidenceWait = deferred(), ctx = setup(() => evidenceWait.promise);const pending = ctx.flow.perform('aPrivacyOpenHold', { id, password: 'synthetic' });ctx.leave();evidenceWait.resolve({ hold: item(), reason: 'Private synthetic case', history: [] });await pending;assert.deepEqual(ctx.panes, ['']);assert.equal(ctx.navigation.length, 0);
  const downloadWait = deferred(), download = setup(() => downloadWait.promise);const file = download.flow.perform('aPrivacyDownloadHold', { id, password: 'synthetic' });download.leave();downloadWait.resolve(new Blob(['{}'], { type: 'application/json' }));await file;assert.equal(download.downloads.length, 0);
});
test('private evidence downloads reject HTML and revoke their temporary Blob URLs', async () => {
  const html = setup(async () => new Blob(['<script>evil</script>'], { type: 'text/html' }));await assert.rejects(html.flow.perform('aPrivacyDownloadHold', { id, password: 'synthetic' }), /não corresponde/);assert.equal(html.downloads.length, 0);
  const ctx = setup(async () => new Blob(['{"synthetic":true}'], { type: 'application/json' }));await ctx.flow.perform('aPrivacyDownloadHold', { id, password: 'synthetic' });assert.equal(ctx.downloads.length, 1);ctx.flow.destroy();assert.deepEqual(ctx.revoked, ['blob:test-1']);assert.equal(ctx.timers.size, 0);await assert.rejects(ctx.flow.render('admin_privacy'), /administrativo/);
});
test('a sanction page requires the verified subject and escapes its identification', async () => {
  let subject = { id: owner, name: '<img>Synthetic', email: '<svg>@example.test' };
  const ctx = setup(async () => ({ subject, account: { status: 'active' }, decisions: [] }));
  const html = await ctx.flow.render('admin_privacy_decision', owner + ':ban');assert.match(html, /&lt;img&gt;Synthetic/);assert.match(html, /&lt;svg&gt;@example.test/);
  subject = { ...subject, id };await assert.rejects(ctx.flow.render('admin_privacy_decision', owner + ':ban'), /conferir o titular/);
});
test('profile holds use the selected owner automatically and clear that value when switching resource type', () => {
  const ctx = setup(async () => {}), resource = { value: '', dataset: {} }, fields = { '[name="category"]': { value: 'profile' }, '[name="userId"]': { value: owner }, '[name="resourceId"]': resource, '[data-a-resource-help]': { textContent: '' } };
  const form = { querySelector: selector => fields[selector] }, target = { name: 'category', closest: () => form };
  ctx.flow.change({ target });assert.equal(resource.value, owner);assert.equal(resource.readOnly, true);
  fields['[name="category"]'].value = 'files';ctx.flow.change({ target });assert.equal(resource.value, '');assert.equal(resource.readOnly, false);
  fields['[name="category"]'].value = 'profile';ctx.flow.change({ target });assert.equal(resource.value, owner);
  fields['[name="category"]'].value = 'driver_application';ctx.flow.change({ target });assert.equal(resource.value, '');assert.equal(resource.readOnly, false);assert.match(fields['[data-a-resource-help]'].textContent, /identificador da solicitação de motorista/);assert.match(fields['[data-a-resource-help]'].textContent, /não recupera dados já eliminados/);
  fields['[name="category"]'].value = 'security_audit';ctx.flow.change({ target });assert.equal(resource.value, '');assert.equal(resource.readOnly, false);assert.match(fields['[data-a-resource-help]'].textContent, /evento de segurança deste titular/);assert.match(fields['[data-a-resource-help]'].textContent, /seis meses/);assert.match(fields['[data-a-resource-help]'].textContent, /cópia privada/);
});
test('all-status queues omit status in the strict HTTP contract, including account and post-create navigation', async () => {
  const ctx = setup(async (path, options) => options ? item() : { items: path.startsWith('/admin/privacy/holds?') ? [item()] : [] });
  await ctx.flow.render('admin_privacy_holds');assert.equal(new URL(ctx.calls.at(-1)[0], 'http://test').searchParams.get('status'), 'review_due');
  await ctx.flow.perform('aPrivacyRequestFilter', { status: '' });await ctx.flow.render('admin_privacy_requests');
  assert.equal(new URL(ctx.calls.at(-1)[0], 'http://test').searchParams.has('status'), false);
  await ctx.flow.perform('aPrivacyHoldFilter', { status: '', userId: '' });await ctx.flow.render('admin_privacy_holds');
  assert.equal(new URL(ctx.calls.at(-1)[0], 'http://test').searchParams.has('status'), false);
  await ctx.flow.render('admin_privacy_holds', owner);
  const accountQuery = new URL(ctx.calls.at(-1)[0], 'http://test').searchParams;assert.equal(accountQuery.has('status'), false);assert.equal(accountQuery.get('userId'), owner);
  await ctx.flow.perform('aPrivacyCreateHold', { password: 'synthetic', userId: owner, resourceId: owner, category: 'profile', legalBasis: 'regular_exercise_of_rights', reason: 'Synthetic specific case', caseReference: 'Synthetic-query-1', reviewAt: '2026-11-10T09:00', expiresAt: '2026-12-10T09:00', acknowledged: true });
  const html = await ctx.flow.render('admin_privacy_holds');const createdQuery = new URL(ctx.calls.at(-1)[0], 'http://test').searchParams;
  assert.equal(createdQuery.has('status'), false);assert.equal(createdQuery.get('userId'), owner);assert.match(html, /Consultar evidência e revisar/);
});
