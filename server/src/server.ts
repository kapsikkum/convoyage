import type { FastifyInstance } from 'fastify';
import { buildApp } from './app.js';
import { Tokens } from './api/tokens.js';
import { providersFromConfig, type Providers } from './api/providers.js';
import { devAuthenticator, firstOf, jwtAuthenticator } from './auth.js';
import type { Config } from './config.js';
import type { Database } from './db/index.js';
import { noopMailer, smtpMailer, type Mailer } from './mail.js';
import { LocalDiskMediaStore, S3MediaStore, type MediaStore } from './media/store.js';
import type { CruiseSource, RestStopFinder } from './ports.js';
import { Notifier } from './push/notifier.js';
import { pushFromConfig, type PushSender } from './push/sender.js';
import type { Fetch } from './http.js';
import { MemoryCruiseSource } from './stubs.js';
import { OverpassRestStops } from './convoy/overpass.js';
import { osrmGeometry } from './convoy/osrm.js';
import { PhotoScoutService } from './photoscout/service.js';
import { PhotoScoutCruiseSource } from './photoscout/cruiseSource.js';

export interface ServerDeps {
  config: Config;
  database: Database;
  mailer?: Mailer;
  providers?: Providers;
  media?: MediaStore;
  cruises?: CruiseSource;
  restStops?: RestStopFinder;
  push?: PushSender;
  /** Used for OSRM and PhotoScout; tests hand in a fake. */
  fetch?: Fetch;
  logger?: boolean;
  heartbeatMs?: number;
  sweepMs?: number;
  recheckMs?: number;
}

/** The whole server: REST + socket, wired to a database. `index.ts` and the tests both come through here. */
export async function buildServer(deps: ServerDeps): Promise<FastifyInstance> {
  const { config, database } = deps;
  const tokens = new Tokens(database.db, config);
  const jwt = jwtAuthenticator(tokens, database.db);
  const doFetch: Fetch = deps.fetch ?? ((url, init) => fetch(url, init));
  const notifier = new Notifier(database.db, deps.push ?? pushFromConfig(config));
  const photoscout = config.photoscout ? new PhotoScoutService(database.db, config.photoscout, doFetch) : null;
  const app = await buildApp({
    auth: config.devAuth ? firstOf(jwt, devAuthenticator) : jwt,
    cruises: deps.cruises ?? (photoscout ? new PhotoScoutCruiseSource(photoscout) : new MemoryCruiseSource()),
    restStops: deps.restStops ?? new OverpassRestStops(config.overpassUrls),
    routeFinder: (from, to, via) => osrmGeometry(config, doFetch, from, to, via),
    heartbeatMs: deps.heartbeatMs,
    sweepMs: deps.sweepMs,
    recheckMs: deps.recheckMs,
    stillValid: config.devAuth ? undefined : async (userId) => {
      const u = await database.db.selectFrom('users').select(['disabled_at', 'expires_at']).where('id', '=', userId).executeTakeFirst();
      return !!u && !u.disabled_at && !(u.expires_at && new Date(u.expires_at).getTime() <= Date.now());
    },
    logger: deps.logger,
    api: {
      db: database.db,
      config,
      tokens,
      mailer: deps.mailer ?? (deps.config.smtp ? smtpMailer(deps.config.smtp) : noopMailer),
      providers: deps.providers ?? providersFromConfig(config),
      media: deps.media ?? (config.s3 ? new S3MediaStore(config.s3) : new LocalDiskMediaStore(config.mediaDir)),
      notifier,
      fetch: doFetch,
      photoscout,
    },
  });
  // Expired guest accounts (and everything they own) are removed hourly.
  const sweep = setInterval(() => {
    database.db.deleteFrom('users').where('kind', '=', 'guest').where('expires_at', '<', new Date()).execute().catch(() => {});
  }, 3_600_000);
  sweep.unref();
  return app;
}

