import test, { after, before } from 'node:test';
import assert from 'node:assert/strict';
import sharp from 'sharp';
import { encodePolyline, decodePolyline } from '../src/api/polyline.js';
import { exifDateTimeOriginal, sniffImage } from '../src/media/process.js';
import { makeJpeg, testServer, upload, type Session, type TestServer } from './apiHelpers.js';

let t: TestServer;
let a: Session;
let b: Session;
before(async () => {
  t = await testServer();
  a = await t.signup('ga');
  b = await t.signup('gb');
  await t.req('PATCH', '/v1/me', { token: a.token, body: { handle: 'garage_a' } });
  await t.req('PATCH', '/v1/me', { token: b.token, body: { handle: 'garage_b' } });
});
after(() => t.close());

const build = { paint: '#aa1122', finish: 'metallic', wheel: 'te37', rideHeightMm: -30, decals: [{ id: 'stripe', slot: 'hood', color: '#ffffff' }] };

test('vehicles: create with a build, first becomes primary, validation', async () => {
  const r = await t.req('POST', '/v1/vehicles', { token: a.token, body: { kind: 'car', make: 'Nissan', model: 'Silvia', year: 1999, nickname: 'Sylvie', build } });
  assert.equal(r.status, 201, JSON.stringify(r.body));
  assert.equal(r.body.isPrimary, true);
  assert.deepEqual(r.body.build, build);
  assert.equal(r.body.soldAt, null);
  const bad = (body: object) => t.req('POST', '/v1/vehicles', { token: a.token, body });
  assert.equal((await bad({ kind: 'boat', make: 'x', model: 'y' })).status, 400);
  assert.equal((await bad({ kind: 'car', make: 'x' })).status, 400);
  assert.equal((await bad({ kind: 'car', make: 'x', model: 'y', build: { paint: 'red' } })).status, 400);
  assert.equal((await bad({ kind: 'car', make: 'x', model: 'y', build: { rideHeightMm: 9000 } })).status, 400);
  assert.equal((await bad({ kind: 'car', make: 'x', model: 'y', build: { body: 'spaceship' } })).status, 400);
  assert.equal((await bad({ kind: 'car', make: 'x', model: 'y', build: { shape: 'Focus RS!' } })).status, 400);
  assert.equal((await bad({ kind: 'car', make: 'x', model: 'y', build: { finish: 'sparkly' } })).status, 400);
  assert.equal((await bad({ kind: 'car', make: 'x', model: 'y', year: 1700 })).status, 400);
  assert.equal((await t.req('POST', '/v1/vehicles', { token: a.token })).status, 400);
});

test('vehicles: list, edit, set primary, sold, delete, ownership', async () => {
  const bike = (await t.req('POST', '/v1/vehicles', { token: a.token, body: { kind: 'motorcycle', make: 'Ducati', model: 'Monster' } })).body;
  assert.equal(bike.isPrimary, false);
  const list = await t.req('GET', '/v1/vehicles', { token: a.token });
  assert.equal(list.body.length, 2);
  assert.equal(list.body.filter((v: any) => v.isPrimary).length, 1);

  const patched = await t.req('PATCH', `/v1/vehicles/${bike.id}`, { token: a.token, body: { nickname: 'Red', build: { paint: '#ff0000', finish: 'gloss' } } });
  assert.equal(patched.body.nickname, 'Red');
  assert.deepEqual(patched.body.build, { paint: '#ff0000', finish: 'gloss' });

  const primary = await t.req('POST', `/v1/vehicles/${bike.id}/primary`, { token: a.token });
  assert.equal(primary.body.isPrimary, true);
  assert.equal((await t.req('GET', '/v1/vehicles', { token: a.token })).body.filter((v: any) => v.isPrimary).length, 1);
  assert.equal((await t.req('GET', '/v1/me', { token: a.token })).body.primaryVehicle.id, bike.id);

  // Someone else cannot touch it.
  assert.equal((await t.req('PATCH', `/v1/vehicles/${bike.id}`, { token: b.token, body: { nickname: 'mine' } })).status, 404);
  assert.equal((await t.req('POST', `/v1/vehicles/${bike.id}/primary`, { token: b.token })).status, 404);
  assert.equal((await t.req('POST', `/v1/vehicles/${bike.id}/sold`, { token: b.token, body: {} })).status, 404);
  assert.equal((await t.req('DELETE', `/v1/vehicles/${bike.id}`, { token: b.token })).status, 404);
  // Public garage visible to others.
  assert.equal((await t.req('GET', '/v1/users/garage_a/vehicles', { token: b.token })).body.length, 2);

  // Selling clears primary and blocks re-promoting.
  const sold = await t.req('POST', `/v1/vehicles/${bike.id}/sold`, { token: a.token, body: {} });
  assert.equal(sold.status, 200);
  assert.ok(sold.body.soldAt);
  assert.equal(sold.body.isPrimary, false);
  assert.equal((await t.req('POST', `/v1/vehicles/${bike.id}/primary`, { token: a.token })).status, 400);
  assert.equal((await t.req('GET', '/v1/me', { token: a.token })).body.primaryVehicle, null);
  assert.equal((await t.req('POST', `/v1/vehicles/${bike.id}/sold`, { token: a.token, body: { soldAt: '2999-01-01T00:00:00Z' } })).status, 400);

  assert.equal((await t.req('DELETE', `/v1/vehicles/${bike.id}`, { token: a.token })).status, 200);
  assert.equal((await t.req('GET', `/v1/vehicles/${bike.id}`, { token: a.token })).status, 404);
});

test('vehicles: a build post shows the build to the feed', async () => {
  const v = (await t.req('GET', '/v1/vehicles', { token: a.token })).body[0];
  const post = await t.req('POST', '/v1/posts', { token: a.token, body: { kind: 'build', vehicleId: v.id, body: 'new wheels' } });
  assert.equal(post.status, 201);
  assert.equal(post.body.vehicle.id, v.id);
  assert.equal(post.body.vehicle.build.wheel, 'te37');
  assert.equal((await t.req('POST', '/v1/posts', { token: b.token, body: { kind: 'build', vehicleId: v.id } })).status, 400, 'not my vehicle');
  assert.equal((await t.req('POST', '/v1/posts', { token: a.token, body: { kind: 'build' } })).status, 400);
});

// ---- rides ---------------------------------------------------------------------------------------------------

const trace = encodePolyline([[-33.8688, 151.2093], [-33.87, 151.21], [-33.88, 151.23]]);
const finishBody = (extra: object = {}) => ({
  endedAt: new Date().toISOString(), distanceM: 4200.5, durationS: 600, maxSpeedMps: 25.3, avgSpeedMps: 7, elevationGainM: 80, maxLeanDeg: 41.5, polyline: trace, ...extra,
});

test('polyline codec round trips', () => {
  const pts: [number, number][] = [[38.5, -120.2], [40.7, -120.95], [43.252, -126.453]];
  assert.deepEqual(decodePolyline(encodePolyline(pts)), pts);
  assert.equal(encodePolyline(pts), '_p~iF~ps|U_ulLnnqC_mqNvxq`@');
  assert.throws(() => decodePolyline('~'));
  assert.throws(() => decodePolyline('\u0001abc'));
});

test('rides: start, finish with a trace, read back, and the state machine', async () => {
  const v = (await t.req('GET', '/v1/vehicles', { token: a.token })).body[0];
  const start = await t.req('POST', '/v1/rides', { token: a.token, body: { vehicleId: v.id, title: 'Sunday run', startLat: -33.8688, startLng: 151.2093, convoyCode: 'abcd-2345' } });
  assert.equal(start.status, 201, JSON.stringify(start.body));
  assert.equal(start.body.status, 'active');
  assert.equal(start.body.visibility, 'followers');
  assert.equal(start.body.convoyCode, 'ABCD2345');
  const id = start.body.id;

  assert.equal((await t.req('POST', '/v1/rides', { token: a.token, body: { vehicleId: '00000000-0000-4000-8000-000000000000' } })).status, 400);
  assert.equal((await t.req('POST', '/v1/rides', { token: a.token, body: { convoyCode: 'nope' } })).status, 400);
  assert.equal((await t.req('POST', `/v1/rides/${id}/finish`, { token: b.token, body: finishBody() })).status, 404, 'not mine');
  assert.equal((await t.req('POST', `/v1/rides/${id}/finish`, { token: a.token, body: finishBody({ polyline: '!!!bad' }) })).status, 400);
  assert.equal((await t.req('POST', `/v1/rides/${id}/finish`, { token: a.token, body: finishBody({ maxSpeedMps: 9000 }) })).status, 400);
  assert.equal((await t.req('POST', `/v1/rides/${id}/finish`, { token: a.token, body: finishBody({ endedAt: '2001-01-01T00:00:00Z' }) })).status, 400);
  assert.equal((await t.req('POST', `/v1/rides/${id}/finish`, { token: a.token, body: { endedAt: new Date().toISOString() } })).status, 400);

  // While active only the owner can see it.
  assert.equal((await t.req('GET', `/v1/rides/${id}`, { token: b.token })).status, 404);

  const done = await t.req('POST', `/v1/rides/${id}/finish`, { token: a.token, body: finishBody({ visibility: 'public' }) });
  assert.equal(done.status, 200, JSON.stringify(done.body));
  assert.equal(done.body.status, 'finished');
  assert.equal(done.body.distanceM, 4200.5);
  assert.equal(done.body.maxLeanDeg, 41.5);
  assert.equal(done.body.polyline, trace);
  assert.equal(done.body.polylinePrecision, 5);
  assert.equal(done.body.visibility, 'public');
  assert.equal((await t.req('POST', `/v1/rides/${id}/finish`, { token: a.token, body: finishBody() })).status, 409);

  const read = await t.req('GET', `/v1/rides/${id}`, { token: b.token });
  assert.equal(read.status, 200);
  assert.equal(read.body.polyline, trace);
  const mine = await t.req('GET', '/v1/rides', { token: a.token });
  assert.equal(mine.body.items[0].id, id);
  assert.equal(mine.body.items[0].polyline, undefined, 'lists do not carry the trace');
  assert.equal((await t.req('GET', '/v1/users/garage_a/rides', { token: b.token })).body.items.length, 1);
});

test('rides: visibility private / followers / public and list paging', async () => {
  const mk = async (visibility: string) => {
    const r = (await t.req('POST', '/v1/rides', { token: a.token, body: { visibility, title: visibility } })).body;
    await t.req('POST', `/v1/rides/${r.id}/finish`, { token: a.token, body: finishBody() });
    return r.id as string;
  };
  const priv = await mk('private');
  const fol = await mk('followers');
  const pub = await mk('public');
  const titles = async (s: Session) => (await t.req('GET', '/v1/users/garage_a/rides?limit=50', { token: s.token })).body.items.map((r: any) => r.id);

  assert.equal((await t.req('GET', `/v1/rides/${priv}`, { token: b.token })).status, 404);
  assert.equal((await t.req('GET', `/v1/rides/${fol}`, { token: b.token })).status, 404);
  assert.equal((await t.req('GET', `/v1/rides/${pub}`, { token: b.token })).status, 200);
  assert.ok(!(await titles(b)).includes(priv) && !(await titles(b)).includes(fol));

  await t.req('POST', '/v1/users/garage_a/follow', { token: b.token });
  assert.equal((await t.req('GET', `/v1/rides/${fol}`, { token: b.token })).status, 200);
  assert.equal((await t.req('GET', `/v1/rides/${priv}`, { token: b.token })).status, 404, 'private stays private');
  assert.ok((await titles(a)).includes(priv));

  // Blocked users lose everything.
  await t.req('POST', '/v1/users/garage_b/block', { token: a.token });
  assert.equal((await t.req('GET', `/v1/rides/${pub}`, { token: b.token })).status, 404);
  await t.req('DELETE', '/v1/users/garage_b/block', { token: a.token });

  // Paging: two at a time, no duplicates, ends with a null cursor.
  const seen: string[] = [];
  let cursor: string | null = null;
  for (let i = 0; i < 10; i++) {
    const p: any = await t.req('GET', `/v1/rides?limit=2${cursor ? `&before=${encodeURIComponent(cursor)}` : ''}`, { token: a.token });
    assert.equal(p.status, 200);
    seen.push(...p.body.items.map((r: any) => r.id));
    cursor = p.body.nextCursor;
    if (!cursor) break;
  }
  assert.equal(new Set(seen).size, seen.length);
  assert.equal(seen.length, 4);
  assert.equal((await t.req('GET', '/v1/rides?before=garbage', { token: a.token })).status, 400);

  assert.equal((await t.req('PATCH', `/v1/rides/${priv}`, { token: a.token, body: { visibility: 'public', title: 'now public' } })).body.title, 'now public');
  assert.equal((await t.req('DELETE', `/v1/rides/${priv}`, { token: b.token })).status, 404);
  assert.equal((await t.req('DELETE', `/v1/rides/${priv}`, { token: a.token })).status, 200);
});

// ---- media ---------------------------------------------------------------------------------------------------

test('media: upload a JPEG, thumbnail, fetch bytes; EXIF is stripped', async () => {
  const withExif = await makeJpeg(80, 60, true);
  assert.ok((await sharp(withExif).metadata()).exif, 'fixture has EXIF');
  const r = await upload(t, a.token, withExif);
  assert.equal(r.status, 201, JSON.stringify(r.body));
  assert.equal(r.body.mime, 'image/jpeg');
  assert.equal(r.body.width, 80);
  assert.equal(r.body.height, 60);
  const row = await t.database.db.selectFrom('media').selectAll().where('id', '=', r.body.id).executeTakeFirstOrThrow();
  assert.match(row.sha256, /^[0-9a-f]{64}$/);
  const stored = t.media.files.get(row.storage_key)!;
  assert.equal((await sharp(stored).metadata()).exif, undefined, 'stored file carries no EXIF');
  assert.ok(!stored.includes(Buffer.from('SecretCam')) && !stored.includes(Buffer.from('Jane Rider')));
  assert.ok(t.media.files.has(row.thumb_key!));

  const bytes = await fetch(`${t.base}${r.body.url}`, { headers: { authorization: `Bearer ${b.token}` } });
  assert.equal(bytes.status, 200);
  assert.equal(bytes.headers.get('content-type'), 'image/jpeg');
  assert.equal(sniffImage(Buffer.from(await bytes.arrayBuffer())), 'jpeg');
  const thumb = await fetch(`${t.base}${r.body.thumbUrl}`, { headers: { authorization: `Bearer ${b.token}` } });
  assert.equal(thumb.status, 200);
  assert.equal((await fetch(`${t.base}${r.body.url}`)).status, 401);
});

test('media: validation by magic bytes, size cap, decompression bombs, auth', async () => {
  const t1 = await upload(t, a.token, Buffer.from('<?php echo 1; ?>'), 'shell.jpg', 'image/jpeg');
  assert.equal(t1.status, 415);
  const svg = await upload(t, a.token, Buffer.from('<svg xmlns="http://www.w3.org/2000/svg"><script>alert(1)</script></svg>'), 'x.svg', 'image/svg+xml');
  assert.equal(svg.status, 415);
  // Real header, garbage body.
  const truncated = (await makeJpeg(50, 50)).subarray(0, 120);
  assert.equal((await upload(t, a.token, truncated)).status, 400);
  assert.equal((await upload(t, a.token, Buffer.alloc(0))).status, 400);
  const big = Buffer.concat([(await makeJpeg(10, 10)), Buffer.alloc(16 * 1024 * 1024)]);
  assert.equal((await upload(t, a.token, big)).status, 413);
  assert.equal((await upload(t, 'bad-token', await makeJpeg())).status, 401);
  const noFile = await fetch(`${t.base}/v1/media`, { method: 'POST', headers: { authorization: `Bearer ${a.token}`, 'content-type': 'application/json' }, body: '{}' });
  assert.equal(noFile.status, 400);
  const png = await sharp({ create: { width: 10, height: 10, channels: 4, background: { r: 0, g: 0, b: 0, alpha: 0.5 } } }).png().toBuffer();
  assert.equal((await upload(t, a.token, png, 'a.png', 'image/png')).body.mime, 'image/png');
  // Oversized images are scaled to 2560 on the long edge.
  const wide = await sharp({ create: { width: 3200, height: 100, channels: 3, background: '#223344' } }).jpeg().toBuffer();
  const scaled = await upload(t, a.token, wide);
  assert.equal(scaled.body.width, 2560);
});

test('media: orientation is applied, and DateTimeOriginal survives as takenAt only', async () => {
  const sensor = await sharp({ create: { width: 40, height: 20, channels: 3, background: '#445566' } })
    .withMetadata({ orientation: 6 }).withExif({ IFD0: { Make: 'x' }, IFD2: { DateTimeOriginal: '2024:05:06 07:08:09' } } as never).jpeg().toBuffer();
  const r = await upload(t, a.token, sensor);
  assert.equal(r.status, 201, JSON.stringify(r.body));
  assert.equal(r.body.width, 20, 'rotated upright: 40x20 with orientation 6 is 20x40');
  assert.equal(r.body.height, 40);
  assert.equal(r.body.takenAt, '2024-05-06T07:08:09.000Z');
  const row = await t.database.db.selectFrom('media').selectAll().where('id', '=', r.body.id).executeTakeFirstOrThrow();
  assert.equal((await sharp(t.media.files.get(row.storage_key)!).metadata()).exif, undefined);
  assert.equal(exifDateTimeOriginal(Buffer.from('nope')), null);
});

test('media: ride photos attach, show on the ride and stay on the owner', async () => {
  const ride = (await t.req('POST', '/v1/rides', { token: a.token, body: { visibility: 'public' } })).body;
  const mine = (await upload(t, a.token, await makeJpeg())).body;
  const theirs = (await upload(t, b.token, await makeJpeg())).body;
  const ok = await t.req('POST', `/v1/rides/${ride.id}/photos`, { token: a.token, body: { mediaId: mine.id, lat: -33.87, lng: 151.21 } });
  assert.equal(ok.status, 201, JSON.stringify(ok.body));
  assert.equal(ok.body.media.id, mine.id);
  assert.equal((await t.req('POST', `/v1/rides/${ride.id}/photos`, { token: a.token, body: { mediaId: theirs.id } })).status, 400, 'someone else media');
  assert.equal((await t.req('POST', `/v1/rides/${ride.id}/photos`, { token: b.token, body: { mediaId: theirs.id } })).status, 404, 'someone else ride');
  assert.equal((await t.req('POST', `/v1/rides/${ride.id}/photos`, { token: a.token, body: { mediaId: mine.id, lat: 1 } })).status, 400);
  assert.equal((await t.req('GET', `/v1/rides/${ride.id}`, { token: a.token })).body.photos.length, 1);
  assert.equal((await t.req('DELETE', `/v1/rides/${ride.id}/photos/${ok.body.id}`, { token: a.token })).status, 200);
  assert.equal((await t.req('GET', `/v1/rides/${ride.id}`, { token: a.token })).body.photos.length, 0);
});

test('avatar: set from my own upload', async () => {
  const m = (await upload(t, a.token, await makeJpeg())).body;
  const r = await t.req('PATCH', '/v1/me', { token: a.token, body: { avatarMediaId: m.id } });
  assert.equal(r.body.avatarMediaId, m.id);
  const stolen = (await upload(t, b.token, await makeJpeg())).body;
  assert.equal((await t.req('PATCH', '/v1/me', { token: a.token, body: { avatarMediaId: stolen.id } })).status, 400);
  assert.equal((await t.req('PATCH', '/v1/me', { token: a.token, body: { avatarMediaId: null } })).body.avatarMediaId, null);
});
