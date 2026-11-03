import type { FastifyInstance } from 'fastify';
import type { Updateable } from 'kysely';
import type { RidesTable } from '../../db/types.js';
import { badRequest, conflict, notFound } from '../../errors.js';
import { CODE_PATTERN } from '../../convoy/rooms.js';
import { authenticate, beforeCursor, decodeCursor, encodeCursor, page, visibleTo, type ApiContext } from '../common.js';
import { rideSummaryDto, ridePhotoDto } from '../dto.js';
import { limits } from '../limits.js';
import { decodePolyline } from '../polyline.js';
import { userByHandle } from './users.js';
import { bearer, bodyObj, cursorQuery, dateTime, errorResponses, idParams, nullable, ref, uuid, VISIBILITIES } from '../schemas.js';

const MAX_POLYLINE_CHARS = 1_500_000;
const MAX_DISTANCE_M = 20_000_000;
const MAX_DURATION_S = 7 * 86_400;
const MAX_SPEED_MPS = 120;
const notNeg = { type: 'number', minimum: 0 } as const;

export async function rideRoutes(app: FastifyInstance, ctx: ApiContext): Promise<void> {
  const { db } = ctx;
  const lim = limits(ctx.config);
  const auth = { preHandler: [authenticate(ctx.tokens)] };
  const security = { security: bearer, tags: ['rides'] };
  const visibility = { type: 'string', enum: [...VISIBILITIES] };

  async function loadRide(id: string, viewer: string) {
    const ride = await db.selectFrom('rides').selectAll().where('id', '=', id).where(visibleTo(viewer, 'rides.owner_id', 'rides.visibility')).executeTakeFirst();
    // Others only see finished rides.
    if (!ride || (ride.owner_id !== viewer && ride.status !== 'finished')) throw notFound('No such ride');
    return ride;
  }

  async function fullRide(id: string, viewer: string) {
    const r = await loadRide(id, viewer);
    const photos = await db.selectFrom('ride_photos').selectAll().where('ride_id', '=', id).orderBy('created_at').orderBy('id').execute();
    const media = photos.length ? await db.selectFrom('media').selectAll().where('id', 'in', photos.map((p) => p.media_id)).execute() : [];
    const byId = new Map(media.map((m) => [m.id, m]));
    return {
      ...rideSummaryDto(r), polyline: r.polyline, polylinePrecision: r.polyline_precision,
      photos: photos.flatMap((p) => { const m = byId.get(p.media_id); return m ? [ridePhotoDto(p, m)] : []; }),
    };
  }

  app.post('/v1/rides', {
    ...auth, ...lim.write,
    schema: {
      ...security, summary: 'Start a ride',
      body: bodyObj({
        vehicleId: uuid, title: { type: 'string', maxLength: 120 }, visibility,
        startedAt: dateTime, startLat: { type: 'number', minimum: -90, maximum: 90 }, startLng: { type: 'number', minimum: -180, maximum: 180 },
        convoyCode: { type: 'string', maxLength: 16, description: 'The convoy room code this ride is part of.' },
      }, []),
      response: { 201: ref('Ride'), ...errorResponses },
    },
  }, async (req, reply) => {
    const b = req.body as { vehicleId?: string; title?: string; visibility?: 'public' | 'followers' | 'private'; startedAt?: string; startLat?: number; startLng?: number; convoyCode?: string };
    if (b.vehicleId) {
      const v = await db.selectFrom('vehicles').select('id').where('id', '=', b.vehicleId).where('owner_id', '=', req.userId).executeTakeFirst();
      if (!v) throw badRequest('Unknown vehicle');
    }
    const startedAt = b.startedAt ? new Date(b.startedAt) : new Date();
    const now = Date.now();
    if (startedAt.getTime() > now + 300_000 || startedAt.getTime() < now - 30 * 86_400_000) throw badRequest('startedAt must be within the last 30 days');
    const convoyCode = b.convoyCode ? b.convoyCode.replace(/[\s-]/g, '').toUpperCase() : null;
    if (convoyCode && !CODE_PATTERN.test(convoyCode)) throw badRequest('Bad convoy code');
    const row = await db.insertInto('rides').values({
      owner_id: req.userId, vehicle_id: b.vehicleId ?? null, title: (b.title ?? '').trim(), visibility: b.visibility ?? 'followers',
      started_at: startedAt, start_lat: b.startLat ?? null, start_lng: b.startLng ?? null, convoy_code: convoyCode,
    }).returning('id').executeTakeFirstOrThrow();
    return reply.code(201).send(await fullRide(row.id, req.userId));
  });

  app.post('/v1/rides/:id/finish', {
    ...auth, ...lim.write, bodyLimit: 4 * 1024 * 1024,
    schema: {
      ...security, summary: 'Finish a ride and upload its trace',
      description: 'The trace is a Google encoded polyline. Speeds are metres per second. Answers 409 if the ride is already finished.',
      params: idParams,
      body: bodyObj({
        endedAt: dateTime,
        distanceM: { ...notNeg, maximum: MAX_DISTANCE_M },
        durationS: { type: 'integer', minimum: 0, maximum: MAX_DURATION_S },
        movingTimeS: { type: 'integer', minimum: 0, maximum: MAX_DURATION_S, description: 'Seconds spent moving, stops excluded.' },
        maxSpeedMps: { ...notNeg, maximum: MAX_SPEED_MPS },
        avgSpeedMps: { ...notNeg, maximum: MAX_SPEED_MPS },
        elevationGainM: { ...notNeg, maximum: 20_000 },
        maxLeanDeg: { type: 'number', minimum: 0, maximum: 90 },
        polyline: { type: 'string', maxLength: MAX_POLYLINE_CHARS },
        polylinePrecision: { type: 'integer', enum: [5, 6] },
        title: { type: 'string', maxLength: 120 },
        visibility,
      }, ['endedAt', 'distanceM', 'durationS', 'maxSpeedMps', 'avgSpeedMps', 'polyline']),
      response: { 200: ref('Ride'), ...errorResponses },
    },
  }, async (req) => {
    const id = (req.params as { id: string }).id;
    const b = req.body as {
      endedAt: string; distanceM: number; durationS: number; movingTimeS?: number; maxSpeedMps: number; avgSpeedMps: number; elevationGainM?: number;
      maxLeanDeg?: number; polyline: string; polylinePrecision?: 5 | 6; title?: string; visibility?: 'public' | 'followers' | 'private';
    };
    const ride = await db.selectFrom('rides').selectAll().where('id', '=', id).where('owner_id', '=', req.userId).executeTakeFirst();
    if (!ride) throw notFound('No such ride');
    if (ride.status === 'finished') throw conflict('This ride is already finished');
    const endedAt = new Date(b.endedAt);
    if (endedAt.getTime() < new Date(ride.started_at).getTime()) throw badRequest('endedAt is before the ride started');
    if (endedAt.getTime() > Date.now() + 300_000) throw badRequest('endedAt is in the future');
    const precision = b.polylinePrecision ?? 5;
    let points: [number, number][];
    try { points = decodePolyline(b.polyline, precision); } catch (err) { throw badRequest(`Bad polyline: ${(err as Error).message}`); }
    const set: Updateable<RidesTable> = {
      status: 'finished', ended_at: endedAt, distance_m: b.distanceM, duration_s: b.durationS, moving_time_s: b.movingTimeS ?? null, max_speed_mps: b.maxSpeedMps,
      avg_speed_mps: b.avgSpeedMps, elevation_gain_m: b.elevationGainM ?? null, max_lean_deg: b.maxLeanDeg ?? null,
      polyline: b.polyline, polyline_precision: precision,
    };
    if (points.length && ride.start_lat === null) { set.start_lat = points[0][0]; set.start_lng = points[0][1]; }
    if (b.title !== undefined) set.title = b.title.trim();
    if (b.visibility !== undefined) set.visibility = b.visibility;
    await db.updateTable('rides').set(set).where('id', '=', id).execute();
    return fullRide(id, req.userId);
  });

  app.get('/v1/rides', {
    ...auth,
    schema: { ...security, summary: 'My rides, newest first (active ones included)', querystring: cursorQuery(), response: { 200: ref('RidePage'), ...errorResponses } },
  }, async (req) => {
    const q = req.query as { before?: string; limit?: number };
    const limit = q.limit ?? 20;
    const rows = await db.selectFrom('rides').selectAll().where('owner_id', '=', req.userId).where(beforeCursor('started_at', 'id', decodeCursor(q.before)))
      .orderBy('started_at', 'desc').orderBy('id', 'desc').limit(limit + 1).execute();
    const p = page(rows, limit, (r) => encodeCursor(r.started_at, r.id));
    return { items: p.items.map(rideSummaryDto), nextCursor: p.nextCursor };
  });

  app.get('/v1/users/:handle/rides', {
    ...auth,
    schema: {
      ...security, summary: 'A user rides that I may see', params: bodyObj({ handle: { type: 'string', maxLength: 40 } }),
      querystring: cursorQuery(), response: { 200: ref('RidePage'), ...errorResponses },
    },
  }, async (req) => {
    const q = req.query as { before?: string; limit?: number };
    const { id } = await userByHandle(db, req.userId, (req.params as { handle: string }).handle);
    const limit = q.limit ?? 20;
    const rows = await db.selectFrom('rides').selectAll().where('owner_id', '=', id).where('status', '=', 'finished')
      .where(visibleTo(req.userId, 'rides.owner_id', 'rides.visibility')).where(beforeCursor('started_at', 'id', decodeCursor(q.before)))
      .orderBy('started_at', 'desc').orderBy('id', 'desc').limit(limit + 1).execute();
    const p = page(rows, limit, (r) => encodeCursor(r.started_at, r.id));
    return { items: p.items.map(rideSummaryDto), nextCursor: p.nextCursor };
  });

  app.get('/v1/rides/:id', {
    ...auth,
    schema: { ...security, summary: 'One ride with its trace and photos', params: idParams, response: { 200: ref('Ride'), ...errorResponses } },
  }, async (req) => fullRide((req.params as { id: string }).id, req.userId));

  app.patch('/v1/rides/:id', {
    ...auth, ...lim.write,
    schema: {
      ...security, summary: 'Edit a ride', params: idParams,
      body: bodyObj({ title: nullable({ type: 'string', maxLength: 120 }), visibility, vehicleId: nullable(uuid) }, []),
      response: { 200: ref('Ride'), ...errorResponses },
    },
  }, async (req) => {
    const id = (req.params as { id: string }).id;
    const b = req.body as { title?: string | null; visibility?: 'public' | 'followers' | 'private'; vehicleId?: string | null };
    if (!(await db.selectFrom('rides').select('id').where('id', '=', id).where('owner_id', '=', req.userId).executeTakeFirst())) throw notFound('No such ride');
    const set: Updateable<RidesTable> = {};
    if (b.title !== undefined) set.title = (b.title ?? '').trim();
    if (b.visibility !== undefined) set.visibility = b.visibility;
    if (b.vehicleId !== undefined) {
      if (b.vehicleId && !(await db.selectFrom('vehicles').select('id').where('id', '=', b.vehicleId).where('owner_id', '=', req.userId).executeTakeFirst())) throw badRequest('Unknown vehicle');
      set.vehicle_id = b.vehicleId;
    }
    if (Object.keys(set).length) await db.updateTable('rides').set(set).where('id', '=', id).execute();
    return fullRide(id, req.userId);
  });

  app.delete('/v1/rides/:id', {
    ...auth, ...lim.write,
    schema: { ...security, summary: 'Delete a ride (and posts about it)', params: idParams, response: { 200: ref('Ok'), ...errorResponses } },
  }, async (req) => {
    const res = await db.deleteFrom('rides').where('id', '=', (req.params as { id: string }).id).where('owner_id', '=', req.userId).executeTakeFirst();
    if (Number(res.numDeletedRows) === 0) throw notFound('No such ride');
    return { ok: true };
  });

  app.post('/v1/rides/:id/photos', {
    ...auth, ...lim.write,
    schema: {
      ...security, summary: 'Attach an uploaded photo to a ride',
      description: 'lat/lng place the photo on the route. takenAt defaults to the EXIF capture time of the media.',
      params: idParams,
      body: bodyObj({
        mediaId: uuid, lat: { type: 'number', minimum: -90, maximum: 90 }, lng: { type: 'number', minimum: -180, maximum: 180 }, takenAt: dateTime,
      }, ['mediaId']),
      response: { 201: ref('RidePhoto'), ...errorResponses },
    },
  }, async (req, reply) => {
    const id = (req.params as { id: string }).id;
    const b = req.body as { mediaId: string; lat?: number; lng?: number; takenAt?: string };
    if ((b.lat === undefined) !== (b.lng === undefined)) throw badRequest('Send both lat and lng, or neither');
    if (!(await db.selectFrom('rides').select('id').where('id', '=', id).where('owner_id', '=', req.userId).executeTakeFirst())) throw notFound('No such ride');
    const media = await db.selectFrom('media').selectAll().where('id', '=', b.mediaId).where('owner_id', '=', req.userId).executeTakeFirst();
    if (!media) throw badRequest('Unknown media');
    const count = await db.selectFrom('ride_photos').select((eb) => eb.fn.countAll<number>().as('n')).where('ride_id', '=', id).executeTakeFirstOrThrow();
    if (Number(count.n) >= 100) throw conflict('A ride holds at most 100 photos');
    const row = await db.insertInto('ride_photos').values({
      ride_id: id, media_id: b.mediaId, lat: b.lat ?? null, lng: b.lng ?? null, taken_at: b.takenAt ? new Date(b.takenAt) : media.taken_at,
    }).onConflict((oc) => oc.columns(['ride_id', 'media_id']).doUpdateSet({ lat: b.lat ?? null, lng: b.lng ?? null })).returningAll().executeTakeFirstOrThrow();
    return reply.code(201).send(ridePhotoDto(row, media));
  });

  app.delete('/v1/rides/:id/photos/:photoId', {
    ...auth, ...lim.write,
    schema: { ...security, summary: 'Detach a photo from a ride', params: bodyObj({ id: uuid, photoId: uuid }), response: { 200: ref('Ok'), ...errorResponses } },
  }, async (req) => {
    const p = req.params as { id: string; photoId: string };
    const ride = await db.selectFrom('rides').select('id').where('id', '=', p.id).where('owner_id', '=', req.userId).executeTakeFirst();
    if (!ride) throw notFound('No such ride');
    await db.deleteFrom('ride_photos').where('id', '=', p.photoId).where('ride_id', '=', p.id).execute();
    return { ok: true };
  });
}
