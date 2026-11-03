import test, { after, before } from 'node:test';
import assert from 'node:assert/strict';
import { testServer, type Session, type TestServer } from './apiHelpers.js';
import { encodePolyline } from '../src/api/polyline.js';
import { thinImportant, offSegmentM } from '../src/convoy/thin.js';
import { convoyStops } from '../src/api/routes/savedRoutes.js';
import { psRouteDto, psRouteInput } from '../src/api/routes/photoscout.js';

let t: TestServer;
let a: Session;
let b: Session;

// Bathurst CBD out to Mount Panorama, roughly: an L with a corner the old even spacing used to cut.
const bathurst: [number, number][] = [[149.5775, -33.4193], [149.5700, -33.4195], [149.5650, -33.4200], [149.5640, -33.4260], [149.5600, -33.4400], [149.5580, -33.4470]];

before(async () => {
  t = await testServer();
  a = await t.signup('ra');
  b = await t.signup('rb');
  await t.req('PATCH', '/v1/me', { token: a.token, body: { handle: 'route_a' } });
  await t.req('PATCH', '/v1/me', { token: b.token, body: { handle: 'route_b' } });
});
after(() => t.close());

test('saved routes: create, read, list, edit, delete; convoy stops close a circuit', async () => {
  const geometry = encodePolyline(bathurst.map(([lng, lat]): [number, number] => [lat, lng]), 6);
  const made = await t.req('POST', '/v1/routes', {
    token: a.token,
    body: { name: 'Mount Panorama lap', type: 'circuit', waypoints: bathurst, geometry, distanceM: 6200, durationS: 600, staging: { lat: -33.4193, lng: 149.5775 }, visibility: 'public' },
  });
  assert.equal(made.status, 201);
  assert.equal(made.body.type, 'circuit');
  assert.deepEqual(made.body.staging, { lat: -33.4193, lng: 149.5775 });
  assert.deepEqual(made.body.convoyStops.at(-1), made.body.convoyStops[0], 'a circuit ends where it starts');

  const mine = await t.req('GET', '/v1/routes', { token: a.token });
  assert.equal(mine.body.length, 1);
  const theirs = await t.req('GET', '/v1/users/route_a/routes', { token: b.token });
  assert.equal(theirs.body.length, 1, 'public routes show on the profile');

  const edited = await t.req('PATCH', `/v1/routes/${made.body.id}`, { token: a.token, body: { name: 'The Mountain', type: 'sprint', staging: null, visibility: 'private' } });
  assert.equal(edited.status, 200, JSON.stringify(edited.body));
  assert.equal(edited.body.name, 'The Mountain');
  assert.equal(edited.body.staging, null);
  assert.notDeepEqual(edited.body.convoyStops.at(-1), edited.body.convoyStops[0], 'a sprint does not loop');
  assert.equal((await t.req('GET', `/v1/routes/${made.body.id}`, { token: b.token })).status, 404, 'private routes are hidden');
  assert.equal((await t.req('PATCH', `/v1/routes/${made.body.id}`, { token: b.token, body: { name: 'x' } })).status, 404, 'only the owner edits');

  assert.equal((await t.req('DELETE', `/v1/routes/${made.body.id}`, { token: a.token })).status, 204);
  assert.equal((await t.req('GET', '/v1/routes', { token: a.token })).body.length, 0);
});

test('saved routes: bad input is refused', async () => {
  assert.equal((await t.req('POST', '/v1/routes', { token: a.token, body: { name: 'one point', waypoints: [[149.5, -33.4]] } })).status, 400);
  assert.equal((await t.req('POST', '/v1/routes', { token: a.token, body: { name: 'bad geo', waypoints: bathurst, geometry: '!!!' } })).status, 400);
  assert.equal((await t.req('POST', '/v1/routes', { token: a.token, body: { name: 'out of range', waypoints: [[200, 0], [0, 0]] } })).status, 400);
});

test('thinning keeps the corners: a long road line fits 25 stops without cutting across blocks', () => {
  // A dense line: 400 points along an L (2 km east, then 2 km south).
  const line: [number, number][] = [];
  for (let i = 0; i <= 200; i++) line.push([149.55 + (i / 200) * 0.0216, -33.42]);
  for (let i = 1; i <= 200; i++) line.push([149.5716, -33.42 - (i / 200) * 0.018]);
  const stops = thinImportant(line, 25);
  assert.ok(stops.length <= 25);
  assert.deepEqual(stops[0], line[0]);
  assert.deepEqual(stops.at(-1), line.at(-1));
  const worst = Math.max(...line.map((p) => Math.min(...stops.slice(1).map((s, i) => offSegmentM(p, stops[i], s)))));
  assert.ok(worst < 5, `thinned line strays ${worst.toFixed(1)} m from the road`);

  const many = Array.from({ length: 60 }, (_, i): [number, number] => line[i * 6]);
  const viaGeometry = convoyStops({ type: 'sprint', waypoints: JSON.stringify(many), geometry: encodePolyline(line.map(([lng, lat]): [number, number] => [lat, lng]), 6) });
  assert.ok(viaGeometry.length <= 25, 'too many waypoints fall back to the thinned road line');
});

test('PhotoScout routes normalise and import as saved routes', () => {
  const raw = { id: 'r1', name: '  Oberon run ', type: 'circuit', snap: true, vertices: bathurst, waypoints: [bathurst[0], bathurst[5]], staging: { lat: -33.4, lng: 149.5 }, notes: 'Fuel at the servo' };
  const dto = psRouteDto(raw)!;
  assert.equal(dto.name, 'Oberon run');
  assert.equal(dto.type, 'circuit');
  assert.equal(dto.waypoints!.length, 2);
  const input = psRouteInput(dto);
  assert.deepEqual(input.waypoints, [bathurst[0], bathurst[5]]);
  assert.equal(input.visibility, 'private');
  assert.ok(input.geometry && input.geometry.length > 0);
  assert.equal(psRouteDto({ id: 'x', vertices: [[1, 2]] }), null, 'a route needs a line');
});
