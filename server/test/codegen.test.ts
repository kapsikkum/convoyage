import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

test('generated Swift and Kotlin wire types match openapi.json and convoy.v1.schema.json', () => {
  const script = fileURLToPath(new URL('../../protocol/codegen/generate.mjs', import.meta.url));
  const r = spawnSync(process.execPath, [script, '--check'], { encoding: 'utf8' });
  assert.equal(r.status, 0, r.stderr || 'run: node protocol/codegen/generate.mjs');
});