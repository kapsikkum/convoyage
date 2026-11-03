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
