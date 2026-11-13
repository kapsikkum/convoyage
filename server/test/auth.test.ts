import test, { after, before } from 'node:test';
import assert from 'node:assert/strict';
import { SignJWT, exportJWK, generateKeyPair, createLocalJWKSet } from 'jose';
import { oidcVerifier, APPLE_ISSUER, GOOGLE_ISSUERS } from '../src/api/providers.js';
import { sha256hex } from '../src/security.js';
import { testServer, type TestServer } from './apiHelpers.js';

let t: TestServer;
const APPLE_AUD = 'com.example.convoyage';
const GOOGLE_AUD = 'google-client-id.apps.googleusercontent.com';
const keys = await generateKeyPair('RS256');
const jwk = { ...(await exportJWK(keys.publicKey)), kid: 'k1', alg: 'RS256', use: 'sig' };
const jwks = createLocalJWKSet({ keys: [jwk] });

const idToken = (claims: Record<string, unknown>, o: { iss: string; aud: string; exp?: string; key?: typeof keys.privateKey }) =>
  new SignJWT(claims).setProtectedHeader({ alg: 'RS256', kid: 'k1' }).setIssuer(o.iss).setAudience(o.aud).setIssuedAt().setExpirationTime(o.exp ?? '10m').sign(o.key ?? keys.privateKey);

before(async () => {
  t = await testServer({
    providers: {
      apple: oidcVerifier({ issuers: [APPLE_ISSUER], audiences: [APPLE_AUD], jwks }),
      google: oidcVerifier({ issuers: GOOGLE_ISSUERS, audiences: [GOOGLE_AUD], jwks }),
    },
  });
});
after(() => t.close());

const linkToken = async (email: string) => {
  const r = await t.req('POST', '/auth/magic-link', { body: { email } });
  assert.equal(r.status, 202);
  return new URL(r.body.devLink).searchParams.get('token')!;
};

test('magic link: request, verify, sign in, and the token works on /v1/me', async () => {
  const token = await linkToken('First@Example.com');
  const done = await t.req('POST', '/auth/magic-link/verify', { body: { token } });
  assert.equal(done.status, 200);
  assert.equal(done.body.isNewUser, true);
  assert.equal(done.body.tokenType, 'Bearer');
  assert.equal(done.body.expiresIn, 900);
  const me = await t.req('GET', '/v1/me', { token: done.body.accessToken });
  assert.equal(me.status, 200);
  assert.equal(me.body.email, 'first@example.com');
  assert.match(me.body.handle, /^rider_[0-9a-f]{8}$/);
  assert.equal(me.body.handleChosen, false);
  // A second sign-in with the same email is the same account.
  const again = await t.req('POST', '/auth/magic-link/verify', { body: { token: await linkToken('first@example.com') } });
  assert.equal(again.body.isNewUser, false);
  const me2 = await t.req('GET', '/v1/me', { token: again.body.accessToken });
  assert.equal(me2.body.id, me.body.id);
});

test('magic link: single use', async () => {
  const token = await linkToken('reuse@example.com');
  assert.equal((await t.req('POST', '/auth/magic-link/verify', { body: { token } })).status, 200);
  assert.equal((await t.req('POST', '/auth/magic-link/verify', { body: { token } })).status, 401);
});

test('magic link: expired, unknown and malformed tokens are refused; only the hash is stored', async () => {
  const token = await linkToken('expired@example.com');
  const stored = await t.database.db.selectFrom('magic_link_tokens').selectAll().where('email', '=', 'expired@example.com').executeTakeFirstOrThrow();
  assert.equal(stored.token_hash, sha256hex(token));
  assert.notEqual(stored.token_hash, token);
  await t.database.db.updateTable('magic_link_tokens').set({ expires_at: new Date(Date.now() - 1000) }).where('id', '=', stored.id).execute();
  assert.equal((await t.req('POST', '/auth/magic-link/verify', { body: { token } })).status, 401);
  assert.equal((await t.req('POST', '/auth/magic-link/verify', { body: { token: 'x'.repeat(43) } })).status, 401);
  assert.equal((await t.req('POST', '/auth/magic-link/verify', { body: { token: 'short' } })).status, 400);
  assert.equal((await t.req('POST', '/auth/magic-link', { body: { email: 'not-an-email' } })).status, 400);
});

test('magic link: a link lives 15 minutes', async () => {
  await linkToken('ttl@example.com');
  const row = await t.database.db.selectFrom('magic_link_tokens').selectAll().where('email', '=', 'ttl@example.com').executeTakeFirstOrThrow();
  const ttl = new Date(row.expires_at).getTime() - new Date(row.created_at).getTime();
  assert.ok(Math.abs(ttl - 15 * 60_000) < 5000, `ttl ${ttl}`);
});

test('without MAIL_DEV the link is not returned', async () => {
  const quiet = await testServer({ config: { mailDev: false }, database: t.database });
  const r = await quiet.req('POST', '/auth/magic-link', { body: { email: 'quiet@example.com' } });
  assert.equal(r.status, 202);
  assert.deepEqual(r.body, { ok: true });
  await quiet.close();
});

test('the access token is rejected when missing, malformed, forged or expired', async () => {
  assert.equal((await t.req('GET', '/v1/me')).status, 401);
  assert.equal((await t.req('GET', '/v1/me', { token: 'nonsense' })).status, 401);
  const s = await t.signup();
  const forged = await new SignJWT({}).setProtectedHeader({ alg: 'HS256' }).setSubject(s.id).setIssuer('convoyage').setAudience('convoyage-api')
    .setExpirationTime('10m').sign(new TextEncoder().encode('z'.repeat(40)));
  assert.equal((await t.req('GET', '/v1/me', { token: forged })).status, 401);
  const expired = await new SignJWT({}).setProtectedHeader({ alg: 'HS256' }).setSubject(s.id).setIssuer('convoyage').setAudience('convoyage-api')
    .setIssuedAt(Math.floor(Date.now() / 1000) - 2000).setExpirationTime(Math.floor(Date.now() / 1000) - 1000).sign(new TextEncoder().encode(t.config.jwtSecret));
  assert.equal((await t.req('GET', '/v1/me', { token: expired })).status, 401);
  assert.equal((await t.req('GET', '/v1/me', { token: s.token })).status, 200);
});

test('refresh: rotation gives a new pair and the old token dies', async () => {
  const s = await t.signup();
  const r1 = await t.req('POST', '/auth/refresh', { body: { refreshToken: s.refresh } });
  assert.equal(r1.status, 200);
  assert.notEqual(r1.body.refreshToken, s.refresh);
  assert.equal((await t.req('GET', '/v1/me', { token: r1.body.accessToken })).status, 200);
  const r2 = await t.req('POST', '/auth/refresh', { body: { refreshToken: r1.body.refreshToken } });
  assert.equal(r2.status, 200);
  const hashes = await t.database.db.selectFrom('refresh_tokens').select('token_hash').where('user_id', '=', s.id).execute();
  assert.equal(hashes.length, 3);
  assert.ok(!hashes.some((h) => h.token_hash === s.refresh), 'refresh tokens are stored hashed');
});

test('refresh: reusing a rotated token revokes the whole family', async () => {
  const s = await t.signup();
  const r1 = await t.req('POST', '/auth/refresh', { body: { refreshToken: s.refresh } });
  assert.equal(r1.status, 200);
  // The thief (or a confused client) presents the old token again.
  assert.equal((await t.req('POST', '/auth/refresh', { body: { refreshToken: s.refresh } })).status, 401);
  // The legitimate newest token is now dead too.
  assert.equal((await t.req('POST', '/auth/refresh', { body: { refreshToken: r1.body.refreshToken } })).status, 401);
  // Another login of the same user is a different family and keeps working.
  const other = await t.signup();
  assert.equal((await t.req('POST', '/auth/refresh', { body: { refreshToken: other.refresh } })).status, 200);
});

test('refresh: expired and unknown tokens are refused', async () => {
  const s = await t.signup();
  await t.database.db.updateTable('refresh_tokens').set({ expires_at: new Date(Date.now() - 1000) }).where('user_id', '=', s.id).execute();
  assert.equal((await t.req('POST', '/auth/refresh', { body: { refreshToken: s.refresh } })).status, 401);
  assert.equal((await t.req('POST', '/auth/refresh', { body: { refreshToken: 'q'.repeat(43) } })).status, 401);
});

test('logout revokes the session and is idempotent', async () => {
  const s = await t.signup();
  assert.equal((await t.req('POST', '/auth/logout', { body: { refreshToken: s.refresh } })).status, 200);
  assert.equal((await t.req('POST', '/auth/refresh', { body: { refreshToken: s.refresh } })).status, 401);
  assert.equal((await t.req('POST', '/auth/logout', { body: { refreshToken: s.refresh } })).status, 200);
  assert.equal((await t.req('POST', '/auth/logout', { body: { refreshToken: 'never-issued' } })).status, 200);
});

test('Sign in with Apple: valid token creates an account, repeat signs in, bad tokens are refused', async () => {
  const sub = 'apple-sub-001';
  const good = await idToken({ sub, email: 'Apple.User@Example.com', email_verified: 'true' }, { iss: APPLE_ISSUER, aud: APPLE_AUD });
  const r = await t.req('POST', '/auth/apple', { body: { idToken: good, displayName: 'Apple User' } });
  assert.equal(r.status, 200);
  assert.equal(r.body.isNewUser, true);
  const me = await t.req('GET', '/v1/me', { token: r.body.accessToken });
  assert.equal(me.body.displayName, 'Apple User');
  assert.equal(me.body.email, 'apple.user@example.com');
  const again = await t.req('POST', '/auth/apple', { body: { idToken: await idToken({ sub }, { iss: APPLE_ISSUER, aud: APPLE_AUD }) } });
  assert.equal(again.body.isNewUser, false);

  const wrongAud = await idToken({ sub: 's2' }, { iss: APPLE_ISSUER, aud: 'com.someone.else' });
  assert.equal((await t.req('POST', '/auth/apple', { body: { idToken: wrongAud } })).status, 401);
  const wrongIss = await idToken({ sub: 's2' }, { iss: 'https://evil.example', aud: APPLE_AUD });
  assert.equal((await t.req('POST', '/auth/apple', { body: { idToken: wrongIss } })).status, 401);
  const expired = await idToken({ sub: 's2' }, { iss: APPLE_ISSUER, aud: APPLE_AUD, exp: '-1m' as never });
  assert.equal((await t.req('POST', '/auth/apple', { body: { idToken: expired } })).status, 401);
  const stranger = await generateKeyPair('RS256');
  const forged = await idToken({ sub: 's2' }, { iss: APPLE_ISSUER, aud: APPLE_AUD, key: stranger.privateKey });
  assert.equal((await t.req('POST', '/auth/apple', { body: { idToken: forged } })).status, 401);
});

test('Sign in with Apple: nonce must match (raw or hashed)', async () => {
  const hashed = await idToken({ sub: 'n1', nonce: sha256hex('raw-nonce-123') }, { iss: APPLE_ISSUER, aud: APPLE_AUD });
  assert.equal((await t.req('POST', '/auth/apple', { body: { idToken: hashed, nonce: 'raw-nonce-123' } })).status, 200);
  assert.equal((await t.req('POST', '/auth/apple', { body: { idToken: hashed, nonce: 'other' } })).status, 401);
  const rawClaim = await idToken({ sub: 'n2', nonce: 'plain' }, { iss: APPLE_ISSUER, aud: APPLE_AUD });
  assert.equal((await t.req('POST', '/auth/apple', { body: { idToken: rawClaim, nonce: 'plain' } })).status, 200);
});

test('Sign in with Google links to an existing account only through a verified email', async () => {
  const existing = await t.signup('linker');
  const verified = await idToken({ sub: 'g-1', email: existing.email, email_verified: true }, { iss: GOOGLE_ISSUERS[0], aud: GOOGLE_AUD });
  const r = await t.req('POST', '/auth/google', { body: { idToken: verified } });
  assert.equal(r.status, 200);
  assert.equal(r.body.isNewUser, false);
  assert.equal((await t.req('GET', '/v1/me', { token: r.body.accessToken })).body.id, existing.id);

  const victim = await t.signup('victim');
  const unverified = await idToken({ sub: 'g-2', email: victim.email, email_verified: false }, { iss: 'accounts.google.com', aud: GOOGLE_AUD });
  const u = await t.req('POST', '/auth/google', { body: { idToken: unverified } });
  assert.equal(u.status, 200);
  assert.equal(u.body.isNewUser, true, 'an unverified email must not attach to the existing account');
  assert.notEqual((await t.req('GET', '/v1/me', { token: u.body.accessToken })).body.id, victim.id);
});

test('a provider with no configured audiences answers 501', async () => {
  const bare = await testServer({ database: t.database });
  const tok = await idToken({ sub: 'x' }, { iss: APPLE_ISSUER, aud: APPLE_AUD });
  assert.equal((await bare.req('POST', '/auth/apple', { body: { idToken: tok } })).status, 501);
  assert.equal((await bare.req('POST', '/auth/google', { body: { idToken: tok } })).status, 501);
  await bare.close();
});

test('auth endpoints are rate limited', async () => {
  // A server and database of its own: the counter lives in this process, and nothing about the
  // window may depend on how fast the machine is. Three test files run at once, so a request can
  // be starved for seconds; with a window far longer than this test's runtime, the four attempts
  // the limit allows and the ones it refuses cannot drift apart.
  const limited = await testServer({ config: { rateLimit: { enabled: true, authMax: 4, writeMax: 100, windowMs: 10 * 60_000 } } });
  const attempt = () => limited.req('POST', '/auth/refresh', { body: { refreshToken: 'q'.repeat(43) } });
  const statuses: number[] = [];
  for (let i = 0; i < 6; i++) statuses.push((await attempt()).status);
  // The first four reach the handler and fail on the unknown token; every later one is refused
  // by the limiter before it gets there, and a refused caller is told when to come back.
  assert.deepEqual(statuses.slice(0, 4), [401, 401, 401, 401], `statuses ${statuses}`);
  assert.deepEqual(statuses.slice(4), [429, 429], `statuses ${statuses}`);
  const refused = await attempt();
  assert.equal(refused.status, 429, `statuses ${[...statuses, refused.status]}`);
  assert.ok(Number(refused.headers.get('retry-after')) > 0, 'a refused caller is told when to retry');
  await limited.close();
});

test('unknown JSON fields are dropped, and server errors never leak details', async () => {
  const r = await t.req('POST', '/auth/magic-link', { body: { email: 'extra@example.com', admin: true } });
  assert.equal(r.status, 202);
  const bad = await t.req('GET', '/v1/users/' + 'x'.repeat(50), { token: (await t.signup()).token });
  assert.equal(bad.status, 400);
  assert.deepEqual(Object.keys(bad.body).sort(), ['error', 'message', 'statusCode']);
});
