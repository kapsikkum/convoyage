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
}

/** Minimal transport surface, so tests can pass a fake. */
export interface MailTransport {
  sendMail(message: { from: string; to: string; subject: string; text: string; html: string }): Promise<unknown>;
}

const escapeHtml = (s: string) => s.replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[c]!);

export function smtpMailer(cfg: SmtpConfig, transport: MailTransport = nodemailer.createTransport(cfg.url)): Mailer {
  return {
    async sendMagicLink(email, link) {
      await transport.sendMail({
        from: cfg.from,
        to: email,
        subject: 'Your Convoyage sign-in link',
        text: `Open this link on your phone to sign in to Convoyage. It works once and expires in 15 minutes.\n\n${link}\n\nIf you did not ask for this, ignore this email.`,
        html: `<p>Open this link on your phone to sign in to Convoyage. It works once and expires in 15 minutes.</p><p><a href="${escapeHtml(link)}">Sign in to Convoyage</a></p><p>If you did not ask for this, ignore this email.</p>`,
      });
    },
  };
}
