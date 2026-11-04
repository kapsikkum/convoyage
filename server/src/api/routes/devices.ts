import type { FastifyInstance } from 'fastify';
import { authenticate, type ApiContext } from '../common.js';
import { limits } from '../limits.js';
import { bearer, bodyObj, errorResponses, ref } from '../schemas.js';
import { validEndpoint } from '../../push/webpush.js';
import { badRequest } from '../../errors.js';

const MAX_DEVICES_PER_USER = 10;
const tokenField = { type: 'string', minLength: 8, maxLength: 4096 } as const;
const BODY = bodyObj({
  platform: { type: 'string', enum: ['ios', 'android', 'unifiedpush'] }, token: tokenField,
  p256dh: { type: 'string', pattern: '^[A-Za-z0-9_-]{80,100}={0,2}$' }, auth: { type: 'string', pattern: '^[A-Za-z0-9_-]{16,32}={0,2}$' },
}, ['platform', 'token']);

export async function deviceRoutes(app: FastifyInstance, ctx: ApiContext): Promise<void> {
  const { db } = ctx;
  const lim = limits(ctx.config);
  const auth = { preHandler: [authenticate(ctx.tokens)] };

  app.post('/v1/me/devices', {
    ...auth, ...lim.write,
    schema: {
      security: bearer, tags: ['devices'], summary: 'Register (or refresh) a push token for this device',
      description: 'Idempotent. A token already registered to another account moves to this one. At most 10 devices per account (oldest dropped). '
        + 'For `unifiedpush` (builds without Google services) the token is the distributor endpoint (a public https URL) and `p256dh`/`auth` are the '
        + 'app Web Push keys (base64url); notifications are then sent encrypted (RFC 8291) as JSON `{ title, body, data }`.',
      body: BODY,
      response: { 200: ref('Ok'), ...errorResponses },
    },
  }, async (req) => {
    const b = req.body as { platform: 'ios' | 'android' | 'unifiedpush'; token: string; p256dh?: string; auth?: string };
    if (b.platform === 'unifiedpush' && (!b.p256dh || !b.auth || !validEndpoint(b.token))) {
      throw badRequest('unifiedpush needs a public https endpoint and its p256dh and auth keys');
    }
    const keys = b.platform === 'unifiedpush' ? { p256dh: b.p256dh!, auth: b.auth! } : { p256dh: null, auth: null };
    await db.insertInto('device_tokens').values({ token: b.token, user_id: req.userId, platform: b.platform, ...keys })
      .onConflict((oc) => oc.column('token').doUpdateSet({ user_id: req.userId, platform: b.platform, ...keys, updated_at: new Date() })).execute();
    const rows = await db.selectFrom('device_tokens').select('token').where('user_id', '=', req.userId).orderBy('updated_at', 'desc').orderBy('token').execute();
    const stale = rows.slice(MAX_DEVICES_PER_USER).map((r) => r.token);
    if (stale.length) await db.deleteFrom('device_tokens').where('token', 'in', stale).execute();
    return { ok: true };
  });

  app.delete('/v1/me/devices', {
    ...auth, ...lim.write,
    schema: {
      security: bearer, tags: ['devices'], summary: 'Forget a push token (sign-out, or notifications turned off)',
      description: 'Send `{ "token": ... }` as the JSON body. Idempotent; only removes tokens that belong to me.',
      body: bodyObj({ token: tokenField }),
      response: { 200: ref('Ok'), ...errorResponses },
    },
  }, async (req) => {
    await db.deleteFrom('device_tokens').where('token', '=', (req.body as { token: string }).token).where('user_id', '=', req.userId).execute();
    return { ok: true };
  });
}
