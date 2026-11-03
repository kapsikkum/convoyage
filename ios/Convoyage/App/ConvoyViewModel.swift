import Foundation
import CoreLocation
import Observation

/// Owns the socket, the current convoy and the 1 Hz position sender.
@Observable
@MainActor
final class ConvoyViewModel {
    var room: Room?
    var myId: String = ""
    var socketState: SocketState = .idle
    var lastError: String?
    /// Live positions that arrived after the last full room snapshot.
    var livePositions: [String: MemberPosition] = [:]

    let location: LocationService = LocationService()

    @ObservationIgnored private let socket: ConvoySocket = ConvoySocket()
    @ObservationIgnored private var ticker: Task<Void, Never>?
    @ObservationIgnored private var userId: String = ""
    @ObservationIgnored private var userName: String = ""

    init() {
        socket.onMessage = { [weak self] (m: ServerMessage) in
            self?.handle(m)
        }
        socket.onState = { [weak self] (s: SocketState) in
            self?.socketState = s
        }
    }

    // MARK: Lifecycle

    func connect(userId: String, name: String) {
        self.userId = userId
        self.userName = name
        myId = userId
        location.start()
        socket.connect(userId: userId, name: name)
        startTicker()
    }

    func disconnect() {
        ticker?.cancel()
        ticker = nil
        socket.send(.posStop)
        socket.disconnect()
        location.stop()
        room = nil
        livePositions = [:]
        socketState = .idle
    }

    func reconnect() {
        if !userId.isEmpty { socket.connect(userId: userId, name: userName) }
    }

    // MARK: Commands

    func createConvoy(name: String) {
        let trimmed: String = name.trimmingCharacters(in: .whitespacesAndNewlines)
        socket.send(.create(name: trimmed.isEmpty ? "\(userName)'s convoy" : trimmed,
                            regroup: AutomaticRegroup(enabled: true, separationM: 2000)))
    }

    func joinConvoy(code: String) {
        socket.send(.join(code: code))
    }

    func leaveConvoy() {
        if let r = room { socket.send(.leave(room: r.code)) }
    }

    func requestRegroup() {
        if let r = room { socket.send(.rest(room: r.code, action: "find")) }
    }

    // MARK: Incoming

    private func handle(_ m: ServerMessage) {
        switch m {
        case .pong, .unknown:
            break
        case .hello(let me, let rooms):
            myId = me
            room = rooms.first
            livePositions = [:]
        case .room(let r):
            if room == nil || room?.code == r.code {
                room = r
                livePositions = [:]
            }
        case .left(let code, _):
            if room?.code == code {
                room = nil
                livePositions = [:]
            }
        case .pos(let u):
            if room?.code == u.room {
                livePositions[u.id] = MemberPosition(lat: u.lat, lng: u.lng, heading: u.heading, speed: u.speed, ts: u.ts)
            }
        case .error(let message, _):
            lastError = message
        case .chat(let code, let message):
            if room?.code == code {
                var r: Room = room!
                r.messages.append(message)
                room = r
            }
        }
    }

    // MARK: Outgoing position, about 1 Hz while the app is in the foreground

    private func startTicker() {
        ticker?.cancel()
        ticker = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 1_000_000_000)
                self?.sendPosition()
            }
        }
    }

    private func sendPosition() {
        guard socketState == .open, let loc = location.latest else { return }
        // Skip stale fixes (older than 10 s).
        if abs(loc.timestamp.timeIntervalSinceNow) > 10 { return }
        let heading: Double? = loc.course >= 0 ? loc.course : nil
        let speed: Double? = loc.speed >= 0 ? min(loc.speed, 120) : nil
        socket.send(.pos(lat: loc.coordinate.latitude, lng: loc.coordinate.longitude, heading: heading, speed: speed))
    }

    // MARK: Derived

    var members: [Member] {
        guard let r = room else { return [] }
        return r.members.map { (m: Member) -> Member in
            var copy: Member = m
            if let live = livePositions[m.id] { copy.position = live }
            return copy
        }
    }

    var leaderMember: Member? {
        guard let r = room else { return nil }
        return members.first(where: { $0.id == r.leader })
    }

    var onlineCount: Int {
        return members.filter { $0.online }.count
    }

    func distance(to member: Member) -> Double? {
        guard let p = member.position, let loc = location.latest else { return nil }
        return loc.distance(from: CLLocation(latitude: p.lat, longitude: p.lng))
    }

    func isStopped(_ member: Member) -> Bool {
        guard member.online, let s = member.position?.speed else { return false }
        return s < 0.8
    }

    /// Largest distance from me to any other online member, metres.
    var spreadMeters: Double? {
        let ds: [Double] = members.filter { $0.id != myId && $0.online }.compactMap { distance(to: $0) }
        return ds.max()
    }

    /// The member furthest from me, if past the convoy's regroup separation.
    var straggler: (member: Member, meters: Double)? {
        guard let r = room else { return nil }
        var best: (member: Member, meters: Double)? = nil
        for m in members where m.id != myId && m.online {
            if let d = distance(to: m), d > r.automaticRegroup.separationM, d > (best?.meters ?? 0) {
                best = (member: m, meters: d)
            }
        }
        return best
    }

    var mapPins: [MapPin] {
        return members.compactMap { (m: Member) -> MapPin? in
            guard m.id != myId, let p = m.position else { return nil }
            let kind: MapPin.Kind
            if m.id == room?.leader { kind = .lead }
            else if isStopped(m) { kind = .stopped }
            else { kind = .member }
            return MapPin(id: m.id,
                          initials: Formatters.initials(m.username),
                          kind: kind,
                          coordinate: CLLocationCoordinate2D(latitude: p.lat, longitude: p.lng))
        }
    }
}

enum Formatters {
    static func initials(_ name: String) -> String {
        let parts: [Substring] = name.split(separator: " ")
        if parts.count >= 2, let a = parts[0].first, let b = parts[1].first {
            return String([a, b]).uppercased()
        }
        return String(name.prefix(2)).uppercased()
    }

    static func distance(_ meters: Double) -> (value: String, unit: String) {
        if meters >= 1000 {
            return (String(format: "%.1f", meters / 1000), "KM")
        }
        return (String(Int(meters.rounded())), "M")
    }

    static func distanceText(_ meters: Double) -> String {
        let d = distance(meters)
        return "\(d.value) \(d.unit)"
    }

    static func kmh(_ metersPerSecond: Double?) -> Int {
        guard let s = metersPerSecond, s >= 0 else { return 0 }
        return Int((s * 3.6).rounded())
    }
}
