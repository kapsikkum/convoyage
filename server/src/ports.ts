import type { IncomingMessage } from 'node:http';
import type { Who } from './convoy/rooms.js';

/** Who is opening a socket. Return null to refuse it (401). */
export interface Authenticator {
  authenticate(req: IncomingMessage): Promise<Who | null> | Who | null;
}

export interface Cruise {
  name: string;
  meet: { lat: number; lng: number; label: string };
  /** May open the cruise's convoy (the organiser). */
  canStart: boolean;
  /** May join it once it exists. */
  canJoin: boolean;
  /** The line the cruise drives, as [lng, lat] stops (at most MAX_CONVOY_ROUTE_STOPS). The convoy opens with it shared. */
  route?: { name: string; stops: [number, number][] } | null;
}

/** A cruise lookup that failed for a reason the person should read ("link your PhotoScout account"). */
export class CruiseError extends Error {}

/** Where cruises (events with a convoy) come from: PhotoScout events, read with the member's own linked token. */
export interface CruiseSource {
  getCruise(eventId: string, userId: string): Promise<Cruise | null> | Cruise | null;
}

export interface RestStop { lat: number; lng: number; label: string }

/** Finds a rest stop ahead on a route ([lng, lat] stops), or null. */
export interface RestStopFinder {
  find(route: [number, number][], from: [number, number]): Promise<RestStop | null>;
}
