import { test } from 'node:test';
import assert from 'node:assert/strict';
import { smtpMailer } from '../src/mail.js';
import { loadConfig } from '../src/config.js';

test('smtpMailer sends the link to the address, escaped in html', async () => {
  const sent: any[] = [];
  const m = smtpMailer({ url: 'smtp://x', from: 'C <a@b.c>' }, { sendMail: async (msg) => void sent.push(msg) });
  await m.sendMagicLink('me@example.com', 'convoyage://auth/magic?token=a&b="x"');
  assert.equal(sent.length, 1);
  assert.equal(sent[0].to, 'me@example.com');
  assert.equal(sent[0].from, 'C <a@b.c>');
  assert.ok(sent[0].text.includes('token=a&b="x"'));
  assert.ok(sent[0].html.includes('token=a&amp;b=&quot;x&quot;'));
  // The code to paste and the app link are derived from the same token.
  assert.ok(sent[0].text.includes("copy this code into Convoyage's sign-in field: a\nconvoyage://auth/magic?token=a\n"));
  assert.ok(sent[0].html.includes('<code>a</code>'));
  assert.ok(sent[0].html.includes('href="convoyage://auth/magic?token=a"'));
});

test('smtpMailer repeats the token and the deep link when the emailed link is https', async () => {
  const sent: any[] = [];
  const m = smtpMailer({ url: 'smtp://x', from: 'C <a@b.c>' }, { sendMail: async (msg) => void sent.push(msg) });
  await m.sendMagicLink('me@example.com', 'https://convoy.example.com/auth/magic?token=tok_A%26B%3D%22C%22&x=1');
  assert.ok(sent[0].text.includes('https://convoy.example.com/auth/magic?token=tok_A%26B%3D%22C%22&x=1'));
  assert.ok(sent[0].text.includes('convoyage://auth/magic?token=tok_A%26B%3D%22C%22'));
  assert.ok(sent[0].text.includes("copy this code into Convoyage's sign-in field: tok_A&B=\"C\"\nconvoyage://auth/magic?token=tok_A%26B%3D%22C%22\n"));
  assert.ok(sent[0].html.includes('href="https://convoy.example.com/auth/magic?token=tok_A%26B%3D%22C%22&amp;x=1"'));
  assert.ok(sent[0].html.includes('href="convoyage://auth/magic?token=tok_A%26B%3D%22C%22"'));
  assert.ok(sent[0].html.includes('<code>tok_A&amp;B=&quot;C&quot;</code>'));
  assert.ok(!sent[0].html.includes('"C"'), 'every quote is escaped');
  // The ignore-this line stays last, in both bodies.
  assert.ok(sent[0].text.trimEnd().endsWith('If you did not ask for this, ignore this email.'));
  assert.ok(sent[0].html.endsWith('<p>If you did not ask for this, ignore this email.</p>'));
});

test('production requires SMTP_URL', () => {
  const base = { NODE_ENV: 'production', JWT_SECRET: 'x'.repeat(40), DATABASE_URL: 'postgres://u:p@h/db' };
  assert.throws(() => loadConfig(base), /SMTP_URL/);
  const cfg = loadConfig({ ...base, SMTP_URL: 'smtps://u:p@smtp.example.com', MAIL_FROM: 'Convoyage <x@y.z>' });
  assert.deepEqual(cfg.smtp, { url: 'smtps://u:p@smtp.example.com', from: 'Convoyage <x@y.z>' });
});

test('the emailed link base and the app deep-link base are configured separately', () => {
  const dev = loadConfig({ JWT_SECRET: 'x'.repeat(40) });
  assert.equal(dev.magicLinkBaseUrl, 'convoyage://auth/magic');
  assert.equal(dev.deepLinkBaseUrl, 'convoyage://auth/magic');
  const prod = loadConfig({
    JWT_SECRET: 'x'.repeat(40), MAGIC_LINK_BASE_URL: 'https://convoy.example.com/auth/magic', DEEP_LINK_BASE_URL: 'convoyage://auth/magic',
  } as NodeJS.ProcessEnv);
  assert.equal(prod.magicLinkBaseUrl, 'https://convoy.example.com/auth/magic');
  assert.equal(prod.deepLinkBaseUrl, 'convoyage://auth/magic');
});
