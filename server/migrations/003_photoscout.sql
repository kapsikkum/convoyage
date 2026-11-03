-- Wave C: a user's connection to a PhotoScout account (OAuth 2 + PKCE).
-- Tokens are AES-256-GCM sealed by the application (PHOTOSCOUT_TOKEN_KEY); the database never holds one in the clear.

create table photoscout_links (
  user_id uuid primary key references users(id) on delete cascade,
  access_token_enc text not null,
  refresh_token_enc text not null,
  access_expires_at timestamptz not null,
  scope text not null default '',
  ps_user_id text,
  ps_username text,
  created_at timestamptz not null default cv_now(),
  updated_at timestamptz not null default cv_now()
);

-- One row per connect attempt in flight. The state is stored as its SHA-256; the PKCE verifier is sealed.
create table photoscout_oauth_states (
  state_hash text primary key,
  user_id uuid not null references users(id) on delete cascade,
  verifier_enc text not null,
  created_at timestamptz not null default cv_now(),
  expires_at timestamptz not null
);
create index photoscout_oauth_states_user_idx on photoscout_oauth_states (user_id);
create index photoscout_oauth_states_expiry_idx on photoscout_oauth_states (expires_at);
