import type { FastifyInstance } from 'fastify';
import { randomBytes } from 'node:crypto';
import type { Kysely } from 'kysely';
import type { DB } from '../../db/index.js';
import { forbidden, HttpError, unauthorized } from '../../errors.js';
import { randomToken, sha256hex } from '../../security.js';
import type { ApiContext } from '../common.js';
import { limits } from '../limits.js';
import type { IdTokenVerifier } from '../providers.js';
import { bodyObj, errorResponses, ref } from '../schemas.js';

const MAX_LINKS_PER_EMAIL_PER_HOUR = 5;

interface Resolved { userId: string; created: boolean }

/** The user behind a verified identity, creating the user, identity and a starter profile on first sight. */
async function resolveUser(
  db: Kysely<DB>, provider: 'email' | 'apple' | 'google', subject: string,
  email: string | null, emailVerified: boolean, displayName: string | null,
): Promise<Resolved> {
  return db.transaction().execute(async (trx) => {
    const known = await trx.selectFrom('auth_identities').select('user_id').where('provider', '=', provider).where('subject', '=', subject).executeTakeFirst();
    if (known) return { userId: known.user_id, created: false };

    let userId: string | undefined;
    let created = false;
    // Only a *verified* email may attach a new sign-in method to an existing account.
    if (email && emailVerified) userId = (await trx.selectFrom('users').select('id').where('email', '=', email).executeTakeFirst())?.id;
    if (!userId) {
      userId = (await trx.insertInto('users').values({ email: emailVerified ? email : null }).returning('id').executeTakeFirstOrThrow()).id;
      created = true;
      const handle = `rider_${randomBytes(4).toString('hex')}`;
      await trx.insertInto('profiles').values({ user_id: userId, handle, display_name: (displayName?.trim() || handle).slice(0, 60) }).execute();
    }
    await trx.insertInto('auth_identities').values({ user_id: userId, provider, subject, email }).execute();
    return { userId, created };
  });
}

export async function authRoutes(app: FastifyInstance, ctx: ApiContext): Promise<void> {
  const lim = limits(ctx.config);

  async function login(userId: string, created: boolean, userAgent: string | undefined) {
    const user = await ctx.db.selectFrom('users').select('disabled_at').where('id', '=', userId).executeTakeFirst();
    if (!user || user.disabled_at) throw forbidden('This account is disabled');
    const pair = await ctx.tokens.startSession(userId, userAgent ?? null);
    return { ...pair, isNewUser: created };
  }

  app.post('/auth/magic-link', {
    ...lim.authStrict,
    schema: {
      tags: ['auth'], summary: 'Email a one-time sign-in link',
      description: 'Always answers 202 so addresses cannot be probed. With MAIL_DEV=1 the link is also returned as `devLink`.',
      body: bodyObj({ email: { type: 'string', format: 'email', maxLength: 254 } }),
      response: { 202: bodyObj({ ok: { type: 'boolean' }, devLink: { type: 'string' } }, ['ok']), ...errorResponses },
    },
  }, async (req, reply) => {
    const email = (req.body as { email: string }).email.trim().toLowerCase();
    const recent = await ctx.db.selectFrom('magic_link_tokens').select((eb) => eb.fn.countAll<number>().as('n'))
      .where('email', '=', email).where('created_at', '>', new Date(Date.now() - 3_600_000)).executeTakeFirstOrThrow();
    if (Number(recent.n) >= MAX_LINKS_PER_EMAIL_PER_HOUR) return reply.code(202).send({ ok: true });

    const token = randomToken();
    await ctx.db.insertInto('magic_link_tokens').values({
      email, token_hash: sha256hex(token), expires_at: new Date(Date.now() + ctx.config.magicLinkTtlS * 1000),
    }).execute();
    const base = ctx.config.magicLinkBaseUrl;
    const link = `${base}${base.includes('?') ? '&' : '?'}token=${token}`;
    if (ctx.config.mailDev) req.log.info({ email, link }, 'magic link (MAIL_DEV)');
    try { await ctx.mailer.sendMagicLink(email, link); } catch (err) { req.log.error({ err }, 'sending magic link failed'); }
    return reply.code(202).send(ctx.config.mailDev ? { ok: true, devLink: link } : { ok: true });
  });

  app.post('/auth/magic-link/verify', {
    ...lim.authStrict,
    schema: {
      tags: ['auth'], summary: 'Exchange a magic-link token for tokens',
      body: bodyObj({ token: { type: 'string', minLength: 20, maxLength: 200 } }),
      response: { 200: ref('AuthResult'), ...errorResponses },
    },
  }, async (req) => {
    const hash = sha256hex((req.body as { token: string }).token);
    // Single use: only one request can flip used_at from null.
    const row = await ctx.db.updateTable('magic_link_tokens').set({ used_at: new Date() })
      .where('token_hash', '=', hash).where('used_at', 'is', null).where('expires_at', '>', new Date())
      .returning('email').executeTakeFirst();
    if (!row) throw unauthorized('This link is invalid, expired or already used');
    const { userId, created } = await resolveUser(ctx.db, 'email', row.email, row.email, true, null);
    return login(userId, created, req.headers['user-agent']);
  });

  const social = (provider: 'apple' | 'google', label: string) => {
    app.post(`/auth/${provider}`, {
      ...lim.auth,
      schema: {
        tags: ['auth'], summary: `Sign in with ${label}`,
        description: `Verifies the ${label} ID token signature (provider JWKS), issuer, audience and expiry. Answers 501 when the server has no ${label} audiences configured.`,
        body: bodyObj({
          idToken: { type: 'string', minLength: 20, maxLength: 8192 },
          nonce: { type: 'string', maxLength: 256, description: 'If sent, must match the nonce claim of the token (raw or SHA-256 hex).' },
          displayName: { type: 'string', maxLength: 60, description: 'Used only when this sign-in creates the account.' },
        }, ['idToken']),
        response: { 200: ref('AuthResult'), ...errorResponses, 501: ref('Error') },
      },
    }, async (req) => {
      const verifier: IdTokenVerifier | undefined = ctx.providers[provider];
      if (!verifier) throw new HttpError(501, `Sign in with ${label} is not configured`);
      const body = req.body as { idToken: string; nonce?: string; displayName?: string };
      const claims = await verifier.verify(body.idToken, body.nonce);
      const { userId, created } = await resolveUser(ctx.db, provider, claims.subject, claims.email, claims.emailVerified, body.displayName ?? null);
      return login(userId, created, req.headers['user-agent']);
    });
  };
  social('apple', 'Apple');
  social('google', 'Google');

  app.post('/auth/refresh', {
    ...lim.auth,
    schema: {
      tags: ['auth'], summary: 'Rotate a refresh token',
      description: 'The old token stops working. Presenting a token that was already used revokes the whole session family.',
      body: bodyObj({ refreshToken: { type: 'string', minLength: 20, maxLength: 200 } }),
      response: { 200: ref('TokenPair'), ...errorResponses },
    },
  }, async (req) => ctx.tokens.rotate((req.body as { refreshToken: string }).refreshToken, req.headers['user-agent'] ?? null));

  app.post('/auth/logout', {
    ...lim.auth,
    schema: {
      tags: ['auth'], summary: 'Revoke a session',
      body: bodyObj({ refreshToken: { type: 'string', minLength: 1, maxLength: 200 } }),
      response: { 200: ref('Ok'), ...errorResponses },
    },
  }, async (req) => {
    await ctx.tokens.revoke((req.body as { refreshToken: string }).refreshToken);
    return { ok: true };
  });
}
