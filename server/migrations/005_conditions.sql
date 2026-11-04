-- Route conditions: the last snapshot of traffic, weather, road quality and police along a saved route, shown at once
-- while a fresh one loads. Road quality (OpenStreetMap via Overpass) is slow to fetch and slow to change, so it is
-- also kept per route line and per map cell for 14 days.

alter table saved_routes add column conditions jsonb;
alter table saved_routes add column conditions_at timestamptz;

create table route_roads (
  hash text primary key,                    -- sha1 of the thinned route line
  fetched_at timestamptz not null,
  result jsonb not null
);

create table road_cells (
  cell text primary key,                    -- south,west corner of a 0.05 degree cell
  fetched_at timestamptz not null,
  ways jsonb not null
);
