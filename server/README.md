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

## Run it locally with Docker/Podman

`compose.yaml` runs Postgres/PostGIS plus the server (`server/Dockerfile`: multi-stage, non-root, prod deps only,
`/health` healthcheck, migrations on start via `AUTO_MIGRATE=1`, media in the `convoyage-media` volume at `/data/media`).

```
cp .env.example .env     # repo root; set JWT_SECRET to 48+ random chars (.env is gitignored)
podman compose up -d --build
curl localhost:8080/health
```

`MAIL_DEV=1` is set in compose (**beta only**: magic links are returned in API responses because there is no real
mailer). `NODE_ENV` is deliberately not `production`, since config refuses `MAIL_DEV` there.

**If `podman compose up` fails with a netavark/nftables error** (seen on the Windows Podman machine; bridge networks
cannot be set up, host networking works), use the fallback, which runs the same containers with `--network host`:

```
powershell scripts/up.ps1      # builds the image, starts db + server, starts the LAN relay
powershell scripts/down.ps1    # stops them (-Volumes also deletes the data)
```

**Phones on the LAN**: set the app's Server URL to `http://<lan-ip>:8080` (the PC's Wi-Fi/Ethernet IPv4, see
`ipconfig`). On Windows the Podman machine only publishes ports on loopback, so `up.ps1` also runs
`scripts/lan-relay.mjs` (0.0.0.0:8080 to 127.0.0.1:8080). If Windows Firewall blocks it, allow inbound 8080 on the
Private profile from an admin shell:
`New-NetFirewallRule -DisplayName "Convoyage 8080" -Direction Inbound -Protocol TCP -LocalPort 8080 -Action Allow -Profile Private`

## Moving to data-server

- [ ] A real `Mailer` implementation (`src/mail.ts`) sending the magic link.
- [ ] HTTPS reverse proxy in front (Caddy/nginx) with WebSocket upgrade for `/ws`; set `TRUST_PROXY=1`; stop publishing 8080 publicly.
- [ ] `NODE_ENV=production` (requires `JWT_SECRET` and `DATABASE_URL`).
- [ ] Remove `MAIL_DEV` (and never set `CONVOYAGE_DEV_AUTH`).
- [ ] Strong, unique Postgres password (compose uses `convoyage`/`convoyage`) and not publishing 5432; back up the db and media volumes.

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
