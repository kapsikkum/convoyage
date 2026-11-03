import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { Ajv2020 } from 'ajv/dist/2020.js';
import { generateOpenApi, OPENAPI_PATH } from '../src/openapi.js';
import { createPgliteDatabase } from '../src/db/index.js';
import { migrate } from '../src/db/migrate.js';
import { testServer } from './apiHelpers.js';

test('OpenAPI 3.1 document: paths, schemas, security, and it matches protocol/openapi.json', async () => {
  const text = await generateOpenApi();
  const doc = JSON.parse(text);
  assert.equal(doc.openapi, '3.1.0');
  for (const path of ['/auth/magic-link', '/auth/magic-link/verify', '/auth/apple', '/auth/google', '/auth/refresh', '/auth/logout',
    '/v1/me', '/v1/users/{handle}', '/v1/vehicles', '/v1/rides', '/v1/rides/{id}/finish', '/v1/media', '/v1/feed', '/v1/posts', '/v1/hazards', '/v1/hazards/{id}/confirm']) {
    assert.ok(doc.paths[path], `missing ${path}`);
  }
  for (const name of ['Me', 'Profile', 'Vehicle', 'Ride', 'Post', 'Hazard', 'FeedPage', 'Error', 'AuthResult']) assert.ok(doc.components.schemas[name], `missing schema ${name}`);
  assert.deepEqual(doc.paths['/v1/me'].get.security, [{ bearerAuth: [] }]);
  assert.ok(doc.paths['/v1/media'].post.requestBody.content['multipart/form-data']);
  assert.ok(!JSON.stringify(doc).includes('"$ref":"Error#'), 'no unresolved refs');
  assert.equal(doc.paths['/v1/openapi.json'], undefined);
  // The document itself is valid JSON Schema-ish: every $ref points at something that exists.
  const refs = [...text.matchAll(/"\$ref": "#\/components\/schemas\/([^"]+)"/g)].map((m) => m[1]);
  assert.ok(refs.length > 20);
  for (const r of refs) assert.ok(doc.components.schemas[r], `dangling ref ${r}`);
  // Every component compiles as JSON Schema 2020-12 (what OpenAPI 3.1 uses).
  const ajv = new Ajv2020({ strict: false, validateFormats: false });
  for (const name of Object.keys(doc.components.schemas)) ajv.compile({ $ref: `#/components/schemas/${name}`, components: doc.components });

  // The committed copy must be current: run `npm run openapi` after changing a route.
  assert.equal(readFileSync(OPENAPI_PATH, 'utf8'), text, 'protocol/openapi.json is stale: run npm run openapi');
});

test('the live server serves the same document at /v1/openapi.json', async () => {
  const t = await testServer();
  const res = await t.req('GET', '/v1/openapi.json');
  assert.equal(res.status, 200);
  assert.equal(res.body.openapi, '3.1.0');
  assert.ok(res.body.paths['/v1/feed']);
  await t.close();
});

test('migrations are idempotent', async () => {
  const db = await createPgliteDatabase();
  assert.deepEqual(await migrate(db), ['001_init.sql']);
  assert.deepEqual(await migrate(db), []);
  const tables = await db.db.selectFrom('information_schema.tables' as never).select('table_name' as never).where('table_schema' as never, '=', 'public' as never).execute();
  const names = (tables as unknown as { table_name: string }[]).map((r) => r.table_name);
  for (const n of ['users', 'auth_identities', 'refresh_tokens', 'magic_link_tokens', 'profiles', 'follows', 'blocks', 'vehicles', 'rides', 'ride_photos', 'media', 'posts', 'post_likes', 'comments', 'reports', 'hazard_reports', 'hazard_acks']) {
    assert.ok(names.includes(n), `missing table ${n}`);
  }
  await db.close();
});
