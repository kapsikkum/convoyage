import { randomBytes } from 'node:crypto';
import path from 'node:path';

/** The PhotoScout connection. Absent (null in Config) until PHOTOSCOUT_URL is set. */
export interface PhotoScoutConfig {
  /** PhotoScout base URL, no trailing slash. */
  url: string;
  clientId: string;
  /** Sent as client_secret when set; PhotoScout's connected apps are public clients, so normally null. */
  clientSecret: string | null;
  /** Where PhotoScout sends the browser back: `<publicBaseUrl>/v1/photoscout/callback`. */
  publicBaseUrl: string;
  /** 32-byte AES-256-GCM key sealing the stored PhotoScout tokens. */
  tokenKey: Buffer;
}

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
  /** OSRM base URL for GET /v1/route. */
  osrmUrl: string;
  osrmProfiles: { car: string; motorcycle: string };
  /** Overpass API endpoint for rest-stop search. */
  overpassUrls: string[];
  /** Transport for NSW Open Data key (live traffic hazards on routes). Null turns that source off. */
  tfnswApiKey: string | null;
  /** Self-hosted Waze report harvester (police, jams). Null turns it off. Self-host only. */
  waze: { url: string; key: string } | null;
  routeRateLimit: { max: number; windowMs: number };
  /** LiveKit (push-to-talk). Null until LIVEKIT_API_KEY and LIVEKIT_API_SECRET are set. */
  livekit: { url: string; apiKey: string; apiSecret: string } | null;
  /** Push notifications: each is null (no-op) unless configured. */
  fcmServiceAccountPath: string | null;
  apns: { keyPath: string; keyId: string; teamId: string; topic: string; sandbox: boolean } | null;
  photoscout: PhotoScoutConfig | null;
  guestTtlS: number;
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

/** 64 hex characters, or base64 / base64url of 32 bytes. */
export function parseTokenKey(raw: string): Buffer {
  const v = raw.trim();
  const key = /^[0-9a-fA-F]{64}$/.test(v) ? Buffer.from(v, 'hex') : Buffer.from(v, /^[A-Za-z0-9_-]+={0,2}$/.test(v) ? 'base64url' : 'base64');
  if (key.length !== 32) throw new Error('PHOTOSCOUT_TOKEN_KEY must be 32 bytes: 64 hex characters or base64 (generate with: openssl rand -hex 32)');
  return key;
}

function photoscoutConfig(env: NodeJS.ProcessEnv): PhotoScoutConfig | null {
  const url = (env.PHOTOSCOUT_URL ?? '').trim().replace(/\/+$/, '');
  if (!url) return null;
  if (!/^https?:\/\/[^/]/i.test(url)) throw new Error('PHOTOSCOUT_URL must be an http(s) URL');
  const publicBaseUrl = (env.PUBLIC_BASE_URL ?? '').trim().replace(/\/+$/, '');
  if (!/^https?:\/\/[^/]/i.test(publicBaseUrl)) throw new Error('PUBLIC_BASE_URL (the address PhotoScout redirects the browser back to) is required when PHOTOSCOUT_URL is set');
  if (!env.PHOTOSCOUT_TOKEN_KEY) throw new Error('PHOTOSCOUT_TOKEN_KEY is required when PHOTOSCOUT_URL is set');
  return {
    url, publicBaseUrl, tokenKey: parseTokenKey(env.PHOTOSCOUT_TOKEN_KEY),
    clientId: (env.PHOTOSCOUT_CLIENT_ID ?? '').trim() || 'convoyage',
    clientSecret: (env.PHOTOSCOUT_CLIENT_SECRET ?? '').trim() || null,
  };
}

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
    // The public demo server is rate-limited and not for production use: run your own OSRM and point OSRM_URL at it.
    osrmUrl: (env.OSRM_URL || 'https://router.project-osrm.org').replace(/\/+$/, ''),
    // Stock OSRM has one profile per deployment; the public demo only answers 'driving'. Override per profile if you run a motorcycle build.
    osrmProfiles: { car: env.OSRM_PROFILE_CAR || 'driving', motorcycle: env.OSRM_PROFILE_MOTORCYCLE || 'driving' },
    overpassUrls: list(env.OVERPASS_URL).length ? list(env.OVERPASS_URL) : ['https://overpass-api.de/api/interpreter', 'https://overpass.openstreetmap.fr/api/interpreter'],
    tfnswApiKey: env.TFNSW_API_KEY?.trim() || null,
    waze: env.WAZE_HARVESTER_URL ? { url: env.WAZE_HARVESTER_URL.trim(), key: (env.WAZE_HARVESTER_KEY ?? '').trim() } : null,
    routeRateLimit: { max: int(env.ROUTE_RATE_LIMIT_MAX, 30), windowMs: int(env.RATE_LIMIT_WINDOW_MS, 60_000) },
    livekit: env.LIVEKIT_API_KEY && env.LIVEKIT_API_SECRET
      ? { url: env.LIVEKIT_URL || 'ws://localhost:7880', apiKey: env.LIVEKIT_API_KEY, apiSecret: env.LIVEKIT_API_SECRET }
      : null,
    fcmServiceAccountPath: env.FCM_SERVICE_ACCOUNT_JSON || null,
    apns: env.APNS_KEY_PATH && env.APNS_KEY_ID && env.APNS_TEAM_ID && env.APNS_TOPIC
      ? { keyPath: env.APNS_KEY_PATH, keyId: env.APNS_KEY_ID, teamId: env.APNS_TEAM_ID, topic: env.APNS_TOPIC, sandbox: env.APNS_ENV !== 'production' }
      : null,
    photoscout: photoscoutConfig(env),
    guestTtlS: int(env.GUEST_TTL_SECONDS, 24 * 3600),
    rateLimit: {
      enabled: !flag(env.RATE_LIMIT_DISABLED),
      authMax: int(env.RATE_LIMIT_AUTH_MAX, 10),
      writeMax: int(env.RATE_LIMIT_WRITE_MAX, 120),
      windowMs: int(env.RATE_LIMIT_WINDOW_MS, 60_000),
    },
    warnings,
  };
}
