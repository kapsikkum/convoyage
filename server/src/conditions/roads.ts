/**
 * Road quality from OpenStreetMap (PhotoScout's grading): ways near a route from Overpass, graded from their surface
 * and smoothness tags, matched to the route every 50 m. Results are kept in the database, per route line for 14 days
 * and per 0.05 degree map cell for the builder's layer, because road surfaces change slowly and Overpass is slow.
 */
import { createHash } from 'node:crypto';
import type { Kysely } from 'kysely';
import type { DB } from '../db/types.js';
import type { Fetch } from '../http.js';
import { USER_AGENT } from '../convoy/overpass.js';
import { offSegmentM } from '../convoy/thin.js';
import { encodePolyline } from '../api/polyline.js';
import { boxOf, cumulative, sampleEvery, simplify, type Box, type Pt } from './line.js';

export type Grade = 'good' | 'fair' | 'poor' | 'unknown';
export const ROADS_TTL_MS = 14 * 86_400_000;
export const CELL_DEG = 0.05;

const UNPAVED = /^(unpaved|gravel|fine_gravel|dirt|earth|ground|compacted|sand|grass|mud|clay|pebblestone|rock|woodchips)$/;
const PAVED = /^(paved|asphalt|concrete|concrete:plates|concrete:lanes|paving_stones|sett|cobblestone|bricks|metal|chipseal)$/;
const BAD = /^(bad|very_bad|horrible|very_horrible|impassable)$/;
const HIGHWAYS = 'motorway|trunk|primary|secondary|tertiary|unclassified|residential|living_street|road|track|raceway|motorway_link|trunk_link|primary_link|secondary_link|tertiary_link';

/** Pure. Unpaved or bad smoothness: poor. Paved and excellent/good: good. Paved or intermediate: fair. Untagged
 *  motorway/trunk/primary are assumed paved (fair); tracks are poor; anything else untagged is unknown. */
export function gradeRoad(tags: Record<string, string>): Grade {
  const { surface = '', smoothness = '', highway = '' } = tags;
  if (UNPAVED.test(surface) || BAD.test(smoothness)) return 'poor';
  if (PAVED.test(surface) && /^(excellent|good)$/.test(smoothness)) return 'good';
  if (PAVED.test(surface) || smoothness === 'intermediate') return 'fair';
  if (/^(motorway|trunk|primary)(_link)?$/.test(highway)) return 'fair';
  if (highway === 'track') return 'poor';
  return 'unknown';
}

/** A graded way, stored compactly: grade, name, highway, surface, [lng, lat] line. */
export interface Way { g: Grade; n: string; h: string; s: string; c: Pt[] }

interface Element { type: string; id: number; geometry?: { lat: number; lon: number }[]; tags?: Record<string, string> }

export function waysFrom(elements: Element[]): Way[] {
  const out: Way[] = [];
  for (const e of elements) {
    if (e.type !== 'way' || !e.geometry || e.geometry.length < 2) continue;
    const t = e.tags ?? {};
    out.push({ g: gradeRoad(t), n: t.name ?? t.ref ?? '', h: t.highway ?? '', s: t.surface ?? '', c: e.geometry.map((p): Pt => [p.lon, p.lat]) });
  }
  return out;
}

export interface RoadRun { grade: Grade; km: number; name: string; surface: string; line: string }
export interface RoadSummary {
  /** Kilometres of route in each grade. */
  km: Record<Grade, number>;
  unpavedKm: number;
  /** The route split where the grade changes; `line` is polyline6, for colouring the route. */
  runs: RoadRun[];
  /** The longest poor stretches, worst first. */
  worst: { name: string; km: number; surface: string }[];
  fetchedAt: string;
}

/** Pure: grade the route every 50 m from the nearest way within 25 m. */
export function summariseRoads(line: Pt[], ways: Way[], fetchedAt: string): RoadSummary {
  // Bucket way segments on a ~200 m grid.
  const CELL = 0.002;
  const grid = new Map<string, { w: Way; a: Pt; b: Pt }[]>();
  for (const w of ways) for (let i = 1; i < w.c.length; i++) {
    const b = boxOf([w.c[i - 1], w.c[i]]);
    for (let x = Math.floor(b.minLng / CELL); x <= Math.floor(b.maxLng / CELL); x++)
      for (let y = Math.floor(b.minLat / CELL); y <= Math.floor(b.maxLat / CELL); y++) {
        const k = `${x},${y}`; const l = grid.get(k); const s = { w, a: w.c[i - 1], b: w.c[i] };
        if (l) l.push(s); else grid.set(k, [s]);
      }
  }
  const samples = sampleEvery(line, 50);
  const tagged = samples.map(({ p, m }) => {
    let best: { w: Way; d: number } | null = null;
    const cx = Math.floor(p[0] / CELL), cy = Math.floor(p[1] / CELL);
    for (let x = cx - 1; x <= cx + 1; x++) for (let y = cy - 1; y <= cy + 1; y++)
      for (const s of grid.get(`${x},${y}`) ?? []) {
        const d = offSegmentM(p, s.a, s.b);
        if (d <= 25 && (!best || d < best.d)) best = { w: s.w, d };
      }
    return { p, m, w: best?.w ?? null };
  });
  const km: Record<Grade, number> = { good: 0, fair: 0, poor: 0, unknown: 0 };
  let unpavedKm = 0;
  const runs: (RoadRun & { pts: Pt[] })[] = [];
  for (let i = 0; i < tagged.length; i++) {
    const t = tagged[i];
    const grade: Grade = t.w?.g ?? 'unknown';
    const step = i + 1 < tagged.length ? (tagged[i + 1].m - t.m) / 1000 : 0;
    km[grade] += step;
    if (t.w && UNPAVED.test(t.w.s)) unpavedKm += step;
    const last = runs[runs.length - 1];
    if (last && last.grade === grade) { last.km += step; last.pts.push(t.p); if (!last.name && t.w?.n) last.name = t.w.n; }
    else {
      if (last) last.pts.push(t.p); // runs join up
      runs.push({ grade, km: step, name: t.w?.n ?? '', surface: t.w?.s ?? '', line: '', pts: [t.p] });
    }
  }
  const round = (n: number) => Math.round(n * 10) / 10;
  const worst = runs.filter((r) => r.grade === 'poor' && r.km >= 0.2).sort((a, b) => b.km - a.km).slice(0, 3)
    .map((r) => ({ name: r.name, km: round(r.km), surface: r.surface }));
  return {
    km: { good: round(km.good), fair: round(km.fair), poor: round(km.poor), unknown: round(km.unknown) },
    unpavedKm: round(unpavedKm),
    runs: runs.map(({ pts, ...r }) => ({ ...r, km: round(r.km), line: encodePolyline(pts.map(([lng, lat]): Pt => [lat, lng]), 6) })),
    worst,
    fetchedAt,
  };
}

const coords = (pts: Pt[]) => pts.map(([lng, lat]) => `${lat.toFixed(5)},${lng.toFixed(5)}`).join(',');

export class RoadQuality {
  constructor(
    private db: Kysely<DB>,
    private urls: string[],
    private fetcher: Fetch,
    private now: () => number = Date.now,
  ) {}

  private async overpass(query: string): Promise<Element[]> {
    let lastErr: unknown = new Error('no Overpass endpoint');
    for (const url of this.urls) {
      try {
        const res = await this.fetcher(url, {
          method: 'POST', signal: AbortSignal.timeout(95_000),
          headers: { 'content-type': 'application/x-www-form-urlencoded', accept: 'application/json', 'user-agent': USER_AGENT },
          body: `data=${encodeURIComponent(query)}`,
        });
        if (!res.ok) { lastErr = new Error(`Overpass HTTP ${res.status}`); continue; }
        const body = (await res.json()) as { elements?: Element[]; remark?: string };
        // Overpass answers 200 with a remark and no elements when it ran out of time or memory.
        if (body.remark && /error|timed out|out of memory/i.test(body.remark)) { lastErr = new Error(`Overpass: ${body.remark}`); continue; }
        return body.elements ?? [];
      } catch (err) { lastErr = err; }
    }
    throw lastErr;
  }

  private inflight = new Map<string, Promise<RoadSummary>>();

  /**
   * Road quality along a route: from the database when this line was graded in the last 14 days, else fetched (one
   * fetch per line at a time; a caller that stops waiting leaves it running so the result is saved for next time).
   */
  async along(line: Pt[]): Promise<RoadSummary> {
    // Overpass's cost grows with the points in the `around` line: 15 m off the road is well inside the 25 m radius.
    const thin = simplify(line, 15);
    const hash = createHash('sha1').update(coords(thin)).digest('hex');
    const hit = await this.db.selectFrom('route_roads').select(['result', 'fetched_at']).where('hash', '=', hash).executeTakeFirst();
    if (hit && this.now() - new Date(hit.fetched_at).getTime() < ROADS_TTL_MS) return hit.result as RoadSummary;
    let job = this.inflight.get(hash);
    if (!job) {
      job = this.fetchAlong(line, thin, hash).finally(() => this.inflight.delete(hash));
      job.catch(() => {}); // a caller that gave up must not leave an unhandled rejection behind
      this.inflight.set(hash, job);
    }
    return job;
  }

  private async fetchAlong(line: Pt[], thin: Pt[], hash: string): Promise<RoadSummary> {
    const ways: Way[] = [];
    // Small boxes along the route (about 1.5 km of road each), many per request. Not Overpass's `around` on a line:
    // one of overpass-api.de's backends answers that with nothing.
    const cum = cumulative(thin);
    const boxes: Box[] = [];
    let from = 0;
    for (let i = 1; i < thin.length; i++) {
      if (cum[i] - cum[from] >= 1500 || i === thin.length - 1) { boxes.push(boxOf(thin.slice(from, i + 1), 40)); from = i; }
    }
    const f = (n: number) => n.toFixed(5);
    const seen = new Set<string>();
    for (let i = 0; i < boxes.length; i += 40) {
      const part = boxes.slice(i, i + 40).map((b) => `way["highway"~"^(${HIGHWAYS})$"](${f(b.minLat)},${f(b.minLng)},${f(b.maxLat)},${f(b.maxLng)});`).join('');
      for (const w of waysFrom(await this.overpass(`[out:json][timeout:90];(${part});out tags geom;`))) {
        const key = `${w.n}|${w.c[0]}|${w.c[w.c.length - 1]}`;
        if (!seen.has(key)) { seen.add(key); ways.push(w); }
      }
    }
    // No roads at all along a real route means Overpass let us down, not that there are none: do not keep that.
    if (!ways.length && line.length > 1 && thin.length > 2) throw new Error('Overpass returned no roads along the route');
    const at = new Date(this.now()).toISOString();
    const result = summariseRoads(line, ways, at);
    await this.db.insertInto('route_roads').values({ hash, fetched_at: at, result: JSON.stringify(result) })
      .onConflict((oc) => oc.column('hash').doUpdateSet({ fetched_at: at, result: JSON.stringify(result) })).execute();
    await this.db.deleteFrom('route_roads').where('fetched_at', '<', new Date(this.now() - ROADS_TTL_MS * 2).toISOString()).execute();
    return result;
  }

  /** Graded ways for a map view, per 0.05 degree cell, as GeoJSON. At most `maxCells` cells. */
  async area(b: Box, maxCells = 9): Promise<{ type: 'FeatureCollection'; features: unknown[] }> {
    const cells: Box[] = [];
    const r = (n: number) => Math.round(n * 1e4) / 1e4;
    for (let s = Math.floor(b.minLat / CELL_DEG); s * CELL_DEG < b.maxLat; s++)
      for (let w = Math.floor(b.minLng / CELL_DEG); w * CELL_DEG < b.maxLng; w++)
        cells.push({ minLat: r(s * CELL_DEG), minLng: r(w * CELL_DEG), maxLat: r((s + 1) * CELL_DEG), maxLng: r((w + 1) * CELL_DEG) });
    const features: unknown[] = [];
    for (const c of cells.slice(0, maxCells)) {
      const key = `${c.minLat},${c.minLng}`;
      const hit = await this.db.selectFrom('road_cells').select(['ways', 'fetched_at']).where('cell', '=', key).executeTakeFirst();
      let ways: Way[];
      if (hit && this.now() - new Date(hit.fetched_at).getTime() < ROADS_TTL_MS) ways = hit.ways as Way[];
      else {
        ways = waysFrom(await this.overpass(`[out:json][timeout:60];way["highway"~"^(${HIGHWAYS})$"](${c.minLat},${c.minLng},${c.maxLat},${c.maxLng});out tags geom;`));
        const at = new Date(this.now()).toISOString();
        await this.db.insertInto('road_cells').values({ cell: key, fetched_at: at, ways: JSON.stringify(ways) })
          .onConflict((oc) => oc.column('cell').doUpdateSet({ fetched_at: at, ways: JSON.stringify(ways) })).execute();
      }
      for (const w of ways) features.push({ type: 'Feature', geometry: { type: 'LineString', coordinates: w.c }, properties: { grade: w.g, name: w.n, surface: w.s, highway: w.h } });
    }
    return { type: 'FeatureCollection', features };
  }
}

