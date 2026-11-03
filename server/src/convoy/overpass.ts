import { haversine } from './geo.js';
import type { Fetch } from '../http.js';
import type { RestStop, RestStopFinder } from '../ports.js';

const TIMEOUT_MS = 5000;
const CACHE_TTL_MS = 10 * 60_000;
const CACHE_MAX = 200;
export const REST_RADIUS_M = 10_000;
export const DEFAULT_OVERPASS_URLS = ['https://overpass-api.de/api/interpreter', 'https://overpass.openstreetmap.fr/api/interpreter'];
export const USER_AGENT = 'Convoyage/0.1 (self-hosted)';

interface Element { type?: string; lat?: number; lon?: number; center?: { lat: number; lon: number }; tags?: Record<string, string> }

const KIND_LABEL: Record<string, string> = {
  fuel: 'Fuel station', rest_area: 'Rest area', cafe: 'Cafe', fast_food: 'Fast food', restaurant: 'Restaurant', picnic_site: 'Picnic site',
};

function kindOf(tags: Record<string, string>): string | null {
  if (tags.amenity === 'fuel') return 'fuel';
  if (tags.highway === 'rest_area') return 'rest_area';
  if (tags.amenity === 'cafe' || tags.amenity === 'fast_food' || tags.amenity === 'restaurant') return tags.amenity;
  if (tags.tourism === 'picnic_site') return 'picnic_site';
  return null;
}

/** The Overpass QL for everything a convoy might stop at around one point. */
export function overpassQuery(lat: number, lng: number, radiusM: number): string {
  const around = `(around:${radiusM},${lat},${lng})`;
  return `[out:json][timeout:5];(`
    + `nwr["amenity"="fuel"]${around};nwr["highway"="rest_area"]${around};`
    + `nwr["amenity"~"^(cafe|fast_food|restaurant)$"]${around};nwr["tourism"="picnic_site"]${around};`
    + `);out center 120;`;
}

/** Rest stops from OpenStreetMap through Overpass: fuel and rest areas first, nearest first, cached 10 minutes by rounded location. */
export class OverpassRestStops implements RestStopFinder {
  private cache = new Map<string, { at: number; stop: RestStop | null }>();

  constructor(
    /** One endpoint or several, tried in order: the public servers are often at capacity. */
    private urls: string | string[] = DEFAULT_OVERPASS_URLS,
    private fetcher: Fetch = (u, init) => fetch(u, init),
    private now: () => number = Date.now,
    private radiusM = REST_RADIUS_M,
  ) {}

  async find(_route: [number, number][], from: [number, number]): Promise<RestStop | null> {
    const [lng, lat] = from;
    // About 1 km cells: the same convoy asking again a few hundred metres on reuses the answer.
    const key = `${lat.toFixed(2)},${lng.toFixed(2)}`;
    const hit = this.cache.get(key);
    if (hit && this.now() - hit.at < CACHE_TTL_MS) return hit.stop;
    let stop: RestStop | null = null;
    let answered = false;
    for (const url of Array.isArray(this.urls) ? this.urls : [this.urls]) {
      try {
        const res = await this.fetcher(url, {
          method: 'POST',
          signal: AbortSignal.timeout(TIMEOUT_MS),
          headers: { 'content-type': 'application/x-www-form-urlencoded', accept: 'application/json', 'user-agent': USER_AGENT },
          body: `data=${encodeURIComponent(overpassQuery(lat, lng, this.radiusM))}`,
        });
        if (!res.ok) continue;
        const data = (await res.json()) as { elements?: Element[] };
        stop = this.pick(data.elements ?? [], lat, lng);
        answered = true;
        break;
      } catch { /* try the next endpoint */ }
    }
    if (!answered) return null; // not cached: try again next time
    if (this.cache.size >= CACHE_MAX) this.cache.delete(this.cache.keys().next().value!);
    this.cache.set(key, { at: this.now(), stop });
    return stop;
  }

  private pick(elements: Element[], lat: number, lng: number): RestStop | null {
    const found: { stop: RestStop; preferred: boolean; d: number }[] = [];
    for (const e of elements) {
      const tags = e.tags ?? {};
      const kind = kindOf(tags);
      const eLat = e.lat ?? e.center?.lat;
      const eLng = e.lon ?? e.center?.lon;
      if (!kind || typeof eLat !== 'number' || typeof eLng !== 'number') continue;
      const name = (tags.name || tags.brand || tags.operator || '').trim();
      const label = name ? `${name} (${KIND_LABEL[kind].toLowerCase()})` : KIND_LABEL[kind];
      found.push({ stop: { lat: eLat, lng: eLng, label }, preferred: kind === 'fuel' || kind === 'rest_area', d: haversine(lat, lng, eLat, eLng) });
    }
    found.sort((a, b) => Number(b.preferred) - Number(a.preferred) || a.d - b.d);
    return found[0]?.stop ?? null;
  }
}
