import type { FastifyInstance } from 'fastify';
import { authenticate, type ApiContext } from '../common.js';
import { limits } from '../limits.js';
import { bearer, bodyObj, errorResponses, ref } from '../schemas.js';

const MAX_DEVICES_PER_USER = 10;
const tokenField = { type: 'string', minLength: 8, maxLength: 4096 } as const;

export async function deviceRoutes(app: FastifyInstance, ctx: ApiContext): Promise<void> {
  const { db } = ctx;
  const lim = limits(ctx.config);
  const auth = { preHandler: [authenticate(ctx.tokens)] };

  app.post('/v1/me/devices', {
    ...auth, ...lim.write,
    schema: {
      security: bearer, tags: ['devices'], summary: 'Register (or refresh) a push token for this device',
      description: 'Idempotent. A token already registered to another account moves to this one. At most 10 devices per account (oldest dropped).',
      body: bodyObj({ platform: { type: 'string', enum: ['ios', 'android'] }, token: tokenField }),
      response: { 200: ref('Ok'), ...errorResponses },
    },
  }, async (req) => {
    const b = req.body as { platform: 'ios' | 'android'; token: string };
    await db.insertInto('device_tokens').values({ token: b.token, user_id: req.userId, platform: b.platform })
      .onConflict((oc) => oc.column('token').doUpdateSet({ user_id: req.userId, platform: b.platform, updated_at: new Date() })).execute();
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
