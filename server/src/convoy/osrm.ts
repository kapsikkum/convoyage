import type { Config } from '../config.js';
import type { Fetch } from '../http.js';

/**
 * Road-following route between [lng, lat] points as a polyline6, or null on any failure (same OSRM as GET /v1/route).
 * `via` are stops to pass on the way (a shared route's remaining stops).
 */
export async function osrmGeometry(config: Pick<Config, 'osrmUrl' | 'osrmProfiles'>, fetcher: Fetch, from: [number, number], to: [number, number], via: [number, number][] = []): Promise<string | null> {
  const points = [from, ...via, to].map((p) => `${p[0]},${p[1]}`).join(';');
  const url = `${config.osrmUrl}/route/v1/${encodeURIComponent(config.osrmProfiles.car)}/${points}?overview=full&geometries=polyline6&steps=false&alternatives=false`;
  try {
    const res = await fetcher(url, { signal: AbortSignal.timeout(8000), headers: { accept: 'application/json', 'user-agent': 'convoyage-server' } });
    const data = (await res.json().catch(() => null)) as { code?: string; routes?: { geometry?: string }[] } | null;
    const geometry = data?.code === 'Ok' ? data.routes?.[0]?.geometry : null;
    return typeof geometry === 'string' && geometry.length > 0 && geometry.length < 200_000 ? geometry : null;
  } catch {
    return null;
  }
}
