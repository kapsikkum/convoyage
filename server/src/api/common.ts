import type { FastifyRequest } from 'fastify';
import { sql, type Kysely, type RawBuilder, type SqlBool } from 'kysely';
import type { Config } from '../config.js';
import type { DB } from '../db/index.js';
import type { Mailer } from '../mail.js';
import type { MediaStore } from '../media/store.js';
import { badRequest, forbidden, notFound, unauthorized } from '../errors.js';
import type { Providers } from './providers.js';
import type { Notifier } from '../push/notifier.js';
import type { Fetch } from '../http.js';
import type { Tokens } from './tokens.js';
import type { PhotoScoutService } from '../photoscout/service.js';

declare module 'fastify' {
  interface FastifyRequest { userId: string; userKind: 'user' | 'guest' }
}

/** How the REST layer reaches the live convoy rooms (for hazard broadcast). */
export interface ConvoyLink {
  roomsOf(userId: string): string[];
  sendToRoom(code: string, message: unknown): void;
  /** Members of a room with no live socket. */
  offlineMemberIds(code: string): string[];
  /** A connected user changed their display name. */
  renameUser(userId: string, name: string): void;
  /** A connected user's primary vehicle changed: update the badge in their rooms. */
  setVehicle(userId: string, vehicle: VehicleBadge | null): void;
}

export interface ApiContext {
  db: Kysely<DB>;
  config: Config;
  tokens: Tokens;
  mailer: Mailer;
  providers: Providers;
  media: MediaStore;
  convoy: ConvoyLink;
  notifier: Notifier;
  fetch: Fetch;
  /** Null until PHOTOSCOUT_URL is configured. */
  photoscout: PhotoScoutService | null;
}

export const iso = (d: Date | string): string => new Date(d).toISOString();
export const isoOrNull = (d: Date | string | null): string | null => (d ? iso(d) : null);

/** Writes a guest may still make: convoy-adjacent things only. Everything else that is not a GET is 403 for guests. */
const GUEST_WRITES = new Set(['/v1/voice/token', '/v1/hazards/:id/confirm', '/v1/hazards/:id/dismiss', '/v1/hazards/:id/ack']);

/** preHandler: require a valid bearer access token and set `request.userId`. Guests may only read, plus GUEST_WRITES. */
export const authenticate = (tokens: Tokens) => async (request: FastifyRequest): Promise<void> => {
  const m = /^Bearer\s+(\S+)$/i.exec(request.headers.authorization ?? '');
  const who = m ? await tokens.principal(m[1]) : null;
  if (!who) throw unauthorized('Missing or invalid access token');
  request.userId = who.id;
  request.userKind = who.kind;
  if (who.kind === 'guest' && request.method !== 'GET' && request.method !== 'HEAD' && !GUEST_WRITES.has(request.routeOptions.url ?? '')) {
    throw forbidden('Guests cannot do this: sign in with an account');
  }
};

// ---- cursors: (timestamp, id) keyset paging ------------------------------------------------------------------

export function encodeCursor(ts: Date | string, id: string): string {
  return Buffer.from(`${iso(ts)}|${id}`).toString('base64url');
}

export function decodeCursor(raw: string | undefined): { ts: string; id: string } | null {
  if (!raw) return null;
  const [ts, id] = Buffer.from(raw, 'base64url').toString('utf8').split('|');
  if (!ts || !id || Number.isNaN(Date.parse(ts)) || !/^[0-9a-f-]{36}$/i.test(id)) throw badRequest('Bad cursor');
  return { ts: new Date(ts).toISOString(), id };
}

/** `(tsCol, idCol) < cursor`, for descending (newest first) paging. */
export function beforeCursor(tsCol: string, idCol: string, cursor: { ts: string; id: string } | null): RawBuilder<SqlBool> {
  if (!cursor) return sql<SqlBool>`true`;
  return sql<SqlBool>`(${sql.ref(tsCol)}, ${sql.ref(idCol)}) < (${cursor.ts}::timestamptz, ${cursor.id}::uuid)`;
}

/** `(tsCol, idCol) > cursor`, for ascending paging. */
export function afterCursor(tsCol: string, idCol: string, cursor: { ts: string; id: string } | null): RawBuilder<SqlBool> {
  if (!cursor) return sql<SqlBool>`true`;
  return sql<SqlBool>`(${sql.ref(tsCol)}, ${sql.ref(idCol)}) > (${cursor.ts}::timestamptz, ${cursor.id}::uuid)`;
}

export function page<T>(rows: T[], limit: number, cursorOf: (row: T) => string): { items: T[]; nextCursor: string | null } {
  const more = rows.length > limit;
  const items = more ? rows.slice(0, limit) : rows;
  return { items, nextCursor: more ? cursorOf(items[items.length - 1]) : null };
}

// ---- access rules ---------------------------------------------------------------------------------------------

/**
 * SQL: may `viewer` see content owned by `ownerCol` with visibility `visCol` (public | followers | private)?
 *  - the owner always can;
 *  - nobody can across a block, in either direction;
 *  - `private` is owner-only;
 *  - `followers` needs an accepted follow;
 *  - `public` is open, unless the owner's account is private, which also needs an accepted follow.
 */
export function visibleTo(viewer: string, ownerCol: string, visCol: string): RawBuilder<SqlBool> {
  const owner = sql.ref(ownerCol);
  const vis = sql.ref(visCol);
  return sql<SqlBool>`(
    ${owner} = ${viewer}::uuid
    or (
      not exists (select 1 from blocks bl where (bl.blocker_id = ${viewer}::uuid and bl.blocked_id = ${owner}) or (bl.blocker_id = ${owner} and bl.blocked_id = ${viewer}::uuid))
      and ${vis} <> 'private'
      and (
        exists (select 1 from follows fo where fo.follower_id = ${viewer}::uuid and fo.followee_id = ${owner} and fo.status = 'accepted')
        or (${vis} = 'public' and not exists (select 1 from profiles pv where pv.user_id = ${owner} and pv.is_private))
      )
    )
  )`;
}

export interface Relationship {
  self: boolean;
  blockedByMe: boolean;
  blockedMe: boolean;
  following: 'none' | 'pending' | 'accepted';
  followedBy: boolean;
  isPrivate: boolean;
  /** Not blocked, and either a public account, myself, or an accepted follower. */
  canViewContent: boolean;
}

export async function relationship(db: Kysely<DB>, viewer: string, target: string): Promise<Relationship> {
  const [blocks, follows, profile] = await Promise.all([
    db.selectFrom('blocks').select(['blocker_id', 'blocked_id'])
      .where((eb) => eb.or([eb.and([eb('blocker_id', '=', viewer), eb('blocked_id', '=', target)]), eb.and([eb('blocker_id', '=', target), eb('blocked_id', '=', viewer)])])).execute(),
    db.selectFrom('follows').select(['follower_id', 'status'])
      .where((eb) => eb.or([eb.and([eb('follower_id', '=', viewer), eb('followee_id', '=', target)]), eb.and([eb('follower_id', '=', target), eb('followee_id', '=', viewer)])])).execute(),
    db.selectFrom('profiles').select('is_private').where('user_id', '=', target).executeTakeFirst(),
  ]);
  if (!profile) throw notFound('No such user');
  const self = viewer === target;
  const blockedByMe = blocks.some((b) => b.blocker_id === viewer);
  const blockedMe = blocks.some((b) => b.blocker_id === target);
  const mine = follows.find((f) => f.follower_id === viewer);
  const following = mine ? mine.status : 'none';
  const followedBy = follows.some((f) => f.follower_id === target && f.status === 'accepted');
  const canViewContent = self || (!blockedByMe && !blockedMe && (!profile.is_private || following === 'accepted'));
  return { self, blockedByMe, blockedMe, following, followedBy, isPrivate: profile.is_private, canViewContent };
}

// ---- vehicle badges ------------------------------------------------------------------------------------------

/** What an avatar badge needs of a user's primary vehicle: its kind and the build fields that change how it looks. */
export interface VehicleBadge {
  kind: 'car' | 'motorcycle' | 'ute' | '4wd';
  build: { paint?: string; finish?: string; wheel?: string; rideHeightMm?: number; body?: string; shape?: string };
}

export function badgeOf(kind: VehicleBadge['kind'], build: unknown): VehicleBadge {
  const b = (typeof build === 'object' && build !== null ? build : {}) as Record<string, unknown>;
  const out: VehicleBadge['build'] = {};
  if (typeof b.paint === 'string') out.paint = b.paint;
  if (typeof b.finish === 'string') out.finish = b.finish;
  if (typeof b.wheel === 'string') out.wheel = b.wheel;
  if (typeof b.rideHeightMm === 'number') out.rideHeightMm = b.rideHeightMm;
  if (typeof b.body === 'string') out.body = b.body;
  if (typeof b.shape === 'string') out.shape = b.shape;
  return { kind, build: out };
}

/**
 * The primary (unsold) vehicle of each user, as a badge, for the users `viewer` may see it for. The same rule as a profile's
 * `primaryVehicle`: the owner always; nobody across a block; a private account only to an accepted follower. Others map to null.
 */
export async function vehicleBadges(db: Kysely<DB>, viewer: string, userIds: string[]): Promise<Map<string, VehicleBadge | null>> {
  const ids = [...new Set(userIds)];
  const out = new Map<string, VehicleBadge | null>(ids.map((id) => [id, null]));
  if (!ids.length) return out;
  const [vehicles, profiles, follows, blocks] = await Promise.all([
    db.selectFrom('vehicles').select(['owner_id', 'kind', 'build']).where('owner_id', 'in', ids).where('is_primary', '=', true).where('sold_at', 'is', null).execute(),
    db.selectFrom('profiles').select(['user_id', 'is_private']).where('user_id', 'in', ids).execute(),
    db.selectFrom('follows').select('followee_id').where('follower_id', '=', viewer).where('followee_id', 'in', ids).where('status', '=', 'accepted').execute(),
    db.selectFrom('blocks').select(['blocker_id', 'blocked_id'])
      .where((eb) => eb.or([eb.and([eb('blocker_id', '=', viewer), eb('blocked_id', 'in', ids)]), eb.and([eb('blocked_id', '=', viewer), eb('blocker_id', 'in', ids)])])).execute(),
  ]);
  const priv = new Set(profiles.filter((p) => p.is_private).map((p) => p.user_id));
  const followed = new Set(follows.map((f) => f.followee_id));
  const blocked = new Set(blocks.map((b) => (b.blocker_id === viewer ? b.blocked_id : b.blocker_id)));
  for (const v of vehicles) {
    const ok = v.owner_id === viewer || (!blocked.has(v.owner_id) && (!priv.has(v.owner_id) || followed.has(v.owner_id)));
    if (ok) out.set(v.owner_id, badgeOf(v.kind, v.build));
  }
  return out;
}

/** Nobody: a viewer id that follows no one and is blocked by no one, i.e. what a stranger sees. */
const STRANGER = '00000000-0000-0000-0000-000000000000';

/** The badge anyone in a convoy room may see: a private account shows none (rooms are shared with people who may not follow it). */
export async function roomBadge(db: Kysely<DB>, userId: string): Promise<VehicleBadge | null> {
  return (await vehicleBadges(db, STRANGER, [userId])).get(userId) ?? null;
}
