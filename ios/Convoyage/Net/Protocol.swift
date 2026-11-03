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
    var automaticRegroup: AutomaticRegroup
    var members: [Member]

    var id: String { return code }
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
    case leave(room: String)
    case destination(room: String, destination: Destination?)
    case rest(room: String, action: String)
    case lead(room: String, to: String)
    case remove(room: String, id: String)
    case chat(room: String, text: String, clientId: String)

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
        }
    }

    func jsonString() -> String? {
        guard let data = try? JSONSerialization.data(withJSONObject: payload, options: []) else { return nil }
        return String(data: data, encoding: .utf8)
    }
}
