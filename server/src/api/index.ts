import multipart from '@fastify/multipart';
import rateLimit from '@fastify/rate-limit';
import swagger from '@fastify/swagger';
import type { FastifyInstance } from 'fastify';
import { STATUS_CODES } from 'node:http';
import { HttpError } from '../errors.js';
import type { ApiContext } from './common.js';
import { MAX_UPLOAD_BYTES } from '../media/process.js';
import { authRoutes } from './routes/auth.js';
import { hazardRoutes } from './routes/hazards.js';
import { mediaRoutes } from './routes/media.js';
import { postRoutes } from './routes/posts.js';
import { rideRoutes } from './routes/rides.js';
import { userRoutes } from './routes/users.js';
import { vehicleRoutes } from './routes/vehicles.js';
import { registerSchemas } from './schemas.js';

export type { ApiContext, ConvoyLink } from './common.js';

/** Adds the multipart request body, which route schemas cannot describe, to the generated document. */
function patchOpenApi(doc: Record<string, any>): void {
  const post = doc.paths?.['/v1/media']?.post;
  if (post) {
    post.requestBody = {
      required: true,
      content: { 'multipart/form-data': { schema: { type: 'object', required: ['file'], properties: { file: { type: 'string', contentEncoding: 'binary', description: 'JPEG, PNG or WebP, up to 15 MB.' } } } } },
    };
  }
  const text: Record<string, string> = {
    200: 'OK', 201: 'Created', 202: 'Accepted', 400: 'Bad request', 401: 'Missing, invalid or expired credentials', 403: 'Forbidden',
    404: 'Not found (also used when the viewer may not see the thing)', 409: 'Conflict with the current state', 413: 'Too large',
    415: 'Unsupported media type', 429: 'Rate limited', 501: 'Not configured on this server',
  };
  for (const item of Object.values(doc.paths ?? {}) as Record<string, any>[]) {
    for (const op of Object.values(item)) {
      for (const [code, res] of Object.entries((op as any).responses ?? {}) as [string, any][]) {
        if (res.description === 'Default Response') res.description = text[code] ?? 'Response';
      }
    }
  }
  for (const path of ['/v1/media/{id}', '/v1/media/{id}/thumb']) {
    const get = doc.paths?.[path]?.get;
    if (get) get.responses = { 200: { description: 'The image bytes.', content: { 'image/*': { schema: { type: 'string', contentEncoding: 'binary' } } } }, ...get.responses };
  }
}

/** Everything under /auth and /v1, the OpenAPI document and the HTTP-level plumbing. */
export async function registerApi(app: FastifyInstance, ctx: ApiContext, version: string): Promise<void> {
  app.setErrorHandler((error: Error & { statusCode?: number }, request, reply) => {
    const status = error.statusCode && error.statusCode >= 400 && error.statusCode < 600 ? error.statusCode : 500;
    // Deliberate errors (HttpError) say what they mean; anything else that is a 5xx stays opaque.
    if (status >= 500 && !(error instanceof HttpError)) {
      request.log.error({ err: error }, 'request failed');
      return reply.code(500).send({ statusCode: 500, error: STATUS_CODES[500], message: 'Something went wrong' });
    }
    return reply.code(status).send({ statusCode: status, error: STATUS_CODES[status] ?? 'Error', message: error.message });
  });
  app.addHook('onSend', async (_req, reply) => {
    if (!reply.hasHeader('cache-control')) reply.header('cache-control', 'no-store');
    reply.header('x-content-type-options', 'nosniff');
  });

  if (ctx.config.rateLimit.enabled) await app.register(rateLimit, { global: false, keyGenerator: (req) => req.ip });
  await app.register(multipart, { limits: { fileSize: MAX_UPLOAD_BYTES, files: 1, fields: 4, parts: 6 } });
  await app.register(swagger, {
    openapi: {
      openapi: '3.1.0',
      info: { title: 'Convoyage API', version, description: 'REST API for Convoyage. JSON everywhere; /v1 routes take `Authorization: Bearer <access token>`. The convoy WebSocket is described separately in convoy.v1.schema.json.' },
      servers: [{ url: 'http://localhost:8080', description: 'Local development' }],
      components: { securitySchemes: { bearerAuth: { type: 'http', scheme: 'bearer', bearerFormat: 'JWT' } } },
      tags: [
        { name: 'auth' }, { name: 'users' }, { name: 'vehicles' }, { name: 'rides' }, { name: 'media' },
        { name: 'posts' }, { name: 'feed' }, { name: 'moderation' }, { name: 'hazards' },
      ],
    },
    refResolver: { buildLocalReference: (json, _base, _fragment, i) => (typeof json.$id === 'string' ? json.$id : `def-${i}`) },
    transformObject: (document) => {
      if ('openapiObject' in document) patchOpenApi(document.openapiObject as Record<string, any>);
      return 'openapiObject' in document ? document.openapiObject : document.swaggerObject;
    },
  });
  registerSchemas(app);

  app.get('/v1/openapi.json', { schema: { hide: true } }, async () => app.swagger());

  await app.register(async (api) => {
    await authRoutes(api, ctx);
    await userRoutes(api, ctx);
    await vehicleRoutes(api, ctx);
    await rideRoutes(api, ctx);
    await mediaRoutes(api, ctx);
    await postRoutes(api, ctx);
    await hazardRoutes(api, ctx);
  });
}
