import type { FastifyInstance } from 'fastify';
import { FINISHES, VEHICLE_KINDS, arrayOf, bodyObj, errorResponses } from '../schemas.js';
import type { ApiContext } from '../common.js';

/** The garage catalogue: ids the `build` object refers to. Static; bump `version` when ids change. */
export const CATALOGUE = {
  version: 1,
  kinds: [
    { id: 'car', name: 'Car' }, { id: 'motorcycle', name: 'Motorcycle' }, { id: 'ute', name: 'Ute' }, { id: '4wd', name: '4WD' },
  ].filter((k) => (VEHICLE_KINDS as readonly string[]).includes(k.id)),
  paints: [
    { id: 'convoy_cyan', name: 'Convoy Cyan', hex: '#2de2e6' },
    { id: 'pearl_white', name: 'Pearl White', hex: '#f4f1ea' },
    { id: 'gloss_black', name: 'Black', hex: '#0b0b0d' },
    { id: 'race_red', name: 'Race Red', hex: '#c8102e' },
    { id: 'cobalt_blue', name: 'Cobalt Blue', hex: '#1f4fd8' },
    { id: 'forest_green', name: 'Forest Green', hex: '#1e7b45' },
    { id: 'solar_yellow', name: 'Solar Yellow', hex: '#f2c230' },
    { id: 'silver', name: 'Silver', hex: '#b8bcc2' },
  ],
  finishes: FINISHES.map((id) => ({ id, name: id[0].toUpperCase() + id.slice(1) })),
  wheels: [
    { id: 'stock', name: 'Stock' }, { id: 'sport5', name: 'Sport 5-Spoke' }, { id: 'mesh', name: 'Mesh' },
    { id: 'split_spoke', name: 'Split Spoke' }, { id: 'deep_dish', name: 'Deep Dish' }, { id: 'steelie', name: 'Steel Wheel' },
  ],
  decals: [
    { id: 'twin_stripe', name: 'Twin Stripe', slot: 'body' },
    { id: 'racing_number', name: 'Racing Number', slot: 'door' },
    { id: 'flames', name: 'Flames', slot: 'side' },
    { id: 'hood_stripe', name: 'Hood Stripe', slot: 'hood' },
    { id: 'sponsor_band', name: 'Sponsor Band', slot: 'windscreen' },
    { id: 'checker_flag', name: 'Chequered Flag', slot: 'rear' },
  ],
};

export async function catalogueRoutes(app: FastifyInstance, _ctx: ApiContext): Promise<void> {
  const item = bodyObj({ id: { type: 'string' }, name: { type: 'string' } });
  app.get('/v1/catalogue', {
    schema: {
      tags: ['catalogue'], summary: 'Garage catalogue: vehicle kinds, paints, finishes, wheels and decal presets',
      description: 'Public and static. The ids are what `build.paint` (use `hex`), `build.finish`, `build.wheel` and `build.decals[].id` carry.',
      response: {
        200: bodyObj({
          version: { type: 'integer' },
          kinds: arrayOf(item),
          paints: arrayOf(bodyObj({ id: { type: 'string' }, name: { type: 'string' }, hex: { type: 'string', pattern: '^#[0-9a-f]{6}$' } })),
          finishes: arrayOf(item),
          wheels: arrayOf(item),
          decals: arrayOf(bodyObj({ id: { type: 'string' }, name: { type: 'string' }, slot: { type: 'string' } })),
        }),
        ...errorResponses,
      },
    },
  }, async (_req, reply) => {
    reply.header('cache-control', 'public, max-age=3600');
    return CATALOGUE;
  });
}
