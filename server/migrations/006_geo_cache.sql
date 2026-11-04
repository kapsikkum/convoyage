-- Slow-to-fetch map data kept for a while: stops (servos, food, toilets, rest areas, EV chargers) along route lines
-- and per map cell, from OpenStreetMap via Overpass. Keyed by what was asked; the value is the answer as JSON.

create table geo_cache (
  key text primary key,
  fetched_at timestamptz not null,
  value jsonb not null
);
