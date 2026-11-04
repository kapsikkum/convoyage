import { createCipheriv, createECDH, createHmac, randomBytes } from 'node:crypto';
import { lookup } from 'node:dns/promises';
import { isIP } from 'node:net';
import type { Fetch } from '../http.js';
import type { PushPayload, PushSender, PushTarget } from './sender.js';

/**
 * Web Push message encryption (RFC 8291, aes128gcm content coding, RFC 8188), for UnifiedPush endpoints.
 * The distributor (ntfy, NextPush, ...) only ever sees ciphertext; the app decrypts with the keys it registered.
 */
export function encryptWebPush(plain: Buffer, uaPublic: Buffer, authSecret: Buffer, opts: { salt?: Buffer; asPrivate?: Buffer } = {}): Buffer {
  const ecdh = createECDH('prime256v1');
  if (opts.asPrivate) ecdh.setPrivateKey(opts.asPrivate); else ecdh.generateKeys();
  const asPublic = ecdh.getPublicKey();
  const shared = ecdh.computeSecret(uaPublic);
  const hmac = (key: Buffer, ...data: Buffer[]) => { const h = createHmac('sha256', key); for (const d of data) h.update(d); return h.digest(); };
  // HKDF with a single 32-byte block: extract then expand(info || 0x01).
  const prkKey = hmac(authSecret, shared);
  const ikm = hmac(prkKey, Buffer.from('WebPush: info\0'), uaPublic, asPublic, Buffer.from([1]));
  const salt = opts.salt ?? randomBytes(16);
  const prk = hmac(salt, ikm);
  const cek = hmac(prk, Buffer.from('Content-Encoding: aes128gcm\0'), Buffer.from([1])).subarray(0, 16);
  const nonce = hmac(prk, Buffer.from('Content-Encoding: nonce\0'), Buffer.from([1])).subarray(0, 12);
  const cipher = createCipheriv('aes-128-gcm', cek, nonce);
  // One record: the plaintext then the 0x02 "last record" delimiter.
  const body = Buffer.concat([cipher.update(Buffer.concat([plain, Buffer.from([2])])), cipher.final(), cipher.getAuthTag()]);
  const header = Buffer.alloc(21);
  salt.copy(header, 0);
  header.writeUInt32BE(4096, 16);
  header.writeUInt8(asPublic.length, 20);
  return Buffer.concat([header, asPublic, body]);
}

const b64u = (s: string): Buffer => Buffer.from(s.replace(/-/g, '+').replace(/_/g, '/'), 'base64');

/** Private, loopback, link-local and similar ranges: endpoints there would let a client make the server probe its own network. */
export function isPrivateAddress(ip: string): boolean {
  if (isIP(ip) === 6) {
    const v = ip.toLowerCase();
    if (v === '::1' || v === '::') return true;
    if (v.startsWith('::ffff:')) return isPrivateAddress(v.slice(7));
    return /^(fc|fd|fe8|fe9|fea|feb)/.test(v);
  }
  const p = ip.split('.').map(Number);
  if (p.length !== 4 || p.some((n) => !Number.isInteger(n))) return true;
  const [a, b] = p;
  return a === 0 || a === 10 || a === 127 || (a === 100 && b >= 64 && b <= 127) || (a === 169 && b === 254)
    || (a === 172 && b >= 16 && b <= 31) || (a === 192 && b === 168) || (a === 198 && (b === 18 || b === 19)) || a >= 224;
}

/** A syntactically acceptable endpoint: https, a host name or public IP, no credentials. Checked again (by DNS) before each send. */
export function validEndpoint(raw: string): boolean {
  let u: URL;
  try { u = new URL(raw); } catch { return false; }
  if (u.protocol !== 'https:' || u.username || u.password) return false;
  const host = u.hostname.replace(/^\[|\]$/g, '');
  if (host === 'localhost' || host.endsWith('.localhost') || host.endsWith('.local') || host.endsWith('.internal')) return false;
  return isIP(host) ? !isPrivateAddress(host) : host.includes('.');
}

export type Resolve = (host: string) => Promise<string[]>;
const dnsResolve: Resolve = async (host) => (await lookup(host, { all: true })).map((r) => r.address);

/** UnifiedPush (Web Push) delivery. Endpoints that answer 404/410 are reported dead; private addresses are never contacted. */
export class WebPushSender implements PushSender {
  constructor(private fetchImpl: Fetch = fetch, private resolve: Resolve = dnsResolve) {}

  async send(targets: PushTarget[], payload: PushPayload): Promise<{ invalid: string[] }> {
    const invalid: string[] = [];
    const ups = targets.filter((t) => t.platform === 'unifiedpush' && t.p256dh && t.auth);
    const plain = Buffer.from(JSON.stringify(payload));
    await Promise.all(ups.map(async (t) => {
      try {
        if (!validEndpoint(t.token)) { invalid.push(t.token); return; }
        const host = new URL(t.token).hostname.replace(/^\[|\]$/g, '');
        const addrs = isIP(host) ? [host] : await this.resolve(host);
        if (!addrs.length || addrs.some(isPrivateAddress)) return;
        const res = await this.fetchImpl(t.token, {
          method: 'POST',
          headers: { 'content-encoding': 'aes128gcm', 'content-type': 'application/octet-stream', ttl: '86400', urgency: 'high' },
          body: new Uint8Array(encryptWebPush(plain, b64u(t.p256dh!), b64u(t.auth!))),
          redirect: 'error',
          signal: AbortSignal.timeout(10_000),
        });
        if (res.status === 404 || res.status === 410) invalid.push(t.token);
      } catch {
        // Network trouble or a slow distributor: try again with the next notification.
      }
    }));
    return { invalid };
  }
}
