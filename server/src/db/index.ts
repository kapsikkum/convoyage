import { Kysely, PostgresDialect, sql } from 'kysely';
import pg from 'pg';
import type { DB } from './types.js';

export type { DB } from './types.js';

/** A Kysely handle plus the few things Kysely cannot do: multi-statement migration scripts and shutdown. */
export interface Database {
  db: Kysely<DB>;
  /** Run one migration script and record it, atomically. */
  applyMigration(name: string, script: string): Promise<void>;
  appliedMigrations(): Promise<Set<string>>;
  close(): Promise<void>;
}

const ENSURE_MIGRATIONS = 'create table if not exists schema_migrations (name text primary key, applied_at timestamptz not null default now())';

async function applied(db: Kysely<DB>): Promise<Set<string>> {
  await sql.raw(ENSURE_MIGRATIONS).execute(db);
  const rows = await db.selectFrom('schema_migrations').select('name').execute();
  return new Set(rows.map((r) => r.name));
}

/** Real Postgres over node-postgres. */
export function createPgDatabase(connectionString: string): Database {
  // Keep int8 (count(*) etc.) as strings; the schema uses int4/float8 for everything the API returns.
  const pool = new pg.Pool({ connectionString, max: 10, idleTimeoutMillis: 30_000 });
  pool.on('error', (err) => console.error('[db] idle client error:', err.message));
  const db = new Kysely<DB>({ dialect: new PostgresDialect({ pool }) });
  return {
    db,
    appliedMigrations: () => applied(db),
    async applyMigration(name, script) {
      const client = await pool.connect();
      try {
        await client.query('begin');
        await client.query(script);
        await client.query('insert into schema_migrations (name) values ($1)', [name]);
        await client.query('commit');
      } catch (err) {
        await client.query('rollback').catch(() => {});
        throw err;
      } finally {
        client.release();
      }
    },
    close: () => db.destroy(),
  };
}

/** One pglite instance is one connection: hand it out under a lock, so transactions never interleave. */
class Lock {
  private tail: Promise<void> = Promise.resolve();
  async acquire(): Promise<() => void> {
    let release!: () => void;
    const mine = new Promise<void>((resolve) => { release = resolve; });
    const before = this.tail;
    this.tail = before.then(() => mine);
    await before;
    return release;
  }
}

/**
 * Embedded Postgres (pglite): disposable test databases and a zero-setup dev fallback.
 * `dataDir` undefined = in memory.
 */
export async function createPgliteDatabase(dataDir?: string): Promise<Database> {
  const { PGlite } = await import('@electric-sql/pglite');
  const lite = dataDir ? new PGlite(dataDir) : new PGlite();
  await lite.waitReady;
  const lock = new Lock();
  const pool = {
    async connect() {
      const release = await lock.acquire();
      return {
        async query(text: string, params: readonly unknown[] = []) {
          const res = await lite.query(text, [...params]);
          const m = /^\s*(insert|update|delete|merge)\b/i.exec(text);
          return { command: m ? m[1].toUpperCase() : 'SELECT', rowCount: res.affectedRows ?? res.rows.length, rows: res.rows };
        },
        release,
      };
    },
    async end() { await lite.close(); },
  };
  const db = new Kysely<DB>({ dialect: new PostgresDialect({ pool: pool as never }) });
  return {
    db,
    appliedMigrations: () => applied(db),
    async applyMigration(name, script) {
      const release = await lock.acquire();
      try {
        await lite.exec('begin');
        try {
          await lite.exec(script);
          await lite.query('insert into schema_migrations (name) values ($1)', [name]);
          await lite.exec('commit');
        } catch (err) {
          await lite.exec('rollback').catch(() => {});
          throw err;
        }
      } finally {
        release();
      }
    },
    close: () => db.destroy(),
  };
}

/** `postgres://...` for Postgres, `pglite:memory` or `pglite:<dir>` for the embedded database. */
export async function openDatabase(url: string): Promise<Database> {
  if (url.startsWith('pglite:')) {
    const rest = url.slice('pglite:'.length);
    return createPgliteDatabase(rest && rest !== 'memory' ? rest : undefined);
  }
  return createPgDatabase(url);
}
