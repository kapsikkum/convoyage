import test, { after, before } from 'node:test';
import assert from 'node:assert/strict';
import WebSocket from 'ws';
import { testServer, type TestServer } from './apiHelpers.js';

let t: TestServer;
before(async () => { t = await testServer({ recheckMs: 150 }); });
after(() => t.close());

test('an open socket is closed with 4401 once its account is gone', async () => {
  const u = await t.signup('rc_user');
  const ws = new WebSocket(`${t.wsBase}/ws`, ['convoyage.v1', `bearer.${u.token}`]);
  await new Promise<void>((res, rej) => { ws.once('open', () => res()); ws.once('error', rej); });
  const closed = new Promise<number>((res) => ws.once('close', (code) => res(code)));
  // The user row disappears while the socket (and its access token) are still around.
  await t.database.db.deleteFrom('users').where('id', '=', u.id).execute();
  const code = await Promise.race([closed, new Promise<number>((res) => setTimeout(() => res(-1), 3000))]);
  assert.equal(code, 4401);
});

test('a healthy account keeps its socket across rechecks', async () => {
  const u = await t.signup('rc_ok');
  const ws = new WebSocket(`${t.wsBase}/ws`, ['convoyage.v1', `bearer.${u.token}`]);
  await new Promise<void>((res, rej) => { ws.once('open', () => res()); ws.once('error', rej); });
  let closed = false;
  ws.once('close', () => { closed = true; });
  await new Promise((r) => setTimeout(r, 600));
  assert.equal(closed, false);
  ws.close();
});
