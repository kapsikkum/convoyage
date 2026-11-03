import type { FastifyInstance } from 'fastify';
import { sql, type Selectable, type Updateable } from 'kysely';
import { haversine } from '../../convoy/geo.js';
import type { SavedRoutesTable } from '../../db/types.js';
import { badRequest, notFound } from '../../errors.js';
import { MAX_CONVOY_ROUTE_STOPS } from '../../convoy/rooms.js';
import { thinImportant } from '../../convoy/thin.js';
import { authenticate, visibleTo, type ApiContext } from '../common.js';
import { limits } from '../limits.js';
import { decodePolyline } from '../polyline.js';
import { userByHandle } from './users.js';
import { arrayOf, bearer, bodyObj, errorResponses, idParams, nullable, ref, VISIBILITIES } from '../schemas.js';

const MAX_ROUTES = 200;
const MAX_WAYPOINTS = 100;
type Row = Selectable<SavedRoutesTable>;
type Pt = [number, number];

const point = { type: 'array', items: { type: 'number' }, minItems: 2, maxItems: 2 } as const;
const fields = {
  name: { type: 'string', minLength: 1, maxLength: 80 },
  notes: { type: 'string', maxLength: 1000 },
  type: { type: 'string', enum: ['sprint', 'circuit'] },
  waypoints: { type: 'array', items: point, minItems: 2, maxItems: MAX_WAYPOINTS, description: '[lng, lat] points, in driving order.' },
  geometry: { ...nullable({ type: 'string', maxLength: 200_000 }), description: 'Road-snapped line through the waypoints, polyline precision 6 (from /v1/route).' },
  distanceM: nullable({ type: 'number', minimum: 0 }),
  durationS: nullable({ type: 'number', minimum: 0 }),
  staging: { anyOf: [bodyObj({ lat: { type: 'number', minimum: -90, maximum: 90 }, lng: { type: 'number', minimum: -180, maximum: 180 } }, ['lat', 'lng']), { type: 'null' }] },
  visibility: { type: 'string', enum: [...VISIBILITIES] },
};

const validPoint = (p: Pt) => Math.abs(p[0]) <= 180 && Math.abs(p[1]) <= 90;

function checkGeometry(geometry: string | null | undefined): Pt[] | null {
  if (!geometry) return null;
  try {
    const pts = decodePolyline(geometry, 6, 50_000);
    if (pts.length < 2) throw new Error('needs at least two points');
    return pts.map(([lat, lng]): Pt => [lng, lat]);
  } catch (err) {
    throw badRequest(`Bad geometry: ${(err as Error).message}`);
  }
}

/**
 * The stops a convoy follows for this route (convoy.route allows 25): the road line thinned to the vertices that
 * matter when the route follows roads, else the author's waypoints (thinned the same way if there are too many).
 * A circuit ends where it starts.
 */
export function convoyStops(r: Pick<Row, 'type' | 'waypoints' | 'geometry'>): Pt[] {
  const way = (typeof r.waypoints === 'string' ? JSON.parse(r.waypoints) : r.waypoints) as Pt[];
  const circuit = r.type === 'circuit';
  const budget = MAX_CONVOY_ROUTE_STOPS - (circuit ? 1 : 0);
  let road: Pt[] | null = null;
  try { road = r.geometry ? decodePolyline(r.geometry, 6, 50_000).map(([lat, lng]): Pt => [lng, lat]) : null; } catch { road = null; }
  let line = thinImportant(road && road.length >= 2 ? road : way, budget);
  const first = line[0], last = line[line.length - 1];
  if (circuit && (first[0] !== last[0] || first[1] !== last[1])) {
    line = Math.hypot(first[0] - last[0], first[1] - last[1]) < 2e-4 ? [...line.slice(0, -1), first] : [...line, first];
  }
  return line;
}

/** How much a line turns, in degrees of heading change per kilometre, ignoring tiny jitters. */
export function turnDegPerKm(line: Pt[]): number {
  let turn = 0, metres = 0, prev: number | null = null;
  for (let i = 1; i < line.length; i++) {
    const [lng1, lat1] = line[i - 1], [lng2, lat2] = line[i];
    const d = haversine(lat1, lng1, lat2, lng2);
    if (d < 5) continue;
    metres += d;
    const k = Math.cos(((lat1 + lat2) / 2) * Math.PI / 180);
    const heading = Math.atan2((lng2 - lng1) * k, lat2 - lat1) * 180 / Math.PI;
    if (prev !== null) {
      let delta = Math.abs(heading - prev) % 360;
      if (delta > 180) delta = 360 - delta;
      if (delta > 2) turn += delta;
    }
    prev = heading;
  }
  return metres < 200 ? 0 : turn / (metres / 1000);
}

export const character = (degPerKm: number): 'relaxed' | 'flowing' | 'twisty' => (degPerKm < 60 ? 'relaxed' : degPerKm < 150 ? 'flowing' : 'twisty');

function lineOf(r: Pick<Row, 'waypoints' | 'geometry'>): Pt[] {
  try { if (r.geometry) return decodePolyline(r.geometry, 6, 50_000).map(([lat, lng]): Pt => [lng, lat]); } catch { /* fall back */ }
  return (typeof r.waypoints === 'string' ? JSON.parse(r.waypoints) : r.waypoints) as Pt[];
}

export function savedRouteDto(r: Row) {
  const waypoints = (typeof r.waypoints === 'string' ? JSON.parse(r.waypoints) : r.waypoints) as Pt[];
  return {
    id: r.id, ownerId: r.owner_id, name: r.name, notes: r.notes, type: r.type, waypoints, geometry: r.geometry,
    distanceM: r.distance_m, durationS: r.duration_s,
    staging: r.staging_lat != null && r.staging_lng != null ? { lat: r.staging_lat, lng: r.staging_lng } : null,
    convoyStops: convoyStops(r), visibility: r.visibility, source: r.source,
    createdAt: new Date(r.created_at).toISOString(), updatedAt: new Date(r.updated_at).toISOString(),
  };
}

export interface SavedRouteInput {
  name: string; notes?: string; type?: 'sprint' | 'circuit'; waypoints: Pt[]; geometry?: string | null;
  distanceM?: number | null; durationS?: number | null; staging?: { lat: number; lng: number } | null;
  visibility?: 'public' | 'followers' | 'private';
}

/** Insert a route for `owner` after validating it; used by the API and by PhotoScout imports. */
export async function insertSavedRoute(ctx: ApiContext, owner: string, b: SavedRouteInput, source: { source: 'convoyage' | 'photoscout'; sourceId: string | null } = { source: 'convoyage', sourceId: null }): Promise<Row> {
  const { db } = ctx;
  if (!b.waypoints.every(validPoint)) throw badRequest('A waypoint is out of range');
  checkGeometry(b.geometry);
  const count = await db.selectFrom('saved_routes').select((eb) => eb.fn.countAll<number>().as('n')).where('owner_id', '=', owner).executeTakeFirstOrThrow();
  if (Number(count.n) >= MAX_ROUTES) throw badRequest(`At most ${MAX_ROUTES} saved routes`);
  return db.insertInto('saved_routes').values({
    owner_id: owner, name: b.name.trim(), notes: (b.notes ?? '').trim(), type: b.type ?? 'sprint',
    waypoints: JSON.stringify(b.waypoints), geometry: b.geometry ?? null,
    distance_m: b.distanceM ?? null, duration_s: b.durationS ?? null,
    staging_lat: b.staging?.lat ?? null, staging_lng: b.staging?.lng ?? null,
    visibility: b.visibility ?? 'followers', source: source.source, source_id: source.sourceId,
  }).returningAll().executeTakeFirstOrThrow();
}

export async function savedRouteRoutes(app: FastifyInstance, ctx: ApiContext): Promise<void> {
  const { db } = ctx;
  const lim = limits(ctx.config);
  const auth = { preHandler: [authenticate(ctx.tokens)] };
  const security = { security: bearer, tags: ['routes'] };

  app.get('/v1/routes', {
    ...auth,
    schema: { ...security, summary: 'My saved routes, newest first', response: { 200: arrayOf(ref('SavedRoute')), ...errorResponses } },
  }, async (req) => {
    const rows = await db.selectFrom('saved_routes').selectAll().where('owner_id', '=', req.userId).orderBy('updated_at', 'desc').orderBy('id').execute();
    return rows.map(savedRouteDto);
  });

  app.get('/v1/routes/discover', {
    ...auth,
    schema: {
      ...security, summary: 'Routes other drivers share, starting near a point',
      description: 'Public routes (and followers-only ones from people you follow) whose start is within `radiusKm` of `lat`,`lng`, nearest first. Your own are left out. Each comes with its character (relaxed, flowing, twisty) from how much the road turns.',
      querystring: bodyObj({
        lat: { type: 'number', minimum: -90, maximum: 90 }, lng: { type: 'number', minimum: -180, maximum: 180 },
        radiusKm: { type: 'number', minimum: 1, maximum: 300, default: 50 }, limit: { type: 'integer', minimum: 1, maximum: 50, default: 30 },
      }, ['lat', 'lng']),
      response: { 200: arrayOf(ref('DiscoveredRoute')), ...errorResponses },
    },
  }, async (req) => {
    const q = req.query as { lat: number; lng: number; radiusKm?: number; limit?: number };
    const radiusM = (q.radiusKm ?? 50) * 1000;
    const dLat = radiusM / 111_320;
    const dLng = radiusM / (111_320 * Math.max(0.05, Math.cos(q.lat * Math.PI / 180)));
    const startLat = sql<number>`coalesce(saved_routes.staging_lat, (saved_routes.waypoints->0->>1)::float8)`;
    const startLng = sql<number>`coalesce(saved_routes.staging_lng, (saved_routes.waypoints->0->>0)::float8)`;
    const rows = await db.selectFrom('saved_routes')
      .innerJoin('profiles', 'profiles.user_id', 'saved_routes.owner_id')
      .selectAll('saved_routes').select(['profiles.handle as owner_handle', 'profiles.display_name as owner_name'])
      .where('saved_routes.owner_id', '<>', req.userId)
      .where('saved_routes.visibility', '<>', 'private')
      .where(visibleTo(req.userId, 'saved_routes.owner_id', 'saved_routes.visibility'))
      .where(sql<boolean>`${startLat} between ${q.lat - dLat} and ${q.lat + dLat}`)
      .where(sql<boolean>`${startLng} between ${q.lng - dLng} and ${q.lng + dLng}`)
      .orderBy('saved_routes.updated_at', 'desc').limit(500).execute();
    return rows.map((r) => {
      const start = r.staging_lat != null && r.staging_lng != null ? [r.staging_lng, r.staging_lat] as Pt : lineOf(r)[0];
      const deg = turnDegPerKm(lineOf(r));
      return {
        route: savedRouteDto(r), owner: { handle: r.owner_handle, displayName: r.owner_name ?? '' },
        distanceM: Math.round(haversine(q.lat, q.lng, start[1], start[0])), character: character(deg), turnDegPerKm: Math.round(deg),
      };
    }).filter((d) => d.distanceM <= radiusM).sort((a, b) => a.distanceM - b.distanceM).slice(0, q.limit ?? 30);
  });

  app.get('/v1/users/:handle/routes', {
    ...auth,
    schema: { ...security, summary: 'Saved routes a user shares with me', params: bodyObj({ handle: { type: 'string', maxLength: 40 } }), response: { 200: arrayOf(ref('SavedRoute')), ...errorResponses } },
  }, async (req) => {
    const { id, rel } = await userByHandle(db, req.userId, (req.params as { handle: string }).handle);
    if (!rel.canViewContent) return [];
    const rows = await db.selectFrom('saved_routes').selectAll().where('owner_id', '=', id)
      .where(visibleTo(req.userId, 'saved_routes.owner_id', 'saved_routes.visibility')).orderBy('updated_at', 'desc').execute();
    return rows.map(savedRouteDto);
  });

  app.get('/v1/routes/:id', {
    ...auth,
    schema: { ...security, summary: 'One saved route', params: idParams, response: { 200: ref('SavedRoute'), ...errorResponses } },
  }, async (req) => {
    const row = await db.selectFrom('saved_routes').selectAll().where('id', '=', (req.params as { id: string }).id)
      .where(visibleTo(req.userId, 'saved_routes.owner_id', 'saved_routes.visibility')).executeTakeFirst();
    if (!row) throw notFound('No such route');
    return savedRouteDto(row);
  });

  app.post('/v1/routes', {
    ...auth, ...lim.write,
    schema: {
      ...security, summary: 'Save a route',
      description: 'Waypoints are [lng, lat]. Send the road-snapped `geometry` from `/v1/route` (via the waypoints) to make it follow roads; without it the route is drawn straight between waypoints.',
      body: bodyObj(fields, ['name', 'waypoints']), response: { 201: ref('SavedRoute'), ...errorResponses },
    },
  }, async (req, reply) => {
    const row = await insertSavedRoute(ctx, req.userId, req.body as SavedRouteInput);
    reply.code(201);
    return savedRouteDto(row);
  });

  app.patch('/v1/routes/:id', {
    ...auth, ...lim.write,
    schema: { ...security, summary: 'Edit a saved route', params: idParams, body: bodyObj(fields, []), response: { 200: ref('SavedRoute'), ...errorResponses } },
  }, async (req) => {
    const id = (req.params as { id: string }).id;
    const b = req.body as Partial<SavedRouteInput>;
    const set: Updateable<SavedRoutesTable> = { updated_at: new Date() };
    if (b.name !== undefined) set.name = b.name.trim();
    if (b.notes !== undefined) set.notes = b.notes.trim();
    if (b.type !== undefined) set.type = b.type;
    if (b.waypoints !== undefined) {
      if (!b.waypoints.every(validPoint)) throw badRequest('A waypoint is out of range');
      set.waypoints = JSON.stringify(b.waypoints);
    }
    if (b.geometry !== undefined) { checkGeometry(b.geometry); set.geometry = b.geometry; }
    if (b.distanceM !== undefined) set.distance_m = b.distanceM;
    if (b.durationS !== undefined) set.duration_s = b.durationS;
    if (b.staging !== undefined) { set.staging_lat = b.staging?.lat ?? null; set.staging_lng = b.staging?.lng ?? null; }
    if (b.visibility !== undefined) set.visibility = b.visibility;
    const row = await db.updateTable('saved_routes').set(set).where('id', '=', id).where('owner_id', '=', req.userId).returningAll().executeTakeFirst();
    if (!row) throw notFound('No such route');
    return savedRouteDto(row);
  });

  app.delete('/v1/routes/:id', {
    ...auth, ...lim.write,
    schema: { ...security, summary: 'Delete a saved route', params: idParams, response: { 204: { type: 'null' }, ...errorResponses } },
  }, async (req, reply) => {
    const r = await db.deleteFrom('saved_routes').where('id', '=', (req.params as { id: string }).id).where('owner_id', '=', req.userId).executeTakeFirst();
    if (!Number(r.numDeletedRows)) throw notFound('No such route');
    reply.code(204);
  });
}
