import type http from 'node:http';
import type { Duplex } from 'node:stream';
import { WebSocketServer, type WebSocket } from 'ws';
import { MAX_LABEL, MAX_NAME, MissLimiter, Rooms, type MemberVehicle, type Who } from './convoy/rooms.js';
import { parseClientMessage, type ClientMessage } from './convoy/messages.js';
import { OpenCruise } from './convoy/openCruise.js';
import { CruiseError, type Authenticator, type Cruise, type CruiseSource, type RestStopFinder } from './ports.js';

/**
 * The one WebSocket endpoint, `/ws`: convoy rooms.
 *
 * Positions are relayed from memory to memory. They are not stored, and not
 * logged: nothing in this file writes one anywhere but another socket.
 */

export const WS_PATH = '/ws';
/** A few KB: the biggest honest message is a 80-character label. */
export const MAX_MESSAGE_BYTES = 4096;
const MAX_SOCKETS_PER_USER = 5;
const MAX_SOCKETS = 2000;
/** Ten messages at once, then five a second. */
const BUCKET = 10;
const REFILL_PER_SECOND = 5;
/** More messages than this refused inside ten seconds and the socket is closed on the sender. */
const FLOOD_DROPS = 50;

/** Close codes in the private range, so a client can tell why it was dropped and not retry in vain. */
export const CLOSE_SESSION_ENDED = 4401;
export const CLOSE_FLOODING = 1008;

interface Conn {
  ws: WebSocket;
  who: Who;
  userId: string;
  alive: boolean;
  tokens: number;
  refilledAt: number;
  drops: number;
  dropsSince: number;
}

export interface RealtimeOptions {
  auth: Authenticator;
  cruises: CruiseSource;
  restStops: RestStopFinder;
  /** Road-following polyline6 between two [lng, lat] points, or null. Optional: without it a destination carries no routeGeometry. */
  routeFinder?: (from: [number, number], to: [number, number], via?: [number, number][]) => Promise<string | null>;
  heartbeatMs?: number;
  sweepMs?: number;
  /** Re-checked every `recheckMs` for each connected user; false (or a throw is ignored) ends their sockets with CLOSE_SESSION_ENDED. */
  stillValid?: (userId: string) => Promise<boolean>;
  recheckMs?: number;
  rooms?: Rooms;
  openCruise?: OpenCruise;
  /** Called when a member raises an SOS, so the push layer can reach members who are not connected. */
  onSos?: (event: { code: string; from: string; note: string | undefined; memberIds: string[] }) => void;
}

/** Rough ground distance in metres between two [lng, lat] points. */
function metres(a: [number, number], b: [number, number]): number {
  const k = Math.cos(((a[1] + b[1]) / 2) * Math.PI / 180);
  return Math.hypot((a[0] - b[0]) * k, a[1] - b[1]) * 111_320;
}

export interface Realtime {
  close(): void;
  sendToRoom(code: string, message: unknown): void;
  /** A connected user changed their display name: update it in their rooms and tell the members. */
  renameUser(userId: string, name: string): void;
  /** A user's primary vehicle changed: update the badge in their rooms and tell the members. */
  setVehicle(userId: string, vehicle: MemberVehicle | null): void;
  /** Sends a `notify` to the user's open sockets (the app's keep-alive connection). False if none is open. */
  notifyUser(userId: string, payload: { title: string; body: string; data?: Record<string, string> }): boolean;
}

/** Subprotocol a client offers (alongside `bearer.<access token>`) so the token never lands in a URL. */
export const WS_PROTOCOL = 'convoyage.v1';

const send = (conn: Conn, message: unknown): void => {
  // A stalled phone must not accumulate an unbounded queue of positions or chat.
  if (conn.ws.bufferedAmount > 256 * 1024) { conn.ws.terminate(); return; }
  if (conn.ws.readyState === conn.ws.OPEN) conn.ws.send(JSON.stringify(message));
};

function reject(socket: Duplex, status: number, reason: string): void {
  socket.write(`HTTP/1.1 ${status} ${reason}\r\nConnection: close\r\nContent-Length: 0\r\n\r\n`);
  socket.destroy();
}

function close(conn: Conn, code: number, reason: string): void {
  try { conn.ws.close(code, reason); } catch { conn.ws.terminate(); }
}

/** A token bucket per socket. Returns whether this message may be acted on. */
function allowed(conn: Conn, now: number): boolean {
  conn.tokens = Math.min(BUCKET, conn.tokens + ((now - conn.refilledAt) / 1000) * REFILL_PER_SECOND);
  conn.refilledAt = now;
  if (conn.tokens >= 1) { conn.tokens -= 1; return true; }
  if (now - conn.dropsSince > 10_000) { conn.dropsSince = now; conn.drops = 0; }
  if (++conn.drops > FLOOD_DROPS) close(conn, CLOSE_FLOODING, 'Slow down');
  return false;
}

/**
 * Attach the WebSocket endpoint to an HTTP server. `close` drops every socket and stops the
 * timers (for tests and a clean shutdown); `sendToRoom` lets the REST layer push a server
 * message to every member of a convoy room.
 */
export function attachRealtime(server: http.Server, opts: RealtimeOptions): Realtime {
  const heartbeatMs = opts.heartbeatMs ?? 30_000;
  const rooms = opts.rooms ?? new Rooms();
  const misses = new MissLimiter();
  const cruise = opts.openCruise ?? new OpenCruise();
  /** Invites a user holds: code -> expiry. Joining an open convoy without an invite needs it to be open and nearby. */
  const invitedTo = new Map<string, Map<string, number>>();
  const conns = new Set<Conn>();
  const byUser = new Map<string, Set<Conn>>();
  const wss = new WebSocketServer({
    noServer: true, maxPayload: MAX_MESSAGE_BYTES, perMessageDeflate: false,
    // Echo our protocol if offered; the `bearer.<token>` entry is only a carrier and is never selected.
    handleProtocols: (protocols) => (protocols.has(WS_PROTOCOL) ? WS_PROTOCOL : false),
  });

  const sendUser = (userId: string, message: unknown): void => {
    for (const conn of byUser.get(userId) ?? []) send(conn, message);
  };

  const sendToRoom = (code: string, message: unknown): void => {
    for (const id of rooms.memberIds(code)) sendUser(id, message);
  };

  /** The room as every member should now see it. */
  function announce(code: string): void {
    const view = rooms.view(code);
    if (!view) return;
    for (const id of rooms.memberIds(code)) sendUser(id, { t: 'room', room: view });
  }

  /** Send an Open Cruise driver who and what is around them. */
  function pushNearby(userId: string): void {
    if (!cruise.isOn(userId)) return;
    sendUser(userId, { t: 'cruise.nearby', ...cruise.nearby(userId, rooms.openRooms(), (a, b) => rooms.together(a, b)) });
  }

  /** A sustained split queued by Rooms: look up one advisory stop and apply it only if it is still pending. */
  function automaticRegroup(code: string): void {
    const room = rooms.view(code);
    if (!room?.route || room.resting) return;
    const reserved = rooms.reserveRestLookup(code, room.leader);
    if (!reserved.ok) { rooms.completeAutomaticRegroup(code, null); return; }
    // Somewhere close to all of them, not just the leader.
    const from = rooms.meetingPoint(code) ?? room.route.stops[0];
    void opts.restStops.find(room.route.stops, from).then((stop) => {
      if (rooms.navigationVersion(code) !== reserved.value) return;
      const done = rooms.completeAutomaticRegroup(code, stop ? { lat: stop.lat, lng: stop.lng, label: `Regroup: ${stop.label}` } : null);
      if (done.ok && stop) announce(code);
    }).catch(() => { rooms.completeAutomaticRegroup(code, null); });
  }

  /**
   * The shared route's stops still ahead, when the destination is where the route ends (a saved route or a cruise):
   * from the stop after the one nearest the leader, up to (not including) the last. Empty otherwise, so a destination
   * off the route is driven to directly.
   */
  function viaStops(stops: [number, number][], from: [number, number], to: [number, number]): [number, number][] {
    if (stops.length < 3 || metres(stops[stops.length - 1], to) > 150) return [];
    let nearest = 0;
    for (let i = 1; i < stops.length - 1; i++) if (metres(stops[i], from) < metres(stops[nearest], from)) nearest = i;
    const ahead = stops.slice(nearest + (metres(stops[nearest], from) < 150 ? 1 : 0), -1);
    return ahead.slice(0, 20);
  }

  /** After the leader sets a destination: fetch a road-following route from where they are, and share it if nothing changed meanwhile. */
  function routeToDestination(code: string): void {
    const find = opts.routeFinder;
    const room = rooms.view(code);
    const leader = room?.members.find((member) => member.id === room.leader)?.position;
    if (!find || !room?.destination || !leader) return;
    const version = rooms.navigationVersion(code)!;
    const via = room.route ? viaStops(room.route.stops, [leader.lng, leader.lat], [room.destination.lng, room.destination.lat]) : [];
    void find([leader.lng, leader.lat], [room.destination.lng, room.destination.lat], via).then((geometry) => {
      if (!geometry) return;
      if (rooms.setRouteGeometry(code, version, geometry).ok) announce(code);
    }).catch(() => {});
  }

  async function enter(conn: Conn, m: Extract<ClientMessage, { t: 'convoy.create' | 'convoy.join' | 'convoy.cruise' }>, error: (text: string) => void): Promise<void> {
    const who = conn.who;
    let done;
    if (m.t === 'convoy.create') {
      done = rooms.create(who, { name: m.name, automaticRegroup: m.automaticRegroup });
    } else if (m.t === 'convoy.join') {
      if (misses.blocked(conn.userId)) return error('Too many wrong codes. Try again in a few minutes.');
      done = rooms.join(m.code, who);
      if (!done.ok && done.error.startsWith('No convoy')) misses.miss(conn.userId);
    } else {
      let cruise: Cruise | null;
      try {
        cruise = await opts.cruises.getCruise(m.eventId, conn.userId);
      } catch (e) {
        if (e instanceof CruiseError) return error(e.message);
        throw e;
      }
      if (!cruise) return error('No such cruise');
      const code = rooms.forEvent(m.eventId);
      if (code) {
        if (!cruise.canJoin) return error('You are not on this cruise');
        done = rooms.join(code, who);
      } else if (!cruise.canStart) {
        return error('The organiser has not started this convoy yet');
      } else {
        done = rooms.create(who, {
          name: cruise.name,
          eventId: m.eventId,
          route: cruise.route ?? null,
          destination: { lat: cruise.meet.lat, lng: cruise.meet.lng, label: (cruise.meet.label || 'Meeting point').slice(0, MAX_LABEL) },
        });
      }
    }
    if (!done.ok) return error(done.error);
    announce(done.value.code);
  }

  function handle(conn: Conn, m: ClientMessage): void {
    const reply = (message: unknown) => send(conn, message);
    const error = (text: string) => reply({ t: 'error', error: text, in: m.t });
    switch (m.t) {
      case 'ping':
        return reply({ t: 'pong' });
      case 'convoy.message': {
        const done = rooms.message(m.room, conn.userId, m.text, m.clientId);
        if (!done.ok) return error(done.error);
        for (const id of rooms.memberIds(m.room)) sendUser(id, { t: 'convoy.message', room: m.room, message: done.value });
        return;
      }
      case 'convoy.status': {
        const codes = rooms.roomsOf(conn.userId);
        if (!codes.length) return error('You are not in a convoy');
        for (const code of codes) {
          const msg: Record<string, unknown> = { t: 'status', room: code, id: conn.userId, status: m.status };
          if (m.note) msg.note = m.note;
          if (m.status === 'sos') msg.position = rooms.memberPosition(code, conn.userId);
          sendToRoom(code, msg);
          if (m.status === 'sos') opts.onSos?.({ code, from: conn.userId, note: m.note, memberIds: rooms.memberIds(code) });
        }
        return;
      }
      case 'pos.stop':
        for (const code of rooms.clearPosition(conn.userId)) announce(code);
        return;
      case 'pos': {
        if (cruise.update(conn.userId, m.lat, m.lng)) pushNearby(conn.userId);
        const out = rooms.position(conn.userId, { lat: m.lat, lng: m.lng, heading: m.heading, speed: m.speed });
        if (!out) return; // not in a room, or too soon after the last: dropped without a word
        for (const code of out.codes) {
          const packet = { t: 'pos', room: code, id: conn.userId, ...out.position };
          for (const id of rooms.memberIds(code)) if (id !== conn.userId) sendUser(id, packet);
        }
        for (const code of out.automaticRegroupCodes) automaticRegroup(code);
        return;
      }
      case 'convoy.leave': {
        if (!rooms.roomsOf(conn.userId).includes(m.room)) return error('You are not in that convoy');
        rooms.leave(m.room, conn.userId);
        sendUser(conn.userId, { t: 'left', room: m.room });
        return announce(m.room);
      }
      case 'convoy.remove': {
        const done = rooms.remove(m.room, conn.userId, m.id);
        if (!done.ok) return error(done.error);
        sendUser(m.id, { t: 'left', room: m.room, removed: true });
        return announce(m.room);
      }
      case 'convoy.open': {
        const done = rooms.setOpen(m.room, conn.userId, m.open);
        return done.ok ? announce(m.room) : error(done.error);
      }
      case 'cruise.open': {
        cruise.set(conn.who, m.on);
        reply({ t: 'cruise.state', on: m.on });
        if (m.on) pushNearby(conn.userId);
        return;
      }
      case 'cruise.invite': {
        const ok = cruise.invite(conn.userId, m.to);
        if (!ok.ok) return error(ok.error);
        // Invite into the convoy you are in, or start an open one for the two of you.
        let code = rooms.roomsOf(conn.userId)[0];
        if (!code) {
          const made = rooms.create(conn.who, { name: `${conn.who.username}'s open convoy`, open: true });
          if (!made.ok) return error(made.error);
          code = made.value.code;
          announce(code);
        }
        const view = rooms.view(code)!;
        let mine = invitedTo.get(m.to);
        if (!mine) invitedTo.set(m.to, (mine = new Map()));
        mine.set(code, ok.expiresAt);
        sendUser(m.to, { t: 'cruise.invite', from: conn.userId, fromName: conn.who.username, code, name: view.name, expiresAt: ok.expiresAt });
        return reply({ t: 'cruise.invited', to: m.to, code });
      }
      case 'cruise.join': {
        // An open convoy nearby, or one you were invited to: no code typed, so no wrong-code counting either.
        const invite = invitedTo.get(conn.userId)?.get(m.code);
        const invited = invite !== undefined && invite > Date.now();
        const nearby = cruise.isOn(conn.userId) && cruise.nearby(conn.userId, rooms.openRooms(), () => false).convoys.some((c) => c.code === m.code);
        if (!invited && !nearby) return error('That convoy is not open to you');
        const done = rooms.join(m.code, conn.who);
        if (!done.ok) return error(done.error);
        invitedTo.get(conn.userId)?.delete(m.code);
        announce(m.code);
        return;
      }
      case 'convoy.rename':
      case 'convoy.regroup': {
        const done = m.t === 'convoy.rename'
          ? rooms.rename(m.room, conn.userId, m.name)
          : rooms.setAutomaticRegroup(m.room, conn.userId, { enabled: m.enabled, separationM: m.separationM });
        return done.ok ? announce(m.room) : error(done.error);
      }
      case 'convoy.dest':
      case 'convoy.route':
      case 'convoy.lead': {
        const done = m.t === 'convoy.dest'
          ? rooms.setDestination(m.room, conn.userId, m.destination)
          : m.t === 'convoy.route'
            ? rooms.setRoute(m.room, conn.userId, m.route)
            : rooms.handOver(m.room, conn.userId, m.to);
        if (!done.ok) return error(done.error);
        announce(m.room);
        if (m.t === 'convoy.dest') routeToDestination(m.room);
        return;
      }
      case 'convoy.rest': {
        const room = rooms.view(m.room);
        if (!room || !room.members.some((member) => member.id === conn.userId)) return error('You are not in that convoy');
        if (m.action === 'continue') {
          if (room.leader !== conn.userId) return error('Only the leader can continue to the destination');
          const done = rooms.continueDestination(m.room, conn.userId);
          return done.ok ? announce(m.room) : error(done.error);
        }
        if (!room.route) return error('Set a convoy route before finding a rest stop');
        const reserved = rooms.reserveRestLookup(m.room, conn.userId);
        if (!reserved.ok) return error(reserved.error);
        const from = rooms.meetingPoint(m.room) ?? room.route.stops[0];
        void opts.restStops.find(room.route.stops, from).then((stop) => {
          if (!conns.has(conn)) return;
          if (rooms.navigationVersion(m.room) !== reserved.value) return error('The convoy route or destination changed. Search again.');
          if (!stop) return error('No suitable rest stop was found close to the remaining route');
          const done = rooms.setRestStop(m.room, conn.userId, stop);
          if (done.ok) announce(m.room); else error(done.error);
        }).catch(() => error('Could not look up rest stops right now'));
        return;
      }
      case 'convoy.create':
      case 'convoy.join':
      case 'convoy.cruise':
        void enter(conn, m, error).catch(() => error('Could not do that right now'));
        return;
    }
  }

  server.on('upgrade', onUpgrade);
  function onUpgrade(req: http.IncomingMessage, socket: Duplex, head: Buffer): void {
    socket.on('error', () => socket.destroy());
    if (new URL(req.url ?? '/', 'http://x').pathname !== WS_PATH) return void socket.destroy();
    // The Authenticator decides who this is (JWT in production). Access tokens are not re-checked while a socket stays open.
    Promise.resolve(opts.auth.authenticate(req)).then((who) => {
      if (!who) return reject(socket, 401, 'Unauthorized');
      if (conns.size >= MAX_SOCKETS || (byUser.get(who.id)?.size ?? 0) >= MAX_SOCKETS_PER_USER) return reject(socket, 429, 'Too Many Requests');
      wss.handleUpgrade(req, socket, head, (ws) => open(ws, who));
    }, () => reject(socket, 401, 'Unauthorized'));
  }

  function open(ws: WebSocket, who: Who): void {
    const userId = who.id;
    const now = Date.now();
    const conn: Conn = { ws, who, userId, alive: true, tokens: BUCKET, refilledAt: now, drops: 0, dropsSince: now };
    conns.add(conn);
    let mine = byUser.get(userId);
    if (!mine) byUser.set(userId, (mine = new Set()));
    mine.add(conn);

    ws.on('pong', () => { conn.alive = true; });
    ws.on('message', (data, isBinary) => {
      conn.alive = true;
      if (isBinary) return close(conn, 1003, 'Text only');
      if (!allowed(conn, Date.now())) return;
      const parsed = parseClientMessage((data as Buffer).toString('utf8'));
      if (!parsed.ok) return send(conn, { t: 'error', error: parsed.error });
      try { handle(conn, parsed.message); } catch (err) { console.error('[ws] handler failed:', (err as Error).message); }
    });
    ws.on('error', () => ws.terminate());
    ws.on('close', () => {
      conns.delete(conn);
      mine!.delete(conn);
      if (mine!.size === 0) {
        byUser.delete(userId);
        cruise.set(conn.who, false);
        for (const code of rooms.setOnline(userId, false)) announce(code);
      }
    });

    // Tell them who they are and which convoys they are still in, so a reconnect picks up where it left off,
    // and then tell the rest of those convoys they are back.
    const back = rooms.setOnline(userId, true);
    send(conn, { t: 'hello', me: userId, rooms: rooms.roomsOf(userId).map((c) => rooms.view(c)) });
    for (const code of back) announce(code);
  }

  // Ping everyone; whoever did not answer the last one is gone.
  const beat = setInterval(() => {
    for (const conn of [...conns]) {
      if (!conn.alive) { conn.ws.terminate(); continue; }
      conn.alive = false;
      try { conn.ws.ping(); } catch { conn.ws.terminate(); }
    }
  }, heartbeatMs);
  const sweepTimer = setInterval(() => {
    const { changed } = rooms.sweep();
    for (const code of changed) announce(code);
    misses.sweep();
    cruise.sweep();
    const now = Date.now();
    for (const [user, invites] of invitedTo) {
      for (const [code, at] of invites) if (at <= now) invites.delete(code);
      if (!invites.size) invitedTo.delete(user);
    }
  }, opts.sweepMs ?? 30_000);
  // A socket outlives the access token it connected with, so ask again whether the account may still be here
  // (deleted, disabled, expired guest). One query per connected user.
  let rechecking = false;
  const recheckTimer = opts.stillValid ? setInterval(async () => {
    if (rechecking) return;
    rechecking = true;
    try {
      for (const userId of [...byUser.keys()]) {
        let ok = true;
        try { ok = await opts.stillValid!(userId); } catch { continue; }
        if (ok) continue;
        for (const conn of [...(byUser.get(userId) ?? [])]) conn.ws.close(CLOSE_SESSION_ENDED, 'session ended');
      }
    } finally { rechecking = false; }
  }, opts.recheckMs ?? 60_000) : null;
  beat.unref();
  sweepTimer.unref();
  recheckTimer?.unref();

  return {
    sendToRoom,
    renameUser(userId, name) {
      for (const conn of byUser.get(userId) ?? []) conn.who = { ...conn.who, username: name.trim().slice(0, MAX_NAME) || conn.who.username };
      for (const code of rooms.renameMember(userId, name)) announce(code);
    },
    notifyUser(userId, payload) {
      const conns = byUser.get(userId);
      if (!conns || !conns.size) return false;
      sendUser(userId, { t: 'notify', title: payload.title, body: payload.body, data: payload.data ?? {} });
      return true;
    },
    setVehicle(userId, vehicle) {
      for (const conn of byUser.get(userId) ?? []) conn.who = { ...conn.who, vehicle };
      for (const code of rooms.setMemberVehicle(userId, vehicle)) announce(code);
    },
    close() {
      clearInterval(beat);
      clearInterval(sweepTimer);
      if (recheckTimer) clearInterval(recheckTimer);
      server.off('upgrade', onUpgrade);
      for (const conn of [...conns]) conn.ws.terminate();
      wss.close();
    },
  };
}
