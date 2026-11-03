import test, { after } from 'node:test';
import assert from 'node:assert/strict';
import { WebSocket } from 'ws';
import { buildApp, VERSION } from '../src/app.js';
import { devAuthenticator, denyAuthenticator } from '../src/auth.js';
import { MemoryCruiseSource, noRestStops } from '../src/stubs.js';
import { connected, refusal } from './socketHelpers.js';

const cruises = new MemoryCruiseSource();
cruises.add('ev1', { name: 'Dawn cruise', organiser: 'alice', meet: { lat: -33.8, lng: 151.2, label: 'Car park' } });
const app = await buildApp({ auth: devAuthenticator, cruises, restStops: noRestStops, heartbeatMs: 200 });
await app.listen({ port: 0, host: '127.0.0.1' });
const base = `127.0.0.1:${(app.server.address() as { port: number }).port}`;
after(() => app.close());

const socket = (user: string, name = user) => new WebSocket(`ws://${base}/ws?dev_user=${user}&name=${name}`);

test('GET /health', async () => {
  const res = await fetch(`http://${base}/health`);
  assert.deepEqual(await res.json(), { ok: true, version: VERSION });
});

test('the socket refuses a caller the authenticator does not accept', async () => {
  assert.equal(await refusal(new WebSocket(`ws://${base}/ws`)), 401);
  const closed = await buildApp({ auth: denyAuthenticator, cruises, restStops: noRestStops });
  await closed.listen({ port: 0, host: '127.0.0.1' });
  const port = (closed.server.address() as { port: number }).port;
  assert.equal(await refusal(new WebSocket(`ws://127.0.0.1:${port}/ws?dev_user=alice`)), 401);
  await closed.close();
});

test('create, join, position relay, chat, ping and removal', async () => {
  const a = await connected(socket('a1', 'Alice'));
  const b = await connected(socket('b1', 'Bob'));
  assert.equal(a.all[0].me, 'a1');
  a.send({ t: 'convoy.create', name: 'Ride' });
  const room = (await a.next((m) => m.t === 'room')).room;
  b.send({ t: 'convoy.join', code: room.code });
  await b.next((m) => m.t === 'room');
  a.send({ t: 'pos', lat: -33, lng: 151, heading: 90, speed: 10 });
  const pos = await b.next((m) => m.t === 'pos');
  assert.equal(pos.id, 'a1');
  assert.equal(await a.quietly((m) => m.t === 'pos'), 0, 'never echoed to the sender');
  a.send({ t: 'convoy.message', room: room.code, text: 'hi', clientId: 'c1', username: 'Spoof' });
  assert.equal((await b.next((m) => m.t === 'convoy.message')).message.username, 'Alice');
  a.send({ t: 'ping' });
  await a.next((m) => m.t === 'pong');
  a.send({ t: 'convoy.remove', room: room.code, id: 'b1' });
  assert.equal((await b.next((m) => m.t === 'left')).removed, true);
  a.ws.close(); b.ws.close();
});

test('bad messages get an error and oversize frames close the socket', async () => {
  const a = await connected(socket('big1'));
  a.send({ t: 'nope' });
  assert.equal((await a.next((m) => m.t === 'error')).error, 'Unknown message');
  a.ws.send(JSON.stringify({ t: 'convoy.create', name: 'x'.repeat(5000) }));
  assert.equal(await a.closed, 1009);
});

test('a burst past the rate limit is dropped', async () => {
  const a = await connected(socket('rate1'));
  for (let i = 0; i < 30; i++) a.send({ t: 'ping' });
  await new Promise((r) => setTimeout(r, 300));
  const pongs = a.all.filter((m) => m.t === 'pong').length;
  assert.ok(pongs >= 10 && pongs < 30, `got ${pongs}`);
  a.ws.close();
});

test('convoy.cruise: organiser starts at the meet point, others join, strangers cannot start', async () => {
  const guest = await connected(socket('guest1'));
  guest.send({ t: 'convoy.cruise', eventId: 'ev1' });
  assert.match((await guest.next((m) => m.t === 'error')).error, /not started/);
  guest.send({ t: 'convoy.cruise', eventId: 'missing' });
  assert.match((await guest.next((m) => m.t === 'error')).error, /No such cruise/);
  const org = await connected(socket('alice', 'Alice'));
  org.send({ t: 'convoy.cruise', eventId: 'ev1' });
  const room = (await org.next((m) => m.t === 'room')).room;
  assert.equal(room.eventId, 'ev1');
  assert.deepEqual(room.destination, { lat: -33.8, lng: 151.2, label: 'Car park' });
  guest.send({ t: 'convoy.cruise', eventId: 'ev1' });
  assert.equal((await guest.next((m) => m.t === 'room')).room.code, room.code);
  org.ws.close(); guest.ws.close();
});

test('rest-stop search with the stub finds nothing; the cooldown still applies', async () => {
  const a = await connected(socket('rest1'));
  a.send({ t: 'convoy.create', name: 'Rest', automaticRegroup: { enabled: false, separationM: 5000 } });
  const room = (await a.next((m) => m.t === 'room')).room;
  a.send({ t: 'convoy.route', room: room.code, route: { name: 'R', stops: [[151, -33], [152, -33]] } });
  await a.next((m) => m.t === 'room');
  a.send({ t: 'convoy.rest', room: room.code, action: 'find' });
  assert.match((await a.next((m) => m.t === 'error')).error, /No suitable rest stop/);
  a.send({ t: 'convoy.rest', room: room.code, action: 'find' });
  assert.match((await a.next((m) => m.t === 'error')).error, /just requested/);
  a.ws.close();
});
