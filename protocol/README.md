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

- Client `convoy.status` `{ t, status: "stopping" | "moving" | "sos", note? }` (note up to 140 characters). It applies to every
  room the sender is in (there is no `room` field). Not in a convoy gets an `error`.
- Server `status` `{ t, room, id, status, note?, position? }`, sent to every member of the room including the sender. `id` is the
  member id. For `sos`, `position` is the sender's last known position (a `Position`, or `null`); other statuses omit it.
  An `sos` also sends a push notification to the other members' registered devices.
