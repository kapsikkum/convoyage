import { haversine } from './geo.js';
import type { MemberVehicle, Who } from './rooms.js';

/**
 * Open Cruise (after Dread905's PhotoScout PR #17): drivers who switch it on see each other, and open convoys, within
 * a few kilometres, and can invite someone close by into a convoy. Off by default; positions held in memory only,
 * shared rounded (about 100 m) and only with other drivers who have it on.
 */

/** Drivers and open convoys within this distance are listed. */
export const OPEN_CRUISE_VISIBLE_RADIUS_M = 5_000;
/** You can invite someone this close. */
export const OPEN_CRUISE_MATCH_DISTANCE_M = 500;
/** One invite per pair per this long. */
export const OPEN_CRUISE_INVITE_GAP_MS = 10_000;
/** How long an invite stands. */
export const OPEN_CRUISE_INVITE_TTL_MS = 60_000;
/** A cruiser with no fix for this long drops off the lists. */
export const OPEN_CRUISE_STALE_MS = 2 * 60_000;
/** Nearby lists are pushed to a cruiser at most this often. */
export const OPEN_CRUISE_PUSH_GAP_MS = 5_000;

export interface Cruiser { who: Who; lat: number | null; lng: number | null; at: number; pushedAt: number }

export interface NearbyDriver { id: string; username: string; avatar: string; vehicle: MemberVehicle | null; lat: number; lng: number; distanceM: number; canInvite: boolean }
export interface NearbyConvoy { code: string; name: string; members: number; lat: number; lng: number; distanceM: number; destination: string | null }
export interface OpenRoom { code: string; name: string; members: number; lat: number; lng: number; destination: string | null; memberIds: string[] }

const round3 = (n: number): number => Math.round(n * 1000) / 1000;
const roundTo = (n: number, step: number): number => Math.round(n / step) * step;

export class OpenCruise {
  private cruisers = new Map<string, Cruiser>();
  private invites = new Map<string, number>();

  constructor(private now: () => number = Date.now) {}

  isOn(userId: string): boolean { return this.cruisers.has(userId); }

  set(who: Who, on: boolean): void {
    if (!on) { this.cruisers.delete(who.id); return; }
    const had = this.cruisers.get(who.id);
    this.cruisers.set(who.id, had ? { ...had, who } : { who, lat: null, lng: null, at: this.now(), pushedAt: 0 });
  }

  /** A fix from a cruiser. Returns whether a nearby list is due for them. */
  update(userId: string, lat: number, lng: number): boolean {
    const c = this.cruisers.get(userId);
    if (!c) return false;
    const now = this.now();
    c.lat = lat; c.lng = lng; c.at = now;
    if (now - c.pushedAt < OPEN_CRUISE_PUSH_GAP_MS) return false;
    c.pushedAt = now;
    return true;
  }

  /** What [userId] may see: other fresh cruisers within range (not in a convoy with them), and open convoys they are not in. */
  nearby(userId: string, openRooms: OpenRoom[], sameRoom: (a: string, b: string) => boolean): { drivers: NearbyDriver[]; convoys: NearbyConvoy[] } {
    const me = this.cruisers.get(userId);
    if (!me || me.lat === null || me.lng === null) return { drivers: [], convoys: [] };
    const now = this.now();
    const drivers: NearbyDriver[] = [];
    for (const [id, c] of this.cruisers) {
      if (id === userId || c.lat === null || c.lng === null || now - c.at > OPEN_CRUISE_STALE_MS || sameRoom(userId, id)) continue;
      const d = haversine(me.lat, me.lng, c.lat, c.lng);
      if (d > OPEN_CRUISE_VISIBLE_RADIUS_M) continue;
      drivers.push({
        id, username: c.who.username, avatar: c.who.avatar, vehicle: c.who.vehicle ?? null,
        lat: round3(c.lat), lng: round3(c.lng), distanceM: roundTo(d, 50), canInvite: d <= OPEN_CRUISE_MATCH_DISTANCE_M,
      });
    }
    const convoys: NearbyConvoy[] = [];
    for (const r of openRooms) {
      if (r.memberIds.includes(userId)) continue;
      const d = haversine(me.lat, me.lng, r.lat, r.lng);
      if (d > OPEN_CRUISE_VISIBLE_RADIUS_M) continue;
      convoys.push({ code: r.code, name: r.name, members: r.members, lat: round3(r.lat), lng: round3(r.lng), distanceM: roundTo(d, 50), destination: r.destination });
    }
    drivers.sort((a, b) => a.distanceM - b.distanceM);
    convoys.sort((a, b) => a.distanceM - b.distanceM);
    return { drivers: drivers.slice(0, 20), convoys: convoys.slice(0, 10) };
  }

  /** Whether [from] may invite [to] now; records the invite when it may. */
  invite(from: string, to: string): { ok: true; expiresAt: number } | { ok: false; error: string } {
    const a = this.cruisers.get(from);
    const b = this.cruisers.get(to);
    if (!a) return { ok: false, error: 'Turn on Open Cruise first' };
    if (!b) return { ok: false, error: 'They are not on Open Cruise any more' };
    const now = this.now();
    if (a.lat === null || a.lng === null || b.lat === null || b.lng === null || now - b.at > OPEN_CRUISE_STALE_MS) return { ok: false, error: 'Waiting for a location fix' };
    if (haversine(a.lat, a.lng, b.lat, b.lng) > OPEN_CRUISE_MATCH_DISTANCE_M) return { ok: false, error: `Get within ${OPEN_CRUISE_MATCH_DISTANCE_M} m to invite` };
    const key = `${from}>${to}`;
    if (now - (this.invites.get(key) ?? 0) < OPEN_CRUISE_INVITE_GAP_MS) return { ok: false, error: 'You just invited them' };
    this.invites.set(key, now);
    return { ok: true, expiresAt: now + OPEN_CRUISE_INVITE_TTL_MS };
  }

  /** Every cruiser id, for pushing lists to. */
  ids(): string[] { return [...this.cruisers.keys()]; }

  sweep(): void {
    const now = this.now();
    for (const [key, at] of this.invites) if (now - at > OPEN_CRUISE_INVITE_TTL_MS) this.invites.delete(key);
  }
}
