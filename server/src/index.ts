import { buildServer } from './server.js';
import { loadConfig } from './config.js';
import { openDatabase } from './db/index.js';
import { migrate } from './db/migrate.js';

const config = loadConfig();
for (const w of config.warnings) console.warn(`[convoyage] ${w}`);
const database = await openDatabase(config.databaseUrl);
if (config.autoMigrate) {
  const ran = await migrate(database);
  if (ran.length) console.log(`[convoyage] migrated: ${ran.join(', ')}`);
}
const app = await buildServer({ config, database, logger: true });
await app.listen({ port: config.port, host: config.host });
if (config.devAuth) console.warn('[convoyage] DEV AUTH ENABLED: do not use in production');
if (config.mailDev) console.warn('[convoyage] MAIL_DEV: magic links are logged and returned in responses');

const shutdown = async () => {
  await app.close();
  await database.close();
  process.exit(0);
};
process.on('SIGINT', shutdown);
process.on('SIGTERM', shutdown);
