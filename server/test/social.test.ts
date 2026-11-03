import test, { after, before } from 'node:test';
import assert from 'node:assert/strict';
import { testServer, type Session, type TestServer } from './apiHelpers.js';

let t: TestServer;
before(async () => { t = await testServer(); });
after(() => t.close());

const setHandle = async (s: Session, handle: string, extra: object = {}) => {
  const r = await t.req('PATCH', '/v1/me', { token: s.token, body: { handle, ...extra } });
  assert.equal(r.status, 200, JSON.stringify(r.body));
  s.handle = handle;
  return r.body;
};

test('profile: update, handle rules, uniqueness and availability', async () => {
  const a = await t.signup('a');
  const b = await t.signup('b');
  const me = await setHandle(a, 'Alice_01', { displayName: 'Alice', bio: 'Low and slow' });
  assert.equal(me.handle, 'alice_01', 'handles are lower-cased');
  assert.equal(me.handleChosen, true);
  assert.equal(me.bio, 'Low and slow');
  assert.equal((await t.req('PATCH', '/v1/me', { token: b.token, body: { handle: 'alice_01' } })).status, 409);
  assert.equal((await t.req('PATCH', '/v1/me', { token: b.token, body: { handle: 'ab' } })).status, 400);
  assert.equal((await t.req('PATCH', '/v1/me', { token: b.token, body: { handle: 'has space' } })).status, 400);
  assert.equal((await t.req('PATCH', '/v1/me', { token: b.token, body: { handle: 'admin' } })).status, 400);
  const free = await t.req('GET', '/v1/handles/fresh_name/availability', { token: b.token });
  assert.deepEqual(free.body, { handle: 'fresh_name', available: true, reason: null });
  assert.equal((await t.req('GET', '/v1/handles/alice_01/availability', { token: b.token })).body.available, false);
  assert.equal((await t.req('GET', '/v1/handles/alice_01/availability', { token: a.token })).body.available, true, 'my own handle');
  assert.equal((await t.req('GET', '/v1/handles/admin/availability', { token: b.token })).body.available, false);
  assert.equal((await t.req('PATCH', '/v1/me', { token: b.token, body: { avatarMediaId: '00000000-0000-4000-8000-000000000000' } })).status, 400);
});

test('users/:handle is public by default; follows are accepted at once', async () => {
  const a = await t.signup('pub');
  const b = await t.signup('fan');
  await setHandle(a, 'pub_user');
  await setHandle(b, 'pub_fan');
  const before = await t.req('GET', '/v1/users/pub_user', { token: b.token });
  assert.equal(before.status, 200);
  assert.equal(before.body.canViewContent, true);
  assert.equal(before.body.relationship.following, 'none');
  assert.equal((await t.req('POST', '/v1/users/pub_user/follow', { token: b.token })).body.status, 'accepted');
  const after = await t.req('GET', '/v1/users/pub_user', { token: b.token });
  assert.equal(after.body.relationship.following, 'accepted');
  assert.equal(after.body.followerCount, 1);
  assert.equal((await t.req('GET', '/v1/users/pub_fan', { token: a.token })).body.relationship.followedBy, true);
  assert.equal((await t.req('POST', '/v1/users/pub_fan/follow', { token: b.token })).status, 400, 'no self follow');
  assert.equal((await t.req('DELETE', '/v1/users/pub_user/follow', { token: b.token })).body.status, 'none');
  assert.equal((await t.req('GET', '/v1/users/nobody_here', { token: b.token })).status, 404);
  assert.equal((await t.req('GET', '/v1/users/pub_user')).status, 401);
});

test('private profile: follow needs approval and content stays hidden until then', async () => {
  const owner = await t.signup('priv');
  const fan = await t.signup('pfan');
  const stranger = await t.signup('pstr');
  await setHandle(owner, 'priv_owner', { isPrivate: true });
  await setHandle(fan, 'priv_fan');
  await setHandle(stranger, 'priv_stranger');
  const car = await t.req('POST', '/v1/vehicles', { token: owner.token, body: { kind: 'car', make: 'Mazda', model: 'MX-5' } });
  assert.equal(car.status, 201);
  await t.req('POST', '/v1/posts', { token: owner.token, body: { kind: 'text', body: 'secret garage' } });

  const view = await t.req('GET', '/v1/users/priv_owner', { token: fan.token });
  assert.equal(view.body.isPrivate, true);
  assert.equal(view.body.canViewContent, false);
  assert.equal(view.body.primaryVehicle, null);
  assert.deepEqual((await t.req('GET', '/v1/users/priv_owner/vehicles', { token: fan.token })).body, []);
  assert.equal((await t.req('GET', '/v1/users/priv_owner/posts', { token: fan.token })).body.items.length, 0);

  assert.equal((await t.req('POST', '/v1/users/priv_owner/follow', { token: fan.token })).body.status, 'pending');
  assert.equal((await t.req('GET', '/v1/users/priv_owner', { token: fan.token })).body.canViewContent, false, 'pending is not following');
  assert.equal((await t.req('GET', '/v1/users/priv_owner', { token: fan.token })).body.followerCount, 0);

  const reqs = await t.req('GET', '/v1/me/follow-requests', { token: owner.token });
  assert.equal(reqs.body.length, 1);
  assert.equal(reqs.body[0].user.handle, 'priv_fan');
  assert.equal((await t.req('GET', '/v1/me', { token: owner.token })).body.pendingRequestCount, 1);
  assert.equal((await t.req('POST', `/v1/me/follow-requests/${stranger.id}/approve`, { token: owner.token })).status, 404);
  assert.equal((await t.req('POST', `/v1/me/follow-requests/${fan.id}/approve`, { token: owner.token })).status, 200);

  const approved = await t.req('GET', '/v1/users/priv_owner', { token: fan.token });
  assert.equal(approved.body.canViewContent, true);
  assert.equal(approved.body.primaryVehicle.model, 'MX-5');
  assert.equal((await t.req('GET', '/v1/users/priv_owner/posts', { token: fan.token })).body.items.length, 1);
  assert.equal((await t.req('GET', '/v1/users/priv_owner/posts', { token: stranger.token })).body.items.length, 0);

  // Decline removes the request; going public approves the rest.
  await t.req('POST', '/v1/users/priv_owner/follow', { token: stranger.token });
  assert.equal((await t.req('DELETE', `/v1/me/follow-requests/${stranger.id}`, { token: owner.token })).status, 200);
  assert.equal((await t.req('GET', '/v1/users/priv_owner', { token: stranger.token })).body.relationship.following, 'none');
  await t.req('POST', '/v1/users/priv_owner/follow', { token: stranger.token });
  await t.req('PATCH', '/v1/me', { token: owner.token, body: { isPrivate: false } });
  assert.equal((await t.req('GET', '/v1/users/priv_owner', { token: stranger.token })).body.relationship.following, 'accepted');
});

test('blocks: hide both ways, remove follows, stop interaction, and can be undone', async () => {
  const a = await t.signup('ba');
  const b = await t.signup('bb');
  await setHandle(a, 'block_a');
  await setHandle(b, 'block_b');
  await t.req('POST', '/v1/users/block_a/follow', { token: b.token });
  await t.req('POST', '/v1/users/block_b/follow', { token: a.token });
  const post = await t.req('POST', '/v1/posts', { token: a.token, body: { kind: 'text', body: 'hello from a' } });

  assert.equal((await t.req('POST', '/v1/users/block_b/block', { token: a.token })).status, 200);
  // The blocker: follows gone both ways, can still see the profile to unblock it, cannot follow again.
  const seen = await t.req('GET', '/v1/users/block_b', { token: a.token });
  assert.equal(seen.status, 200);
  assert.equal(seen.body.relationship.blockedByMe, true);
  assert.equal(seen.body.relationship.following, 'none');
  assert.equal((await t.req('POST', '/v1/users/block_b/follow', { token: a.token })).status, 409);
  assert.deepEqual((await t.req('GET', '/v1/me/blocks', { token: a.token })).body.map((u: any) => u.handle), ['block_b']);
  // The blocked user: the blocker does not exist.
  assert.equal((await t.req('GET', '/v1/users/block_a', { token: b.token })).status, 404);
  assert.equal((await t.req('POST', '/v1/users/block_a/follow', { token: b.token })).status, 404);
  assert.equal((await t.req('GET', `/v1/posts/${post.body.id}`, { token: b.token })).status, 404);
  assert.equal((await t.req('POST', `/v1/posts/${post.body.id}/like`, { token: b.token })).status, 404);
  assert.equal((await t.req('POST', `/v1/posts/${post.body.id}/comments`, { token: b.token, body: { body: 'hi' } })).status, 404);
  assert.equal((await t.req('GET', `/v1/posts/${post.body.id}`, { token: a.token })).status, 200);
  // And the blocker cannot see the blocked user's posts either.
  const theirs = await t.req('POST', '/v1/posts', { token: b.token, body: { kind: 'text', body: 'from b' } });
  assert.equal((await t.req('GET', `/v1/posts/${theirs.body.id}`, { token: a.token })).status, 404);

  assert.equal((await t.req('DELETE', '/v1/users/block_b/block', { token: a.token })).status, 200);
  assert.equal((await t.req('GET', '/v1/users/block_a', { token: b.token })).status, 200);
  assert.equal((await t.req('POST', '/v1/users/block_a/block', { token: a.token })).status, 400, 'no self block');
});

test('comments: list, create, delete rules, blocked authors hidden', async () => {
  const author = await t.signup('ca');
  const fan = await t.signup('cf');
  const rude = await t.signup('cr');
  await setHandle(author, 'c_author');
  await setHandle(fan, 'c_fan');
  await setHandle(rude, 'c_rude');
  const post = (await t.req('POST', '/v1/posts', { token: author.token, body: { kind: 'text', body: 'my build' } })).body;
  const c1 = await t.req('POST', `/v1/posts/${post.id}/comments`, { token: fan.token, body: { body: 'nice one' } });
  assert.equal(c1.status, 201);
  assert.equal(c1.body.author.handle, 'c_fan');
  const c2 = await t.req('POST', `/v1/posts/${post.id}/comments`, { token: rude.token, body: { body: 'meh' } });
  assert.equal((await t.req('POST', `/v1/posts/${post.id}/comments`, { token: fan.token, body: { body: '   ' } })).status, 400);

  assert.equal((await t.req('GET', `/v1/posts/${post.id}/comments`, { token: fan.token })).body.items.length, 2);
  await t.req('POST', '/v1/users/c_rude/block', { token: fan.token });
  assert.deepEqual((await t.req('GET', `/v1/posts/${post.id}/comments`, { token: fan.token })).body.items.map((c: any) => c.body), ['nice one']);
  assert.equal((await t.req('GET', `/v1/posts/${post.id}`, { token: author.token })).body.commentCount, 2);

  assert.equal((await t.req('DELETE', `/v1/comments/${c1.body.id}`, { token: rude.token })).status, 403);
  assert.equal((await t.req('DELETE', `/v1/comments/${c2.body.id}`, { token: author.token })).status, 200, 'post author may remove comments');
  assert.equal((await t.req('DELETE', `/v1/comments/${c1.body.id}`, { token: fan.token })).status, 200);
  assert.equal((await t.req('DELETE', `/v1/comments/${c1.body.id}`, { token: fan.token })).status, 404);
});

test('likes are idempotent and counted; reports are recorded once', async () => {
  const a = await t.signup('la');
  const b = await t.signup('lb');
  const post = (await t.req('POST', '/v1/posts', { token: a.token, body: { kind: 'text', body: 'like me' } })).body;
  assert.deepEqual((await t.req('POST', `/v1/posts/${post.id}/like`, { token: b.token })).body, { likeCount: 1, likedByMe: true });
  assert.deepEqual((await t.req('POST', `/v1/posts/${post.id}/like`, { token: b.token })).body, { likeCount: 1, likedByMe: true });
  assert.deepEqual((await t.req('POST', `/v1/posts/${post.id}/like`, { token: a.token })).body, { likeCount: 2, likedByMe: true });
  assert.deepEqual((await t.req('DELETE', `/v1/posts/${post.id}/like`, { token: b.token })).body, { likeCount: 1, likedByMe: false });
  assert.equal((await t.req('GET', `/v1/posts/${post.id}`, { token: b.token })).body.likedByMe, false);

  const report = { targetType: 'post', targetId: post.id, reason: 'spam' };
  assert.equal((await t.req('POST', '/v1/reports', { token: b.token, body: report })).status, 201);
  assert.equal((await t.req('POST', '/v1/reports', { token: b.token, body: report })).status, 201);
  const rows = await t.database.db.selectFrom('reports').selectAll().where('target_id', '=', post.id).execute();
  assert.equal(rows.length, 1);
  assert.equal((await t.req('POST', '/v1/reports', { token: b.token, body: { ...report, targetId: '00000000-0000-4000-8000-000000000000' } })).status, 404);
  assert.equal((await t.req('POST', '/v1/reports', { token: b.token, body: { ...report, reason: 'rude' } })).status, 400);
  assert.equal((await t.req('POST', '/v1/reports', { token: b.token, body: { targetType: 'user', targetId: b.id, reason: 'spam' } })).status, 400);
  assert.equal((await t.req('POST', '/v1/reports', { token: b.token, body: { targetType: 'user', targetId: a.id, reason: 'abuse', detail: 'rude' } })).status, 201);
});

test('deleting my account removes my data', async () => {
  const gone = await t.signup('gone');
  await setHandle(gone, 'soon_gone');
  await t.req('POST', '/v1/vehicles', { token: gone.token, body: { kind: 'car', make: 'A', model: 'B' } });
  assert.equal((await t.req('DELETE', '/v1/me', { token: gone.token })).status, 200);
  const left = await t.database.db.selectFrom('profiles').select('user_id').where('handle', '=', 'soon_gone').execute();
  assert.equal(left.length, 0);
  assert.equal((await t.req('GET', '/v1/me', { token: gone.token })).status, 401);
});
