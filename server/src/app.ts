import { readFileSync } from 'node:fs';
import Fastify, { type FastifyInstance } from 'fastify';
import { registerApi, type ApiContext } from './api/index.js';
import { Rooms } from './convoy/rooms.js';
import { attachRealtime, type RealtimeOptions } from './realtime.js';

// Resolves to server/package.json from both src/ and dist/.
export const VERSION: string = JSON.parse(readFileSync(new URL('../package.json', import.meta.url), 'utf8')).version;

export interface AppOptions extends RealtimeOptions {
  /** The REST API. Without it only /health and the socket exist (phase 1 behaviour). */
  api?: Omit<ApiContext, 'convoy'>;
  logger?: boolean;
}

/** The HTTP app plus the socket on its server. Close the app to stop both. */
export async function buildApp(opts: AppOptions): Promise<FastifyInstance> {
  const app = Fastify({
    logger: opts.logger ?? false,
    trustProxy: opts.api?.config.trustProxy ?? false,
    // Request bodies are small JSON; the ride-finish route raises its own limit, uploads use multipart limits.
    bodyLimit: 256 * 1024,
  });
  const rooms = opts.rooms ?? new Rooms();
  const realtime = attachRealtime(app.server, {
    ...opts, rooms,
    onSos: (e) => opts.api?.notifier.sos(e.code, e.memberIds, e.from, e.note),
  });
  app.addHook('onClose', async () => realtime.close());
  app.get('/health', async () => ({ ok: true, version: VERSION }));
  if (opts.api) {
    await registerApi(app, { ...opts.api, convoy: { roomsOf: (id) => rooms.roomsOf(id), sendToRoom: realtime.sendToRoom, offlineMemberIds: (code) => rooms.offlineMemberIds(code), renameUser: realtime.renameUser, setVehicle: realtime.setVehicle } }, VERSION);
  }
  await app.ready();
  return app;
}
