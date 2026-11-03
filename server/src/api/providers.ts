import { createRemoteJWKSet, jwtVerify, type JWTVerifyGetKey } from 'jose';
import { unauthorized } from '../errors.js';
import { sha256hex } from '../security.js';

export interface IdentityClaims {
  subject: string;
  email: string | null;
  emailVerified: boolean;
}

/** Verifies a provider's ID token (signature against the provider's JWKS, issuer, audience, expiry). */
export interface IdTokenVerifier {
  verify(idToken: string, nonce?: string): Promise<IdentityClaims>;
}

export interface Providers { apple?: IdTokenVerifier; google?: IdTokenVerifier }

export interface OidcOptions {
  issuers: string[];
  audiences: string[];
  jwks: JWTVerifyGetKey;
}

export function oidcVerifier(opts: OidcOptions): IdTokenVerifier {
  return {
    async verify(idToken, nonce) {
      let payload;
      try {
        ({ payload } = await jwtVerify(idToken, opts.jwks, { issuer: opts.issuers, audience: opts.audiences, algorithms: ['RS256', 'ES256'] }));
      } catch {
        throw unauthorized('Invalid identity token');
      }
      if (typeof payload.sub !== 'string' || !payload.sub) throw unauthorized('Invalid identity token');
      if (nonce !== undefined) {
        // iOS sends the SHA-256 of the raw nonce to Apple; web/Android send it as is. Accept either form.
        if (payload.nonce !== nonce && payload.nonce !== sha256hex(nonce)) throw unauthorized('Nonce mismatch');
      }
      const verified = payload.email_verified;
      return {
        subject: payload.sub,
        email: typeof payload.email === 'string' ? payload.email.toLowerCase() : null,
        emailVerified: verified === true || verified === 'true',
      };
    },
  };
}

export const APPLE_ISSUER = 'https://appleid.apple.com';
export const GOOGLE_ISSUERS = ['https://accounts.google.com', 'accounts.google.com'];

/** Providers whose audiences are configured; the rest are left out (their endpoints answer 501). */
export function providersFromConfig(cfg: { appleAudiences: string[]; googleAudiences: string[] }): Providers {
  const out: Providers = {};
  if (cfg.appleAudiences.length) {
    out.apple = oidcVerifier({ issuers: [APPLE_ISSUER], audiences: cfg.appleAudiences, jwks: createRemoteJWKSet(new URL('https://appleid.apple.com/auth/keys')) });
  }
  if (cfg.googleAudiences.length) {
    out.google = oidcVerifier({ issuers: GOOGLE_ISSUERS, audiences: cfg.googleAudiences, jwks: createRemoteJWKSet(new URL('https://www.googleapis.com/oauth2/v3/certs')) });
  }
  return out;
}
