import type { FastifyInstance } from 'fastify';
import type { Selectable } from 'kysely';
import { parseBbox } from '../../convoy/geo.js';
import type { HazardReportsTable } from '../../db/types.js';
import { badRequest, conflict, notFound } from '../../errors.js';
import { authenticate, iso, type ApiContext } from '../common.js';
import { limits } from '../limits.js';
import { arrayOf, bearer, bodyObj, errorResponses, HAZARD_KINDS, idParams, nullable, ref } from '../schemas.js';

/** A hazard lives 30 minutes, each confirmation renews that, but never past this long after the first report. */
const LIFETIME_MS = 30 * 60_000;
const MAX_LIFETIME_MS = 4 * 3_600_000;
/** This many "not there" votes remove it. */
const DISMISS_LIMIT = 3;
const MAX_RESULTS = 500;
const MAX_BBOX_DEGREES = 10;

type HazardRow = Selectable<HazardReportsTable>;

export const hazardDto = (h: HazardRow, myAction: 'confirm' | 'dismiss' | null = null) => ({
  id: h.id, kind: h.kind, subkind: h.subkind, lat: h.lat, lng: h.lng, heading: h.heading,
  createdAt: iso(h.created_at), expiresAt: iso(h.expires_at), confirmations: h.confirmations, dismissals: h.dismissals, myAction,
});

/** What goes over the convoy socket (see `hazard` in convoy.v1.schema.json): no vote counts, no reporter. */
export const hazardWire = (h: HazardRow) => ({
  id: h.id, kind: h.kind, subkind: h.subkind, lat: h.lat, lng: h.lng, heading: h.heading,
  createdAt: iso(h.created_at), expiresAt: iso(h.expires_at),
});

export async function hazardRoutes(app: FastifyInstance, ctx: ApiContext): Promise<void> {
  const { db } = ctx;
  const lim = limits(ctx.config);
  const auth = { preHandler: [authenticate(ctx.tokens)] };
  const security = { security: bearer, tags: ['hazards'] };

  app.post('/v1/hazards', {
    ...auth, ...lim.write,
    schema: {
      ...security, summary: 'Report a hazard',
      description: 'Lives 30 minutes. If I am in a convoy room, every member gets a `hazard` message on the socket. Reporters are never shown to other users.',
      body: bodyObj({
        kind: { type: 'string', enum: [...HAZARD_KINDS] },
        subkind: { type: 'string', pattern: '^[a-z0-9_]{0,24}$', description: 'e.g. gravel, oil, debris, speed_camera.' },
        lat: { type: 'number', minimum: -90, maximum: 90 }, lng: { type: 'number', minimum: -180, maximum: 180 },
        heading: { ...nullable({ type: 'number' }), minimum: 0, maximum: 360, description: 'Direction of travel when reported, degrees.' },
      }, ['kind', 'lat', 'lng']),
      response: { 201: ref('Hazard'), ...errorResponses },
    },
  }, async (req, reply) => {
    const b = req.body as { kind: HazardRow['kind']; subkind?: string; lat: number; lng: number; heading?: number | null };
    const codes = ctx.convoy.roomsOf(req.userId);
    const row = await db.insertInto('hazard_reports').values({
      kind: b.kind, subkind: b.subkind ?? '', lat: b.lat, lng: b.lng, heading: b.heading ?? null, reporter_id: req.userId, convoy_code: codes[0] ?? null,
      expires_at: new Date(Date.now() + LIFETIME_MS),
    }).returningAll().executeTakeFirstOrThrow();
    for (const code of codes) ctx.convoy.sendToRoom(code, { t: 'hazard', room: code, hazard: hazardWire(row) });
    return reply.code(201).send(hazardDto(row));
  });

  app.get('/v1/hazards', {
    ...auth,
    schema: {
      ...security, summary: 'Live hazards in a box',
      description: `bbox is "south,west,north,east" in degrees (at most ${MAX_BBOX_DEGREES} degrees each way; west > east crosses the antimeridian). Expired and removed hazards are never returned. At most ${MAX_RESULTS}, newest first.`,
      querystring: bodyObj({ bbox: { type: 'string', maxLength: 100 } }),
      response: { 200: arrayOf(ref('Hazard')), ...errorResponses },
    },
  }, async (req) => {
    let box;
    try { box = parseBbox((req.query as { bbox: string }).bbox); } catch (err) { throw badRequest((err as Error).message); }
    const lngSpan = box.west <= box.east ? box.east - box.west : box.east + 360 - box.west;
    if (box.south < -90 || box.north > 90 || box.south > box.north || Math.abs(box.west) > 180 || Math.abs(box.east) > 180) throw badRequest('bbox out of range');
    if (box.north - box.south > MAX_BBOX_DEGREES || lngSpan > MAX_BBOX_DEGREES) throw badRequest(`bbox is too large (max ${MAX_BBOX_DEGREES} degrees)`);
    let q = db.selectFrom('hazard_reports').selectAll().where('removed_at', 'is', null).where('expires_at', '>', new Date())
      .where('lat', '>=', box.south).where('lat', '<=', box.north);
    q = box.west <= box.east
      ? q.where('lng', '>=', box.west).where('lng', '<=', box.east)
      : q.where((eb) => eb.or([eb('lng', '>=', box.west), eb('lng', '<=', box.east)]));
    const rows = await q.orderBy('created_at', 'desc').limit(MAX_RESULTS).execute();
    const mine = rows.length
      ? await db.selectFrom('hazard_acks').select(['hazard_id', 'action']).where('user_id', '=', req.userId).where('action', 'in', ['confirm', 'dismiss']).where('hazard_id', 'in', rows.map((r) => r.id)).execute()
      : [];
    const vote = new Map(mine.map((m) => [m.hazard_id, m.action as 'confirm' | 'dismiss']));
    return rows.map((h) => hazardDto(h, vote.get(h.id) ?? null));
  });

  async function act(id: string, userId: string, action: 'confirm' | 'dismiss' | 'ack') {
    return db.transaction().execute(async (trx) => {
      const h = await trx.selectFrom('hazard_reports').selectAll().where('id', '=', id).where('removed_at', 'is', null).where('expires_at', '>', new Date()).forUpdate().executeTakeFirst();
      if (!h) throw notFound('No such hazard (it may have expired)');
      const votes = await trx.selectFrom('hazard_acks').select('action').where('hazard_id', '=', id).where('user_id', '=', userId).where('action', 'in', ['confirm', 'dismiss']).execute();
      const prior = (votes[0]?.action as 'confirm' | 'dismiss' | undefined) ?? null;
      if (action === 'ack') {
        await trx.insertInto('hazard_acks').values({ hazard_id: id, user_id: userId, action }).onConflict((oc) => oc.doNothing()).execute();
        return hazardDto(h, prior);
      }
      if (prior === action) return hazardDto(h, prior); // a repeat changes nothing
      if (prior) throw conflict(`You already voted "${prior}" on this hazard`);
      if (action === 'confirm' && h.reporter_id === userId) throw conflict('You reported this hazard');
      await trx.insertInto('hazard_acks').values({ hazard_id: id, user_id: userId, action }).execute();
      const now = Date.now();
      if (action === 'confirm') {
        const renewed = Math.min(Math.max(new Date(h.expires_at).getTime(), now + LIFETIME_MS), new Date(h.created_at).getTime() + MAX_LIFETIME_MS);
        const row = await trx.updateTable('hazard_reports').set({ confirmations: h.confirmations + 1, expires_at: new Date(renewed) }).where('id', '=', id).returningAll().executeTakeFirstOrThrow();
        return hazardDto(row, 'confirm');
      }
      // The reporter taking their own report back removes it; otherwise it takes DISMISS_LIMIT strangers.
      const dismissals = h.dismissals + 1;
      const gone = dismissals >= DISMISS_LIMIT || h.reporter_id === userId;
      const row = await trx.updateTable('hazard_reports').set({ dismissals, removed_at: gone ? new Date(now) : null }).where('id', '=', id).returningAll().executeTakeFirstOrThrow();
      return hazardDto(row, 'dismiss');
    });
  }

  const actions = {
    confirm: 'Still there. Renews the 30 minute lifetime (never past 4 hours from the first report). Once per user; not for my own report.',
    dismiss: `Not there. ${DISMISS_LIMIT} dismissals remove the hazard; the reporter dismissing their own report removes it at once. Once per user.`,
    ack: 'Seen / passed it. Recorded, changes nothing else, idempotent.',
  } as const;
  for (const action of ['confirm', 'dismiss', 'ack'] as const) {
    app.post(`/v1/hazards/:id/${action}`, {
      ...auth, ...lim.write,
      schema: { ...security, summary: `${action[0].toUpperCase()}${action.slice(1)} a hazard`, description: actions[action], params: idParams, response: { 200: ref('Hazard'), ...errorResponses } },
    }, async (req) => act((req.params as { id: string }).id, req.userId, action));
  }
}
