import nodemailer from 'nodemailer';

/** Sends the sign-in email. */
export interface Mailer {
  sendMagicLink(email: string, link: string): Promise<void>;
}

/** Does nothing: a magic link is then only usable with MAIL_DEV=1. */
export const noopMailer: Mailer = { sendMagicLink: async () => {} };

export interface SmtpConfig {
  /** nodemailer connection URL, e.g. smtps://user:pass@smtp.example.com:465 */
  url: string;
  /** From header, e.g. `Convoyage <no-reply@example.com>`. */
  from: string;
  /** The app scheme the email also offers, for clients that refuse to linkify it. Defaults to `convoyage://auth/magic`. */
  deepLinkBaseUrl?: string;
}

/** Minimal transport surface, so tests can pass a fake. */
export interface MailTransport {
  sendMail(message: { from: string; to: string; subject: string; text: string; html: string }): Promise<unknown>;
}

export const escapeHtml = (s: string) => s.replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]!);

/** `token=` out of a link, so the mailer can repeat it without widening `sendMagicLink`. */
const tokenOf = (link: string): string | null => {
  const raw = /[?&]token=([^&#]*)/.exec(link)?.[1];
  if (!raw) return null;
  try { return decodeURIComponent(raw); } catch { return raw; }
};

/** Adds `token=` to a base that may already carry a query. */
export const withToken = (base: string, token: string): string => `${base}${base.includes('?') ? '&' : '?'}token=${encodeURIComponent(token)}`;

export function smtpMailer(cfg: SmtpConfig, transport: MailTransport = nodemailer.createTransport(cfg.url)): Mailer {
  const deepBase = cfg.deepLinkBaseUrl || 'convoyage://auth/magic';
  return {
    async sendMagicLink(email, link) {
      const token = tokenOf(link);
      const deep = token ? withToken(deepBase, token) : null;
      // Mail clients are unreliable with custom schemes, so the https link, a pasteable code and the deep link all go out.
      const extraText = token && deep ? `If your mail app will not open that link, copy this code into Convoyage's sign-in field: ${token}\n${deep}\n` : '';
      const extraHtml = token && deep
        ? `<p>If the link does not open the app, copy this code into Convoyage's sign-in field: <code>${escapeHtml(token)}</code></p><p><a href="${escapeHtml(deep)}">Open it in the Convoyage app</a></p>`
        : '';
      await transport.sendMail({
        from: cfg.from,
        to: email,
        subject: 'Your Convoyage sign-in link',
        text: `Open this link on your phone to sign in to Convoyage. It works once and expires in 15 minutes.\n`
          + `\n${link}\n`
          + extraText
          + `\nIf you did not ask for this, ignore this email.`,
        html: `<p>Open this link on your phone to sign in to Convoyage. It works once and expires in 15 minutes.</p>`
          + `<p><a href="${escapeHtml(link)}">Sign in to Convoyage</a></p>`
          + extraHtml
          + `<p>If you did not ask for this, ignore this email.</p>`,
      });
    },
  };
}
