import type { FastifyInstance } from 'fastify';
import { sql } from 'kysely';
import { authenticate, beforeCursor, decodeCursor, encodeCursor, iso, isoOrNull, page, visibleTo, type ApiContext } from '../common.js';
import { mediaDto } from '../dto.js';
import { userByHandle } from './users.js';
import { bearer, bodyObj, cursorQuery, errorResponses, ref } from '../schemas.js';

export async function photoRoutes(app: FastifyInstance, ctx: ApiContext): Promise<void> {
  const { db } = ctx;
  const auth = { preHandler: [authenticate(ctx.tokens)] };

  /**
   * A user's photos the viewer may see: images attached to their rides or used in their posts, newest upload first.
   * Each photo appears once, with the first visible ride and post it belongs to.
   */
  async function photosOf(owner: string, viewer: string, q: { before?: string; limit?: number }) {
    const limit = q.limit ?? 24;
    const rideVisible = sql`(${visibleTo(viewer, 'rides.owner_id', 'rides.visibility')} and (rides.owner_id = ${viewer}::uuid or rides.status = 'finished'))`;
    const postVisible = visibleTo(viewer, 'posts.author_id', 'posts.visibility');
    const rows = await db.selectFrom('media').selectAll('media')
      .select(sql<string | null>`(select rp.ride_id from ride_photos rp join rides on rides.id = rp.ride_id where rp.media_id = media.id and ${rideVisible} order by rp.created_at, rp.id limit 1)`.as('ride_id'))
      .select(sql<number | null>`(select rp.lat from ride_photos rp join rides on rides.id = rp.ride_id where rp.media_id = media.id and ${rideVisible} order by rp.created_at, rp.id limit 1)`.as('lat'))
      .select(sql<number | null>`(select rp.lng from ride_photos rp join rides on rides.id = rp.ride_id where rp.media_id = media.id and ${rideVisible} order by rp.created_at, rp.id limit 1)`.as('lng'))
      .select(sql<string | null>`(select pm.post_id from post_media pm join posts on posts.id = pm.post_id where pm.media_id = media.id and ${postVisible} order by posts.created_at, posts.id limit 1)`.as('post_id'))
      .where('media.owner_id', '=', owner)
      .where(sql<boolean>`(
        exists (select 1 from ride_photos rp join rides on rides.id = rp.ride_id where rp.media_id = media.id and ${rideVisible})
        or exists (select 1 from post_media pm join posts on posts.id = pm.post_id where pm.media_id = media.id and ${postVisible})
      )`)
      .where(beforeCursor('media.created_at', 'media.id', decodeCursor(q.before)))
      .orderBy('media.created_at', 'desc').orderBy('media.id', 'desc').limit(limit + 1).execute();
    const p = page(rows, limit, (r) => encodeCursor(r.created_at, r.id));
    return {
      items: p.items.map((r) => ({
        media: mediaDto(r), rideId: r.ride_id, postId: r.post_id, lat: r.lat, lng: r.lng, takenAt: isoOrNull(r.taken_at), createdAt: iso(r.created_at),
      })),
      nextCursor: p.nextCursor,
    };
  }

  app.get('/v1/me/photos', {
    ...auth,
    schema: {
      security: bearer, tags: ['users'], summary: 'My photos (ride photos and photo posts), newest first',
      querystring: cursorQuery(), response: { 200: ref('PhotoPage'), ...errorResponses },
    },
  }, async (req) => photosOf(req.userId, req.userId, req.query as { before?: string; limit?: number }));

  app.get('/v1/users/:handle/photos', {
    ...auth,
    schema: {
      security: bearer, tags: ['users'], summary: 'A user photos that I may see',
      description: 'Respects ride and post visibility, private accounts and blocks (a blocked pair sees nothing).',
      params: bodyObj({ handle: { type: 'string', maxLength: 40 } }), querystring: cursorQuery(), response: { 200: ref('PhotoPage'), ...errorResponses },
    },
  }, async (req) => {
    const { id } = await userByHandle(db, req.userId, (req.params as { handle: string }).handle);
    return photosOf(id, req.userId, req.query as { before?: string; limit?: number });
  });
}
