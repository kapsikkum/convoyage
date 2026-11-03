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
  VehicleBadge: obj({
    kind: { type: 'string', enum: [...VEHICLE_KINDS] },
    build: obj({ paint: BuildSchema.properties.paint, finish: BuildSchema.properties.finish, wheel: BuildSchema.properties.wheel, rideHeightMm: BuildSchema.properties.rideHeightMm }, []),
  }),
  UserRef: obj({
    id: uuid, handle: string, displayName: string, avatarMediaId: nUuid,
    primaryVehicle: { ...nullableRef('VehicleBadge'), description: 'Their primary vehicle, for the avatar badge. Null when they have none or the viewer may not see it (private account not followed, blocked).' },
  }, ['id', 'handle', 'displayName', 'avatarMediaId']),
  Vehicle: obj({
    id: uuid, ownerId: uuid, kind: { type: 'string', enum: [...VEHICLE_KINDS] }, make: string, model: string, year: nInt, nickname: string,
    build: ref('Build'), isPrimary: bool, soldAt: nDate, createdAt: dateTime, updatedAt: dateTime,
  }),
  Me: obj({
    id: uuid, handle: string, handleChosen: bool, displayName: string, bio: string, avatarMediaId: nUuid, isPrivate: bool,
    email: nString, kind: { type: 'string', enum: ['user', 'guest'] }, createdAt: dateTime, followerCount: int, followingCount: int, pendingRequestCount: int, primaryVehicle: nullableRef('Vehicle'),
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
    distanceM: nNum, durationS: nInt, movingTimeS: { ...nInt, description: 'Seconds spent moving (stops excluded); null if the client did not send it.' }, maxSpeedMps: nNum, avgSpeedMps: nNum, elevationGainM: nNum, maxLeanDeg: nNum, convoyCode: nString,
    createdAt: dateTime,
  }),
  Ride: obj({
    id: uuid, ownerId: uuid, vehicleId: nUuid, title: string, status: { type: 'string', enum: ['active', 'finished'] },
    visibility: { type: 'string', enum: [...VISIBILITIES] }, startedAt: dateTime, endedAt: nDate, startLat: nNum, startLng: nNum,
    distanceM: nNum, durationS: nInt, movingTimeS: { ...nInt, description: 'Seconds spent moving (stops excluded); null if the client did not send it.' }, maxSpeedMps: nNum, avgSpeedMps: nNum, elevationGainM: nNum, maxLeanDeg: nNum, convoyCode: nString,
    createdAt: dateTime,
    polyline: { ...nString, description: 'Google encoded polyline of the trace.' },
    polylinePrecision: { type: 'integer', enum: [5, 6] },
    photos: arrayOf(ref('RidePhoto')),
  }),
  RoutePreview: obj({
    polyline: { type: 'string', description: 'Simplified trace, at most about 100 points.' }, polylinePrecision: { type: 'integer', enum: [5] }, pointCount: int,
    bbox: nullable(obj({ south: num, west: num, north: num, east: num })),
  }),
  FeedRide: obj({
    id: uuid, ownerId: uuid, vehicleId: nUuid, title: string, status: { type: 'string', enum: ['active', 'finished'] },
    visibility: { type: 'string', enum: [...VISIBILITIES] }, startedAt: dateTime, endedAt: nDate, startLat: nNum, startLng: nNum,
    distanceM: nNum, durationS: nInt, movingTimeS: nInt, maxSpeedMps: nNum, avgSpeedMps: nNum, elevationGainM: nNum, maxLeanDeg: nNum, convoyCode: nString,
    createdAt: dateTime, preview: nullableRef('RoutePreview'),
  }),
  PhotoItem: obj({ media: ref('Media'), rideId: nUuid, postId: nUuid, lat: nNum, lng: nNum, takenAt: nDate, createdAt: dateTime }),
  PhotoPage: obj({ items: arrayOf(ref('PhotoItem')), nextCursor: nString }),
  GuestAuthResult: obj({
    accessToken: string, tokenType: { type: 'string', enum: ['Bearer'] }, expiresIn: { ...int, description: 'Seconds until the guest session ends (24 h). There is no refresh token.' },
    user: obj({ id: uuid, kind: { type: 'string', enum: ['guest'] }, displayName: string }),
  }),
  RidePage: obj({ items: arrayOf(ref('RideSummary')), nextCursor: nString }),
  Post: obj({
    id: uuid, kind: { type: 'string', enum: ['ride', 'build', 'photo', 'text'] }, author: ref('UserRef'), body: string,
    visibility: { type: 'string', enum: ['public', 'followers'] }, createdAt: dateTime, lat: nNum, lng: nNum,
    ride: nullableRef('FeedRide'), vehicle: nullableRef('Vehicle'), media: arrayOf(ref('Media')),
    likeCount: int, commentCount: int, likedByMe: bool,
  }),
  FeedPage: obj({ items: arrayOf(ref('Post')), nextCursor: { ...nString, description: 'Pass as `before` for the next page; null at the end.' } }),
  Comment: obj({ id: uuid, postId: uuid, author: ref('UserRef'), body: string, createdAt: dateTime }),
  CommentPage: obj({ items: arrayOf(ref('Comment')), nextCursor: nString }),
  Hazard: obj({
    id: uuid, kind: { type: 'string', enum: [...HAZARD_KINDS] }, subkind: string, lat: num, lng: num, heading: nNum,
    createdAt: dateTime, expiresAt: dateTime, confirmations: int, dismissals: int,
    myAction: { anyOf: [{ type: 'string', enum: ['confirm', 'dismiss'] }, { type: 'null' }], description: 'The viewer vote: null when none, never an empty string.' },
  }),
  HandleAvailability: obj({ handle: string, available: bool, reason: nString }),
  FollowResult: obj({ status: { type: 'string', enum: ['none', 'pending', 'accepted'] } }),
  FollowRequest: obj({ user: ref('UserRef'), createdAt: dateTime }),
  Ok: obj({ ok: bool }),
  PhotoScoutStatus: obj({
    configured: { ...bool, description: 'False when this server has no PHOTOSCOUT_URL: every other PhotoScout route answers 503.' },
    linked: bool, username: { ...nString, description: 'The PhotoScout username, when PhotoScout told us at link time.' },
    scope: { ...string, description: 'The scopes PhotoScout granted.' }, linkedAt: nDate,
  }),
  PhotoScoutAuthorizeUrl: obj({ authorizeUrl: { ...string, description: 'Open in a browser or custom tab. It ends at convoyage://photoscout/linked (or ?error=...).' } }),
  PsCruise: obj({ routeName: string, meet: obj({ lat: num, lng: num, note: string }), rollOut: string }),
  PsEvent: obj({
    id: { ...string, description: 'A community event id, or a crawled event group key.' }, source: { type: 'string', enum: ['community', 'scout'] },
    title: string, description: string, startTime: string, endTime: nString, venue: string, address: string, lat: nNum, lng: nNum,
    imageUrl: string, url: string, going: int, interested: int,
    myRsvp: { anyOf: [{ type: 'string', enum: ['going', 'interested'] }, { type: 'null' }] },
    cruise: { anyOf: [ref('PsCruise'), { type: 'null' }], description: 'Set on a community event with a cruise: open its convoy with socket `convoy.cruise {eventId: id}`.' },
    distanceKm: { ...nNum, description: 'From `near`, when given.' },
  }),
  PsSpot: obj({
    id: string, name: string, notes: string, lat: num, lng: num, tags: arrayOf(string), facingDeg: nNum, fovDeg: nNum,
    goodTimes: obj({ phases: arrayOf(string), months: arrayOf(int), days: string, conditions: arrayOf(string), avoid: string, notes: string }),
    photoCount: int,
    photo: { anyOf: [obj({ id: string, thumbUrl: string, url: string }), { type: 'null' }], description: 'Cover photo. The URLs are served by Convoyage and need the bearer token, like /v1/media.' },
  }),
  PsCheckin: obj({ id: string, createdAt: string, placeName: string, lat: nNum, lng: nNum, body: string }),
  PsRsvp: obj({
    eventId: string, source: { type: 'string', enum: ['community', 'scout'] }, going: int, interested: int,
    myRsvp: { anyOf: [{ type: 'string', enum: ['going', 'interested'] }, { type: 'null' }] },
  }),
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
