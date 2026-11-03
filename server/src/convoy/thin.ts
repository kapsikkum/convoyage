/**
 * Thinning a line to a stop budget (convoy routes carry at most 25 stops).
 *
 * Evenly spaced points cut corners and roundabouts, so the shared line ran across blocks. This keeps the vertices that
 * matter instead: the one furthest from the chord of the stops kept so far is added until the budget is spent or the
 * line is within `toleranceM`. Points are [lng, lat]. The first and last points are always kept.
 */
const M_PER_DEG = 111_320;

/** Distance in metres from p to the segment a-b (local equirectangular projection; fine at road scale). */
export function offSegmentM(p: [number, number], a: [number, number], b: [number, number]): number {
  const cosLat = Math.cos((p[1] * Math.PI) / 180);
  const ax = (a[0] - p[0]) * cosLat * M_PER_DEG, ay = (a[1] - p[1]) * M_PER_DEG;
  const bx = (b[0] - p[0]) * cosLat * M_PER_DEG, by = (b[1] - p[1]) * M_PER_DEG;
  const dx = bx - ax, dy = by - ay;
  const len2 = dx * dx + dy * dy;
  const t = len2 <= 1e-9 ? 0 : Math.min(1, Math.max(0, -(ax * dx + ay * dy) / len2));
  return Math.hypot(ax + t * dx, ay + t * dy);
}

export function thinImportant(points: [number, number][], max: number, toleranceM = 1): [number, number][] {
  const budget = Math.max(2, max);
  if (points.length <= budget) return points;
  const keep = new Set<number>([0, points.length - 1]);
  type Span = { from: number; to: number; at: number; off: number };
  const worst = (from: number, to: number): Span | null => {
    let at = -1, off = 0;
    for (let i = from + 1; i < to; i++) {
      const d = offSegmentM(points[i], points[from], points[to]);
      if (d > off) { off = d; at = i; }
    }
    return at < 0 ? null : { from, to, at, off };
  };
  const open: Span[] = [];
  const first = worst(0, points.length - 1);
  if (first) open.push(first);
  while (keep.size < budget && open.length) {
    open.sort((x, y) => y.off - x.off);
    const s = open.shift()!;
    if (s.off < toleranceM) break;
    keep.add(s.at);
    const l = worst(s.from, s.at), r = worst(s.at, s.to);
    if (l) open.push(l);
    if (r) open.push(r);
  }
  return [...keep].sort((a, b) => a - b).map((i) => points[i]);
}
