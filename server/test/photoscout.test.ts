import test, { after, before } from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { WebSocket } from 'ws';
import { loadConfig, parseTokenKey } from '../src/config.js';
import { thin } from '../src/photoscout/cruiseSource.js';
import { open, seal } from '../src/photoscout/seal.js';
import { FakePhotoScout, type FakeUser } from './fakePhotoScout.js';
import { makeJpeg, testServer, type Reply, type Session, type TestServer } from './apiHelpers.js';
import { connected } from './socketHelpers.js';

const KEY = '11'.repeat(32);
const env = (url: string) => ({ PHOTOSCOUT_URL: url, PUBLIC_BASE_URL: 'https://convoy.test', PHOTOSCOUT_TOKEN_KEY: KEY });
const EVENT = '6f1c2d3e-0000-4000-8000-000000000001';

const fake = new FakePhotoScout();
let t: TestServer;
let off: TestServer;
let org: Session, mate: Session, loner: Session;
const orgPs: FakeUser = { id: 'ps-org', username: 'Organiser' };
const matePs: FakeUser = { id: 'ps-mate', username: 'Mate' };

before(async () => {
  await fake.start();
  t = await testServer({ env: env(fake.url) });
  off = await testServer();
  [org, mate, loner] = [await t.signup('org'), await t.signup('mate'), await t.signup('loner')];
});
after(async () => { await t.close(); await off.close(); await fake.stop(); });

/** Run the whole link flow as the browser would. Returns where Convoyage sent the browser at the end. */
async function link(s: Session, user: FakeUser, decision: 'allow' | 'deny' = 'allow') {
  const c = await t.req('GET', '/v1/photoscout/connect', { token: s.token });
  assert.equal(c.status, 200, JSON.stringify(c.body));
  const back = new URL(fake.consent(c.body.authorizeUrl, user, decision));
  const res = await fetch(t.base + back.pathname + back.search, { redirect: 'manual' });
  return { authorizeUrl: new URL(c.body.authorizeUrl), status: res.status, location: res.headers.get('location')!, back };
}
const get = (s: Session, p: string) => t.req('GET', p, { token: s.token });
const suggest = (s: { token: string }, body: unknown) => t.req('POST', '/v1/photoscout/suggestions', { token: s.token, body });

/** Multipart upload of a suggestion photo, the way an app sends it. */
async function suggestPhoto(s: { token: string }, id: string, data: Buffer, field = 'file'): Promise<Reply> {
  const form = new FormData();
  form.append(field, new Blob([new Uint8Array(data)], { type: 'image/jpeg' }), 'x.jpg');
  const res = await fetch(`${t.base}/v1/photoscout/suggestions/${id}/photo`, { method: 'POST', headers: { authorization: `Bearer ${s.token}` }, body: form });
  const text = await res.text();
  let body: any = text;
  try { body = text ? JSON.parse(text) : null; } catch { /* not JSON */ }
  return { status: res.status, body, headers: res.headers };
}

test('seal: round trip, bound to its place, tamper-evident, key formats', () => {
  const key = Buffer.from(KEY, 'hex');
  const sealed = seal(key, 'psat_secret', 'a:1');
  assert.ok(!sealed.includes('psat_secret'));
  assert.equal(open(key, sealed, 'a:1'), 'psat_secret');
  assert.equal(open(key, sealed, 'a:2'), null, 'another binding');
  assert.equal(open(Buffer.alloc(32, 2), sealed, 'a:1'), null, 'another key');
  const parts = sealed.split('.');
  parts[3] = Buffer.from('xxxxxxxxxxx').toString('base64url');
  assert.equal(open(key, parts.join('.'), 'a:1'), null, 'tampered');
  assert.equal(open(key, 'garbage', 'a:1'), null);
  assert.equal(parseTokenKey(KEY).length, 32);
  assert.equal(parseTokenKey(key.toString('base64')).length, 32);
  assert.throws(() => parseTokenKey('short'));
});

test('config: PhotoScout needs URL, public base and key together; absent means off', () => {
  const base = { JWT_SECRET: 't'.repeat(40), DATABASE_URL: 'pglite:memory' };
  assert.equal(loadConfig(base as NodeJS.ProcessEnv).photoscout, null);
  assert.throws(() => loadConfig({ ...base, PHOTOSCOUT_URL: 'http://x' } as NodeJS.ProcessEnv), /PUBLIC_BASE_URL/);
  assert.throws(() => loadConfig({ ...base, PHOTOSCOUT_URL: 'http://x', PUBLIC_BASE_URL: 'http://y' } as NodeJS.ProcessEnv), /PHOTOSCOUT_TOKEN_KEY/);
  const c = loadConfig({ ...base, ...env('http://x/') } as NodeJS.ProcessEnv).photoscout!;
  assert.deepEqual([c.url, c.clientId, c.clientSecret], ['http://x', 'convoyage', null]);
});

test('thin keeps the ends and the limit', () => {
  // A zigzag: every vertex matters, so the budget is the limit. A straight line needs only its ends.
  const pts = Array.from({ length: 100 }, (_, i) => [149 + i * 0.001, -33 + (i % 2) * 0.001] as [number, number]);
  const out = thin(pts, 25);
  assert.equal(out.length, 25);
  assert.equal(thin(Array.from({ length: 100 }, (_, i) => [149 + i * 0.001, -33] as [number, number]), 25).length, 2);
  assert.deepEqual([out[0], out.at(-1)], [pts[0], pts[99]]);
});

test('not configured: status says so, everything else is 503 photoscout_not_configured', async () => {
  const s = await off.signup('off');
  const st = await off.req('GET', '/v1/photoscout/status', { token: s.token });
  assert.deepEqual(st.body, { configured: false, linked: false, username: null, scope: '', linkedAt: null, canSuggest: false });
  for (const [m, p] of [['GET', '/v1/photoscout/connect'], ['GET', '/v1/photoscout/events'], ['GET', '/v1/photoscout/spots?bbox=-34,149,-33,150'], ['DELETE', '/v1/photoscout/link']] as const) {
    const r = await off.req(m, p, { token: s.token });
    assert.equal(r.status, 503, p);
    assert.equal(r.body.error, 'photoscout_not_configured');
  }
  const cb = await fetch(off.base + '/v1/photoscout/callback?code=x&state=y', { redirect: 'manual' });
  assert.equal(cb.headers.get('location'), 'convoyage://photoscout/linked?error=not_configured');
});

test('unlinked: every proxy is 409 photoscout_not_linked; guests cannot link', async () => {
  for (const [m, p, body] of [
    ['GET', '/v1/photoscout/events'], ['GET', '/v1/photoscout/spots?bbox=-34,149,-33,150'],
    ['POST', `/v1/photoscout/events/${EVENT}/rsvp`, {}], ['POST', '/v1/photoscout/checkins', { spotId: 's1' }],
    ['POST', '/v1/photoscout/suggestions', { name: 'x', lat: 1, lng: 2 }],
  ] as [string, string, unknown?][]) {
    const r = await t.req(m, p, { token: loner.token, body });
    assert.equal(r.status, 409, p);
    assert.equal(r.body.error, 'photoscout_not_linked');
  }
  assert.deepEqual((await get(loner, '/v1/photoscout/status')).body, { configured: true, linked: false, username: null, scope: '', linkedAt: null, canSuggest: false });
  assert.equal((await t.req('GET', '/v1/photoscout/events')).status, 401);
  const guest = await t.req('POST', '/auth/guest', { body: { name: 'Pete' } });
  assert.equal((await t.req('GET', '/v1/photoscout/connect', { token: guest.body.accessToken })).status, 403);
});

test('link flow: PKCE S256, scopes, redirect uri, state checks, tokens sealed at rest', async () => {
  const r = await link(org, orgPs);
  const q = r.authorizeUrl.searchParams;
  assert.equal(r.authorizeUrl.origin + r.authorizeUrl.pathname, fake.url + '/oauth/authorize');
  assert.equal(q.get('client_id'), 'convoyage');
  assert.equal(q.get('redirect_uri'), 'https://convoy.test/v1/photoscout/callback');
  assert.equal(q.get('scope'), 'events:read events:rsvp spots:read routes:read checkins:write spots:suggest');
  assert.equal(q.get('code_challenge_method'), 'S256');
  assert.equal(r.status, 302);
  assert.equal(r.location, 'convoyage://photoscout/linked');
  // the code was exchanged with the verifier that matches the challenge
  const ex = fake.tokenCalls.at(-1)!;
  assert.equal(createHash('sha256').update(ex.code_verifier).digest('base64url'), q.get('code_challenge'));
  assert.equal(ex.client_secret, undefined);

  const st = (await get(org, '/v1/photoscout/status')).body;
  assert.equal(st.linked, true);
  assert.equal(st.username, 'Organiser');
  assert.match(st.scope, /events:read/);
  assert.equal(st.canSuggest, true, 'a fresh link carries spots:suggest');

  // nothing readable in the database
  const row = await t.database.db.selectFrom('photoscout_links').selectAll().where('user_id', '=', org.id).executeTakeFirstOrThrow();
  const dump = JSON.stringify(row);
  assert.ok(!dump.includes('psat_') && !dump.includes('psrt_'));
  const key = Buffer.from(KEY, 'hex');
  assert.match(open(key, row.access_token_enc, `photoscout:${org.id}:access`)!, /^psat_/);
  assert.match(open(key, row.refresh_token_enc, `photoscout:${org.id}:refresh`)!, /^psrt_/);
  assert.equal(open(key, row.access_token_enc, `photoscout:${mate.id}:access`), null, 'not valid under another user');
  assert.equal(await t.database.db.selectFrom('photoscout_oauth_states').selectAll().execute().then((x) => x.length), 0, 'state spent');

  // state: replay, unknown, missing
  const replay = await fetch(t.base + r.back.pathname + r.back.search, { redirect: 'manual' });
  assert.equal(replay.headers.get('location'), 'convoyage://photoscout/linked?error=invalid_state');
  const bogus = await fetch(t.base + '/v1/photoscout/callback?code=x&state=nope', { redirect: 'manual' });
  assert.equal(bogus.headers.get('location'), 'convoyage://photoscout/linked?error=invalid_state');
  const none = await fetch(t.base + '/v1/photoscout/callback?code=x', { redirect: 'manual' });
  assert.equal(none.headers.get('location'), 'convoyage://photoscout/linked?error=invalid_state');
});

test('link flow: denial and a rejected exchange come back as ?error=, and a state is tied to its user', async () => {
  const denied = await link(loner, matePs, 'deny');
  assert.equal(denied.location, 'convoyage://photoscout/linked?error=access_denied');
  assert.equal((await get(loner, '/v1/photoscout/status')).body.linked, false);

  // a code that PhotoScout will not honour (wrong verifier is simulated by a code it never issued)
  const c = await t.req('GET', '/v1/photoscout/connect', { token: loner.token });
  const state = new URL(c.body.authorizeUrl).searchParams.get('state');
  const bad = await fetch(`${t.base}/v1/photoscout/callback?code=psc_unknown&state=${state}`, { redirect: 'manual' });
  assert.equal(bad.headers.get('location'), 'convoyage://photoscout/linked?error=exchange_rejected');
  assert.equal((await get(loner, '/v1/photoscout/status')).body.linked, false);
});

test('events: merged, normalised, filtered by from/to, near adds distance, cruises flagged', async () => {
  fake.events = [{ group: 'g1', title: 'Cars at the Park', description: 'd', startTime: '2099-03-01T09:00:00.000Z', endTime: null, venueName: 'Park', address: 'A', lat: -33.4, lng: 149.5, imageUrl: 'https://i/x.jpg', url: 'https://e/1', going: 3, interested: 2, myRsvp: 'interested', secret: 'x' }];
  fake.communityEvents = [
    { id: EVENT, ownerId: 'ps-org', owner: { username: 'Organiser' }, title: 'Dawn Cruise', description: '', startTime: '2099-02-01T05:00:00.000Z', endTime: '2099-02-01T12:00:00.000Z', venueName: 'Servo', address: '', lat: -33.4, lng: 149.5, going: 5, isGoing: true,
      cruise: { route: { name: 'Loop', type: 'circuit', vertices: [[149.5, -33.4], [149.6, -33.5], [149.7, -33.4]] }, meet: { lat: -33.4, lng: 149.5, note: 'Servo' }, rollOut: '2099-02-01T06:00:00+10:00' } },
    { id: 'old', ownerId: 'x', title: 'Gone', startTime: '2001-01-01T00:00:00Z', endTime: null, lat: null, lng: null, going: 0, isGoing: false, cruise: null },
  ];
  const all = (await get(org, '/v1/photoscout/events')).body;
  assert.deepEqual(all.map((e: any) => e.title), ['Dawn Cruise', 'Cars at the Park']);
  assert.deepEqual(all[0].cruise, { routeName: 'Loop', meet: { lat: -33.4, lng: 149.5, note: 'Servo' }, rollOut: '2099-02-01T06:00:00+10:00' });
  assert.equal(all[0].source, 'community');
  assert.equal(all[0].myRsvp, 'going');
  assert.equal(all[1].source, 'scout');
  assert.equal(all[1].myRsvp, 'interested');
  assert.equal(all[1].interested, 2);
  assert.equal('secret' in all[1], false);
  assert.equal(all[1].cruise, null);
  const ranged = (await get(org, '/v1/photoscout/events?to=2099-02-15T00:00:00Z')).body;
  assert.deepEqual(ranged.map((e: any) => e.id), [EVENT]);
  const near = (await get(org, '/v1/photoscout/events?near=-33.4,149.5&radiusKm=5')).body;
  assert.equal(near.length, 2);
  assert.equal(near[0].distanceKm, 0);
  assert.equal((await get(org, '/v1/photoscout/events?near=nope')).status, 400);
  // the proxy spoke as the user, with a bearer token
  assert.ok(fake.log.some((l) => l.path === '/api/events' && /^Bearer psat_/.test(l.auth ?? '')));
});

test('spots: bbox proxied, normalised with a Convoyage photo URL; photo bytes proxied, non-images refused', async () => {
  fake.spots = [{ id: 'sp1', name: 'Lookout', notes: 'n', lat: -33.4, lng: 149.5, tags: ['view'], facingDeg: 90, fovDeg: 60, goodTimes: { phases: ['sunset'], months: [3], days: 'any', conditions: [], eventKeywords: [], avoid: '', notes: 'calm' }, photoCount: 2, coverThumbUrl: '/api/photos/ph1/thumb', visibility: 'public' }];
  const r = await get(org, '/v1/photoscout/spots?bbox=-34,149,-33,150');
  assert.equal(r.status, 200);
  assert.equal(r.body[0].photo.thumbUrl, '/v1/photoscout/photos/ph1/thumb');
  assert.deepEqual(r.body[0].goodTimes.phases, ['sunset']);
  assert.equal(r.body[0].visibility, undefined);
  assert.ok(fake.log.some((l) => l.path === '/api/spots?bbox=-34,149,-33,150'));
  assert.equal((await get(org, '/v1/photoscout/spots?bbox=1,2,3')).status, 400);
  assert.equal((await get(org, '/v1/photoscout/spots')).status, 400);

  const img = await fetch(`${t.base}/v1/photoscout/photos/ph1/thumb`, { headers: { authorization: `Bearer ${org.token}` } });
  assert.equal(img.headers.get('content-type'), 'image/jpeg');
  assert.equal(await img.text(), 'jpeg-ph1-thumb');
  assert.equal((await get(org, '/v1/photoscout/photos/html/file')).status, 502);
  assert.equal((await t.req('GET', '/v1/photoscout/photos/ph1/thumb')).status, 401);
});

test('rsvp (community and crawled, set and clear) and check-ins', async () => {
  const going = await t.req('POST', `/v1/photoscout/events/${EVENT}/rsvp`, { token: org.token, body: {} });
  assert.deepEqual(going.body, { eventId: EVENT, source: 'community', going: 4, interested: 0, myRsvp: 'going' });
  const cleared = await t.req('POST', `/v1/photoscout/events/${EVENT}/rsvp`, { token: org.token, body: { going: false } });
  assert.equal(cleared.body.myRsvp, null);
  assert.equal((await t.req('POST', `/v1/photoscout/events/${EVENT}/rsvp`, { token: org.token, body: { status: 'interested' } })).status, 400);
  const maybe = await t.req('POST', '/v1/photoscout/events/g1/rsvp', { token: org.token, body: { status: 'interested' } });
  assert.deepEqual([maybe.body.source, maybe.body.myRsvp], ['scout', 'interested']);
  assert.deepEqual(fake.rsvps.map((x) => [x.method, x.path]), [['PUT', `/api/community-events/${EVENT}/rsvp`], ['DELETE', `/api/community-events/${EVENT}/rsvp`], ['PUT', '/api/events/g1/rsvp']]);

  const ci = await t.req('POST', '/v1/photoscout/checkins', { token: org.token, body: { spotId: 'sp1', body: 'here' } });
  assert.equal(ci.status, 201);
  assert.deepEqual([ci.body.id, ci.body.placeName, ci.body.body], ['post-1', 'Mount Panorama', 'here']);
  assert.deepEqual(fake.checkins[0], { spotId: 'sp1', body: 'here' });
  assert.equal((await t.req('POST', '/v1/photoscout/checkins', { token: org.token, body: { spotId: 'a', eventId: 'b' } })).status, 400);
  assert.equal((await t.req('POST', '/v1/photoscout/checkins', { token: org.token, body: { lat: 1 } })).status, 400);
  const missing = await t.req('POST', '/v1/photoscout/checkins', { token: org.token, body: { spotId: 'private' } });
  assert.deepEqual([missing.status, missing.body.error], [404, 'photoscout_not_found']);
});

test('tokens refresh: before expiry, on a 401, once under concurrency; a revoked link becomes not linked', async () => {
  const before = fake.refreshCalls();
  // 1. stored expiry passed: refreshed first, and the new pair is stored (rotated)
  await t.database.db.updateTable('photoscout_links').set({ access_expires_at: new Date(Date.now() - 3600_000) }).where('user_id', '=', org.id).execute();
  assert.equal((await get(org, '/v1/photoscout/events')).status, 200);
  assert.equal(fake.refreshCalls(), before + 1);
  // 2. PhotoScout forgot the access token early: 401, refresh, retry
  fake.expireAccessTokens();
  assert.equal((await get(org, '/v1/photoscout/events')).status, 200);
  assert.equal(fake.refreshCalls(), before + 2);
  // 3. five at once with an expired token: one refresh (a second use of a rotated token would end the link)
  await t.database.db.updateTable('photoscout_links').set({ access_expires_at: new Date(Date.now() - 3600_000) }).where('user_id', '=', org.id).execute();
  const rs = await Promise.all([1, 2, 3, 4, 5].map(() => get(org, '/v1/photoscout/spots?bbox=-34,149,-33,150')));
  assert.deepEqual(rs.map((r) => r.status), [200, 200, 200, 200, 200]);
  assert.equal(fake.refreshCalls(), before + 3);
  assert.equal((await get(org, '/v1/photoscout/status')).body.linked, true);

  // 4. disconnected on PhotoScout's side
  await link(loner, matePs);
  assert.equal((await get(loner, '/v1/photoscout/status')).body.linked, true);
  fake.revokeAll();
  const dead = await get(loner, '/v1/photoscout/events');
  assert.deepEqual([dead.status, dead.body.error], [409, 'photoscout_not_linked']);
  assert.equal((await get(loner, '/v1/photoscout/status')).body.linked, false);
  await link(org, orgPs); // org is linked again afterwards, fresh grant
});

test('unlink revokes at PhotoScout and deletes the tokens; relinking works', async () => {
  await link(mate, matePs);
  const rt = fake.tokenCalls.length;
  const del = await t.req('DELETE', '/v1/photoscout/link', { token: mate.token });
  assert.equal(del.status, 200);
  assert.equal(fake.revoked.length, 1);
  assert.match(fake.revoked[0], /^psrt_/);
  assert.equal((await t.database.db.selectFrom('photoscout_links').selectAll().where('user_id', '=', mate.id).execute()).length, 0);
  assert.equal((await get(mate, '/v1/photoscout/events')).status, 409);
  assert.equal((await t.req('DELETE', '/v1/photoscout/link', { token: mate.token })).status, 200, 'idempotent');
  assert.equal(fake.tokenCalls.length, rt);
  await link(mate, matePs);
  assert.equal((await get(mate, '/v1/photoscout/status')).body.linked, true);
});

test('cruise convoy: opened by the organiser from PhotoScout data, joined by linked members only', async () => {
  const wsFor = (s: Session) => new WebSocket(`${t.wsBase}/ws`, ['convoyage.v1', `bearer.${s.token}`]);
  const o = await connected(wsFor(org));
  const m = await connected(wsFor(mate));
  const n = await connected(wsFor(loner));

  m.send({ t: 'convoy.cruise', eventId: EVENT });
  assert.match((await m.next((x) => x.t === 'error')).error, /organiser has not started/);
  n.send({ t: 'convoy.cruise', eventId: EVENT });
  assert.match((await n.next((x) => x.t === 'error')).error, /Link your PhotoScout/);
  o.send({ t: 'convoy.cruise', eventId: 'no-such-event' });
  assert.match((await o.next((x) => x.t === 'error')).error, /No such cruise/);

  o.send({ t: 'convoy.cruise', eventId: EVENT });
  const room = (await o.next((x) => x.t === 'room')).room;
  assert.equal(room.name, 'Dawn Cruise');
  assert.equal(room.eventId, EVENT);
  assert.deepEqual(room.destination, { lat: -33.4, lng: 149.5, label: 'Servo' });
  assert.equal(room.route.name, 'Loop');
  assert.deepEqual(room.route.stops.at(-1), room.route.stops[0], 'a circuit closes');
  assert.equal(room.leader, org.id);

  m.send({ t: 'convoy.cruise', eventId: EVENT });
  const joined = (await m.next((x) => x.t === 'room')).room;
  assert.equal(joined.code, room.code);
  assert.equal(joined.members.length, 2);
  o.ws.close(); m.ws.close(); n.ws.close();
});

// ---- suggesting a spot (scope spots:suggest) ----------------------------------------------------------------------

test('suggest a spot: 201 waiting, tags passed through, PhotoScout bookkeeping hidden, and it is not a spot yet', async () => {
  const before = fake.suggestions.length;
  const r = await suggest(org, { name: 'Bombo Quarry', notes: 'Blowhole at dawn, park at the gate', lat: -35.3241, lng: 150.8362, tags: ['coast', 'sunrise'] });
  assert.equal(r.status, 201, JSON.stringify(r.body));
  const made = fake.created.at(-1)!;
  assert.deepEqual(fake.suggestions.at(-1), { name: 'Bombo Quarry', notes: 'Blowhole at dawn, park at the gate', lat: -35.3241, lng: 150.8362, tags: ['coast', 'sunrise'] }, 'the body PhotoScout got');
  assert.equal(fake.suggestions.length, before + 1);
  const [id, name, notes, tags, photo, status] = [r.body.id, r.body.name, r.body.notes, r.body.tags, r.body.photo, r.body.status] as [string, string, string, string[], unknown, string];
  assert.equal(id, made.id);
  assert.deepEqual([name, notes, tags, photo, status], ['Bombo Quarry', 'Blowhole at dawn, park at the gate', ['coast', 'sunrise'], null, 'waiting']);
  assert.equal(typeof r.body.createdAt, 'string');
  // The moderator's side of PhotoScout stays there.
  for (const k of ['suggestedBy', 'outcome', 'resolvedAt', 'spotId']) assert.equal(k in r.body, false, `leaked ${k}`);
  // A suggestion is not on the map until PhotoScout approves it: the spot list is still only PhotoScout's spots.
  const listed = (await get(org, '/v1/photoscout/spots?bbox=-36,150,-35,151')).body;
  assert.equal(listed.some((s: any) => s.name === 'Bombo Quarry'), false);

  // Required fields and limits, checked before anything is proxied.
  const count = fake.suggestions.length;
  const bad: unknown[] = [
    { lat: 1, lng: 2 },                                  // no name
    { name: '', lat: 1, lng: 2 },
    { name: 'x'.repeat(121), lat: 1, lng: 2 },
    { name: 'x', notes: 'n'.repeat(1001), lat: 1, lng: 2 },
    { name: 'x', lat: 91, lng: 2 },
    { name: 'x', lat: 1, lng: 181 },
    { name: 'x', lng: 2 },                               // no lat
    { name: 'x', lat: 1, lng: 2, tags: Array.from({ length: 9 }, (_, i) => `t${i}`) },
    { name: 'x', lat: 1, lng: 2, tags: ['fine', 't'.repeat(31)] },
  ];
  for (const body of bad) assert.equal((await suggest(org, body)).status, 400, JSON.stringify(body));
  assert.equal((await t.req('POST', '/v1/photoscout/suggestions', { body: { name: 'x', lat: 1, lng: 2 } })).status, 401);
  assert.equal(fake.suggestions.length, count, 'no rejected body reached PhotoScout');

  // Optional fields: only a name and a point is enough.
  const minimal = await suggest(org, { name: 'Just a point', lat: -33.4, lng: 149.5 });
  assert.deepEqual([minimal.status, minimal.body.tags, minimal.body.notes, minimal.body.status], [201, [], '', 'waiting']);
});

test('suggest a spot: PhotoScout errors pass through (429 daily cap, 400) and 403 insufficient_scope says relink', async () => {
  const slow = await suggest(org, { name: 'toslow', lat: -33.4, lng: 149.5 });
  assert.deepEqual([slow.status, slow.body.error, slow.body.message], [429, 'photoscout_rejected', 'You can suggest 10 spots a day']);
  const nope = await suggest(org, { name: 'nope', lat: -33.4, lng: 149.5 });
  assert.deepEqual([nope.status, nope.body.error, nope.body.message], [400, 'photoscout_rejected', 'A name is required']);
  const scope = await suggest(org, { name: 'scope', lat: -33.4, lng: 149.5 });
  assert.deepEqual([scope.status, scope.body.error], [403, 'photoscout_insufficient_scope']);
  assert.match(scope.body.message, /relink your PhotoScout account/i);
});

test('suggest a spot: a link from before spots:suggest answers 403 relink without a round trip; relinking fixes it', async () => {
  const old = await t.signup('oldlink');
  await link(old, { id: 'ps-old', username: 'Old' });
  assert.equal((await get(old, '/v1/photoscout/status')).body.canSuggest, true);
  // An older consent: linked, everything else still works, but no spots:suggest.
  await t.database.db.updateTable('photoscout_links').set({ scope: 'events:read events:rsvp spots:read routes:read checkins:write' }).where('user_id', '=', old.id).execute();
  const st = (await get(old, '/v1/photoscout/status')).body;
  assert.deepEqual([st.linked, st.canSuggest], [true, false]);
  assert.equal((await get(old, '/v1/photoscout/events')).status, 200, 'the rest of the link is fine');

  const before = fake.suggestions.length;
  const denied = await suggest(old, { name: 'Should not reach PhotoScout', lat: -33.4, lng: 149.5 });
  assert.deepEqual([denied.status, denied.body.error], [403, 'photoscout_insufficient_scope']);
  assert.match(denied.body.message, /relink your PhotoScout account/i);
  assert.equal(fake.suggestions.length, before, 'known from the stored scope, no call made');

  await link(old, { id: 'ps-old', username: 'Old' });
  assert.equal((await get(old, '/v1/photoscout/status')).body.canSuggest, true);
  assert.equal((await suggest(old, { name: 'After relinking', lat: -33.4, lng: 149.5 })).status, 201);
});

test('suggest a spot: guests and unlinked members are refused like the other routes', async () => {
  const fresh = await t.signup('suggestunlinked');
  const unlinked = await suggest(fresh, { name: 'x', lat: 1, lng: 2 });
  assert.deepEqual([unlinked.status, unlinked.body.error], [409, 'photoscout_not_linked']);
  const guest = await t.req('POST', '/auth/guest', { body: { name: 'Gina' } });
  const asGuest = await suggest({ token: guest.body.accessToken }, { name: 'x', lat: 1, lng: 2 });
  assert.equal(asGuest.status, 403);
  assert.match(asGuest.body.message, /Guests cannot do this/);
});

test('suggest a photo: a JPEG is re-encoded and forwarded as multipart; the suggestion comes back with a photo', async () => {
  const made = (await suggest(org, { name: 'Photo spot', lat: -35.3241, lng: 150.8362 })).body;
  const r = await suggestPhoto(org, made.id, await makeJpeg(80, 60));
  assert.equal(r.status, 201, JSON.stringify(r.body));
  assert.equal(r.body.id, made.id);
  assert.equal(r.body.status, 'waiting');
  assert.equal(r.body.photo.thumbUrl, `${fake.url}/api/suggestions/${made.id}/thumb.jpg`, 'made absolute on PhotoScout\'s origin');
  const sent = fake.suggestionPhotos.at(-1)!;
  assert.equal(sent.id, made.id);
  assert.match(sent.contentType, /^multipart\/form-data; boundary=/);
  assert.match(sent.body, /name="photo"/);
  assert.match(sent.body, /name="thumb"/);
  assert.match(sent.body, /content-type: image\/jpeg/i, 're-encoded as JPEG, not forwarded raw');
  assert.match(sent.body, /name="w"[\s\S]*80/);
  assert.match(sent.body, /name="h"[\s\S]*60/);

  // `photo` is accepted as the field name too (that is what PhotoScout calls it).
  assert.equal((await suggestPhoto(org, made.id, await makeJpeg(40, 30), 'photo')).status, 201);

  const before = fake.suggestionPhotos.length;
  assert.equal((await suggestPhoto(org, made.id, Buffer.from('<?php echo 1; ?>'))).status, 415, 'not an image');
  const heic = Buffer.concat([Buffer.from([0, 0, 0, 0x18]), Buffer.from('ftypheic'), Buffer.alloc(64)]);
  assert.equal((await suggestPhoto(org, made.id, heic)).status, 415, 'HEIC is refused');
  assert.equal((await suggestPhoto(org, made.id, Buffer.alloc(0))).status, 400, 'empty file');
  assert.equal((await suggestPhoto(org, made.id, (await makeJpeg()).subarray(0, 120))).status, 400, 'truncated image');
  assert.equal(fake.suggestionPhotos.length, before, 'nothing bad reached PhotoScout');

  // Field name / content type are checked the way POST /v1/media checks them.
  assert.equal((await suggestPhoto(org, made.id, await makeJpeg(), 'avatar')).status, 400);
  assert.equal((await t.req('POST', `/v1/photoscout/suggestions/${made.id}/photo`, { token: org.token, body: {} })).status, 400);
  assert.equal((await suggestPhoto(org, 'not-valid-id!', await makeJpeg())).status, 400);
});

test('suggest a photo: settled suggestions, unlinked members and insufficient_scope map to their errors', async () => {
  const settled = await suggestPhoto(org, 'settled', await makeJpeg());
  assert.deepEqual([settled.status, settled.body.error, settled.body.message], [409, 'photoscout_rejected', 'This suggestion has already been settled']);

  const fresh = await t.signup('photounlinked');
  const unlinked = await suggestPhoto(fresh, 'sug-1', await makeJpeg());
  assert.deepEqual([unlinked.status, unlinked.body.error], [409, 'photoscout_not_linked']);

  fake.suggestPhotoScopeDenied = true;
  try {
    const denied = await suggestPhoto(org, 'sug-1', await makeJpeg());
    assert.deepEqual([denied.status, denied.body.error], [403, 'photoscout_insufficient_scope']);
    assert.match(denied.body.message, /relink your PhotoScout account/i);
  } finally { fake.suggestPhotoScopeDenied = false; }
  assert.equal((await suggestPhoto(org, 'sug-1', await makeJpeg())).status, 201, 'works again');
});

