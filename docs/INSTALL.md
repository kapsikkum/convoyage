# Install Convoyage

Convoyage has two apps (Android and iOS) and a server you can run locally or on your own host. Pick one path per
column; the apps work against the hosted origin by default.

## Choose your path

| I want to… | Go to | You need |
| --- | --- | --- |
| Install the Android app | [Android](#android) | Android 9+, F-Droid or a downloaded APK |
| Install the iOS app | [iOS](#ios) | iOS 17+, SideStore or AltStore |
| Run a server on my machine | [Local server](#local-server) | Node 22+, or Docker/Podman |
| Run a server for real | [Production server](#production-server) | A Linux host, a DNS name, SMTP credentials |

---

## Android

**Requirements:** Android 9 (API 28) or newer. The app asks for location (including background location while a
convoy or ride recording needs it), notifications and microphone access (push-to-talk). There are no Google services
in the build — no Play Services, no Firebase.

### Recommended: F-Droid repository

1. Install [F-Droid](https://f-droid.org) if you do not have it.
2. In F-Droid: **Settings → Repositories → Add repository**.
3. Enter `https://kapsikkum.github.io/apps/fdroid/repo` and add it.
4. Search for **Convoyage** and install it. Updates then arrive through F-Droid.

The same repository is linked from the apps page: <https://kapsikkum.github.io/apps>.

### Direct APK

1. Open <https://github.com/kapsikkum/convoyage/releases> and download `Convoyage-release.apk` from the release you
   want (the newest stable, or the `beta` pre-release).
2. Allow installs from unknown apps for your browser or file manager when prompted, then open the APK.

### Signing caveat

Builds from 0.1.9 onward use a stable release key, so future updates install in place. Builds older than 0.1.9 were
signed with per-run keys: if one of those is installed, **uninstall Convoyage once** before installing 0.1.9 or
newer. Android refuses an update whose signing key does not match.

### Updates

Install the newest release from F-Droid, or download the newest `Convoyage-release.apk` from
[Releases](https://github.com/kapsikkum/convoyage/releases). Both pre-releases (`v*-beta.*`) and stable releases
carry the same two files.

---

## iOS

**Requirements:** iOS 17 or newer. There is no TestFlight build in this repository; the IPA is unsigned and is
signed on-device by your sideloading tool.

### Recommended: SideStore / AltStore source

1. Install [SideStore](https://sidestore.io) or [AltStore](https://altstore.io) and get it working on your device.
2. Add this source: `https://kapsikkum.github.io/apps/sidestore.json`
3. Install **Convoyage** from that source.
4. Refresh the app at least every 7 days, or it stops launching until it is refreshed again.

The IPA is unsigned; SideStore or AltStore signs it with your Apple ID on the device. The source is also linked
from <https://kapsikkum.github.io/apps>.

### Direct IPA fallback

Download `Convoyage-unsigned.ipa` from [Releases](https://github.com/kapsikkum/convoyage/releases) and install it
with SideStore, AltStore or Sideloadly.

---

## Local server

### Fastest: no container

Node 22 or newer.

```bash
cd server
npm install
npm run dev
```

With no `DATABASE_URL`, the server uses an embedded pglite database in `server/data/pgdata`, migrates it at start,
and listens on port 8080.

```bash
curl localhost:8080/health
# {"ok":true,"version":"..."}
```

Magic-link sign-in in development: the server reads `process.env` only, so set `MAIL_DEV=1` before starting it.
Then the endpoint logs the link and returns it in the response.

```bash
# PowerShell: $env:MAIL_DEV='1'; npm run dev
MAIL_DEV=1 npm run dev

curl -s -X POST localhost:8080/auth/magic-link \
  -H 'content-type: application/json' -d '{"email":"me@example.com"}'
# {"ok":true,"devLink":"convoyage://auth/magic?token=..."}
```

### Container path

From the repo root:

```bash
cp .env.example .env
# edit .env: set JWT_SECRET to 48+ random characters, e.g.
#   openssl rand -base64 48 | tr -d '\n='
docker compose up -d --build    # or: podman compose up -d --build
curl localhost:8080/health
```

`compose.yaml` starts Postgres/PostGIS, the API and LiveKit. Migrations run on start (`AUTO_MIGRATE=1`). It sets
`MAIL_DEV=1` so magic links come back in the response — that is **beta-only** (there is no real mailer there), and
Compose deliberately does not run with `NODE_ENV=production`.

### Phones on the LAN

Point the app's server URL at the PC: `http://<lan-ip>:8080` (find the IPv4 address with `ipconfig`).

On Windows, the Podman machine only publishes ports on loopback. Use the fallback, which runs the same containers
with host networking and starts a small LAN relay:

```powershell
powershell scripts/up.ps1      # builds the image, starts db + LiveKit + API, starts the LAN relay
powershell scripts/down.ps1    # stop (add -Volumes to delete the database and media volumes)
```

If Windows Firewall blocks the port, allow it from an admin shell:

```powershell
New-NetFirewallRule -DisplayName "Convoyage 8080" -Direction Inbound -Protocol TCP -LocalPort 8080 -Action Allow -Profile Private
```

Testing push-to-talk from phones on the LAN also needs TCP 7880 and 7881 open:

```powershell
New-NetFirewallRule -DisplayName "Convoyage LiveKit" -Direction Inbound -Protocol TCP -LocalPort 7880,7881 -Action Allow -Profile Private
```

---

## Production server

The production stack is aimed at a Linux host (the repo's own deployment runs it on a NixOS machine). It runs
Postgres/PostGIS, the API, Caddy for TLS, LiveKit on host networking, and an optional self-hosted OSRM. Only Caddy
and LiveKit face the internet; the API's port 8080 is not published.

### 1. Configuration

```bash
cp deploy/.env.prod.example deploy/.env.prod
```

Fill in `deploy/.env.prod`:

| Variable | Required | Notes |
| --- | --- | --- |
| `PUBLIC_HOST` | yes | The DNS name Caddy serves, e.g. `convoy.<your-domain>` |
| `POSTGRES_PASSWORD` | yes | 32+ random characters |
| `JWT_SECRET` | yes | 48+ random characters |
| `SMTP_URL` | yes | e.g. `smtps://user:pass@smtp.example.com:465` |
| `MAIL_FROM` | yes | e.g. `Convoyage <no-reply@<your-domain>>` |
| `LIVEKIT_NODE_IP` | yes | The host's public IPv4, advertised as the WebRTC candidate |
| `LIVEKIT_API_KEY`, `LIVEKIT_API_SECRET` | yes | Secret 32+ characters |
| `MAGIC_LINK_BASE_URL` | recommended | `https://<your-domain>/auth/magic`, so mail clients linkify it |
| `ADMIN_TOKEN` | optional | 32+ characters; enables the `/admin/*` moderation routes |
| `APPLE_AUDIENCES`, `GOOGLE_AUDIENCES` | optional | Unset means that sign-in provider answers 501 |
| S3 (`S3_BUCKET`, `S3_ENDPOINT`, …) | optional | Media in S3-compatible storage instead of the local volume |
| APNs / FCM | optional | Push is a no-op until configured |
| PhotoScout (`PHOTOSCOUT_URL`, `PHOTOSCOUT_TOKEN_KEY`) | optional | All or none; `PHOTOSCOUT_TOKEN_KEY` is `openssl rand -hex 32` |
| `TFNSW_API_KEY`, `FUELCHECK_*`, `WAZE_HARVESTER_*` | optional | Route conditions |

The full environment table, API and integration details are in [`server/README.md`](../server/README.md).

### 2. DNS and ports

- Point an `A` (and `AAAA` if you have IPv6) record for `PUBLIC_HOST` at the host.
- Open inbound: **80/tcp** and **443/tcp** (and 443/udp if you want HTTP/3) for Caddy, **7881/tcp** and
  **50000–50100/udp** for LiveKit media.
- Caddy obtains and renews certificates automatically. LiveKit signalling goes through Caddy at `/livekit`, so
  phones only need 443 plus the media ports.
- Do not publish 8080 or 5432.

### 3. Start

```bash
docker compose -f deploy/compose.prod.yaml --env-file deploy/.env.prod up -d --build
# or: podman compose -f deploy/compose.prod.yaml --env-file deploy/.env.prod up -d --build

curl -fsS https://<your-domain>/health
```

### 4. Self-hosted routing (optional)

The default `OSRM_URL` points at the bundled OSRM container, which needs a routing graph built once:

```bash
deploy/osrm-prepare.sh <osm-pbf-url>
docker compose -f deploy/compose.prod.yaml --env-file deploy/.env.prod --profile osrm up -d
```

Use a Geofabrik extract for your region; a country-sized extract needs roughly 16 GB of RAM while it builds. Without
it, point `OSRM_URL` at another OSRM instance — the public demo is rate-limited and not for production.

### 5. Backups

`deploy/backup.sh` dumps Postgres and the media volume into `./backups`, keeping the newest 14 by default. Run it
from cron, for example:

```cron
17 3 * * * /srv/convoyage/deploy/backup.sh
```

Test a restore before you need one — the commands are in the script header (`gunzip -c backups/db-*.sql.gz | ... psql`
and a `tar` restore into the media volume).

### 6. Updates and rollback

```bash
git pull
docker compose -f deploy/compose.prod.yaml --env-file deploy/.env.prod up -d --build
```

To roll back, check out the previous release tag and rebuild:

```bash
git checkout v0.1.9
docker compose -f deploy/compose.prod.yaml --env-file deploy/.env.prod up -d --build
```

Never commit `deploy/.env.prod`; it is covered by `.gitignore`. Keep the real values on the host and in your secret
manager.

### 7. Health and logs

```bash
curl -fsS https://<your-domain>/health
docker compose -f deploy/compose.prod.yaml --env-file deploy/.env.prod ps
docker compose -f deploy/compose.prod.yaml --env-file deploy/.env.prod logs -f server
```

---

## Pointing the apps at your server

Release builds default to the hosted origin `https://convoy.24thframe.media`.

- **Android, custom build:** run `bash scripts/fetch-maplibre-terrain.sh` once in `android/` (Git Bash or WSL), then
  `./gradlew :app:assembleDebug -PconvoyServerUrl=https://<your-domain>` (`-PconvoyServerUrl` overrides both build
  types).
- **Android, debug:** the default is `http://127.0.0.1:8090`; forward the port with
  `adb reverse tcp:8090 tcp:8090` to reach a local server.
- **iOS:** the server URL is a setting in the app (Welcome and Profile screens).

---

## Troubleshooting

| Symptom | Fix |
| --- | --- |
| Android says "App not installed" / signature mismatch | Uninstall the old Convoyage once (pre-0.1.9 builds used per-run keys), then install the new APK |
| F-Droid does not show an update | Refresh the repository (pull-to-refresh in F-Droid → Updates), then check the repo is still listed |
| SideStore source is empty or Convoyage is missing | Re-add `https://kapsikkum.github.io/apps/sidestore.json`, refresh SideStore, and make sure the app was refreshed within 7 days |
| `docker compose ps` shows the server unhealthy | `docker compose -f deploy/compose.prod.yaml --env-file deploy/.env.prod logs server`; check migrations ran and that `JWT_SECRET`, `DATABASE_URL`/`POSTGRES_PASSWORD` and SMTP values are set |
| Magic-link email never arrives | `MAIL_DEV=1` only works in development (and is refused in production). Production needs a working `SMTP_URL` and `MAIL_FROM` |
| Windows Podman fails with a netavark/nftables error | Use the host-network fallback: `powershell scripts/up.ps1` |
| Phones cannot reach the server | Check `<lan-ip>` and that it is the right interface, allow inbound 8080 on the Private profile, and use `scripts/up.ps1` on Windows (it starts the LAN relay) |
| Ports look blocked from outside | Open 80/tcp, 443/tcp, 7881/tcp and 50000–50100/udp in the host firewall and any upstream firewall |

---

## Security checklist

- [ ] `.env` and `deploy/.env.prod` are never committed (verify with `git status`).
- [ ] `JWT_SECRET`, `POSTGRES_PASSWORD` and `ADMIN_TOKEN` are long random values, not examples.
- [ ] The Android release keystore is backed up; losing it breaks in-place updates for every installed copy.
- [ ] TLS is served by Caddy with automatic certificates; nothing else is published except 80/443 and LiveKit media.
- [ ] A backup restore has actually been tested.
- [ ] If a secret leaks, rotate it and redeploy. Rotating `JWT_SECRET` invalidates every access token (clients
      then refresh); rotating `PHOTOSCOUT_TOKEN_KEY` unlinks every PhotoScout account.

---

## Further reading

- [`server/README.md`](../server/README.md) — environment variables, API, PhotoScout, moderation, decisions and
  known limitations.
- [`README.md`](../README.md) — project overview and development quick start.
- [`protocol/README.md`](../protocol/README.md) — socket schema, OpenAPI document and codegen.
