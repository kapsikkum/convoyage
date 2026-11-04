import test, { after, before } from 'node:test';
import assert from 'node:assert/strict';
import { WebSocket } from 'ws';
import { LiveLinks, LIVE_LINK_TTL_MS } from '../src/api/routes/live.js';
import { testServer, type Session, type TestServer } from './apiHelpers.js';
import { connected } from './socketHelpers.js';

test('live links expire', () => {
  let now = 1_000;
  const links = new LiveLinks(() => now);
  const { token } = links.create('ABCDEFGH');
  assert.equal(links.resolve(token), 'ABCDEFGH');
  now += LIVE_LINK_TTL_MS + 1;
  assert.equal(links.resolve(token), null);
});

let t: TestServer;
before(async () => { t = await testServer(); });
after(() => t.close());
const wsFor = (s: Session) => new WebSocket(`${t.wsBase}/ws`, ['convoyage.v1', `bearer.${s.token}`]);

test('a member makes a live link; anyone can read it until the convoy ends; outsiders cannot make one', async () => {
  const lead = await t.signup('live-lead');
  const other = await t.signup('live-other');
  const w = await connected(wsFor(lead));
  w.send({ t: 'convoy.create', name: 'Sunday Run' });
  const code = (await w.next((x) => x.t === 'room')).room.code;
  w.send({ t: 'pos', lat: -33.42, lng: 149.58, heading: null, speed: null });
  await new Promise((r) => setTimeout(r, 100));

  assert.equal((await t.req('POST', `/v1/convoys/${code}/live`, { token: other.token })).status, 404);
  const made = await t.req('POST', `/v1/convoys/${code}/live`, { token: lead.token });
  assert.equal(made.status, 201, JSON.stringify(made.body));
  assert.match(made.body.url, /\/live\/[A-Za-z0-9_-]+$/);

  const page = await fetch(`${t.base}/live/${made.body.token}`);
  assert.equal(page.status, 200);
  assert.match(await page.text(), /CONVOY LIVE/);
  const state = await (await fetch(`${t.base}/live/${made.body.token}/state`)).json();
  assert.equal(state.name, 'Sunday Run');
  assert.equal(state.members.length, 1);
  assert.equal(state.members[0].leader, true);
  assert.equal(state.members[0].lat, -33.42);

  w.send({ t: 'convoy.leave', room: code });
  await w.next((x) => x.t === 'left');
  assert.equal((await fetch(`${t.base}/live/${made.body.token}/state`)).status, 404);
  assert.equal((await fetch(`${t.base}/live/not-a-real-token-xx/state`)).status, 404);
  w.ws.close();
});
