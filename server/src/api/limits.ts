import type { Config } from '../config.js';

/** Route `config` fragments for @fastify/rate-limit (inert when the plugin is not registered). */
export function limits(config: Config) {
  const { authMax, writeMax, windowMs } = config.rateLimit;
  return {
    auth: { config: { rateLimit: { max: authMax, timeWindow: windowMs } } },
    /** Stricter: things that send mail or accept a token guess. */
    authStrict: { config: { rateLimit: { max: Math.max(1, Math.floor(authMax / 2)), timeWindow: windowMs } } },
    write: { config: { rateLimit: { max: writeMax, timeWindow: windowMs } } },
  };
}
