import test from 'node:test';
import assert from 'node:assert/strict';
import { CONVOY_CHAT_GAP_MS, CONVOY_MESSAGE_TTL_MS, MAX_CONVOY_MESSAGES, Rooms } from '../src/convoy/rooms.js';
import { parseClientMessage } from '../src/convoy/messages.js';

test('convoy messages require membership, authenticate their sender, deduplicate retries and expire', () => {
  let now = 10_000;
  const rooms = new Rooms(() => now);
  const made = rooms.create({ id: 'a', username: 'Alice', avatar: '' });
  assert.ok(made.ok);
  const code = made.value.code;
  assert.equal(rooms.message(code, 'outsider', 'Hello', '1').ok, false);
  const first = rooms.message(code, 'a', ' Hello ', '1');
  assert.ok(first.ok);
  assert.equal(first.value.username, 'Alice');
  assert.equal(first.value.text, 'Hello');
  assert.deepEqual(rooms.message(code, 'a', 'different retry text', '1'), first);
  assert.equal(rooms.message(code, 'a', 'Too soon', '2').ok, false);
  assert.equal(rooms.message(code, 'a', 'x'.repeat(501), '3').ok, false);
  for (let i = 0; i < MAX_CONVOY_MESSAGES + 10; i++) {
    now += CONVOY_CHAT_GAP_MS;
    assert.ok(rooms.message(code, 'a', `Message ${i}`, `many-${i}`).ok);
  }
  assert.equal(rooms.view(code)!.messages.length, MAX_CONVOY_MESSAGES);
  now += CONVOY_MESSAGE_TTL_MS;
  assert.deepEqual(rooms.view(code)!.messages, []);
});

test('rest lookups share a cooldown and a destination change invalidates pending results', () => {
  const rooms = new Rooms();
  const made = rooms.create({ id: 'a', username: 'Alice', avatar: '' }, { route: { name: 'Route', stops: [[149, -33], [150, -33]] } });
  assert.ok(made.ok);
  const code = made.value.code;
  rooms.join(code, { id: 'b', username: 'Bob', avatar: '' });
  const reserved = rooms.reserveRestLookup(code, 'a');
  assert.ok(reserved.ok);
  assert.equal(rooms.reserveRestLookup(code, 'b').ok, false);
  rooms.setDestination(code, 'a', { lat: -33, lng: 150, label: 'Changed' });
  assert.notEqual(rooms.navigationVersion(code), reserved.value);
});

test('convoy message parser rejects empty, oversize and missing ids and drops spoofed identities', () => {
  const base = { t: 'convoy.message', room: 'ROOM', text: 'Wait for me', clientId: '123' };
  assert.deepEqual(parseClientMessage(JSON.stringify({ ...base, userId: 'spoof', username: 'spoof' })), { ok: true, message: base });
  for (const patch of [{ text: '' }, { text: 'x'.repeat(501) }, { clientId: '' }, { room: '' }]) assert.equal(parseClientMessage(JSON.stringify({ ...base, ...patch })).ok, false);
});
