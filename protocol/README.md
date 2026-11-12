# Convoyage protocol

`convoy.v1.schema.json` is the versioned JSON Schema for the convoy WebSocket (`/ws`):
`#/$defs/ClientMessage` for what a client sends, `#/$defs/ServerMessage` for what the server sends.

It is the source of truth for the wire format. Swift and Kotlin types will be generated from it later;
until then, keep it in step with `server/src/convoy/messages.ts` (the server's validator). A breaking
change means a new file (`convoy.v2.schema.json`), not an edit.

## REST API

`openapi.json` is the OpenAPI 3.1 description of the REST API (`/auth/*` and `/v1/*`), generated from the
server's route schemas by `npm run openapi` in `server/` and also served live at `/v1/openapi.json`.
Regenerate and commit it whenever a route or its schema changes. Apps build their clients against it.

The convoy socket stays in `convoy.v1.schema.json`. The `hazard` server message was added to v1 additively:
no existing message changed.

Also added to v1 additively, nothing existing changed:

- Client `convoy.rename` `{ t, room, name }` and `convoy.regroup` `{ t, room, enabled, separationM }` (1000 to 20000 m): leader only,
  everyone then gets an updated `room`. Anyone else gets an `error`.
- `Room.routeGeometry` (optional polyline6 string): a road-following route from the leader to the destination, present only while the
  server has one.

- Client `convoy.status` `{ t, status: "stopping" | "moving" | "sos", note? }` (note up to 140 characters). It applies to every
  room the sender is in (there is no `room` field). Not in a convoy gets an `error`.
- Server `status` `{ t, room, id, status, note?, position? }`, sent to every member of the room including the sender. `id` is the
  member id. For `sos`, `position` is the sender's last known position (a `Position`, or `null`); other statuses omit it.
  An `sos` also sends a push notification to the other members' registered devices.

Also added to v1 additively: `Member.vehicle` (optional `MemberVehicle` `{kind, build:{paint?, finish?, wheel?, rideHeightMm?}}` or null), the
member's primary vehicle for their avatar badge. REST: `UserRef.primaryVehicle` (optional `VehicleBadge` or null) and the `/v1/photoscout/*`
routes, all in `openapi.json`. `convoy.cruise` is unchanged on the wire; the cruise now comes from PhotoScout.

## Generated types

`node protocol/codegen/generate.mjs` writes Swift (`ios/.../Net/Generated/WireTypes.swift`) and Kotlin (`android/.../api/gen/WireTypes.kt`) types for every OpenAPI schema and every socket message. Regenerate and commit after changing either schema; `server/test/codegen.test.ts` fails when they are stale.
