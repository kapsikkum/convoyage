import type { FastifyInstance } from 'fastify';
import { HttpError, badRequest, notFound } from '../../errors.js';
import { authenticate, type ApiContext } from '../common.js';
import { arrayOf, bearer, bodyObj, errorResponses, nullable } from '../schemas.js';

const CACHE_TTL_MS = 60_000;
const CACHE_MAX = 500;
const MAX_VIA = 20;
const UPSTREAM_TIMEOUT_MS = 8000;

interface OsrmStep {
  distance: number; duration: number; name?: string; ref?: string; exits?: string;
  maneuver: { type: string; modifier?: string; location: [number, number]; bearing_after?: number; exit?: number };
}

const COMPASS = ['north', 'northeast', 'east', 'southeast', 'south', 'southwest', 'west', 'northwest'];
const compass = (bearing: number | undefined) => (bearing === undefined ? '' : COMPASS[Math.round(((bearing % 360) + 360) % 360 / 45) % 8]);
const ordinal = (n: number) => ['first', 'second', 'third', 'fourth', 'fifth', 'sixth', 'seventh', 'eighth', 'ninth', 'tenth'][n - 1] ?? `${n}th`;

/** Human instruction from an OSRM maneuver (type + modifier + street name). */
export function instruction(step: Pick<OsrmStep, 'name' | 'ref' | 'maneuver'>): string {
  const { type, modifier, bearing_after: bearing, exit } = step.maneuver;
  const street = (step.name || step.ref || '').trim();
  const onto = street ? ` onto ${street}` : '';
  const on = street ? ` on ${street}` : '';
  const dir = modifier ?? 'straight';
  switch (type) {
    case 'depart': return `Head ${compass(bearing) || 'out'}${on}`;
    case 'arrive': return modifier === 'left' || modifier === 'right' ? `You have arrived, your destination is on the ${modifier}` : 'You have arrived at your destination';
    case 'turn': return dir === 'uturn' ? `Make a U-turn${on}` : `Turn ${dir}${onto}`;
    case 'new name': return `Continue${onto}`;
    case 'continue': return dir === 'straight' ? `Continue straight${on}` : dir === 'uturn' ? `Make a U-turn${on}` : `Continue ${dir}${onto}`;
    case 'merge': return `Merge ${modifier ?? ''}${onto}`.replace('  ', ' ');
    case 'on ramp': return `Take the ramp${modifier ? ` ${modifier}` : ''}${onto}`;
    case 'off ramp': return `Take the exit${modifier ? ` ${modifier}` : ''}${onto}`;
    case 'fork': return `Keep ${modifier === 'left' || modifier === 'slight left' ? 'left' : modifier === 'right' || modifier === 'slight right' ? 'right' : 'straight'} at the fork${onto}`;
    case 'end of road': return `At the end of the road, turn ${dir}${onto}`;
    case 'roundabout':
    case 'rotary': return exit ? `At the roundabout, take the ${ordinal(exit)} exit${onto}` : `Enter the roundabout${onto}`;
    case 'roundabout turn': return `At the roundabout, turn ${dir}${onto}`;
    case 'exit roundabout':
    case 'exit rotary': return `Exit the roundabout${onto}`;
    case 'notification': return street ? `Continue on ${street}` : 'Continue';
    default: return `Continue${onto}`;
  }
}

function coord(raw: string, what: string): [number, number] {
  const m = /^\s*(-?\d+(?:\.\d+)?)\s*,\s*(-?\d+(?:\.\d+)?)\s*$/.exec(raw);
  if (!m) throw badRequest(`${what} must be "lng,lat"`);
  const lng = Number(m[1]);
  const lat = Number(m[2]);
  if (Math.abs(lng) > 180 || Math.abs(lat) > 90) throw badRequest(`${what} is outside valid lng/lat bounds`);
  return [lng, lat];
}

/** A sliding-window limiter per user, in memory. */
class UserLimiter {
  private hits = new Map<string, number[]>();
  constructor(private max: number, private windowMs: number, private now: () => number = Date.now) {}
  /** Seconds to wait if over the limit, else 0. */
  take(key: string): number {
    const now = this.now();
    const recent = (this.hits.get(key) ?? []).filter((t) => now - t < this.windowMs);
    if (recent.length >= this.max) { this.hits.set(key, recent); return Math.ceil((recent[0] + this.windowMs - now) / 1000); }
    recent.push(now);
    this.hits.set(key, recent);
    if (this.hits.size > 10_000) for (const [k, v] of this.hits) if (!v.some((t) => now - t < this.windowMs)) this.hits.delete(k);
    return 0;
  }
}

export async function navigationRoutes(app: FastifyInstance, ctx: ApiContext): Promise<void> {
  const cache = new Map<string, { at: number; body: unknown }>();
  const limiter = new UserLimiter(ctx.config.routeRateLimit.max, ctx.config.routeRateLimit.windowMs);

  app.get('/v1/route', {
    preHandler: [authenticate(ctx.tokens)],
    schema: {
      security: bearer, tags: ['navigation'], summary: 'Driving route with turn-by-turn steps (OSRM)',
      description: 'Proxies OSRM (OSRM_URL). `from`, `to` and each `via` are "lng,lat"; `via` is a `;`-separated list. Geometry is a polyline with 6 decimals of precision. Cached for a minute, limited per user. 404 when no route exists, 502 when the routing server fails.',
      querystring: bodyObj({
        from: { type: 'string', maxLength: 40 }, to: { type: 'string', maxLength: 40 },
        via: { type: 'string', maxLength: 800 }, profile: { type: 'string', enum: ['car', 'motorcycle'], default: 'car' },
        heading: { type: 'number', minimum: 0, maximum: 360, description: 'Direction of travel at `from`, degrees: the route starts that way instead of turning around.' },
      }, ['from', 'to']),
      response: {
        200: bodyObj({
          profile: { type: 'string', enum: ['car', 'motorcycle'] },
          geometry: { type: 'string', description: 'Polyline, precision 6.' },
          geometryPrecision: { type: 'integer', enum: [6] },
          distanceM: { type: 'number' }, durationS: { type: 'number' },
          steps: arrayOf(bodyObj({
            maneuver: bodyObj({ type: { type: 'string' }, modifier: nullable({ type: 'string' }), location: { type: 'array', items: { type: 'number' }, minItems: 2, maxItems: 2 } }),
            name: { type: 'string' }, distanceM: { type: 'number' }, durationS: { type: 'number' }, instruction: { type: 'string' },
          })),
        }),
        ...errorResponses, 502: { $ref: 'Error#' },
      },
    },
  }, async (req, reply) => {
    const q = req.query as { from: string; to: string; via?: string; profile?: 'car' | 'motorcycle'; heading?: number };
    const profile = q.profile ?? 'car';
    const points = [coord(q.from, 'from'), ...(q.via ? q.via.split(';').filter((s) => s.trim()).map((s) => coord(s, 'via')) : []), coord(q.to, 'to')];
    if (points.length > MAX_VIA + 2) throw badRequest(`At most ${MAX_VIA} via points`);

    const wait = limiter.take(req.userId);
    if (wait) { reply.header('retry-after', String(wait)); throw new HttpError(429, 'Too many route requests'); }

    const osrmProfile = ctx.config.osrmProfiles[profile];
    const base = `${ctx.config.osrmUrl}/route/v1/${encodeURIComponent(osrmProfile)}/${points.map((p) => `${p[0]},${p[1]}`).join(';')}?overview=full&geometries=polyline6&steps=true&alternatives=false`;
    // Moving: start the way the car is already heading (OSRM snaps the first point to a road segment within 60 degrees
    // of it), so the route goes on and comes round rather than saying "make a U-turn".
    const withHeading = q.heading !== undefined ? `${base}&bearings=${Math.round(q.heading) % 360},60${';'.repeat(points.length - 1)}` : null;
    const key = `${profile}|${withHeading ?? base}`;
    const hit = cache.get(key);
    if (hit && Date.now() - hit.at < CACHE_TTL_MS) return hit.body;

    const ask = async (url: string): Promise<any> => {
      try {
        const res = await ctx.fetch(url, { signal: AbortSignal.timeout(UPSTREAM_TIMEOUT_MS), headers: { accept: 'application/json', 'user-agent': 'convoyage-server' } });
        const body = await res.json().catch(() => null);
        if (!res.ok && !body?.code) throw new Error(`OSRM answered ${res.status}`);
        return body;
      } catch (err) {
        req.log.warn({ err }, 'routing upstream failed');
        throw new HttpError(502, 'The routing server is unavailable');
      }
    };
    let data: any = await ask(withHeading ?? base);
    // No road that way near the start (a car park, a wrong heading): route without it.
    if (withHeading && (data?.code === 'NoSegment' || data?.code === 'NoRoute' || data?.code === 'InvalidValue')) data = await ask(base);
    if (data?.code === 'NoRoute' || data?.code === 'NoSegment') throw notFound('No route found between those points');
    if (data?.code === 'InvalidQuery' || data?.code === 'InvalidValue') throw badRequest(data.message ?? 'The routing server rejected the request');
    const route = data?.code === 'Ok' ? data.routes?.[0] : null;
    if (!route) throw new HttpError(502, 'The routing server returned no route');

    const steps = (route.legs as { steps: OsrmStep[] }[]).flatMap((leg) => leg.steps).map((s) => ({
      maneuver: { type: s.maneuver.type, modifier: s.maneuver.modifier ?? null, location: s.maneuver.location },
      name: s.name ?? '', distanceM: s.distance, durationS: s.duration, instruction: instruction(s),
    }));
    const body = { profile, geometry: route.geometry as string, geometryPrecision: 6, distanceM: route.distance as number, durationS: route.duration as number, steps };
    if (cache.size >= CACHE_MAX) cache.delete(cache.keys().next().value!);
    cache.set(key, { at: Date.now(), body });
    return body;
  });
}
