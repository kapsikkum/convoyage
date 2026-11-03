import type { FastifyInstance } from 'fastify';
import { sql, type Selectable } from 'kysely';
import type { PostsTable } from '../../db/types.js';
import { badRequest, conflict, forbidden, notFound } from '../../errors.js';
import {
  afterCursor, authenticate, beforeCursor, decodeCursor, encodeCursor, iso, page, visibleTo, type ApiContext,
} from '../common.js';
import { mediaDto, rideSummaryDto, vehicleDto } from '../dto.js';
import { limits } from '../limits.js';
import { routePreview } from '../preview.js';
import { userByHandle } from './users.js';
import { bearer, bodyObj, cursorQuery, errorResponses, idParams, ref, uuid } from '../schemas.js';

const MAX_MEDIA_PER_POST = 10;
const NEARBY_DEFAULT_KM = 50;

export async function postRoutes(app: FastifyInstance, ctx: ApiContext): Promise<void> {
  const { db } = ctx;
  const lim = limits(ctx.config);
  const auth = { preHandler: [authenticate(ctx.tokens)] };
  const security = (tag: string) => ({ security: bearer, tags: [tag] });
  const idOf = (req: { params: unknown }) => (req.params as { id: string }).id;

  /** Posts in the shape the apps render, with a handful of batched queries instead of one set per post. */
  async function hydrate(viewer: string, rows: Selectable<PostsTable>[]) {
    if (!rows.length) return [];
    const ids = rows.map((r) => r.id);
    const authorIds = [...new Set(rows.map((r) => r.author_id))];
    const rideIds = [...new Set(rows.flatMap((r) => (r.ride_id ? [r.ride_id] : [])))];
    const vehicleIds = [...new Set(rows.flatMap((r) => (r.vehicle_id ? [r.vehicle_id] : [])))];
    const [authors, media, rides, vehicles, likes, comments, mine] = await Promise.all([
      db.selectFrom('profiles').select(['user_id', 'handle', 'display_name', 'avatar_media_id']).where('user_id', 'in', authorIds).execute(),
      db.selectFrom('post_media').innerJoin('media', 'media.id', 'post_media.media_id').selectAll('media').select(['post_media.post_id', 'post_media.position'])
        .where('post_media.post_id', 'in', ids).orderBy('post_media.position').execute(),
      rideIds.length
        ? db.selectFrom('rides').selectAll().select(visibleTo(viewer, 'rides.owner_id', 'rides.visibility').as('can_view')).where('id', 'in', rideIds).execute()
        : [],
      vehicleIds.length ? db.selectFrom('vehicles').selectAll().where('id', 'in', vehicleIds).execute() : [],
      db.selectFrom('post_likes').select(['post_id', (eb) => eb.fn.countAll<number>().as('n')]).where('post_id', 'in', ids).groupBy('post_id').execute(),
      db.selectFrom('comments').select(['post_id', (eb) => eb.fn.countAll<number>().as('n')]).where('post_id', 'in', ids).groupBy('post_id').execute(),
      db.selectFrom('post_likes').select('post_id').where('post_id', 'in', ids).where('user_id', '=', viewer).execute(),
    ]);
    const authorBy = new Map(authors.map((a) => [a.user_id, { id: a.user_id, handle: a.handle, displayName: a.display_name, avatarMediaId: a.avatar_media_id }]));
    const rideBy = new Map(rides.filter((r) => r.can_view).map((r) => [r.id, { ...rideSummaryDto(r), preview: routePreview(r.polyline, r.polyline_precision) }]));
    const vehicleBy = new Map(vehicles.map((v) => [v.id, vehicleDto(v)]));
    const likeBy = new Map(likes.map((l) => [l.post_id, Number(l.n)]));
    const commentBy = new Map(comments.map((c) => [c.post_id, Number(c.n)]));
    const likedByMe = new Set(mine.map((m) => m.post_id));
    return rows.map((p) => ({
      id: p.id, kind: p.kind, author: authorBy.get(p.author_id)!, body: p.body, visibility: p.visibility, createdAt: iso(p.created_at),
      lat: p.lat, lng: p.lng,
      ride: p.ride_id ? rideBy.get(p.ride_id) ?? null : null,
      vehicle: p.vehicle_id ? vehicleBy.get(p.vehicle_id) ?? null : null,
      media: media.filter((m) => m.post_id === p.id).map(mediaDto),
      likeCount: likeBy.get(p.id) ?? 0, commentCount: commentBy.get(p.id) ?? 0, likedByMe: likedByMe.has(p.id),
    }));
  }

  async function visiblePost(id: string, viewer: string) {
    const post = await db.selectFrom('posts').selectAll().where('id', '=', id).where(visibleTo(viewer, 'posts.author_id', 'posts.visibility')).executeTakeFirst();
    if (!post) throw notFound('No such post');
    return post;
  }

  // ---- posts -------------------------------------------------------------------------------------------------

  app.post('/v1/posts', {
    ...auth, ...lim.write,
    schema: {
      ...security('posts'), summary: 'Create a post',
      description: 'kind=ride needs a finished ride of mine that is not private; kind=build needs one of my vehicles; kind=photo needs mediaIds; kind=text needs a body. A post cannot be more public than the ride it shows. lat/lng (optional) place it for the nearby feed; ride posts default to the ride start.',
      body: bodyObj({
        kind: { type: 'string', enum: ['ride', 'build', 'photo', 'text'] },
        body: { type: 'string', maxLength: 2000 },
        rideId: uuid, vehicleId: uuid,
        mediaIds: { type: 'array', items: uuid, maxItems: MAX_MEDIA_PER_POST, uniqueItems: true },
        visibility: { type: 'string', enum: ['public', 'followers'] },
        lat: { type: 'number', minimum: -90, maximum: 90 }, lng: { type: 'number', minimum: -180, maximum: 180 },
      }, ['kind']),
      response: { 201: ref('Post'), ...errorResponses },
    },
  }, async (req, reply) => {
    const b = req.body as { kind: 'ride' | 'build' | 'photo' | 'text'; body?: string; rideId?: string; vehicleId?: string; mediaIds?: string[]; visibility?: 'public' | 'followers'; lat?: number; lng?: number };
    const visibility = b.visibility ?? 'public';
    const body = (b.body ?? '').trim();
    let lat = b.lat ?? null;
    let lng = b.lng ?? null;
    if ((b.lat === undefined) !== (b.lng === undefined)) throw badRequest('Send both lat and lng, or neither');

    if (b.kind === 'text' && !body) throw badRequest('A text post needs a body');
    if (b.kind === 'photo' && !b.mediaIds?.length) throw badRequest('A photo post needs at least one photo');
    if (b.kind !== 'ride' && b.rideId) throw badRequest('rideId only goes with kind=ride');
    if (b.kind !== 'build' && b.vehicleId) throw badRequest('vehicleId only goes with kind=build');

    if (b.kind === 'ride') {
      if (!b.rideId) throw badRequest('A ride post needs rideId');
      const ride = await db.selectFrom('rides').selectAll().where('id', '=', b.rideId).where('owner_id', '=', req.userId).executeTakeFirst();
      if (!ride) throw badRequest('Unknown ride');
      if (ride.status !== 'finished') throw conflict('Finish the ride before sharing it');
      if (ride.visibility === 'private') throw conflict('Change the ride visibility from private before sharing it');
      if (visibility === 'public' && ride.visibility !== 'public') throw conflict('A public post needs a public ride; make the post followers-only or the ride public');
      if (lat === null && ride.start_lat !== null) { lat = ride.start_lat; lng = ride.start_lng; }
    }
    if (b.kind === 'build') {
      if (!b.vehicleId) throw badRequest('A build post needs vehicleId');
      if (!(await db.selectFrom('vehicles').select('id').where('id', '=', b.vehicleId).where('owner_id', '=', req.userId).executeTakeFirst())) throw badRequest('Unknown vehicle');
    }
    if (b.mediaIds?.length) {
      const owned = await db.selectFrom('media').select('id').where('id', 'in', b.mediaIds).where('owner_id', '=', req.userId).execute();
      if (owned.length !== b.mediaIds.length) throw badRequest('Unknown media');
    }

    const post = await db.transaction().execute(async (trx) => {
      const row = await trx.insertInto('posts').values({
        author_id: req.userId, kind: b.kind, ride_id: b.rideId ?? null, vehicle_id: b.vehicleId ?? null, body, visibility, lat, lng,
      }).returningAll().executeTakeFirstOrThrow();
      if (b.mediaIds?.length) await trx.insertInto('post_media').values(b.mediaIds.map((media_id, position) => ({ post_id: row.id, media_id, position }))).execute();
      return row;
    });
    return reply.code(201).send((await hydrate(req.userId, [post]))[0]);
  });

  app.get('/v1/posts/:id', {
    ...auth,
    schema: { ...security('posts'), summary: 'One post', params: idParams, response: { 200: ref('Post'), ...errorResponses } },
  }, async (req) => (await hydrate(req.userId, [await visiblePost(idOf(req), req.userId)]))[0]);

  app.delete('/v1/posts/:id', {
    ...auth, ...lim.write,
    schema: { ...security('posts'), summary: 'Delete my post', params: idParams, response: { 200: ref('Ok'), ...errorResponses } },
  }, async (req) => {
    const res = await db.deleteFrom('posts').where('id', '=', idOf(req)).where('author_id', '=', req.userId).executeTakeFirst();
    if (Number(res.numDeletedRows) === 0) throw notFound('No such post');
    return { ok: true };
  });

  app.get('/v1/users/:handle/posts', {
    ...auth,
    schema: {
      ...security('posts'), summary: 'A user posts that I may see', params: bodyObj({ handle: { type: 'string', maxLength: 40 } }),
      querystring: cursorQuery(), response: { 200: ref('FeedPage'), ...errorResponses },
    },
  }, async (req) => {
    const q = req.query as { before?: string; limit?: number };
    const { id } = await userByHandle(db, req.userId, (req.params as { handle: string }).handle);
    const limit = q.limit ?? 20;
    const rows = await db.selectFrom('posts').selectAll().where('author_id', '=', id).where(visibleTo(req.userId, 'posts.author_id', 'posts.visibility'))
      .where(beforeCursor('posts.created_at', 'posts.id', decodeCursor(q.before))).orderBy('posts.created_at', 'desc').orderBy('posts.id', 'desc').limit(limit + 1).execute();
    const p = page(rows, limit, (r) => encodeCursor(r.created_at, r.id));
    return { items: await hydrate(req.userId, p.items), nextCursor: p.nextCursor };
  });

  // ---- feed --------------------------------------------------------------------------------------------------

  app.get('/v1/feed', {
    ...auth,
    schema: {
      ...security('feed'), summary: 'The feed, newest first',
      description: 'scope=everyone: every post I may see. scope=following: me and the people I follow. scope=nearby: posts within radiusKm of lat/lng (needs lat and lng). Blocked users and private accounts I do not follow never appear. Page with `before` = nextCursor.',
      querystring: cursorQuery({
        scope: { type: 'string', enum: ['everyone', 'following', 'nearby'], default: 'everyone' },
        lat: { type: 'number', minimum: -90, maximum: 90 }, lng: { type: 'number', minimum: -180, maximum: 180 },
        radiusKm: { type: 'number', minimum: 1, maximum: 500, default: NEARBY_DEFAULT_KM },
      }),
      response: { 200: ref('FeedPage'), ...errorResponses },
    },
  }, async (req) => {
    const q = req.query as { scope?: 'everyone' | 'following' | 'nearby'; lat?: number; lng?: number; radiusKm?: number; before?: string; limit?: number };
    const scope = q.scope ?? 'everyone';
    const limit = q.limit ?? 20;
    let query = db.selectFrom('posts').leftJoin('rides', 'rides.id', 'posts.ride_id').selectAll('posts')
      .where(visibleTo(req.userId, 'posts.author_id', 'posts.visibility'))
      .where(beforeCursor('posts.created_at', 'posts.id', decodeCursor(q.before)));
    if (scope === 'following') {
      query = query.where(sql<boolean>`(posts.author_id = ${req.userId}::uuid or exists (select 1 from follows ff where ff.follower_id = ${req.userId}::uuid and ff.followee_id = posts.author_id and ff.status = 'accepted'))`);
    } else if (scope === 'nearby') {
      if (q.lat === undefined || q.lng === undefined) throw badRequest('scope=nearby needs lat and lng');
      const radiusM = (q.radiusKm ?? NEARBY_DEFAULT_KM) * 1000;
      const [lat, lng] = [q.lat, q.lng];
      const plat = sql`coalesce(posts.lat, rides.start_lat)`;
      const plng = sql`coalesce(posts.lng, rides.start_lng)`;
      query = query.where(sql<boolean>`(
        ${plat} is not null and ${plng} is not null
        and 6371000 * 2 * asin(sqrt(least(1, power(sin(radians(${lat}::float8 - ${plat}) / 2), 2)
          + cos(radians(${lat}::float8)) * cos(radians(${plat})) * power(sin(radians(${lng}::float8 - ${plng}) / 2), 2)))) <= ${radiusM}::float8
      )`);
    }
    const rows = await query.orderBy('posts.created_at', 'desc').orderBy('posts.id', 'desc').limit(limit + 1).execute();
    const p = page(rows, limit, (r) => encodeCursor(r.created_at, r.id));
    return { items: await hydrate(req.userId, p.items), nextCursor: p.nextCursor };
  });

  // ---- likes -------------------------------------------------------------------------------------------------

  const likeResult = bodyObj({ likeCount: { type: 'integer' }, likedByMe: { type: 'boolean' } });
  const likeState = async (id: string, viewer: string, likedByMe: boolean) => {
    const n = await db.selectFrom('post_likes').select((eb) => eb.fn.countAll<number>().as('n')).where('post_id', '=', id).executeTakeFirstOrThrow();
    return { likeCount: Number(n.n), likedByMe };
  };

  app.post('/v1/posts/:id/like', {
    ...auth, ...lim.write,
    schema: { ...security('posts'), summary: 'Like a post (idempotent)', params: idParams, response: { 200: likeResult, ...errorResponses } },
  }, async (req) => {
    const post = await visiblePost(idOf(req), req.userId);
    const ins = await db.insertInto('post_likes').values({ post_id: post.id, user_id: req.userId }).onConflict((oc) => oc.doNothing()).executeTakeFirst();
    if (Number(ins.numInsertedOrUpdatedRows) > 0) ctx.notifier.like(post.id, req.userId);
    return likeState(post.id, req.userId, true);
  });

  app.delete('/v1/posts/:id/like', {
    ...auth, ...lim.write,
    schema: { ...security('posts'), summary: 'Remove my like (idempotent)', params: idParams, response: { 200: likeResult, ...errorResponses } },
  }, async (req) => {
    const post = await visiblePost(idOf(req), req.userId);
    await db.deleteFrom('post_likes').where('post_id', '=', post.id).where('user_id', '=', req.userId).execute();
    return likeState(post.id, req.userId, false);
  });

  // ---- comments ----------------------------------------------------------------------------------------------

  const commentDto = (c: { id: string; post_id: string; body: string; created_at: Date | string; author_id: string; handle: string; display_name: string; avatar_media_id: string | null }) => ({
    id: c.id, postId: c.post_id, body: c.body, createdAt: iso(c.created_at),
    author: { id: c.author_id, handle: c.handle, displayName: c.display_name, avatarMediaId: c.avatar_media_id },
  });
  const commentRows = () => db.selectFrom('comments').innerJoin('profiles', 'profiles.user_id', 'comments.author_id')
    .select(['comments.id', 'comments.post_id', 'comments.body', 'comments.created_at', 'comments.author_id', 'profiles.handle', 'profiles.display_name', 'profiles.avatar_media_id']);

  app.get('/v1/posts/:id/comments', {
    ...auth,
    schema: {
      ...security('posts'), summary: 'Comments, oldest first', description: 'Comments by people blocked by me, or who blocked me, are left out. `before` here is the cursor from nextCursor, paging forward in time.',
      params: idParams, querystring: cursorQuery(), response: { 200: ref('CommentPage'), ...errorResponses },
    },
  }, async (req) => {
    const q = req.query as { before?: string; limit?: number };
    const post = await visiblePost(idOf(req), req.userId);
    const limit = q.limit ?? 30;
    const rows = await commentRows().where('comments.post_id', '=', post.id).where(afterCursor('comments.created_at', 'comments.id', decodeCursor(q.before)))
      .where(sql<boolean>`not exists (select 1 from blocks bl where (bl.blocker_id = ${req.userId}::uuid and bl.blocked_id = comments.author_id) or (bl.blocker_id = comments.author_id and bl.blocked_id = ${req.userId}::uuid))`)
      .orderBy('comments.created_at').orderBy('comments.id').limit(limit + 1).execute();
    const p = page(rows, limit, (r) => encodeCursor(r.created_at, r.id));
    return { items: p.items.map(commentDto), nextCursor: p.nextCursor };
  });

  app.post('/v1/posts/:id/comments', {
    ...auth, ...lim.write,
    schema: {
      ...security('posts'), summary: 'Comment on a post', params: idParams,
      body: bodyObj({ body: { type: 'string', minLength: 1, maxLength: 1000 } }), response: { 201: ref('Comment'), ...errorResponses },
    },
  }, async (req, reply) => {
    const post = await visiblePost(idOf(req), req.userId);
    const text = (req.body as { body: string }).body.trim();
    if (!text) throw badRequest('Empty comment');
    const row = await db.insertInto('comments').values({ post_id: post.id, author_id: req.userId, body: text }).returning('id').executeTakeFirstOrThrow();
    ctx.notifier.comment(post.id, req.userId, text);
    return reply.code(201).send(commentDto(await commentRows().where('comments.id', '=', row.id).executeTakeFirstOrThrow()));
  });

  app.delete('/v1/comments/:id', {
    ...auth, ...lim.write,
    schema: { ...security('posts'), summary: 'Delete a comment (mine, or any on my post)', params: idParams, response: { 200: ref('Ok'), ...errorResponses } },
  }, async (req) => {
    const c = await db.selectFrom('comments').innerJoin('posts', 'posts.id', 'comments.post_id')
      .select(['comments.id', 'comments.author_id', 'posts.author_id as post_author']).where('comments.id', '=', idOf(req)).executeTakeFirst();
    if (!c) throw notFound('No such comment');
    if (c.author_id !== req.userId && c.post_author !== req.userId) throw forbidden('Not your comment');
    await db.deleteFrom('comments').where('id', '=', c.id).execute();
    return { ok: true };
  });

  // ---- reports -----------------------------------------------------------------------------------------------

  app.post('/v1/reports', {
    ...auth, ...lim.write,
    schema: {
      ...security('moderation'), summary: 'Report a user, post, comment, ride or hazard',
      body: bodyObj({
        targetType: { type: 'string', enum: ['user', 'post', 'comment', 'ride', 'hazard'] },
        targetId: uuid,
        reason: { type: 'string', enum: ['spam', 'abuse', 'nudity', 'violence', 'illegal', 'other'] },
        detail: { type: 'string', maxLength: 1000 },
      }, ['targetType', 'targetId', 'reason']),
      response: { 201: ref('Ok'), ...errorResponses },
    },
  }, async (req, reply) => {
    const b = req.body as { targetType: 'user' | 'post' | 'comment' | 'ride' | 'hazard'; targetId: string; reason: 'spam' | 'abuse' | 'nudity' | 'violence' | 'illegal' | 'other'; detail?: string };
    let exists = false;
    switch (b.targetType) {
      case 'user':
        if (b.targetId === req.userId) throw badRequest('You cannot report yourself');
        exists = !!(await db.selectFrom('users').select('id').where('id', '=', b.targetId).executeTakeFirst());
        break;
      case 'post': exists = !!(await db.selectFrom('posts').select('id').where('id', '=', b.targetId).where(visibleTo(req.userId, 'posts.author_id', 'posts.visibility')).executeTakeFirst()); break;
      case 'comment':
        exists = !!(await db.selectFrom('comments').innerJoin('posts', 'posts.id', 'comments.post_id').select('comments.id').where('comments.id', '=', b.targetId)
          .where(visibleTo(req.userId, 'posts.author_id', 'posts.visibility')).executeTakeFirst());
        break;
      case 'ride': exists = !!(await db.selectFrom('rides').select('id').where('id', '=', b.targetId).where(visibleTo(req.userId, 'rides.owner_id', 'rides.visibility')).executeTakeFirst()); break;
      case 'hazard': exists = !!(await db.selectFrom('hazard_reports').select('id').where('id', '=', b.targetId).executeTakeFirst()); break;
    }
    if (!exists) throw notFound('Nothing to report');
    await db.insertInto('reports').values({ reporter_id: req.userId, target_type: b.targetType, target_id: b.targetId, reason: b.reason, detail: (b.detail ?? '').trim() })
      .onConflict((oc) => oc.columns(['reporter_id', 'target_type', 'target_id']).doNothing()).execute();
    return reply.code(201).send({ ok: true });
  });
}
