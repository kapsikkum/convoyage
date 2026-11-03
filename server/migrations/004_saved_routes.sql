-- Saved routes: drawn or imported drives a convoy can follow (PhotoScout's route model: sprint or circuit, with an
-- optional staging point where the convoy gathers). Waypoints are what the author placed; geometry is the road-snapped
-- line (polyline, precision 6) the app computed through them, when it was snapped.

create table saved_routes (
  id uuid primary key default gen_random_uuid(),
  owner_id uuid not null references users(id) on delete cascade,
  name text not null,
  notes text not null default '',
  type text not null default 'sprint' check (type in ('sprint', 'circuit')),
  waypoints jsonb not null,                 -- [[lng, lat], ...], at least 2
  geometry text,                            -- polyline6 along roads, or null for a straight-line route
  distance_m double precision,
  duration_s double precision,
  staging_lat double precision,
  staging_lng double precision,
  visibility text not null default 'followers' check (visibility in ('public', 'followers', 'private')),
  source text not null default 'convoyage' check (source in ('convoyage', 'photoscout')),
  source_id text,                           -- the PhotoScout route id for an import
  created_at timestamptz not null default cv_now(),
  updated_at timestamptz not null default cv_now()
);
create index saved_routes_owner_idx on saved_routes (owner_id, updated_at desc);
create unique index saved_routes_import_idx on saved_routes (owner_id, source, source_id) where source_id is not null;
