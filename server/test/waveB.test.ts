import test, { after, before } from 'node:test';
import assert from 'node:assert/strict';
import { WebSocket } from 'ws';
import { encodePolyline } from '../src/api/polyline.js';
import { OverpassRestStops } from '../src/convoy/overpass.js';
import { parseClientMessage } from '../src/convoy/messages.js';
import { Rooms } from '../src/convoy/rooms.js';
import { testServer, type Session, type TestServer } from './apiHelpers.js';
import { connected } from './socketHelpers.js';

// ---- Overpass rest stops ------------------------------------------------------------------------------

const BATHURST: [number, number] = [149.5775, -33.4193];
const elements = [
  { type: 'node', lat: -33.4200, lon: 149.5780, tags: { amenity: 'cafe', name: 'Near Cafe' } },
  { type: 'node', lat: -33.4600, lon: 149.6000, tags: { amenity: 'fuel', name: 'Far Servo', brand: 'X' } },
  { type: 'way', center: { lat: -33.4400, lon: 149.5800 }, tags: { highway: 'rest_area' } },
  { type: 'node', lat: -33.4195, lon: 149.5776, tags: { shop: 'bakery' } },
];

function fakeOverpass(body: unknown, init: { ok?: boolean; throws?: boolean } = {}) {
  const calls: { url: string; init: RequestInit }[] = [];
  const fetcher = async (url: string, req?: RequestInit) => {
    calls.push({ url, init: req ?? {} });
    if (init.throws) throw new Error('boom');
    return { ok: init.ok ?? true, json: async () => body } as Response;
  };
  return { calls, fetcher };
}

test('rest stops: fuel and rest areas beat nearer cafes, nearest of those wins, polite request', async () => {
  const f = fakeOverpass({ elements });
  const finder = new OverpassRestStops('https://overpass.test/api', f.fetcher);
  const stop = await finder.find([], BATHURST);
  assert.deepEqual(stop, { lat: -33.44, lng: 149.58, label: 'Rest area' });
  assert.equal(f.calls[0].url, 'https://overpass.test/api');
  assert.equal((f.calls[0].init.headers as Record<string, string>)['user-agent'], 'Convoyage/0.1 (self-hosted)');
  const q = decodeURIComponent(String(f.calls[0].init.body));
  assert.match(q, /"amenity"="fuel"/);
  assert.match(q, /rest_area/);
  assert.match(q, /picnic_site/);
  assert.match(q, /cafe\|fast_food\|restaurant/);
  assert.ok(q.includes('-33.4193,149.5775'));
});

test('rest stops: falls back to a cafe when nothing preferred is around, names are used', async () => {
  const f = fakeOverpass({ elements: [elements[0], elements[3]] });
  const stop = await new OverpassRestStops('https://o', f.fetcher).find([], BATHURST);
  assert.equal(stop?.label, 'Near Cafe (cafe)');
});

test('rest stops: cached 10 minutes by rounded location', async () => {
  let t = 1_000_000;
  const f = fakeOverpass({ elements });
  const finder = new OverpassRestStops('https://o', f.fetcher, () => t);
  await finder.find([], BATHURST);
  await finder.find([], [149.5779, -33.4196]);
  assert.equal(f.calls.length, 1);
  await finder.find([], [149.9, -33.9]);
  assert.equal(f.calls.length, 2);
  t += 10 * 60_000 + 1;
  await finder.find([], BATHURST);
  assert.equal(f.calls.length, 3);
});

test('rest stops: a failing endpoint falls over to the next one', async () => {
  const calls: string[] = [];
  const fetcher = async (url: string) => {
    calls.push(url);
    if (url === 'https://busy') return { ok: false, json: async () => ({}) } as Response;
    return { ok: true, json: async () => ({ elements }) } as Response;
  };
  const stop = await new OverpassRestStops(['https://busy', 'https://mirror'], fetcher).find([], BATHURST);
  assert.equal(stop?.label, 'Rest area');
  assert.deepEqual(calls, ['https://busy', 'https://mirror']);
});

test('rest stops: errors, bad status and empty results all mean none found', async () => {
  assert.equal(await new OverpassRestStops('https://o', fakeOverpass({}, { throws: true }).fetcher).find([], BATHURST), null);
  assert.equal(await new OverpassRestStops('https://o', fakeOverpass({}, { ok: false }).fetcher).find([], BATHURST), null);
  assert.equal(await new OverpassRestStops('https://o', fakeOverpass({ elements: [] }).fetcher).find([], BATHURST), null);
  const failing = fakeOverpass({ elements }, { ok: false });
  const finder = new OverpassRestStops('https://o', failing.fetcher);
  await finder.find([], BATHURST); await finder.find([], BATHURST);
  assert.equal(failing.calls.length, 2, 'failures are not cached');
});

// ---- rooms: rename, regroup, live names, geometry ---------------------------------------------------------

const who = (n: number) => ({ id: `u${n}`, username: `User ${n}`, avatar: '' });

test('rooms: only the leader renames or changes regroup, within bounds', () => {
  const rooms = new Rooms();
  const made = rooms.create(who(1));
  assert.ok(made.ok);
  const code = made.value.code;
  rooms.join(code, who(2));
  assert.equal(rooms.rename(code, 'u2', 'Mine').ok, false);
  assert.equal(rooms.rename(code, 'u1', '   ').ok, false);
  const r = rooms.rename(code, 'u1', '  Sunday run ');
  assert.ok(r.ok && r.value.name === 'Sunday run');
  assert.equal(rooms.setAutomaticRegroup(code, 'u2', { enabled: true, separationM: 3000 }).ok, false);
  assert.equal(rooms.setAutomaticRegroup(code, 'u1', { enabled: true, separationM: 999 }).ok, false);
  assert.equal(rooms.setAutomaticRegroup(code, 'u1', { enabled: true, separationM: 20_001 }).ok, false);
  const g = rooms.setAutomaticRegroup(code, 'u1', { enabled: true, separationM: 3000 });
  assert.ok(g.ok && g.value.automaticRegroup.enabled && g.value.automaticRegroup.separationM === 3000);
});

test('rooms: renameMember updates every room, geometry is dropped when the destination changes or is stale', () => {
  const rooms = new Rooms();
  const a = rooms.create(who(1));
  const b = rooms.create(who(1));
  assert.ok(a.ok && b.ok);
  assert.deepEqual(rooms.renameMember('u1', ' Neo ').sort(), [a.value.code, b.value.code].sort());
  assert.equal(rooms.view(a.value.code)!.members[0].username, 'Neo');
  assert.deepEqual(rooms.renameMember('u1', 'Neo'), [], 'no change, nothing to announce');
  assert.deepEqual(rooms.renameMember('u9', 'Nobody'), []);

  const code = a.value.code;
  assert.equal(rooms.view(code)!.routeGeometry, undefined);
  rooms.setDestination(code, 'u1', { lat: -33.4, lng: 149.5, label: 'Top' });
  const v = rooms.navigationVersion(code)!;
  assert.equal(rooms.setRouteGeometry(code, v - 1, 'abc').ok, false);
  assert.equal(rooms.setRouteGeometry(code, v, 'abc').ok, true);
  assert.equal(rooms.view(code)!.routeGeometry, 'abc');
  rooms.setDestination(code, 'u1', { lat: -33.5, lng: 149.5, label: 'Next' });
  assert.equal(rooms.view(code)!.routeGeometry, undefined);
});

test('parser: convoy.rename and convoy.regroup', () => {
  assert.deepEqual(parseClientMessage('{"t":"convoy.rename","room":"R","name":" Dawn "}'), { ok: true, message: { t: 'convoy.rename', room: 'R', name: 'Dawn' } });
  assert.equal(parseClientMessage('{"t":"convoy.rename","room":"R","name":" "}').ok, false);
  assert.equal(parseClientMessage('{"t":"convoy.rename","room":"R","name":"' + 'x'.repeat(41) + '"}').ok, false);
  assert.deepEqual(parseClientMessage('{"t":"convoy.regroup","room":"R","enabled":true,"separationM":5000}'), { ok: true, message: { t: 'convoy.regroup', room: 'R', enabled: true, separationM: 5000 } });
  assert.equal(parseClientMessage('{"t":"convoy.regroup","room":"R","enabled":true,"separationM":500}').ok, false);
  assert.equal(parseClientMessage('{"t":"convoy.regroup","room":"R","enabled":true,"separationM":25000}').ok, false);
  assert.equal(parseClientMessage('{"t":"convoy.regroup","room":"R","enabled":"yes","separationM":5000}').ok, false);
});

// ---- over the wire --------------------------------------------------------------------------------------

const GEOMETRY = encodePolyline([[-33.4193, 149.5775], [-33.447, 149.558]], 6);
let t: TestServer;
let osrmCalls: string[] = [];
before(async () => {
  t = await testServer({
    fetch: async (url) => {
      osrmCalls.push(url);
      return new Response(JSON.stringify({ code: 'Ok', routes: [{ geometry: GEOMETRY, distance: 1, duration: 1, legs: [] }] }), { status: 200 });
    },
  });
});
after(() => t.close());
const wsFor = (s: Session) => new WebSocket(`${t.wsBase}/ws`, ['convoyage.v1', `bearer.${s.token}`]);

test('socket: leader renames and changes regroup, a follower cannot', async () => {
  const lead = await t.signup('lead');
  const mate = await t.signup('mate');
  const l = await connected(wsFor(lead));
  const m = await connected(wsFor(mate));
  l.send({ t: 'convoy.create', name: 'Old name' });
  const code = (await l.next((x) => x.t === 'room')).room.code;
  m.send({ t: 'convoy.join', code });
  await m.next((x) => x.t === 'room');

  l.send({ t: 'convoy.rename', room: code, name: 'Blue Mountains' });
  assert.equal((await m.next((x) => x.t === 'room' && x.room.name === 'Blue Mountains')).room.code, code);
  l.send({ t: 'convoy.regroup', room: code, enabled: true, separationM: 8000 });
  assert.deepEqual((await m.next((x) => x.t === 'room' && x.room.automaticRegroup.enabled)).room.automaticRegroup, { enabled: true, separationM: 8000 });

  m.send({ t: 'convoy.rename', room: code, name: 'Mine now' });
  assert.match((await m.next((x) => x.t === 'error')).error, /Only the leader/);
  m.send({ t: 'convoy.regroup', room: code, enabled: false, separationM: 8000 });
  assert.match((await m.next((x) => x.t === 'error')).error, /Only the leader/);
  l.send({ t: 'convoy.regroup', room: code, enabled: true, separationM: 50 });
  assert.match((await l.next((x) => x.t === 'error')).error, /separationM/);
  l.ws.close(); m.ws.close();
});

test('socket: changing displayName over REST updates the member name in the room live', async () => {
  const a = await t.signup('namer');
  const b = await t.signup('watcher');
  const wa = await connected(wsFor(a));
  const wb = await connected(wsFor(b));
  wa.send({ t: 'convoy.create', name: 'Names' });
  const code = (await wa.next((x) => x.t === 'room')).room.code;
  wb.send({ t: 'convoy.join', code });
  await wb.next((x) => x.t === 'room');
  const patched = await t.req('PATCH', '/v1/me', { token: a.token, body: { displayName: 'Maverick' } });
  assert.equal(patched.status, 200);
  const seen = await wb.next((x) => x.t === 'room' && x.room.members.some((mem: any) => mem.id === a.id && mem.username === 'Maverick'));
  assert.equal(seen.room.code, code);
  // a bio-only change is not a rename
  assert.equal((await t.req('PATCH', '/v1/me', { token: a.token, body: { bio: 'hi' } })).status, 200);
  assert.equal(await wb.quietly((x) => x.t === 'room'), 0);
  wa.ws.close(); wb.ws.close();
});

test('socket: a destination from a leader with a position brings a road-following routeGeometry', async () => {
  const lead = await t.signup('router');
  const l = await connected(wsFor(lead));
  l.send({ t: 'convoy.create', name: 'Roads' });
  const code = (await l.next((x) => x.t === 'room')).room.code;
  osrmCalls = [];
  // no position yet: destination works as before and nothing is fetched
  l.send({ t: 'convoy.dest', room: code, destination: { lat: -33.447, lng: 149.558, label: 'Mt Panorama' } });
  const plain = await l.next((x) => x.t === 'room' && x.room.destination);
  assert.equal(plain.room.routeGeometry, undefined);
  assert.equal(osrmCalls.length, 0);

  l.send({ t: 'pos', lat: -33.4193, lng: 149.5775, heading: 0, speed: 5 });
  await new Promise((r) => setTimeout(r, 300));
  l.send({ t: 'convoy.dest', room: code, destination: { lat: -33.447, lng: 149.558, label: 'Mt Panorama' } });
  const routed = await l.next((x) => x.t === 'room' && x.room.routeGeometry);
  assert.equal(routed.room.routeGeometry, GEOMETRY);
  assert.match(osrmCalls[0], /149\.5775,-33\.4193;149\.558,-33\.447\?overview=full&geometries=polyline6/);

  l.send({ t: 'convoy.dest', room: code, destination: null });
  const cleared = await l.next((x) => x.t === 'room' && x.room.destination === null);
  assert.equal(cleared.room.routeGeometry, undefined);
  l.ws.close();
});

test('guests: cannot report hazards or register devices, still can use voice and the socket', async () => {
  const guest = (await t.req('POST', '/auth/guest', { body: { name: 'Pete' } })).body.accessToken;
  assert.equal((await t.req('POST', '/v1/hazards', { token: guest, body: { kind: 'hazard', lat: -33.2, lng: 151.2 } })).status, 403);
  assert.equal((await t.req('POST', '/v1/me/devices', { token: guest, body: { platform: 'ios', token: 'apns-guest-token-02' } })).status, 403);
  const g = await connected(new WebSocket(`${t.wsBase}/ws`, ['convoyage.v1', `bearer.${guest}`]));
  g.ws.close();
});
