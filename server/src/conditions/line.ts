/**
 * Geometry along a route for the conditions checks. Points are [lng, lat]; distances use a local equirectangular
 * projection, which is fine at road scale.
 */
import { offSegmentM } from '../convoy/thin.js';
import { M_PER_DEG_LAT } from '../convoy/geo.js';

export type Pt = [number, number];

export function segM(a: Pt, b: Pt): number {
  const k = Math.cos((((a[1] + b[1]) / 2) * Math.PI) / 180);
  return Math.hypot((b[0] - a[0]) * k, b[1] - a[1]) * M_PER_DEG_LAT;
}

/** Metres from the start to each vertex. */
export function cumulative(line: Pt[]): number[] {
  const out = [0];
  for (let i = 1; i < line.length; i++) out.push(out[i - 1] + segM(line[i - 1], line[i]));
  return out;
}

/** A point every `stepM` along the line (and the last vertex), with how far along it is. */
export function sampleEvery(line: Pt[], stepM: number): { p: Pt; m: number }[] {
  if (line.length === 0) return [];
  const cum = cumulative(line);
  const total = cum[cum.length - 1];
  const out: { p: Pt; m: number }[] = [];
  let i = 1;
  for (let m = 0; m < total; m += stepM) {
    while (i < line.length - 1 && cum[i] < m) i++;
    const a = line[i - 1], b = line[i];
    const seg = cum[i] - cum[i - 1] || 1;
    const f = Math.min(1, Math.max(0, (m - cum[i - 1]) / seg));
    out.push({ p: [a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f], m });
  }
  out.push({ p: line[line.length - 1], m: total });
  return out;
}

export interface Box { minLng: number; minLat: number; maxLng: number; maxLat: number }

export function boxOf(points: Pt[], padM = 0): Box {
  let minLng = Infinity, minLat = Infinity, maxLng = -Infinity, maxLat = -Infinity;
  for (const [lng, lat] of points) {
    if (lng < minLng) minLng = lng; if (lng > maxLng) maxLng = lng;
    if (lat < minLat) minLat = lat; if (lat > maxLat) maxLat = lat;
  }
  const dLat = padM / M_PER_DEG_LAT;
  const dLng = padM / (M_PER_DEG_LAT * Math.max(0.1, Math.cos((((minLat + maxLat) / 2) * Math.PI) / 180)));
  return { minLng: minLng - dLng, minLat: minLat - dLat, maxLng: maxLng + dLng, maxLat: maxLat + dLat };
}

export const inBox = (b: Box, p: Pt) => p[0] >= b.minLng && p[0] <= b.maxLng && p[1] >= b.minLat && p[1] <= b.maxLat;

/**
 * Finds how close points come to a route, and where along it. Segments are bucketed on a grid so a long route and a
 * few hundred reports stay cheap.
 */
export class RouteIndex {
  readonly cum: number[];
  readonly total: number;
  private grid = new Map<string, number[]>();
  private static CELL = 0.01;

  constructor(readonly line: Pt[]) {
    this.cum = cumulative(line);
    this.total = this.cum[this.cum.length - 1] ?? 0;
    for (let i = 1; i < line.length; i++) {
      const b = boxOf([line[i - 1], line[i]]);
      for (let x = Math.floor(b.minLng / RouteIndex.CELL); x <= Math.floor(b.maxLng / RouteIndex.CELL); x++)
        for (let y = Math.floor(b.minLat / RouteIndex.CELL); y <= Math.floor(b.maxLat / RouteIndex.CELL); y++) {
          const k = `${x},${y}`;
          const list = this.grid.get(k);
          if (list) list.push(i); else this.grid.set(k, [i]);
        }
    }
  }

  /** Distance from p to the route and metres along it at the closest point, or null if further than `withinM`. */
  near(p: Pt, withinM: number): { d: number; m: number } | null {
    const reach = Math.ceil(withinM / (RouteIndex.CELL * 80_000)) ;
    const cx = Math.floor(p[0] / RouteIndex.CELL), cy = Math.floor(p[1] / RouteIndex.CELL);
    let best: { d: number; m: number } | null = null;
    const seen = new Set<number>();
    for (let x = cx - reach; x <= cx + reach; x++)
      for (let y = cy - reach; y <= cy + reach; y++)
        for (const i of this.grid.get(`${x},${y}`) ?? []) {
          if (seen.has(i)) continue;
          seen.add(i);
          const a = this.line[i - 1], b = this.line[i];
          const d = offSegmentM(p, a, b);
          if (d <= withinM && (!best || d < best.d)) {
            const dA = segM(a, p);
            best = { d, m: this.cum[i - 1] + Math.min(dA, this.cum[i] - this.cum[i - 1]) };
          }
        }
    return best;
  }

  /** Closest approach of a polyline (e.g. a closure's extent) to the route. */
  nearLine(points: Pt[], withinM: number): { d: number; m: number } | null {
    let best: { d: number; m: number } | null = null;
    for (const s of sampleEvery(points, 50)) {
      const n = this.near(s.p, withinM);
      if (n && (!best || n.d < best.d)) best = n;
    }
    return best;
  }
}

/** Douglas-Peucker: drop vertices within `tolM` of the simplified line. First and last are kept. */
export function simplify(points: Pt[], tolM: number): Pt[] {
  if (points.length <= 2) return points.slice();
  const keep = new Uint8Array(points.length);
  keep[0] = keep[points.length - 1] = 1;
  const stack: [number, number][] = [[0, points.length - 1]];
  while (stack.length) {
    const [a, b] = stack.pop()!;
    let at = -1, off = tolM;
    for (let i = a + 1; i < b; i++) {
      const d = offSegmentM(points[i], points[a], points[b]);
      if (d > off) { off = d; at = i; }
    }
    if (at >= 0) { keep[at] = 1; stack.push([a, at], [at, b]); }
  }
  return points.filter((_, i) => keep[i]);
}
