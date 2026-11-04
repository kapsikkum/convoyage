/**
 * Transport for NSW Live Traffic hazards (incidents, roadworks, floods, fires, alpine conditions, major events).
 * Needs a TfNSW Open Data key (TFNSW_API_KEY). The whole state is a few hundred items, so it is fetched as one list,
 * kept for 3 minutes, and served stale if TfNSW is down.
 */
import type { Fetch } from '../http.js';
import { decodePolyline } from '../api/polyline.js';
import type { Pt } from './line.js';

export const TFNSW_CATEGORIES = ['incident', 'roadwork', 'flood', 'fire', 'alpine', 'majorevent'] as const;
export type TfnswCategory = (typeof TFNSW_CATEGORIES)[number];
const BASE = 'https://api.transport.nsw.gov.au/v1/live/hazards';
const TTL_MS = 3 * 60_000;

export interface TrafficItem {
  id: string;
  category: TfnswCategory;
  /** e.g. "Mamre Road upgrade", "Crash". */
  title: string;
  /** e.g. "Great Western Highway, Kelso". */
  road: string;
  lat: number;
  lng: number;
  /** The road is closed (in at least one direction), not just slowed. */
  closure: boolean;
  /** TfNSW's expected delay in minutes, when it gives one. */
  delayMin: number | null;
  advice: string;
  /** Where it applies along the road, when TfNSW draws it. */
  lines: Pt[][];
}

const text = (v: unknown) => (typeof v === 'string' ? v.replace(/<[^>]+>/g, ' ').replace(/\s+/g, ' ').trim() : '');

/** Pure: one category's GeoJSON into items. */
export function parseTfnsw(category: TfnswCategory, raw: unknown): TrafficItem[] {
  const features = (raw as { features?: unknown[] } | null)?.features;
  if (!Array.isArray(features)) return [];
  const out: TrafficItem[] = [];
  for (const f of features) {
    const feat = f as { id?: unknown; geometry?: { type?: string; coordinates?: unknown[] }; properties?: Record<string, any> };
    const p = feat.properties ?? {};
    if (p.ended === true) continue;
    const c = feat.geometry?.coordinates;
    const lng = Number(c?.[0]), lat = Number(c?.[1]);
    if (!Number.isFinite(lat) || !Number.isFinite(lng)) continue;
    const main = text(p.mainCategory);
    let title = text(p.headline) || text(p.displayName);
    if (main && title.toUpperCase().startsWith(main.toUpperCase())) title = title.slice(main.length).trim();
    if (!title) title = main ? main.charAt(0) + main.slice(1).toLowerCase() : category;
    const r0 = Array.isArray(p.roads) ? p.roads[0] ?? {} : {};
    const road = [text(r0.mainStreet), text(r0.suburb)].filter(Boolean).join(', ');
    const periods: any[] = Array.isArray(p.periods) ? p.periods : [];
    const closure = periods.some((x) => x?.closureType === 'ROAD_CLOSURE' || /^closed$/i.test(text(x?.roadextent))) || /closed/i.test(main);
    const delay = Number(p.expectedDelay);
    const lines: Pt[][] = [];
    for (const e of Array.isArray(p.encodedPolylines) ? p.encodedPolylines : []) {
      try {
        const pts = decodePolyline(String(e?.coords ?? ''), 5, 5000).map(([la, ln]): Pt => [ln, la]);
        if (pts.length >= 2) lines.push(pts);
      } catch { /* skip a bad line */ }
    }
    out.push({
      id: `${category}:${String(feat.id ?? out.length)}`, category, title, road, lat, lng, closure,
      delayMin: Number.isFinite(delay) && delay > 0 ? delay : null,
      advice: [text(p.adviceA), text(p.adviceB)].filter((s) => s && s !== 'null').join('. '), lines,
    });
  }
  return out;
}

export class TfnswFeed {
  private cache: { at: number; items: TrafficItem[] } | null = null;
  private inflight: Promise<TrafficItem[]> | null = null;

  constructor(private key: string, private fetcher: Fetch, private now: () => number = Date.now) {}

  async all(): Promise<TrafficItem[]> {
    if (this.cache && this.now() - this.cache.at < TTL_MS) return this.cache.items;
    this.inflight ??= this.load().finally(() => { this.inflight = null; });
    return this.inflight;
  }

  private async load(): Promise<TrafficItem[]> {
    const items: TrafficItem[] = [];
    let ok = 0;
    await Promise.all(TFNSW_CATEGORIES.map(async (cat) => {
      try {
        const res = await this.fetcher(`${BASE}/${cat}/open`, { headers: { authorization: `apikey ${this.key}`, accept: 'application/json' }, signal: AbortSignal.timeout(15_000) });
        if (!res.ok) return;
        items.push(...parseTfnsw(cat, await res.json()));
        ok++;
      } catch { /* that category is missing this round */ }
    }));
    if (ok === 0) {
      if (this.cache) return this.cache.items; // stale beats nothing
      throw new Error('TfNSW Live Traffic is not answering');
    }
    this.cache = { at: this.now(), items };
    return items;
  }
}
