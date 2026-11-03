import { SignJWT, jwtVerify } from 'jose';
import type { Kysely } from 'kysely';
import { randomUUID } from 'node:crypto';
import type { DB } from '../db/index.js';
import { unauthorized } from '../errors.js';
import { randomToken, sha256hex } from '../security.js';

export const JWT_ISSUER = 'convoyage';
export const JWT_AUDIENCE = 'convoyage-api';

export interface TokenPair {
  accessToken: string;
  refreshToken: string;
  tokenType: 'Bearer';
  /** Access token lifetime in seconds. */
  expiresIn: number;
}

export interface TokenConfig { jwtSecret: string; accessTtlS: number; refreshTtlDays: number }

/** Short-lived HS256 access JWTs plus rotating, hashed, family-tracked refresh tokens. */
export class Tokens {
  private key: Uint8Array;
  constructor(private db: Kysely<DB>, private cfg: TokenConfig) {
    this.key = new TextEncoder().encode(cfg.jwtSecret);
  }

  async signAccess(userId: string): Promise<string> {
    const now = Math.floor(Date.now() / 1000);
    return new SignJWT({})
      .setProtectedHeader({ alg: 'HS256' })
      .setSubject(userId)
      .setIssuer(JWT_ISSUER)
      .setAudience(JWT_AUDIENCE)
      .setIssuedAt(now)
      .setExpirationTime(now + this.cfg.accessTtlS)
      .sign(this.key);
  }

  /** The user id in a valid access token, or null. */
  async verifyAccess(token: string): Promise<string | null> {
    try {
      const { payload } = await jwtVerify(token, this.key, { issuer: JWT_ISSUER, audience: JWT_AUDIENCE, algorithms: ['HS256'] });
      return typeof payload.sub === 'string' ? payload.sub : null;
    } catch {
      return null;
    }
  }

  /** Like verifyAccess, but also requires the account to still exist and not be disabled. */
  async authenticate(token: string): Promise<string | null> {
    const id = await this.verifyAccess(token);
    if (!id) return null;
    const u = await this.db.selectFrom('users').select('disabled_at').where('id', '=', id).executeTakeFirst();
    return u && !u.disabled_at ? id : null;
  }

  private async pair(userId: string, familyId: string, userAgent: string | null, db: Kysely<DB>): Promise<TokenPair> {
    const refreshToken = randomToken();
    await db.insertInto('refresh_tokens').values({
      user_id: userId,
      family_id: familyId,
      token_hash: sha256hex(refreshToken),
      expires_at: new Date(Date.now() + this.cfg.refreshTtlDays * 86_400_000),
      user_agent: userAgent?.slice(0, 200) ?? null,
    }).execute();
    return { accessToken: await this.signAccess(userId), refreshToken, tokenType: 'Bearer', expiresIn: this.cfg.accessTtlS };
  }

  /** A new login: a fresh token family. */
  startSession(userId: string, userAgent: string | null, db: Kysely<DB> = this.db): Promise<TokenPair> {
    return this.pair(userId, randomUUID(), userAgent, db);
  }

  /**
   * Exchange a refresh token for a new pair. A token that was already used (or revoked) means it leaked or a
   * client raced itself: the whole family is revoked and the caller must sign in again.
   */
  async rotate(refreshToken: string, userAgent: string | null): Promise<TokenPair> {
    const hash = sha256hex(refreshToken);
    const outcome = await this.db.transaction().execute(async (trx) => {
      const row = await trx.selectFrom('refresh_tokens').selectAll().where('token_hash', '=', hash).forUpdate().executeTakeFirst();
      if (!row) return { ok: false as const };
      if (row.used_at || row.revoked_at) {
        await trx.updateTable('refresh_tokens').set({ revoked_at: new Date() })
          .where('family_id', '=', row.family_id).where('revoked_at', 'is', null).execute();
        return { ok: false as const };
      }
      if (new Date(row.expires_at).getTime() <= Date.now()) return { ok: false as const };
      await trx.updateTable('refresh_tokens').set({ used_at: new Date() }).where('id', '=', row.id).execute();
      return { ok: true as const, pair: await this.pair(row.user_id, row.family_id, userAgent, trx as unknown as Kysely<DB>) };
    });
    if (!outcome.ok) throw unauthorized('Invalid refresh token');
    return outcome.pair;
  }

  /** Revoke the family a refresh token belongs to. Unknown tokens are ignored (logout is idempotent). */
  async revoke(refreshToken: string): Promise<void> {
    const row = await this.db.selectFrom('refresh_tokens').select('family_id').where('token_hash', '=', sha256hex(refreshToken)).executeTakeFirst();
    if (!row) return;
    await this.db.updateTable('refresh_tokens').set({ revoked_at: new Date() })
      .where('family_id', '=', row.family_id).where('revoked_at', 'is', null).execute();
  }
}
