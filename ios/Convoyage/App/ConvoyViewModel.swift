import Foundation
import CoreLocation
import Observation

/// Who the socket signs in as. `account` uses the stored JWT; `dev` is the DEBUG-only `?dev_user=` auth.
enum SocketAuth {
    case account
    case dev(userId: String, name: String)
}

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

    /// "<name> is stopping", raised by a convoy chat message from another member.
    var stopNotice: String?
    /// Set by me: I told the convoy I am stopping.
    var iAmStopping: Bool = false

    let location: LocationService = LocationService()

    /// Supplies access tokens for the socket handshake (refreshing them when needed). `force` = the server just refused one.
    @ObservationIgnored var tokenProvider: ((Bool) async -> String?)?
    /// Every live `hazard` frame (fed to the HazardStore).
    /// My display name, for default convoy names.
    @ObservationIgnored var displayName: String = ""
    @ObservationIgnored var onHazard: ((String, HazardDto) -> Void)?

    @ObservationIgnored private let socket: ConvoySocket = ConvoySocket()
    @ObservationIgnored private var ticker: Task<Void, Never>?
    @ObservationIgnored private var connection: Task<Void, Never>?
    @ObservationIgnored private var noticeTimer: Task<Void, Never>?
    @ObservationIgnored private var auth: SocketAuth?

    init() {
        socket.onMessage = { [weak self] (m: ServerMessage) in
            self?.handle(m)
        }
        socket.onState = { [weak self] (s: SocketState) in
            self?.socketState = s
        }
    }

    // MARK: Lifecycle

    /// Connects (and keeps reconnecting with backoff). A fresh access token is fetched for every attempt.
    func connect(auth: SocketAuth, myId: String) {
        self.auth = auth
        self.myId = myId
        location.start()
        startConnection()
        startTicker()
    }

    func disconnect() {
        connection?.cancel()
        connection = nil
        ticker?.cancel()
        ticker = nil
        noticeTimer?.cancel()
        noticeTimer = nil
        socket.send(.posStop)
        socket.close()
        location.stop()
        auth = nil
        room = nil
        livePositions = [:]
        socketState = .idle
        stopNotice = nil
        iAmStopping = false
    }

    func reconnect() {
        if auth != nil { startConnection() }
    }

    private func startConnection() {
        connection?.cancel()
        socket.close()
        guard let a = auth else { return }
        connection = Task { [weak self] in
            await self?.connectionLoop(a)
        }
    }

    private func connectionLoop(_ a: SocketAuth) async {
        var attempt: Int = 0
        var refreshedAfter401: Bool = false
        while !Task.isCancelled {
            var url: URL? = nil
            var protocols: [String] = []
            switch a {
            case .account:
                guard let provider = tokenProvider, let jwt = await provider(refreshedAfter401) else {
                    socketState = .closed("Signed out")
                    return
                }
                url = ServerConfig.socketURL()
                protocols = [wsProtocolName, "bearer.\(jwt)"]
            case .dev(let userId, let name):
                url = ServerConfig.devSocketURL(userId: userId, name: name)
            }
            guard let target = url else {
                socketState = .closed("Server URL is not valid")
                return
            }
            let end: SocketEnd = await socket.run(url: target, protocols: protocols)
            if Task.isCancelled { return }
            if end.opened {
                attempt = 0
                refreshedAfter401 = false
            }
            if end.closeCode == closeSessionEnded {
                lastError = "Session ended"
                socketState = .closed("Session ended")
                return
            }
            if !end.opened && end.httpStatus == 401 {
                var isAccount: Bool = false
                if case .account = a { isAccount = true }
                if isAccount && !refreshedAfter401 {
                    // The access token probably expired: get a new one and try once more, right away.
                    refreshedAfter401 = true
                    continue
                }
                let msg: String = isAccount ? "Server refused sign-in" : "Server refused sign-in (is CONVOYAGE_DEV_AUTH=1 set?)"
                lastError = msg
                socketState = .closed(msg)
                return
            }
            attempt += 1
            let seconds: Double = min(15.0, Double(1 << min(attempt, 4)))
            try? await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
        }
    }

    // MARK: Commands

    func createConvoy(name: String) {
        let trimmed: String = name.trimmingCharacters(in: .whitespacesAndNewlines)
        socket.send(.create(name: trimmed.isEmpty ? "\(displayName)'s convoy" : trimmed,
                            regroup: AutomaticRegroup(enabled: true, separationM: 2000)))
    }

    func joinConvoy(code: String) {
        socket.send(.join(code: code))
    }

    func leaveConvoy() {
        if let r = room { socket.send(.leave(room: r.code)) }
    }

    /// "I'm stopping": a local flag plus a convoy chat line (the v1 protocol has no dedicated stopped signal).
    func setStopping(_ stopping: Bool) {
        iAmStopping = stopping
        if let r = room {
            let text: String = stopping ? Self.stoppingText : "I'm moving again"
            socket.send(.chat(room: r.code, text: text, clientId: UUID().uuidString))
        }
    }

    static let stoppingText: String = "I'm stopping"

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
                if message.userId != myId && message.text == Self.stoppingText {
                    raiseStopNotice("\(message.username) is stopping")
                }
            }
        case .hazard(let code, let hazard):
            onHazard?(code, hazard)
        }
    }

    private func raiseStopNotice(_ text: String) {
        stopNotice = text
        noticeTimer?.cancel()
        noticeTimer = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 45_000_000_000)
            if Task.isCancelled { return }
            self?.stopNotice = nil
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

    /// 3:42 (m:ss) or 1:03:42 (h:mm:ss).
    static func duration(_ seconds: Double) -> String {
        let total: Int = max(0, Int(seconds.rounded()))
        let h: Int = total / 3600
        let m: Int = (total % 3600) / 60
        let sec: Int = total % 60
        if h > 0 { return String(format: "%d:%02d:%02d", h, m, sec) }
        return String(format: "%d:%02d", m, sec)
    }

    static func kmh(_ metersPerSecond: Double?) -> Int {
        guard let s = metersPerSecond, s >= 0 else { return 0 }
        return Int((s * 3.6).rounded())
    }
}
