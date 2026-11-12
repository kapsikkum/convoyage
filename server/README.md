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

- [x] Real mailer: set `SMTP_URL` (+ `MAIL_FROM`); required when `NODE_ENV=production`.
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
| `OVERPASS_URL` | `https://overpass-api.de/api/interpreter,https://overpass.openstreetmap.fr/api/interpreter` | Overpass endpoint(s) for rest-stop search, comma-separated, tried in order (the public servers are often at capacity) |
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

- ~~Mail~~: done. `SMTP_URL` selects an SMTP mailer (nodemailer); without it the mailer is a no-op and only `MAIL_DEV=1` returns links.
- **Sign in with Apple / Google** verify against the providers' live JWKS, which is only exercised in tests with a
  locally generated key set. Not tried against a real Apple/Google token.
- **S3 media**: `S3MediaStore` is a stub (`src/media/store.ts`); local disk only. HEIC uploads are refused
  (convert to JPEG on the device).
- `CruiseSource` is `MemoryCruiseSource` (PhotoScout OAuth/events come later).
- Convoy rooms stay in memory (one process); no Redis, no push.
- Lat/lng are plain `double precision` columns with Haversine in SQL; PostGIS is in the compose image but unused.
- No admin/moderation tooling: `reports` rows are only recorded. Access tokens are not re-checked while a socket
  stays open (up to its lifetime of the connection).
- Likes/comments counts are computed per page (no denormalised counters); fine at this scale.

## Wave A additions

| area | what |
| --- | --- |
| Routing | `GET /v1/route?from=lng,lat&to=lng,lat&via=lng,lat;...&profile=car\|motorcycle` proxies OSRM (`OSRM_URL`, default the public demo, which is rate-limited and not for production). Cached 60 s, limited per user (`ROUTE_RATE_LIMIT_MAX`). |
| Voice | `POST /v1/voice/token {convoyCode}` returns a LiveKit token for room `convoy-<code>` to current members. `LIVEKIT_URL` (what phones connect to), `LIVEKIT_API_KEY`, `LIVEKIT_API_SECRET`. The `livekit` service is in `compose.yaml` and `scripts/up.ps1`. Phones need 7880/tcp, 7881/tcp and 50000-50100/udp reachable on the PC's LAN IP (`LIVEKIT_NODE_IP`). |
| Push | `POST`/`DELETE /v1/me/devices`; FCM (`FCM_SERVICE_ACCOUNT_JSON`) and APNs (`APNS_KEY_PATH`, `APNS_KEY_ID`, `APNS_TEAM_ID`, `APNS_TOPIC`, `APNS_ENV`), both no-ops when unset. |
| Guests | `POST /auth/guest {name}`: 24 h, no refresh token. Convoy socket, voice token and confirming/dismissing hazards only; reporting hazards, device registration and every other write is 403. |
| Misc | `GET /v1/catalogue`, `GET /v1/me/photos`, `GET /v1/users/:handle/photos`, feed ride `preview`, ride `movingTimeS`, `convoy.status` socket message. |

## Wave B additions

- **Rest stops** (`src/convoy/overpass.ts`): `convoy.rest {action:"find"}` and automatic regroup now ask Overpass (`OVERPASS_URL`)
  for `amenity=fuel`, `highway=rest_area`, `amenity=cafe|fast_food|restaurant` and `tourism=picnic_site` within 10 km of the
  leader. Fuel and rest areas win over cafes and the like; nearest first. Cached 10 minutes per ~1 km cell, 5 s timeout per endpoint (the next one is tried on failure), any error
  means "none found" (and is not cached). Requests carry `User-Agent: Convoyage/0.1 (self-hosted)` and only the rounded search point.
- **Convoy socket, additive** (`../protocol/convoy.v1.schema.json`):
  - `convoy.rename {room, name}`: leader only, 1 to 40 characters; everyone gets an updated `room`.
  - `convoy.regroup {room, enabled, separationM}`: leader only, `separationM` 1000 to 20000; everyone gets an updated `room`.
  - Live display names: `PATCH /v1/me {displayName}` while connected updates that member's name in every room they are in and
    broadcasts the room.
  - `room.routeGeometry` (optional polyline6): when the leader sets `convoy.dest` and the server knows the leader's position, it asks
    OSRM (same `OSRM_URL` and car profile as `/v1/route`) for a road-following route from the leader to the destination and broadcasts a
    second `room` message that carries it. Absent when there is no destination, no leader position yet, or OSRM failed. It is
    dropped when the destination changes or the convoy goes into/out of rest mode. Clients that ignore it keep working.
- **Guests** now get 403 on `POST /v1/hazards` and `/v1/me/devices` too. Socket, voice token and confirm/dismiss/ack still work.
- **LiveKit from phones on the LAN** (`scripts/up.ps1` + `scripts/lan-relay.mjs`): Podman's `--network host` lives inside the WSL VM,
  so LiveKit answers on the VM IP (like 172.30.x.x) but not on Windows `127.0.0.1` or the LAN IP. `lan-relay.mjs` finds the VM IP
  (`podman machine ssh "ip -4 -o addr show eth0"`, re-checked after a failed connect, override with `VM_IP`) and forwards Windows
  `0.0.0.0:7880` (signal) and `:7881` (ICE/TCP media) to it, next to the existing 8080 relay. LiveKit runs with `rtc.tcp_port: 7881`,
  `allow_tcp_fallback: true` and `node_ip` = the Windows LAN IPv4 (found by `up.ps1`, or `LIVEKIT_NODE_IP`), and `LIVEKIT_URL` returned
  by `/v1/voice/token` defaults to `ws://<LAN-IP>:7880`. **UDP media (50000-50100) is not forwarded**, so clients must fall back to TCP.
  Nothing in Windows networking, WSL config or the firewall is changed by the scripts. A phone still needs inbound TCP 7880 and 7881
  allowed, from an admin shell:
  `New-NetFirewallRule -DisplayName "Convoyage LiveKit" -Direction Inbound -Protocol TCP -LocalPort 7880,7881 -Action Allow -Profile Private`

## Wave C additions: PhotoScout link

A member can link their PhotoScout account (OAuth 2 authorization code + PKCE; PhotoScout's "connect an app" flow) and the server
then proxies PhotoScout events, spots, RSVPs and check-ins as that member, in small Convoyage-shaped DTOs (see `openapi.json`).

| route (bearer) | what |
| --- | --- |
| `GET /v1/photoscout/status` | `{configured, linked, username, scope, linkedAt}`. Always 200. `username` is null unless PhotoScout puts `username` in its token response (it does not today). |
| `GET /v1/photoscout/connect` | `{authorizeUrl}`: open it in a browser or custom tab. Guests: 403. |
| `GET /v1/photoscout/callback` | (no bearer) PhotoScout sends the browser here; the server swaps the code, stores the tokens and 302s to `convoyage://photoscout/linked` or `...linked?error=<code>`. |
| `DELETE /v1/photoscout/link` | revoke at PhotoScout (`/oauth/revoke`), then delete the tokens. |
| `GET /v1/photoscout/events?from&to&near=lat,lng&radiusKm` | community events (cruises carry `cruise.meet`) and crawled events, merged, soonest first. |
| `GET /v1/photoscout/spots?bbox=s,w,n,e` | spots with good times and a cover `photo`; photo bytes via `GET /v1/photoscout/photos/:id/thumb\|file` (bearer needed, like `/v1/media`). |
| `POST /v1/photoscout/events/:id/rsvp` | `{}` / `{status: going\|interested}` / `{going:false}`. A uuid id is a community event (going only), anything else a crawled event. |
| `POST /v1/photoscout/checkins` | `{spotId}`, `{eventId}` or `{lat,lng,placeName}`, plus optional `body`. |

Errors are `{statusCode, error, message}` with a stable `error`: `photoscout_not_configured` (503), `photoscout_not_linked` (409),
`photoscout_not_found`, `photoscout_forbidden`, `photoscout_rejected` (PhotoScout's 400/409/429), `photoscout_unavailable` (502).

- **Tokens** are sealed with AES-256-GCM (`PHOTOSCOUT_TOKEN_KEY`, bound to user and field) in `photoscout_links`; the PKCE verifier is sealed
  in `photoscout_oauth_states` (state stored as SHA-256, 10 minute life, single use). The access token is refreshed 30 s before expiry and
  once on a 401; refreshes are serialised per user because PhotoScout rotates refresh tokens (a replay ends the connection). If PhotoScout
  says the grant is gone, the link is deleted and the member gets 409 `photoscout_not_linked`.
- **Cruise convoys**: socket `convoy.cruise {eventId}` (already in the v1 schema) now reads the PhotoScout community event with the member's
  own token (`PhotoScoutCruiseSource`): name, meet point and route line (thinned to 25 stops, a circuit closed). The organiser opens the
  convoy, other linked members join. PhotoScout does not tell a connected app who the member is, so unless its token response carries
  `user_id` / `username`, any linked member who can read the event may open it (the leader can hand over with `convoy.lead`).
- **Badges**: every `UserRef` (post and comment authors, follow requests, blocks) has `primaryVehicle: {kind, build:{paint,finish,wheel,rideHeightMm}} | null`,
  hidden under the profile rules (private account not followed, either way of a block). `/v1/me` and `/v1/users/:handle` already return the full primary vehicle. Convoy `members[].vehicle` is the same
  badge from the member's account, refreshed live when they edit vehicles or toggle `isPrivate`; a private account shows none in rooms
  (rooms mix people who may not follow it). There is no followers/following list endpoint yet, so none to extend.

| variable | meaning |
| --- | --- |
| `PHOTOSCOUT_URL` | PhotoScout base URL. Unset = the feature is off (503). |
| `PUBLIC_BASE_URL` | this server's address as the phone's browser reaches it; the redirect URI is `<PUBLIC_BASE_URL>/v1/photoscout/callback`. Required with `PHOTOSCOUT_URL`. |
| `PHOTOSCOUT_TOKEN_KEY` | 32 bytes (64 hex, or base64): `openssl rand -hex 32`. Required with `PHOTOSCOUT_URL`; losing it unlinks everyone. |
| `PHOTOSCOUT_CLIENT_ID`, `PHOTOSCOUT_CLIENT_SECRET` | default `convoyage`, none (PhotoScout's connected apps are public clients; a secret is only sent if set). |

**Registering Convoyage on PhotoScout** (operator, PhotoScout's environment; there is no admin page):

```
OAUTH_CLIENTS=[{"clientId":"convoyage","name":"Convoyage","redirectUris":["<PUBLIC_BASE_URL>/v1/photoscout/callback"]}]
CONVOYAGE_URL=<where the Convoyage web/app link should point>   # optional: "Open in Convoyage" links
PUBLIC_URL=<PhotoScout's own address>                            # must match what members use
```

Scopes asked: `events:read events:rsvp spots:read checkins:write`. Then set the three variables above in Convoyage's `.env` and redeploy
(`scripts/down.ps1`, `scripts/up.ps1`). A member can also end the link from PhotoScout's Settings.

