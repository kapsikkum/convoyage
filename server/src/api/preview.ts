import { decodePolyline, encodePolyline } from './polyline.js';

export const PREVIEW_MAX_POINTS = 100;

/** Perpendicular distance (in degrees, flat approximation) of p from segment a-b. */
function dist(p: [number, number], a: [number, number], b: [number, number]): number {
  const dx = b[0] - a[0];
  const dy = b[1] - a[1];
  const len2 = dx * dx + dy * dy;
  const t = len2 === 0 ? 0 : Math.max(0, Math.min(1, ((p[0] - a[0]) * dx + (p[1] - a[1]) * dy) / len2));
  return Math.hypot(p[0] - (a[0] + t * dx), p[1] - (a[1] + t * dy));
}

function rdp(points: [number, number][], eps: number): [number, number][] {
  const keep = new Uint8Array(points.length);
  keep[0] = keep[points.length - 1] = 1;
  const stack: [number, number][] = [[0, points.length - 1]];
  while (stack.length) {
    const [s, e] = stack.pop()!;
    let worst = 0;
    let at = -1;
    for (let i = s + 1; i < e; i++) {
      const d = dist(points[i], points[s], points[e]);
      if (d > worst) { worst = d; at = i; }
    }
    if (at >= 0 && worst > eps) { keep[at] = 1; stack.push([s, at], [at, e]); }
  }
  return points.filter((_, i) => keep[i]);
}

/** At most `max` points that keep the shape (Douglas-Peucker with a widening tolerance). */
export function simplify(points: [number, number][], max = PREVIEW_MAX_POINTS): [number, number][] {
  if (points.length <= max) return points;
  let lo = 0;
  let hi = 1;
  let best = rdp(points, hi);
  if (best.length > max) return best.filter((_, i) => i % Math.ceil(best.length / max) === 0 || i === best.length - 1).slice(0, max);
  for (let i = 0; i < 18; i++) {
    const mid = (lo + hi) / 2;
    const r = rdp(points, mid);
    if (r.length > max) lo = mid; else { hi = mid; best = r; }
  }
  return best;
}

export interface RoutePreview {
  polyline: string;
  polylinePrecision: 5;
  pointCount: number;
  bbox: { south: number; west: number; north: number; east: number } | null;
}

/** A feed-card route: simplified polyline (<= 100 points, precision 5) plus the bounding box of the full trace. Null without a trace. */
export function routePreview(polyline: string | null, precision: number): RoutePreview | null {
  if (!polyline) return null;
  let pts: [number, number][];
  try { pts = decodePolyline(polyline, precision === 6 ? 6 : 5); } catch { return null; }
  if (!pts.length) return null;
  let south = 90, north = -90, west = 180, east = -180;
  for (const [lat, lng] of pts) {
    if (lat < south) south = lat; if (lat > north) north = lat;
    if (lng < west) west = lng; if (lng > east) east = lng;
  }
  const small = simplify(pts);
  return { polyline: encodePolyline(small, 5), polylinePrecision: 5, pointCount: small.length, bbox: { south, west, north, east } };
}
