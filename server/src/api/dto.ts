import type { Selectable } from 'kysely';
import type { MediaTable, RidePhotosTable, RidesTable, VehiclesTable } from '../db/types.js';
import { iso, isoOrNull } from './common.js';

type Row<T> = Selectable<T>;

export const mediaUrl = (id: string) => `/v1/media/${id}`;

export function mediaDto(m: Row<MediaTable>) {
  return {
    id: m.id, mime: m.mime, bytes: m.bytes, width: m.width, height: m.height,
    takenAt: isoOrNull(m.taken_at), createdAt: iso(m.created_at), url: mediaUrl(m.id), thumbUrl: `${mediaUrl(m.id)}/thumb`,
  };
}

export function vehicleDto(v: Row<VehiclesTable>) {
  return {
    id: v.id, ownerId: v.owner_id, kind: v.kind, make: v.make, model: v.model, year: v.year, nickname: v.nickname,
    build: v.build, isPrimary: v.is_primary, soldAt: isoOrNull(v.sold_at), createdAt: iso(v.created_at), updatedAt: iso(v.updated_at),
  };
}

export function rideSummaryDto(r: Row<RidesTable>) {
  return {
    id: r.id, ownerId: r.owner_id, vehicleId: r.vehicle_id, title: r.title, status: r.status, visibility: r.visibility,
    startedAt: iso(r.started_at), endedAt: isoOrNull(r.ended_at), startLat: r.start_lat, startLng: r.start_lng,
    distanceM: r.distance_m, durationS: r.duration_s, maxSpeedMps: r.max_speed_mps, avgSpeedMps: r.avg_speed_mps,
    movingTimeS: r.moving_time_s, elevationGainM: r.elevation_gain_m, maxLeanDeg: r.max_lean_deg, convoyCode: r.convoy_code, createdAt: iso(r.created_at),
  };
}

export function ridePhotoDto(p: Row<RidePhotosTable>, media: Row<MediaTable>) {
  return { id: p.id, media: mediaDto(media), lat: p.lat, lng: p.lng, takenAt: isoOrNull(p.taken_at) };
}

export const RESERVED_HANDLES = new Set([
  'admin', 'administrator', 'support', 'help', 'convoyage', 'official', 'staff', 'moderator', 'mod', 'system', 'root', 'api',
  'me', 'null', 'undefined', 'www', 'security', 'abuse', 'photoscout', 'team', 'info', 'contact',
]);

/** A message if the handle cannot be used (format or reserved), else null. Uniqueness is checked against the database. */
export function handleProblem(handle: string): string | null {
  if (!/^[a-z0-9_]{3,30}$/.test(handle)) return 'Handles are 3 to 30 characters: letters, digits and underscores';
  if (RESERVED_HANDLES.has(handle)) return 'That handle is reserved';
  return null;
}

/** Postgres unique_violation, from either node-postgres or pglite. */
export const isUniqueViolation = (err: unknown): boolean => (err as { code?: string })?.code === '23505';
