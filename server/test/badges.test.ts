import test, { after, before } from 'node:test';
import assert from 'node:assert/strict';
import { WebSocket } from 'ws';
import { testServer, type Session, type TestServer } from './apiHelpers.js';
import { connected } from './socketHelpers.js';

let t: TestServer;
let owner: Session;
let viewer: Session;
let priv: Session;
const build = { paint: '#aa1122', finish: 'metallic', wheel: 'te37', rideHeightMm: -30, body: 'hot_hatch', decals: [{ id: 'stripe', slot: 'hood', color: '#ffffff' }] };
const badge = { kind: 'car', build: { paint: '#aa1122', finish: 'metallic', wheel: 'te37', rideHeightMm: -30, body: 'hot_hatch' } };
let vehicleId: string;
const wsFor = (s: Session) => new WebSocket(`${t.wsBase}/ws`, ['convoyage.v1', `bearer.${s.token}`]);

before(async () => {
  t = await testServer();
  owner = await t.signup('bo');
  viewer = await t.signup('bv');
  priv = await t.signup('bp');
  for (const [s, h] of [[owner, 'bad_owner'], [viewer, 'bad_viewer'], [priv, 'bad_priv']] as const) await t.req('PATCH', '/v1/me', { token: s.token, body: { handle: h } });
  vehicleId = (await t.req('POST', '/v1/vehicles', { token: owner.token, body: { kind: 'car', make: 'Nissan', model: 'Silvia', build } })).body.id;
  await t.req('POST', '/v1/vehicles', { token: priv.token, body: { kind: 'motorcycle', make: 'Ducati', model: 'V4', build: { paint: '#00ff00' } } });
  await t.req('PATCH', '/v1/me', { token: priv.token, body: { isPrivate: true } });
});
after(() => t.close());

test('badges: feed and comment authors carry the primary vehicle (kind + four build fields, no decals)', async () => {
  const post = await t.req('POST', '/v1/posts', { token: owner.token, body: { kind: 'text', body: 'hello' } });
  const feed = await t.req('GET', '/v1/feed?scope=everyone', { token: viewer.token });
  const mine = feed.body.items.find((p: any) => p.id === post.body.id);
  assert.deepEqual(mine.author.primaryVehicle, badge);

  const made = await t.req('POST', `/v1/posts/${post.body.id}/comments`, { token: owner.token, body: { body: 'first' } });
  assert.equal(made.status, 201);
  assert.deepEqual(made.body.author.primaryVehicle, badge);
  const list = await t.req('GET', `/v1/posts/${post.body.id}/comments`, { token: viewer.token });
  assert.deepEqual(list.body.items[0].author.primaryVehicle, badge);

  // no primary vehicle: null, not missing
  const vp = await t.req('POST', '/v1/posts', { token: viewer.token, body: { kind: 'text', body: 'no car' } });
  const again = await t.req('GET', '/v1/feed?scope=everyone', { token: owner.token });
  assert.equal(again.body.items.find((p: any) => p.id === vp.body.id).author.primaryVehicle, null);
});

test('badges: a private account shows its vehicle only to accepted followers; follow requests and blocks lists carry it too', async () => {
  // priv asks to follow a second private account: that account sees the request, but priv is private and not followed back
  const priv2 = await t.signup('bp2');
  await t.req('PATCH', '/v1/me', { token: priv2.token, body: { handle: 'bad_priv2', isPrivate: true } });
  await t.req('POST', '/v1/users/bad_priv2/follow', { token: priv.token });
  await t.req('POST', '/v1/users/bad_priv2/follow', { token: owner.token });
  const reqs = await t.req('GET', '/v1/me/follow-requests', { token: priv2.token });
  assert.equal(reqs.body.find((r: any) => r.user.handle === 'bad_priv').user.primaryVehicle, null);
  assert.deepEqual(reqs.body.find((r: any) => r.user.handle === 'bad_owner').user.primaryVehicle, badge);

  // viewer follows priv and priv approves: now the post author shows it
  await t.req('POST', '/v1/users/bad_priv/follow', { token: viewer.token });
  await t.req('POST', `/v1/me/follow-requests/${viewer.id}/approve`, { token: priv.token });
  const post = await t.req('POST', '/v1/posts', { token: priv.token, body: { kind: 'text', body: 'private post' } });
  const feed = await t.req('GET', '/v1/feed?scope=following', { token: viewer.token });
  assert.deepEqual(feed.body.items.find((p: any) => p.id === post.body.id).author.primaryVehicle, { kind: 'motorcycle', build: { paint: '#00ff00' } });

  // across a block nothing is shown
  await t.req('POST', '/v1/users/bad_owner/block', { token: viewer.token });
  const blocks = await t.req('GET', '/v1/me/blocks', { token: viewer.token });
  assert.equal(blocks.body[0].primaryVehicle, null);
  await t.req('DELETE', '/v1/users/bad_owner/block', { token: viewer.token });
});

test('badges: profile and /v1/me still carry the full primary vehicle', async () => {
  assert.equal((await t.req('GET', '/v1/me', { token: owner.token })).body.primaryVehicle.build.paint, '#aa1122');
  assert.equal((await t.req('GET', '/v1/users/bad_owner', { token: viewer.token })).body.primaryVehicle.kind, 'car');
});

test('badges: convoy members get vehicle at connect, and it follows edits and privacy', async () => {
  const o = await connected(wsFor(owner));
  const v = await connected(wsFor(viewer));
  o.send({ t: 'convoy.create', name: 'Badges' });
  const code = (await o.next((x) => x.t === 'room')).room.code;
  v.send({ t: 'convoy.join', code });
  const room = (await v.next((x) => x.t === 'room')).room;
  assert.deepEqual(room.members.find((m: any) => m.id === owner.id).vehicle, badge);
  assert.equal(room.members.find((m: any) => m.id === viewer.id).vehicle, null);

  const edited = await t.req('PATCH', `/v1/vehicles/${vehicleId}`, { token: owner.token, body: { build: { ...build, paint: '#112233' } } });
  assert.equal(edited.status, 200);
  const seen = await v.next((x) => x.t === 'room' && x.room.members.some((m: any) => m.id === owner.id && m.vehicle?.build.paint === '#112233'));
  assert.ok(seen);

  await t.req('PATCH', '/v1/me', { token: owner.token, body: { isPrivate: true } });
  await v.next((x) => x.t === 'room' && x.room.members.some((m: any) => m.id === owner.id && m.vehicle === null));
  await t.req('PATCH', '/v1/me', { token: owner.token, body: { isPrivate: false } });
  o.ws.close(); v.ws.close();
});
