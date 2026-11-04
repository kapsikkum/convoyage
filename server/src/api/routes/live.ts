import { randomBytes } from 'node:crypto';
import type { FastifyInstance, preHandlerHookHandler } from 'fastify';
import type { Rooms } from '../../convoy/rooms.js';

/**
 * Live links (Konvoyage's "share my drive"): a member makes a link anyone can open in a browser to watch the convoy
 * on a map, without the app or an account. Tokens live in memory with the rooms, last 12 hours at most, and die
 * with the convoy. The page polls a small JSON view: names, the destination and online members' positions.
 */

export const LIVE_LINK_TTL_MS = 12 * 3_600_000;
const MAX_LINKS = 5000;

export class LiveLinks {
  private links = new Map<string, { code: string; expiresAt: number }>();
  constructor(private now: () => number = Date.now) {}

  create(code: string): { token: string; expiresAt: number } {
    this.sweep();
    if (this.links.size >= MAX_LINKS) this.links.delete(this.links.keys().next().value!);
    const token = randomBytes(16).toString('base64url');
    const expiresAt = this.now() + LIVE_LINK_TTL_MS;
    this.links.set(token, { code, expiresAt });
    return { token, expiresAt };
  }

  resolve(token: string): string | null {
    const l = this.links.get(token);
    if (!l || l.expiresAt <= this.now()) return null;
    return l.code;
  }

  sweep(): void {
    const now = this.now();
    for (const [t, l] of this.links) if (l.expiresAt <= now) this.links.delete(t);
  }
}

const TOKEN = { type: 'string', pattern: '^[A-Za-z0-9_-]{16,64}$' } as const;

export async function liveRoutes(app: FastifyInstance, rooms: Rooms, auth: preHandlerHookHandler, links = new LiveLinks()): Promise<void> {
  app.post('/v1/convoys/:code/live', {
    preHandler: [auth],
    schema: {
      tags: ['convoy'], summary: 'Make a live link to this convoy',
      description: 'Anyone with the link can watch the convoy on a map in a browser (names, destination, online positions) for 12 hours or until the convoy ends. Members only.',
      params: { type: 'object', properties: { code: { type: 'string', maxLength: 16 } }, required: ['code'], additionalProperties: false },
    },
  }, async (req, reply) => {
    const code = (req.params as { code: string }).code.toUpperCase();
    const userId = (req as unknown as { userId: string }).userId;
    if (!rooms.roomsOf(userId).includes(code)) return reply.code(404).send({ error: 'You are not in that convoy' });
    const { token, expiresAt } = links.create(code);
    const host = req.headers['x-forwarded-host'] ?? req.headers.host;
    reply.code(201);
    return { token, url: `${req.protocol}://${host}/live/${token}`, expiresAt: new Date(expiresAt).toISOString() };
  });

  app.get('/live/:token/state', {
    schema: { hide: true, params: { type: 'object', properties: { token: TOKEN }, required: ['token'] } },
  }, async (req, reply) => {
    const code = links.resolve((req.params as { token: string }).token);
    const room = code ? rooms.view(code) : undefined;
    reply.header('cache-control', 'no-store');
    if (!room) return reply.code(404).send({ error: 'This live link has ended' });
    return {
      name: room.name, destination: room.destination,
      members: room.members.filter((m) => m.online && m.position).map((m) => ({
        name: m.username, leader: m.id === room.leader, lat: m.position!.lat, lng: m.position!.lng, heading: m.position!.heading, ts: m.position!.ts,
      })),
      total: room.members.length,
    };
  });

  app.get('/live/:token', {
    schema: { hide: true, params: { type: 'object', properties: { token: TOKEN }, required: ['token'] } },
  }, async (_req, reply) => {
    reply.header('content-type', 'text/html; charset=utf-8').header('cache-control', 'no-store').header('referrer-policy', 'no-referrer');
    return LIVE_PAGE;
  });
}

/** The watch page: a MapLibre map (OpenFreeMap tiles) and a list, refreshed every 3 s from `./<token>/state`. */
const LIVE_PAGE = `<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Convoy live</title>
<link rel="stylesheet" href="https://unpkg.com/maplibre-gl@4.7.1/dist/maplibre-gl.css">
<style>
:root{--bg:#05080d;--panel:#0a1018;--ink:#e8f1fa;--muted:#8ea4ba;--signal:#2de2e6;--line:#2f4862}
html,body{margin:0;height:100%;background:var(--bg);color:var(--ink);font:15px/1.4 system-ui,sans-serif}
#map{position:absolute;inset:0}
.panel{position:absolute;left:12px;right:12px;bottom:12px;max-width:420px;padding:14px 16px;background:var(--panel);border:1px solid var(--line);clip-path:polygon(14px 0,100% 0,100% 100%,0 100%,0 14px)}
h1{margin:0 0 2px;font-size:18px}.muted{color:var(--muted);font-size:13px}.eyebrow{font-size:11px;letter-spacing:.12em;color:var(--signal);font-weight:700}
ul{list-style:none;margin:8px 0 0;padding:0}li{display:flex;gap:8px;align-items:center;padding:2px 0}
.dot{width:10px;height:10px;border-radius:50%;background:var(--muted)}.lead .dot{background:var(--signal)}
.pin{width:26px;height:26px;border-radius:50%;background:#101a26;border:2px solid var(--muted);color:var(--ink);font:700 10px system-ui;display:flex;align-items:center;justify-content:center}
.pin.lead{border-color:var(--signal)}.pin.dest{border-color:#ff3d8b;width:14px;height:14px}
</style></head><body>
<div id="map"></div>
<div class="panel"><div class="eyebrow">CONVOY LIVE</div><h1 id="name">Loading…</h1><div class="muted" id="sub"></div><ul id="list"></ul></div>
<script src="https://unpkg.com/maplibre-gl@4.7.1/dist/maplibre-gl.js"></script>
<script>
const stateUrl = location.pathname.replace(/\\/$/, '') + '/state';
const map = new maplibregl.Map({ container: 'map', style: 'https://tiles.openfreemap.org/styles/dark', center: [149.5775, -33.4193], zoom: 11, attributionControl: { compact: true } });
const markers = new Map(); let dest = null; let framed = false;
const initials = (n) => (n || '?').trim().split(/\\s+/).map((p) => p[0]).join('').slice(0, 2).toUpperCase();
function pin(cls, text) { const el = document.createElement('div'); el.className = 'pin ' + cls; el.textContent = text; return el; }
async function tick() {
  try {
    const r = await fetch(stateUrl, { cache: 'no-store' });
    if (!r.ok) { document.getElementById('name').textContent = 'This live link has ended'; document.getElementById('sub').textContent = ''; return; }
    const s = await r.json();
    document.getElementById('name').textContent = s.name || 'Convoy';
    document.getElementById('sub').textContent = (s.destination ? 'To ' + (s.destination.label || 'a dropped pin') + ' · ' : '') + s.members.length + ' of ' + s.total + ' sharing';
    const list = document.getElementById('list'); list.innerHTML = '';
    const seen = new Set(); const bounds = new maplibregl.LngLatBounds();
    for (const m of s.members) {
      const key = m.name + (m.leader ? '*' : ''); seen.add(key);
      let mk = markers.get(key);
      if (!mk) { mk = new maplibregl.Marker({ element: pin(m.leader ? 'lead' : '', initials(m.name)) }).setLngLat([m.lng, m.lat]).addTo(map); markers.set(key, mk); }
      mk.setLngLat([m.lng, m.lat]); bounds.extend([m.lng, m.lat]);
      const li = document.createElement('li'); if (m.leader) li.className = 'lead';
      li.innerHTML = '<span class="dot"></span>'; li.append(document.createTextNode(m.name + (m.leader ? ' · leading' : ''))); list.append(li);
    }
    for (const [k, mk] of markers) if (!seen.has(k)) { mk.remove(); markers.delete(k); }
    if (s.destination) {
      if (!dest) dest = new maplibregl.Marker({ element: pin('dest', '') }).addTo(map);
      dest.setLngLat([s.destination.lng, s.destination.lat]); bounds.extend([s.destination.lng, s.destination.lat]);
    } else if (dest) { dest.remove(); dest = null; }
    if (!framed && !bounds.isEmpty()) { map.fitBounds(bounds, { padding: 80, maxZoom: 14, duration: 0 }); framed = true; }
  } catch (e) { /* try again next tick */ }
}
tick(); setInterval(tick, 3000);
</script></body></html>`;
