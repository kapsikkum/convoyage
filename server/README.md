# Convoyage server

Node 22, TypeScript (strict, ESM), Fastify 5, Kysely on Postgres, and a plain `ws` socket on `/ws`.

- **Convoy rooms** (`src/convoy/*`, `src/realtime.ts`): ported from PhotoScout. In memory, one process, positions
  never stored or logged.
- **REST API** (`src/api/*`): accounts, social layer, garage, rides, media, feed, hazards. Described by
  `../protocol/openapi.json`.
- **Wire formats**: `../protocol/convoy.v1.schema.json` (socket) and `../protocol/openapi.json` (REST).

## Setup

```
npm install

# Database: Postgres 16 (+PostGIS image, not used yet) from the repo root
podman compose up -d          # or: docker compose up -d   (compose.yaml is compose-spec only)
cp ../.env.example .env       # then load it, e.g. node --env-file=.env ...  (the server reads process.env)
npm run migrate               # applies server/migrations/NNN_*.sql in order, recorded in schema_migrations
npm run dev                   # tsx watch, listens on PORT (default 8080)
```

With no `DATABASE_URL` in development the server falls back to an **embedded pglite database** in
`server/data/pgdata` and migrates it at start, so `npm run dev` works without any container. (pglite is a
dev dependency; production must set `DATABASE_URL`.)

Quick check, using the dev mail mode (`MAIL_DEV=1`):

```
curl -s localhost:8080/health
curl -s -X POST localhost:8080/auth/magic-link -H 'content-type: application/json' -d '{"email":"me@example.com"}'
#  -> {"ok":true,"devLink":"convoyage://auth/magic?token=..."}   (also logged)
curl -s -X POST localhost:8080/auth/magic-link/verify -H 'content-type: application/json' -d '{"token":"..."}'
#  -> {"accessToken","refreshToken","expiresIn":900,"isNewUser":true,...}
curl -s localhost:8080/v1/me -H 'authorization: Bearer <accessToken>'
```

## Scripts

| script | does |
| --- | --- |
| `npm run dev` | run with reload |
| `npm test` | all tests, **no container needed** (each test file uses an in-memory pglite database) |
| `npm run build` / `npm start` | compile to `dist/`, run it |
| `npm run migrate` | apply pending SQL migrations to `DATABASE_URL` (`migrate:prod` runs the built copy) |
| `npm run openapi` | regenerate `../protocol/openapi.json` from the route schemas; a test fails if the committed file is stale |

## Environment

| variable | default | meaning |
| --- | --- | --- |
| `PORT`, `HOST` | `8080`, `0.0.0.0` | listen address |
| `NODE_ENV` | | `production` turns the checks below into hard errors |
| `DATABASE_URL` | pglite in `data/pgdata` (dev only) | `postgres://...`; `pglite:memory` also works |
| `AUTO_MIGRATE` | `0` | run migrations at start (always on for pglite) |
| `JWT_SECRET` | random per start (dev only) | HS256 access-token secret, 32+ chars; **required in production** |
| `ACCESS_TTL_SECONDS`, `REFRESH_TTL_DAYS` | `900`, `60` | token lifetimes |
| `MAIL_DEV` | `0` | `1`: log the magic link **and return it in the response**. Refuses to start in production |
| `MAGIC_LINK_BASE_URL` | `convoyage://auth/magic` | link base; `token=` is appended (15 minute, single use) |
| `APPLE_AUDIENCES`, `GOOGLE_AUDIENCES` | unset | comma-separated accepted `aud` values (bundle id / client ids). Unset = that provider answers 501 |
| `CONVOYAGE_DEV_AUTH` | `0` | `1`: sockets also accept `?dev_user=alice`. Refuses to start in production |
| `MEDIA_DIR` | `data/media` | local disk media store |
| `TRUST_PROXY` | `0` | `1` behind a reverse proxy, so rate limits key on the real client IP |
| `RATE_LIMIT_DISABLED`, `RATE_LIMIT_AUTH_MAX`, `RATE_LIMIT_WRITE_MAX`, `RATE_LIMIT_WINDOW_MS` | `0`, `10`, `120`, `60000` | per-IP limits on `/auth/*` and on writes |

## API at a glance

Everything under `/v1` is JSON with `Authorization: Bearer <access token>`. Errors are
`{ statusCode, error, message }`. Full detail: `../protocol/openapi.json` or `GET /v1/openapi.json`.

- **Auth** (no bearer): `POST /auth/magic-link`, `/auth/magic-link/verify`, `/auth/apple`, `/auth/google`,
  `/auth/refresh`, `/auth/logout`. Refresh tokens rotate; reusing an old one revokes the whole session family.
- **Me / users**: `GET|PATCH|DELETE /v1/me`, `GET /v1/handles/:handle/availability`, `GET /v1/users/:handle`,
  follow / unfollow (`POST|DELETE /v1/users/:handle/follow`), `GET /v1/me/follow-requests`, approve / decline,
  block / unblock, `GET /v1/me/blocks`.
- **Vehicles**: CRUD under `/v1/vehicles`, `POST /:id/primary`, `POST /:id/sold`, `GET /v1/users/:handle/vehicles`.
  `build` = `{ paint, finish, wheel, rideHeightMm, decals[] }`.
- **Rides**: `POST /v1/rides` (start), `POST /v1/rides/:id/finish` (stats + encoded polyline), list mine / by user,
  get, patch, delete, `POST /v1/rides/:id/photos`.
- **Media**: `POST /v1/media` (multipart `file`; JPEG/PNG/WebP, 15 MB, EXIF stripped except capture time,
  512 px thumbnail), `GET /v1/media/:id[/thumb]`.
- **Social**: `POST /v1/posts` (ride | build | photo | text), `GET /v1/feed?scope=everyone|following|nearby&lat&lng&before=`,
  like / unlike, comments, `POST /v1/reports`.
- **Hazards**: `POST /v1/hazards`, `GET /v1/hazards?bbox=south,west,north,east`, `/:id/confirm|dismiss|ack`.
  30 minute life (a confirmation renews it, capped at 4 hours total); 3 dismissals remove it.
- **Socket** `/ws`: authenticate with `Sec-WebSocket-Protocol: convoyage.v1, bearer.<access token>` (preferred) or
  `?access_token=<token>`. A hazard created by a user who is in a convoy room is sent to every member as
  `{ "t": "hazard", "room", "hazard": {...} }`.

## Privacy rules (implemented once, in `visibleTo` in `src/api/common.ts`)

- A block hides both people from each other everywhere (profile, posts, rides, comments, feed) and removes follows.
  The blocker can still open the blocked profile so they can unblock.
- A private account shows only name, avatar and counts until a follow is approved; its rides, vehicles and
  posts are hidden from everyone else (including `public` ones). Going public approves pending requests.
- Ride visibility `public | followers | private`; a post can never be more public than the ride it shows.
- Others only ever see **finished** rides. Hazard reporters are never exposed.

## Stubbed / not built

- **Mail**: `Mailer` (`src/mail.ts`) defaults to a no-op; magic links only reach people with `MAIL_DEV=1`.
- **Sign in with Apple / Google** verify against the providers' live JWKS, which is only exercised in tests with a
  locally generated key set. Not tried against a real Apple/Google token.
- **S3 media**: `S3MediaStore` is a stub (`src/media/store.ts`); local disk only. HEIC uploads are refused
  (convert to JPEG on the device).
- `CruiseSource` is `MemoryCruiseSource` (PhotoScout OAuth/events come later); `RestStopFinder` finds nothing.
- Convoy rooms stay in memory (one process); no Redis, no push.
- Lat/lng are plain `double precision` columns with Haversine in SQL; PostGIS is in the compose image but unused.
- No admin/moderation tooling: `reports` rows are only recorded. Access tokens are not re-checked while a socket
  stays open (up to its lifetime of the connection).
- Likes/comments counts are computed per page (no denormalised counters); fine at this scale.
