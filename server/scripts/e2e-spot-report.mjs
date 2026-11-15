/**
 * End-to-end check of the PhotoScout spot-suggestion loop against a **real** PhotoScout instance.
 *
 * It signs in to Convoyage, links PhotoScout through the OAuth consent page the way a browser would,
 * suggests a spot, uploads its photo, then (with a staff session) approves the suggestion in PhotoScout's
 * moderation queue and reads the resulting public spot back through Convoyage's proxy.
 *
 * Prerequisites
 * - Convoyage running with PHOTOSCOUT_URL, PUBLIC_BASE_URL and PHOTOSCOUT_TOKEN_KEY set, and MAIL_DEV=1
 *   (the script reads the magic link from `POST /auth/magic-link`'s `devLink`).
 * - PhotoScout running with PUBLIC_URL matching PHOTOSCOUT_URL, `PUBLIC_READS=1`, and Convoyage
 *   registered in OAUTH_CLIENTS with `<PUBLIC_BASE_URL>/v1/photoscout/callback` as its redirect URI.
 * - Two PhotoScout sessions, minted on the server host (see server/README.md, "End-to-end check"):
 *   a normal user's `__Host-ps_session` cookie value in PS_SESSION, and a staff member's in
 *   PS_ADMIN_SESSION (an admin id in ADMIN_IDS; needed for the approval half).
 *
 * Environment
 * - CONVOYAGE_URL      base URL of the Convoyage server (default http://localhost:8080)
 * - PHOTOSCOUT_URL     base URL of the PhotoScout instance (default http://localhost:3003)
 * - PS_SESSION         value of PhotoScout's `__Host-ps_session` cookie for the account that links
 * - PS_ADMIN_SESSION   optional: the same for a PhotoScout admin/moderator; without it the approval steps fail
 *
 * Usage: node scripts/e2e-spot-report.mjs
 * Prints one PASS/FAIL line per step and exits non-zero if any step failed. Plain Node 22, no dependencies.
 */
import { randomBytes } from 'node:crypto';

const CONVOYAGE_URL = (process.env.CONVOYAGE_URL ?? 'http://localhost:8080').replace(/\/+$/, '');
const PHOTOSCOUT_URL = (process.env.PHOTOSCOUT_URL ?? 'http://localhost:3003').replace(/\/+$/, '');
const PS_SESSION = process.env.PS_SESSION ?? '';
const PS_ADMIN_SESSION = process.env.PS_ADMIN_SESSION ?? '';

const SESSION_COOKIE = '__Host-ps_session';
const CONSENT_COOKIE = '__Host-ps_consent';

/** A 1x1 baseline JPEG: enough for the real image pipeline to decode, re-encode and thumbnail. */
const TINY_JPEG = Buffer.from(
  '/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0aHBwgJC4nICIsIxwcKDcpLDAx' +
  'NDQ0Hyc5PTgyPC4zNDL/2wBDAQkJCQwLDBgNDRgyIRwhMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIy' +
  'MjIyMjIyMjL/wAARCAABAAEDASIAAhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQA' +
  'AAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZ' +
  'WmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl' +
  '5ufo6erx8vP09fb3+Pn6/8QAHwEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAECAxEE' +
  'BSExBhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hp' +
  'anN0dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq8vP0' +
  '9fb3+Pn6/9oADAMBAAIRAxEAPwD3+iiigD//2Q==',
  'base64',
);

const SPOT = { lat: -33.8568, lng: 151.2153, name: 'Convoyage E2E Lookout', notes: 'Harbour view, park at the gate. Suggested by the Convoyage server test.', tags: ['e2e', 'convoyage', 'harbour'] };
const FULL_SCOPE = 'events:read events:rsvp spots:read routes:read checkins:write spots:suggest';
/** The consent Convoyage asked for before PhotoScout offered spots:suggest. */
const OLD_SCOPE = 'events:read events:rsvp spots:read routes:read checkins:write';

const run = randomBytes(4).toString('hex');
let failed = 0;
let passed = 0;

class StepError extends Error {}
const assert = (ok, message) => { if (!ok) throw new StepError(message); };

async function step(label, fn) {
  try {
    const note = await fn();
    passed += 1;
    console.log(`PASS  ${label}${note ? `  (${note})` : ''}`);
  } catch (err) {
    failed += 1;
    console.log(`FAIL  ${label}`);
    console.log(`      ${err instanceof StepError ? err.message : String(err?.stack ?? err).split('\n').slice(0, 4).join('\n      ')}`);
  }
}

/** Parse a response body as JSON (or return the text when it is not JSON). */
async function body(res) {
  const text = await res.text();
  if (!text) return null;
  try { return JSON.parse(text); } catch { return text; }
}

const bearer = (token) => ({ authorization: `Bearer ${token}` });

async function convoyage(method, path, { token, body: payload } = {}) {
  const headers = {};
  if (token) Object.assign(headers, bearer(token));
  if (payload !== undefined) headers['content-type'] = 'application/json';
  const res = await fetch(`${CONVOYAGE_URL}${path}`, { method, headers, body: payload === undefined ? undefined : JSON.stringify(payload) });
  return { status: res.status, body: await body(res) };
}

/** Requests to PhotoScout's own API as the staff member (cookie session, like its web UI). */
async function staff(method, path, payload) {
  const headers = { cookie: `${SESSION_COOKIE}=${PS_ADMIN_SESSION}`, origin: PHOTOSCOUT_URL };
  if (payload !== undefined) headers['content-type'] = 'application/json';
  const res = await fetch(`${PHOTOSCOUT_URL}${path}`, { method, headers, body: payload === undefined ? undefined : JSON.stringify(payload) });
  return { status: res.status, body: await body(res) };
}

/** The five characters a browser decodes in a hidden input; the consent page's own values never need more. */
const decodeEntities = (s) => s.replace(/&(amp|lt|gt|quot|#39);/g, (_, e) => ({ amp: '&', lt: '<', gt: '>', quot: '"', '#39': "'" })[e]);

/** Sign in to Convoyage through the dev mailer and return a bearer token. */
async function signIn(email) {
  const asked = await convoyage('POST', '/auth/magic-link', { body: { email } });
  assert(asked.status === 202, `POST /auth/magic-link answered ${asked.status}: ${JSON.stringify(asked.body)}`);
  const devLink = asked.body?.devLink;
  assert(typeof devLink === 'string' && devLink.includes('token='), 'no devLink in the answer: is MAIL_DEV=1 set on Convoyage?');
  const token = new URL(devLink).searchParams.get('token');
  const verified = await convoyage('POST', '/auth/magic-link/verify', { body: { token } });
  assert(verified.status === 200 && typeof verified.body?.accessToken === 'string', `POST /auth/magic-link/verify answered ${verified.status}: ${JSON.stringify(verified.body)}`);
  return verified.body.accessToken;
}

/**
 * Walk Convoyage's connect flow in a browser's place: open PhotoScout's consent page, submit "allow" with the
 * hidden fields and the CSRF cookie, follow the 303 to Convoyage's callback (not further) and let Convoyage swap
 * the code. `scope` overrides the scope on the authorize URL, to model an older consent.
 */
async function linkPhotoScout({ scope } = {}) {
  const connect = await convoyage('GET', '/v1/photoscout/connect', { token: S.accessToken });
  assert(connect.status === 200 && typeof connect.body?.authorizeUrl === 'string', `GET /v1/photoscout/connect answered ${connect.status}: ${JSON.stringify(connect.body)}`);

  const authorize = new URL(connect.body.authorizeUrl);
  assert(`${authorize.origin}${authorize.pathname}` === `${PHOTOSCOUT_URL}/oauth/authorize`, `authorize URL is ${authorize.origin}${authorize.pathname}, expected PhotoScout's /oauth/authorize`);
  assert(authorize.searchParams.get('client_id') === 'convoyage', `client_id is ${authorize.searchParams.get('client_id')}`);
  const asked = authorize.searchParams.get('scope');
  assert(asked.includes('spots:suggest'), `the consent does not ask for spots:suggest: ${asked}`);
  if (scope) authorize.searchParams.set('scope', scope);

  const page = await fetch(authorize, { headers: { cookie: `${SESSION_COOKIE}=${PS_SESSION}` }, redirect: 'manual' });
  const html = await page.text();
  assert(page.status === 200, `PhotoScout's consent page answered ${page.status}: is PS_SESSION a live PhotoScout session?`);
  assert(html.includes('/oauth/authorize'), 'that was not the consent page (no decision form)');
  const csrfCookie = page.headers.getSetCookie().map((c) => c.split(';')[0]).find((c) => c.startsWith(`${CONSENT_COOKIE}=`));
  assert(csrfCookie, 'the consent page set no CSRF cookie');

  const fields = {};
  for (const m of html.matchAll(/<input type="hidden" name="([^"]+)" value="([^"]*)">/g)) fields[m[1]] = decodeEntities(m[2]);
  for (const needed of ['csrf', 'client_id', 'redirect_uri', 'response_type', 'scope', 'state', 'code_challenge', 'code_challenge_method']) {
    assert(typeof fields[needed] === 'string' && fields[needed] !== '', `the consent form has no ${needed}`);
  }
  assert(fields.code_challenge_method === 'S256', `code_challenge_method is ${fields.code_challenge_method}`);

  const decision = await fetch(`${PHOTOSCOUT_URL}/oauth/authorize`, {
    method: 'POST', redirect: 'manual',
    headers: { 'content-type': 'application/x-www-form-urlencoded', cookie: `${SESSION_COOKIE}=${PS_SESSION}; ${csrfCookie}`, origin: PHOTOSCOUT_URL },
    body: new URLSearchParams({ ...fields, decision: 'allow' }),
  });
  assert(decision.status === 303, `the consent POST answered ${decision.status}, expected a 303 to the callback`);
  const callbackUrl = decision.headers.get('location');
  assert(callbackUrl, 'the consent POST sent no Location');
  const callback = new URL(callbackUrl);
  assert(callback.pathname.endsWith('/v1/photoscout/callback'), `the consent POST redirected to ${callback.pathname}`);
  assert(!callback.searchParams.get('error'), `PhotoScout refused: error=${callback.searchParams.get('error')} ${callback.searchParams.get('error_description') ?? ''}`);
  assert(callback.searchParams.get('code'), 'the redirect carries no authorization code');

  // Convoyage's callback exchanges the code and sends the browser on to the app's deep link.
  const back = await fetch(callbackUrl, { redirect: 'manual' });
  assert(back.status === 302, `Convoyage's callback answered ${back.status}, expected a 302 to convoyage://`);
  const to = back.headers.get('location');
  assert(to === 'convoyage://photoscout/linked', `Convoyage sent the browser to ${to}`);
  return { asked: fields.scope, scope: scope ?? fields.scope };
}

const S = { accessToken: null, suggestionId: null, thumbUrl: null, spotId: null };

console.log(`Convoyage: ${CONVOYAGE_URL}\nPhotoScout: ${PHOTOSCOUT_URL}\nrun ${run}\n`);

// ---- 1. Convoyage sign-in --------------------------------------------------------------------------------------
await step('1. Convoyage sign-in (magic link, dev mailer)', async () => {
  S.accessToken = await signIn(`e2e+${run}@example.test`);
  return 'bearer token issued';
});

// ---- 2. link PhotoScout through the real consent page ----------------------------------------------------------
await step('2. PhotoScout link (consent page, PKCE exchange, callback)', async () => {
  const linked = await linkPhotoScout();
  return `consented to ${linked.scope}`;
});

// ---- 3. status ------------------------------------------------------------------------------------------------
await step('3. status: linked and canSuggest', async () => {
  const st = await convoyage('GET', '/v1/photoscout/status', { token: S.accessToken });
  assert(st.status === 200, `status answered ${st.status}`);
  assert(st.body.configured === true, 'configured is not true');
  assert(st.body.linked === true, `linked is ${st.body.linked}`);
  assert(st.body.scope.split(/\s+/).includes('spots:suggest'), `stored scope lacks spots:suggest: ${st.body.scope}`);
  assert(st.body.canSuggest === true, `canSuggest is ${st.body.canSuggest}`);
  return `scope "${st.body.scope}"`;
});

// ---- 4. suggest a spot -----------------------------------------------------------------------------------------
await step('4. suggest a spot: 201, status waiting, tags kept', async () => {
  const r = await convoyage('POST', '/v1/photoscout/suggestions', { token: S.accessToken, body: { ...SPOT, name: `${SPOT.name} ${run}` } });
  assert(r.status === 201, `answered ${r.status}: ${JSON.stringify(r.body)}`);
  assert(typeof r.body.id === 'string' && r.body.id.length > 0, 'no id');
  assert(r.body.status === 'waiting', `status is ${r.body.status}`);
  assert(r.body.photo === null, `a fresh suggestion has a photo: ${JSON.stringify(r.body.photo)}`);
  assert(JSON.stringify(r.body.tags) === JSON.stringify(SPOT.tags), `tags came back as ${JSON.stringify(r.body.tags)}`);
  for (const hidden of ['suggestedBy', 'outcome', 'resolvedAt', 'spotId']) assert(!(hidden in r.body), `PhotoScout's ${hidden} leaked into the Convoyage answer`);
  S.suggestionId = r.body.id;
  return `id ${r.body.id}`;
});

// ---- 5. add its photo -------------------------------------------------------------------------------------------
await step('5. suggestion photo: multipart upload, 201 with photo.thumbUrl', async () => {
  assert(S.suggestionId, 'no suggestion to attach a photo to (step 4 failed)');
  const form = new FormData();
  form.append('file', new Blob([new Uint8Array(TINY_JPEG)], { type: 'image/jpeg' }), 'spot.jpg');
  const res = await fetch(`${CONVOYAGE_URL}/v1/photoscout/suggestions/${S.suggestionId}/photo`, {
    method: 'POST', headers: bearer(S.accessToken), body: form,
  });
  const made = await body(res);
  assert(res.status === 201, `answered ${res.status}: ${JSON.stringify(made)}`);
  assert(made.photo && typeof made.photo.thumbUrl === 'string', `no photo in the answer: ${JSON.stringify(made.photo)}`);
  assert(made.photo.thumbUrl.startsWith(`${PHOTOSCOUT_URL}/`), `thumbUrl ${made.photo.thumbUrl} does not point at PhotoScout (${PHOTOSCOUT_URL})`);
  S.thumbUrl = made.photo.thumbUrl;
  return made.photo.thumbUrl;
});

// ---- 6. PhotoScout staff: approve, then read the spot back -------------------------------------------------------
await step('6a. staff queue (PhotoScout GET /api/suggestions): waiting, tags, photo', async () => {
  assert(PS_ADMIN_SESSION, 'PS_ADMIN_SESSION is not set, so the approval half cannot run');
  assert(S.suggestionId, 'no suggestion id (step 4 failed)');
  const listed = await staff('GET', '/api/suggestions');
  assert(listed.status === 200 && Array.isArray(listed.body), `answered ${listed.status}: ${JSON.stringify(listed.body)}`);
  const mine = listed.body.find((s) => s.id === S.suggestionId);
  assert(mine, `suggestion ${S.suggestionId} is not in PhotoScout's queue`);
  assert(mine.resolvedAt === null && mine.outcome === null, `already settled: outcome=${mine.outcome}`);
  assert(JSON.stringify(mine.tags) === JSON.stringify(SPOT.tags), `tags in the queue: ${JSON.stringify(mine.tags)}`);
  assert(mine.photo && typeof mine.photo.thumbUrl === 'string', 'no photo on the queue entry');
  return `waiting with ${mine.tags.length} tags and a photo`;
});

await step("6b. approve it (PhotoScout POST /api/suggestions/:id/resolve {action:'approve'})", async () => {
  const res = await staff('POST', `/api/suggestions/${S.suggestionId}/resolve`, { action: 'approve' });
  assert(res.status === 200 && res.body?.ok === true, `answered ${res.status}: ${JSON.stringify(res.body)}`);
  assert(typeof res.body.spotId === 'string', 'no spotId');
  S.spotId = res.body.spotId;
  return `spot ${S.spotId}`;
});

await step('6c. the approved spot on PhotoScout: tags kept, photoCount 1', async () => {
  assert(S.spotId, 'no spot id (6b failed)');
  const res = await staff('GET', `/api/spots/${S.spotId}`);
  assert(res.status === 200, `answered ${res.status}: ${JSON.stringify(res.body)}`);
  assert(res.body.visibility === 'public', `visibility is ${res.body.visibility}`);
  assert(JSON.stringify(res.body.tags) === JSON.stringify(SPOT.tags), `tags are ${JSON.stringify(res.body.tags)}`);
  assert(res.body.photoCount === 1, `photoCount is ${res.body.photoCount}`);
  return `public with tags ${res.body.tags.join(', ')}`;
});

await step('6d. the spot through Convoyage (GET /v1/photoscout/spots?bbox=)', async () => {
  assert(S.spotId, 'no spot id (6b failed)');
  const d = 0.05;
  const bbox = [SPOT.lat - d, SPOT.lng - d, SPOT.lat + d, SPOT.lng + d].join(',');
  const res = await convoyage('GET', `/v1/photoscout/spots?bbox=${bbox}`, { token: S.accessToken });
  assert(res.status === 200 && Array.isArray(res.body), `answered ${res.status}: ${JSON.stringify(res.body)}`);
  const mine = res.body.find((s) => s.id === S.spotId);
  assert(mine, `spot ${S.spotId} is not in ${res.body.length} Convoyage spots for bbox ${bbox}`);
  assert(JSON.stringify(mine.tags) === JSON.stringify(SPOT.tags), `tags are ${JSON.stringify(mine.tags)}`);
  assert(mine.photoCount === 1, `photoCount is ${mine.photoCount}`);
  assert(mine.photo && typeof mine.photo.thumbUrl === 'string', 'no photo on the Convoyage spot');
  return `${mine.name} with ${mine.photoCount} photo`;
});

// ---- 7. negatives -----------------------------------------------------------------------------------------------
await step('7a. a second Convoyage member who has not linked: 409 photoscout_not_linked', async () => {
  const other = await signIn(`e2e+unlinked+${run}@example.test`);
  const res = await convoyage('POST', '/v1/photoscout/suggestions', { token: other, body: { name: 'Should not be sent', lat: 1, lng: 2 } });
  assert(res.status === 409, `answered ${res.status}: ${JSON.stringify(res.body)}`);
  assert(res.body.error === 'photoscout_not_linked', `error is ${res.body.error}`);
  return '409 photoscout_not_linked';
});

await step('7b. an older link without spots:suggest: canSuggest false, 403 relink; relinking fixes it', async () => {
  await linkPhotoScout({ scope: OLD_SCOPE });
  const old = await convoyage('GET', '/v1/photoscout/status', { token: S.accessToken });
  assert(old.status === 200, `status answered ${old.status}`);
  assert(old.body.linked === true, 'the older link is not linked');
  assert(old.body.canSuggest === false, `canSuggest is ${old.body.canSuggest} for a link without spots:suggest`);
  const denied = await convoyage('POST', '/v1/photoscout/suggestions', { token: S.accessToken, body: { name: 'Should not reach PhotoScout', lat: 1, lng: 2 } });
  assert(denied.status === 403, `answered ${denied.status}: ${JSON.stringify(denied.body)}`);
  assert(denied.body.error === 'photoscout_insufficient_scope', `error is ${denied.body.error}`);
  assert(/relink/i.test(denied.body.message ?? ''), `message does not ask to relink: ${denied.body.message}`);

  // Consent again with the full scope, the way the app's "link PhotoScout again" would.
  await linkPhotoScout();
  const fresh = await convoyage('GET', '/v1/photoscout/status', { token: S.accessToken });
  assert(fresh.body.canSuggest === true, `canSuggest is ${fresh.body.canSuggest} after relinking`);
  return '403 photoscout_insufficient_scope, then canSuggest true after relinking';
});

console.log(`\n${passed} passed, ${failed} failed`);
process.exitCode = failed === 0 ? 0 : 1;
