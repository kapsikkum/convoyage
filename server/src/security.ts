import { createHash, randomBytes } from 'node:crypto';

/** 256 bits of randomness, URL-safe. Used for refresh tokens and magic-link tokens. */
export const randomToken = (): string => randomBytes(32).toString('base64url');

/** Tokens are high-entropy random strings, so an unsalted SHA-256 is the right storage form. */
export const sha256hex = (s: string | Buffer): string => createHash('sha256').update(s).digest('hex');
