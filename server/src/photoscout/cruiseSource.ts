import { MAX_CONVOY_ROUTE_STOPS } from '../convoy/rooms.js';
import { thinImportant } from '../convoy/thin.js';
import { CruiseError, type Cruise, type CruiseSource } from '../ports.js';
import { cruiseOf } from './dto.js';
import { PhotoScoutError, type PhotoScoutService } from './service.js';

const EVENT_ID = /^[\w-]{1,64}$/;
const isPoint = (p: unknown): p is [number, number] =>
  Array.isArray(p) && p.length >= 2 && typeof p[0] === 'number' && typeof p[1] === 'number' && Math.abs(p[0]) <= 180 && Math.abs(p[1]) <= 90;

/** At most `max` stops, keeping the vertices that matter (see thinImportant), always with the first and the last. */
export function thin(points: [number, number][], max: number): [number, number][] {
  return thinImportant(points, max);
}

/**
 * Cruises are PhotoScout community events that carry a cruise. The definition (name, meet point, line) is read with the
 * asking member's own token, so a member who has not linked PhotoScout cannot open or join one.
 *
 * Who may START the convoy: the event's organiser. PhotoScout does not tell a connected app who the member is (no
 * "me" endpoint for bearer tokens), so when the token response carried no identity, any linked member who can read
 * the event may open it; the leader can hand over with `convoy.lead` as usual.
 */
export class PhotoScoutCruiseSource implements CruiseSource {
  constructor(private photoscout: PhotoScoutService) {}

  async getCruise(eventId: string, userId: string): Promise<Cruise | null> {
    if (!EVENT_ID.test(eventId)) return null;
    const me = await this.photoscout.identity(userId);
    if (!me) throw new CruiseError('Link your PhotoScout account to join a cruise');
    let ev: Record<string, unknown>;
    try {
      ev = await this.photoscout.json<Record<string, unknown>>(userId, `/api/community-events/${encodeURIComponent(eventId)}`);
    } catch (e) {
      if (e instanceof PhotoScoutError) {
        if (e.status === 404) return null;
        if (e.code === 'photoscout_not_linked') throw new CruiseError('Link your PhotoScout account to join a cruise');
        throw new CruiseError('PhotoScout is not reachable right now');
      }
      throw e;
    }
    const meta = cruiseOf(ev.cruise);
    if (!meta) return null;
    const raw = (ev.cruise as { route?: { vertices?: unknown; type?: unknown } }).route;
    let stops = Array.isArray(raw?.vertices) ? raw.vertices.filter(isPoint).map((p): [number, number] => [p[0], p[1]]) : [];
    // A circuit ends where it began; the rooms code expects that closing stop to be there.
    if (stops.length > 1 && raw?.type === 'circuit' && (stops[0][0] !== stops.at(-1)![0] || stops[0][1] !== stops.at(-1)![1])) stops = [...stops, stops[0]];
    const owner = ev.owner as { username?: unknown } | undefined;
    const organiser = me.id
      ? ev.ownerId === me.id
      : me.username
        ? typeof owner?.username === 'string' && owner.username.toLowerCase() === me.username.toLowerCase()
        : true;
    return {
      name: typeof ev.title === 'string' && ev.title.trim() ? ev.title : 'Cruise',
      meet: { lat: meta.meet.lat, lng: meta.meet.lng, label: meta.meet.note },
      canStart: organiser,
      canJoin: true,
      route: stops.length >= 2 ? { name: meta.routeName || 'Cruise route', stops: thin(stops, MAX_CONVOY_ROUTE_STOPS) } : null,
    };
  }
}
