import test, { after, before } from 'node:test';
import assert from 'node:assert/strict';
import { testServer, type Session, type TestServer } from './apiHelpers.js';
import { encodePolyline } from '../src/api/polyline.js';
import { gradeRoad, parseMaxspeed, summariseRoads, waysFrom } from '../src/conditions/roads.js';
import { parseTfnsw } from '../src/conditions/tfnsw.js';
import { parseWaze, snapBox, WazeHarvester } from '../src/conditions/waze.js';
import { trafficLevel, trafficSummary, type ConditionItem } from '../src/conditions/conditions.js';
import { weatherLabel, weatherSeverity } from '../src/conditions/weather.js';
import { RouteIndex, type Pt } from '../src/conditions/line.js';

// Pit Straight up to Hell Corner and along Mountain Straight, roughly ([lng, lat]).
const lap: Pt[] = [[149.5585, -33.4393], [149.5605, -33.4440], [149.5630, -33.4470], [149.5700, -33.4460], [149.5790, -33.4440]];
const geometry = encodePolyline(lap.map(([lng, lat]): Pt => [lat, lng]), 6);

const item = (kind: string): ConditionItem => ({ id: kind, source: 'waze', kind, title: kind, detail: '', lat: 0, lng: 0, km: 1, reportedAt: null });

test('road grades: surface and smoothness, assumed paved highways, tracks', () => {
  assert.equal(gradeRoad({ surface: 'asphalt', smoothness: 'excellent' }), 'good');
  assert.equal(gradeRoad({ surface: 'asphalt' }), 'fair');
  assert.equal(gradeRoad({ surface: 'gravel' }), 'poor');
  assert.equal(gradeRoad({ surface: 'asphalt', smoothness: 'bad' }), 'poor');
  assert.equal(gradeRoad({ highway: 'primary' }), 'fair');
  assert.equal(gradeRoad({ highway: 'track' }), 'poor');
  assert.equal(gradeRoad({ highway: 'residential' }), 'unknown');
});

test('road summary: kilometres per grade, runs where the grade changes, worst stretches', () => {
  const ways = waysFrom([
    { type: 'way', id: 1, tags: { name: 'Mountain Straight', surface: 'asphalt', smoothness: 'good' }, geometry: lap.slice(0, 3).map(([lon, lat]) => ({ lat, lon })) },
    { type: 'way', id: 2, tags: { name: 'Gravel Rd', surface: 'gravel' }, geometry: lap.slice(2).map(([lon, lat]) => ({ lat, lon })) },
  ]);
  const s = summariseRoads(lap, ways, '2026-11-04T00:00:00.000Z');
  assert.ok(s.km.good > 0.5 && s.km.poor > 0.8, JSON.stringify(s.km));
  assert.ok(s.unpavedKm > 0.8);
  assert.deepEqual(s.runs.map((r) => r.grade), ['good', 'poor']);
  assert.equal(s.worst[0].name, 'Gravel Rd');
  assert.equal(summariseRoads(lap, [], 'x').km.unknown > 0, true, 'no ways: all unknown');
});

test('TfNSW: titles without the category prefix, closures, delays, drawn extents', () => {
  const items = parseTfnsw('incident', {
    features: [
      { id: 7, geometry: { type: 'Point', coordinates: [149.56, -33.444] }, properties: {
        displayName: 'CRASH Great Western Hwy', mainCategory: 'CRASH', expectedDelay: 15, roads: [{ mainStreet: 'Great Western Highway', suburb: 'Kelso' }],
        periods: [{ closureType: 'ROAD_CLOSURE', roadextent: 'Closed' }], encodedPolylines: [{ coords: encodePolyline([[-33.444, 149.56], [-33.445, 149.561]], 5) }],
        adviceA: 'Exercise caution', adviceB: 'null' } },
      { id: 8, geometry: { type: 'Point', coordinates: [149.6, -33.4] }, properties: { ended: true } },
    ],
  });
  assert.equal(items.length, 1);
  assert.equal(items[0].title, 'Great Western Hwy');
  assert.equal(items[0].road, 'Great Western Highway, Kelso');
  assert.equal(items[0].closure, true);
  assert.equal(items[0].delayMin, 15);
  assert.equal(items[0].advice, 'Exercise caution');
  assert.equal(items[0].lines[0].length, 2);
});

test('Waze: alerts outside the box or of unknown types are dropped; boxes snap to the grid', async () => {
  const box = { minLng: 149.5, minLat: -33.5, maxLng: 149.6, maxLat: -33.4 };
  const alerts = parseWaze({ alerts: [
    { id: 'a', type: 'POLICE', subType: 'POLICE_MOBILE_CAMERA', point: [149.55, -33.45], street: 'Howick St', reportTime: 1 },
    { id: 'b', type: 'CHITCHAT', point: [149.55, -33.45] },
    { id: 'c', type: 'JAM', point: [150.5, -33.45] },
  ] }, box);
  assert.deepEqual(alerts.map((a) => a.id), ['a']);
  assert.deepEqual(snapBox({ minLng: 149.51, minLat: -33.46, maxLng: 149.57, maxLat: -33.41 }), { minLng: 149.5, minLat: -33.5, maxLng: 149.6, maxLat: -33.4 });

  // Queued first (202), then the scan.
  let calls = 0;
  const harvester = new WazeHarvester('http://harvester:8088/', 'k', async (url, init) => {
    calls++;
    assert.equal((init?.headers as Record<string, string>)['x-api-key'], 'k');
    assert.match(url, /^http:\/\/harvester:8088\/alerts\?minLng=/);
    return calls === 1
      ? new Response(JSON.stringify({ state: 'queued', retryAfterMs: 10 }), { status: 202 })
      : Response.json({ alerts: [{ id: 'a', type: 'POLICE', point: [149.55, -33.45] }], coverage: 'viewport', observedAt: Date.now() });
  }, Date.now, async () => {});
  assert.equal((await harvester.alerts(box, Date.now() + 5000))?.length, 1);
  assert.equal((await harvester.alerts(box, Date.now() + 5000))?.length, 1);
  assert.equal(calls, 2, 'the second ask is served from the minute cache');
});

test('speed limits from maxspeed tags', () => {
  assert.equal(parseMaxspeed('60'), 60);
  assert.equal(parseMaxspeed('30 mph'), 48);
  assert.equal(parseMaxspeed('80 km/h'), 80);
  assert.equal(parseMaxspeed('AU:urban'), 50);
  assert.equal(parseMaxspeed('AU:rural'), 100);
  assert.equal(parseMaxspeed('signals'), null);
  assert.equal(parseMaxspeed('none'), null);
  assert.equal(parseMaxspeed(undefined), null);
  assert.equal(parseMaxspeed('900'), null);
});

test('traffic level and summary', () => {
  assert.equal(trafficLevel([], 0), 'clear');
  assert.equal(trafficLevel([item('roadwork')], 0), 'light');
  assert.equal(trafficLevel([item('jam'), item('roadwork')], 0), 'moderate');
  assert.equal(trafficLevel([item('closure'), item('crash')], 0), 'heavy');
  assert.equal(trafficSummary([item('crash'), item('crash'), item('roadwork')], 12), '2 crashes, 1 roadworks · about 12 min of delays');
});

test('weather words and severity', () => {
  assert.equal(weatherLabel(0), 'Clear');
  assert.equal(weatherLabel(45), 'Fog');
  assert.equal(weatherSeverity({ code: 95, gustKmh: 10, visibilityM: 20000, precipProbPct: 80 }), 3);
  assert.equal(weatherSeverity({ code: 0, gustKmh: 10, visibilityM: 20000, precipProbPct: 0 }), 0);
});

test('route index: distance from the route and how far along', () => {
  const idx = new RouteIndex(lap);
  const n = idx.near([149.5605, -33.4440], 50);
  assert.ok(n && n.d < 1 && n.m > 500 && n.m < 700, JSON.stringify(n));
  assert.equal(idx.near([149.70, -33.30], 150), null);
});

// ---- the endpoints, with every outside service faked ----

let t: TestServer;
let a: Session;
let b: Session;
const asked: string[] = [];

async function fakeFetch(url: string, init?: RequestInit): Promise<Response> {
  asked.push(url);
  if (url.startsWith('https://api.transport.nsw.gov.au/')) {
    if (!url.includes('/roadwork/')) return Response.json({ type: 'FeatureCollection', features: [] });
    return Response.json({ features: [{ id: 1, geometry: { type: 'Point', coordinates: [149.5630, -33.4471] }, properties: { displayName: 'SCHEDULED ROADWORK Conrod resurfacing', mainCategory: 'SCHEDULED ROADWORK', expectedDelay: 5, roads: [{ mainStreet: 'Conrod Straight', suburb: 'Mount Panorama' }] } }] });
  }
  if (url.startsWith('http://harvester:8088/alerts')) {
    return Response.json({ alerts: [
      { id: 'p1', type: 'POLICE', subType: 'POLICE_MOBILE_CAMERA', point: [149.5700, -33.4460], street: 'Mountain Straight', reportTime: Date.now() - 60_000 },
      { id: 'j1', type: 'JAM', subType: 'JAM_HEAVY_TRAFFIC', point: [149.5605, -33.4441] },
      { id: 'far', type: 'POLICE', point: [149.5900, -33.4100] },
    ], coverage: 'viewport', observedAt: Date.now() });
  }
  if (url.startsWith('https://api.open-meteo.com/')) {
    const n = new URL(url).searchParams.get('latitude')!.split(',').length;
    const now = Math.floor(Date.now() / 3600_000) * 3600;
    const one = { hourly: { time: [now, now + 3600], temperature_2m: [14, 15], precipitation_probability: [70, 80], precipitation: [1, 2], weather_code: [63, 63], wind_speed_10m: [10, 12], wind_gusts_10m: [20, 22], visibility: [9000, 9000], is_day: [1, 1] } };
    return Response.json(n === 1 ? one : Array.from({ length: n }, () => one));
  }
  if (url.includes('overpass') && decodeURIComponent(String(init?.body ?? '')).includes('amenity')) {
    return Response.json({ elements: [
      { type: 'node', id: 11, lat: -33.4441, lon: 149.5606, tags: { amenity: 'fuel', brand: 'Shell', name: 'Shell Panorama', opening_hours: '24/7' } },
      { type: 'way', id: 12, center: { lat: -33.4466, lon: 149.5700 }, tags: { amenity: 'toilets' } },
      { type: 'node', id: 13, lat: -33.4100, lon: 149.5900, tags: { amenity: 'cafe', name: 'Too far' } },
    ] });
  }
  if (url.startsWith('https://api.onegov.nsw.gov.au/oauth')) return Response.json({ access_token: 'tok', expires_in: '43199' });
  if (url.startsWith('https://api.onegov.nsw.gov.au/FuelPriceCheck')) {
    return Response.json({
      stations: [{ code: 1, name: 'Shell Panorama', brand: 'Shell', location: { latitude: -33.4442, longitude: 149.5607 } }, { code: 2, name: 'Metro Kelso', brand: 'Metro', location: { latitude: -33.4469, longitude: 149.5780 } }],
      prices: [{ stationcode: 1, fueltype: 'U91', price: 199.9, lastupdated: '04/11/2026 10:00:00' }, { stationcode: 2, fueltype: 'E10', price: 189.5, lastupdated: '04/11/2026 09:00:00' }],
    });
  }
  if (url.includes('overpass')) {
    return Response.json({ elements: [{ type: 'way', id: 1, tags: { name: 'Mountain Straight', surface: 'asphalt', smoothness: 'good', highway: 'tertiary', maxspeed: '60' }, geometry: lap.map(([lon, lat]) => ({ lat, lon })) }] });
  }
  return new Response('not faked', { status: 500 });
}

before(async () => {
  t = await testServer({ fetch: fakeFetch, env: { TFNSW_API_KEY: 'tf-key', WAZE_HARVESTER_URL: 'http://harvester:8088', WAZE_HARVESTER_KEY: 'wk', FUELCHECK_API_KEY: 'fk', FUELCHECK_API_SECRET: 'fs', OVERPASS_URL: 'https://overpass.test/api/interpreter' } });
  a = await t.signup('ca');
  b = await t.signup('cb');
});
after(() => t.close());

test('conditions for a line: traffic, police, weather and road quality, nearest first', async () => {
  const r = await t.req('POST', '/v1/conditions', { token: a.token, body: { geometry, durationS: 300 } });
  assert.equal(r.status, 200, JSON.stringify(r.body));
  assert.deepEqual(r.body.sources, { tfnsw: 'ok', waze: 'ok', weather: 'ok', roads: 'ok', stops: 'ok', fuel: 'ok' });
  assert.deepEqual(r.body.stops.map((x: any) => [x.kind, x.name]), [['fuel', 'Shell Panorama'], ['toilets', ''], ['fuel', 'Metro Kelso']]);
  assert.equal(r.body.stops[0].prices.U91, 199.9, 'FuelCheck price matched to the mapped servo');
  assert.equal(r.body.stops[0].open24, true);
  assert.equal(r.body.stops[2].prices.E10, 189.5, 'a FuelCheck station the map lacks is added');
  assert.deepEqual(r.body.traffic.items.map((i: any) => i.kind), ['jam', 'roadwork']);
  assert.equal(r.body.traffic.level, 'moderate');
  assert.equal(r.body.traffic.delayMin, 5);
  assert.equal(r.body.police.length, 1, 'the far police report is not on the route');
  assert.equal(r.body.police[0].title, 'Mobile speed camera');
  assert.equal(r.body.weather.points[0].label, 'Rain');
  assert.equal(r.body.weather.worst, 2);
  assert.ok(r.body.roads.km.good > 1);
  const tfCall = asked.find((u) => u.includes('transport.nsw'))!;
  assert.ok(tfCall, 'asked TfNSW');
});

test('saved route conditions: computed once, then the saved snapshot; refresh recomputes; others need to see the route', async () => {
  const made = await t.req('POST', '/v1/routes', { token: a.token, body: { name: 'Lap', waypoints: lap, geometry, durationS: 300, visibility: 'private' } });
  assert.equal(made.status, 201);
  const none = await t.req('GET', `/v1/routes/${made.body.id}/conditions?cachedOnly=true`, { token: a.token });
  assert.equal(none.status, 404);
  const first = await t.req('GET', `/v1/routes/${made.body.id}/conditions`, { token: a.token });
  assert.equal(first.status, 200);
  assert.equal(first.body.cached, false);
  const overpassCalls = asked.filter((u) => u.includes('overpass')).length;
  const second = await t.req('GET', `/v1/routes/${made.body.id}/conditions`, { token: a.token });
  assert.equal(second.body.cached, true);
  assert.equal(second.body.computedAt, first.body.computedAt);
  const fresh = await t.req('GET', `/v1/routes/${made.body.id}/conditions?refresh=true`, { token: a.token });
  assert.equal(fresh.body.cached, false);
  assert.equal(asked.filter((u) => u.includes('overpass')).length, overpassCalls, 'road quality for the same line comes from the database');
  const theirs = await t.req('GET', `/v1/routes/${made.body.id}/conditions`, { token: b.token });
  assert.equal(theirs.status, 404);
});

test('road quality layer: GeoJSON per cell, cached; big boxes refused', async () => {
  const r = await t.req('GET', '/v1/roads?bbox=-33.45,149.55,-33.44,149.58', { token: a.token });
  assert.equal(r.status, 200, JSON.stringify(r.body));
  assert.equal(r.body.type, 'FeatureCollection');
  assert.equal(r.body.features[0].properties.grade, 'good');
  assert.equal(r.body.features[0].properties.maxspeed, 60, 'the signed limit rides along');
  const before = asked.length;
  await t.req('GET', '/v1/roads?bbox=-33.45,149.55,-33.44,149.58', { token: a.token });
  assert.equal(asked.length, before, 'second ask is from the database');
  const big = await t.req('GET', '/v1/roads?bbox=-34,149,-33,150', { token: a.token });
  assert.equal(big.status, 400);
});

test('police and traffic on the map: Waze, TfNSW and reports in the box only; big boxes refused', async () => {
  const rep = await t.req('POST', '/v1/hazards', { token: b.token, body: { kind: 'police', lat: -33.4450, lng: 149.5650 } });
  assert.ok(rep.status === 201 || rep.status === 200, JSON.stringify(rep.body));
  const r = await t.req('GET', '/v1/conditions/area?bbox=-33.46,149.55,-33.43,149.58', { token: a.token });
  assert.equal(r.status, 200, JSON.stringify(r.body));
  assert.deepEqual(r.body.sources, { tfnsw: 'ok', waze: 'ok' });
  const ids = r.body.items.map((i: any) => i.id);
  assert.ok(ids.includes('waze:p1') && ids.includes('waze:j1'), 'Waze police and jam');
  assert.ok(!ids.includes('waze:far'), 'outside the box');
  assert.ok(ids.includes('tfnsw:roadwork:1'), 'TfNSW roadworks');
  assert.ok(r.body.items.some((i: any) => i.source === 'convoyage' && i.kind === 'police'), 'our own report');
  assert.equal(r.body.items.find((i: any) => i.id === 'waze:p1').kind, 'camera');
  const big = await t.req('GET', '/v1/conditions/area?bbox=-34,149,-33,150', { token: a.token });
  assert.equal(big.status, 400);
  const bad = await t.req('GET', '/v1/conditions/area?bbox=nope', { token: a.token });
  assert.equal(bad.status, 400);
});

test('sources that are not configured say off', async () => {
  const t2 = await testServer({ fetch: fakeFetch, env: { OVERPASS_URL: 'https://overpass.test/api/interpreter' } });
  try {
    const s = await t2.signup('off');
    const r = await t2.req('POST', '/v1/conditions', { token: s.token, body: { geometry } });
    assert.equal(r.status, 200);
    assert.equal(r.body.sources.tfnsw, 'off');
    assert.equal(r.body.sources.waze, 'off');
    assert.equal(r.body.sources.fuel, 'off');
    assert.equal(r.body.traffic.level, 'clear');
  } finally { await t2.close(); }
});

test('slow road data: answer without it (pending), keep fetching, and serve it from the database next time', async () => {
  const { ConditionsService } = await import('../src/conditions/conditions.js');
  let release!: () => void;
  const gate = new Promise<void>((r) => { release = r; });
  const slow = async (url: string, init?: RequestInit) => { if (url.includes('overpass')) await gate; return fakeFetch(url, init); };
  const svc = new ConditionsService(t.database.db, { ...t.config, tfnswApiKey: null, waze: null }, slow);
  const line: Pt[] = [[149.60, -33.40], [149.61, -33.41], [149.62, -33.40]];
  const first = await svc.forRoute(line, { roadsWaitMs: 50 });
  assert.equal(first.sources.roads, 'pending');
  assert.equal(first.roads, null);
  release();
  await new Promise((r) => setTimeout(r, 200));
  const second = await svc.forRoute(line, { roadsWaitMs: 50 });
  assert.equal(second.sources.roads, 'ok');
  assert.ok(second.roads);
});

test('stops: kinds from map tags, FuelCheck parsing, the map layer', async () => {
  const { stopKind, parseFuelCheck } = await import('../src/conditions/stops.js');
  assert.equal(stopKind({ amenity: 'fuel' }), 'fuel');
  assert.equal(stopKind({ amenity: 'fast_food' }), 'food');
  assert.equal(stopKind({ highway: 'rest_area' }), 'rest');
  assert.equal(stopKind({ amenity: 'charging_station' }), 'ev');
  assert.equal(stopKind({ amenity: 'bench' }), null);
  const fc = parseFuelCheck({ stations: [{ code: 5, name: 'X', brand: 'Y', location: { latitude: -33, longitude: 149 } }, { code: 6 }], prices: [{ stationcode: 5, fueltype: 'DL', price: 210.1 }, { stationcode: 5, fueltype: 'U91', price: 0 }] });
  assert.equal(fc.stations.length, 1);
  assert.deepEqual(fc.prices.map((p) => p.fuel), ['DL']);
  const r = await t.req('GET', '/v1/stops?bbox=-33.45,149.55,-33.44,149.58', { token: a.token });
  assert.equal(r.status, 200, JSON.stringify(r.body));
  assert.ok(r.body.some((x: any) => x.kind === 'fuel' && x.prices.U91 === 199.9));
});
