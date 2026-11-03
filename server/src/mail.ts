/** Sends the sign-in email. Real delivery (SES, Postmark, ...) is not built: the default does nothing. */
export interface Mailer {
  sendMagicLink(email: string, link: string): Promise<void>;
}

/** TODO(mail): replace with a real provider. Until then a magic link is only usable with MAIL_DEV=1. */
export const noopMailer: Mailer = { sendMagicLink: async () => {} };
