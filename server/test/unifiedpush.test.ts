import test from 'node:test';
import assert from 'node:assert/strict';
import { createDecipheriv, createECDH, createHmac, randomBytes } from 'node:crypto';
import { WebSocket } from 'ws';
import { encryptWebPush, isPrivateAddress, validEndpoint, WebPushSender } from '../src/push/webpush.js';
import type { PushPayload, PushSender, PushTarget } from '../src/push/sender.js';
import { testServer } from './apiHelpers.js';
import { connected } from './socketHelpers.js';

const b64u = (b: Buffer) => b.toString('base64').replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');

/** What the app does on receipt (RFC 8291 from the user agent's side), to prove the server's output decrypts. */
function decrypt(msg: Buffer, uaPrivate: Buffer, authSecret: Buffer): Buffer {
  const salt = msg.subarray(0, 16);
  const idlen = msg.readUInt8(20);
  const asPublic = msg.subarray(21, 21 + idlen);
  const body = msg.subarray(21 + idlen);
  const ua = createECDH('prime256v1');
  ua.setPrivateKey(uaPrivate);
  const shared = ua.computeSecret(asPublic);
  const hmac = (key: Buffer, ...data: Buffer[]) => { const h = createHmac('sha256', key); for (const d of data) h.update(d); return h.digest(); };
  const ikm = hmac(hmac(authSecret, shared), Buffer.from('WebPush: info\0'), ua.getPublicKey(), asPublic, Buffer.from([1]));
  const prk = hmac(salt, ikm);
  const cek = hmac(prk, Buffer.from('Content-Encoding: aes128gcm\0'), Buffer.from([1])).subarray(0, 16);
  const nonce = hmac(prk, Buffer.from('Content-Encoding: nonce\0'), Buffer.from([1])).subarray(0, 12);
  const d = createDecipheriv('aes-128-gcm', cek, nonce);
  d.setAuthTag(body.subarray(body.length - 16));
  const out = Buffer.concat([d.update(body.subarray(0, body.length - 16)), d.final()]);
  assert.equal(out[out.length - 1], 2, 'last-record delimiter');
  return out.subarray(0, out.length - 1);
}

function uaKeys() {
  const ua = createECDH('prime256v1');
  ua.generateKeys();
  return { priv: ua.getPrivateKey(), pub: ua.getPublicKey(), auth: randomBytes(16) };
}

test('webpush: aes128gcm output decrypts with the app keys, header as RFC 8188', () => {
  const k = uaKeys();
  const plain = Buffer.from(JSON.stringify({ title: 'SOS from Sam', body: 'Needs help', data: { type: 'sos' } }));
  const msg = encryptWebPush(plain, k.pub, k.auth);
  assert.equal(msg.readUInt32BE(16), 4096);
  assert.equal(msg.readUInt8(20), 65);
  assert.deepEqual(decrypt(msg, k.priv, k.auth), plain);
  // A different auth secret must not decrypt.
  assert.throws(() => decrypt(msg, k.priv, randomBytes(16)));
});

test('webpush: endpoints must be public https', () => {
  assert.equal(validEndpoint('https://ntfy.sh/upAbc123?up=1'), true);
  assert.equal(validEndpoint('https://push.example.org:8443/x'), true);
  for (const bad of ['http://ntfy.sh/x', 'https://localhost/x', 'https://10.0.0.5/x', 'https://[::1]/x', 'https://u:p@ntfy.sh/x', 'https://printer.local/x', 'notaurl']) {
    assert.equal(validEndpoint(bad), false, bad);
  }
  for (const ip of ['127.0.0.1', '192.168.1.4', '172.20.0.1', '169.254.1.1', '100.64.0.1', 'fd00::1', '::ffff:10.0.0.1']) assert.equal(isPrivateAddress(ip), true, ip);
  for (const ip of ['1.1.1.1', '159.69.10.2', '2a01:4f8::1']) assert.equal(isPrivateAddress(ip), false, ip);
});

test('webpush sender: posts encrypted, forgets 404/410 endpoints, never contacts private addresses', async () => {
  const k = uaKeys();
  const calls: { url: string; headers: Record<string, string>; body: Buffer }[] = [];
  const fakeFetch = async (url: string, init?: RequestInit) => {
    calls.push({ url, headers: init!.headers as Record<string, string>, body: Buffer.from(init!.body as Uint8Array) });
    return new Response(null, { status: url.includes('gone') ? 410 : 201 });
  };
  const resolve = async (host: string) => (host === 'evil.example' ? ['10.1.2.3'] : ['159.69.10.2']);
  const s = new WebPushSender(fakeFetch, resolve);
  const keys = { p256dh: b64u(k.pub), auth: b64u(k.auth) };
  const targets: PushTarget[] = [
    { platform: 'unifiedpush', token: 'https://ntfy.example/up1', ...keys },
    { platform: 'unifiedpush', token: 'https://ntfy.example/gone', ...keys },
    { platform: 'unifiedpush', token: 'https://evil.example/x', ...keys },
    { platform: 'android', token: 'fcm-token' },
  ];
  const payload: PushPayload = { title: 'New follower', body: 'Sam started following you', data: { type: 'follow' } };
  const { invalid } = await s.send(targets, payload);
  assert.deepEqual(invalid, ['https://ntfy.example/gone']);
  assert.deepEqual(calls.map((c) => c.url).sort(), ['https://ntfy.example/gone', 'https://ntfy.example/up1']);
  const first = calls.find((c) => c.url.endsWith('up1'))!;
  assert.equal(first.headers['content-encoding'], 'aes128gcm');
  assert.deepEqual(JSON.parse(decrypt(first.body, k.priv, k.auth).toString()), payload);
});

class FakeSender implements PushSender {
  sent: { targets: PushTarget[]; payload: PushPayload }[] = [];
  async send(targets: PushTarget[], payload: PushPayload) { this.sent.push({ targets, payload }); return { invalid: [] }; }
}

test('devices: unifiedpush registers with its keys; bad endpoints and missing keys are refused; open sockets get notify instead of push', async () => {
  const sender = new FakeSender();
  const d = await testServer({ push: sender });
  try {
    const [x, y] = [await d.signup('upx'), await d.signup('upy')];
    await d.req('PATCH', '/v1/me', { token: x.token, body: { handle: 'up_x' } });
    const k = uaKeys();
    const keys = { p256dh: b64u(k.pub), auth: b64u(k.auth) };
    const reg = (body: object) => d.req('POST', '/v1/me/devices', { token: x.token, body });
    assert.equal((await reg({ platform: 'unifiedpush', token: 'https://ntfy.example/upX1', ...keys })).status, 200);
    assert.equal((await reg({ platform: 'unifiedpush', token: 'https://ntfy.example/upX2' })).status, 400, 'keys are required');
    assert.equal((await reg({ platform: 'unifiedpush', token: 'http://ntfy.example/upX3', ...keys })).status, 400, 'https only');
    assert.equal((await reg({ platform: 'unifiedpush', token: 'https://192.168.0.2/upX4', ...keys })).status, 400, 'no private hosts');
    const row = await d.database.db.selectFrom('device_tokens').selectAll().where('user_id', '=', x.id).executeTakeFirstOrThrow();
    assert.equal(row.platform, 'unifiedpush');
    assert.equal(row.p256dh, keys.p256dh);

    // Offline: the follow goes out as a push, with the keys the Web Push sender needs.
    await d.req('POST', '/v1/users/up_x/follow', { token: y.token });
    for (let i = 0; i < 50 && !sender.sent.length; i++) await new Promise((r) => setTimeout(r, 10));
    assert.equal(sender.sent.length, 1);
    assert.equal(sender.sent[0].targets[0].p256dh, keys.p256dh);

    // With a socket open (the keep-alive connection) it arrives there as notify, and no push is sent.
    sender.sent.length = 0;
    const ws = await connected(new WebSocket(`${d.wsBase}/ws`, ['convoyage.v1', `bearer.${x.token}`]));
    await d.req('DELETE', '/v1/users/up_x/follow', { token: y.token });
    await d.req('POST', '/v1/users/up_x/follow', { token: y.token });
    const m = await ws.next((msg) => msg.t === 'notify');
    assert.equal(m.title, 'New follower');
    assert.equal(m.data.type, 'follow');
    assert.equal(sender.sent.length, 0);
    ws.ws.close();
  } finally {
    await d.close();
  }
});
