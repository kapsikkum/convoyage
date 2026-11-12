import test, { after, before } from 'node:test';
import assert from 'node:assert/strict';
import { testServer, type TestServer } from './apiHelpers.js';

const ADMIN = 'a'.repeat(40);
let t: TestServer;
let off: TestServer;
before(async () => {
  t = await testServer({ env: { ADMIN_TOKEN: ADMIN } });
  off = await testServer();
});
after(async () => { await t.close(); await off.close(); });

const admin = { Authorization: `Bearer ${ADMIN}` };

test('admin routes need the admin token, and do not exist when none is configured', async () => {
  assert.equal((await t.req('GET', '/admin/reports')).status, 401);
  assert.equal((await t.req('GET', '/admin/reports', { headers: { Authorization: 'Bearer nope' } })).status, 401);
  assert.equal((await t.req('GET', '/admin/reports', { headers: admin })).status, 200);
  assert.equal((await off.req('GET', '/admin/reports', { headers: admin })).status, 404);
});

test('reports: list, action, remove content, disable and re-enable a user', async () => {
  const author = await t.signup('mod_author');
  const reporter = await t.signup('mod_reporter');
  const post = (await t.req('POST', '/v1/posts', { token: author.token, body: { kind: 'text', body: 'rude post' } })).body;
  assert.equal((await t.req('POST', '/v1/reports', { token: reporter.token, body: { targetType: 'post', targetId: post.id, reason: 'abuse', detail: 'nasty' } })).status, 201);

  const open = await t.req('GET', '/admin/reports', { headers: admin });
  const r = open.body.find((x: any) => x.targetId === post.id);
  assert.equal(r.reason, 'abuse');
  assert.match(r.target, /rude post/);

  assert.equal((await t.req('DELETE', `/admin/posts/${post.id}`, { headers: admin })).status, 200);
  assert.equal((await t.req('POST', `/admin/reports/${r.id}`, { headers: admin, body: { status: 'actioned' } })).status, 200);
  assert.equal((await t.req('GET', '/admin/reports', { headers: admin })).body.some((x: any) => x.id === r.id), false);
  assert.equal((await t.req('GET', '/admin/reports?status=actioned', { headers: admin })).body.some((x: any) => x.id === r.id), true);
  assert.equal((await t.req('POST', '/admin/reports/not-a-uuid', { headers: admin, body: { status: 'actioned' } })).status, 400);

  assert.equal((await t.req('POST', `/admin/users/${author.id}/disable`, { headers: admin })).status, 200);
  const row = await t.database.db.selectFrom('users').select('disabled_at').where('id', '=', author.id).executeTakeFirstOrThrow();
  assert.ok(row.disabled_at);
  assert.equal((await t.req('POST', `/admin/users/${author.id}/enable`, { headers: admin })).status, 200);
  assert.equal((await t.req('POST', '/admin/users/00000000-0000-4000-8000-000000000000/disable', { headers: admin })).status, 404);
});

test('ADMIN_TOKEN must be 32+ characters', async () => {
  const { loadConfig } = await import('../src/config.js');
  assert.throws(() => loadConfig({ ADMIN_TOKEN: 'short', JWT_SECRET: 'x'.repeat(40) } as NodeJS.ProcessEnv), /ADMIN_TOKEN/);
});