import type { Cruise, CruiseSource, RestStopFinder } from './ports.js';

/** In-memory cruises, for dev and tests. `organiser` may start; anyone may join. */
export class MemoryCruiseSource implements CruiseSource {
  private cruises = new Map<string, { name: string; organiser: string; meet: Cruise['meet'] }>();

  add(eventId: string, cruise: { name: string; organiser: string; meet: Cruise['meet'] }): void { this.cruises.set(eventId, cruise); }

  getCruise(eventId: string, userId: string): Cruise | null {
    const c = this.cruises.get(eventId);
    return c ? { name: c.name, meet: c.meet, canStart: c.organiser === userId, canJoin: true } : null;
  }
}

/** Rest-stop search needs map data (PhotoScout uses Overpass); this finds nothing. */
export const noRestStops: RestStopFinder = { find: async () => null };
