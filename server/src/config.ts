import { randomBytes } from 'node:crypto';
import path from 'node:path';

export interface Config {
  production: boolean;
  port: number;
  host: string;
  databaseUrl: string;
  autoMigrate: boolean;
  jwtSecret: string;
  accessTtlS: number;
  refreshTtlDays: number;
  magicLinkTtlS: number;
  mailDev: boolean;
  magicLinkBaseUrl: string;
  devAuth: boolean;
  appleAudiences: string[];
  googleAudiences: string[];
  mediaDir: string;
  trustProxy: boolean;
  rateLimit: { enabled: boolean; authMax: number; writeMax: number; windowMs: number };
  /** Human-readable notes about insecure/dev fallbacks, logged once at start. */
  warnings: string[];
}

const list = (v: string | undefined) => (v ?? '').split(',').map((s) => s.trim()).filter(Boolean);
const flag = (v: string | undefined) => v === '1' || v === 'true';
const int = (v: string | undefined, fallback: number) => {
  const n = Number(v);
  return v !== undefined && v !== '' && Number.isFinite(n) && n > 0 ? Math.floor(n) : fallback;
};

export function loadConfig(env: NodeJS.ProcessEnv = process.env): Config {
  const production = env.NODE_ENV === 'production';
  const warnings: string[] = [];
  const mailDev = flag(env.MAIL_DEV);
  const devAuth = flag(env.CONVOYAGE_DEV_AUTH);
  if (production && mailDev) throw new Error('MAIL_DEV=1 returns magic links in API responses; refusing to start in production');
  if (production && devAuth) throw new Error('CONVOYAGE_DEV_AUTH=1 lets anyone impersonate any user; refusing to start in production');

  let jwtSecret = env.JWT_SECRET ?? '';
  if (!jwtSecret) {
    if (production) throw new Error('JWT_SECRET is required in production');
    jwtSecret = randomBytes(32).toString('hex');
    warnings.push('JWT_SECRET is not set: using an ephemeral secret, all tokens are invalid after a restart');
  } else if (jwtSecret.length < 32) {
    throw new Error('JWT_SECRET must be at least 32 characters');
  }

  let databaseUrl = env.DATABASE_URL ?? '';
  if (!databaseUrl) {
    if (production) throw new Error('DATABASE_URL is required in production');
    databaseUrl = `pglite:${path.resolve('data/pgdata')}`;
    warnings.push(`DATABASE_URL is not set: using embedded pglite at ${databaseUrl.slice('pglite:'.length)} (development only)`);
  }

  return {
    production,
    port: int(env.PORT, 8080),
    host: env.HOST ?? '0.0.0.0',
    databaseUrl,
    autoMigrate: flag(env.AUTO_MIGRATE) || databaseUrl.startsWith('pglite:'),
    jwtSecret,
    accessTtlS: int(env.ACCESS_TTL_SECONDS, 15 * 60),
    refreshTtlDays: int(env.REFRESH_TTL_DAYS, 60),
    magicLinkTtlS: int(env.MAGIC_LINK_TTL_SECONDS, 15 * 60),
    mailDev,
    magicLinkBaseUrl: env.MAGIC_LINK_BASE_URL || 'convoyage://auth/magic',
    devAuth,
    appleAudiences: list(env.APPLE_AUDIENCES),
    googleAudiences: list(env.GOOGLE_AUDIENCES),
    mediaDir: path.resolve(env.MEDIA_DIR || 'data/media'),
    trustProxy: flag(env.TRUST_PROXY),
    rateLimit: {
      enabled: !flag(env.RATE_LIMIT_DISABLED),
      authMax: int(env.RATE_LIMIT_AUTH_MAX, 10),
      writeMax: int(env.RATE_LIMIT_WRITE_MAX, 120),
      windowMs: int(env.RATE_LIMIT_WINDOW_MS, 60_000),
    },
    warnings,
  };
}
