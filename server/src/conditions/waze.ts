/**
 * Waze police and road reports, read from a self-hosted harvester (PhotoScout's photoscout-waze container), never
 * from Waze directly. Optional and self-host only: off unless WAZE_HARVESTER_URL is set. The harvester pans its own
 * emulator to each box, so a new box is answered 202 (queued or scanning) first and polled until it is ready;
 * answers are kept a minute per box snapped to a 0.05 degree grid.
 */
import type { Fetch } from '../http.js';
import type { Box } from './line.js';

export const WAZE_TYPES = ['POLICE', 'HAZARD', 'CONSTRUCTION', 'CAMERA', 'ROAD_CLOSED', 'ACCIDENT', 'JAM'] as const;
export type WazeType = (typeof WAZE_TYPES)[number];
const GRID = 0.05;
const TTL_MS = 60_000;

export interface WazeAlert { id: string; type: WazeType; subType: string; lat: number; lng: number; street: string; city: string; reportTime: number | null; thumbsUp: number }

/** Pure: the harvester's alerts inside a box. */
export function parseWaze(raw: unknown, box: Box): WazeAlert[] {
  const list = (raw as { alerts?: unknown[] } | null)?.alerts;
  if (!Array.isArray(list)) return [];
  const out: WazeAlert[] = [];
  for (const v of list) {
    const a = v as Record<string, any>;
    if (typeof a?.id !== 'string' || !WAZE_TYPES.includes(a.type) || !Array.isArray(a.point)) continue;
    const [lng, lat] = a.point.map(Number);
    if (!Number.isFinite(lat) || !Number.isFinite(lng) || lng < box.minLng || lng > box.maxLng || lat < box.minLat || lat > box.maxLat) continue;
    out.push({
      id: a.id, type: a.type, subType: typeof a.subType === 'string' ? a.subType : '', lat, lng,
      street: typeof a.street === 'string' ? a.street : '', city: typeof a.city === 'string' ? a.city : '',
      reportTime: typeof a.reportTime === 'number' && a.reportTime > 0 ? a.reportTime : null,
      thumbsUp: typeof a.thumbsUp === 'number' && a.thumbsUp >= 0 ? a.thumbsUp : 0,
    });
  }
  return out;
}

export function snapBox(b: Box): Box {
  const s = (n: number) => Number(n.toFixed(6));
  return {
    minLng: s(Math.floor(b.minLng / GRID + 1e-9) * GRID), minLat: s(Math.floor(b.minLat / GRID + 1e-9) * GRID),
    maxLng: s(Math.ceil(b.maxLng / GRID - 1e-9) * GRID), maxLat: s(Math.ceil(b.maxLat / GRID - 1e-9) * GRID),
  };
}

export class WazeHarvester {
  private cache = new Map<string, { at: number; alerts: WazeAlert[] }>();

  constructor(
    private baseUrl: string,
    private key: string,
    private fetcher: Fetch,
    private now: () => number = Date.now,
    private sleep: (ms: number) => Promise<void> = (ms) => new Promise((r) => setTimeout(r, ms)),
  ) {
    this.baseUrl = baseUrl.replace(/\/+$/, '').replace(/\/alerts$/, '');
  }

  /** Alerts in a box, or null if the harvester did not answer by `deadline` (epoch ms). */
  async alerts(box: Box, deadline: number): Promise<WazeAlert[] | null> {
    const b = snapBox(box);
    const key = `${b.minLng},${b.minLat},${b.maxLng},${b.maxLat}`;
    const hit = this.cache.get(key);
    if (hit && this.now() - hit.at < TTL_MS) return hit.alerts;
    const url = `${this.baseUrl}/alerts?minLng=${b.minLng}&minLat=${b.minLat}&maxLng=${b.maxLng}&maxLat=${b.maxLat}&priority=map`;
    while (this.now() < deadline) {
      let res: Response;
      try {
        res = await this.fetcher(url, { headers: { accept: 'application/json', ...(this.key ? { 'x-api-key': this.key } : {}) }, signal: AbortSignal.timeout(Math.max(1000, Math.min(15_000, deadline - this.now()))) });
      } catch { return null; }
      if (!res.ok) return null;
      const data = (await res.json()) as { state?: string; retryAfterMs?: number; connected?: boolean; mock?: boolean };
      if (res.status === 202) {
        await this.sleep(Math.min(Math.max(1000, data.retryAfterMs ?? 3000), Math.max(0, deadline - this.now())));
        continue;
      }
      if (data.mock === true || data.connected === false) return null;
      const alerts = parseWaze(data, b);
      if (this.cache.size > 500) this.cache.delete(this.cache.keys().next().value!);
      this.cache.set(key, { at: this.now(), alerts });
      return alerts;
    }
    return null;
  }
}
