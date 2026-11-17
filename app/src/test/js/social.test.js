// Unit tests for app/src/main/assets/social.js, against a fake server. Run with:  node --test app/src/test/js
'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');

const Social = require(path.join(__dirname, '../../main/assets/social.js'));

function memoryStorage() {
  const m = new Map();
  return { getItem: (k) => (m.has(k) ? m.get(k) : null), setItem: (k, v) => m.set(k, String(v)), removeItem: (k) => m.delete(k), m };
}
/** A fake fetch: `routes` maps "METHOD path" (path without the project URL) to (body, init) => [status, json]. */
function fakeServer(routes) {
  const calls = [];
  const fetch = async (url, init) => {
    const p = url.replace(Social.PROJECT.url, '');
    const body = init && init.body ? JSON.parse(init.body) : null;
    calls.push({ path: p, body, headers: init.headers });
    const r = routes[`${init.method} ${p}`];
    if (!r) return { ok: false, status: 404, text: async () => JSON.stringify({ message: 'no route ' + p }) };
    if (r === 'offline') throw new TypeError('Failed to fetch');
    const [status, json] = await r(body, init);
    return { ok: status < 300, status, text: async () => (json === undefined ? '' : JSON.stringify(json)) };
  };
  return { fetch, calls };
}
const authBody = (n) => ({ access_token: 'at' + n, refresh_token: 'rt' + n, expires_in: 3600, user: { id: 'u1', email: 'a@b.co' } });

test('sign in: code sent, then verified; the session is kept', async () => {
  const store = memoryStorage();
  const srv = fakeServer({
    'POST /auth/v1/otp': () => [200, {}],
    'POST /auth/v1/verify': (b) => [200, authBody(1)]
  });
  const c = Social.create({ fetch: srv.fetch, storage: store });
  assert.equal(c.signedIn(), false);
  assert.equal(await c.sendCode('  A@B.co '), 'a@b.co');
  assert.deepEqual(srv.calls[0].body, { email: 'a@b.co', create_user: true });
  assert.equal(srv.calls[0].headers.apikey, Social.PROJECT.key);
  assert.equal(srv.calls[0].headers.Authorization, undefined);
  let changes = [];
  c.onChange((v) => changes.push(v));
  await c.verifyCode('a@b.co', '123 456');
  assert.deepEqual(srv.calls[1].body, { type: 'email', email: 'a@b.co', token: '123456' });
  assert.equal(c.signedIn(), true);
  assert.equal(c.email(), 'a@b.co');
  assert.deepEqual(changes, [true]);
  // Another client on the same storage (the app opened again) is signed in.
  assert.equal(Social.create({ fetch: srv.fetch, storage: store }).signedIn(), true);
});

test('bad emails and codes are caught before sending', async () => {
  const c = Social.create({ fetch: fakeServer({}).fetch, storage: memoryStorage() });
  await assert.rejects(c.sendCode('nope'), { code: 'bad_email' });
  await assert.rejects(c.verifyCode('a@b.co', '12'), { code: 'bad_code' });
});

test('a new account\'s first code: falls back to a sign-up code', async () => {
  const srv = fakeServer({
    'POST /auth/v1/verify': (b) => (b.type === 'signup' ? [200, authBody(1)] : [403, { error_code: 'otp_expired', msg: 'Token has expired or is invalid' }])
  });
  const c = Social.create({ fetch: srv.fetch, storage: memoryStorage() });
  await c.verifyCode('a@b.co', '123456');
  assert.deepEqual(srv.calls.map((x) => x.body.type), ['email', 'signup']);
  assert.equal(c.signedIn(), true);
});

test('a wrong code: the first error is the one shown', async () => {
  const srv = fakeServer({ 'POST /auth/v1/verify': () => [403, { error_code: 'otp_expired', msg: 'Token has expired or is invalid' }] });
  const c = Social.create({ fetch: srv.fetch, storage: memoryStorage() });
  await assert.rejects(c.verifyCode('a@b.co', '123456'), { code: 'otp_expired', message: /wrong or has expired/ });
  assert.equal(c.signedIn(), false);
});

test('rate limits and being offline read as such', async () => {
  const c1 = Social.create({ fetch: fakeServer({ 'POST /auth/v1/otp': () => [429, { msg: 'email rate limit exceeded' }] }).fetch, storage: memoryStorage() });
  await assert.rejects(c1.sendCode('a@b.co'), { code: 'rate_limited' });
  const c2 = Social.create({ fetch: fakeServer({ 'POST /auth/v1/otp': 'offline' }).fetch, storage: memoryStorage() });
  await assert.rejects(c2.sendCode('a@b.co'), { code: 'offline' });
});

async function signedInClient(routes, opts) {
  const srv = fakeServer(Object.assign({ 'POST /auth/v1/verify': () => [200, authBody(1)] }, routes));
  const c = Social.create(Object.assign({ fetch: srv.fetch, storage: memoryStorage() }, opts));
  await c.verifyCode('a@b.co', '123456');
  srv.calls.length = 0;
  return { c, srv };
}

test('database calls send the access token and the arguments', async () => {
  const { c, srv } = await signedInClient({
    'POST /rest/v1/rpc/my_profile': () => [200, [{ display_name: 'Player', friend_code: 'VT98VDJX' }]],
    'POST /rest/v1/rpc/send_friend_request': () => [200, 'sent'],
    'POST /rest/v1/rpc/list_friends': () => [200, [{ id: 'u2', display_name: 'Sam', status: 'accepted', since: '2026-11-01' }]],
    'POST /rest/v1/rpc/respond_friend_request': () => [204],
    'POST /rest/v1/rpc/set_display_name': (b) => [200, b.new_name.trim()]
  });
  assert.deepEqual(await c.profile(), { display_name: 'Player', friend_code: 'VT98VDJX' });
  assert.equal(srv.calls[0].headers.Authorization, 'Bearer at1');
  assert.equal(await c.addFriend('vt98-vdjx'), 'sent');
  assert.deepEqual(srv.calls[1].body, { code: 'vt98-vdjx' });
  assert.equal((await c.friends())[0].display_name, 'Sam');
  assert.equal(await c.respond('u2', 1), null);
  assert.deepEqual(srv.calls[3].body, { other: 'u2', accept: true });
  assert.equal(await c.setName(' Sam '), 'Sam');
});

test('an access token near its end is renewed once, even for calls at the same time', async () => {
  let t = 1_000_000_000_000;
  let refreshes = 0;
  const { c, srv } = await signedInClient({
    'POST /auth/v1/token?grant_type=refresh_token': async (b) => { refreshes++; assert.equal(b.refresh_token, 'rt1'); return [200, authBody(2)]; },
    'POST /rest/v1/rpc/list_friends': () => [200, []]
  }, { now: () => t });
  t += 3550 * 1000; // 50 s left
  await Promise.all([c.friends(), c.friends(), c.friends()]);
  assert.equal(refreshes, 1);
  assert.deepEqual(srv.calls.filter((x) => x.path.startsWith('/rest')).map((x) => x.headers.Authorization), ['Bearer at2', 'Bearer at2', 'Bearer at2']);
  assert.equal(c.signedIn(), true);
});

test('a refresh token that no longer works signs out; being offline doesn\'t', async () => {
  let t = 1_000_000_000_000;
  let mode = 'offline';
  const { c } = await signedInClient({
    'POST /auth/v1/token?grant_type=refresh_token': async () => { if (mode === 'offline') throw new TypeError('x'); return [400, { error_code: 'refresh_token_not_found', msg: 'Invalid Refresh Token' }]; }
  }, { now: () => t });
  t += 4000 * 1000;
  await assert.rejects(c.friends(), { code: 'offline' });
  assert.equal(c.signedIn(), true);
  mode = 'gone';
  await assert.rejects(c.friends(), { code: 'signed_out' });
  assert.equal(c.signedIn(), false);
});

test('a 401 from the database signs out', async () => {
  const { c } = await signedInClient({ 'POST /rest/v1/rpc/list_friends': () => [401, { message: 'JWT expired' }] });
  await assert.rejects(c.friends(), { code: 'signed_out' });
  assert.equal(c.signedIn(), false);
});

test('the server without setup.sql run reads as not set up', async () => {
  const { c } = await signedInClient({ 'POST /rest/v1/rpc/my_profile': () => [404, { code: 'PGRST202', message: 'Could not find the function' }] });
  await assert.rejects(c.profile(), { code: 'not_set_up' });
  assert.equal(c.signedIn(), true);
});

test('sign out and delete account clear the session', async () => {
  const { c, srv } = await signedInClient({ 'POST /auth/v1/logout': () => [204], 'POST /rest/v1/rpc/delete_my_account': () => [204] });
  await c.signOut();
  assert.equal(c.signedIn(), false);
  assert.equal(srv.calls[0].headers.Authorization, 'Bearer at1');
  await assert.rejects(c.friends(), { code: 'signed_out' });
  const s2 = await signedInClient({ 'POST /rest/v1/rpc/delete_my_account': () => [204] });
  await s2.c.deleteAccount();
  assert.equal(s2.c.signedIn(), false);
});

test('a broken stored session is ignored', () => {
  const store = memoryStorage();
  store.setItem(Social.SESSION_KEY, '{nope');
  assert.equal(Social.create({ fetch: fakeServer({}).fetch, storage: store }).signedIn(), false);
});

test('friend codes: typed forms normalize, others are rejected', () => {
  assert.equal(Social.normalizeCode(' vt98-vdjx '), 'VT98VDJX');
  assert.equal(Social.normalizeCode('VT98VDJ0'), '');
  assert.equal(Social.normalizeCode('VT98'), '');
  assert.equal(Social.formatCode('VT98VDJX'), 'VT98-VDJX');
});
