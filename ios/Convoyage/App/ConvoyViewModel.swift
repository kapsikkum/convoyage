import Foundation
import CoreLocation
import Observation

/// Who the socket signs in as. `account` uses the stored JWT; `dev` is the DEBUG-only `?dev_user=` auth.
enum SocketAuth {
    case account
    case dev(userId: String, name: String)
}

/// What a member told the convoy about themselves (`convoy.status`).
enum MemberStatusKind: String {
    case stopping
    case moving
    case sos
}

struct MemberStatus: Equatable {
    var kind: MemberStatusKind
    var note: String?
    var at: Date
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

    /// "<name> is stopping", raised by a `status` frame (or the old chat line) from another member.
    var stopNotice: String?
    /// Set by me: I told the convoy I am stopping.
    var iAmStopping: Bool = false
    /// Latest status per member id (stopping / sos). A "moving" status removes the entry.
    var statuses: [String: MemberStatus] = [:]
    /// Open SOS calls from other members: member id -> text. Stays until they say they are moving or I dismiss it.
    var sosNotices: [String: String] = [:]
    /// Chat messages up to this server timestamp count as read.
    var lastReadTs: Int64 = 0
    /// A convoy invite code waiting for the socket (deep link before sign-in / connect).
    var pendingJoin: String?
    /// An invite code that needs a decision because I am already in another convoy.
    var joinPrompt: String?
    /// A road-following line fetched by this device (the leader) while the server has none.
    var leaderRoute: [CLLocationCoordinate2D] = []

    let location: LocationService = LocationService()

    /// Supplies access tokens for the socket handshake (refreshing them when needed). `force` = the server just refused one.
    @ObservationIgnored var tokenProvider: ((Bool) async -> String?)?
    /// My display name, for default convoy names.
    @ObservationIgnored var displayName: String = ""
    /// Every live `hazard` frame (fed to the HazardStore).
    @ObservationIgnored var onHazard: ((String, HazardDto) -> Void)?
    /// Called when I enter or leave a convoy (background location follows it).
    @ObservationIgnored var onRoomChange: (() -> Void)?
    /// REST access for `/v1/route` (set by AppState).
    @ObservationIgnored var api: ApiClient?
    /// "car" or "motorcycle", from the saved drive / ride choice.
    @ObservationIgnored var profileProvider: () -> String = { return "car" }
    /// Navigation started a convoy: set this destination (and use this route line) as soon as the room exists.
    @ObservationIgnored private var pendingNav: (place: NavPlace, line: [CLLocationCoordinate2D])?
    /// The navigation route to reuse for the destination it ends at, so the leader does not fetch it twice.
    @ObservationIgnored private var navHint: (dest: CLLocationCoordinate2D, line: [CLLocationCoordinate2D])?
    @ObservationIgnored private var routeKey: String?
    @ObservationIgnored private var routeFetch: Task<Void, Never>?
    @ObservationIgnored private var lastLeaderCommand: Date?
    @ObservationIgnored private var geometryCache: (text: String, coords: [CLLocationCoordinate2D])?
    /// False once the server answered "Unknown message" to `convoy.status`: fall back to chat lines.
    @ObservationIgnored private var statusSupported: Bool = true
    @ObservationIgnored private var lastStatusSend: (kind: MemberStatusKind, at: Date)?

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
        statuses = [:]
        sosNotices = [:]
        resetLeaderRoute()
        pendingNav = nil
        statusSupported = true
        onRoomChange?()
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

    static func normalizeCode(_ raw: String) -> String {
        let stripped: String = raw.filter { (ch: Character) -> Bool in
            return !ch.isWhitespace && ch != "-"
        }
        return stripped.uppercased()
    }

    /// Invite codes: 8 characters from this alphabet (no 0/O/1/I/L).
    static func isValidCode(_ code: String) -> Bool {
        return code.range(of: "^[A-HJKMNP-Z2-9]{8}$", options: .regularExpression) != nil
    }

    /// A name for a convoy when the person types none.
    var defaultConvoyName: String {
        if Formatters.isGeneratedName(displayName) { return "Convoy" }
        return "\(displayName.trimmingCharacters(in: .whitespacesAndNewlines))'s convoy"
    }

    func createConvoy(name: String, regroupEnabled: Bool = true, separationM: Double = 2000) {
        let trimmed: String = name.trimmingCharacters(in: .whitespacesAndNewlines)
        let sep: Double = min(20000, max(1000, separationM))
        let finalName: String = trimmed.isEmpty ? defaultConvoyName : trimmed
        socket.send(.create(name: String(finalName.prefix(40)),
                            regroup: AutomaticRegroup(enabled: regroupEnabled, separationM: sep)))
    }

    func joinConvoy(code: String) {
        socket.send(.join(code: Self.normalizeCode(code)))
    }

    /// Join from a link: waits for the socket, asks first when I am already in another convoy.
    func requestJoin(code: String) {
        let clean: String = Self.normalizeCode(code)
        if !Self.isValidCode(clean) {
            lastError = "That is not a convoy code"
            return
        }
        if let r = room {
            if r.code == clean { return }
            joinPrompt = clean
            return
        }
        if socketState == .open {
            socket.send(.join(code: clean))
        } else {
            pendingJoin = clean
        }
    }

    /// Leave the current convoy and join `code` (the one in `joinPrompt`).
    func confirmSwitch(code: String) {
        if let r = room { socket.send(.leave(room: r.code)) }
        socket.send(.join(code: code))
        joinPrompt = nil
    }

    func leaveConvoy() {
        if let r = room { socket.send(.leave(room: r.code)) }
    }

    // MARK: Status ("I'm stopping", SOS)

    func setStopping(_ stopping: Bool) {
        iAmStopping = stopping
        sendStatus(stopping ? .stopping : .moving, note: nil)
    }

    /// Sends `convoy.status`. A server that does not know it answers "Unknown message": see `handle(.error)`.
    func sendStatus(_ kind: MemberStatusKind, note: String?) {
        guard room != nil else { return }
        if statusSupported {
            lastStatusSend = (kind: kind, at: Date())
            socket.send(.status(status: kind.rawValue, note: note))
        } else {
            sendStatusFallback(kind)
        }
    }

    private func sendStatusFallback(_ kind: MemberStatusKind) {
        guard let r = room else { return }
        switch kind {
        case .stopping:
            socket.send(.chat(room: r.code, text: Self.stoppingText, clientId: UUID().uuidString))
        case .moving:
            socket.send(.chat(room: r.code, text: Self.movingText, clientId: UUID().uuidString))
        case .sos:
            break // the SOS chat message that always goes with it carries the alert
        }
    }

    /// The crash countdown ran out: SOS status plus a chat line with where I am. Returns false when not in a convoy.
    @discardableResult
    func sendSOS() -> Bool {
        guard let r = room else { return false }
        sendStatus(.sos, note: "Possible crash")
        var text: String = "SOS: possible crash detected and no response."
        if let loc = location.latest {
            let lat: String = String(format: "%.5f", loc.coordinate.latitude)
            let lng: String = String(format: "%.5f", loc.coordinate.longitude)
            text += " Location \(lat), \(lng) https://maps.apple.com/?ll=\(lat),\(lng)"
        }
        // The server allows one chat message per member every 2 s, so let the status go first.
        let code: String = r.code
        Task { [weak self] in
            try? await Task.sleep(nanoseconds: 2_200_000_000)
            self?.socket.send(.chat(room: code, text: text, clientId: UUID().uuidString))
        }
        iAmStopping = true
        return true
    }

    static let stoppingText: String = "I'm stopping"
    static let movingText: String = "I'm moving again"

    func requestRegroup() {
        if let r = room { socket.send(.rest(room: r.code, action: "find")) }
    }

    func restContinue() {
        if let r = room { socket.send(.rest(room: r.code, action: "continue")) }
    }

    // MARK: Chat

    var chatMessages: [ConvoyChatMessage] {
        return room?.messages ?? []
    }

    var unreadCount: Int {
        guard let r = room else { return 0 }
        return r.messages.filter { (m: ConvoyChatMessage) -> Bool in
            return m.userId != myId && m.ts > lastReadTs
        }.count
    }

    func markChatRead() {
        if let last = room?.messages.last?.ts, last > lastReadTs { lastReadTs = last }
    }

    func sendChat(_ text: String) {
        guard let r = room else { return }
        let clean: String = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if clean.isEmpty { return }
        socket.send(.chat(room: r.code, text: String(clean.prefix(500)), clientId: UUID().uuidString))
    }

    /// Display name for a user id in this convoy (never a raw generated handle).
    func nameOf(userId: String) -> String {
        if let m = room?.members.first(where: { (x: Member) -> Bool in return x.id == userId }) {
            return Formatters.shownName(m.username)
        }
        return "Someone"
    }

    // MARK: Leader tools

    var isLeader: Bool {
        guard let r = room else { return false }
        return r.leader == myId
    }

    func setDestination(_ coordinate: CLLocationCoordinate2D, label: String) {
        guard let r = room else { return }
        let clean: String = label.trimmingCharacters(in: .whitespacesAndNewlines)
        let d: Destination = Destination(lat: coordinate.latitude, lng: coordinate.longitude,
                                         label: String((clean.isEmpty ? "Destination" : clean).prefix(80)))
        socket.send(.destination(room: r.code, destination: d))
    }

    func clearDestination() {
        if let r = room { socket.send(.destination(room: r.code, destination: nil)) }
    }

    /// Leader renames the convoy. An older server answers "Unknown message": see `handle(.error)`.
    func renameConvoy(_ name: String) {
        guard let r = room else { return }
        let clean: String = name.trimmingCharacters(in: .whitespacesAndNewlines)
        if clean.isEmpty { return }
        lastLeaderCommand = Date()
        socket.send(.rename(room: r.code, name: String(clean.prefix(40))))
    }

    /// Leader changes automatic regroup. An older server answers "Unknown message".
    func setAutomaticRegroup(enabled: Bool, separationM: Double) {
        guard let r = room else { return }
        lastLeaderCommand = Date()
        socket.send(.regroup(room: r.code, enabled: enabled, separationM: min(20000, max(1000, separationM))))
    }

    /// Navigation's "Invite friends": start a convoy that heads for the navigation destination along the navigation
    /// route. If I am already in a convoy, I only set the destination (when I lead it).
    func startConvoyForNavigation(place: NavPlace, line: [CLLocationCoordinate2D]) {
        if room != nil {
            if isLeader { applyNavDestination(place: place, line: line) }
            return
        }
        pendingNav = (place: place, line: line)
        createConvoy(name: "")
    }

    private func applyNavDestination(place: NavPlace, line: [CLLocationCoordinate2D]) {
        navHint = (dest: place.coordinate, line: line)
        setDestination(place.coordinate, label: place.title)
    }

    // MARK: Road-following route line

    /// Decoded `routeGeometry` of the room (polyline6), cached by its text.
    private var serverRouteLine: [CLLocationCoordinate2D] {
        guard let g = room?.routeGeometry, !g.isEmpty else { return [] }
        if let c = geometryCache, c.text == g { return c.coords }
        let coords: [CLLocationCoordinate2D] = Polyline.decode(g, precision: 6)
        geometryCache = (text: g, coords: coords)
        return coords
    }

    private func resetLeaderRoute() {
        routeFetch?.cancel()
        routeFetch = nil
        routeKey = nil
        navHint = nil
        if !leaderRoute.isEmpty { leaderRoute = [] }
    }

    /// When I lead and the server has no road line for the destination, fetch one from `/v1/route` and draw it
    /// (and share a simplified copy so the others and the rest-stop search have it too).
    private func updateLeaderRoute() {
        guard let r = room, let d = r.destination, r.leader == myId else {
            resetLeaderRoute()
            return
        }
        if r.routeGeometry != nil {
            // The server's line wins; drop mine.
            if !leaderRoute.isEmpty { leaderRoute = [] }
            return
        }
        let key: String = "\(r.code)|\(d.lat)|\(d.lng)"
        if routeKey == key { return }
        routeKey = key
        routeFetch?.cancel()
        let dest: CLLocationCoordinate2D = CLLocationCoordinate2D(latitude: d.lat, longitude: d.lng)
        routeFetch = Task { [weak self] in
            await self?.fetchLeaderRoute(code: r.code, dest: dest, key: key)
        }
    }

    private func fetchLeaderRoute(code: String, dest: CLLocationCoordinate2D, key: String) async {
        var line: [CLLocationCoordinate2D] = []
        if let hint = navHint, abs(hint.dest.latitude - dest.latitude) < 0.00002, abs(hint.dest.longitude - dest.longitude) < 0.00002 {
            line = hint.line
        } else {
            // The server may deliver its own line in a moment; give it three seconds first.
            try? await Task.sleep(nanoseconds: 3_000_000_000)
            if Task.isCancelled || routeKey != key { return }
            if room?.routeGeometry != nil { return }
            guard let api = api, let loc = location.latest else {
                if routeKey == key { routeKey = nil }
                return
            }
            let result: ApiResult<RouteDto> = await api.route(fromLng: loc.coordinate.longitude, fromLat: loc.coordinate.latitude,
                                                               toLng: dest.longitude, toLat: dest.latitude,
                                                               profile: profileProvider())
            if Task.isCancelled || routeKey != key { return }
            guard case .ok(let dto) = result else {
                if routeKey == key { routeKey = nil }
                return
            }
            line = Polyline.decode(dto.geometry, precision: dto.geometryPrecision ?? 6)
        }
        if line.count < 2 || room?.code != code || room?.routeGeometry != nil { return }
        leaderRoute = line
        if room?.route == nil || (room?.route?.stops.count ?? 0) <= 2 {
            let stops: [[Double]] = ConvoyViewModel.simplify(line, maxPoints: 25).map { (c: CLLocationCoordinate2D) -> [Double] in
                return [c.longitude, c.latitude]
            }
            if let r = room, stops.count >= 2 {
                socket.send(.route(room: r.code, name: String("To \(r.destination?.label ?? "destination")".prefix(40)), stops: stops))
            }
        }
    }

    /// At most `maxPoints` points of a line, evenly spaced along it, keeping the first and the last.
    static func simplify(_ line: [CLLocationCoordinate2D], maxPoints: Int) -> [CLLocationCoordinate2D] {
        if line.count <= maxPoints || maxPoints < 2 { return line }
        var out: [CLLocationCoordinate2D] = []
        let step: Double = Double(line.count - 1) / Double(maxPoints - 1)
        for i in 0..<maxPoints {
            out.append(line[Int((Double(i) * step).rounded())])
        }
        return out
    }

    func handOver(to id: String) {
        if let r = room { socket.send(.lead(room: r.code, to: id)) }
    }

    func removeMember(_ id: String) {
        if let r = room { socket.send(.remove(room: r.code, id: id)) }
    }

    /// Shares a straight route from me to the destination. The server needs a shared route before it will look for
    /// a rest stop; there is no road routing in the app yet.
    func shareRouteToDestination() {
        guard let r = room, let d = r.destination, let loc = location.latest else {
            lastError = "Need a destination and a GPS fix first"
            return
        }
        var stops: [[Double]] = [[loc.coordinate.longitude, loc.coordinate.latitude], [d.lng, d.lat]]
        if leaderRoute.count >= 2 {
            stops = ConvoyViewModel.simplify(leaderRoute, maxPoints: 25).map { (c: CLLocationCoordinate2D) -> [Double] in
                return [c.longitude, c.latitude]
            }
        }
        socket.send(.route(room: r.code, name: String("To \(d.label)".prefix(40)), stops: stops))
    }

    // MARK: Incoming

    private func handle(_ m: ServerMessage) {
        let before: String? = room?.code
        switch m {
        case .pong, .unknown:
            break
        case .hello(let me, let rooms):
            myId = me
            room = rooms.first
            livePositions = [:]
            statuses = [:]
            sosNotices = [:]
            seedRead()
            if let p = pendingJoin {
                pendingJoin = nil
                requestJoin(code: p)
            }
            updateLeaderRoute()
        case .room(let r):
            if room == nil || room?.code == r.code {
                let first: Bool = (room == nil)
                room = r
                if first { seedRead() }
                livePositions = [:]
                pruneStatuses()
                if first, let nav = pendingNav {
                    pendingNav = nil
                    if r.leader == myId { applyNavDestination(place: nav.place, line: nav.line) }
                }
                updateLeaderRoute()
            }
        case .left(let code, _):
            if room?.code == code {
                room = nil
                livePositions = [:]
                statuses = [:]
                sosNotices = [:]
                iAmStopping = false
                resetLeaderRoute()
            }
        case .pos(let u):
            if room?.code == u.room {
                livePositions[u.id] = MemberPosition(lat: u.lat, lng: u.lng, heading: u.heading, speed: u.speed, ts: u.ts)
            }
        case .error(let message, _):
            if message == "Unknown message", let probe = lastStatusSend, Date().timeIntervalSince(probe.at) < 6 {
                // An older server without convoy.status: use chat lines from now on.
                statusSupported = false
                lastStatusSend = nil
                sendStatusFallback(probe.kind)
            } else if message == "Unknown message", let sent = lastLeaderCommand, Date().timeIntervalSince(sent) < 6 {
                lastLeaderCommand = nil
                lastError = "This server cannot do that yet"
            } else {
                pendingNav = nil
                lastError = message
            }
        case .chat(let code, let message):
            if var r = room, r.code == code {
                if !r.messages.contains(where: { (x: ConvoyChatMessage) -> Bool in return x.id == message.id }) {
                    r.messages.append(message)
                }
                room = r
                if message.userId != myId {
                    if message.text == Self.stoppingText { applyStatus(userId: message.userId, kind: .stopping, note: nil) }
                    if message.text == Self.movingText { applyStatus(userId: message.userId, kind: .moving, note: nil) }
                }
            }
        case .hazard(let code, let hazard):
            onHazard?(code, hazard)
        case .status(let code, let id, let status, let note):
            let forThisRoom: Bool = (code == nil) || (code == room?.code)
            if forThisRoom, let kind = MemberStatusKind(rawValue: status) {
                applyStatus(userId: id, kind: kind, note: note)
            }
        }
        if room?.code != before { onRoomChange?() }
    }

    private func seedRead() {
        lastReadTs = room?.messages.last?.ts ?? 0
    }

    private func pruneStatuses() {
        guard let r = room else { return }
        let ids: Set<String> = Set(r.members.map { (x: Member) -> String in return x.id })
        statuses = statuses.filter { (entry: (key: String, value: MemberStatus)) -> Bool in return ids.contains(entry.key) }
        sosNotices = sosNotices.filter { (entry: (key: String, value: String)) -> Bool in return ids.contains(entry.key) }
    }

    private func applyStatus(userId: String, kind: MemberStatusKind, note: String?) {
        let name: String = (userId == myId) ? "You" : nameOf(userId: userId)
        switch kind {
        case .moving:
            statuses.removeValue(forKey: userId)
            sosNotices.removeValue(forKey: userId)
            if userId == myId { iAmStopping = false }
        case .stopping:
            statuses[userId] = MemberStatus(kind: .stopping, note: note, at: Date())
            sosNotices.removeValue(forKey: userId)
            if userId != myId { raiseStopNotice("\(name) is stopping") }
        case .sos:
            statuses[userId] = MemberStatus(kind: .sos, note: note, at: Date())
            if userId != myId {
                var text: String = "\(name) needs help"
                if let n = note, !n.isEmpty { text += " (\(n))" }
                sosNotices[userId] = text
            }
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

    // MARK: Outgoing position, about 1 Hz (also in the background while a convoy or ride keeps location alive)

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

    func statusKind(of member: Member) -> MemberStatusKind? {
        if let st = statuses[member.id] { return st.kind }
        if let raw = member.status, let k = MemberStatusKind(rawValue: raw), k != .moving { return k }
        return nil
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
        var out: [MapPin] = members.compactMap { (m: Member) -> MapPin? in
            guard m.id != myId, let p = m.position else { return nil }
            let kind: MapPin.Kind
            let sk: MemberStatusKind? = statusKind(of: m)
            if sk == .sos { kind = .sos }
            else if m.id == room?.leader { kind = .lead }
            else if sk == .stopping || isStopped(m) { kind = .stopped }
            else { kind = .member }
            return MapPin(id: m.id,
                          initials: Formatters.initials(Formatters.shownName(m.username)),
                          kind: kind,
                          coordinate: CLLocationCoordinate2D(latitude: p.lat, longitude: p.lng))
        }
        if let r = room, let d = r.destination {
            out.append(MapPin(id: "convoy-destination",
                              initials: "",
                              kind: r.resting ? .rest : .destination,
                              coordinate: CLLocationCoordinate2D(latitude: d.lat, longitude: d.lng),
                              symbol: r.resting ? "cup.and.saucer.fill" : "flag.checkered"))
        }
        return out
    }

    /// The route to draw: the server's road-following line when it has one, else the one I fetched as leader, else the
    /// shared stops (a straight line or a simplified road line).
    var mapRoute: [CLLocationCoordinate2D] {
        let server: [CLLocationCoordinate2D] = serverRouteLine
        if server.count >= 2 { return server }
        if room?.destination != nil && leaderRoute.count >= 2 { return leaderRoute }
        guard let stops = room?.route?.stops else { return [] }
        return stops.compactMap { (s: [Double]) -> CLLocationCoordinate2D? in
            if s.count < 2 { return nil }
            return CLLocationCoordinate2D(latitude: s[1], longitude: s[0])
        }
    }
}

enum Formatters {
    /// True for the handle the server invents for a new account (`rider_` and 8 hex digits), or a bare UUID.
    static func isGeneratedName(_ raw: String) -> Bool {
        let t: String = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if t.isEmpty { return true }
        if t.range(of: "^rider_[0-9a-fA-F]{8}$", options: .regularExpression) != nil { return true }
        if t.range(of: "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$", options: .regularExpression) != nil { return true }
        return false
    }

    /// A name fit to show in a roster or chat: a generated handle becomes "Rider 3FA2".
    static func shownName(_ raw: String) -> String {
        let t: String = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if t.isEmpty { return "Driver" }
        if isGeneratedName(t) {
            return "Rider " + String(t.suffix(4)).uppercased()
        }
        return t
    }

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
