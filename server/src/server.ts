import type { FastifyInstance } from 'fastify';
import { buildApp } from './app.js';
import { Tokens } from './api/tokens.js';
import { providersFromConfig, type Providers } from './api/providers.js';
import { devAuthenticator, firstOf, jwtAuthenticator } from './auth.js';
import type { Config } from './config.js';
import type { Database } from './db/index.js';
import { noopMailer, type Mailer } from './mail.js';
import { LocalDiskMediaStore, type MediaStore } from './media/store.js';
import type { CruiseSource, RestStopFinder } from './ports.js';
import { MemoryCruiseSource, noRestStops } from './stubs.js';

export interface ServerDeps {
  config: Config;
  database: Database;
  mailer?: Mailer;
  providers?: Providers;
  media?: MediaStore;
  cruises?: CruiseSource;
  restStops?: RestStopFinder;
  logger?: boolean;
  heartbeatMs?: number;
  sweepMs?: number;
}

/** The whole server: REST + socket, wired to a database. `index.ts` and the tests both come through here. */
export function buildServer(deps: ServerDeps): Promise<FastifyInstance> {
  const { config, database } = deps;
  const tokens = new Tokens(database.db, config);
  const jwt = jwtAuthenticator(tokens, database.db);
  return buildApp({
    auth: config.devAuth ? firstOf(jwt, devAuthenticator) : jwt,
    cruises: deps.cruises ?? new MemoryCruiseSource(),
    restStops: deps.restStops ?? noRestStops,
    heartbeatMs: deps.heartbeatMs,
    sweepMs: deps.sweepMs,
    logger: deps.logger,
    api: {
      db: database.db,
      config,
      tokens,
      mailer: deps.mailer ?? noopMailer,
      providers: deps.providers ?? providersFromConfig(config),
      media: deps.media ?? new LocalDiskMediaStore(config.mediaDir),
    },
  });
}
