-- Convoyage initial schema. Plain Postgres (no PostGIS): lat/lng are double precision columns.

-- Millisecond-truncated clock, so JS Dates and cursors round-trip exactly.
create function cv_now() returns timestamptz language sql as $$ select date_trunc('milliseconds', clock_timestamp()) $$;

create table users (
  id uuid primary key default gen_random_uuid(),
  email text unique,                       -- lower-cased, verified emails only
  created_at timestamptz not null default cv_now(),
  disabled_at timestamptz
);

create table auth_identities (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id) on delete cascade,
  provider text not null check (provider in ('email', 'apple', 'google')),
  subject text not null,                   -- email: lower-cased address; apple/google: the sub claim
  email text,
  created_at timestamptz not null default cv_now(),
  unique (provider, subject)
);
create index auth_identities_user_idx on auth_identities (user_id);

-- One row per issued refresh token. Tokens are rotated within a family; presenting a used or revoked
-- token revokes the whole family.
create table refresh_tokens (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id) on delete cascade,
  family_id uuid not null,
  token_hash text not null unique,         -- sha256 hex of the opaque token
  created_at timestamptz not null default cv_now(),
  expires_at timestamptz not null,
  used_at timestamptz,
  revoked_at timestamptz,
  user_agent text
);
create index refresh_tokens_family_idx on refresh_tokens (family_id);
create index refresh_tokens_user_idx on refresh_tokens (user_id);

create table magic_link_tokens (
  id uuid primary key default gen_random_uuid(),
  email text not null,
  token_hash text not null unique,
  created_at timestamptz not null default cv_now(),
  expires_at timestamptz not null,
  used_at timestamptz
);
create index magic_link_tokens_email_idx on magic_link_tokens (email, created_at);

create table media (
  id uuid primary key default gen_random_uuid(),
  owner_id uuid not null references users(id) on delete cascade,
  kind text not null default 'image' check (kind in ('image')),
  mime text not null,
  bytes integer not null,
  width integer not null,
  height integer not null,
  storage_key text not null unique,
  thumb_key text,
  sha256 text not null,                    -- of the stored (processed) bytes
  taken_at timestamptz,                    -- EXIF DateTimeOriginal (read as UTC), the only EXIF kept
  created_at timestamptz not null default cv_now()
);
create index media_owner_idx on media (owner_id, created_at);

create table profiles (
  user_id uuid primary key references users(id) on delete cascade,
  handle text not null unique check (handle ~ '^[a-z0-9_]{3,30}$'),
  handle_chosen boolean not null default false,
  display_name text not null default '',
  bio text not null default '',
  avatar_media_id uuid references media(id) on delete set null,
  is_private boolean not null default false,
  created_at timestamptz not null default cv_now(),
  updated_at timestamptz not null default cv_now()
);

create table follows (
  follower_id uuid not null references users(id) on delete cascade,
  followee_id uuid not null references users(id) on delete cascade,
  status text not null check (status in ('pending', 'accepted')),
  created_at timestamptz not null default cv_now(),
  primary key (follower_id, followee_id),
  check (follower_id <> followee_id)
);
create index follows_followee_idx on follows (followee_id, status);

create table blocks (
  blocker_id uuid not null references users(id) on delete cascade,
  blocked_id uuid not null references users(id) on delete cascade,
  created_at timestamptz not null default cv_now(),
  primary key (blocker_id, blocked_id),
  check (blocker_id <> blocked_id)
);
create index blocks_blocked_idx on blocks (blocked_id);

-- The primary vehicle of a profile is vehicles.is_primary (at most one unsold vehicle per owner).
create table vehicles (
  id uuid primary key default gen_random_uuid(),
  owner_id uuid not null references users(id) on delete cascade,
  kind text not null check (kind in ('car', 'motorcycle', 'ute', '4wd')),
  make text not null,
  model text not null,
  year integer,
  nickname text not null default '',
  build jsonb not null default '{}'::jsonb,   -- {paint, finish, wheel, rideHeightMm, decals}
  is_primary boolean not null default false,
  sold_at timestamptz,
  created_at timestamptz not null default cv_now(),
  updated_at timestamptz not null default cv_now()
);
create index vehicles_owner_idx on vehicles (owner_id);
create unique index vehicles_one_primary on vehicles (owner_id) where is_primary and sold_at is null;

create table rides (
  id uuid primary key default gen_random_uuid(),
  owner_id uuid not null references users(id) on delete cascade,
  vehicle_id uuid references vehicles(id) on delete set null,
  title text not null default '',
  status text not null default 'active' check (status in ('active', 'finished')),
  visibility text not null default 'followers' check (visibility in ('public', 'followers', 'private')),
  started_at timestamptz not null,
  ended_at timestamptz,
  start_lat double precision,
  start_lng double precision,
  distance_m double precision,
  duration_s integer,
  max_speed_mps double precision,
  avg_speed_mps double precision,
  elevation_gain_m double precision,
  max_lean_deg double precision,
  convoy_code text,
  polyline text,                           -- Google encoded polyline
  polyline_precision smallint not null default 5 check (polyline_precision in (5, 6)),
  created_at timestamptz not null default cv_now()
);
create index rides_owner_idx on rides (owner_id, started_at desc, id desc);

create table ride_photos (
  id uuid primary key default gen_random_uuid(),
  ride_id uuid not null references rides(id) on delete cascade,
  media_id uuid not null references media(id) on delete cascade,
  lat double precision,
  lng double precision,
  taken_at timestamptz,
  created_at timestamptz not null default cv_now(),
  unique (ride_id, media_id)
);

create table posts (
  id uuid primary key default gen_random_uuid(),
  author_id uuid not null references users(id) on delete cascade,
  kind text not null check (kind in ('ride', 'build', 'photo', 'text')),
  ride_id uuid references rides(id) on delete cascade,
  vehicle_id uuid references vehicles(id) on delete cascade,
  body text not null default '',
  visibility text not null default 'public' check (visibility in ('public', 'followers')),
  lat double precision,
  lng double precision,
  created_at timestamptz not null default cv_now()
);
create index posts_feed_idx on posts (created_at desc, id desc);
create index posts_author_idx on posts (author_id, created_at desc, id desc);

create table post_media (
  post_id uuid not null references posts(id) on delete cascade,
  media_id uuid not null references media(id) on delete cascade,
  position integer not null default 0,
  primary key (post_id, media_id)
);

create table post_likes (
  post_id uuid not null references posts(id) on delete cascade,
  user_id uuid not null references users(id) on delete cascade,
  created_at timestamptz not null default cv_now(),
  primary key (post_id, user_id)
);

create table comments (
  id uuid primary key default gen_random_uuid(),
  post_id uuid not null references posts(id) on delete cascade,
  author_id uuid not null references users(id) on delete cascade,
  body text not null,
  created_at timestamptz not null default cv_now()
);
create index comments_post_idx on comments (post_id, created_at, id);

create table reports (
  id uuid primary key default gen_random_uuid(),
  reporter_id uuid not null references users(id) on delete cascade,
  target_type text not null check (target_type in ('user', 'post', 'comment', 'ride', 'hazard')),
  target_id uuid not null,
  reason text not null check (reason in ('spam', 'abuse', 'nudity', 'violence', 'illegal', 'other')),
  detail text not null default '',
  status text not null default 'open' check (status in ('open', 'actioned', 'dismissed')),
  created_at timestamptz not null default cv_now(),
  unique (reporter_id, target_type, target_id)
);

create table hazard_reports (
  id uuid primary key default gen_random_uuid(),
  kind text not null check (kind in ('police', 'hazard', 'works', 'fuel', 'rest', 'crash')),
  subkind text not null default '',        -- e.g. gravel, oil, debris
  lat double precision not null,
  lng double precision not null,
  heading double precision,
  reporter_id uuid references users(id) on delete set null,
  convoy_code text,
  created_at timestamptz not null default cv_now(),
  expires_at timestamptz not null default (cv_now() + interval '30 minutes'),
  confirmations integer not null default 0,
  dismissals integer not null default 0,
  removed_at timestamptz
);
create index hazard_reports_live_idx on hazard_reports (expires_at) where removed_at is null;
create index hazard_reports_pos_idx on hazard_reports (lat, lng);

create table hazard_acks (
  hazard_id uuid not null references hazard_reports(id) on delete cascade,
  user_id uuid not null references users(id) on delete cascade,
  action text not null check (action in ('confirm', 'dismiss', 'ack')),
  created_at timestamptz not null default cv_now(),
  primary key (hazard_id, user_id, action)
);
