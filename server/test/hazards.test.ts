import test, { after, before } from 'node:test';
import assert from 'node:assert/strict';
import { WebSocket } from 'ws';
import { testServer, type Session, type TestServer } from './apiHelpers.js';
import { connected, refusal } from './socketHelpers.js';

let t: TestServer;
let a: Session;
let b: Session;
let c: Session;
let d: Session;
before(async () => {
  t = await testServer();
  [a, b, c, d] = [await t.signup('ha'), await t.signup('hb'), await t.signup('hc'), await t.signup('hd')];
});
after(() => t.close());

const here = { lat: -33.87, lng: 151.21 };
const box = '-34,151,-33,152';
const report = (s: Session, extra: object = {}) => t.req('POST', '/v1/hazards', { token: s.token, body: { kind: 'hazard', subkind: 'gravel', ...here, heading: 90, ...extra } });
const live = async (s: Session, bbox = box) => (await t.req('GET', `/v1/hazards?bbox=${bbox}`, { token: s.token })).body as any[];

test('create, list in a box, defaults to a 30 minute life', async () => {
  const r = await report(a);
  assert.equal(r.status, 201, JSON.stringify(r.body));
  assert.equal(r.body.kind, 'hazard');
  assert.equal(r.body.subkind, 'gravel');
  assert.equal(r.body.confirmations, 0);
  const life = new Date(r.body.expiresAt).getTime() - new Date(r.body.createdAt).getTime();
  assert.ok(Math.abs(life - 30 * 60_000) < 2000, `life ${life}`);
  assert.equal(r.body.reporterId, undefined, 'the reporter is not exposed');

  assert.ok((await live(b)).some((h) => h.id === r.body.id));
  assert.ok(!(await live(b, '10,10,11,11')).some((h) => h.id === r.body.id), 'outside the box');
  assert.equal((await t.req('GET', '/v1/hazards?bbox=-80,-170,80,170', { token: b.token })).status, 400, 'too big');
  assert.equal((await t.req('GET', '/v1/hazards?bbox=nonsense', { token: b.token })).status, 400);
  assert.equal((await t.req('GET', '/v1/hazards', { token: b.token })).status, 400);
  assert.equal((await t.req('GET', `/v1/hazards?bbox=${box}`)).status, 401);

  const bad = (body: object) => t.req('POST', '/v1/hazards', { token: a.token, body });
  assert.equal((await bad({ kind: 'ufo', ...here })).status, 400);
  assert.equal((await bad({ kind: 'police', lat: 100, lng: 0 })).status, 400);
  assert.equal((await bad({ kind: 'police', subkind: 'Bad Value!', ...here })).status, 400);
  assert.equal((await bad({ kind: 'police', ...here, heading: 400 })).status, 400);
});

test('expired hazards disappear and cannot be voted on', async () => {
  const h = (await report(a, { lat: -33.5, lng: 151.5 })).body;
  assert.ok((await live(b)).some((x) => x.id === h.id));
  await t.database.db.updateTable('hazard_reports').set({ expires_at: new Date(Date.now() - 1000) }).where('id', '=', h.id).execute();
  assert.ok(!(await live(b)).some((x) => x.id === h.id));
  assert.equal((await t.req('POST', `/v1/hazards/${h.id}/confirm`, { token: b.token })).status, 404);
  assert.equal((await t.req('POST', `/v1/hazards/${h.id}/ack`, { token: b.token })).status, 404);
});

test('confirm: once per user, renews the life, not for your own report', async () => {
  const h = (await report(a, { kind: 'police', subkind: '', lat: -33.6, lng: 151.6 })).body;
  // Make it nearly expired so the renewal is visible.
  const soon = new Date(Date.now() + 60_000);
  await t.database.db.updateTable('hazard_reports').set({ expires_at: soon }).where('id', '=', h.id).execute();
  const c1 = await t.req('POST', `/v1/hazards/${h.id}/confirm`, { token: b.token });
  assert.equal(c1.status, 200);
  assert.equal(c1.body.confirmations, 1);
  assert.equal(c1.body.myAction, 'confirm');
  assert.ok(new Date(c1.body.expiresAt).getTime() > Date.now() + 25 * 60_000, 'life renewed');
  assert.equal((await t.req('POST', `/v1/hazards/${h.id}/confirm`, { token: b.token })).body.confirmations, 1, 'repeat is a no-op');
  assert.equal((await t.req('POST', `/v1/hazards/${h.id}/confirm`, { token: a.token })).status, 409, 'reporter cannot confirm');
  assert.equal((await t.req('POST', `/v1/hazards/${h.id}/dismiss`, { token: b.token })).status, 409, 'cannot flip a vote');
  assert.equal((await t.req('POST', `/v1/hazards/${h.id}/confirm`, { token: c.token })).body.confirmations, 2);
  const mine = (await live(b, '-33.7,151.5,-33.5,151.7')).find((x) => x.id === h.id);
  assert.equal(mine.myAction, 'confirm');
  assert.equal((await t.req('POST', '/v1/hazards/00000000-0000-4000-8000-000000000000/confirm', { token: b.token })).status, 404);
});

test('dismiss: three dismissals remove a hazard; one user counts once; the reporter can retract', async () => {
  const h = (await report(a, { lat: -33.4, lng: 151.4 })).body;
  assert.equal((await t.req('POST', `/v1/hazards/${h.id}/dismiss`, { token: b.token })).body.dismissals, 1);
  assert.equal((await t.req('POST', `/v1/hazards/${h.id}/dismiss`, { token: b.token })).body.dismissals, 1, 'same user again');
  assert.ok((await live(c, '-33.5,151.3,-33.3,151.5')).some((x) => x.id === h.id), 'still there after one');
  assert.equal((await t.req('POST', `/v1/hazards/${h.id}/dismiss`, { token: c.token })).body.dismissals, 2);
  assert.ok((await live(c, '-33.5,151.3,-33.3,151.5')).some((x) => x.id === h.id), 'still there after two');
  assert.equal((await t.req('POST', `/v1/hazards/${h.id}/dismiss`, { token: d.token })).body.dismissals, 3);
  assert.ok(!(await live(c, '-33.5,151.3,-33.3,151.5')).some((x) => x.id === h.id), 'gone after three');
  assert.equal((await t.req('POST', `/v1/hazards/${h.id}/confirm`, { token: d.token })).status, 404);

  const mine = (await report(a, { lat: -33.45, lng: 151.45 })).body;
  assert.equal((await t.req('POST', `/v1/hazards/${mine.id}/dismiss`, { token: a.token })).status, 200);
  assert.ok(!(await live(b, '-33.5,151.3,-33.3,151.5')).some((x) => x.id === mine.id), 'retracted by the reporter');
});

test('ack is recorded once and changes nothing else', async () => {
  const h = (await report(a, { lat: -33.2, lng: 151.2 })).body;
  assert.equal((await t.req('POST', `/v1/hazards/${h.id}/ack`, { token: b.token })).status, 200);
  assert.equal((await t.req('POST', `/v1/hazards/${h.id}/ack`, { token: b.token })).status, 200);
  const acks = await t.database.db.selectFrom('hazard_acks').selectAll().where('hazard_id', '=', h.id).execute();
  assert.equal(acks.length, 1);
  assert.equal(acks[0].action, 'ack');
  const row = await t.database.db.selectFrom('hazard_reports').selectAll().where('id', '=', h.id).executeTakeFirstOrThrow();
  assert.equal(row.confirmations, 0);
  assert.equal(row.dismissals, 0);
  // an ack does not stop you voting later
  assert.equal((await t.req('POST', `/v1/hazards/${h.id}/confirm`, { token: b.token })).body.confirmations, 1);
});

test('hazards can be reported to moderators', async () => {
  const h = (await report(a, { lat: -33.1, lng: 151.1 })).body;
  assert.equal((await t.req('POST', '/v1/reports', { token: b.token, body: { targetType: 'hazard', targetId: h.id, reason: 'spam' } })).status, 201);
});

// ---- the socket ----------------------------------------------------------------------------------------------

const wsWithProtocol = (token: string) => new WebSocket(`${t.wsBase}/ws`, ['convoyage.v1', `bearer.${token}`]);
const wsWithQuery = (token: string) => new WebSocket(`${t.wsBase}/ws?access_token=${encodeURIComponent(token)}`);

test('ws: a JWT is accepted from the subprotocol or the query string, and the hello names the user', async () => {
  const viaProtocol = await connected(wsWithProtocol(a.token));
  assert.equal(viaProtocol.all[0].me, a.id);
  assert.equal(viaProtocol.ws.protocol, 'convoyage.v1');
  const viaQuery = await connected(wsWithQuery(b.token));
  assert.equal(viaQuery.all[0].me, b.id);
  viaProtocol.ws.close(); viaQuery.ws.close();
});

test('ws: no token, a bad token, a forged token and a dev login are all refused', async () => {
  assert.equal(await refusal(new WebSocket(`${t.wsBase}/ws`)), 401);
  assert.equal(await refusal(new WebSocket(`${t.wsBase}/ws?access_token=junk`)), 401);
  assert.equal(await refusal(new WebSocket(`${t.wsBase}/ws`, ['convoyage.v1', 'bearer.junk'])), 401);
  assert.equal(await refusal(new WebSocket(`${t.wsBase}/ws?dev_user=alice`)), 401, 'dev auth is off');
  const refresh = new WebSocket(`${t.wsBase}/ws?access_token=${encodeURIComponent(a.refresh)}`);
  assert.equal(await refusal(refresh), 401, 'a refresh token is not an access token');
});

test('ws: a deleted account can no longer connect', async () => {
  const doomed = await t.signup('doomed');
  await t.req('DELETE', '/v1/me', { token: doomed.token });
  assert.equal(await refusal(wsWithQuery(doomed.token)), 401);
});

test('ws: a hazard reported by a convoy member reaches every member of the room, nobody else', async () => {
  const wa = await connected(wsWithProtocol(a.token));
  const wb = await connected(wsWithProtocol(b.token));
  const wc = await connected(wsWithProtocol(c.token)); // connected but not in the room
  wa.send({ t: 'convoy.create', name: 'Run' });
  const room = (await wa.next((m) => m.t === 'room')).room;
  wb.send({ t: 'convoy.join', code: room.code });
  await wb.next((m) => m.t === 'room');

  const r = await report(a, { kind: 'hazard', subkind: 'gravel', lat: -33.3, lng: 151.3 });
  assert.equal(r.status, 201);
  for (const member of [wa, wb]) {
    const m = await member.next((x) => x.t === 'hazard');
    assert.equal(m.room, room.code);
    assert.equal(m.hazard.id, r.body.id);
    assert.equal(m.hazard.subkind, 'gravel');
    assert.deepEqual(Object.keys(m.hazard).sort(), ['createdAt', 'expiresAt', 'heading', 'id', 'kind', 'lat', 'lng', 'subkind']);
  }
  assert.equal(await wc.quietly((m) => m.t === 'hazard'), 0);

  // Outside any convoy: stored, not broadcast.
  const lone = await report(c, { lat: -33.31, lng: 151.31 });
  assert.equal(lone.status, 201);
  assert.equal(await wa.quietly((m) => m.t === 'hazard'), 0);
  const saved = await t.database.db.selectFrom('hazard_reports').select('convoy_code').where('id', '=', r.body.id).executeTakeFirstOrThrow();
  assert.equal(saved.convoy_code, room.code);
  wa.ws.close(); wb.ws.close(); wc.ws.close();
});

test('ws: with CONVOYAGE_DEV_AUTH the dev login works next to JWTs', async () => {
  const dev = await testServer({ database: t.database, config: { devAuth: true } });
  const devSocket = await connected(new WebSocket(`${dev.wsBase}/ws?dev_user=alice&name=Alice`));
  assert.equal(devSocket.all[0].me, 'alice');
  const s = await dev.signup('jwt-too');
  const jwtSocket = await connected(new WebSocket(`${dev.wsBase}/ws?access_token=${encodeURIComponent(s.token)}`));
  assert.equal(jwtSocket.all[0].me, s.id);
  devSocket.ws.close(); jwtSocket.ws.close();
  await dev.close();
});

test('configuration refuses insecure production settings', async () => {
  const { loadConfig } = await import('../src/config.js');
  const prod = { NODE_ENV: 'production', JWT_SECRET: 'p'.repeat(40), DATABASE_URL: 'postgres://x/y', SMTP_URL: 'smtp://mail.example.com' };
  assert.doesNotThrow(() => loadConfig(prod));
  assert.throws(() => loadConfig({ ...prod, SMTP_URL: '' }), /SMTP_URL/);
  assert.throws(() => loadConfig({ ...prod, MAIL_DEV: '1' }), /MAIL_DEV/);
  assert.throws(() => loadConfig({ ...prod, CONVOYAGE_DEV_AUTH: '1' }), /DEV_AUTH/);
  assert.throws(() => loadConfig({ ...prod, JWT_SECRET: '' }), /JWT_SECRET/);
  assert.throws(() => loadConfig({ ...prod, JWT_SECRET: 'short' }), /32/);
  assert.throws(() => loadConfig({ ...prod, DATABASE_URL: '' }), /DATABASE_URL/);
  assert.ok(loadConfig({}).warnings.length >= 1, 'development fallbacks are announced');
});
