import type { FastifyInstance } from 'fastify';
import { loadConfig, type Config } from '../src/config.js';
import { createPgliteDatabase, type Database } from '../src/db/index.js';
import { migrate } from '../src/db/migrate.js';
import { MemoryMediaStore } from '../src/media/store.js';
import type { Providers } from '../src/api/providers.js';
import type { Fetch } from '../src/http.js';
import type { PushSender } from '../src/push/sender.js';
import { buildServer } from '../src/server.js';

export interface Reply { status: number; body: any; headers: Headers }

export interface Session { id: string; email: string; token: string; refresh: string; handle: string }

export interface TestServer {
  app: FastifyInstance;
  database: Database;
  media: MemoryMediaStore;
  config: Config;
  base: string;
  wsBase: string;
  /** JSON request. `token` adds a bearer header. */
  req(method: string, path: string, opts?: { token?: string; body?: unknown; headers?: Record<string, string> }): Promise<Reply>;
  /** A new account through the dev magic-link flow. */
  signup(name?: string): Promise<Session>;
  close(): Promise<void>;
}

let counter = 0;

export async function testServer(opts: { config?: Partial<Config>; providers?: Providers; env?: Record<string, string>; database?: Database; push?: PushSender; fetch?: Fetch; recheckMs?: number } = {}): Promise<TestServer> {
  const shared = opts.database;
  const database = shared ?? await createPgliteDatabase();
  if (!shared) await migrate(database);
  const config: Config = {
    ...loadConfig({ JWT_SECRET: 't'.repeat(40), MAIL_DEV: '1', RATE_LIMIT_DISABLED: '1', DATABASE_URL: 'pglite:memory', ...opts.env } as NodeJS.ProcessEnv),
    ...opts.config,
  };
  const media = new MemoryMediaStore();
  const app = await buildServer({ config, database, media, providers: opts.providers ?? {}, push: opts.push, fetch: opts.fetch, heartbeatMs: 5000, recheckMs: opts.recheckMs });
  await app.listen({ port: 0, host: '127.0.0.1' });
  const port = (app.server.address() as { port: number }).port;
  const base = `http://127.0.0.1:${port}`;

  async function req(method: string, path: string, o: { token?: string; body?: unknown; headers?: Record<string, string> } = {}): Promise<Reply> {
    const headers: Record<string, string> = { ...o.headers };
    if (o.token) headers.authorization = `Bearer ${o.token}`;
    if (o.body !== undefined) headers['content-type'] = 'application/json';
    const res = await fetch(base + path, { method, headers, body: o.body === undefined ? undefined : JSON.stringify(o.body) });
    const text = await res.text();
    let body: any = text;
    try { body = text ? JSON.parse(text) : null; } catch { /* not JSON */ }
    return { status: res.status, body, headers: res.headers };
  }

  async function signup(name = 'rider'): Promise<Session> {
    const email = `${name}${++counter}@example.com`;
    const link = await req('POST', '/auth/magic-link', { body: { email } });
    if (link.status !== 202) throw new Error(`magic-link: ${link.status} ${JSON.stringify(link.body)}`);
    const token = new URL(link.body.devLink).searchParams.get('token')!;
    const done = await req('POST', '/auth/magic-link/verify', { body: { token } });
    if (done.status !== 200) throw new Error(`verify: ${done.status} ${JSON.stringify(done.body)}`);
    const me = await req('GET', '/v1/me', { token: done.body.accessToken });
    return { id: me.body.id, email, token: done.body.accessToken, refresh: done.body.refreshToken, handle: me.body.handle };
  }

  return {
    app, database, media, config, base, wsBase: `ws://127.0.0.1:${port}`, req, signup,
    async close() { await app.close(); if (!shared) await database.close(); },
  };
}

/** A real JPEG with GPS-free, camera-free content, made with sharp. */
export async function makeJpeg(width = 64, height = 48, withExif = false): Promise<Buffer> {
  const sharp = (await import('sharp')).default;
  let img = sharp({ create: { width, height, channels: 3, background: { r: 200, g: 30, b: 30 } } });
  if (withExif) img = img.withExif({ IFD0: { Make: 'SecretCam', Artist: 'Jane Rider' }, IFD3: { GPSLatitudeRef: 'S' } } as never);
  return img.jpeg().toBuffer();
}

export async function upload(t: TestServer, token: string, data: Buffer, filename = 'x.jpg', type = 'image/jpeg'): Promise<Reply> {
  const form = new FormData();
  form.append('file', new Blob([new Uint8Array(data)], { type }), filename);
  const res = await fetch(`${t.base}/v1/media`, { method: 'POST', headers: { authorization: `Bearer ${token}` }, body: form });
  const text = await res.text();
  let body: any = text;
  try { body = JSON.parse(text); } catch { /* not JSON */ }
  return { status: res.status, body, headers: res.headers };
}
