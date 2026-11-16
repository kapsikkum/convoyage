# Convoyage

A live convoy app for cars and motorcycles: an Android app, an iOS app, and a self-hostable server.

> **Beta.** Convoyage is in active development. iOS is sideloaded with SideStore or AltStore (the IPA is unsigned),
> Android ships without Google services, and physical-device testing is ongoing. Expect rough edges and breaking
> changes between releases.

## Features

- **Live convoy map** — everyone in a room on one map, with optional road-following route geometry from the leader
  to the destination.
- **Regroup alerts** — automatic regroup when the convoy spreads past a set distance, plus rest-stop search
  (fuel, rest areas, cafes) through OpenStreetMap.
- **Hazard reports** — police, crashes and road hazards on the map, confirmed or dismissed by other members;
  hazards expire and reporters stay anonymous.
- **Motorcycle ride HUD** — a ride screen with speed, lean placeholder and large push-to-talk targets, and a car
  variant without the lean meter.
- **Vehicle garage** — cars, utes, 4WDs and motorcycles rendered in a PS1-style shader, used as profile badges.
- **PhotoScout** — spots and events on the map when you link a PhotoScout account.
- **Push-to-talk** — convoy voice over LiveKit.
- **Route planning and conditions** — OSRM routing (car or motorcycle profile) with live route conditions.
- **Self-hostable API** — one Fastify server, Postgres, and a WebSocket for convoy state.

## Install

### Apps

| Platform | How |
| --- | --- |
| Android 9+ | F-Droid repo, or the APK from a release |
| iOS 17+ | SideStore/AltStore source, or the unsigned IPA from a release |

- Step-by-step instructions: [`docs/INSTALL.md`](docs/INSTALL.md)
- Apps page (F-Droid repo and SideStore source): <https://kapsikkum.github.io/apps>
- Downloads: <https://github.com/kapsikkum/convoyage/releases>

### Server

- Local or production server steps: [`docs/INSTALL.md`](docs/INSTALL.md)
- Environment variables, API, PhotoScout and design decisions: [`server/README.md`](server/README.md)

## Quick start (development)

### Server

Node 22 or newer.

```bash
cd server
npm install
npm run dev
curl localhost:8080/health
```

With no `DATABASE_URL`, development falls back to an embedded pglite database in `server/data/pgdata`, migrates it
at start, and needs no container. With `MAIL_DEV=1` the magic-link endpoint returns the link in the response.

Full stack (Postgres + API) with Compose, from the repo root:

```bash
cp .env.example .env          # set JWT_SECRET to 48+ random characters
docker compose up -d --build  # or: podman compose up -d --build
curl localhost:8080/health
```

### Android

```bash
cd android
bash scripts/fetch-maplibre-terrain.sh    # once (Git Bash/WSL): unpacks the MapLibre terrain build into third_party/
./gradlew :app:assembleDebug              # Windows: .\gradlew.bat :app:assembleDebug
./gradlew :app:testDebugUnitTest          # Windows: .\gradlew.bat :app:testDebugUnitTest
```

Debug builds default to `http://127.0.0.1:8090`; reach a local server there with
`adb reverse tcp:8090 tcp:8090`. Pass `-PconvoyServerUrl=<url>` to point either build type somewhere else.

### iOS

XcodeGen is required — the `.xcodeproj` is generated, not committed.

```bash
cd ios
xcodegen generate
open Convoyage.xcodeproj
```

Build for a device with your own signing team. CI builds an unsigned IPA (`Convoyage-unsigned.ipa`) with code
signing disabled.

## Repository layout

| Path | What |
| --- | --- |
| `android/` | Kotlin/Compose app (minSdk 28), MapLibre map, PS1 vehicle renderer |
| `ios/` | SwiftUI app (iOS 17+), XcodeGen project (`ios/project.yml`) |
| `server/` | Fastify + Kysely API, convoy WebSocket, migrations, tests |
| `protocol/` | `convoy.v1.schema.json` (socket) and `openapi.json` (REST), plus codegen |
| `design-tools/` | Blender bodies, PS1 reference renderer and render scripts |
| `deploy/` | Production Compose stack, Caddyfile, LiveKit config, OSRM and backup scripts |
| `docs/` | App and server install guide |
| `scripts/` | `up.ps1`/`down.ps1` Windows Podman fallback, LAN relay, changelog builder |
| `.github/workflows/` | Server tests, container image, Android/iOS builds and releases |

## Releases and changelog

Every `v*` tag builds and publishes a GitHub Release with `Convoyage-release.apk` and `Convoyage-unsigned.ipa`.

- Tags containing a `-` (for example `v0.1.10-beta.1`) are published as pre-releases and also move the rolling
  `beta` release, which the apps page follows.
- Stable tags (for example `v0.1.9`) are marked as Latest.
- Release notes are generated from commits by `scripts/changelog.mjs`: commits are grouped by their subject prefix,
  `release:` commits are skipped, and the body links back to the comparison on GitHub.

See <https://github.com/kapsikkum/convoyage/releases>.

## Status and limitations

- **Beta.** APIs and wire formats may change; the protocol schemas are versioned so breaking changes create a new
  version rather than editing the current one.
- **Android 9+** (`minSdk 28`), **iOS 17+** (deployment target `17.0`).
- **No Google Play Services and no Firebase.** Android push uses UnifiedPush distributors.
- Optional integrations are off unless configured: LiveKit voice, S3-compatible media storage, APNs/FCM push,
  PhotoScout and self-hosted OSRM. Voice returns 501 without LiveKit keys, PhotoScout routes return 503 without
  `PHOTOSCOUT_URL`, push is a no-op until configured, and routing defaults to the rate-limited public OSRM demo.
- Convoy rooms live in one server process and positions are never stored or logged.
- The production Compose stack serves TLS through Caddy; the API port (8080) is not published.

## License

Proprietary. There is no `LICENSE` file in this repository; nothing here grants a right to redistribute or reuse
the code.
