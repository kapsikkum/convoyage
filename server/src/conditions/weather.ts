/**
 * Weather along a route from Open-Meteo (free, no key): one request for up to 8 points, each read at the hour the
 * car should reach it. Forecasts are kept 20 minutes per point (rounded to ~1 km) and hour.
 */
import type { Fetch } from '../http.js';

const URL_BASE = 'https://api.open-meteo.com/v1/forecast';
const VARS = ['temperature_2m', 'precipitation_probability', 'precipitation', 'weather_code', 'wind_speed_10m', 'wind_gusts_10m', 'visibility', 'is_day'] as const;
const TTL_MS = 20 * 60_000;

export interface WeatherPoint {
  km: number; lat: number; lng: number;
  /** When the car should be here (ISO). */
  at: string;
  tempC: number | null; precipProbPct: number | null; precipMm: number | null; code: number | null;
  windKmh: number | null; gustKmh: number | null; visibilityM: number | null;
  /** e.g. "Light rain", "Fog", "Clear". */
  label: string;
  isDay: boolean;
}

/** WMO weather codes as words. */
export function weatherLabel(code: number | null): string {
  if (code == null) return 'Unknown';
  if (code === 0) return 'Clear';
  if (code <= 2) return 'Partly cloudy';
  if (code === 3) return 'Overcast';
  if (code === 45 || code === 48) return 'Fog';
  if (code >= 51 && code <= 57) return 'Drizzle';
  if (code === 61 || code === 80) return 'Light rain';
  if (code === 63 || code === 81) return 'Rain';
  if (code === 65 || code === 82) return 'Heavy rain';
  if (code === 66 || code === 67) return 'Freezing rain';
  if ((code >= 71 && code <= 77) || code === 85 || code === 86) return 'Snow';
  if (code >= 95) return 'Thunderstorm';
  return 'Cloudy';
}

/** How much a point's weather matters to a drive, 0 (nothing) to 3 (take care). */
export function weatherSeverity(w: Pick<WeatherPoint, 'code' | 'gustKmh' | 'visibilityM' | 'precipProbPct'>): number {
  const c = w.code ?? 0;
  if (c >= 95 || c === 65 || c === 82 || c === 66 || c === 67 || (c >= 71 && c <= 77) || (w.visibilityM ?? 99_999) < 200 || (w.gustKmh ?? 0) >= 80) return 3;
  if (c === 45 || c === 48 || c === 63 || c === 81 || (w.visibilityM ?? 99_999) < 1000 || (w.gustKmh ?? 0) >= 60) return 2;
  if ((c >= 51 && c <= 61) || c === 80 || (w.precipProbPct ?? 0) >= 50) return 1;
  return 0;
}

type Hourly = Partial<Record<(typeof VARS)[number], (number | null)[]>> & { time?: number[] };

export class RouteWeather {
  private cache = new Map<string, { at: number; hourly: Hourly }>();

  constructor(private fetcher: Fetch, private now: () => number = Date.now) {}

  async at(points: { lat: number; lng: number; km: number; at: Date }[]): Promise<WeatherPoint[]> {
    const pts = points.slice(0, 8);
    const keyOf = (p: { lat: number; lng: number }) => `${p.lat.toFixed(2)},${p.lng.toFixed(2)}`;
    const missing = [...new Set(pts.map(keyOf))].filter((k) => { const h = this.cache.get(k); return !h || this.now() - h.at >= TTL_MS; });
    if (missing.length) {
      const lats = missing.map((k) => k.split(',')[0]).join(','), lngs = missing.map((k) => k.split(',')[1]).join(',');
      const res = await this.fetcher(`${URL_BASE}?latitude=${lats}&longitude=${lngs}&hourly=${VARS.join(',')}&forecast_days=3&timeformat=unixtime&timezone=GMT`, {
        headers: { accept: 'application/json' }, signal: AbortSignal.timeout(15_000),
      });
      if (!res.ok) throw new Error(`Open-Meteo HTTP ${res.status}`);
      const body = (await res.json()) as { hourly?: Hourly } | { hourly?: Hourly }[];
      const list = Array.isArray(body) ? body : [body];
      missing.forEach((k, i) => { if (list[i]?.hourly) this.cache.set(k, { at: this.now(), hourly: list[i].hourly! }); });
      if (this.cache.size > 2000) this.cache.delete(this.cache.keys().next().value!);
    }
    return pts.map((p) => {
      const h = this.cache.get(keyOf(p))?.hourly ?? {};
      const t = p.at.getTime() / 1000;
      let i = 0;
      const times = h.time ?? [];
      for (let j = 0; j < times.length; j++) if (Math.abs(times[j] - t) < Math.abs(times[i] - t)) i = j;
      const v = (name: (typeof VARS)[number]) => { const x = h[name]?.[i]; return typeof x === 'number' ? x : null; };
      const code = v('weather_code');
      return {
        km: Math.round(p.km * 10) / 10, lat: p.lat, lng: p.lng, at: p.at.toISOString(),
        tempC: v('temperature_2m'), precipProbPct: v('precipitation_probability'), precipMm: v('precipitation'), code,
        windKmh: v('wind_speed_10m'), gustKmh: v('wind_gusts_10m'), visibilityM: v('visibility'),
        label: weatherLabel(code), isDay: v('is_day') !== 0,
      };
    });
  }
}
