import { test } from 'node:test';
import assert from 'node:assert/strict';
import { S3MediaStore } from '../src/media/store.js';

function fakeS3() {
  const objects = new Map<string, Buffer>();
  const calls: string[] = [];
  return {
    objects,
    calls,
    client: {
      async send(cmd: any) {
        const name = cmd.constructor.name;
        const { Bucket, Key, Body } = cmd.input;
        calls.push(`${name} ${Bucket}/${Key}`);
        if (name === 'PutObjectCommand') { objects.set(Key, Body); return {}; }
        if (name === 'DeleteObjectCommand') { objects.delete(Key); return {}; }
        const data = objects.get(Key);
        if (!data) throw Object.assign(new Error('nope'), { name: 'NoSuchKey', $metadata: { httpStatusCode: 404 } });
        return { Body: { transformToByteArray: async () => new Uint8Array(data) } };
      },
    },
  };
}

test('S3MediaStore round-trips, prefixes keys and maps a missing key to null', async () => {
  const f = fakeS3();
  const store = new S3MediaStore({ bucket: 'b', prefix: 'media/' }, f.client);
  await store.put('a/b.jpg', Buffer.from('hello'), 'image/jpeg');
  assert.deepEqual(f.calls, ['PutObjectCommand b/media/a/b.jpg']);
  assert.equal((await store.get('a/b.jpg'))?.toString(), 'hello');
  assert.equal(await store.get('missing'), null);
  await store.delete('a/b.jpg');
  assert.equal(await store.get('a/b.jpg'), null);
});

test('S3MediaStore refuses keys that climb out of the prefix', async () => {
  const store = new S3MediaStore({ bucket: 'b' }, fakeS3().client);
  await assert.rejects(store.put('../x', Buffer.from('x'), 'image/png'), /escapes/);
});
