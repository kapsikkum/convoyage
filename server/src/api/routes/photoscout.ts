import type { FastifyInstance } from 'fastify';
import { badRequest, forbidden } from '../../errors.js';
import { checkinDto, communityEventDto, distanceKm, rsvpDto, scoutEventDto, spotDto, type PsEvent } from '../../photoscout/dto.js';
import { PhotoScoutError, type PhotoScoutService } from '../../photoscout/service.js';
import { authenticate, type ApiContext } from '../common.js';
import { limits } from '../limits.js';
import { arrayOf, bearer, bodyObj, errorResponses, ref } from '../schemas.js';

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const PHOTO_ID = /^[\w-]{1,64}$/;
const MAX_EVENTS = 200;
const MAX_PHOTO_BYTES = 20 * 1024 * 1024;

const notConfigured = () => new PhotoScoutError(503, 'photoscout_not_configured', 'PhotoScout is not configured on this server');

function num(v: string, min: number, max: number, what: string): number {
  const n = Number(v);
  if (v.trim() === '' || !Number.isFinite(n) || n < min || n > max) throw badRequest(`${what} is out of range`);
  return n;
}

const pair = (raw: string, what: string): [number, number] => {
  const parts = raw.split(',');
  if (parts.length !== 2) throw badRequest(`${what} must be lat,lng`);
  return [num(parts[0], -90, 90, `${what} latitude`), num(parts[1], -180, 180, `${what} longitude`)];
};

const instant = (raw: string | undefined, what: string): number | null => {
  if (!raw) return null;
  const t = Date.parse(raw);
  if (!Number.isFinite(t)) throw badRequest(`${what} must be an ISO date or time`);
  return t;
};

export async function photoscoutRoutes(app: FastifyInstance, ctx: ApiContext): Promise<void> {
  const lim = limits(ctx.config);
  const auth = { preHandler: [authenticate(ctx.tokens)] };
  const svc = (): PhotoScoutService => {
    if (!ctx.photoscout) throw notConfigured();
    return ctx.photoscout;
  };
  const errors = { ...errorResponses, 502: ref('Error'), 503: ref('Error') };
  const tags = ['photoscout'];

  app.get('/v1/photoscout/status', {
    ...auth, ...lim.write,
    schema: {
      security: bearer, tags, summary: 'Is my PhotoScout account linked?',
      description: 'Always 200. `configured: false` means this server has no PhotoScout (every other PhotoScout route is then 503 `photoscout_not_configured`).',
      response: { 200: ref('PhotoScoutStatus'), ...errors },
    },
  }, async (req) => {
    if (!ctx.photoscout) return { configured: false, linked: false, username: null, scope: '', linkedAt: null };
    return { configured: true, ...(await ctx.photoscout.status(req.userId)) };
  });

  app.get('/v1/photoscout/connect', {
    ...auth, ...lim.write,
    schema: {
      security: bearer, tags, summary: 'Start linking my PhotoScout account',
      description: 'Returns the PhotoScout consent URL (OAuth 2 authorization code + PKCE). Open it in a browser or custom tab. When the person has allowed it, PhotoScout redirects to this server\'s `/v1/photoscout/callback`, which stores the tokens and sends the browser on to `convoyage://photoscout/linked` (or `convoyage://photoscout/linked?error=<code>`). Connecting again replaces the earlier link. Guests cannot link.',
      response: { 200: ref('PhotoScoutAuthorizeUrl'), ...errors },
    },
  }, async (req) => {
    const s = svc();
    if (req.userKind === 'guest') throw forbidden('Guests cannot link PhotoScout: sign in with an account');
    return { authorizeUrl: await s.begin(req.userId) };
  });

  // The browser, not the app, arrives here: no bearer token. The one-time `state` ties it to the member who started the flow.
  app.get('/v1/photoscout/callback', {
    ...lim.auth,
    schema: {
      tags, summary: 'PhotoScout redirects the browser here after consent',
      description: 'Not called by apps. Exchanges the code, stores the tokens (sealed), and redirects (302) to `convoyage://photoscout/linked`, or `convoyage://photoscout/linked?error=<invalid_state|access_denied|exchange_failed|exchange_rejected|not_configured|...>`. Register `<PUBLIC_BASE_URL>/v1/photoscout/callback` as the redirect URI at PhotoScout.',
      querystring: bodyObj({ code: { type: 'string', maxLength: 500 }, state: { type: 'string', maxLength: 500 }, error: { type: 'string', maxLength: 200 }, error_description: { type: 'string', maxLength: 500 } }, []),
      response: { 302: { type: 'null', description: 'Redirect to the app.' } },
    },
  }, async (req, reply) => {
    if (!ctx.photoscout) return reply.redirect('convoyage://photoscout/linked?error=not_configured', 302);
    const q = req.query as { code?: string; state?: string; error?: string };
    return reply.redirect(await ctx.photoscout.complete(q), 302);
  });

  app.delete('/v1/photoscout/link', {
    ...auth, ...lim.write,
    schema: {
      security: bearer, tags, summary: 'Unlink my PhotoScout account',
      description: 'Revokes the connection at PhotoScout (best effort) and deletes the stored tokens. Idempotent.',
      response: { 200: ref('Ok'), ...errors },
    },
  }, async (req) => {
    await svc().unlink(req.userId);
    return { ok: true };
  });

  app.get('/v1/photoscout/events', {
    ...auth, ...lim.write,
    schema: {
      security: bearer, tags, summary: 'Upcoming PhotoScout events',
      description: 'Community events (some are cruises, with their meet point) and crawled events, soonest first, merged into one list. `from` defaults to now. With `near` each event gets `distanceKm` (null without a position), and `radiusKm` drops the farther ones. At most 200. 409 `photoscout_not_linked` when I have not linked.',
      querystring: bodyObj({
        from: { type: 'string', maxLength: 40 }, to: { type: 'string', maxLength: 40 }, near: { type: 'string', maxLength: 40, description: 'lat,lng' },
        radiusKm: { type: 'number', minimum: 0.1, maximum: 20000 },
      }, []),
      response: { 200: arrayOf(ref('PsEvent')), ...errors },
    },
  }, async (req) => {
    const q = req.query as { from?: string; to?: string; near?: string; radiusKm?: number };
    const from = instant(q.from, 'from') ?? Date.now();
    const to = instant(q.to, 'to');
    const near = q.near ? pair(q.near, 'near') : null;
    const s = svc();
    const [community, scout] = await Promise.allSettled([s.json(req.userId, '/api/community-events'), s.json(req.userId, '/api/events')]);
    if (community.status === 'rejected' && scout.status === 'rejected') throw community.reason;
    // A link that was ended mid-request fails both; one failing alone (a crawler hiccup) just thins the list.
    for (const r of [community, scout]) if (r.status === 'rejected' && r.reason instanceof PhotoScoutError && r.reason.code === 'photoscout_not_linked') throw r.reason;
    const events: PsEvent[] = [];
    if (community.status === 'fulfilled' && Array.isArray(community.value)) for (const e of community.value) { const d = communityEventDto(e); if (d) events.push(d); }
    if (scout.status === 'fulfilled' && Array.isArray(scout.value)) for (const e of scout.value) { const d = scoutEventDto(e); if (d) events.push(d); }
    const out = events
      .filter((e) => {
        const start = Date.parse(e.startTime);
        if (!Number.isFinite(start)) return false;
        // Still on counts as upcoming: an event with an end is on until then.
        const end = e.endTime ? Date.parse(e.endTime) : start;
        return (Number.isFinite(end) ? end : start) >= from && (to === null || start <= to);
      })
      .map((e) => ({ ...e, distanceKm: near && e.lat !== null && e.lng !== null ? Math.round(distanceKm(near[0], near[1], e.lat, e.lng) * 10) / 10 : null }))
      .filter((e) => q.radiusKm === undefined || !near || (e.distanceKm !== null && e.distanceKm <= q.radiusKm))
      .sort((a, b) => Date.parse(a.startTime) - Date.parse(b.startTime) || a.id.localeCompare(b.id))
      .slice(0, MAX_EVENTS);
    return out;
  });

  app.get('/v1/photoscout/spots', {
    ...auth, ...lim.write,
    schema: {
      security: bearer, tags, summary: 'PhotoScout spots in a box',
      description: 'The spots I can see in PhotoScout inside `bbox` = south,west,north,east, with their good times and cover photo. Photo URLs are served by this server (`/v1/photoscout/photos/:id/thumb|file`) and need the bearer token.',
      querystring: bodyObj({ bbox: { type: 'string', maxLength: 100, description: 'south,west,north,east' } }),
      response: { 200: arrayOf(ref('PsSpot')), ...errors },
    },
  }, async (req) => {
    const parts = (req.query as { bbox: string }).bbox.split(',');
    if (parts.length !== 4) throw badRequest('bbox must be south,west,north,east');
    const [s0, w, n, e] = [num(parts[0], -90, 90, 'bbox south'), num(parts[1], -180, 180, 'bbox west'), num(parts[2], -90, 90, 'bbox north'), num(parts[3], -180, 180, 'bbox east')];
    if (s0 > n) throw badRequest('bbox south must not exceed north');
    const rows = await svc().json(req.userId, `/api/spots?bbox=${[s0, w, n, e].join(',')}`);
    return (Array.isArray(rows) ? rows : []).map(spotDto).filter((x) => x !== null).slice(0, 500);
  });

  app.get('/v1/photoscout/photos/:id/:variant', {
    ...auth, ...lim.write,
    schema: {
      security: bearer, tags, summary: 'A spot photo from PhotoScout',
      params: bodyObj({ id: { type: 'string', maxLength: 64 }, variant: { type: 'string', enum: ['thumb', 'file'] } }),
      response: { 200: { description: 'The image bytes.', type: 'string' }, ...errors },
    },
  }, async (req, reply) => {
    const { id, variant } = req.params as { id: string; variant: 'thumb' | 'file' };
    if (!PHOTO_ID.test(id)) throw badRequest('Not a photo id');
    const s = svc();
    const res = await s.checked(await s.request(req.userId, `/api/photos/${encodeURIComponent(id)}/${variant}`));
    const type = res.headers.get('content-type') ?? '';
    if (!/^image\/(jpeg|png|webp)\b/i.test(type)) throw new PhotoScoutError(502, 'photoscout_unavailable', 'PhotoScout did not send an image');
    const bytes = Buffer.from(await res.arrayBuffer());
    if (bytes.length > MAX_PHOTO_BYTES) throw new PhotoScoutError(502, 'photoscout_unavailable', 'That photo is too large');
    return reply.header('content-type', type.split(';')[0]).header('cache-control', 'private, max-age=3600').send(bytes);
  });

  app.post('/v1/photoscout/events/:id/rsvp', {
    ...auth, ...lim.write,
    schema: {
      security: bearer, tags, summary: 'Say I am going to (or interested in) an event, or take it back',
      description: 'The event id as listed: a uuid is a community event (going only), anything else a crawled event (going or interested). `{}` or `{status}` sets my answer (default going); `{going: false}` clears it.',
      params: bodyObj({ id: { type: 'string', minLength: 1, maxLength: 100 } }),
      body: bodyObj({ status: { type: 'string', enum: ['going', 'interested'] }, going: { type: 'boolean' } }, []),
      response: { 200: ref('PsRsvp'), ...errors },
    },
  }, async (req) => {
    const { id } = req.params as { id: string };
    const b = (req.body ?? {}) as { status?: 'going' | 'interested'; going?: boolean };
    const community = UUID.test(id);
    const source = community ? 'community' : 'scout';
    if (community && b.status === 'interested') throw badRequest('Community events only have going');
    const path = community ? `/api/community-events/${id}/rsvp` : `/api/events/${encodeURIComponent(id)}/rsvp`;
    const s = svc();
    const clear = b.going === false;
    const result = await s.json(req.userId, path, clear ? { method: 'DELETE' } : { method: 'PUT', body: community ? {} : { status: b.status ?? 'going' } });
    return rsvpDto(id, source, result);
  });

  app.post('/v1/photoscout/checkins', {
    ...auth, ...lim.write,
    schema: {
      security: bearer, tags, summary: 'Check in on PhotoScout',
      description: 'Exactly one of `spotId` (a public spot), `eventId` (a public community event) or `lat` + `lng` + `placeName`. Shows on the PhotoScout feed. PhotoScout allows 30 a day (429 `photoscout_rejected`).',
      body: bodyObj({
        spotId: { type: 'string', maxLength: 64 }, eventId: { type: 'string', maxLength: 64 },
        lat: { type: 'number', minimum: -90, maximum: 90 }, lng: { type: 'number', minimum: -180, maximum: 180 },
        placeName: { type: 'string', minLength: 1, maxLength: 120 }, body: { type: 'string', maxLength: 500 },
      }, []),
      response: { 201: ref('PsCheckin'), ...errors },
    },
  }, async (req, reply) => {
    const b = req.body as { spotId?: string; eventId?: string; lat?: number; lng?: number; placeName?: string; body?: string };
    const where = [b.spotId ? 1 : 0, b.eventId ? 1 : 0, b.lat !== undefined || b.lng !== undefined || b.placeName !== undefined ? 1 : 0].reduce((a, c) => a + c, 0);
    if (where !== 1) throw badRequest('Give spotId, eventId, or lat + lng + placeName');
    if (!b.spotId && !b.eventId && (b.lat === undefined || b.lng === undefined || !b.placeName)) throw badRequest('A free check-in needs lat, lng and placeName');
    const payload = b.spotId ? { spotId: b.spotId } : b.eventId ? { eventId: b.eventId } : { lat: b.lat, lng: b.lng, placeName: b.placeName };
    const made = await svc().json(req.userId, '/api/checkins', { method: 'POST', body: { ...payload, ...(b.body ? { body: b.body } : {}) } });
    return reply.code(201).send(checkinDto(made));
  });
}
