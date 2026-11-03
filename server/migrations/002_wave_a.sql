-- Wave A: ride moving time, guest accounts, push device tokens.

alter table rides add column moving_time_s integer;

-- 'guest' users are anonymous, expire after 24 hours and cannot write to the social layer.
alter table users add column kind text not null default 'user' check (kind in ('user', 'guest'));
alter table users add column expires_at timestamptz;
create index users_guest_expiry_idx on users (expires_at) where kind = 'guest';

create table device_tokens (
  token text primary key,
  user_id uuid not null references users(id) on delete cascade,
  platform text not null check (platform in ('ios', 'android')),
  created_at timestamptz not null default cv_now(),
  updated_at timestamptz not null default cv_now()
);
create index device_tokens_user_idx on device_tokens (user_id);
