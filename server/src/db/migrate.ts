import { readdirSync, readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import type { Database } from './index.js';

/** server/migrations, from both src/ and dist/. */
export const MIGRATIONS_DIR = fileURLToPath(new URL('../../migrations/', import.meta.url));

/** Apply every migrations/NNN_name.sql not yet recorded, in order. Returns the names applied. */
export async function migrate(database: Database, dir: string = MIGRATIONS_DIR): Promise<string[]> {
  const files = readdirSync(dir).filter((f) => /^\d+_.+\.sql$/.test(f)).sort();
  const done = await database.appliedMigrations();
  const ran: string[] = [];
  for (const file of files) {
    if (done.has(file)) continue;
    await database.applyMigration(file, readFileSync(`${dir}/${file}`, 'utf8'));
    ran.push(file);
  }
  return ran;
}
