import { fileURLToPath } from 'node:url';
import { loadConfig } from './config.js';
import { createPgliteDatabase } from './db/index.js';
import { migrate } from './db/migrate.js';
import { MemoryMediaStore } from './media/store.js';
import { buildServer } from './server.js';

export const OPENAPI_PATH = fileURLToPath(new URL('../../protocol/openapi.json', import.meta.url));

/** Build the app against a throwaway in-memory database and return its OpenAPI document as stable JSON text. */
export async function generateOpenApi(): Promise<string> {
  const database = await createPgliteDatabase();
  await migrate(database);
  const config = loadConfig({ JWT_SECRET: 'x'.repeat(32), RATE_LIMIT_DISABLED: '1' } as NodeJS.ProcessEnv);
  const app = await buildServer({ config, database, media: new MemoryMediaStore() });
  const doc = JSON.stringify(app.swagger(), null, 2) + '\n';
  await app.close();
  await database.close();
  return doc;
}
