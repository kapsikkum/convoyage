import type { FastifyInstance } from 'fastify';

/** Small JSON-Schema builders; the shared models below become OpenAPI components. */
export const uuid = { type: 'string', format: 'uuid' } as const;
export const dateTime = { type: 'string', format: 'date-time' } as const;
export const nullable = <T extends { type: string }>(s: T) => ({ ...s, type: [s.type, 'null'] as const });
export const ref = (name: string) => ({ $ref: `${name}#` });
export const nullableRef = (name: string) => ({ anyOf: [ref(name), { type: 'null' }] });
export const arrayOf = (items: object) => ({ type: 'array', items });
const obj = (properties: Record<string, object>, required: string[] = Object.keys(properties)) =>
  ({ type: 'object', properties, required, additionalProperties: false }) as const;
export const bodyObj = obj;
const num = { type: 'number' } as const;
const int = { type: 'integer' } as const;
const bool = { type: 'boolean' } as const;
const string = { type: 'string' } as const;
const nNum = nullable(num);
const nInt = nullable(int);
const nString = nullable(string);
const nUuid = nullable(uuid);
const nDate = nullable(dateTime);

export const VISIBILITIES = ['public', 'followers', 'private'] as const;
export const VEHICLE_KINDS = ['car', 'motorcycle', 'ute', '4wd'] as const;
export const HAZARD_KINDS = ['police', 'hazard', 'works', 'fuel', 'rest', 'crash'] as const;
export const FINISHES = ['gloss', 'satin', 'matte', 'metallic', 'pearl'] as const;

export const BuildSchema = obj({
  paint: { type: 'string', pattern: '^#[0-9a-fA-F]{6}$', description: 'Body colour as #RRGGBB.' },
  finish: { type: 'string', enum: [...FINISHES] },
  wheel: { type: 'string', minLength: 1, maxLength: 64, description: 'Wheel model id from the garage catalogue.' },
  rideHeightMm: { type: 'integer', minimum: -200, maximum: 200, description: 'Offset from stock ride height, millimetres.' },
  decals: {
    type: 'array', maxItems: 24,
    items: obj({
      id: { type: 'string', minLength: 1, maxLength: 64 },
      slot: { type: 'string', maxLength: 32 },
      color: { type: 'string', pattern: '^#[0-9a-fA-F]{6}$' },
    }, ['id']),
  },
}, []);

/** Models registered once and referenced by $id, so the OpenAPI document has named components. */
const models: Record<string, object> = {
  Error: obj({ statusCode: int, error: string, message: string }),
  TokenPair: obj({ accessToken: string, refreshToken: string, tokenType: { type: 'string', enum: ['Bearer'] }, expiresIn: { ...int, description: 'Access token lifetime, seconds.' } }),
  AuthResult: obj({
    accessToken: string, refreshToken: string, tokenType: { type: 'string', enum: ['Bearer'] }, expiresIn: int,
    isNewUser: { ...bool, description: 'True when this sign-in created the account (prompt for a handle).' },
  }),
  Build: BuildSchema,
  UserRef: obj({ id: uuid, handle: string, displayName: string, avatarMediaId: nUuid }),
  Vehicle: obj({
    id: uuid, ownerId: uuid, kind: { type: 'string', enum: [...VEHICLE_KINDS] }, make: string, model: string, year: nInt, nickname: string,
    build: ref('Build'), isPrimary: bool, soldAt: nDate, createdAt: dateTime, updatedAt: dateTime,
  }),
  Me: obj({
    id: uuid, handle: string, handleChosen: bool, displayName: string, bio: string, avatarMediaId: nUuid, isPrivate: bool,
    email: nString, createdAt: dateTime, followerCount: int, followingCount: int, pendingRequestCount: int, primaryVehicle: nullableRef('Vehicle'),
  }),
  Profile: obj({
    id: uuid, handle: string, displayName: string, bio: string, avatarMediaId: nUuid, isPrivate: bool, createdAt: dateTime,
    followerCount: int, followingCount: int,
    relationship: obj({ following: { type: 'string', enum: ['none', 'pending', 'accepted'] }, followedBy: bool, blockedByMe: bool }),
    canViewContent: { ...bool, description: 'False for a private account the viewer does not follow: rides, vehicles and posts are hidden.' },
    primaryVehicle: nullableRef('Vehicle'),
  }),
  Media: obj({ id: uuid, mime: string, bytes: int, width: int, height: int, takenAt: nDate, createdAt: dateTime, url: string, thumbUrl: string }),
  RidePhoto: obj({ id: uuid, media: ref('Media'), lat: nNum, lng: nNum, takenAt: nDate }),
  RideSummary: obj({
    id: uuid, ownerId: uuid, vehicleId: nUuid, title: string, status: { type: 'string', enum: ['active', 'finished'] },
    visibility: { type: 'string', enum: [...VISIBILITIES] }, startedAt: dateTime, endedAt: nDate, startLat: nNum, startLng: nNum,
    distanceM: nNum, durationS: nInt, maxSpeedMps: nNum, avgSpeedMps: nNum, elevationGainM: nNum, maxLeanDeg: nNum, convoyCode: nString,
    createdAt: dateTime,
  }),
  Ride: obj({
    id: uuid, ownerId: uuid, vehicleId: nUuid, title: string, status: { type: 'string', enum: ['active', 'finished'] },
    visibility: { type: 'string', enum: [...VISIBILITIES] }, startedAt: dateTime, endedAt: nDate, startLat: nNum, startLng: nNum,
    distanceM: nNum, durationS: nInt, maxSpeedMps: nNum, avgSpeedMps: nNum, elevationGainM: nNum, maxLeanDeg: nNum, convoyCode: nString,
    createdAt: dateTime,
    polyline: { ...nString, description: 'Google encoded polyline of the trace.' },
    polylinePrecision: { type: 'integer', enum: [5, 6] },
    photos: arrayOf(ref('RidePhoto')),
  }),
  RidePage: obj({ items: arrayOf(ref('RideSummary')), nextCursor: nString }),
  Post: obj({
    id: uuid, kind: { type: 'string', enum: ['ride', 'build', 'photo', 'text'] }, author: ref('UserRef'), body: string,
    visibility: { type: 'string', enum: ['public', 'followers'] }, createdAt: dateTime, lat: nNum, lng: nNum,
    ride: nullableRef('RideSummary'), vehicle: nullableRef('Vehicle'), media: arrayOf(ref('Media')),
    likeCount: int, commentCount: int, likedByMe: bool,
  }),
  FeedPage: obj({ items: arrayOf(ref('Post')), nextCursor: { ...nString, description: 'Pass as `before` for the next page; null at the end.' } }),
  Comment: obj({ id: uuid, postId: uuid, author: ref('UserRef'), body: string, createdAt: dateTime }),
  CommentPage: obj({ items: arrayOf(ref('Comment')), nextCursor: nString }),
  Hazard: obj({
    id: uuid, kind: { type: 'string', enum: [...HAZARD_KINDS] }, subkind: string, lat: num, lng: num, heading: nNum,
    createdAt: dateTime, expiresAt: dateTime, confirmations: int, dismissals: int,
    myAction: { type: ['string', 'null'], enum: ['confirm', 'dismiss', null], description: 'The viewer\'s confirm/dismiss vote, if any.' },
  }),
  HandleAvailability: obj({ handle: string, available: bool, reason: nString }),
  FollowResult: obj({ status: { type: 'string', enum: ['none', 'pending', 'accepted'] } }),
  FollowRequest: obj({ user: ref('UserRef'), createdAt: dateTime }),
  Ok: obj({ ok: bool }),
};

export function registerSchemas(app: FastifyInstance): void {
  for (const [id, schema] of Object.entries(models)) app.addSchema({ $id: id, ...schema });
}

export const errorResponses = {
  400: ref('Error'), 401: ref('Error'), 403: ref('Error'), 404: ref('Error'), 409: ref('Error'), 429: ref('Error'),
};

export const bearer = [{ bearerAuth: [] }];

export const idParams = obj({ id: uuid });
export const cursorQuery = (extra: Record<string, object> = {}) =>
  obj({ before: { type: 'string', maxLength: 200, description: 'Opaque cursor from a previous page.' }, limit: { type: 'integer', minimum: 1, maximum: 50, default: 20 }, ...extra }, []);
