import { readFileSync } from 'node:fs';
import http2 from 'node:http2';
import { SignJWT, importPKCS8 } from 'jose';
import type { Config } from '../config.js';
import type { Fetch } from '../http.js';

export type Platform = 'ios' | 'android';
export interface PushTarget { platform: Platform; token: string }
export interface PushPayload { title: string; body: string; data?: Record<string, string> }

/** Sends one notification to a set of devices. Returns the tokens the provider says are dead, so they can be forgotten. */
export interface PushSender {
  send(targets: PushTarget[], payload: PushPayload): Promise<{ invalid: string[] }>;
}

export const noopSender: PushSender = { send: async () => ({ invalid: [] }) };

/** FCM HTTP v1. Authenticates with a service-account JWT exchanged for an OAuth token (cached until it nearly expires). */
export class FcmSender implements PushSender {
  private access: { token: string; expiresAt: number } | null = null;
  private sa: { project_id: string; client_email: string; private_key: string; token_uri?: string };
  constructor(path: string, private fetchImpl: Fetch = fetch) {
    this.sa = JSON.parse(readFileSync(path, 'utf8'));
  }

  private async token(): Promise<string> {
    if (this.access && this.access.expiresAt > Date.now() + 60_000) return this.access.token;
    const uri = this.sa.token_uri || 'https://oauth2.googleapis.com/token';
    const now = Math.floor(Date.now() / 1000);
    const jwt = await new SignJWT({ scope: 'https://www.googleapis.com/auth/firebase.messaging' })
      .setProtectedHeader({ alg: 'RS256', typ: 'JWT' }).setIssuer(this.sa.client_email).setAudience(uri).setIssuedAt(now).setExpirationTime(now + 3600)
      .sign(await importPKCS8(this.sa.private_key, 'RS256'));
    const res = await this.fetchImpl(uri, {
      method: 'POST', headers: { 'content-type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({ grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer', assertion: jwt }),
    });
    if (!res.ok) throw new Error(`FCM auth failed: ${res.status}`);
    const j = await res.json() as { access_token: string; expires_in: number };
    this.access = { token: j.access_token, expiresAt: Date.now() + j.expires_in * 1000 };
    return j.access_token;
  }

  async send(targets: PushTarget[], payload: PushPayload): Promise<{ invalid: string[] }> {
    const invalid: string[] = [];
    const android = targets.filter((t) => t.platform === 'android');
    if (!android.length) return { invalid };
    const bearer = await this.token();
    for (const t of android) {
      const res = await this.fetchImpl(`https://fcm.googleapis.com/v1/projects/${this.sa.project_id}/messages:send`, {
        method: 'POST', headers: { authorization: `Bearer ${bearer}`, 'content-type': 'application/json' },
        body: JSON.stringify({ message: { token: t.token, notification: { title: payload.title, body: payload.body }, data: payload.data ?? {}, android: { priority: 'HIGH' } } }),
      });
      if (res.status === 404 || res.status === 400) {
        const text = await res.text().catch(() => '');
        if (/UNREGISTERED|INVALID_ARGUMENT|NOT_FOUND/.test(text)) invalid.push(t.token);
      }
    }
    return { invalid };
  }
}

/** APNs over HTTP/2 with a token-based (.p8) key. */
export class ApnsSender implements PushSender {
  private jwt: { value: string; at: number } | null = null;
  private key: string;
  constructor(private cfg: NonNullable<Config['apns']>) {
    this.key = readFileSync(cfg.keyPath, 'utf8');
  }

  private async bearer(): Promise<string> {
    if (this.jwt && Date.now() - this.jwt.at < 40 * 60_000) return this.jwt.value;
    const value = await new SignJWT({}).setProtectedHeader({ alg: 'ES256', kid: this.cfg.keyId }).setIssuer(this.cfg.teamId)
      .setIssuedAt().sign(await importPKCS8(this.key, 'ES256'));
    this.jwt = { value, at: Date.now() };
    return value;
  }

  async send(targets: PushTarget[], payload: PushPayload): Promise<{ invalid: string[] }> {
    const invalid: string[] = [];
    const ios = targets.filter((t) => t.platform === 'ios');
    if (!ios.length) return { invalid };
    const bearer = await this.bearer();
    const client = http2.connect(this.cfg.sandbox ? 'https://api.sandbox.push.apple.com' : 'https://api.push.apple.com');
    client.on('error', () => {});
    try {
      const body = JSON.stringify({ aps: { alert: { title: payload.title, body: payload.body }, sound: 'default' }, ...(payload.data ?? {}) });
      await Promise.all(ios.map((t) => new Promise<void>((resolve) => {
        const req = client.request({
          ':method': 'POST', ':path': `/3/device/${t.token}`, authorization: `bearer ${bearer}`,
          'apns-topic': this.cfg.topic, 'apns-push-type': 'alert', 'content-type': 'application/json',
        });
        req.setTimeout(10_000, () => { req.close(); resolve(); });
        req.on('response', (h) => { if (h[':status'] === 410 || h[':status'] === 400) invalid.push(t.token); });
        req.on('error', () => resolve());
        req.on('close', () => resolve());
        req.resume();
        req.end(body);
      })));
    } finally {
      client.close();
    }
    return { invalid };
  }
}

/** Fans a send out to every sender; each ignores platforms it does not handle. */
export function compositeSender(...senders: PushSender[]): PushSender {
  return {
    async send(targets, payload) {
      const results = await Promise.all(senders.map((s) => s.send(targets, payload).catch((err) => {
        console.error('[push] send failed:', (err as Error).message);
        return { invalid: [] as string[] };
      })));
      return { invalid: results.flatMap((r) => r.invalid) };
    },
  };
}

/** FCM and/or APNs when configured, else a no-op. A bad credentials file is logged, not fatal. */
export function pushFromConfig(config: Config): PushSender {
  const senders: PushSender[] = [];
  try { if (config.fcmServiceAccountPath) senders.push(new FcmSender(config.fcmServiceAccountPath)); } catch (err) { console.error('[push] FCM disabled:', (err as Error).message); }
  try { if (config.apns) senders.push(new ApnsSender(config.apns)); } catch (err) { console.error('[push] APNs disabled:', (err as Error).message); }
  return senders.length ? compositeSender(...senders) : noopSender;
}
