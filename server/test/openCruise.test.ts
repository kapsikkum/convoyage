import test, { after, before } from 'node:test';
import assert from 'node:assert/strict';
import { WebSocket } from 'ws';
import { OpenCruise, OPEN_CRUISE_INVITE_GAP_MS } from '../src/convoy/openCruise.js';
import { parseClientMessage } from '../src/convoy/messages.js';
import { testServer, type Session, type TestServer } from './apiHelpers.js';
import { connected } from './socketHelpers.js';

const who = (id: string) => ({ id, username: id.toUpperCase(), avatar: '' });
const BATHURST = { lat: -33.4193, lng: 149.5775 };
const NEAR = { lat: -33.4210, lng: 149.5780 }; // ~200 m
const MID = { lat: -33.4400, lng: 149.5775 }; // ~2.3 km
const FAR = { lat: -33.5000, lng: 149.5775 }; // ~9 km

test('open cruise: lists drivers within 5 km, rounded, invite only within 500 m and not twice in 10 s', () => {
  let now = 1_000_000;
  const oc = new OpenCruise(() => now);
  for (const id of ['a', 'b', 'c', 'd']) oc.set(who(id), true);
  oc.update('a', BATHURST.lat, BATHURST.lng);
  oc.update('b', NEAR.lat, NEAR.lng);
  oc.update('c', MID.lat, MID.lng);
  oc.update('d', FAR.lat, FAR.lng);
  const seen = oc.nearby('a', [], () => false);
  assert.deepEqual(seen.drivers.map((d) => d.id), ['b', 'c']);
  assert.equal(seen.drivers[0].canInvite, true);
  assert.equal(seen.drivers[1].canInvite, false);
  assert.equal(seen.drivers[0].lat, -33.421);
  assert.equal(seen.drivers[0].distanceM % 50, 0);

  assert.equal(oc.invite('a', 'b').ok, true);
  assert.match((oc.invite('a', 'b') as { error: string }).error, /just invited/);
  now += OPEN_CRUISE_INVITE_GAP_MS + 1;
  assert.equal(oc.invite('a', 'b').ok, true);
  assert.match((oc.invite('a', 'c') as { error: string }).error, /within 500 m/);

  oc.set(who('b'), false);
  assert.deepEqual(oc.nearby('a', [], () => false).drivers.map((d) => d.id), ['c']);
  // people in your convoy are on the roster, not in the nearby list
  assert.deepEqual(oc.nearby('a', [], (x, y) => x === 'a' && y === 'c').drivers, []);
});

test('open cruise: open convoys nearby, not the ones you are in', () => {
  const oc = new OpenCruise();
  oc.set(who('a'), true);
  oc.update('a', BATHURST.lat, BATHURST.lng);
  const rooms = [
    { code: 'AAAAAAAA', name: 'Sunday Run', members: 5, lat: MID.lat, lng: MID.lng, destination: 'Mt Panorama', memberIds: ['x'] },
    { code: 'BBBBBBBB', name: 'Far away', members: 2, lat: FAR.lat, lng: FAR.lng, destination: null, memberIds: ['y'] },
    { code: 'CCCCCCCC', name: 'Mine', members: 2, lat: NEAR.lat, lng: NEAR.lng, destination: null, memberIds: ['a'] },
  ];
  assert.deepEqual(oc.nearby('a', rooms, () => false).convoys.map((c) => c.name), ['Sunday Run']);
});

test('open cruise: messages are checked', () => {
  assert.deepEqual(parseClientMessage(JSON.stringify({ t: 'cruise.open', on: true })), { ok: true, message: { t: 'cruise.open', on: true } });
  assert.equal(parseClientMessage(JSON.stringify({ t: 'cruise.open', on: 'yes' })).ok, false);
  assert.equal(parseClientMessage(JSON.stringify({ t: 'convoy.open', room: 'X', open: 1 })).ok, false);
  assert.equal(parseClientMessage(JSON.stringify({ t: 'cruise.join', code: 'nope!' })).ok, false);
});

// ---- over the wire --------------------------------------------------------------------------------------

let t: TestServer;
before(async () => { t = await testServer(); });
after(() => t.close());
const wsFor = (s: Session) => new WebSocket(`${t.wsBase}/ws`, ['convoyage.v1', `bearer.${s.token}`]);

test('socket: two cruisers see each other, invite makes an open convoy, the invitee joins without a code', async () => {
  const a = await t.signup('cruiser-a');
  const b = await t.signup('cruiser-b');
  const wa = await connected(wsFor(a));
  const wb = await connected(wsFor(b));
  wa.send({ t: 'cruise.open', on: true });
  wb.send({ t: 'cruise.open', on: true });
  await wa.next((x) => x.t === 'cruise.state' && x.on);
  await wb.next((x) => x.t === 'cruise.state' && x.on);
  wb.send({ t: 'pos', ...NEAR, heading: null, speed: null });
  await wb.next((x) => x.t === 'cruise.nearby');
  wa.send({ t: 'pos', ...BATHURST, heading: null, speed: null });
  const list = await wa.next((x) => x.t === 'cruise.nearby' && x.drivers.length === 1);
  assert.equal(list.drivers[0].id, b.id);
  assert.equal(list.drivers[0].canInvite, true);

  // Without an invite, joining by code through cruise.join is refused.
  wb.send({ t: 'cruise.join', code: 'ABCDEFGH' });
  assert.match((await wb.next((x) => x.t === 'error')).error, /not open to you/);

  wa.send({ t: 'cruise.invite', to: b.id });
  const room = await wa.next((x) => x.t === 'room');
  assert.equal(room.room.open, true);
  assert.match(room.room.name, /open convoy/);
  const invite = await wb.next((x) => x.t === 'cruise.invite');
  assert.equal(invite.code, room.room.code);
  wb.send({ t: 'cruise.join', code: invite.code });
  const joined = await wb.next((x) => x.t === 'room' && x.room.members.length === 2);
  assert.equal(joined.room.code, room.room.code);
  wa.ws.close(); wb.ws.close();
});

test('socket: a leader opens their convoy and a nearby cruiser can join it from the list', async () => {
  const lead = await t.signup('open-lead');
  const c = await t.signup('open-cruiser');
  const wl = await connected(wsFor(lead));
  const wc = await connected(wsFor(c));
  wl.send({ t: 'convoy.create', name: 'Sunday Run' });
  const code = (await wl.next((x) => x.t === 'room')).room.code;
  wl.send({ t: 'pos', ...MID, heading: null, speed: null });
  wl.send({ t: 'convoy.open', room: code, open: true });
  assert.equal((await wl.next((x) => x.t === 'room' && x.room.open)).room.code, code);

  wc.send({ t: 'cruise.open', on: true });
  await wc.next((x) => x.t === 'cruise.state');
  wc.send({ t: 'pos', ...BATHURST, heading: null, speed: null });
  const list = await wc.next((x) => x.t === 'cruise.nearby' && x.convoys.length > 0);
  assert.equal(list.convoys[0].code, code);
  assert.equal(list.convoys[0].name, 'Sunday Run');
  wc.send({ t: 'cruise.join', code });
  await wc.next((x) => x.t === 'room' && x.room.code === code);
  wl.ws.close(); wc.ws.close();
});
