import type { Config } from '../config.js';
import type { Fetch } from '../http.js';

/** Road-following route between two [lng, lat] points as a polyline6, or null on any failure (same OSRM as GET /v1/route). */
export async function osrmGeometry(config: Pick<Config, 'osrmUrl' | 'osrmProfiles'>, fetcher: Fetch, from: [number, number], to: [number, number]): Promise<string | null> {
  const url = `${config.osrmUrl}/route/v1/${encodeURIComponent(config.osrmProfiles.car)}/${from[0]},${from[1]};${to[0]},${to[1]}?overview=full&geometries=polyline6&steps=false&alternatives=false`;
  try {
    const res = await fetcher(url, { signal: AbortSignal.timeout(8000), headers: { accept: 'application/json', 'user-agent': 'convoyage-server' } });
    const data = (await res.json().catch(() => null)) as { code?: string; routes?: { geometry?: string }[] } | null;
    const geometry = data?.code === 'Ok' ? data.routes?.[0]?.geometry : null;
    return typeof geometry === 'string' && geometry.length > 0 && geometry.length < 200_000 ? geometry : null;
  } catch {
    return null;
  }
}
