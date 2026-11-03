import test, { after, before } from 'node:test';
import assert from 'node:assert/strict';
import { jwtVerify } from 'jose';
import { WebSocket } from 'ws';
import { encodePolyline, decodePolyline } from '../src/api/polyline.js';
import { instruction } from '../src/api/routes/navigation.js';
import { Notifier } from '../src/push/notifier.js';
import type { PushPayload, PushSender, PushTarget } from '../src/push/sender.js';
import { makeJpeg, testServer, upload, type Session, type TestServer } from './apiHelpers.js';
import { connected, refusal } from './socketHelpers.js';

class FakeSender implements PushSender {
  sent: { targets: PushTarget[]; payload: PushPayload }[] = [];
  invalid: string[] = [];
  async send(targets: PushTarget[], payload: PushPayload) {
    this.sent.push({ targets, payload });
    return { invalid: this.invalid };
  }
  of(type: string) { return this.sent.filter((s) => s.payload.data?.type === type); }
}

const osrmReply = {
  code: 'Ok',
  routes: [{
    geometry: encodePolyline([[-33.4193, 149.5775], [-33.447, 149.558]], 6), distance: 4200.5, duration: 400.2,
    legs: [{ steps: [
      { distance: 1000, duration: 100, name: 'Conrod Straight', maneuver: { type: 'depart', location: [149.5775, -33.4193], bearing_after: 200 } },
      { distance: 3200.5, duration: 300.2, name: 'Mount Panorama Circuit', maneuver: { type: 'turn', modifier: 'left', location: [149.57, -33.43] } },
      { distance: 0, duration: 0, name: '', maneuver: { type: 'arrive', location: [149.558, -33.447] } },
    ] }],
  }],
};

const sender = new FakeSender();
const osrmCalls: string[] = [];
let osrmBody: unknown = osrmReply;
let osrmStatus = 200;
let t: TestServer;
let a: Session;
let b: Session;
let c: Session;

before(async () => {
  t = await testServer({
    env: { LIVEKIT_URL: 'ws://lk.test:7880', LIVEKIT_API_KEY: 'devkey', LIVEKIT_API_SECRET: 's'.repeat(40), OSRM_URL: 'http://osrm.test/', ROUTE_RATE_LIMIT_MAX: '4' },
    push: sender,
    fetch: async (url) => { osrmCalls.push(url); return new Response(JSON.stringify(osrmBody), { status: osrmStatus, headers: { 'content-type': 'application/json' } }); },
  });
  [a, b, c] = [await t.signup('wa'), await t.signup('wb'), await t.signup('wc')];
  for (const [s, h] of [[a, 'wave_a'], [b, 'wave_b'], [c, 'wave_c']] as const) await t.req('PATCH', '/v1/me', { token: s.token, body: { handle: h } });
});
after(() => t.close());

const settle = () => new Promise((r) => setTimeout(r, 150));
const wsFor = (s: Session) => new WebSocket(`${t.wsBase}/ws`, ['convoyage.v1', `bearer.${s.token}`]);

test('migration 002 applied: rides.moving_time_s, users.kind, device_tokens', async () => {
  const done = await t.database.appliedMigrations();
  assert.ok(done.has('002_wave_a.sql'));
});

test('catalogue: public, 8 paints including brand cyan, 6 wheels', async () => {
  const res = await t.req('GET', '/v1/catalogue');
  assert.equal(res.status, 200);
  assert.equal(res.body.paints.length, 8);
  assert.ok(res.body.paints.some((p: any) => p.hex === '#2de2e6'));
  assert.equal(res.body.wheels.length, 6);
  for (const w of res.body.wheels) assert.ok(w.id && w.name);
  assert.deepEqual(res.body.finishes.map((f: any) => f.id), ['gloss', 'satin', 'matte', 'metallic', 'pearl']);
  assert.ok(res.body.decals.length >= 4);
  assert.ok(res.body.kinds.some((k: any) => k.id === 'motorcycle'));
});

test('rides: movingTimeS round-trips; feed ride posts carry a compact route preview', async () => {
  const ride = (await t.req('POST', '/v1/rides', { token: a.token, body: { visibility: 'public', startLat: -33.4, startLng: 149.5 } })).body;
  assert.equal(ride.movingTimeS, null);
  const pts: [number, number][] = [];
  for (let i = 0; i < 1500; i++) pts.push([-33.4 + i * 0.0002, 149.5 + Math.sin(i / 40) * 0.01 + i * 0.0001]);
  const fin = await t.req('POST', `/v1/rides/${ride.id}/finish`, {
    token: a.token, body: { endedAt: new Date().toISOString(), distanceM: 9000, durationS: 900, movingTimeS: 800, maxSpeedMps: 30, avgSpeedMps: 10, polyline: encodePolyline(pts) },
  });
  assert.equal(fin.status, 200, JSON.stringify(fin.body));
  assert.equal(fin.body.movingTimeS, 800);
  assert.equal((await t.req('GET', `/v1/rides/${ride.id}`, { token: a.token })).body.movingTimeS, 800);
  assert.equal((await t.req('GET', '/v1/rides', { token: a.token })).body.items[0].movingTimeS, 800);

  const post = await t.req('POST', '/v1/posts', { token: a.token, body: { kind: 'ride', rideId: ride.id, visibility: 'public' } });
  assert.equal(post.status, 201);
  const item = (await t.req('GET', '/v1/feed', { token: b.token })).body.items.find((i: any) => i.id === post.body.id);
  const prev = item.ride.preview;
  assert.ok(prev.pointCount <= 100 && prev.pointCount > 10, `points ${prev.pointCount}`);
  assert.equal(decodePolyline(prev.polyline, 5).length, prev.pointCount);
  assert.ok(prev.bbox.south <= -33.4 && prev.bbox.north > -33.2 && prev.bbox.west < prev.bbox.east);
  assert.equal(item.ride.movingTimeS, 800);
  assert.equal(item.ride.distanceM, 9000);
});

test('PATCH: explicit null clears nullable fields, absent leaves them alone', async () => {
  const v = (await t.req('POST', '/v1/vehicles', { token: a.token, body: { kind: 'car', make: 'Holden', model: 'Commodore', year: 2010, nickname: 'Bessie' } })).body;
  const same = await t.req('PATCH', `/v1/vehicles/${v.id}`, { token: a.token, body: { make: 'HSV' } });
  assert.equal(same.body.year, 2010);
  assert.equal(same.body.nickname, 'Bessie');
  const cleared = await t.req('PATCH', `/v1/vehicles/${v.id}`, { token: a.token, body: { year: null, nickname: null } });
  assert.equal(cleared.status, 200, JSON.stringify(cleared.body));
  assert.equal(cleared.body.year, null);
  assert.equal(cleared.body.nickname, '');

  await t.req('PATCH', '/v1/me', { token: b.token, body: { bio: 'hello' } });
  assert.equal((await t.req('GET', '/v1/me', { token: b.token })).body.bio, 'hello');
  assert.equal((await t.req('PATCH', '/v1/me', { token: b.token, body: { displayName: 'Bee' } })).body.bio, 'hello');
  assert.equal((await t.req('PATCH', '/v1/me', { token: b.token, body: { bio: null } })).body.bio, '');

  const ride = (await t.req('POST', '/v1/rides', { token: a.token, body: { title: 'Morning' } })).body;
  assert.equal((await t.req('PATCH', `/v1/rides/${ride.id}`, { token: a.token, body: { title: null } })).body.title, '');
});

test('hazards: myAction is null or a vote, never an empty string', async () => {
  const h = await t.req('POST', '/v1/hazards', { token: a.token, body: { kind: 'hazard', lat: -33.8, lng: 151.2 } });
  assert.strictEqual(h.body.myAction, null);
  const list = await t.req('GET', '/v1/hazards?bbox=-34,151,-33,152', { token: b.token });
  assert.strictEqual(list.body.find((x: any) => x.id === h.body.id).myAction, null);
  const voted = await t.req('POST', `/v1/hazards/${h.body.id}/confirm`, { token: b.token });
  assert.strictEqual(voted.body.myAction, 'confirm');
  assert.strictEqual((await t.req('POST', `/v1/hazards/${h.body.id}/ack`, { token: c.token })).body.myAction, null);
});

test('photos: mine, theirs with privacy and blocks, cursor paging', async () => {
  const [m1, m2, m3] = [(await upload(t, c.token, await makeJpeg())).body, (await upload(t, c.token, await makeJpeg(80, 60))).body, (await upload(t, c.token, await makeJpeg(70, 50))).body];
  const unused = (await upload(t, c.token, await makeJpeg(10, 10))).body;
  const ride = (await t.req('POST', '/v1/rides', { token: c.token, body: { visibility: 'public' } })).body;
  await t.req('POST', `/v1/rides/${ride.id}/photos`, { token: c.token, body: { mediaId: m1.id, lat: -33.1, lng: 151.1 } });
  await t.req('POST', `/v1/rides/${ride.id}/finish`, { token: c.token, body: { endedAt: new Date().toISOString(), distanceM: 1, durationS: 1, maxSpeedMps: 1, avgSpeedMps: 1, polyline: encodePolyline([[-33.1, 151.1]]) } });
  assert.equal((await t.req('POST', '/v1/posts', { token: c.token, body: { kind: 'photo', mediaIds: [m2.id], visibility: 'followers' } })).status, 201);
  assert.equal((await t.req('POST', '/v1/posts', { token: c.token, body: { kind: 'photo', mediaIds: [m3.id, m1.id] } })).status, 201);

  const mine = (await t.req('GET', '/v1/me/photos', { token: c.token })).body;
  assert.deepEqual(new Set(mine.items.map((i: any) => i.media.id)), new Set([m1.id, m2.id, m3.id]), 'unattached uploads are not photos');
  assert.ok(!mine.items.some((i: any) => i.media.id === unused.id));
  const first = mine.items.find((i: any) => i.media.id === m1.id);
  assert.equal(first.rideId, ride.id);
  assert.ok(first.postId);
  assert.equal(first.lat, -33.1);

  const p1 = (await t.req('GET', '/v1/me/photos?limit=2', { token: c.token })).body;
  assert.equal(p1.items.length, 2);
  assert.ok(p1.nextCursor);
  const p2 = (await t.req('GET', `/v1/me/photos?limit=2&before=${encodeURIComponent(p1.nextCursor)}`, { token: c.token })).body;
  assert.equal(p2.items.length, 1);
  assert.equal(p2.nextCursor, null);

  // a stranger sees public things only (not the followers-only post)
  const seen = (await t.req('GET', '/v1/users/wave_c/photos', { token: b.token })).body;
  assert.deepEqual(new Set(seen.items.map((i: any) => i.media.id)), new Set([m1.id, m3.id]));
  // follower sees the followers-only one
  await t.req('POST', '/v1/users/wave_c/follow', { token: b.token });
  assert.equal((await t.req('GET', '/v1/users/wave_c/photos', { token: b.token })).body.items.length, 3);
  // private account: hidden until followed
  await t.req('PATCH', '/v1/me', { token: c.token, body: { isPrivate: true } });
  await t.req('DELETE', '/v1/users/wave_c/follow', { token: b.token });
  assert.equal((await t.req('GET', '/v1/users/wave_c/photos', { token: b.token })).body.items.length, 0);
  await t.req('PATCH', '/v1/me', { token: c.token, body: { isPrivate: false } });
  // blocked either way: nothing
  await t.req('POST', '/v1/users/wave_b/block', { token: c.token });
  assert.equal((await t.req('GET', '/v1/users/wave_c/photos', { token: b.token })).status, 404);
  await t.req('DELETE', '/v1/users/wave_b/block', { token: c.token });
  await t.req('POST', '/v1/users/wave_c/block', { token: b.token });
  assert.equal((await t.req('GET', '/v1/users/wave_c/photos', { token: b.token })).body.items.length, 0);
  await t.req('DELETE', '/v1/users/wave_c/block', { token: b.token });
  assert.equal((await t.req('GET', '/v1/users/wave_c/photos')).status, 401);
});

test('route: proxies OSRM, builds instructions, caches, validates, maps upstream errors', async () => {
  const q = 'from=149.5775,-33.4193&to=149.5580,-33.4470&profile=motorcycle';
  const r = await t.req('GET', `/v1/route?${q}`, { token: a.token });
  assert.equal(r.status, 200, JSON.stringify(r.body));
  assert.equal(r.body.distanceM, 4200.5);
  assert.equal(r.body.geometryPrecision, 6);
  assert.deepEqual(decodePolyline(r.body.geometry, 6)[0], [-33.4193, 149.5775]);
  assert.deepEqual(r.body.steps.map((s: any) => s.instruction), ['Head south on Conrod Straight', 'Turn left onto Mount Panorama Circuit', 'You have arrived at your destination']);
  assert.equal(r.body.steps[0].maneuver.modifier, null);
  assert.equal(osrmCalls.length, 1);
  assert.match(osrmCalls[0], /^http:\/\/osrm\.test\/route\/v1\/driving\/149\.5775,-33\.4193;149\.558,-33\.447\?.*geometries=polyline6.*steps=true/);
  await t.req('GET', `/v1/route?${q}`, { token: a.token });
  assert.equal(osrmCalls.length, 1, 'second identical call is cached');
  const via = await t.req('GET', '/v1/route?from=149.5,-33.4&to=149.6,-33.5&via=149.55,-33.45;149.56,-33.46', { token: b.token });
  assert.equal(via.status, 200);
  assert.match(osrmCalls[1], /149\.5,-33\.4;149\.55,-33\.45;149\.56,-33\.46;149\.6,-33\.5/);

  assert.equal((await t.req('GET', '/v1/route?from=oops&to=1,2', { token: c.token })).status, 400);
  assert.equal((await t.req('GET', '/v1/route?from=500,2&to=1,2', { token: c.token })).status, 400);
  assert.equal((await t.req('GET', '/v1/route?from=1,2&to=3,4&profile=boat', { token: c.token })).status, 400);
  assert.equal((await t.req('GET', '/v1/route?from=1,2&to=3,4')).status, 401);

  osrmBody = { code: 'NoRoute', message: 'none' };
  assert.equal((await t.req('GET', '/v1/route?from=10,10&to=11,11', { token: c.token })).status, 404);
  osrmBody = 'not json'; osrmStatus = 500;
  assert.equal((await t.req('GET', '/v1/route?from=12,10&to=13,11', { token: c.token })).status, 502);
  osrmBody = osrmReply; osrmStatus = 200;
});

test('route: rate limited per user (cache hits count), other users unaffected', async () => {
  const u = await t.signup('rl');
  const go = () => t.req('GET', '/v1/route?from=149.5775,-33.4193&to=149.5580,-33.4470&profile=motorcycle', { token: u.token });
  for (let i = 0; i < 4; i++) assert.equal((await go()).status, 200);
  const limited = await go();
  assert.equal(limited.status, 429);
  assert.ok(limited.headers.get('retry-after'));
  assert.equal((await t.req('GET', '/v1/route?from=149.5775,-33.4193&to=149.5580,-33.4470', { token: b.token })).status, 200);
});

test('instruction text for common maneuvers', () => {
  const i = (type: string, modifier?: string, name = 'High St', extra: object = {}) => instruction({ name, maneuver: { type, modifier, location: [0, 0], ...extra } });
  assert.equal(i('roundabout', undefined, 'High St', { exit: 2 }), 'At the roundabout, take the second exit onto High St');
  assert.equal(i('merge', 'left'), 'Merge left onto High St');
  assert.equal(i('fork', 'slight right'), 'Keep right at the fork onto High St');
  assert.equal(i('end of road', 'left'), 'At the end of the road, turn left onto High St');
  assert.equal(i('turn', 'uturn', ''), 'Make a U-turn');
  assert.equal(i('arrive', 'right', ''), 'You have arrived, your destination is on the right');
});

test('voice token: only for members of that convoy room, claims are right', async () => {
  const wa = await connected(wsFor(a));
  wa.send({ t: 'convoy.create', name: 'Voice' });
  const code = (await wa.next((m) => m.t === 'room')).room.code;

  const ok = await t.req('POST', '/v1/voice/token', { token: a.token, body: { convoyCode: code } });
  assert.equal(ok.status, 200, JSON.stringify(ok.body));
  assert.equal(ok.body.room, `convoy-${code}`);
  assert.equal(ok.body.url, 'ws://lk.test:7880');
  const { payload } = await jwtVerify(ok.body.token, new TextEncoder().encode('s'.repeat(40)), { issuer: 'devkey' });
  assert.equal(payload.sub, a.id);
  assert.equal(payload.name, (await t.req('GET', '/v1/me', { token: a.token })).body.displayName);
  const video = (payload as any).video;
  assert.equal(video.room, `convoy-${code}`);
  assert.equal(video.roomJoin, true);
  assert.equal(video.canPublish, true);
  assert.ok((payload.exp as number) > Date.now() / 1000);
  assert.equal((await t.req('POST', '/v1/voice/token', { token: a.token, body: { convoyCode: code.toLowerCase() } })).status, 200, 'case forgiven');

  assert.equal((await t.req('POST', '/v1/voice/token', { token: b.token, body: { convoyCode: code } })).status, 403, 'not a member');
  assert.equal((await t.req('POST', '/v1/voice/token', { token: a.token, body: { convoyCode: 'ZZZZZZZZ' } })).status, 403, 'no such room');
  assert.equal((await t.req('POST', '/v1/voice/token', { body: { convoyCode: code } })).status, 401);

  const wb = await connected(wsFor(b));
  wb.send({ t: 'convoy.join', code });
  await wb.next((m) => m.t === 'room');
  assert.equal((await t.req('POST', '/v1/voice/token', { token: b.token, body: { convoyCode: code } })).status, 200);
  wb.send({ t: 'convoy.leave', room: code });
  await wb.next((m) => m.t === 'left');
  assert.equal((await t.req('POST', '/v1/voice/token', { token: b.token, body: { convoyCode: code } })).status, 403, 'left the room');
  wa.ws.close(); wb.ws.close();

  const bare = await testServer({ env: {} });
  const s = await bare.signup('nolk');
  assert.equal((await bare.req('POST', '/v1/voice/token', { token: s.token, body: { convoyCode: 'ABCDEFGH' } })).status, 501);
  await bare.close();
});

test('convoy.status: broadcast to the room with the member id; SOS carries the position and pushes to others', async () => {
  await t.req('POST', '/v1/me/devices', { token: b.token, body: { platform: 'android', token: 'fcm-token-for-b-0001' } });
  const wa = await connected(wsFor(a));
  const wb = await connected(wsFor(b));
  const wc = await connected(wsFor(c));
  wa.send({ t: 'convoy.create', name: 'Status' });
  const code = (await wa.next((m) => m.t === 'room')).room.code;
  wb.send({ t: 'convoy.join', code });
  await wb.next((m) => m.t === 'room');

  wa.send({ t: 'convoy.status', status: 'stopping', note: ' fuel ' });
  for (const w of [wa, wb]) {
    const m = await w.next((x) => x.t === 'status');
    assert.deepEqual(m, { t: 'status', room: code, id: a.id, status: 'stopping', note: 'fuel' });
  }
  wa.send({ t: 'pos', lat: -33.5, lng: 150.5, heading: 90, speed: 12 });
  await wb.next((m) => m.t === 'pos');
  sender.sent.length = 0;
  wa.send({ t: 'convoy.status', status: 'sos' });
  const sos = await wb.next((x) => x.t === 'status' && x.status === 'sos');
  assert.equal(sos.id, a.id);
  assert.equal(sos.position.lat, -33.5);
  assert.equal(sos.position.lng, 150.5);
  assert.equal(sos.note, undefined);
  await settle();
  assert.equal(sender.of('sos').length, 1);
  assert.deepEqual(sender.of('sos')[0].targets.map((x) => x.token), ['fcm-token-for-b-0001']);
  assert.equal(await wc.quietly((m) => m.t === 'status'), 0, 'outsiders hear nothing');

  wa.send({ t: 'convoy.status', status: 'dancing' });
  assert.equal((await wa.next((m) => m.t === 'error')).error, 'status must be stopping, moving or sos');
  wc.send({ t: 'convoy.status', status: 'moving' });
  assert.equal((await wc.next((m) => m.t === 'error')).error, 'You are not in a convoy');
  wa.ws.close(); wb.ws.close(); wc.ws.close();
});

test('push: devices register/unregister; follow, comment, like (throttled), hazard-when-offline; invalid tokens are dropped', async () => {
  const d = await testServer({ push: sender });
  const [x, y, z] = [await d.signup('px'), await d.signup('py'), await d.signup('pz')];
  await d.req('PATCH', '/v1/me', { token: x.token, body: { handle: 'push_x' } });
  await d.req('PATCH', '/v1/me', { token: y.token, body: { handle: 'push_y' } });
  assert.equal((await d.req('POST', '/v1/me/devices', { token: x.token, body: { platform: 'ios', token: 'apns-token-x-0001' } })).status, 200);
  assert.equal((await d.req('POST', '/v1/me/devices', { token: x.token, body: { platform: 'ios', token: 'apns-token-x-0001' } })).status, 200);
  assert.equal((await d.req('POST', '/v1/me/devices', { token: x.token, body: { platform: 'web', token: 'apns-token-x-0001' } })).status, 400);
  assert.equal((await d.req('POST', '/v1/me/devices', { body: { platform: 'ios', token: 'apns-token-x-0001' } })).status, 401);
  assert.equal((await d.database.db.selectFrom('device_tokens').selectAll().where('user_id', '=', x.id).execute()).length, 1);
  await d.req('POST', '/v1/me/devices', { token: z.token, body: { platform: 'android', token: 'fcm-token-z-0001' } });
  await d.req('POST', '/v1/me/devices', { token: y.token, body: { platform: 'android', token: 'apns-token-x-0001' } }); // token moves to y
  assert.equal((await d.database.db.selectFrom('device_tokens').select('user_id').where('token', '=', 'apns-token-x-0001').executeTakeFirstOrThrow()).user_id, y.id);
  await d.req('POST', '/v1/me/devices', { token: x.token, body: { platform: 'ios', token: 'apns-token-x-0001' } }); // and back
  assert.equal((await d.req('DELETE', '/v1/me/devices', { token: y.token, body: { token: 'apns-token-x-0001' } })).status, 200);
  assert.equal((await d.database.db.selectFrom('device_tokens').select('user_id').where('token', '=', 'apns-token-x-0001').executeTakeFirst())?.user_id, x.id, 'cannot delete someone else token');

  sender.sent.length = 0;
  // follow
  await d.req('POST', '/v1/users/push_x/follow', { token: y.token });
  await settle();
  assert.equal(sender.of('follow').length, 1);
  assert.match(sender.of('follow')[0].payload.body, /started following you/);
  await d.req('POST', '/v1/users/push_x/follow', { token: y.token });
  await settle();
  assert.equal(sender.of('follow').length, 1, 'a repeat follow is not news');
  // private account: request
  await d.req('PATCH', '/v1/me', { token: x.token, body: { isPrivate: true } });
  await d.req('POST', '/v1/users/push_x/follow', { token: z.token });
  await settle();
  assert.equal(sender.of('follow_request').length, 1);

  // comment and like on x's post
  const post = (await d.req('POST', '/v1/posts', { token: x.token, body: { kind: 'text', body: 'hi' } })).body;
  await d.req('POST', `/v1/posts/${post.id}/comments`, { token: x.token, body: { body: 'self' } });
  await d.req('POST', `/v1/posts/${post.id}/comments`, { token: y.token, body: { body: 'nice one' } });
  await settle();
  assert.equal(sender.of('comment').length, 1, 'not for my own comment');
  assert.equal(sender.of('comment')[0].payload.body, 'nice one');
  await d.req('POST', `/v1/posts/${post.id}/like`, { token: y.token });
  await d.req('POST', `/v1/posts/${post.id}/like`, { token: y.token });
  await d.req('POST', `/v1/posts/${post.id}/like`, { token: z.token });
  await settle();
  assert.equal(sender.of('like').length, 1, 'repeat and bursts are throttled to one');

  // a block silences push
  await d.req('POST', '/v1/users/push_y/block', { token: x.token });
  await d.req('PATCH', '/v1/me', { token: x.token, body: { isPrivate: false } });
  sender.sent.length = 0;
  await d.req('POST', `/v1/posts/${post.id}/comments`, { token: z.token, body: { body: 'z here' } });
  await settle();
  assert.equal(sender.of('comment').length, 1);

  // dead tokens are forgotten
  sender.invalid = ['apns-token-x-0001'];
  await d.req('POST', `/v1/posts/${post.id}/comments`, { token: z.token, body: { body: 'again' } });
  await settle();
  sender.invalid = [];
  assert.equal((await d.database.db.selectFrom('device_tokens').select('token').where('token', '=', 'apns-token-x-0001').execute()).length, 0);

  // hazard from a convoy member reaches only members with no live socket
  await d.req('POST', '/v1/me/devices', { token: z.token, body: { platform: 'android', token: 'fcm-token-z-0001' } });
  const sock = (s: Session) => new WebSocket(`${d.wsBase}/ws`, ['convoyage.v1', `bearer.${s.token}`]);
  const wx = await connected(sock(x));
  wx.send({ t: 'convoy.create', name: 'H' });
  const code = (await wx.next((m) => m.t === 'room')).room.code;
  const wz = await connected(sock(z));
  wz.send({ t: 'convoy.join', code });
  await wz.next((m) => m.t === 'room');
  wz.ws.close();
  await wx.next((m) => m.t === 'room' && m.room.members.some((u: any) => u.id === z.id && !u.online));
  sender.sent.length = 0;
  await d.req('POST', '/v1/hazards', { token: x.token, body: { kind: 'police', lat: -33.5, lng: 150.5 } });
  await settle();
  assert.equal(sender.of('hazard').length, 1);
  assert.deepEqual(sender.of('hazard')[0].targets.map((v) => v.token), ['fcm-token-z-0001']);
  wx.ws.close();
  await d.close();
});

test('notifier: like throttle uses its own clock (one a minute per owner)', async () => {
  const s = new FakeSender();
  let now = 1_000_000;
  const n = new Notifier(t.database.db, s, () => now);
  const post = (await t.req('POST', '/v1/posts', { token: a.token, body: { kind: 'text', body: 'throttle me' } })).body;
  await t.req('POST', '/v1/me/devices', { token: a.token, body: { platform: 'android', token: 'fcm-token-for-a-0001' } });
  n.like(post.id, b.id); n.like(post.id, c.id);
  await n.idle();
  assert.equal(s.sent.length, 1);
  now += 61_000;
  n.like(post.id, c.id);
  await n.idle();
  assert.equal(s.sent.length, 2);
});

test('guest: short-lived session, can join a convoy and get a voice token, 403 on social/garage/ride writes', async () => {
  const res = await t.req('POST', '/auth/guest', { body: { name: ' Pillion Pete ' } });
  assert.equal(res.status, 200, JSON.stringify(res.body));
  assert.equal(res.body.refreshToken, undefined);
  assert.equal(res.body.expiresIn, 86400);
  assert.equal(res.body.user.kind, 'guest');
  assert.equal(res.body.user.displayName, 'Pillion Pete');
  const token = res.body.accessToken;
  const row = await t.database.db.selectFrom('users').selectAll().where('id', '=', res.body.user.id).executeTakeFirstOrThrow();
  assert.equal(row.kind, 'guest');
  assert.equal(row.email, null);
  assert.ok(Math.abs(new Date(row.expires_at!).getTime() - Date.now() - 86_400_000) < 5000);
  const { payload } = await jwtVerify(token, new TextEncoder().encode(t.config.jwtSecret));
  assert.ok((payload.exp as number) - (payload.iat as number) === 86400);

  const me = await t.req('GET', '/v1/me', { token });
  assert.equal(me.status, 200);
  assert.equal(me.body.kind, 'guest');
  assert.equal(me.body.email, null);
  assert.equal(me.body.displayName, 'Pillion Pete');
  assert.equal((await t.req('GET', '/v1/feed', { token })).status, 200, 'reads are fine');

  const g = { token };
  const vid = (await t.req('POST', '/v1/vehicles', { token: a.token, body: { kind: 'car', make: 'X', model: 'Y' } })).body.id;
  const post = (await t.req('POST', '/v1/posts', { token: a.token, body: { kind: 'text', body: 'for guests' } })).body.id;
  for (const [method, path, body] of [
    ['POST', '/v1/vehicles', { kind: 'car', make: 'X', model: 'Y' }], ['PATCH', `/v1/vehicles/${vid}`, { make: 'Z' }], ['POST', '/v1/rides', {}],
    ['POST', '/v1/posts', { kind: 'text', body: 'hi' }], ['POST', `/v1/posts/${post}/like`, undefined], ['POST', `/v1/posts/${post}/comments`, { body: 'hi' }],
    ['POST', '/v1/users/wave_a/follow', undefined], ['PATCH', '/v1/me', { bio: 'x' }], ['DELETE', '/v1/me', undefined], ['POST', '/v1/users/wave_a/block', undefined],
    ['POST', '/v1/reports', { targetType: 'post', targetId: post, reason: 'spam' }],
  ] as const) {
    const r = await t.req(method, path, { ...g, body });
    assert.equal(r.status, 403, `${method} ${path} -> ${r.status}`);
  }

  // convoys over the socket
  const wg = await connected(wsFor({ ...a, token }));
  const wa = await connected(wsFor(a));
  wa.send({ t: 'convoy.create', name: 'Guests welcome' });
  const code = (await wa.next((m) => m.t === 'room')).room.code;
  wg.send({ t: 'convoy.join', code });
  const room = (await wg.next((m) => m.t === 'room')).room;
  assert.ok(room.members.some((m: any) => m.id === res.body.user.id && m.username === 'Pillion Pete'));
  assert.equal((await t.req('POST', '/v1/voice/token', { token, body: { convoyCode: code } })).status, 200);
  assert.equal((await t.req('POST', '/v1/hazards', { token, body: { kind: 'hazard', lat: -33.2, lng: 151.2 } })).status, 201);
  assert.equal((await t.req('POST', '/v1/me/devices', { token, body: { platform: 'ios', token: 'apns-guest-token-01' } })).status, 200);
  wg.ws.close(); wa.ws.close();

  // expiry: the token stops working and the socket is refused
  await t.database.db.updateTable('users').set({ expires_at: new Date(Date.now() - 1000) }).where('id', '=', res.body.user.id).execute();
  assert.equal((await t.req('GET', '/v1/me', { token })).status, 401);
  assert.equal(await refusal(new WebSocket(`${t.wsBase}/ws`, ['convoyage.v1', `bearer.${token}`])), 401);
  assert.equal((await t.req('POST', '/auth/guest', { body: { name: '' } })).status, 400);
});

test('guest: sign-up is rate limited per IP', async () => {
  const d = await testServer({ env: { RATE_LIMIT_DISABLED: '0', RATE_LIMIT_AUTH_MAX: '4' } }); // strict limit: 2 a minute
  assert.equal((await d.req('POST', '/auth/guest', { body: { name: 'one' } })).status, 200);
  assert.equal((await d.req('POST', '/auth/guest', { body: { name: 'two' } })).status, 200);
  assert.equal((await d.req('POST', '/auth/guest', { body: { name: 'three' } })).status, 429);
  await d.close();
});
