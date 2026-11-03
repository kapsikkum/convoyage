import { createHash, randomBytes } from 'node:crypto';
import { createServer, type IncomingMessage, type Server, type ServerResponse } from 'node:http';
import type { AddressInfo } from 'node:net';

export interface FakeUser { id: string; username: string }

interface Grant { user: FakeUser; clientId: string; scope: string; revoked: boolean }

/**
 * A stand-in for PhotoScout: the OAuth 2 + PKCE endpoints it really has (/oauth/token with rotating refresh tokens,
 * /oauth/revoke) and the handful of /api routes Convoyage proxies, answering with canned data. The "browser" half of
 * the flow (sign in, consent page) is `consent()`.
 */
export class FakePhotoScout {
  server!: Server;
  url = '';
  clientId = 'convoyage';
  /** Seconds a new access token lives. */
  accessTtl = 3600;
  /** Put the PhotoScout user's id and username in the token response (a PhotoScout that tells apps who they are). */
  tellIdentity = true;
  log: { method: string; path: string; auth: string | null }[] = [];
  tokenCalls: Record<string, string>[] = [];
  revoked: string[] = [];

  events: unknown[] = [];
  communityEvents: unknown[] = [];
  spots: unknown[] = [];
  rsvps: { method: string; path: string; body: unknown }[] = [];
  checkins: unknown[] = [];

  private codes = new Map<string, { challenge: string; redirect: string; grant: Grant; spent: boolean }>();
  private access = new Map<string, { grant: Grant; expires: number }>();
  private refresh = new Map<string, { grant: Grant; used: boolean }>();

  async start(): Promise<void> {
    this.server = createServer((req, res) => { this.handle(req, res).catch((e) => { res.statusCode = 500; res.end(String(e)); }); });
    await new Promise<void>((r) => this.server.listen(0, '127.0.0.1', r));
    this.url = `http://127.0.0.1:${(this.server.address() as AddressInfo).port}`;
  }
  stop(): Promise<void> { return new Promise((r) => { this.server.close(() => r()); this.server.closeAllConnections(); }); }

  /** The browser step: PhotoScout shows the consent page, `user` allows. Returns the redirect it would send the browser to. */
  consent(authorizeUrl: string, user: FakeUser, decision: 'allow' | 'deny' = 'allow'): string {
    const u = new URL(authorizeUrl);
    if (u.origin !== this.url || u.pathname !== '/oauth/authorize') throw new Error(`unexpected authorize URL ${authorizeUrl}`);
    const p = u.searchParams;
    if (p.get('response_type') !== 'code' || p.get('code_challenge_method') !== 'S256' || p.get('client_id') !== this.clientId) throw new Error('bad authorize request');
    const back = new URL(p.get('redirect_uri')!);
    if (p.get('state')) back.searchParams.set('state', p.get('state')!);
    if (decision === 'deny') { back.searchParams.set('error', 'access_denied'); return back.toString(); }
    const code = `psc_${randomBytes(16).toString('hex')}`;
    this.codes.set(code, { challenge: p.get('code_challenge')!, redirect: p.get('redirect_uri')!, grant: { user, clientId: this.clientId, scope: p.get('scope')!, revoked: false }, spent: false });
    back.searchParams.set('code', code);
    return back.toString();
  }

  /** Make every access token already issued stop working, as if it had expired or been revoked early. */
  expireAccessTokens(): void { this.access.clear(); }
  /** End every grant at PhotoScout (the user disconnected the app in PhotoScout's settings). */
  revokeAll(): void { for (const g of [...this.refresh.values()]) g.grant.revoked = true; this.access.clear(); }
  refreshCalls(): number { return this.tokenCalls.filter((c) => c.grant_type === 'refresh_token').length; }

  private json(res: ServerResponse, status: number, body: unknown): void {
    res.writeHead(status, { 'content-type': 'application/json' });
    res.end(JSON.stringify(body));
  }

  private mint(grant: Grant): Record<string, unknown> {
    const a = `psat_${randomBytes(24).toString('base64url')}`;
    const r = `psrt_${randomBytes(24).toString('base64url')}`;
    this.access.set(a, { grant, expires: Date.now() + this.accessTtl * 1000 });
    this.refresh.set(r, { grant, used: false });
    return {
      access_token: a, token_type: 'Bearer', expires_in: this.accessTtl, refresh_token: r, scope: grant.scope,
      ...(this.tellIdentity ? { user_id: grant.user.id, username: grant.user.username } : {}),
    };
  }

  private async handle(req: IncomingMessage, res: ServerResponse): Promise<void> {
    const url = new URL(req.url ?? '/', this.url);
    const chunks: Buffer[] = [];
    for await (const c of req) chunks.push(c as Buffer);
    const raw = Buffer.concat(chunks).toString('utf8');
    const auth = req.headers.authorization ?? null;
    this.log.push({ method: req.method ?? 'GET', path: url.pathname + url.search, auth });

    if (url.pathname === '/oauth/token' && req.method === 'POST') {
      const f = Object.fromEntries(new URLSearchParams(raw));
      this.tokenCalls.push(f);
      if (f.client_id !== this.clientId) return this.json(res, 401, { error: 'invalid_client' });
      if (f.grant_type === 'authorization_code') {
        const c = this.codes.get(f.code);
        if (!c || c.spent) return this.json(res, 400, { error: 'invalid_grant', error_description: 'Unknown or expired code' });
        c.spent = true;
        if (c.redirect !== f.redirect_uri) return this.json(res, 400, { error: 'invalid_grant', error_description: 'redirect_uri does not match' });
        if (createHash('sha256').update(f.code_verifier ?? '').digest('base64url') !== c.challenge) return this.json(res, 400, { error: 'invalid_grant', error_description: 'code_verifier does not match' });
        return this.json(res, 200, this.mint(c.grant));
      }
      if (f.grant_type === 'refresh_token') {
        const r = this.refresh.get(f.refresh_token);
        if (!r || r.grant.revoked) return this.json(res, 400, { error: 'invalid_grant', error_description: 'Unknown refresh token' });
        // Replaying a spent refresh token ends the whole connection, as PhotoScout does.
        if (r.used) { r.grant.revoked = true; this.access.clear(); return this.json(res, 400, { error: 'invalid_grant', error_description: 'Refresh token already used' }); }
        r.used = true;
        return this.json(res, 200, this.mint(r.grant));
      }
      return this.json(res, 400, { error: 'unsupported_grant_type' });
    }
    if (url.pathname === '/oauth/revoke' && req.method === 'POST') {
      const f = Object.fromEntries(new URLSearchParams(raw));
      this.revoked.push(f.token);
      const r = this.refresh.get(f.token);
      if (r) r.grant.revoked = true;
      return this.json(res, 200, {});
    }

    // Everything below is the bearer-protected API.
    const token = /^Bearer (\S+)$/.exec(auth ?? '')?.[1] ?? '';
    const a = this.access.get(token);
    if (!a || a.expires <= Date.now() || a.grant.revoked) return this.json(res, 401, { error: 'Invalid or expired access token' });
    const body = raw ? JSON.parse(raw) : undefined;

    if (url.pathname === '/api/events' && req.method === 'GET') return this.json(res, 200, this.events);
    if (url.pathname === '/api/community-events' && req.method === 'GET') return this.json(res, 200, this.communityEvents);
    let m = /^\/api\/community-events\/([^/]+)$/.exec(url.pathname);
    if (m && req.method === 'GET') {
      const ev = (this.communityEvents as { id: string }[]).find((e) => e.id === m![1]);
      return ev ? this.json(res, 200, ev) : this.json(res, 404, { error: 'No such event' });
    }
    if (url.pathname === '/api/spots' && req.method === 'GET') return this.json(res, 200, this.spots);
    m = /^\/api\/photos\/([^/]+)\/(thumb|file)$/.exec(url.pathname);
    if (m && req.method === 'GET') {
      if (m[1] === 'html') { res.writeHead(200, { 'content-type': 'text/html' }); return void res.end('<script>1</script>'); }
      res.writeHead(200, { 'content-type': 'image/jpeg' });
      return void res.end(Buffer.from(`jpeg-${m[1]}-${m[2]}`));
    }
    m = /^\/api\/(community-events|events)\/([^/]+)\/rsvp$/.exec(url.pathname);
    if (m) {
      this.rsvps.push({ method: req.method ?? '', path: url.pathname, body });
      const mine = req.method === 'DELETE' ? null : (body?.status ?? 'going');
      return this.json(res, 200, { rsvp: { going: mine === 'going' ? 4 : 3, interested: mine === 'interested' ? 1 : 0, mine, attendees: [] } });
    }
    if (url.pathname === '/api/checkins' && req.method === 'POST') {
      this.checkins.push(body);
      if (body.spotId === 'private') return this.json(res, 404, { error: 'No such public spot' });
      return this.json(res, 201, { id: 'post-1', kind: 'checkin', created_at: '2030-01-05T09:00:00.000Z', author: { username: a.grant.user.username, avatar: '' }, body: body.body ?? '', place_name: 'Mount Panorama', lat: -33.44, lng: 149.55, link: null, votes: 0, comments: 0, voted: false });
    }
    return this.json(res, 404, { error: 'Not found' });
  }
}
