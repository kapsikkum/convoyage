import { createHash, randomBytes } from 'node:crypto';
import type { Kysely } from 'kysely';
import type { PhotoScoutConfig } from '../config.js';
import type { DB } from '../db/index.js';
import { HttpError } from '../errors.js';
import type { Fetch } from '../http.js';
import { open, seal } from './seal.js';

/** What Convoyage asks PhotoScout for. These must be scopes PhotoScout offers (see the README). */
export const PHOTOSCOUT_SCOPES = 'events:read events:rsvp spots:read routes:read checkins:write';
export const APP_LINKED_URL = 'convoyage://photoscout/linked';

const STATE_TTL_MS = 10 * 60_000;
/** Refresh this long before the access token runs out. */
const EXPIRY_SKEW_MS = 30_000;
const TIMEOUT_MS = 8000;

/** A failure with the HTTP status and the machine-readable `error` code the REST layer answers with. */
export class PhotoScoutError extends HttpError {
  constructor(status: number, public code: string, message?: string) { super(status, message ?? code); }
  get status(): number { return this.statusCode; }
}
export const notLinked = () => new PhotoScoutError(409, 'photoscout_not_linked', 'Link your PhotoScout account first');

const sha256 = (v: string) => createHash('sha256').update(v).digest('hex');
const b64url = (n: number) => randomBytes(n).toString('base64url');

interface LinkRow {
  user_id: string; access_token_enc: string; refresh_token_enc: string; access_expires_at: Date | string; scope: string;
  ps_user_id: string | null; ps_username: string | null; created_at: Date | string;
}

interface TokenResponse { accessToken: string; refreshToken: string; expiresIn: number; scope: string; psUserId: string | null; psUsername: string | null }

export interface LinkStatus { linked: boolean; username: string | null; scope: string; linkedAt: string | null }

/** The link between a Convoyage user and their PhotoScout account: connect flow, token storage and refresh, authenticated calls. */
export class PhotoScoutService {
  /** One refresh at a time per user: PhotoScout rotates refresh tokens, so a second use of the old one would end the connection. */
  private refreshing = new Map<string, Promise<string>>();

  constructor(private db: Kysely<DB>, readonly config: PhotoScoutConfig, private fetcher: Fetch) {}

  get redirectUri(): string { return `${this.config.publicBaseUrl}/v1/photoscout/callback`; }

  private aad = (userId: string, field: string) => `photoscout:${userId}:${field}`;
  private sealFor = (userId: string, field: string, v: string) => seal(this.config.tokenKey, v, this.aad(userId, field));
  private openFor = (userId: string, field: string, v: string) => open(this.config.tokenKey, v, this.aad(userId, field));

  // ---- connect ---------------------------------------------------------------------------------------------

  /** Start the flow: remember a PKCE verifier and a state for this user, and give back PhotoScout's authorize URL. */
  async begin(userId: string): Promise<string> {
    const verifier = b64url(48);
    const state = b64url(24);
    const challenge = createHash('sha256').update(verifier).digest('base64url');
    await this.db.deleteFrom('photoscout_oauth_states').where((eb) => eb.or([eb('expires_at', '<', new Date()), eb('user_id', '=', userId)])).execute();
    await this.db.insertInto('photoscout_oauth_states').values({
      state_hash: sha256(state), user_id: userId, verifier_enc: this.sealFor(userId, 'verifier', verifier), expires_at: new Date(Date.now() + STATE_TTL_MS),
    }).execute();
    const q = new URLSearchParams({
      response_type: 'code', client_id: this.config.clientId, redirect_uri: this.redirectUri, scope: PHOTOSCOUT_SCOPES, state,
      code_challenge: challenge, code_challenge_method: 'S256',
    });
    return `${this.config.url}/oauth/authorize?${q}`;
  }

  /** The browser came back from PhotoScout. Returns where to send it next (always the app's deep link). */
  async complete(q: { code?: string; state?: string; error?: string }): Promise<string> {
    const back = (error?: string) => (error ? `${APP_LINKED_URL}?error=${encodeURIComponent(error)}` : APP_LINKED_URL);
    if (!q.state) return back('invalid_state');
    // Spend the state: one use, and only inside its lifetime.
    const row = await this.db.deleteFrom('photoscout_oauth_states').where('state_hash', '=', sha256(q.state)).returningAll().executeTakeFirst();
    if (!row || new Date(row.expires_at).getTime() <= Date.now()) return back('invalid_state');
    if (q.error) return back(q.error.slice(0, 60).replace(/[^\w.-]/g, '') || 'access_denied');
    if (!q.code) return back('invalid_request');
    const verifier = this.openFor(row.user_id, 'verifier', row.verifier_enc);
    if (!verifier) return back('invalid_state');
    let tok: TokenResponse;
    try {
      tok = await this.tokenRequest({ grant_type: 'authorization_code', code: q.code, redirect_uri: this.redirectUri, code_verifier: verifier });
    } catch (e) {
      return back(e instanceof PhotoScoutError && e.status === 400 ? 'exchange_rejected' : 'exchange_failed');
    }
    await this.store(row.user_id, tok, true);
    return back();
  }

  // ---- state -----------------------------------------------------------------------------------------------

  private async link(userId: string): Promise<LinkRow | undefined> {
    return this.db.selectFrom('photoscout_links').selectAll().where('user_id', '=', userId).executeTakeFirst() as Promise<LinkRow | undefined>;
  }

  async status(userId: string): Promise<LinkStatus> {
    const l = await this.link(userId);
    return l
      ? { linked: true, username: l.ps_username, scope: l.scope, linkedAt: new Date(l.created_at).toISOString() }
      : { linked: false, username: null, scope: '', linkedAt: null };
  }

  /** The PhotoScout identity captured at link time, when PhotoScout told us. */
  async identity(userId: string): Promise<{ id: string | null; username: string | null } | null> {
    const l = await this.link(userId);
    return l ? { id: l.ps_user_id, username: l.ps_username } : null;
  }

  /** Revoke at PhotoScout (best effort: its RFC 7009 endpoint) and forget the tokens. False when there was no link. */
  async unlink(userId: string): Promise<boolean> {
    const l = await this.link(userId);
    if (!l) return false;
    const refresh = this.openFor(userId, 'refresh', l.refresh_token_enc);
    await this.drop(userId);
    if (refresh) {
      try {
        await this.fetcher(`${this.config.url}/oauth/revoke`, {
          method: 'POST', headers: { 'content-type': 'application/x-www-form-urlencoded' },
          body: this.form({ token: refresh }), signal: AbortSignal.timeout(TIMEOUT_MS),
        });
      } catch { /* PhotoScout is unreachable: the local tokens are gone, and the user can end it under PhotoScout's own settings */ }
    }
    return true;
  }

  // ---- tokens ----------------------------------------------------------------------------------------------

  private form(fields: Record<string, string>): string {
    const f = new URLSearchParams({ client_id: this.config.clientId, ...fields });
    if (this.config.clientSecret) f.set('client_secret', this.config.clientSecret);
    return f.toString();
  }

  private async tokenRequest(fields: Record<string, string>): Promise<TokenResponse> {
    let res: Response;
    try {
      res = await this.fetcher(`${this.config.url}/oauth/token`, {
        method: 'POST', headers: { 'content-type': 'application/x-www-form-urlencoded', accept: 'application/json' },
        body: this.form(fields), signal: AbortSignal.timeout(TIMEOUT_MS),
      });
    } catch { throw new PhotoScoutError(502, 'photoscout_unavailable', 'PhotoScout did not answer'); }
    const body = (await res.json().catch(() => null)) as Record<string, unknown> | null;
    if (res.status === 400 || res.status === 401) throw new PhotoScoutError(400, String(body?.error ?? 'invalid_grant'), String(body?.error_description ?? 'PhotoScout refused the token request'));
    if (!res.ok) throw new PhotoScoutError(502, 'photoscout_unavailable', `PhotoScout answered ${res.status}`);
    if (!body || typeof body.access_token !== 'string' || typeof body.refresh_token !== 'string') throw new PhotoScoutError(502, 'photoscout_unavailable', 'PhotoScout sent an unusable token response');
    const expiresIn = typeof body.expires_in === 'number' && body.expires_in > 0 ? body.expires_in : 3600;
    const str = (v: unknown) => (typeof v === 'string' && v ? v.slice(0, 120) : null);
    return {
      accessToken: body.access_token, refreshToken: body.refresh_token, expiresIn, scope: typeof body.scope === 'string' ? body.scope : '',
      psUserId: str(body.user_id), psUsername: str(body.username),
    };
  }

  private async store(userId: string, t: TokenResponse, fresh: boolean): Promise<void> {
    const values = {
      access_token_enc: this.sealFor(userId, 'access', t.accessToken), refresh_token_enc: this.sealFor(userId, 'refresh', t.refreshToken),
      access_expires_at: new Date(Date.now() + t.expiresIn * 1000), scope: t.scope, updated_at: new Date(),
    };
    if (fresh) {
      await this.db.insertInto('photoscout_links').values({ user_id: userId, ...values, ps_user_id: t.psUserId, ps_username: t.psUsername })
        .onConflict((oc) => oc.column('user_id').doUpdateSet({ ...values, ps_user_id: t.psUserId, ps_username: t.psUsername, created_at: new Date() })).execute();
    } else {
      await this.db.updateTable('photoscout_links').set({
        ...values, ...(t.psUserId ? { ps_user_id: t.psUserId } : {}), ...(t.psUsername ? { ps_username: t.psUsername } : {}),
      }).where('user_id', '=', userId).execute();
    }
  }

  /** A usable access token, refreshing first when it is (nearly) expired. 409 not linked when there is no link or it was ended. */
  private async accessToken(userId: string): Promise<string> {
    const l = await this.link(userId);
    if (!l) throw notLinked();
    const token = this.openFor(userId, 'access', l.access_token_enc);
    if (token && new Date(l.access_expires_at).getTime() - EXPIRY_SKEW_MS > Date.now()) return token;
    return this.refresh(userId, null);
  }

  /** `stale` is the access token that just failed: if the stored one has since changed, someone already refreshed. */
  private refresh(userId: string, stale: string | null): Promise<string> {
    const running = this.refreshing.get(userId);
    if (running) return running;
    const p = this.doRefresh(userId, stale).finally(() => this.refreshing.delete(userId));
    this.refreshing.set(userId, p);
    return p;
  }

  private async doRefresh(userId: string, stale: string | null): Promise<string> {
    const l = await this.link(userId);
    if (!l) throw notLinked();
    const current = this.openFor(userId, 'access', l.access_token_enc);
    if (current && current !== stale && new Date(l.access_expires_at).getTime() - EXPIRY_SKEW_MS > Date.now()) return current;
    const refresh = this.openFor(userId, 'refresh', l.refresh_token_enc);
    if (!refresh) { await this.drop(userId); throw notLinked(); }
    let tok: TokenResponse;
    try {
      tok = await this.tokenRequest({ grant_type: 'refresh_token', refresh_token: refresh });
    } catch (e) {
      // PhotoScout says the connection is over (revoked there, expired, or the refresh token was replayed).
      if (e instanceof PhotoScoutError && e.status === 400) { await this.drop(userId); throw notLinked(); }
      throw e;
    }
    await this.store(userId, tok, false);
    return tok.accessToken;
  }

  private drop(userId: string) { return this.db.deleteFrom('photoscout_links').where('user_id', '=', userId).execute(); }

  // ---- calling PhotoScout as the user ------------------------------------------------------------------------------

  /** A raw response from PhotoScout's API for this user. One refresh-and-retry on a 401. Network failure is 502. */
  async request(userId: string, path: string, init: { method?: string; body?: unknown } = {}): Promise<Response> {
    const send = async (token: string): Promise<Response> => {
      try {
        return await this.fetcher(`${this.config.url}${path}`, {
          method: init.method ?? 'GET',
          headers: { authorization: `Bearer ${token}`, accept: 'application/json', ...(init.body !== undefined ? { 'content-type': 'application/json' } : {}) },
          body: init.body === undefined ? undefined : JSON.stringify(init.body),
          signal: AbortSignal.timeout(TIMEOUT_MS),
        });
      } catch { throw new PhotoScoutError(502, 'photoscout_unavailable', 'PhotoScout did not answer'); }
    };
    const token = await this.accessToken(userId);
    let res = await send(token);
    if (res.status === 401) {
      res = await send(await this.refresh(userId, token));
      if (res.status === 401) { await this.drop(userId); throw notLinked(); }
    }
    return res;
  }

  /** Parsed JSON from PhotoScout, or a PhotoScoutError that says what happened in Convoyage's terms. */
  async json<T = unknown>(userId: string, path: string, init: { method?: string; body?: unknown } = {}): Promise<T> {
    const res = await this.checked(await this.request(userId, path, init));
    return res.json() as Promise<T>;
  }

  /** Maps a non-2xx PhotoScout answer to an error; passes a good one through. */
  async checked(res: Response): Promise<Response> {
    if (res.ok) return res;
    const body = (await res.json().catch(() => null)) as { error?: unknown } | null;
    const msg = typeof body?.error === 'string' ? body.error.slice(0, 200) : `PhotoScout answered ${res.status}`;
    if (res.status === 404) throw new PhotoScoutError(404, 'photoscout_not_found', msg);
    if (res.status === 403) throw new PhotoScoutError(403, 'photoscout_forbidden', msg);
    if (res.status === 400 || res.status === 409 || res.status === 429) throw new PhotoScoutError(res.status, 'photoscout_rejected', msg);
    throw new PhotoScoutError(502, 'photoscout_unavailable', msg);
  }
}
