/**
 * "How is this route right now?": traffic (TfNSW incidents and roadworks, Waze jams, crashes and closures), weather
 * at the time you would reach each part, road quality from OpenStreetMap, and police, cameras and hazards (Waze and
 * our own reports). Every source is optional and fails on its own: the answer says which ones it heard from.
 */
import type { Kysely } from 'kysely';
import type { DB } from '../db/types.js';
import type { Config } from '../config.js';
import type { Fetch } from '../http.js';
import { boxOf, inBox, RouteIndex, type Pt } from './line.js';
import { RoadQuality, type RoadSummary } from './roads.js';
import { TfnswFeed, type TrafficItem } from './tfnsw.js';
import { WazeHarvester, type WazeAlert } from './waze.js';
import { RouteWeather, weatherSeverity, type WeatherPoint } from './weather.js';
import { Stops, type RouteStop } from './stops.js';

export type TrafficLevel = 'clear' | 'light' | 'moderate' | 'heavy';
/** `pending`: still being fetched in the background; ask again shortly. */
export type SourceState = 'ok' | 'off' | 'error' | 'partial' | 'pending';

export interface ConditionItem {
  id: string;
  source: 'tfnsw' | 'waze' | 'convoyage';
  /** police, camera, hazard, crash, jam, closure, roadwork, event, flood, fire, alpine. */
  kind: string;
  title: string;
  detail: string;
  lat: number;
  lng: number;
  /** Kilometres from the start of the route. */
  km: number;
  /** When it was reported, if known (ISO). */
  reportedAt: string | null;
}

export interface RouteConditions {
  computedAt: string;
  traffic: { level: TrafficLevel; delayMin: number; summary: string; items: ConditionItem[] };
  police: ConditionItem[];
  hazards: ConditionItem[];
  weather: { summary: string; worst: number; points: WeatherPoint[] };
  roads: RoadSummary | null;
  /** Servos, food, toilets, rest areas and EV chargers within 300 m of the route, in driving order. */
  stops: RouteStop[];
  sources: { tfnsw: SourceState; waze: SourceState; weather: SourceState; roads: SourceState; stops: SourceState; fuel: SourceState };
}

const TFNSW_NEAR_M = 150;
const WAZE_NEAR_M = 80;
const REPORT_NEAR_M = 80;

/** Pure: how busy a route is from what is on it. */
export function trafficLevel(items: ConditionItem[], delayMin: number): TrafficLevel {
  let score = delayMin / 5;
  for (const i of items) score += i.kind === 'closure' ? 4 : i.kind === 'crash' ? 3 : i.kind === 'jam' ? 2 : i.kind === 'event' ? 1.5 : 1;
  return score >= 6 ? 'heavy' : score >= 3 ? 'moderate' : score > 0 ? 'light' : 'clear';
}

const plural = (n: number, one: string, many = `${one}s`) => `${n} ${n === 1 ? one : many}`;

export function trafficSummary(items: ConditionItem[], delayMin: number): string {
  if (!items.length) return 'No incidents or roadworks reported on the route';
  const count = (k: string) => items.filter((i) => i.kind === k).length;
  const parts = [
    count('closure') && plural(count('closure'), 'closure'), count('crash') && plural(count('crash'), 'crash', 'crashes'),
    count('jam') && plural(count('jam'), 'traffic jam'), count('roadwork') && `${count('roadwork')} roadworks`,
    count('event') && plural(count('event'), 'event'),
  ].filter(Boolean);
  return parts.join(', ') + (delayMin > 0 ? ` · about ${Math.round(delayMin)} min of delays` : '');
}

function wazeKind(a: WazeAlert): string {
  switch (a.type) {
    case 'POLICE': return /CAMERA/.test(a.subType) ? 'camera' : 'police';
    case 'CAMERA': return 'camera';
    case 'ACCIDENT': return 'crash';
    case 'JAM': return 'jam';
    case 'ROAD_CLOSED': return 'closure';
    case 'CONSTRUCTION': return 'roadwork';
    default: return 'hazard';
  }
}

const WAZE_TITLE: Record<string, string> = {
  police: 'Police', camera: 'Speed camera', crash: 'Crash', jam: 'Traffic jam', closure: 'Road closed', roadwork: 'Roadworks', hazard: 'Hazard',
};

function wazeTitle(a: WazeAlert, kind: string): string {
  const sub = a.subType.replace(/^(POLICE|HAZARD|JAM|ACCIDENT|ROAD_CLOSED)_?/, '').replace(/_/g, ' ').toLowerCase();
  if (kind === 'camera' && /mobile/.test(sub)) return 'Mobile speed camera';
  if (kind === 'hazard' && sub) return sub.replace(/^on road |^weather |^shoulder /, '').replace(/^./, (c) => c.toUpperCase());
  if (kind === 'jam' && sub) return `${sub.replace(/^./, (c) => c.toUpperCase())} traffic`;
  return WAZE_TITLE[kind];
}

const TFNSW_KIND: Record<TrafficItem['category'], string> = {
  incident: 'crash', roadwork: 'roadwork', flood: 'flood', fire: 'fire', alpine: 'alpine', majorevent: 'event',
};

/** Chunks of a route about `chunkM` long, as boxes for the Waze harvester. */
export function routeBoxes(index: RouteIndex, chunkM: number, padM: number) {
  const boxes = [];
  let start = 0;
  for (let i = 1; i < index.line.length; i++) {
    if (index.cum[i] - index.cum[start] >= chunkM || i === index.line.length - 1) {
      boxes.push(boxOf(index.line.slice(start, i + 1), padM));
      start = i;
    }
  }
  return boxes;
}

export class ConditionsService {
  readonly roads: RoadQuality;
  readonly stops: Stops;
  private tfnsw: TfnswFeed | null;
  private waze: WazeHarvester | null;
  private weather: RouteWeather;

  constructor(
    private db: Kysely<DB>, config: Config, fetcher: Fetch, private now: () => number = Date.now,
    /** Told when a source fails (the answer still goes out without it). */
    private onError: (source: string, err: unknown) => void = () => {},
  ) {
    this.roads = new RoadQuality(db, config.overpassUrls, fetcher, now);
    this.stops = new Stops(db, config.overpassUrls, fetcher, config.fuelCheck, now);
    this.tfnsw = config.tfnswApiKey ? new TfnswFeed(config.tfnswApiKey, fetcher, now) : null;
    this.waze = config.waze ? new WazeHarvester(config.waze.url, config.waze.key, fetcher, now) : null;
    this.weather = new RouteWeather(fetcher, now);
  }

  /** Everything at once. `durationS` spreads the weather over the drive; `withRoads` false skips road quality. */
  async forRoute(line: Pt[], opts: { durationS?: number | null; departAt?: Date; withRoads?: boolean; wazeBudgetMs?: number; roadsWaitMs?: number } = {}): Promise<RouteConditions> {
    const index = new RouteIndex(line);
    const km = (m: number) => Math.round(m / 100) / 10;
    const depart = opts.departAt ?? new Date(this.now());
    const box = boxOf(line, 500);
    const sources: RouteConditions['sources'] = { tfnsw: this.tfnsw ? 'ok' : 'off', waze: this.waze ? 'ok' : 'off', weather: 'ok', roads: opts.withRoads === false ? 'off' : 'ok', stops: 'ok', fuel: this.stops.fuel ? 'ok' : 'off' };

    const tfnswP = (async (): Promise<ConditionItem[]> => {
      if (!this.tfnsw) return [];
      try {
        const out: ConditionItem[] = [];
        for (const t of await this.tfnsw.all()) {
          const hits = [index.near([t.lng, t.lat], TFNSW_NEAR_M), ...t.lines.filter((l) => l.some((p) => inBox(box, p))).map((l) => index.nearLine(l, TFNSW_NEAR_M))]
            .filter((h): h is { d: number; m: number } => h != null);
          if (!hits.length) continue;
          const at = hits.reduce((a, b) => (a.d <= b.d ? a : b));
          // Planned roadworks and events often close a road overnight: they stay roadworks/events, with the closure noted.
          const kind = t.closure && t.category !== 'roadwork' && t.category !== 'majorevent' ? 'closure' : TFNSW_KIND[t.category];
          out.push({
            id: `tfnsw:${t.id}`, source: 'tfnsw', kind, title: t.title, lat: t.lat, lng: t.lng, km: km(at.m), reportedAt: null,
            detail: [t.closure && kind !== 'closure' ? 'Road closed at times' : '', t.road, t.delayMin ? `Expect ${t.delayMin} min delays` : '', t.advice].filter(Boolean).join(' · '),
          });
        }
        return out;
      } catch (err) { sources.tfnsw = 'error'; this.onError('tfnsw', err); return []; }
    })();

    const wazeP = (async (): Promise<ConditionItem[]> => {
      if (!this.waze) return [];
      const deadline = this.now() + (opts.wazeBudgetMs ?? 25_000);
      const boxes = routeBoxes(index, 15_000, 300).slice(0, 12);
      const seen = new Set<string>();
      const out: ConditionItem[] = [];
      let answered = 0;
      for (const b of boxes) {
        const alerts = await this.waze.alerts(b, deadline);
        if (!alerts) continue;
        answered++;
        for (const a of alerts) {
          if (seen.has(a.id)) continue;
          const n = index.near([a.lng, a.lat], WAZE_NEAR_M);
          if (!n) continue;
          seen.add(a.id);
          const kind = wazeKind(a);
          out.push({
            id: `waze:${a.id}`, source: 'waze', kind, title: wazeTitle(a, kind), lat: a.lat, lng: a.lng, km: km(n.m),
            detail: [a.street, a.city].filter(Boolean).join(', ') + (a.thumbsUp ? ` · ${a.thumbsUp} confirmed` : ''),
            reportedAt: a.reportTime ? new Date(a.reportTime).toISOString() : null,
          });
        }
      }
      sources.waze = answered === 0 ? 'error' : answered < boxes.length ? 'partial' : 'ok';
      return out;
    })();

    const reportsP = (async (): Promise<ConditionItem[]> => {
      const rows = await this.db.selectFrom('hazard_reports').selectAll()
        .where('removed_at', 'is', null).where('expires_at', '>', new Date(this.now()))
        .where('lat', '>=', box.minLat).where('lat', '<=', box.maxLat).where('lng', '>=', box.minLng).where('lng', '<=', box.maxLng)
        .limit(500).execute();
      const out: ConditionItem[] = [];
      for (const r of rows) {
        if (r.kind === 'fuel' || r.kind === 'rest') continue;
        const n = index.near([r.lng, r.lat], REPORT_NEAR_M);
        if (!n) continue;
        const kind = r.kind === 'works' ? 'roadwork' : r.kind === 'police' && /camera/.test(r.subkind ?? '') ? 'camera' : r.kind;
        out.push({
          id: `convoyage:${r.id}`, source: 'convoyage', kind, title: WAZE_TITLE[kind] ?? 'Hazard', lat: r.lat, lng: r.lng, km: km(n.m),
          detail: (r.subkind ?? '').replace(/_/g, ' '), reportedAt: new Date(r.created_at).toISOString(),
        });
      }
      return out;
    })();

    const weatherP = (async (): Promise<WeatherPoint[]> => {
      try {
        const total = index.total;
        const n = Math.min(8, Math.max(2, Math.ceil(total / 30_000) + 1));
        const speed = opts.durationS && opts.durationS > 0 ? total / opts.durationS : 22; // ~80 km/h when unknown
        const pts = Array.from({ length: n }, (_, i) => {
          const m = (total * i) / (n - 1);
          let j = 1;
          while (j < index.line.length - 1 && index.cum[j] < m) j++;
          const [lng, lat] = index.line[j] ?? index.line[0];
          return { lat, lng, km: m / 1000, at: new Date(depart.getTime() + (m / speed) * 1000) };
        });
        return await this.weather.at(pts);
      } catch (err) { sources.weather = 'error'; this.onError('weather', err); return []; }
    })();

    const roadsP = (async (): Promise<RoadSummary | null> => {
      if (opts.withRoads === false) return null;
      const job = this.roads.along(line);
      let timer: ReturnType<typeof setTimeout> | undefined;
      const late = new Promise<'late'>((r) => { timer = setTimeout(() => r('late'), opts.roadsWaitMs ?? 20_000); });
      try {
        const got = await Promise.race([job, late]);
        if (got === 'late') { sources.roads = 'pending'; job.catch((err) => this.onError('roads', err)); return null; }
        return got;
      } catch (err) { sources.roads = 'error'; this.onError('roads', err); return null; }
      finally { clearTimeout(timer); }
    })();

    const stopsP = (async (): Promise<RouteStop[]> => {
      const job = this.stops.along(line, index);
      let timer: ReturnType<typeof setTimeout> | undefined;
      const late = new Promise<'late'>((r) => { timer = setTimeout(() => r('late'), opts.roadsWaitMs ?? 20_000); });
      try {
        const got = await Promise.race([job, late]);
        if (got === 'late') { sources.stops = 'pending'; job.catch((err) => this.onError('stops', err)); return []; }
        sources.fuel = got.fuel;
        return got.stops;
      } catch (err) { sources.stops = 'error'; this.onError('stops', err); return []; }
      finally { clearTimeout(timer); }
    })();

    const [tfnsw, waze, reports, weather, roads, stops] = await Promise.all([tfnswP, wazeP, reportsP, weatherP, roadsP, stopsP]);
    const all = [...tfnsw, ...waze, ...reports].sort((a, b) => a.km - b.km);
    const trafficKinds = new Set(['closure', 'crash', 'jam', 'roadwork', 'event']);
    const traffic = all.filter((i) => trafficKinds.has(i.kind));
    const delayMin = tfnsw.reduce((s, i) => s + (Number(/Expect (\d+) min/.exec(i.detail)?.[1]) || 0), 0);
    const worst = weather.reduce((w, p) => Math.max(w, weatherSeverity(p)), 0);
    const worstPoint = weather.find((p) => weatherSeverity(p) === worst);
    const temps = weather.map((p) => p.tempC).filter((t): t is number => t != null);
    const weatherSummary = !weather.length ? 'No forecast'
      : worst > 0 && worstPoint ? `${worstPoint.label} around km ${Math.round(worstPoint.km)}` + (temps.length ? ` · ${Math.round(Math.min(...temps))}–${Math.round(Math.max(...temps))}°` : '')
        : `${weather[0].label}` + (temps.length ? ` · ${Math.round(Math.min(...temps))}–${Math.round(Math.max(...temps))}°` : '');
    return {
      computedAt: new Date(this.now()).toISOString(),
      traffic: { level: trafficLevel(traffic, delayMin), delayMin, summary: trafficSummary(traffic, delayMin), items: traffic },
      police: all.filter((i) => i.kind === 'police' || i.kind === 'camera'),
      hazards: all.filter((i) => !trafficKinds.has(i.kind) && i.kind !== 'police' && i.kind !== 'camera'),
      weather: { summary: weatherSummary, worst, points: weather },
      roads,
      stops,
      sources,
    };
  }
}
