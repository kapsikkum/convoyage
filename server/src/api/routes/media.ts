import type { FastifyInstance } from 'fastify';
import { createHash, randomUUID } from 'node:crypto';
import { badRequest, notFound } from '../../errors.js';
import { processImage } from '../../media/process.js';
import { authenticate, type ApiContext } from '../common.js';
import { mediaDto } from '../dto.js';
import { limits } from '../limits.js';
import { bearer, errorResponses, idParams, ref } from '../schemas.js';

export async function mediaRoutes(app: FastifyInstance, ctx: ApiContext): Promise<void> {
  const { db } = ctx;
  const lim = limits(ctx.config);
  const auth = { preHandler: [authenticate(ctx.tokens)] };
  const security = { security: bearer, tags: ['media'] };

  app.post('/v1/media', {
    ...auth, ...lim.write,
    schema: {
      ...security, summary: 'Upload an image (multipart/form-data, field "file")',
      description: 'JPEG, PNG or WebP up to 15 MB, identified by magic bytes. The image is re-encoded: rotated upright, longest edge capped at 2560 px, and ALL metadata removed (GPS, camera, name). Only the capture time (EXIF DateTimeOriginal) survives, as `takenAt` on the media record. A 512 px JPEG thumbnail is generated. HEIC is refused: convert to JPEG on the device.',
      response: { 201: ref('Media'), 413: ref('Error'), 415: ref('Error'), ...errorResponses },
    },
  }, async (req, reply) => {
    if (!req.isMultipart()) throw badRequest('Send multipart/form-data with a "file" field');
    const part = await req.file();
    if (!part || part.fieldname !== 'file') throw badRequest('Send multipart/form-data with a "file" field');
    const bytes = await part.toBuffer(); // throws 413 past the 15 MB limit
    if (bytes.length === 0) throw badRequest('Empty file');
    const img = await processImage(bytes);

    const id = randomUUID();
    const key = `media/${id.slice(0, 2)}/${id}.${img.ext}`;
    const thumbKey = `media/${id.slice(0, 2)}/${id}_thumb.jpg`;
    await ctx.media.put(key, img.data, img.mime);
    await ctx.media.put(thumbKey, img.thumb, 'image/jpeg');
    try {
      const row = await db.insertInto('media').values({
        id, owner_id: req.userId, mime: img.mime, bytes: img.data.length, width: img.width, height: img.height,
        storage_key: key, thumb_key: thumbKey, sha256: createHash('sha256').update(img.data).digest('hex'), taken_at: img.takenAt,
      }).returningAll().executeTakeFirstOrThrow();
      return reply.code(201).send(mediaDto(row));
    } catch (err) {
      await ctx.media.delete(key).catch(() => {});
      await ctx.media.delete(thumbKey).catch(() => {});
      throw err;
    }
  });

  // Media ids are random UUIDs that are only ever shown to people allowed to see the content they belong to,
  // so any signed-in user holding an id may fetch the bytes.
  const serve = async (id: string, thumb: boolean) => {
    const row = await db.selectFrom('media').selectAll().where('id', '=', id).executeTakeFirst();
    const key = thumb ? row?.thumb_key ?? row?.storage_key : row?.storage_key;
    const data = row && key ? await ctx.media.get(key) : null;
    if (!row || !data) throw notFound('No such media');
    return { data, mime: thumb ? 'image/jpeg' : row.mime };
  };

  for (const thumb of [false, true]) {
    app.get(thumb ? '/v1/media/:id/thumb' : '/v1/media/:id', {
      ...auth,
      schema: {
        ...security, summary: thumb ? 'Thumbnail (JPEG, 512 px)' : 'The image', params: idParams,
        response: { 404: ref('Error'), 401: ref('Error') },
      },
    }, async (req, reply) => {
      const { data, mime } = await serve((req.params as { id: string }).id, thumb);
      return reply.type(mime).header('cache-control', 'private, max-age=31536000, immutable').header('x-content-type-options', 'nosniff').send(data);
    });
  }
}
