import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { Ajv2020 } from 'ajv/dist/2020.js';
import { Rooms } from '../src/convoy/rooms.js';

const schema = JSON.parse(readFileSync(new URL('../../protocol/convoy.v1.schema.json', import.meta.url), 'utf8'));
const ajv = new Ajv2020({ strict: false });
ajv.addSchema(schema, 'v1');
const client = ajv.compile({ $ref: 'v1#/$defs/ClientMessage' });
const server = ajv.compile({ $ref: 'v1#/$defs/ServerMessage' });

test('sample client messages validate, and bad ones do not', () => {
  for (const m of [
    { t: 'ping' },
    { t: 'pos', lat: -33, lng: 151, heading: null, speed: 3.5 },
    { t: 'convoy.create', name: 'Run', automaticRegroup: { enabled: true, separationM: 5000 } },
    { t: 'convoy.route', room: 'ABCDEFGH', route: { name: 'R', stops: [[151, -33], [152, -33]] } },
    { t: 'convoy.rest', room: 'ABCDEFGH', action: 'find' },
    { t: 'convoy.message', room: 'ABCDEFGH', text: 'hi', clientId: '1' },
  ]) assert.ok(client(m), JSON.stringify([m, client.errors]));
  for (const m of [{ t: 'pos', lat: 91, lng: 0 }, { t: 'convoy.rest', room: 'X', action: 'dance' }, { t: 'convoy.route', room: 'X', route: { name: 'R', stops: [[1, 2]] } }, { t: 'nope' }]) {
    assert.equal(client(m), false, JSON.stringify(m));
  }
});

test('messages the server really builds validate as server messages', () => {
  const rooms = new Rooms();
  const made = rooms.create({ id: 'a', username: 'Alice', avatar: '' }, { destination: { lat: 1, lng: 2, label: 'Here' } });
  assert.ok(made.ok);
  const msg = rooms.message(made.value.code, 'a', 'hello', 'c1');
  assert.ok(msg.ok);
  const view = rooms.view(made.value.code)!;
  for (const m of [
    { t: 'pong' },
    { t: 'hello', me: 'a', rooms: [view] },
    { t: 'room', room: view },
    { t: 'left', room: view.code, removed: true },
    { t: 'error', error: 'nope', in: 'pos' },
    { t: 'convoy.message', room: view.code, message: msg.value },
    { t: 'pos', room: view.code, id: 'a', lat: 1, lng: 2, heading: null, speed: null, ts: 5 },
    { t: 'hazard', room: view.code, hazard: { id: '8c5f6d1e-1a2b-4c3d-9e8f-0a1b2c3d4e5f', kind: 'hazard', subkind: 'gravel', lat: 1, lng: 2, heading: null, createdAt: '2026-01-01T00:00:00.000Z', expiresAt: '2026-01-01T00:30:00.000Z' } },
  ]) assert.ok(server(m), JSON.stringify([m.t, server.errors]));
  assert.equal(server({ t: 'hazard', room: view.code, hazard: { id: 'x', kind: 'ufo' } }), false);
  assert.equal(server({ t: 'room', room: { ...view, code: 'oops' } }), false);
});
