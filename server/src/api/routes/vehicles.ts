import type { FastifyInstance } from 'fastify';
import type { Updateable } from 'kysely';
import type { VehiclesTable } from '../../db/types.js';
import { badRequest, conflict, notFound } from '../../errors.js';
import { authenticate, relationship, type ApiContext } from '../common.js';
import { vehicleDto } from '../dto.js';
import { limits } from '../limits.js';
import { userByHandle } from './users.js';
import { arrayOf, bearer, bodyObj, BuildSchema, errorResponses, idParams, nullable, ref, VEHICLE_KINDS } from '../schemas.js';

const MAX_VEHICLES = 50;
const thisYear = new Date().getUTCFullYear();

const fields = {
  kind: { type: 'string', enum: [...VEHICLE_KINDS] },
  make: { type: 'string', minLength: 1, maxLength: 60 },
  model: { type: 'string', minLength: 1, maxLength: 60 },
  year: { ...nullable({ type: 'integer' }), minimum: 1885, maximum: thisYear + 2 },
  nickname: { type: 'string', maxLength: 60 },
  build: BuildSchema,
};

export async function vehicleRoutes(app: FastifyInstance, ctx: ApiContext): Promise<void> {
  const { db } = ctx;
  const lim = limits(ctx.config);
  const auth = { preHandler: [authenticate(ctx.tokens)] };
  const security = { security: bearer, tags: ['vehicles'] };

  const mine = (id: string, owner: string) => db.selectFrom('vehicles').selectAll().where('id', '=', id).where('owner_id', '=', owner).executeTakeFirst();

  app.get('/v1/vehicles', {
    ...auth,
    schema: { ...security, summary: 'My vehicles (garage), sold ones included', response: { 200: arrayOf(ref('Vehicle')), ...errorResponses } },
  }, async (req) => {
    const rows = await db.selectFrom('vehicles').selectAll().where('owner_id', '=', req.userId).orderBy('created_at', 'asc').orderBy('id').execute();
    return rows.map(vehicleDto);
  });

  app.get('/v1/users/:handle/vehicles', {
    ...auth,
    schema: { ...security, summary: 'A user garage', description: 'Empty for a private account the viewer does not follow.', params: bodyObj({ handle: { type: 'string', maxLength: 40 } }), response: { 200: arrayOf(ref('Vehicle')), ...errorResponses } },
  }, async (req) => {
    const { id, rel } = await userByHandle(db, req.userId, (req.params as { handle: string }).handle);
    if (!rel.canViewContent) return [];
    const rows = await db.selectFrom('vehicles').selectAll().where('owner_id', '=', id).orderBy('created_at', 'asc').orderBy('id').execute();
    return rows.map(vehicleDto);
  });

  app.post('/v1/vehicles', {
    ...auth, ...lim.write,
    schema: {
      ...security, summary: 'Add a vehicle',
      description: 'The first unsold vehicle becomes the primary one automatically.',
      body: bodyObj({ ...fields, isPrimary: { type: 'boolean' } }, ['kind', 'make', 'model']),
      response: { 201: ref('Vehicle'), ...errorResponses },
    },
  }, async (req, reply) => {
    const b = req.body as { kind: 'car' | 'motorcycle' | 'ute' | '4wd'; make: string; model: string; year?: number | null; nickname?: string; build?: object; isPrimary?: boolean };
    const row = await db.transaction().execute(async (trx) => {
      const existing = await trx.selectFrom('vehicles').select(['id', 'is_primary', 'sold_at']).where('owner_id', '=', req.userId).execute();
      if (existing.length >= MAX_VEHICLES) throw conflict(`A garage holds at most ${MAX_VEHICLES} vehicles`);
      const hasPrimary = existing.some((v) => v.is_primary && !v.sold_at);
      const makePrimary = b.isPrimary === true || !hasPrimary;
      if (makePrimary) await trx.updateTable('vehicles').set({ is_primary: false }).where('owner_id', '=', req.userId).where('is_primary', '=', true).execute();
      return trx.insertInto('vehicles').values({
        owner_id: req.userId, kind: b.kind, make: b.make.trim(), model: b.model.trim(), year: b.year ?? null, nickname: (b.nickname ?? '').trim(),
        build: JSON.stringify(b.build ?? {}), is_primary: makePrimary,
      }).returningAll().executeTakeFirstOrThrow();
    });
    return reply.code(201).send(vehicleDto(row));
  });

  app.get('/v1/vehicles/:id', {
    ...auth,
    schema: { ...security, summary: 'One vehicle', params: idParams, response: { 200: ref('Vehicle'), ...errorResponses } },
  }, async (req) => {
    const v = await db.selectFrom('vehicles').selectAll().where('id', '=', (req.params as { id: string }).id).executeTakeFirst();
    if (!v) throw notFound('No such vehicle');
    if (v.owner_id !== req.userId && !(await relationship(db, req.userId, v.owner_id)).canViewContent) throw notFound('No such vehicle');
    return vehicleDto(v);
  });

  app.patch('/v1/vehicles/:id', {
    ...auth, ...lim.write,
    schema: {
      ...security, summary: 'Edit a vehicle', description: '`build` replaces the whole build object.',
      params: idParams, body: bodyObj(fields, []), response: { 200: ref('Vehicle'), ...errorResponses },
    },
  }, async (req) => {
    const id = (req.params as { id: string }).id;
    const b = req.body as { kind?: 'car' | 'motorcycle' | 'ute' | '4wd'; make?: string; model?: string; year?: number | null; nickname?: string; build?: object };
    if (!(await mine(id, req.userId))) throw notFound('No such vehicle');
    const set: Updateable<VehiclesTable> = { updated_at: new Date() };
    if (b.kind !== undefined) set.kind = b.kind;
    if (b.make !== undefined) set.make = b.make.trim();
    if (b.model !== undefined) set.model = b.model.trim();
    if (b.year !== undefined) set.year = b.year;
    if (b.nickname !== undefined) set.nickname = b.nickname.trim();
    if (b.build !== undefined) set.build = JSON.stringify(b.build);
    const row = await db.updateTable('vehicles').set(set).where('id', '=', id).where('owner_id', '=', req.userId).returningAll().executeTakeFirstOrThrow();
    return vehicleDto(row);
  });

  app.delete('/v1/vehicles/:id', {
    ...auth, ...lim.write,
    schema: { ...security, summary: 'Delete a vehicle', description: 'Rides keep their stats but lose the vehicle link; posts about the vehicle are removed.', params: idParams, response: { 200: ref('Ok'), ...errorResponses } },
  }, async (req) => {
    const res = await db.deleteFrom('vehicles').where('id', '=', (req.params as { id: string }).id).where('owner_id', '=', req.userId).executeTakeFirst();
    if (Number(res.numDeletedRows) === 0) throw notFound('No such vehicle');
    return { ok: true };
  });

  app.post('/v1/vehicles/:id/primary', {
    ...auth, ...lim.write,
    schema: { ...security, summary: 'Make this my primary vehicle', params: idParams, response: { 200: ref('Vehicle'), ...errorResponses } },
  }, async (req) => {
    const id = (req.params as { id: string }).id;
    const v = await mine(id, req.userId);
    if (!v) throw notFound('No such vehicle');
    if (v.sold_at) throw badRequest('A sold vehicle cannot be primary');
    const row = await db.transaction().execute(async (trx) => {
      await trx.updateTable('vehicles').set({ is_primary: false }).where('owner_id', '=', req.userId).where('is_primary', '=', true).execute();
      return trx.updateTable('vehicles').set({ is_primary: true, updated_at: new Date() }).where('id', '=', id).returningAll().executeTakeFirstOrThrow();
    });
    return vehicleDto(row);
  });

  app.post('/v1/vehicles/:id/sold', {
    ...auth, ...lim.write,
    schema: {
      ...security, summary: 'Mark a vehicle sold', description: 'A sold vehicle stops being primary and stays in the garage history.',
      params: idParams, body: bodyObj({ soldAt: { type: 'string', format: 'date-time' } }, []), response: { 200: ref('Vehicle'), ...errorResponses },
    },
  }, async (req) => {
    const id = (req.params as { id: string }).id;
    const v = await mine(id, req.userId);
    if (!v) throw notFound('No such vehicle');
    const soldAt = (req.body as { soldAt?: string } | null)?.soldAt;
    const when = soldAt ? new Date(soldAt) : new Date();
    if (when.getTime() > Date.now() + 86_400_000) throw badRequest('soldAt cannot be in the future');
    const row = await db.updateTable('vehicles').set({ sold_at: when, is_primary: false, updated_at: new Date() }).where('id', '=', id).returningAll().executeTakeFirstOrThrow();
    return vehicleDto(row);
  });
}
