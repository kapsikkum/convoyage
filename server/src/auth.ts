import type { IncomingMessage } from 'node:http';
import type { Kysely } from 'kysely';
import type { Tokens } from './api/tokens.js';
import type { DB } from './db/index.js';
import type { Authenticator } from './ports.js';
import { roomBadge } from './api/common.js';

/**
 * DEV ONLY (CONVOYAGE_DEV_AUTH=1): anyone can be anyone via `?dev_user=alice&name=Alice`.
 * Config refuses to start with this enabled in production.
 */
export const devAuthenticator: Authenticator = {
  authenticate(req) {
    const url = new URL(req.url ?? '/', 'http://x');
    const id = url.searchParams.get('dev_user')?.trim();
    if (!id || id.length > 64) return null;
    const username = (url.searchParams.get('name')?.trim() || id).slice(0, 40);
    return { id, username, avatar: '' };
  },
};

/** Refuses everyone. */
export const denyAuthenticator: Authenticator = { authenticate: () => null };

/** Dev authenticator only when CONVOYAGE_DEV_AUTH=1, else refuse everyone. Used when there is no database. */
export const authFromEnv = (env: NodeJS.ProcessEnv = process.env): Authenticator =>
  env.CONVOYAGE_DEV_AUTH === '1' ? devAuthenticator : denyAuthenticator;

/** The access token offered on a socket: `Sec-WebSocket-Protocol: convoyage.v1, bearer.<jwt>` (preferred) or `?access_token=`. */
export function socketToken(req: IncomingMessage): string | null {
  const offered = String(req.headers['sec-websocket-protocol'] ?? '').split(',').map((s) => s.trim());
  const carrier = offered.find((p) => p.startsWith('bearer.'));
  if (carrier && carrier.length > 7) return carrier.slice(7);
  const query = new URL(req.url ?? '/', 'http://x').searchParams.get('access_token');
  return query || null;
}

/** Real authentication: a valid access JWT whose user still has a profile. */
export function jwtAuthenticator(tokens: Tokens, db: Kysely<DB>): Authenticator {
  return {
    async authenticate(req) {
      const token = socketToken(req);
      const userId = token ? await tokens.verifyAccess(token) : null;
      if (!userId) return null;
      const p = await db.selectFrom('profiles').innerJoin('users', 'users.id', 'profiles.user_id')
        .select(['handle', 'display_name', 'avatar_media_id', 'users.disabled_at as disabled_at', 'users.expires_at as expires_at']).where('user_id', '=', userId).executeTakeFirst();
      if (!p || p.disabled_at || (p.expires_at && new Date(p.expires_at).getTime() <= Date.now())) return null;
      return { id: userId, username: (p.display_name || p.handle).slice(0, 40), avatar: p.avatar_media_id ? `/v1/media/${p.avatar_media_id}/thumb` : '', vehicle: await roomBadge(db, userId) };
    },
  };
}

/** Try each authenticator in turn; the first that recognises the caller wins. */
export const firstOf = (...auths: Authenticator[]): Authenticator => ({
  async authenticate(req) {
    for (const a of auths) {
      const who = await a.authenticate(req);
      if (who) return who;
    }
    return null;
  },
});
