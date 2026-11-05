import Foundation

/// Something worth saying out loud about the convoy. `message` lines are chat; the rest are events.
struct ConvoyNotice: Equatable {
    var text: String
    var message: Bool = false
}

/// What changed between two snapshots of the convoy, as spoken lines (Android's `ConvoyEvents`): joins, leaves,
/// reconnects, a new leader, destination / rest stop / route changes. Stopping, SOS, chat and arrivals come from
/// their own frames (see `ConvoyViewModel`). Pure, so it is easy to reason about.
@MainActor
enum ConvoyEvents {
    /// Another member within this of the destination has arrived ...
    static let arrivedM: Double = 100
    /// ... once they were further than this (GPS jitter at the gate does not announce it twice).
    static let leftM: Double = 150

    static func between(_ before: Room?, _ room: Room?, me: String) -> [ConvoyNotice] {
        guard let room = room else { return [] }
        guard let before = before, before.code == room.code else {
            let name: String = room.name.isEmpty ? "the convoy" : room.name
            return [ConvoyNotice(text: "Joined " + name)]
        }
        var out: [ConvoyNotice] = []
        func say(_ t: String) { out.append(ConvoyNotice(text: t)) }
        func name(_ id: String) -> String {
            let m: Member? = room.members.first { (x: Member) -> Bool in return x.id == id }
                ?? before.members.first { (x: Member) -> Bool in return x.id == id }
            let n: String = m.map { (x: Member) -> String in return Formatters.shownName(x.username) } ?? ""
            return n.isEmpty ? "A member" : n
        }
        let beforeIds: Set<String> = Set(before.members.map { (m: Member) -> String in return m.id })
        let nowIds: Set<String> = Set(room.members.map { (m: Member) -> String in return m.id })
        for m in room.members where m.id != me && !beforeIds.contains(m.id) {
            say(name(m.id) + " joined the convoy")
        }
        for m in before.members where m.id != me && !nowIds.contains(m.id) {
            say(name(m.id) + " left the convoy")
        }
        for m in room.members where m.id != me {
            guard let was = before.members.first(where: { (x: Member) -> Bool in return x.id == m.id }) else { continue }
            if was.online != m.online { say(name(m.id) + (m.online ? " reconnected" : " disconnected")) }
        }
        if before.leader != room.leader && !room.leader.isEmpty {
            say(room.leader == me ? "You are now leading" : name(room.leader) + " is now leading")
        }
        if before.destination != room.destination {
            if let d = room.destination {
                if room.resting {
                    say("Rest stop: " + (d.label.isEmpty ? "new regroup point" : d.label))
                } else {
                    say("Destination: " + (d.label.isEmpty ? "new destination" : d.label))
                }
            } else {
                say("Convoy destination cleared")
            }
        }
        if before.route?.name != room.route?.name || (before.route == nil) != (room.route == nil) {
            if let r = room.route {
                say("Route shared: " + (r.name.isEmpty ? "convoy route" : r.name))
            } else {
                say("Convoy route cleared")
            }
        }
        return out
    }

    /// A member's position moved from `then` to `now`: did they just arrive at the destination?
    static func arrived(then: MemberPosition, now: MemberPosition, at d: Destination) -> Bool {
        let dNow: Double = GeoMath.distanceM(now.lat, now.lng, d.lat, d.lng)
        let dThen: Double = GeoMath.distanceM(then.lat, then.lng, d.lat, d.lng)
        return dNow <= arrivedM && dThen > leftM
    }
}
