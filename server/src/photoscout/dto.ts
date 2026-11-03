/**
 * PhotoScout's JSON in, Convoyage-shaped DTOs out. PhotoScout is a separate project whose shapes can grow, so
 * everything here is read defensively and only the fields the apps use are copied.
 */

type Raw = Record<string, unknown>;
const obj = (v: unknown): Raw => (typeof v === 'object' && v !== null && !Array.isArray(v) ? (v as Raw) : {});
const text = (v: unknown, max = 2000): string => (typeof v === 'string' ? v.slice(0, max) : '');
const numOrNull = (v: unknown): number | null => (typeof v === 'number' && Number.isFinite(v) ? v : null);
const count = (v: unknown): number => (typeof v === 'number' && Number.isFinite(v) && v > 0 ? Math.floor(v) : 0);
const strings = (v: unknown): string[] => (Array.isArray(v) ? v.filter((s): s is string => typeof s === 'string').slice(0, 50) : []);
const rsvp = (v: unknown): 'going' | 'interested' | null => (v === 'going' || v === 'interested' ? v : null);

export interface PsCruise {
  /** The route's name, and whether PhotoScout gave a line (the line itself is fetched when a convoy opens). */
  routeName: string;
  meet: { lat: number; lng: number; note: string };
  rollOut: string;
}

export interface PsEvent {
  /** A community event id (uuid), or a crawled event's group key. Cruise convoys can only be made from community events. */
  id: string;
  source: 'community' | 'scout';
  title: string;
  description: string;
  startTime: string;
  endTime: string | null;
  venue: string;
  address: string;
  lat: number | null;
  lng: number | null;
  imageUrl: string;
  url: string;
  going: number;
  interested: number;
  myRsvp: 'going' | 'interested' | null;
  cruise: PsCruise | null;
}

export function cruiseOf(v: unknown): PsCruise | null {
  const c = obj(v);
  const meet = obj(c.meet);
  const lat = numOrNull(meet.lat), lng = numOrNull(meet.lng);
  if (lat === null || lng === null) return null;
  return { routeName: text(obj(c.route).name, 100), meet: { lat, lng, note: text(meet.note, 200) }, rollOut: text(c.rollOut, 40) };
}

export function communityEventDto(v: unknown): PsEvent | null {
  const e = obj(v);
  if (typeof e.id !== 'string' || typeof e.startTime !== 'string') return null;
  return {
    id: e.id, source: 'community', title: text(e.title, 200), description: text(e.description), startTime: e.startTime,
    endTime: typeof e.endTime === 'string' ? e.endTime : null, venue: text(e.venueName, 200), address: text(e.address, 300),
    lat: numOrNull(e.lat), lng: numOrNull(e.lng), imageUrl: '', url: '',
    going: count(e.going), interested: 0, myRsvp: e.isGoing === true ? 'going' : null, cruise: cruiseOf(e.cruise),
  };
}

export function scoutEventDto(v: unknown): PsEvent | null {
  const e = obj(v);
  if (typeof e.group !== 'string' || typeof e.startTime !== 'string') return null;
  return {
    id: e.group, source: 'scout', title: text(e.title, 200), description: text(e.description), startTime: e.startTime,
    endTime: typeof e.endTime === 'string' ? e.endTime : null, venue: text(e.venueName, 200), address: text(e.address, 300),
    lat: numOrNull(e.lat), lng: numOrNull(e.lng), imageUrl: text(e.imageUrl, 500), url: text(e.url, 500),
    going: count(e.going), interested: count(e.interested), myRsvp: rsvp(e.myRsvp), cruise: null,
  };
}

export interface PsSpot {
  id: string;
  name: string;
  notes: string;
  lat: number;
  lng: number;
  tags: string[];
  facingDeg: number | null;
  fovDeg: number | null;
  goodTimes: { phases: string[]; months: number[]; days: string; conditions: string[]; avoid: string; notes: string };
  photoCount: number;
  /** Convoyage-served (needs the bearer token, like /v1/media): `/v1/photoscout/photos/:id/thumb` and `/file`. */
  photo: { id: string; thumbUrl: string; url: string } | null;
}

const PHOTO_ID = /^[\w-]{1,64}$/;

export function spotDto(v: unknown): PsSpot | null {
  const s = obj(v);
  const lat = numOrNull(s.lat), lng = numOrNull(s.lng);
  if (typeof s.id !== 'string' || lat === null || lng === null) return null;
  const g = obj(s.goodTimes);
  const coverId = typeof s.coverThumbUrl === 'string' ? /\/api\/photos\/([^/]+)\/thumb$/.exec(s.coverThumbUrl)?.[1] : undefined;
  return {
    id: s.id, name: text(s.name, 200), notes: text(s.notes), lat, lng, tags: strings(s.tags), facingDeg: numOrNull(s.facingDeg), fovDeg: numOrNull(s.fovDeg),
    goodTimes: {
      phases: strings(g.phases), months: Array.isArray(g.months) ? g.months.filter((n): n is number => typeof n === 'number').slice(0, 12) : [],
      days: text(g.days, 20) || 'any', conditions: strings(g.conditions), avoid: text(g.avoid, 300), notes: text(g.notes, 500),
    },
    photoCount: count(s.photoCount),
    photo: coverId && PHOTO_ID.test(coverId) ? { id: coverId, thumbUrl: `/v1/photoscout/photos/${coverId}/thumb`, url: `/v1/photoscout/photos/${coverId}/file` } : null,
  };
}

export interface PsCheckin { id: string; createdAt: string; placeName: string; lat: number | null; lng: number | null; body: string }

export function checkinDto(v: unknown): PsCheckin {
  const c = obj(v);
  return { id: text(c.id, 64), createdAt: text(c.created_at, 40), placeName: text(c.place_name, 120), lat: numOrNull(c.lat), lng: numOrNull(c.lng), body: text(c.body, 500) };
}

export interface PsRsvp { eventId: string; source: 'community' | 'scout'; going: number; interested: number; myRsvp: 'going' | 'interested' | null }

export function rsvpDto(eventId: string, source: PsRsvp['source'], v: unknown): PsRsvp {
  const r = obj(obj(v).rsvp);
  return { eventId, source, going: count(r.going), interested: count(r.interested), myRsvp: rsvp(r.mine) };
}

/** Great-circle distance in kilometres. */
export function distanceKm(aLat: number, aLng: number, bLat: number, bLng: number): number {
  const rad = Math.PI / 180;
  const h = Math.sin(((bLat - aLat) * rad) / 2) ** 2 + Math.cos(aLat * rad) * Math.cos(bLat * rad) * Math.sin(((bLng - aLng) * rad) / 2) ** 2;
  return 12742 * Math.asin(Math.min(1, Math.sqrt(h)));
}
