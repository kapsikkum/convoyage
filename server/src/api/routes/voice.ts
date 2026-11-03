import type { FastifyInstance } from 'fastify';
import { AccessToken } from 'livekit-server-sdk';
import { HttpError, forbidden } from '../../errors.js';
import { CODE_PATTERN, normalizeCode } from '../../convoy/rooms.js';
import { authenticate, type ApiContext } from '../common.js';
import { limits } from '../limits.js';
import { bearer, bodyObj, errorResponses } from '../schemas.js';

const TOKEN_TTL_S = 3600;

export const voiceRoom = (code: string) => `convoy-${code}`;

export async function voiceRoutes(app: FastifyInstance, ctx: ApiContext): Promise<void> {
  const lim = limits(ctx.config);
  app.post('/v1/voice/token', {
    preHandler: [authenticate(ctx.tokens)], ...lim.write,
    schema: {
      security: bearer, tags: ['voice'], summary: 'LiveKit access token for push-to-talk in my convoy',
      description: 'Only while I am a member of the convoy room. The LiveKit room is `convoy-<code>`, the identity is my user id and the name my display name. 501 when LiveKit is not configured.',
      body: bodyObj({ convoyCode: { type: 'string', minLength: 1, maxLength: 32 } }),
      response: {
        200: bodyObj({
          token: { type: 'string' }, url: { type: 'string', description: 'LiveKit server URL (ws://...).' }, room: { type: 'string' },
          identity: { type: 'string' }, expiresIn: { type: 'integer' },
        }),
        ...errorResponses, 501: { $ref: 'Error#' },
      },
    },
  }, async (req) => {
    const lk = ctx.config.livekit;
    if (!lk) throw new HttpError(501, 'Voice is not configured on this server');
    const code = normalizeCode((req.body as { convoyCode: string }).convoyCode);
    if (!CODE_PATTERN.test(code) || !ctx.convoy.roomsOf(req.userId).includes(code)) throw forbidden('Join the convoy before asking for voice');
    const p = await ctx.db.selectFrom('profiles').select(['display_name', 'handle']).where('user_id', '=', req.userId).executeTakeFirst();
    const name = (p?.display_name || p?.handle || 'Rider').slice(0, 60);
    const at = new AccessToken(lk.apiKey, lk.apiSecret, { identity: req.userId, name, ttl: TOKEN_TTL_S });
    at.addGrant({ roomJoin: true, room: voiceRoom(code), canPublish: true, canSubscribe: true });
    return { token: await at.toJwt(), url: lk.url, room: voiceRoom(code), identity: req.userId, expiresIn: TOKEN_TTL_S };
  });
}
