import { timingSafeEqual } from 'node:crypto';
import type { FastifyInstance, FastifyRequest } from 'fastify';
import { badRequest, notFound, unauthorized } from '../../errors.js';
import { iso, type ApiContext } from '../common.js';

const safeEqual = (a: string, b: string): boolean => {
  const x = Buffer.from(a);
  const y = Buffer.from(b);
  return x.length === y.length && timingSafeEqual(x, y);
};

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/**
 * Moderation for the operator: `Authorization: Bearer <ADMIN_TOKEN>`. Not part of the OpenAPI document (apps never call it).
 * Only registered when ADMIN_TOKEN is set (32+ characters).
 *
 *   GET  /admin/reports?status=open            reports with the reporter and a one-line description of the target
 *   POST /admin/reports/:id   {status}         actioned | dismissed | open
 *   POST /admin/users/:id/disable              blocks sign-in, revokes sessions; open sockets close within a minute
 *   POST /admin/users/:id/enable
 *   DELETE /admin/posts/:id, /admin/comments/:id
 */
export async function adminRoutes(app: FastifyInstance, ctx: ApiContext): Promise<void> {
  const token = ctx.config.adminToken;
  if (!token) return;
  const { db } = ctx;

  const guard = async (req: FastifyRequest): Promise<void> => {
    const header = req.headers.authorization ?? '';
    const given = header.startsWith('Bearer ') ? header.slice(7) : '';
    if (!given || !safeEqual(given, token)) throw unauthorized('Admin token required');
  };
  const opts = { preHandler: [guard], schema: { hide: true } };
  const idOf = (req: FastifyRequest): string => {
    const id = (req.params as { id: string }).id;
    if (!UUID.test(id)) throw badRequest('Invalid id');
    return id;
  };

  app.get('/admin/reports', opts, async (req) => {
    const status = (req.query as { status?: string }).status ?? 'open';
    if (!['open', 'actioned', 'dismissed'].includes(status)) throw badRequest('status must be open, actioned or dismissed');
    const rows = await db.selectFrom('reports').innerJoin('profiles', 'profiles.user_id', 'reports.reporter_id')
      .select(['reports.id as id', 'reports.target_type as target_type', 'reports.target_id as target_id', 'reports.reason as reason', 'reports.detail as detail',
        'reports.status as status', 'reports.created_at as created_at', 'profiles.handle as reporter'])
      .where('reports.status', '=', status as 'open').orderBy('reports.created_at', 'desc').limit(200).execute();
    const out = [];
    for (const r of rows) {
      let target: string | null = null;
      if (r.target_type === 'post') {
        const p = await db.selectFrom('posts').select(['body', 'author_id']).where('id', '=', r.target_id).executeTakeFirst();
        target = p ? `post by ${p.author_id}: ${p.body.slice(0, 120)}` : null;
      } else if (r.target_type === 'comment') {
        const c = await db.selectFrom('comments').select(['body', 'author_id']).where('id', '=', r.target_id).executeTakeFirst();
        target = c ? `comment by ${c.author_id}: ${c.body.slice(0, 120)}` : null;
      } else if (r.target_type === 'user') {
        const u = await db.selectFrom('profiles').select('handle').where('user_id', '=', r.target_id).executeTakeFirst();
        target = u ? `user @${u.handle}` : null;
      }
      out.push({ id: r.id, targetType: r.target_type, targetId: r.target_id, target, reason: r.reason, detail: r.detail, status: r.status, reporter: r.reporter, createdAt: iso(r.created_at) });
    }
    return out;
  });

  app.post('/admin/reports/:id', opts, async (req) => {
    const id = idOf(req);
    const status = (req.body as { status?: string } | null)?.status;
    if (status !== 'actioned' && status !== 'dismissed' && status !== 'open') throw badRequest('status must be open, actioned or dismissed');
    const res = await db.updateTable('reports').set({ status }).where('id', '=', id).executeTakeFirst();
    if (!Number(res.numUpdatedRows)) throw notFound('No such report');
    return { ok: true };
  });

  app.post('/admin/users/:id/disable', opts, async (req) => {
    const id = idOf(req);
    const res = await db.updateTable('users').set({ disabled_at: new Date() }).where('id', '=', id).executeTakeFirst();
    if (!Number(res.numUpdatedRows)) throw notFound('No such user');
    await db.updateTable('refresh_tokens').set({ revoked_at: new Date() }).where('user_id', '=', id).where('revoked_at', 'is', null).execute();
    return { ok: true };
  });

  app.post('/admin/users/:id/enable', opts, async (req) => {
    const id = idOf(req);
    const res = await db.updateTable('users').set({ disabled_at: null }).where('id', '=', id).executeTakeFirst();
    if (!Number(res.numUpdatedRows)) throw notFound('No such user');
    return { ok: true };
  });

  app.delete('/admin/posts/:id', opts, async (req) => {
    const res = await db.deleteFrom('posts').where('id', '=', idOf(req)).executeTakeFirst();
    if (!Number(res.numDeletedRows)) throw notFound('No such post');
    return { ok: true };
  });

  app.delete('/admin/comments/:id', opts, async (req) => {
    const res = await db.deleteFrom('comments').where('id', '=', idOf(req)).executeTakeFirst();
    if (!Number(res.numDeletedRows)) throw notFound('No such comment');
    return { ok: true };
  });
}
