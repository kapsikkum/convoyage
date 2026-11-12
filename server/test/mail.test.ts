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
});

test('production requires SMTP_URL', () => {
  const base = { NODE_ENV: 'production', JWT_SECRET: 'x'.repeat(40), DATABASE_URL: 'postgres://u:p@h/db' };
  assert.throws(() => loadConfig(base), /SMTP_URL/);
  const cfg = loadConfig({ ...base, SMTP_URL: 'smtps://u:p@smtp.example.com', MAIL_FROM: 'Convoyage <x@y.z>' });
  assert.deepEqual(cfg.smtp, { url: 'smtps://u:p@smtp.example.com', from: 'Convoyage <x@y.z>' });
});
