import Foundation

// Wire models for protocol/convoy.v1.schema.json.
// TODO(codegen): generate these from the schema instead of keeping them in step by hand.

struct Destination: Codable, Equatable {
    var lat: Double
    var lng: Double
    var label: String
}

struct ConvoyRoute: Codable, Equatable {
    var name: String
    /// [lng, lat] pairs.
    var stops: [[Double]]
}

struct AutomaticRegroup: Codable, Equatable {
    var enabled: Bool
    var separationM: Double
}

struct MemberPosition: Codable, Equatable {
    var lat: Double
    var lng: Double
    var heading: Double?
    var speed: Double?
    var ts: Int64
}

struct Member: Codable, Equatable, Identifiable {
    var id: String
    var username: String
    var avatar: String
    var online: Bool
    var lastSeen: Int64
    var position: MemberPosition?
    /// Not in the v1 snapshot yet; read tolerantly in case the server adds it ("moving", "stopping", "sos").
    var status: String?
    /// The member's primary vehicle, for their avatar badge. Absent or null when they have none or the account is private.
    var vehicle: VehicleBadgeDto?
}

struct ConvoyChatMessage: Codable, Equatable, Identifiable {
    var id: String
    var clientId: String
    var userId: String
    var username: String
    var text: String
    var ts: Int64
}

struct Room: Codable, Equatable, Identifiable {
    var messages: [ConvoyChatMessage]
    var code: String
    var name: String
    var eventId: String?
    var leader: String
    var destination: Destination?
    var resting: Bool
    var route: ConvoyRoute?
    /// Road-following polyline (6 decimals) from the leader to the destination, when the server has one.
    /// Optional and read tolerantly: a missing or malformed value just means "no road line yet".
    var routeGeometry: String?
    var automaticRegroup: AutomaticRegroup
    var members: [Member]
    /// Open to Open Cruise drivers nearby (they can join without the code).
    var open: Bool

    var id: String { return code }

    private enum CodingKeys: String, CodingKey {
        case messages
        case code
        case name
        case eventId
        case leader
        case destination
        case resting
        case route
        case routeGeometry
        case automaticRegroup
        case members
        case open
    }

    init(from decoder: Decoder) throws {
        let c: KeyedDecodingContainer<CodingKeys> = try decoder.container(keyedBy: CodingKeys.self)
        messages = try c.decode([ConvoyChatMessage].self, forKey: .messages)
        code = try c.decode(String.self, forKey: .code)
        name = try c.decode(String.self, forKey: .name)
        eventId = try c.decodeIfPresent(String.self, forKey: .eventId)
        leader = try c.decode(String.self, forKey: .leader)
        destination = try c.decodeIfPresent(Destination.self, forKey: .destination)
        resting = try c.decode(Bool.self, forKey: .resting)
        route = try c.decodeIfPresent(ConvoyRoute.self, forKey: .route)
        let geometry: String? = (try? c.decodeIfPresent(String.self, forKey: .routeGeometry)) ?? nil
        routeGeometry = (geometry?.isEmpty ?? true) ? nil : geometry
        automaticRegroup = try c.decode(AutomaticRegroup.self, forKey: .automaticRegroup)
        members = try c.decode([Member].self, forKey: .members)
        open = (try? c.decodeIfPresent(Bool.self, forKey: .open)) ?? false
    }
}

/// An Open Cruise driver nearby (`cruise.nearby`). `canInvite` once within 500 m.
struct NearbyDriver: Decodable, Equatable, Identifiable {
    var id: String
    var username: String?
    var lat: Double
    var lng: Double
    var distanceM: Double?
    var canInvite: Bool?
}

/// An open convoy nearby (`cruise.nearby`): join it without the code.
struct NearbyConvoy: Decodable, Equatable, Identifiable {
    var code: String
    var name: String?
    var members: Int?
    var lat: Double
    var lng: Double
    var distanceM: Double?
    var destination: String?

    var id: String { return code }
}

/// Somebody nearby invited us (`cruise.invite`). Answer with `cruise.join` before `expiresAt` (ms).
struct CruiseInvite: Decodable, Equatable {
    var from: String
    var fromName: String?
    var code: String
    var name: String?
    var expiresAt: Int64?
}

/// A live position update for one member (server "pos").
struct PositionUpdate: Equatable {
    var room: String
    var id: String
    var lat: Double
    var lng: Double
    var heading: Double?
    var speed: Double?
    var ts: Int64
}

/// Everything the server can send. Decoded on the `t` field.
enum ServerMessage: Decodable {
    case pong
    case hello(me: String, rooms: [Room])
    case room(Room)
    case left(room: String, removed: Bool)
    case pos(PositionUpdate)
    case error(message: String, inType: String?)
    case chat(room: String, message: ConvoyChatMessage)
    case hazard(room: String, hazard: HazardDto)
    /// A member's `convoy.status` (stopping / moving / sos), broadcast by the server. Decoded tolerantly.
    case status(room: String?, id: String, status: String, note: String?)
    case cruiseState(on: Bool)
    case cruiseNearby(drivers: [NearbyDriver], convoys: [NearbyConvoy])
    case cruiseInvite(CruiseInvite)
    case cruiseInvited(to: String)
    case unknown(String)

    private enum Keys: String, CodingKey {
        case t
        case me
        case rooms
        case room
        case removed
        case id
        case lat
        case lng
        case heading
        case speed
        case ts
        case error
        case inType = "in"
        case message
        case hazard
        case userId
        case status
        case note
        case on
        case drivers
        case convoys
        case to
    }

    init(from decoder: Decoder) throws {
        let c: KeyedDecodingContainer<Keys> = try decoder.container(keyedBy: Keys.self)
        let t: String = try c.decode(String.self, forKey: .t)
        switch t {
        case "pong":
            self = .pong
        case "hello":
            let me: String = try c.decode(String.self, forKey: .me)
            let rooms: [Room] = try c.decode([Room].self, forKey: .rooms)
            self = .hello(me: me, rooms: rooms)
        case "room":
            self = .room(try c.decode(Room.self, forKey: .room))
        case "left":
            let room: String = try c.decode(String.self, forKey: .room)
            let removed: Bool = try c.decodeIfPresent(Bool.self, forKey: .removed) ?? false
            self = .left(room: room, removed: removed)
        case "pos":
            let update: PositionUpdate = PositionUpdate(
                room: try c.decode(String.self, forKey: .room),
                id: try c.decode(String.self, forKey: .id),
                lat: try c.decode(Double.self, forKey: .lat),
                lng: try c.decode(Double.self, forKey: .lng),
                heading: try c.decodeIfPresent(Double.self, forKey: .heading),
                speed: try c.decodeIfPresent(Double.self, forKey: .speed),
                ts: try c.decode(Int64.self, forKey: .ts)
            )
            self = .pos(update)
        case "error":
            let text: String = try c.decode(String.self, forKey: .error)
            let inType: String? = try c.decodeIfPresent(String.self, forKey: .inType)
            self = .error(message: text, inType: inType)
        case "convoy.message":
            let room: String = try c.decode(String.self, forKey: .room)
            let message: ConvoyChatMessage = try c.decode(ConvoyChatMessage.self, forKey: .message)
            self = .chat(room: room, message: message)
        case "hazard":
            let room: String = try c.decode(String.self, forKey: .room)
            let hazard: HazardDto = try c.decode(HazardDto.self, forKey: .hazard)
            self = .hazard(room: room, hazard: hazard)
        case "status", "convoy.status":
            var who: String = try c.decodeIfPresent(String.self, forKey: .id) ?? ""
            if who.isEmpty { who = try c.decodeIfPresent(String.self, forKey: .userId) ?? "" }
            let st: String = try c.decodeIfPresent(String.self, forKey: .status) ?? ""
            if who.isEmpty || st.isEmpty {
                self = .unknown(t)
            } else {
                let room: String? = try? c.decodeIfPresent(String.self, forKey: .room)
                let note: String? = try? c.decodeIfPresent(String.self, forKey: .note)
                self = .status(room: room, id: who, status: st, note: note)
            }
        case "cruise.state":
            self = .cruiseState(on: try c.decodeIfPresent(Bool.self, forKey: .on) ?? false)
        case "cruise.nearby":
            let drivers: [NearbyDriver] = (try? c.decodeIfPresent([NearbyDriver].self, forKey: .drivers)) ?? []
            let convoys: [NearbyConvoy] = (try? c.decodeIfPresent([NearbyConvoy].self, forKey: .convoys)) ?? []
            self = .cruiseNearby(drivers: drivers, convoys: convoys)
        case "cruise.invite":
            self = .cruiseInvite(try CruiseInvite(from: decoder))
        case "cruise.invited":
            self = .cruiseInvited(to: try c.decodeIfPresent(String.self, forKey: .to) ?? "")
        default:
            self = .unknown(t)
        }
    }
}

/// Everything the client can send. Encoded by hand to keep `null` and omitted fields exact.
enum ClientMessage {
    case ping
    case posStop
    case pos(lat: Double, lng: Double, heading: Double?, speed: Double?)
    case create(name: String, regroup: AutomaticRegroup)
    case join(code: String)
    /// Opens (or joins) the convoy of a PhotoScout cruise event.
    case cruise(eventId: String)
    case leave(room: String)
    case destination(room: String, destination: Destination?)
    case rest(room: String, action: String)
    case lead(room: String, to: String)
    case remove(room: String, id: String)
    case chat(room: String, text: String, clientId: String)
    case status(status: String, note: String?)
    case route(room: String, name: String, stops: [[Double]])
    case rename(room: String, name: String)
    case regroup(room: String, enabled: Bool, separationM: Double)
    /// Leader: open the convoy to Open Cruise drivers nearby, or close it.
    case convoyOpen(room: String, open: Bool)
    /// Open Cruise on or off.
    case cruiseOpen(on: Bool)
    /// Invite an Open Cruise driver within 500 m.
    case cruiseInvite(to: String)
    /// Join an open convoy nearby, or one we were invited to, without its code.
    case cruiseJoin(code: String)

    var payload: [String: Any] {
        switch self {
        case .ping:
            return ["t": "ping"]
        case .posStop:
            return ["t": "pos.stop"]
        case let .pos(lat, lng, heading, speed):
            var o: [String: Any] = ["t": "pos", "lat": lat, "lng": lng]
            if let h = heading { o["heading"] = h }
            if let s = speed { o["speed"] = s }
            return o
        case let .create(name, regroup):
            return ["t": "convoy.create", "name": name, "automaticRegroup": ["enabled": regroup.enabled, "separationM": regroup.separationM] as [String: Any]]
        case let .join(code):
            return ["t": "convoy.join", "code": code]
        case let .cruise(eventId):
            return ["t": "convoy.cruise", "eventId": eventId]
        case let .leave(room):
            return ["t": "convoy.leave", "room": room]
        case let .destination(room, destination):
            var o: [String: Any] = ["t": "convoy.dest", "room": room]
            if let d = destination {
                o["destination"] = ["lat": d.lat, "lng": d.lng, "label": d.label] as [String: Any]
            } else {
                o["destination"] = NSNull()
            }
            return o
        case let .rest(room, action):
            return ["t": "convoy.rest", "room": room, "action": action]
        case let .lead(room, to):
            return ["t": "convoy.lead", "room": room, "to": to]
        case let .remove(room, id):
            return ["t": "convoy.remove", "room": room, "id": id]
        case let .chat(room, text, clientId):
            return ["t": "convoy.message", "room": room, "text": text, "clientId": clientId]
        case let .status(status, note):
            var o: [String: Any] = ["t": "convoy.status", "status": status]
            if let n = note, !n.isEmpty { o["note"] = String(n.prefix(200)) }
            return o
        case let .rename(room, name):
            return ["t": "convoy.rename", "room": room, "name": name]
        case let .regroup(room, enabled, separationM):
            return ["t": "convoy.regroup", "room": room, "enabled": enabled, "separationM": separationM]
        case let .route(room, name, stops):
            return ["t": "convoy.route", "room": room, "route": ["name": name, "stops": stops] as [String: Any]]
        case let .convoyOpen(room, open):
            return ["t": "convoy.open", "room": room, "open": open]
        case let .cruiseOpen(on):
            return ["t": "cruise.open", "on": on]
        case let .cruiseInvite(to):
            return ["t": "cruise.invite", "to": to]
        case let .cruiseJoin(code):
            return ["t": "cruise.join", "code": code]
        }
    }

    func jsonString() -> String? {
        guard let data = try? JSONSerialization.data(withJSONObject: payload, options: []) else { return nil }
        return String(data: data, encoding: .utf8)
    }
}
