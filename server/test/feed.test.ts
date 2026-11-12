import test, { after, before } from 'node:test';
import assert from 'node:assert/strict';
import { encodePolyline } from '../src/api/polyline.js';
import { makeJpeg, testServer, upload, type Session, type TestServer } from './apiHelpers.js';

let t: TestServer;
let a: Session; // author, public
let b: Session; // viewer
let p: Session; // private account
let x: Session; // stranger
before(async () => {
  t = await testServer();
  [a, b, p, x] = [await t.signup('fa'), await t.signup('fb'), await t.signup('fp'), await t.signup('fx')];
  for (const [s, h] of [[a, 'feed_a'], [b, 'feed_b'], [p, 'feed_p'], [x, 'feed_x']] as const) {
    await t.req('PATCH', '/v1/me', { token: s.token, body: { handle: h } });
  }
  await t.req('PATCH', '/v1/me', { token: p.token, body: { isPrivate: true } });
});
after(() => t.close());

const feed = async (s: Session, query = '') => (await t.req('GET', `/v1/feed${query}`, { token: s.token })).body;
const bodies = (page: { items: any[] }) => page.items.map((i) => i.body);
const text = (s: Session, body: string, extra: object = {}) => t.req('POST', '/v1/posts', { token: s.token, body: { kind: 'text', body, ...extra } });

test('post kinds: validation and what each needs', async () => {
  assert.equal((await t.req('POST', '/v1/posts', { token: a.token, body: { kind: 'text' } })).status, 400);
  assert.equal((await t.req('POST', '/v1/posts', { token: a.token, body: { kind: 'photo' } })).status, 400);
  assert.equal((await t.req('POST', '/v1/posts', { token: a.token, body: { kind: 'ride' } })).status, 400);
  assert.equal((await t.req('POST', '/v1/posts', { token: a.token, body: { kind: 'poll', body: 'x' } })).status, 400);
  assert.equal((await t.req('POST', '/v1/posts', { token: a.token, body: { kind: 'text', body: 'x', rideId: '00000000-0000-4000-8000-000000000000' } })).status, 400);

  // photo post
  const m = (await upload(t, a.token, await makeJpeg())).body;
  const stolen = (await upload(t, b.token, await makeJpeg())).body;
  const ok = await t.req('POST', '/v1/posts', { token: a.token, body: { kind: 'photo', body: 'golden hour', mediaIds: [m.id] } });
  assert.equal(ok.status, 201, JSON.stringify(ok.body));
  assert.equal(ok.body.media[0].id, m.id);
  assert.equal((await t.req('POST', '/v1/posts', { token: a.token, body: { kind: 'photo', mediaIds: [stolen.id] } })).status, 400);

  // ride post: must be finished, not private, and not more public than the ride
  const ride = (await t.req('POST', '/v1/rides', { token: a.token, body: { visibility: 'followers', startLat: -33.87, startLng: 151.21 } })).body;
  assert.equal((await t.req('POST', '/v1/posts', { token: a.token, body: { kind: 'ride', rideId: ride.id } })).status, 409, 'active ride');
  await t.req('POST', `/v1/rides/${ride.id}/finish`, {
    token: a.token, body: { endedAt: new Date().toISOString(), distanceM: 1000, durationS: 100, maxSpeedMps: 20, avgSpeedMps: 10, polyline: encodePolyline([[-33.87, 151.21], [-33.88, 151.22]]) },
  });
  assert.equal((await t.req('POST', '/v1/posts', { token: a.token, body: { kind: 'ride', rideId: ride.id } })).status, 409, 'public post of a followers ride');
  const shared = await t.req('POST', '/v1/posts', { token: a.token, body: { kind: 'ride', rideId: ride.id, visibility: 'followers', body: 'good one' } });
  assert.equal(shared.status, 201, JSON.stringify(shared.body));
  assert.equal(shared.body.ride.id, ride.id);
  assert.equal(shared.body.lat, -33.87, 'defaults to the ride start');
  assert.equal(shared.body.ride.polyline, undefined);
  assert.equal((await t.req('POST', '/v1/posts', { token: b.token, body: { kind: 'ride', rideId: ride.id } })).status, 400, 'not my ride');
});

test('feed: scopes, privacy and blocks', async () => {
  await text(a, 'public from a');
  await text(p, 'private account post');
  await text(a, 'followers only', { visibility: 'followers' });
  await text(x, 'from stranger');
  await text(b, 'b post');

  // everyone: public accounts only, plus my own.
  let seen = bodies(await feed(b));
  assert.ok(seen.includes('public from a') && seen.includes('from stranger'));
  assert.ok(!seen.includes('private account post'), 'private account hidden from non-followers');
  assert.ok(!seen.includes('followers only'));

  // following: just my own posts until I follow someone.
  assert.deepEqual(bodies(await feed(b, '?scope=following')), ['b post']);
  await t.req('POST', '/v1/users/feed_a/follow', { token: b.token });
  seen = bodies(await feed(b, '?scope=following'));
  assert.ok(seen.includes('public from a'));
  assert.ok(seen.includes('followers only'), 'followers-only post reaches followers');
  assert.ok(!seen.includes('from stranger'));
  assert.ok(!bodies(await feed(x)).includes('followers only'));

  // private account: follow request pending -> still hidden; approved -> visible.
  await t.req('POST', '/v1/users/feed_p/follow', { token: b.token });
  assert.ok(!bodies(await feed(b)).includes('private account post'));
  await t.req('POST', `/v1/me/follow-requests/${b.id}/approve`, { token: p.token });
  assert.ok(bodies(await feed(b)).includes('private account post'));
  assert.ok(bodies(await feed(b, '?scope=following')).includes('private account post'));

  // blocks, either direction.
  await t.req('POST', '/v1/users/feed_x/block', { token: b.token });
  assert.ok(!bodies(await feed(b)).includes('from stranger'));
  assert.ok(!bodies(await feed(x)).includes('followers only'));
  await t.req('POST', '/v1/users/feed_b/block', { token: a.token });
  assert.ok(!bodies(await feed(b)).includes('public from a'));
  assert.ok(bodies(await feed(a)).includes('public from a'));
  assert.ok(!bodies(await feed(a)).includes('b post'), 'a no longer sees the b who was blocked');
  await t.req('DELETE', '/v1/users/feed_b/block', { token: a.token });
  await t.req('DELETE', '/v1/users/feed_x/block', { token: b.token });
});

test('feed: cursor paging is stable, complete and ordered newest first', async () => {
  const w = await t.signup('paging');
  await t.req('PATCH', '/v1/me', { token: w.token, body: { handle: 'feed_pager' } });
  for (let i = 0; i < 7; i++) await text(w, `page-${i}`);
  const all: string[] = [];
  let cursor: string | null = null;
  let pages = 0;
  do {
    const pg: any = await feed(b, `?limit=3${cursor ? `&before=${encodeURIComponent(cursor)}` : ''}`);
    assert.ok(pg.items.length <= 3);
    all.push(...pg.items.map((i: any) => i.id));
    cursor = pg.nextCursor;
    pages++;
  } while (cursor && pages < 30);
  assert.equal(new Set(all).size, all.length, 'no duplicates across pages');
  const mine = (await feed(b, '?limit=50')).items.filter((i: any) => i.body.startsWith('page-')).map((i: any) => i.body);
  assert.deepEqual(mine, ['page-6', 'page-5', 'page-4', 'page-3', 'page-2', 'page-1', 'page-0']);
  assert.equal((await t.req('GET', '/v1/feed?limit=500', { token: b.token })).status, 400);
});

test('feed: nearby uses distance from the post or the ride start', async () => {
  const n = await t.signup('nearby');
  await t.req('PATCH', '/v1/me', { token: n.token, body: { handle: 'feed_near' } });
  await text(n, 'sydney post', { lat: -33.8688, lng: 151.2093 });
  await text(n, 'melbourne post', { lat: -37.8136, lng: 144.9631 });
  await text(n, 'nowhere post');
  const near = bodies(await feed(b, '?scope=nearby&lat=-33.9&lng=151.2&radiusKm=30'));
  assert.ok(near.includes('sydney post'));
  assert.ok(!near.includes('melbourne post') && !near.includes('nowhere post'));
  const wide = bodies(await feed(b, '?scope=nearby&lat=-33.9&lng=151.2&radiusKm=500'));
  assert.ok(wide.includes('sydney post') && !wide.includes('melbourne post'));
  const far = bodies(await feed(b, '?scope=nearby&lat=-37.8&lng=145&radiusKm=50'));
  assert.ok(far.includes('melbourne post') && !far.includes('sydney post'));
  assert.equal((await t.req('GET', '/v1/feed?scope=nearby', { token: b.token })).status, 400);
  assert.equal((await t.req('GET', '/v1/feed?scope=weird', { token: b.token })).status, 400);
  assert.equal((await t.req('GET', '/v1/feed')).status, 401);
});

test('feed items carry author, counts and my like', async () => {
  const post = (await text(a, 'counted')).body;
  await t.req('POST', `/v1/posts/${post.id}/like`, { token: b.token });
  await t.req('POST', `/v1/posts/${post.id}/comments`, { token: b.token, body: { body: 'yo' } });
  const item = (await feed(b)).items.find((i: any) => i.id === post.id);
  assert.equal(item.author.handle, 'feed_a');
  assert.equal(item.likeCount, 1);
  assert.equal(item.commentCount, 1);
  assert.equal(item.likedByMe, true);
  assert.equal((await feed(a)).items.find((i: any) => i.id === post.id).likedByMe, false);
  assert.equal((await t.req('DELETE', `/v1/posts/${post.id}`, { token: b.token })).status, 404);
  assert.equal((await t.req('DELETE', `/v1/posts/${post.id}`, { token: a.token })).status, 200);
  assert.equal((await t.req('GET', `/v1/posts/${post.id}`, { token: a.token })).status, 404);
});

test('counters follow unlike and comment deletion (triggers)', async () => {
  const post = (await text(a, 'counter drift')).body;
  await t.req('POST', `/v1/posts/${post.id}/like`, { token: a.token });
  await t.req('POST', `/v1/posts/${post.id}/like`, { token: b.token });
  await t.req('POST', `/v1/posts/${post.id}/like`, { token: b.token });
  const c = (await t.req('POST', `/v1/posts/${post.id}/comments`, { token: b.token, body: { body: 'one' } })).body;
  await t.req('POST', `/v1/posts/${post.id}/comments`, { token: a.token, body: { body: 'two' } });
  let item = (await feed(a)).items.find((i: any) => i.id === post.id);
  assert.deepEqual([item.likeCount, item.commentCount], [2, 2]);
  await t.req('DELETE', `/v1/posts/${post.id}/like`, { token: b.token });
  await t.req('DELETE', `/v1/comments/${c.id}`, { token: b.token });
  item = (await feed(a)).items.find((i: any) => i.id === post.id);
  assert.deepEqual([item.likeCount, item.commentCount], [1, 1]);
  const row = await t.database.db.selectFrom('posts').select(['like_count', 'comment_count']).where('id', '=', post.id).executeTakeFirstOrThrow();
  assert.deepEqual([Number(row.like_count), Number(row.comment_count)], [1, 1]);
});