import type { FastifyInstance } from 'fastify';
import { badRequest, notFound } from '../../errors.js';
import { parseBbox } from '../../convoy/geo.js';
import { AREA_MAX_DEG, ConditionsService, type RouteConditions } from '../../conditions/conditions.js';
import type { Pt } from '../../conditions/line.js';
import { authenticate, visibleTo, type ApiContext } from '../common.js';
import { limits } from '../limits.js';
import { decodePolyline } from '../polyline.js';
import { arrayOf, bearer, bodyObj, errorResponses, idParams, nullable, ref } from '../schemas.js';

/** A saved route's snapshot is reused for this long unless the app asks for a refresh. */
export const SNAPSHOT_FRESH_MS = 10 * 60_000;
const MAX_ROUTE_M = 1_500_000;

function lineFrom(geometry: string | null | undefined, waypoints: unknown): Pt[] {
  if (geometry) {
    try { return decodePolyline(geometry, 6, 50_000).map(([lat, lng]): Pt => [lng, lat]); } catch { throw badRequest('Bad geometry'); }
  }
  const w = (typeof waypoints === 'string' ? JSON.parse(waypoints) : waypoints) as Pt[];
  return Array.isArray(w) ? w : [];
}

export async function conditionsRoutes(app: FastifyInstance, ctx: ApiContext, service = new ConditionsService(ctx.db, ctx.config, ctx.fetch, Date.now, (source, err) => app.log.warn({ err, source }, 'route conditions source failed'))): Promise<void> {
  const lim = limits(ctx.config);
  const auth = { preHandler: [authenticate(ctx.tokens)] };
  const security = { security: bearer, tags: ['routes'] };

  const compute = async (line: Pt[], durationS: number | null, departAt?: string): Promise<RouteConditions> => {
    if (line.length < 2) throw badRequest('A route needs at least two points');
    return service.forRoute(line, { durationS, departAt: departAt ? new Date(departAt) : undefined });
  };

  app.post('/v1/conditions', {
    ...auth, ...lim.write,
    schema: {
      ...security, summary: 'Conditions along a route',
      description: 'Traffic (TfNSW incidents and roadworks, Waze jams, crashes and closures), weather at the time you reach each part, OpenStreetMap road quality, and police, cameras and hazards near the line. Sources that are not configured or not answering are reported in `sources`. Can take up to ~30 s while Waze scans.',
      body: bodyObj({
        geometry: { type: 'string', maxLength: 200_000, description: 'The route line, polyline precision 6.' },
        durationS: nullable({ type: 'number', minimum: 0 }),
        departAt: { type: 'string', format: 'date-time' },
      }, ['geometry']),
      response: { 200: ref('RouteConditions'), ...errorResponses },
    },
  }, async (req) => {
    const b = req.body as { geometry: string; durationS?: number | null; departAt?: string };
    return compute(lineFrom(b.geometry, null), b.durationS ?? null, b.departAt);
  });

  app.get('/v1/routes/:id/conditions', {
    ...auth,
    schema: {
      ...security, summary: 'Conditions along a saved route',
      description: 'The saved snapshot when it is under 10 minutes old (`cached: true`), else computed now and saved. `refresh=true` always recomputes; `cachedOnly=true` returns the saved snapshot however old (404 if none).',
      params: idParams,
      querystring: {
        type: 'object', additionalProperties: false,
        properties: { refresh: { type: 'boolean', default: false }, cachedOnly: { type: 'boolean', default: false } },
      },
      response: { 200: ref('RouteConditions'), ...errorResponses },
    },
  }, async (req) => {
    const { id } = req.params as { id: string };
    const q = req.query as { refresh?: boolean; cachedOnly?: boolean };
    const row = await ctx.db.selectFrom('saved_routes').selectAll().where('id', '=', id)
      .where(visibleTo(req.userId, 'saved_routes.owner_id', 'saved_routes.visibility')).executeTakeFirst();
    if (!row) throw notFound('No such route');
    const age = row.conditions_at ? Date.now() - new Date(row.conditions_at).getTime() : Infinity;
    const snapshot = row.conditions ? (typeof row.conditions === 'string' ? JSON.parse(row.conditions) : row.conditions) as RouteConditions : null;
    if (snapshot && (q.cachedOnly || (!q.refresh && age < SNAPSHOT_FRESH_MS))) return { ...snapshot, cached: true };
    if (q.cachedOnly) throw notFound('No saved conditions yet');
    if (row.distance_m && row.distance_m > MAX_ROUTE_M) throw badRequest('That route is too long to check');
    const fresh = await compute(lineFrom(row.geometry, row.waypoints), row.duration_s);
    // While road quality is still loading the snapshot is saved as already stale, so the next ask fills it in.
    const at = fresh.sources.roads === 'pending' || fresh.sources.stops === 'pending' ? new Date(Date.now() - SNAPSHOT_FRESH_MS) : new Date();
    await ctx.db.updateTable('saved_routes').set({ conditions: JSON.stringify(fresh), conditions_at: at }).where('id', '=', id).execute();
    return { ...fresh, cached: false };
  });

  app.get('/v1/stops', {
    ...auth,
    schema: {
      ...security, summary: 'Stops on the map',
      description: 'Servos (with NSW FuelCheck prices when set up), fast food and cafes, toilets, rest areas and EV chargers in a box, up to 0.15 degrees a side. From OpenStreetMap, cached a week.',
      querystring: { type: 'object', properties: { bbox: { type: 'string', description: 'south,west,north,east' } }, required: ['bbox'], additionalProperties: false },
      response: { 200: arrayOf(ref('Stop')), ...errorResponses },
    },
  }, async (req, reply) => {
    const b = parseBbox((req.query as { bbox: string }).bbox);
    if (b.north - b.south > 0.15 || b.east - b.west > 0.15) throw badRequest('Zoom in: stops cover up to 0.15 degrees at a time');
    try {
      return await service.stops.area({ minLat: b.south, minLng: b.west, maxLat: b.north, maxLng: b.east });
    } catch (err) {
      req.log.warn({ err }, 'stops failed');
      return reply.code(503).send({ statusCode: 503, error: 'Service Unavailable', message: 'Map data is not answering, try again soon' });
    }
  });

  app.get('/v1/conditions/area', {
    ...auth,
    schema: {
      ...security, summary: 'Police, hazards and traffic on the map',
      description: 'Police, speed cameras, hazards, crashes, jams, closures and roadworks in a box: TfNSW, Waze (self-hosted harvester, when set up) and Convoyage reports. Up to 0.3 degrees a side. A box Waze has not scanned yet answers with `sources.waze` = `pending`: ask again in a few seconds.',
      querystring: { type: 'object', properties: { bbox: { type: 'string', description: 'south,west,north,east' } }, required: ['bbox'], additionalProperties: false },
      response: {
        200: {
          type: 'object', additionalProperties: false, required: ['items', 'sources'],
          properties: {
            items: arrayOf({
              type: 'object', additionalProperties: false, required: ['id', 'source', 'kind', 'title', 'detail', 'lat', 'lng', 'reportedAt'],
              properties: {
                id: { type: 'string' }, source: { type: 'string', enum: ['tfnsw', 'waze', 'convoyage'] }, kind: { type: 'string' },
                title: { type: 'string' }, detail: { type: 'string' }, lat: { type: 'number' }, lng: { type: 'number' },
                reportedAt: nullable({ type: 'string', format: 'date-time' }),
              },
            }),
            sources: {
              type: 'object', additionalProperties: false, required: ['tfnsw', 'waze'],
              properties: { tfnsw: { type: 'string' }, waze: { type: 'string' } },
            },
          },
        },
        ...errorResponses,
      },
    },
  }, async (req, reply) => {
    let b;
    try { b = parseBbox((req.query as { bbox: string }).bbox); } catch (err) { throw badRequest((err as Error).message); }
    if (b.north <= b.south || b.east <= b.west) throw badRequest('bbox must be "south,west,north,east"');
    if (b.north - b.south > AREA_MAX_DEG || b.east - b.west > AREA_MAX_DEG) throw badRequest('Zoom in: police and traffic cover up to 0.3 degrees at a time');
    const out = await service.area({ minLat: b.south, minLng: b.west, maxLat: b.north, maxLng: b.east });
    reply.header('cache-control', 'private, max-age=30');
    return out;
  });

  app.get('/v1/roads', {
    ...auth,
    schema: {
      ...security, summary: 'Road quality on the map',
      description: 'OpenStreetMap roads in a box, graded good / fair / poor / unknown from surface and smoothness tags, as GeoJSON lines. Up to 0.15 degrees a side; cached on the server for 14 days.',
      querystring: { type: 'object', properties: { bbox: { type: 'string', description: 'south,west,north,east' } }, required: ['bbox'], additionalProperties: false },
      response: {
        200: { type: 'object', additionalProperties: true, properties: { type: { type: 'string' }, features: arrayOf({ type: 'object', additionalProperties: true }) } },
        ...errorResponses,
      },
    },
  }, async (req, reply) => {
    const b = parseBbox((req.query as { bbox: string }).bbox);
    if (b.north - b.south > 0.15 || b.east - b.west > 0.15) throw badRequest('Zoom in: road quality covers up to 0.15 degrees at a time');
    try {
      const out = await service.roads.area({ minLat: b.south, minLng: b.west, maxLat: b.north, maxLng: b.east });
      reply.header('cache-control', 'private, max-age=600');
      return out;
    } catch (err) {
      req.log.warn({ err }, 'road quality failed');
      return reply.code(503).send({ statusCode: 503, error: 'Service Unavailable', message: 'Road data is not answering, try again soon' });
    }
  });
}
