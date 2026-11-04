/**
 * Stops along a route and on the map: servos, fast food and cafes, toilets, rest areas and EV chargers, from
 * OpenStreetMap (Overpass), kept in the database for a week. Fuel prices come live from NSW FuelCheck when a key
 * is set, matched to servos by location; FuelCheck stations the map does not have are added as servos.
 */
import { createHash, randomUUID } from 'node:crypto';
import type { Kysely } from 'kysely';
import type { DB } from '../db/types.js';
import type { Fetch } from '../http.js';
import { USER_AGENT } from '../convoy/overpass.js';
import { boxOf, cumulative, inBox, RouteIndex, segM, simplify, type Box, type Pt } from './line.js';

export const STOPS_TTL_MS = 7 * 86_400_000;
export const STOP_KINDS = ['fuel', 'food', 'cafe', 'toilets', 'rest', 'ev'] as const;
export type StopKind = (typeof STOP_KINDS)[number];
/** FuelCheck's codes for what we show. DL is diesel, PDL premium diesel. */
export const FUEL_TYPES = ['U91', 'E10', 'P95', 'P98', 'DL', 'PDL', 'LPG'] as const;

export interface Stop {
  id: string;
  kind: StopKind;
  name: string;
  brand: string;
  lat: number;
  lng: number;
  /** Open around the clock, when the map says so. */
  open24: boolean;
  /** Cents per litre by FuelCheck fuel code (servos with prices only). */
  prices: Record<string, number>;
  pricesAt: string | null;
}
export type RouteStop = Stop & { km: number; offM: number };

interface Element { type: string; id: number; lat?: number; lon?: number; center?: { lat: number; lon: number }; tags?: Record<string, string> }

/** Pure: what kind of stop an OpenStreetMap feature is, or null. */
export function stopKind(t: Record<string, string>): StopKind | null {
  if (t.amenity === 'fuel') return 'fuel';
  if (t.amenity === 'charging_station') return 'ev';
  if (t.amenity === 'fast_food' || t.amenity === 'restaurant' || t.amenity === 'food_court') return 'food';
  if (t.amenity === 'cafe') return 'cafe';
  if (t.amenity === 'toilets') return 'toilets';
  if (t.highway === 'rest_area' || t.highway === 'services') return 'rest';
  return null;
}

export function stopsFrom(elements: Element[]): Stop[] {
  const out: Stop[] = [];
  for (const e of elements) {
    const t = e.tags ?? {};
    const kind = stopKind(t);
    const lat = e.lat ?? e.center?.lat, lng = e.lon ?? e.center?.lon;
    if (!kind || typeof lat !== 'number' || typeof lng !== 'number') continue;
    out.push({
      id: `osm:${e.type[0]}${e.id}`, kind, name: (t.name ?? t.brand ?? '').trim(), brand: (t.brand ?? t.operator ?? '').trim(),
      lat, lng, open24: t.opening_hours === '24/7', prices: {}, pricesAt: null,
    });
  }
  return out;
}

const QUERY_BODY = (b: Box) => {
  const f = (n: number) => n.toFixed(5);
  const bb = `${f(b.minLat)},${f(b.minLng)},${f(b.maxLat)},${f(b.maxLng)}`;
  return `nwr["amenity"~"^(fuel|charging_station|fast_food|restaurant|food_court|cafe|toilets)$"](${bb});nwr["highway"~"^(rest_area|services)$"](${bb});`;
};

// ---- FuelCheck ---------------------------------------------------------------------------------------------------

interface FuelStation { code: string; name: string; brand: string; lat: number; lng: number }
interface FuelPrice { code: string; fuel: string; price: number; at: string }

/** Pure: FuelCheck's all-prices answer into stations and prices. */
export function parseFuelCheck(raw: unknown): { stations: FuelStation[]; prices: FuelPrice[] } {
  const r = (raw ?? {}) as { stations?: any[]; prices?: any[] };
  const stations: FuelStation[] = [];
  for (const s of r.stations ?? []) {
    const lat = Number(s?.location?.latitude), lng = Number(s?.location?.longitude);
    if (!Number.isFinite(lat) || !Number.isFinite(lng) || s?.code == null) continue;
    stations.push({ code: String(s.code), name: String(s.name ?? '').trim(), brand: String(s.brand ?? '').trim(), lat, lng });
  }
  const prices: FuelPrice[] = [];
  for (const p of r.prices ?? []) {
    const price = Number(p?.price);
    if (p?.stationcode == null || typeof p?.fueltype !== 'string' || !Number.isFinite(price) || price <= 0) continue;
    prices.push({ code: String(p.stationcode), fuel: p.fueltype, price, at: String(p.lastupdated ?? '') });
  }
  return { stations, prices };
}

/** FuelCheck's request timestamp: dd/MM/yyyy hh:mm:ss AM, UTC. */
function fcTimestamp(d: Date): string {
  const p = (n: number) => String(n).padStart(2, '0');
  const h = d.getUTCHours();
  return `${p(d.getUTCDate())}/${p(d.getUTCMonth() + 1)}/${d.getUTCFullYear()} ${p(h % 12 || 12)}:${p(d.getUTCMinutes())}:${p(d.getUTCSeconds())} ${h < 12 ? 'AM' : 'PM'}`;
}

/**
 * NSW FuelCheck (api.nsw.gov.au): every station and price in one call, kept 30 minutes (the free plan allows a few
 * thousand calls a month) and served stale if it fails.
 */
export class FuelCheck {
  private static BASE = 'https://api.onegov.nsw.gov.au';
  private token: { value: string; until: number } | null = null;
  private cache: { at: number; stations: FuelStation[]; byCode: Map<string, Record<string, { price: number; at: string }>> } | null = null;
  private inflight: Promise<NonNullable<FuelCheck['cache']>> | null = null;

  constructor(private key: string, private secret: string, private fetcher: Fetch, private now: () => number = Date.now) {}

  private async accessToken(): Promise<string> {
    if (this.token && this.now() < this.token.until) return this.token.value;
    const res = await this.fetcher(`${FuelCheck.BASE}/oauth/client_credential/accesstoken?grant_type=client_credentials`, {
      headers: { authorization: `Basic ${Buffer.from(`${this.key}:${this.secret}`).toString('base64')}`, accept: 'application/json' },
      signal: AbortSignal.timeout(15_000),
    });
    if (!res.ok) throw new Error(`FuelCheck token HTTP ${res.status}`);
    const body = (await res.json()) as { access_token?: string; expires_in?: string | number };
    if (!body.access_token) throw new Error('FuelCheck gave no token');
    this.token = { value: body.access_token, until: this.now() + Math.max(60, Number(body.expires_in ?? 3600) - 120) * 1000 };
    return body.access_token;
  }

  async all(): Promise<NonNullable<FuelCheck['cache']>> {
    if (this.cache && this.now() - this.cache.at < 30 * 60_000) return this.cache;
    this.inflight ??= this.load().finally(() => { this.inflight = null; });
    return this.inflight;
  }

  private async load(): Promise<NonNullable<FuelCheck['cache']>> {
    try {
      const token = await this.accessToken();
      const res = await this.fetcher(`${FuelCheck.BASE}/FuelPriceCheck/v2/fuel/prices`, {
        headers: {
          apikey: this.key, authorization: `Bearer ${token}`, 'content-type': 'application/json; charset=utf-8',
          transactionid: randomUUID(), requesttimestamp: fcTimestamp(new Date(this.now())), accept: 'application/json',
        },
        signal: AbortSignal.timeout(30_000),
      });
      if (!res.ok) throw new Error(`FuelCheck HTTP ${res.status}`);
      const { stations, prices } = parseFuelCheck(await res.json());
      const byCode = new Map<string, Record<string, { price: number; at: string }>>();
      for (const p of prices) {
        const m = byCode.get(p.code) ?? {};
        m[p.fuel] = { price: p.price, at: p.at };
        byCode.set(p.code, m);
      }
      this.cache = { at: this.now(), stations, byCode };
      return this.cache;
    } catch (err) {
      if (this.cache) return this.cache;
      throw err;
    }
  }
}

/**
 * Pure: put FuelCheck prices on the servos near them (within 150 m) and add the FuelCheck stations that matched
 * nothing as servos of their own.
 */
export function withPrices(stops: Stop[], stations: FuelStation[], byCode: Map<string, Record<string, { price: number; at: string }>>, box: Box | null): Stop[] {
  const out = stops.map((s) => ({ ...s, prices: { ...s.prices } }));
  const fuel = out.filter((s) => s.kind === 'fuel');
  for (const st of stations) {
    if (box && !inBox(box, [st.lng, st.lat])) continue;
    const p = byCode.get(st.code);
    if (!p) continue;
    const prices = Object.fromEntries(Object.entries(p).map(([k, v]) => [k, v.price]));
    const at = Object.values(p).map((v) => v.at).sort().at(-1) ?? null;
    let best: Stop | null = null, bestD = 150;
    for (const f of fuel) { const d = segM([f.lng, f.lat], [st.lng, st.lat]); if (d < bestD) { bestD = d; best = f; } }
    if (best) { best.prices = prices; best.pricesAt = at; if (!best.name) best.name = st.name; if (!best.brand) best.brand = st.brand; }
    else out.push({ id: `fuelcheck:${st.code}`, kind: 'fuel', name: st.name, brand: st.brand, lat: st.lat, lng: st.lng, open24: false, prices, pricesAt: at });
  }
  return out;
}

// ---- the finder ----------------------------------------------------------------------------------------------------

export class Stops {
  private inflight = new Map<string, Promise<Stop[]>>();
  readonly fuel: FuelCheck | null;

  constructor(
    private db: Kysely<DB>,
    private urls: string[],
    private fetcher: Fetch,
    fuel: { key: string; secret: string } | null,
    private now: () => number = Date.now,
  ) {
    this.fuel = fuel ? new FuelCheck(fuel.key, fuel.secret, fetcher, now) : null;
  }

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
        if (body.remark && /error|timed out|out of memory/i.test(body.remark)) { lastErr = new Error(`Overpass: ${body.remark}`); continue; }
        return body.elements ?? [];
      } catch (err) { lastErr = err; }
    }
    throw lastErr;
  }

  private async cached(key: string, load: () => Promise<Stop[]>): Promise<Stop[]> {
    const hit = await this.db.selectFrom('geo_cache').select(['value', 'fetched_at']).where('key', '=', key).executeTakeFirst();
    if (hit && this.now() - new Date(hit.fetched_at).getTime() < STOPS_TTL_MS) return hit.value as Stop[];
    let job = this.inflight.get(key);
    if (!job) {
      job = load().then(async (stops) => {
        const at = new Date(this.now()).toISOString();
        await this.db.insertInto('geo_cache').values({ key, fetched_at: at, value: JSON.stringify(stops) })
          .onConflict((oc) => oc.column('key').doUpdateSet({ fetched_at: at, value: JSON.stringify(stops) })).execute();
        return stops;
      }).finally(() => this.inflight.delete(key));
      job.catch(() => {});
      this.inflight.set(key, job);
    }
    return job;
  }

  /** Map features near a route (300 m either side), from the database for a week. Prices not included. */
  async mapStopsAlong(line: Pt[]): Promise<Stop[]> {
    const thin = simplify(line, 30);
    const key = 'stops:' + createHash('sha1').update(thin.map(([a, b]) => `${a.toFixed(4)},${b.toFixed(4)}`).join(';')).digest('hex');
    return this.cached(key, async () => {
      const cum = cumulative(thin);
      const boxes: Box[] = [];
      let from = 0;
      for (let i = 1; i < thin.length; i++) if (cum[i] - cum[from] >= 3000 || i === thin.length - 1) { boxes.push(boxOf(thin.slice(from, i + 1), 300)); from = i; }
      const seen = new Set<string>();
      const out: Stop[] = [];
      for (let i = 0; i < boxes.length; i += 30) {
        for (const s of stopsFrom(await this.overpass(`[out:json][timeout:90];(${boxes.slice(i, i + 30).map(QUERY_BODY).join('')});out center tags;`))) {
          if (!seen.has(s.id)) { seen.add(s.id); out.push(s); }
        }
      }
      return out;
    });
  }

  /** Stops on a route, with prices when FuelCheck is set up, in driving order. */
  async along(line: Pt[], index = new RouteIndex(line)): Promise<{ stops: RouteStop[]; fuel: 'ok' | 'off' | 'error' }> {
    let stops = await this.mapStopsAlong(line);
    let fuel: 'ok' | 'off' | 'error' = this.fuel ? 'ok' : 'off';
    if (this.fuel) {
      try { const f = await this.fuel.all(); stops = withPrices(stops, f.stations, f.byCode, boxOf(line, 300)); } catch { fuel = 'error'; }
    }
    const out: RouteStop[] = [];
    for (const s of stops) {
      const n = index.near([s.lng, s.lat], 300);
      if (n) out.push({ ...s, km: Math.round(n.m / 100) / 10, offM: Math.round(n.d) });
    }
    return { stops: out.sort((a, b) => a.km - b.km), fuel };
  }

  /** Stops in a map view (up to 0.15 degrees a side), per 0.05 degree cell. */
  async area(b: Box): Promise<Stop[]> {
    const G = 0.05;
    const r = (n: number) => Math.round(n * 1e4) / 1e4;
    let stops: Stop[] = [];
    for (let s = Math.floor(b.minLat / G); s * G < b.maxLat; s++)
      for (let w = Math.floor(b.minLng / G); w * G < b.maxLng; w++) {
        const cell: Box = { minLat: r(s * G), minLng: r(w * G), maxLat: r((s + 1) * G), maxLng: r((w + 1) * G) };
        stops.push(...await this.cached(`stopcell:${cell.minLat},${cell.minLng}`, async () => stopsFrom(await this.overpass(`[out:json][timeout:90];(${QUERY_BODY(cell)});out center tags;`))));
      }
    if (this.fuel) {
      try { const f = await this.fuel.all(); stops = withPrices(stops, f.stations, f.byCode, b); } catch { /* prices missing this time */ }
    }
    return stops.filter((s) => inBox(b, [s.lng, s.lat]));
  }
}
