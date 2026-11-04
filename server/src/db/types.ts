import type { ColumnType, Generated } from 'kysely';

type Ts = ColumnType<Date, Date | string | undefined, Date | string>;
type TsNull = ColumnType<Date | null, Date | string | null | undefined, Date | string | null>;
type Opt<T> = ColumnType<T, T | undefined, T>;
type OptNull<T> = ColumnType<T | null, T | null | undefined, T | null>;

export type Visibility = 'public' | 'followers' | 'private';
export type VehicleKind = 'car' | 'motorcycle' | 'ute' | '4wd';
export type HazardKind = 'police' | 'hazard' | 'works' | 'fuel' | 'rest' | 'crash';

export interface UsersTable {
  id: Generated<string>; email: string | null; created_at: Ts; disabled_at: TsNull; kind: Opt<'user' | 'guest'>; expires_at: TsNull;
}

export interface AuthIdentitiesTable {
  id: Generated<string>; user_id: string; provider: 'email' | 'apple' | 'google'; subject: string;
  email: OptNull<string>; created_at: Ts;
}

export interface RefreshTokensTable {
  id: Generated<string>; user_id: string; family_id: string; token_hash: string; created_at: Ts;
  expires_at: Date | string; used_at: TsNull; revoked_at: TsNull; user_agent: OptNull<string>;
}

export interface MagicLinkTokensTable {
  id: Generated<string>; email: string; token_hash: string; created_at: Ts; expires_at: Date | string; used_at: TsNull;
}

export interface MediaTable {
  id: Generated<string>; owner_id: string; kind: Opt<'image'>; mime: string; bytes: number; width: number; height: number;
  storage_key: string; thumb_key: OptNull<string>; sha256: string; taken_at: OptNull<Date | string>; created_at: Ts;
}

export interface ProfilesTable {
  user_id: string; handle: string; handle_chosen: Opt<boolean>; display_name: Opt<string>; bio: Opt<string>;
  avatar_media_id: OptNull<string>; is_private: Opt<boolean>; created_at: Ts; updated_at: Ts;
}

export interface FollowsTable { follower_id: string; followee_id: string; status: 'pending' | 'accepted'; created_at: Ts }
export interface BlocksTable { blocker_id: string; blocked_id: string; created_at: Ts }

export interface VehiclesTable {
  id: Generated<string>; owner_id: string; kind: VehicleKind; make: string; model: string; year: OptNull<number>;
  nickname: Opt<string>; build: ColumnType<Record<string, unknown>, string | undefined, string>; is_primary: Opt<boolean>;
  sold_at: TsNull; created_at: Ts; updated_at: Ts;
}

export interface SavedRoutesTable {
  id: Generated<string>; owner_id: string; name: string; notes: Opt<string>; type: Opt<'sprint' | 'circuit'>;
  waypoints: ColumnType<[number, number][], string, string>; geometry: OptNull<string>; distance_m: OptNull<number>;
  duration_s: OptNull<number>; staging_lat: OptNull<number>; staging_lng: OptNull<number>; visibility: Opt<Visibility>;
  source: Opt<'convoyage' | 'photoscout'>; source_id: OptNull<string>; created_at: Ts; updated_at: Ts;
  conditions: ColumnType<unknown | null, string | null | undefined, string | null>; conditions_at: TsNull;
}

export interface RouteRoadsTable { hash: string; fetched_at: Date | string; result: ColumnType<unknown, string, string> }
export interface RoadCellsTable { cell: string; fetched_at: Date | string; ways: ColumnType<unknown, string, string> }

export interface RidesTable {
  id: Generated<string>; owner_id: string; vehicle_id: OptNull<string>; title: Opt<string>; status: Opt<'active' | 'finished'>;
  visibility: Opt<Visibility>; started_at: Date | string; ended_at: TsNull; start_lat: OptNull<number>; start_lng: OptNull<number>;
  distance_m: OptNull<number>; duration_s: OptNull<number>; max_speed_mps: OptNull<number>; avg_speed_mps: OptNull<number>;
  elevation_gain_m: OptNull<number>; max_lean_deg: OptNull<number>; convoy_code: OptNull<string>; polyline: OptNull<string>;
  polyline_precision: Opt<number>; moving_time_s: OptNull<number>; created_at: Ts;
}

export interface RidePhotosTable {
  id: Generated<string>; ride_id: string; media_id: string; lat: OptNull<number>; lng: OptNull<number>;
  taken_at: OptNull<Date | string>; created_at: Ts;
}

export interface PostsTable {
  id: Generated<string>; author_id: string; kind: 'ride' | 'build' | 'photo' | 'text'; ride_id: OptNull<string>;
  vehicle_id: OptNull<string>; body: Opt<string>; visibility: Opt<'public' | 'followers'>; lat: OptNull<number>;
  lng: OptNull<number>; created_at: Ts;
}

export interface PostMediaTable { post_id: string; media_id: string; position: Opt<number> }
export interface PostLikesTable { post_id: string; user_id: string; created_at: Ts }
export interface CommentsTable { id: Generated<string>; post_id: string; author_id: string; body: string; created_at: Ts }

export interface ReportsTable {
  id: Generated<string>; reporter_id: string; target_type: 'user' | 'post' | 'comment' | 'ride' | 'hazard'; target_id: string;
  reason: 'spam' | 'abuse' | 'nudity' | 'violence' | 'illegal' | 'other'; detail: Opt<string>;
  status: Opt<'open' | 'actioned' | 'dismissed'>; created_at: Ts;
}

export interface HazardReportsTable {
  id: Generated<string>; kind: HazardKind; subkind: Opt<string>; lat: number; lng: number; heading: OptNull<number>;
  reporter_id: OptNull<string>; convoy_code: OptNull<string>; created_at: Ts; expires_at: Ts;
  confirmations: Opt<number>; dismissals: Opt<number>; removed_at: TsNull;
}

export interface HazardAcksTable { hazard_id: string; user_id: string; action: 'confirm' | 'dismiss' | 'ack'; created_at: Ts }

export interface DeviceTokensTable { token: string; user_id: string; platform: 'ios' | 'android'; created_at: Ts; updated_at: Ts }

export interface PhotoscoutLinksTable {
  user_id: string; access_token_enc: string; refresh_token_enc: string; access_expires_at: Date | string; scope: Opt<string>;
  ps_user_id: OptNull<string>; ps_username: OptNull<string>; created_at: Ts; updated_at: Ts;
}

export interface PhotoscoutOauthStatesTable {
  state_hash: string; user_id: string; verifier_enc: string; created_at: Ts; expires_at: Date | string;
}

export interface SchemaMigrationsTable { name: string; applied_at: Ts }

export interface DB {
  users: UsersTable;
  auth_identities: AuthIdentitiesTable;
  refresh_tokens: RefreshTokensTable;
  magic_link_tokens: MagicLinkTokensTable;
  media: MediaTable;
  profiles: ProfilesTable;
  follows: FollowsTable;
  blocks: BlocksTable;
  vehicles: VehiclesTable;
  rides: RidesTable;
  saved_routes: SavedRoutesTable;
  route_roads: RouteRoadsTable;
  road_cells: RoadCellsTable;
  ride_photos: RidePhotosTable;
  posts: PostsTable;
  post_media: PostMediaTable;
  post_likes: PostLikesTable;
  comments: CommentsTable;
  reports: ReportsTable;
  hazard_reports: HazardReportsTable;
  hazard_acks: HazardAcksTable;
  device_tokens: DeviceTokensTable;
  photoscout_links: PhotoscoutLinksTable;
  photoscout_oauth_states: PhotoscoutOauthStatesTable;
  schema_migrations: SchemaMigrationsTable;
}
