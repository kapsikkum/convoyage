import type { FastifyInstance } from 'fastify';
import { sql, type Kysely, type Updateable } from 'kysely';
import type { ProfilesTable } from '../../db/types.js';
import type { DB } from '../../db/index.js';
import { badRequest, conflict, notFound } from '../../errors.js';
import { authenticate, iso, relationship, vehicleBadges, type ApiContext } from '../common.js';
import { handleProblem, isUniqueViolation, vehicleDto } from '../dto.js';
import { limits } from '../limits.js';
import { arrayOf, bearer, bodyObj, errorResponses, nullable, ref, uuid } from '../schemas.js';

const handleParam = bodyObj({ handle: { type: 'string', minLength: 1, maxLength: 40 } });

async function counts(db: Kysely<DB>, userId: string) {
  const [followers, following] = await Promise.all([
    db.selectFrom('follows').select((eb) => eb.fn.countAll<number>().as('n')).where('followee_id', '=', userId).where('status', '=', 'accepted').executeTakeFirstOrThrow(),
    db.selectFrom('follows').select((eb) => eb.fn.countAll<number>().as('n')).where('follower_id', '=', userId).where('status', '=', 'accepted').executeTakeFirstOrThrow(),
  ]);
  return { followerCount: Number(followers.n), followingCount: Number(following.n) };
}

async function primaryVehicle(db: Kysely<DB>, userId: string) {
  const v = await db.selectFrom('vehicles').selectAll().where('owner_id', '=', userId).where('is_primary', '=', true).where('sold_at', 'is', null).executeTakeFirst();
  return v ? vehicleDto(v) : null;
}

/** Resolve a handle to a user id the viewer may interact with: unknown and blocked-either-way both answer 404. */
export async function userByHandle(db: Kysely<DB>, viewer: string, handle: string) {
  const row = await db.selectFrom('profiles').select(['user_id', 'handle']).where('handle', '=', handle.toLowerCase()).executeTakeFirst();
  if (!row) throw notFound('No such user');
  const rel = await relationship(db, viewer, row.user_id);
  // Someone who blocked me does not exist for me. Someone I blocked stays visible, so I can unblock them.
  if (rel.blockedMe) throw notFound('No such user');
  return { id: row.user_id, rel };
}

async function loadMe(db: Kysely<DB>, userId: string) {
  const p = await db.selectFrom('profiles').innerJoin('users', 'users.id', 'profiles.user_id')
    .select(['profiles.user_id as id', 'handle', 'handle_chosen', 'display_name', 'bio', 'avatar_media_id', 'is_private', 'profiles.created_at as created_at', 'users.email as email', 'users.kind as kind'])
    .where('profiles.user_id', '=', userId).executeTakeFirst();
  if (!p) throw notFound('No such user');
  const pending = await db.selectFrom('follows').select((eb) => eb.fn.countAll<number>().as('n')).where('followee_id', '=', userId).where('status', '=', 'pending').executeTakeFirstOrThrow();
  return {
    id: p.id, handle: p.handle, handleChosen: p.handle_chosen, displayName: p.display_name, bio: p.bio, avatarMediaId: p.avatar_media_id,
    isPrivate: p.is_private, email: p.email, kind: p.kind, createdAt: iso(p.created_at), ...(await counts(db, userId)),
    pendingRequestCount: Number(pending.n), primaryVehicle: await primaryVehicle(db, userId),
  };
}

export async function userRoutes(app: FastifyInstance, ctx: ApiContext): Promise<void> {
  const { db } = ctx;
  const lim = limits(ctx.config);
  const auth = { preHandler: [authenticate(ctx.tokens)] };
  const security = { security: bearer, tags: ['users'] };

  app.get('/v1/me', { ...auth, schema: { ...security, summary: 'My profile', response: { 200: ref('Me'), ...errorResponses } } },
    async (req) => loadMe(db, req.userId));

  app.patch('/v1/me', {
    ...auth, ...lim.write,
    schema: {
      ...security, summary: 'Update my profile',
      description: 'Going from private to public approves every pending follow request.',
      body: bodyObj({
        handle: { type: 'string', minLength: 3, maxLength: 30 },
        displayName: { type: 'string', minLength: 1, maxLength: 60 },
        bio: nullable({ type: 'string', maxLength: 300 }),
        isPrivate: { type: 'boolean' },
        avatarMediaId: { ...nullable(uuid), description: 'A media id I uploaded, or null to clear.' },
      }, []),
      response: { 200: ref('Me'), ...errorResponses },
    },
  }, async (req) => {
    const body = req.body as { handle?: string; displayName?: string; bio?: string | null; isPrivate?: boolean; avatarMediaId?: string | null };
    const set: Updateable<ProfilesTable> = { updated_at: new Date() };
    if (body.handle !== undefined) {
      const handle = body.handle.toLowerCase();
      const problem = handleProblem(handle);
      if (problem) throw badRequest(problem);
      set.handle = handle;
      set.handle_chosen = true;
    }
    if (body.displayName !== undefined) set.display_name = body.displayName.trim();
    if (body.bio !== undefined) set.bio = (body.bio ?? '').trim();
    if (body.isPrivate !== undefined) set.is_private = body.isPrivate;
    if (body.avatarMediaId !== undefined) {
      if (body.avatarMediaId) {
        const m = await db.selectFrom('media').select('id').where('id', '=', body.avatarMediaId).where('owner_id', '=', req.userId).executeTakeFirst();
        if (!m) throw badRequest('Unknown avatar media');
      }
      set.avatar_media_id = body.avatarMediaId;
    }
    try {
      await db.transaction().execute(async (trx) => {
        await trx.updateTable('profiles').set(set).where('user_id', '=', req.userId).execute();
        if (body.isPrivate === false) {
          await trx.updateTable('follows').set({ status: 'accepted' }).where('followee_id', '=', req.userId).where('status', '=', 'pending').execute();
        }
      });
    } catch (err) {
      if (isUniqueViolation(err)) throw conflict('That handle is taken');
      throw err;
    }
    if (body.displayName !== undefined) ctx.convoy.renameUser(req.userId, body.displayName.trim());
    return loadMe(db, req.userId);
  });

  app.delete('/v1/me', {
    ...auth, ...lim.write,
    schema: { ...security, summary: 'Delete my account and everything it owns', response: { 200: ref('Ok'), ...errorResponses } },
  }, async (req) => {
    const files = await db.selectFrom('media').select(['storage_key', 'thumb_key']).where('owner_id', '=', req.userId).execute();
    await db.deleteFrom('users').where('id', '=', req.userId).execute(); // cascades to every table
    for (const f of files) {
      await ctx.media.delete(f.storage_key).catch(() => {});
      if (f.thumb_key) await ctx.media.delete(f.thumb_key).catch(() => {});
    }
    return { ok: true };
  });

  app.get('/v1/handles/:handle/availability', {
    ...auth,
    schema: { ...security, summary: 'Is this handle free?', params: handleParam, response: { 200: ref('HandleAvailability'), ...errorResponses } },
  }, async (req) => {
    const handle = (req.params as { handle: string }).handle.toLowerCase();
    const problem = handleProblem(handle);
    if (problem) return { handle, available: false, reason: problem };
    const taken = await db.selectFrom('profiles').select('user_id').where('handle', '=', handle).executeTakeFirst();
    // My own current handle counts as available to me.
    const available = !taken || taken.user_id === req.userId;
    return { handle, available, reason: available ? null : 'That handle is taken' };
  });

  app.get('/v1/users/:handle', {
    ...auth,
    schema: { ...security, summary: 'A profile', description: 'A private account shows its name, avatar and counts; `canViewContent` says whether the viewer may see its rides, vehicles and posts. Blocked users (either way) are 404.', params: handleParam, response: { 200: ref('Profile'), ...errorResponses } },
  }, async (req) => {
    const { id, rel } = await userByHandle(db, req.userId, (req.params as { handle: string }).handle);
    const p = await db.selectFrom('profiles').selectAll().where('user_id', '=', id).executeTakeFirstOrThrow();
    return {
      id, handle: p.handle, displayName: p.display_name, bio: p.bio, avatarMediaId: p.avatar_media_id, isPrivate: p.is_private,
      createdAt: iso(p.created_at), ...(await counts(db, id)),
      relationship: { following: rel.following, followedBy: rel.followedBy, blockedByMe: rel.blockedByMe },
      canViewContent: rel.canViewContent,
      primaryVehicle: rel.canViewContent ? await primaryVehicle(db, id) : null,
    };
  });

  // ---- follows -----------------------------------------------------------------------------------------------

  app.post('/v1/users/:handle/follow', {
    ...auth, ...lim.write,
    schema: { ...security, summary: 'Follow (or request to follow a private account)', params: handleParam, response: { 200: ref('FollowResult'), ...errorResponses } },
  }, async (req) => {
    const { id, rel } = await userByHandle(db, req.userId, (req.params as { handle: string }).handle);
    if (id === req.userId) throw badRequest('You cannot follow yourself');
    if (rel.blockedByMe) throw conflict('Unblock this user first');
    const target = await db.selectFrom('profiles').select('is_private').where('user_id', '=', id).executeTakeFirstOrThrow();
    await db.insertInto('follows').values({ follower_id: req.userId, followee_id: id, status: target.is_private ? 'pending' : 'accepted' })
      .onConflict((oc) => oc.columns(['follower_id', 'followee_id']).doNothing()).execute();
    const row = await db.selectFrom('follows').select('status').where('follower_id', '=', req.userId).where('followee_id', '=', id).executeTakeFirstOrThrow();
    if (rel.following === 'none') ctx.notifier.follow(req.userId, id, row.status);
    return { status: row.status };
  });

  app.delete('/v1/users/:handle/follow', {
    ...auth, ...lim.write,
    schema: { ...security, summary: 'Unfollow, or cancel a pending request', params: handleParam, response: { 200: ref('FollowResult'), ...errorResponses } },
  }, async (req) => {
    const { id } = await userByHandle(db, req.userId, (req.params as { handle: string }).handle);
    await db.deleteFrom('follows').where('follower_id', '=', req.userId).where('followee_id', '=', id).execute();
    return { status: 'none' as const };
  });

  app.get('/v1/me/follow-requests', {
    ...auth,
    schema: { ...security, summary: 'Pending requests to follow me', response: { 200: arrayOf(ref('FollowRequest')), ...errorResponses } },
  }, async (req) => {
    const rows = await db.selectFrom('follows').innerJoin('profiles', 'profiles.user_id', 'follows.follower_id')
      .select(['follows.follower_id as id', 'handle', 'display_name', 'avatar_media_id', 'follows.created_at as created_at'])
      .where('follows.followee_id', '=', req.userId).where('follows.status', '=', 'pending')
      .where(sql<boolean>`not exists (select 1 from blocks b where (b.blocker_id = ${req.userId}::uuid and b.blocked_id = follows.follower_id) or (b.blocker_id = follows.follower_id and b.blocked_id = ${req.userId}::uuid))`)
      .orderBy('follows.created_at', 'desc').limit(200).execute();
    const badges = await vehicleBadges(db, req.userId, rows.map((r) => r.id));
    return rows.map((r) => ({ user: { id: r.id, handle: r.handle, displayName: r.display_name, avatarMediaId: r.avatar_media_id, primaryVehicle: badges.get(r.id) ?? null }, createdAt: iso(r.created_at) }));
  });

  // Followers / following of a profile, newest first. Empty for a private account the viewer does not follow; anyone blocked either way with the viewer is left out.
  for (const [dir, summary] of [['followers', 'Accounts following this user'], ['following', 'Accounts this user follows']] as const) {
    app.get(`/v1/users/:handle/${dir}`, {
      ...auth,
      schema: { ...security, summary, description: 'Empty for a private account the viewer does not follow. Only accepted follows; at most 200.', params: handleParam, response: { 200: arrayOf(ref('UserRef')), ...errorResponses } },
    }, async (req) => {
      const { id, rel } = await userByHandle(db, req.userId, (req.params as { handle: string }).handle);
      if (!rel.canViewContent) return [];
      const otherCol = dir === 'followers' ? 'follows.follower_id' : 'follows.followee_id';
      const ownCol = dir === 'followers' ? 'follows.followee_id' : 'follows.follower_id';
      const rows = await db.selectFrom('follows').innerJoin('profiles', 'profiles.user_id', otherCol)
        .select([`${otherCol} as id`, 'handle', 'display_name', 'avatar_media_id'])
        .where(ownCol, '=', id).where('follows.status', '=', 'accepted')
        .where(sql<boolean>`not exists (select 1 from blocks b where (b.blocker_id = ${req.userId}::uuid and b.blocked_id = ${sql.ref(otherCol)}) or (b.blocker_id = ${sql.ref(otherCol)} and b.blocked_id = ${req.userId}::uuid))`)
        .orderBy('follows.created_at', 'desc').limit(200).execute();
      const badges = await vehicleBadges(db, req.userId, rows.map((r) => r.id));
      return rows.map((r) => ({ id: r.id, handle: r.handle, displayName: r.display_name, avatarMediaId: r.avatar_media_id, primaryVehicle: badges.get(r.id) ?? null }));
    });
  }

  const requestParams = bodyObj({ userId: uuid });

  app.post('/v1/me/follow-requests/:userId/approve', {
    ...auth, ...lim.write,
    schema: { ...security, summary: 'Approve a follow request', params: requestParams, response: { 200: ref('Ok'), ...errorResponses } },
  }, async (req) => {
    const res = await db.updateTable('follows').set({ status: 'accepted' })
      .where('followee_id', '=', req.userId).where('follower_id', '=', (req.params as { userId: string }).userId).where('status', '=', 'pending').executeTakeFirst();
    if (Number(res.numUpdatedRows) === 0) throw notFound('No such request');
    return { ok: true };
  });

  app.delete('/v1/me/follow-requests/:userId', {
    ...auth, ...lim.write,
    schema: { ...security, summary: 'Decline a follow request', params: requestParams, response: { 200: ref('Ok'), ...errorResponses } },
  }, async (req) => {
    await db.deleteFrom('follows').where('followee_id', '=', req.userId).where('follower_id', '=', (req.params as { userId: string }).userId).where('status', '=', 'pending').execute();
    return { ok: true };
  });

  // ---- blocks ------------------------------------------------------------------------------------------------

  app.post('/v1/users/:handle/block', {
    ...auth, ...lim.write,
    schema: { ...security, summary: 'Block a user', description: 'Also removes any follow between us in both directions.', params: handleParam, response: { 200: ref('Ok'), ...errorResponses } },
  }, async (req) => {
    const row = await db.selectFrom('profiles').select('user_id').where('handle', '=', (req.params as { handle: string }).handle.toLowerCase()).executeTakeFirst();
    if (!row) throw notFound('No such user');
    if (row.user_id === req.userId) throw badRequest('You cannot block yourself');
    await db.transaction().execute(async (trx) => {
      await trx.insertInto('blocks').values({ blocker_id: req.userId, blocked_id: row.user_id }).onConflict((oc) => oc.doNothing()).execute();
      await trx.deleteFrom('follows').where((eb) => eb.or([
        eb.and([eb('follower_id', '=', req.userId), eb('followee_id', '=', row.user_id)]),
        eb.and([eb('follower_id', '=', row.user_id), eb('followee_id', '=', req.userId)]),
      ])).execute();
    });
    return { ok: true };
  });

  app.delete('/v1/users/:handle/block', {
    ...auth, ...lim.write,
    schema: { ...security, summary: 'Unblock a user', params: handleParam, response: { 200: ref('Ok'), ...errorResponses } },
  }, async (req) => {
    const row = await db.selectFrom('profiles').select('user_id').where('handle', '=', (req.params as { handle: string }).handle.toLowerCase()).executeTakeFirst();
    if (!row) throw notFound('No such user');
    await db.deleteFrom('blocks').where('blocker_id', '=', req.userId).where('blocked_id', '=', row.user_id).execute();
    return { ok: true };
  });

  app.get('/v1/me/blocks', {
    ...auth,
    schema: { ...security, summary: 'Users I have blocked', response: { 200: arrayOf(ref('UserRef')), ...errorResponses } },
  }, async (req) => {
    const rows = await db.selectFrom('blocks').innerJoin('profiles', 'profiles.user_id', 'blocks.blocked_id')
      .select(['profiles.user_id as id', 'handle', 'display_name', 'avatar_media_id']).where('blocks.blocker_id', '=', req.userId).orderBy('blocks.created_at', 'desc').execute();
    const badges = await vehicleBadges(db, req.userId, rows.map((r) => r.id));
    return rows.map((r) => ({ id: r.id, handle: r.handle, displayName: r.display_name, avatarMediaId: r.avatar_media_id, primaryVehicle: badges.get(r.id) ?? null }));
  });
}
