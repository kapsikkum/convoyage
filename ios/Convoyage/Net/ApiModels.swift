import Foundation

// Wire models for protocol/openapi.json. Only the fields the app reads are listed and almost everything is optional,
// so the server can add (or omit) fields within v1 without breaking decoding.
// TODO(codegen): generate these from openapi.json instead of keeping them in step by hand.

struct Empty: Decodable {
    init(from decoder: Decoder) throws {}
}

struct MagicLinkResponse: Decodable {
    var ok: Bool?
    var devLink: String?
}

struct TokenPairDto: Decodable {
    var accessToken: String
    var refreshToken: String
    var expiresIn: Int?
}

struct AuthResultDto: Decodable {
    var accessToken: String
    var refreshToken: String
    var expiresIn: Int?
    var isNewUser: Bool?
}

/// What an avatar badge needs of a vehicle: its kind and the build fields that change how it looks
/// (`VehicleBadge` in the API, `MemberVehicle` in the convoy protocol). Decoded tolerantly.
struct VehicleBadgeDto: Codable, Equatable {
    var kind: String
    var build: BadgeBuildDto?

    private enum Keys: String, CodingKey {
        case kind
        case build
    }

    init(from decoder: Decoder) throws {
        let c: KeyedDecodingContainer<Keys> = try decoder.container(keyedBy: Keys.self)
        kind = (try? c.decodeIfPresent(String.self, forKey: .kind)) ?? "car"
        build = try? c.decodeIfPresent(BadgeBuildDto.self, forKey: .build)
    }
}

struct BadgeBuildDto: Codable, Equatable {
    var paint: String?
    var finish: String?
    var wheel: String?
    var rideHeightMm: Int?
}

struct UserRefDto: Decodable, Equatable {
    var id: String
    var handle: String?
    var displayName: String?
    var avatarMediaId: String?
    var primaryVehicle: VehicleBadgeDto?

    var shownName: String {
        let n: String = displayName ?? ""
        if !n.isEmpty { return n }
        return handle ?? "Driver"
    }

    var avatarPath: String? {
        guard let m = avatarMediaId, !m.isEmpty else { return nil }
        return "/v1/media/\(m)/thumb"
    }

    var vehicleBuild: VehicleBuild? {
        guard let v = primaryVehicle else { return nil }
        return VehicleBuild(badge: v)
    }
}

struct DecalDto: Decodable, Equatable {
    var id: String
    var slot: String?
    var color: String?
}

struct BuildDto: Decodable, Equatable {
    var paint: String?
    var finish: String?
    var wheel: String?
    var rideHeightMm: Int?
    var decals: [DecalDto]?
}

struct VehicleDto: Decodable, Identifiable, Equatable {
    var id: String
    var kind: String
    var make: String
    var model: String
    var year: Int?
    var nickname: String?
    var build: BuildDto?
    var isPrimary: Bool?
    var soldAt: String?

    var title: String {
        let nick: String = nickname ?? ""
        if !nick.isEmpty { return nick }
        return "\(make) \(model)"
    }

    var subtitle: String {
        var parts: [String] = []
        if let y = year { parts.append(String(y)) }
        parts.append("\(make) \(model)")
        return parts.joined(separator: " ")
    }
}

struct MeDto: Decodable, Equatable {
    var id: String
    var handle: String?
    var handleChosen: Bool?
    var displayName: String?
    var bio: String?
    var avatarMediaId: String?
    var isPrivate: Bool?
    var email: String?
    var followerCount: Int?
    var followingCount: Int?
    var primaryVehicle: VehicleDto?

    var avatarPath: String? {
        guard let m = avatarMediaId, !m.isEmpty else { return nil }
        return "/v1/media/\(m)/thumb"
    }
}

struct MediaDto: Decodable, Identifiable, Equatable {
    var id: String
    var mime: String?
    var width: Int?
    var height: Int?
    var url: String?
    var thumbUrl: String?

    var fullPath: String { return url ?? "/v1/media/\(id)" }
    var thumbPath: String { return thumbUrl ?? "/v1/media/\(id)/thumb" }
}

struct RidePhotoDto: Decodable, Identifiable, Equatable {
    var id: String
    var media: MediaDto
    var lat: Double?
    var lng: Double?
    var takenAt: String?
}

/// A ride, summary or full (the full one adds the polyline and photos).
struct RideDto: Decodable, Identifiable, Equatable {
    var id: String
    var vehicleId: String?
    var title: String?
    var status: String?
    var visibility: String?
    var startedAt: String?
    var endedAt: String?
    var startLat: Double?
    var startLng: Double?
    var distanceM: Double?
    var durationS: Double?
    var maxSpeedMps: Double?
    var avgSpeedMps: Double?
    var elevationGainM: Double?
    var maxLeanDeg: Double?
    var convoyCode: String?
    var polyline: String?
    var polylinePrecision: Int?
    var photos: [RidePhotoDto]?
}

struct PostDto: Decodable, Identifiable, Equatable {
    var id: String
    var kind: String
    var author: UserRefDto
    var body: String?
    var visibility: String?
    var createdAt: String?
    var lat: Double?
    var lng: Double?
    var ride: RideDto?
    var vehicle: VehicleDto?
    var media: [MediaDto]?
    var likeCount: Int
    var commentCount: Int
    var likedByMe: Bool
}

struct FeedPageDto: Decodable {
    var items: [PostDto]
    var nextCursor: String?
}

struct CommentDto: Decodable, Identifiable, Equatable {
    var id: String
    var postId: String?
    var author: UserRefDto
    var body: String
    var createdAt: String?
}

struct CommentPageDto: Decodable {
    var items: [CommentDto]
    var nextCursor: String?
}

struct LikeStateDto: Decodable {
    var likeCount: Int
    var likedByMe: Bool
}

/// A road hazard. The same object comes from REST (`GET /v1/hazards`, with vote counts and `myAction`) and from
/// the socket's `hazard` message (no vote counts), so almost everything but the core fields is optional.
struct HazardDto: Decodable, Identifiable, Equatable {
    var id: String
    var kind: String
    var subkind: String?
    var lat: Double
    var lng: Double
    var heading: Double?
    var createdAt: String?
    var expiresAt: String?
    var confirmations: Int?
    var dismissals: Int?
    var myAction: String?

    var isPolice: Bool { return kind == "police" }

    /// "dismiss" | "confirm" | nil (the server may send an empty string for "no vote").
    var myVote: String? {
        let v: String = myAction ?? ""
        return v.isEmpty ? nil : v
    }

    var expiresAtDate: Date? {
        guard let s = expiresAt else { return nil }
        return Iso.parse(s)
    }
}

/// ISO-8601 instants, with or without fractional seconds.
enum Iso {
    private static let withFraction: ISO8601DateFormatter = {
        let f: ISO8601DateFormatter = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return f
    }()

    private static let plain: ISO8601DateFormatter = {
        let f: ISO8601DateFormatter = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime]
        return f
    }()

    static func parse(_ s: String) -> Date? {
        if let d = withFraction.date(from: s) { return d }
        return plain.date(from: s)
    }

    static func string(_ d: Date) -> String {
        return withFraction.string(from: d)
    }

    /// "2 h ago", "just now", "3 d ago".
    static func ago(_ s: String?) -> String {
        guard let s = s, let d = parse(s) else { return "" }
        let secs: Int = Int(Date().timeIntervalSince(d))
        if secs < 60 { return "just now" }
        if secs < 3600 { return "\(secs / 60) min ago" }
        if secs < 86_400 { return "\(secs / 3600) h ago" }
        return "\(secs / 86_400) d ago"
    }
}

/// The hazard kinds the server accepts.
enum HazardKinds {
    static let police: String = "police"
    static let hazard: String = "hazard"
    static let works: String = "works"
    static let fuel: String = "fuel"
    static let rest: String = "rest"
    static let crash: String = "crash"
}

// MARK: - Routing and voice

/// `GET /v1/route`. Everything the app does not strictly need is optional so a small server change cannot break decoding.
struct RouteManeuverDto: Decodable, Equatable {
    var type: String
    var modifier: String?
    var location: [Double]?

    private enum Keys: String, CodingKey {
        case type
        case modifier
        case location
    }

    init(from decoder: Decoder) throws {
        let c: KeyedDecodingContainer<Keys> = try decoder.container(keyedBy: Keys.self)
        type = (try? c.decodeIfPresent(String.self, forKey: .type)) ?? ""
        modifier = try? c.decodeIfPresent(String.self, forKey: .modifier)
        location = try? c.decodeIfPresent([Double].self, forKey: .location)
    }
}

struct RouteStepDto: Decodable, Equatable {
    var maneuver: RouteManeuverDto
    var name: String
    var distanceM: Double
    var durationS: Double
    var instruction: String

    private enum Keys: String, CodingKey {
        case maneuver
        case name
        case distanceM
        case durationS
        case instruction
    }

    init(from decoder: Decoder) throws {
        let c: KeyedDecodingContainer<Keys> = try decoder.container(keyedBy: Keys.self)
        maneuver = try c.decode(RouteManeuverDto.self, forKey: .maneuver)
        name = (try? c.decodeIfPresent(String.self, forKey: .name)) ?? ""
        distanceM = (try? c.decodeIfPresent(Double.self, forKey: .distanceM)) ?? 0
        durationS = (try? c.decodeIfPresent(Double.self, forKey: .durationS)) ?? 0
        instruction = (try? c.decodeIfPresent(String.self, forKey: .instruction)) ?? ""
    }
}

struct RouteDto: Decodable, Equatable {
    var profile: String?
    var geometry: String
    var geometryPrecision: Int?
    var distanceM: Double
    var durationS: Double
    var steps: [RouteStepDto]
}

/// `POST /v1/voice/token`.
struct VoiceTokenDto: Decodable, Equatable {
    var token: String
    var url: String
    var room: String?
    var identity: String?
    var expiresIn: Int?
}

// MARK: - Garage catalogue

/// `GET /v1/catalogue`: public and static; the ids the build fields carry.
struct CatalogueDto: Decodable {
    struct Entry: Decodable {
        var id: String
        var name: String?
        var hex: String?
        var slot: String?
    }

    var version: Int?
    var paints: [Entry]?
    var finishes: [Entry]?
    var wheels: [Entry]?
    var decals: [Entry]?
}

// MARK: Conditions around the map

/// A police report, camera, crash, closure, roadworks or jam (`GET /v1/conditions/area`).
struct ConditionItemDto: Decodable, Identifiable, Equatable {
    var id: String
    /// tfnsw, waze or convoyage.
    var source: String
    /// police, camera, hazard, crash, jam, closure, roadwork, event, flood, fire, alpine.
    var kind: String
    var title: String
    var detail: String
    var lat: Double
    var lng: Double
    var reportedAt: String?
}

struct AreaSourcesDto: Decodable, Equatable {
    var tfnsw: String
    var waze: String
}

struct AreaConditionsDto: Decodable {
    var items: [ConditionItemDto]
    var sources: AreaSourcesDto
}

/// The road layer (`GET /v1/roads`): GeoJSON lines with each road's signed limit.
struct RoadsDto: Decodable {
    struct Geometry: Decodable { var coordinates: [[Double]] }
    struct Properties: Decodable {
        var maxspeed: Int?
        var name: String?
    }
    struct Feature: Decodable {
        var geometry: Geometry
        var properties: Properties
    }
    var features: [Feature]
}
