import Foundation
import Observation

/// How this device is signed in: a real account (JWT in the Keychain) or the DEBUG-only dev socket auth.
enum AuthKind: String {
    case account
    case dev
}

/// What happened to a magic-link request.
enum MagicLinkOutcome {
    /// The server accepted the email. `devToken` is set only when it runs with MAIL_DEV=1 (verify straight away).
    case sent(devToken: String?)
    case failed(String)
}

enum SignInOutcome {
    case signedIn(isNewUser: Bool)
    case failed(String)
}

/// Pulls the one-time token out of a magic link (`convoyage://auth/magic?token=...`) or accepts a bare token.
func extractMagicToken(_ input: String) -> String? {
    let text: String = input.trimmingCharacters(in: .whitespacesAndNewlines)
    if text.isEmpty { return nil }
    if let regex = try? NSRegularExpression(pattern: "[?&#]token=([^&#\\s]+)", options: []) {
        let range: NSRange = NSRange(text.startIndex..<text.endIndex, in: text)
        if let match = regex.firstMatch(in: text, options: [], range: range), match.numberOfRanges > 1,
           let r = Range(match.range(at: 1), in: text) {
            let raw: String = String(text[r])
            return raw.removingPercentEncoding ?? raw
        }
    }
    // The verify endpoint wants up to 200 chars; anything short, with spaces or a scheme cannot be a token.
    let hasSpace: Bool = text.rangeOfCharacter(from: .whitespacesAndNewlines) != nil
    if text.count >= 20 && text.count <= 200 && !hasSpace && !text.contains("://") { return text }
    return nil
}

@Observable
@MainActor
final class AppState {
    var displayName: String
    var isSignedIn: Bool
    var authKind: AuthKind?
    /// Drive (the map) is the centre tab and where the app opens.
    var tab: AppTab = .convoy
    var showRide: Bool = false
    /// The navigation screen is up (navigation itself can keep running with it hidden).
    var showNav: Bool = false
    /// The last `/v1/me` we fetched (nil until loaded or after sign-out).
    var me: MeDto?
    /// A deep-link sign-in in progress or failed: shown on Welcome.
    var linkStatus: String?
    var serverURL: String
    /// Show the "keep sharing with the screen off" explanation before asking for Always location.
    var showAlwaysExplainer: Bool = false
    /// The person skipped the "what should the convoy call you" screen (per account).
    var onboardingSkipped: Bool = false
    /// Police, cameras, crashes, closures, roadworks and jams on the Drive map (on by default).
    var layerTraffic: Bool = UserDefaults.standard.object(forKey: "layerTraffic") as? Bool ?? true {
        didSet { UserDefaults.standard.set(layerTraffic, forKey: "layerTraffic") }
    }

    let userId: String
    let tokens: TokenStore
    let api: ApiClient
    let convoy: ConvoyViewModel
    let hazards: HazardStore
    let nav: NavigationSession
    let voice: VoiceChannel
    let recorder: RideRecorder
    let garage: GarageStore
    let feed: FeedStore
    let photos: PhotoCapture
    let crash: CrashMonitor
    /// Police and traffic around the map view (TfNSW, Waze).
    let area: AreaConditionsStore
    /// The signed limit of the road you are on.
    let speedLimits: SpeedLimits

    @ObservationIgnored private let defaults: UserDefaults = UserDefaults.standard

    init() {
        let store: UserDefaults = UserDefaults.standard
        if let existing = store.string(forKey: "userId") {
            userId = existing
        } else {
            let fresh: String = UUID().uuidString.lowercased()
            store.set(fresh, forKey: "userId")
            userId = fresh
        }

        let tokenStore: TokenStore = TokenStore()
        let client: ApiClient = ApiClient(tokens: tokenStore)
        let convoyModel: ConvoyViewModel = ConvoyViewModel()
        let feedStore: FeedStore = FeedStore(api: client, location: convoyModel.location)
        let recorderModel: RideRecorder = RideRecorder(api: client, location: convoyModel.location)
        tokens = tokenStore
        crash = CrashMonitor(location: convoyModel.location)
        api = client
        convoy = convoyModel
        let hazardStore: HazardStore = HazardStore(api: client)
        hazards = hazardStore
        area = AreaConditionsStore(api: client)
        speedLimits = SpeedLimits(api: client)
        nav = NavigationSession(api: client, location: convoyModel.location, hazards: hazardStore)
        voice = VoiceChannel(api: client)
        recorder = recorderModel
        garage = GarageStore(api: client)
        feed = feedStore
        photos = PhotoCapture(api: client, recorder: recorderModel, location: convoyModel.location, feed: feedStore)
        serverURL = ServerConfig.urlString

        // Work out who is signed in. A dev session only survives in DEBUG builds.
        var kind: AuthKind? = AuthKind(rawValue: store.string(forKey: "authKind") ?? "")
        var name: String = store.string(forKey: "displayName") ?? ""
        if kind == .account && tokenStore.load() == nil { kind = nil }
        #if !DEBUG
        if kind == .dev { kind = nil }
        #endif
        if kind == nil { name = "" }
        authKind = kind
        displayName = name
        isSignedIn = (kind != nil) && !name.isEmpty

        convoyModel.displayName = name
        convoyModel.tokenProvider = { [client] (force: Bool) async -> String? in
            return await client.freshAccessToken(force: force)
        }
        convoyModel.api = client
        convoyModel.profileProvider = { [weak self] () -> String in
            return self?.routeProfile ?? "car"
        }
        nav.profileProvider = { [weak self] () -> String in
            return self?.routeProfile ?? "car"
        }
        nav.onActiveChange = { [weak self] in
            self?.updateServices()
        }
        convoyModel.onHazard = { [weak self] (room: String, hazard: HazardDto) in
            self?.hazards.onSocketHazard(room: room, hazard: hazard)
        }
        convoyModel.onRoomChange = { [weak self] in
            self?.updateServices()
        }
        recorderModel.onPhaseChange = { [weak self] in
            self?.updateServices()
        }
        crash.onExpire = { [weak self] () -> Bool in
            return self?.convoy.sendSOS() ?? false
        }
        client.setSessionLostHandler { [weak self] in
            Task { @MainActor in
                self?.sessionLost()
            }
        }
    }

    // MARK: Sign-in

    func requestMagicLink(email: String) async -> MagicLinkOutcome {
        let r: ApiResult<MagicLinkResponse> = await api.requestMagicLink(email: email)
        switch r {
        case .ok(let res):
            let token: String? = res.devLink.flatMap { (link: String) -> String? in return extractMagicToken(link) }
            return .sent(devToken: token)
        case .failure(let f):
            if f.status == 429 { return .failed("Too many tries. Wait a minute and try again.") }
            return .failed(f.message)
        }
    }

    /// Verifies a magic-link token (or a pasted link), stores the session and loads the profile.
    func verify(_ tokenOrLink: String) async -> SignInOutcome {
        guard let token = extractMagicToken(tokenOrLink) else {
            return .failed("That does not look like a sign-in link")
        }
        let r: ApiResult<AuthResultDto> = await api.verifyMagicLink(token: token)
        switch r {
        case .failure(let f):
            if f.status == 401 || f.status == 400 {
                return .failed("That sign-in link has expired or was already used. Request a new one.")
            }
            return .failed(f.message)
        case .ok(let res):
            tokens.save(Tokens(access: res.accessToken, refresh: res.refreshToken))
            let meResult: ApiResult<MeDto> = await api.me()
            var name: String = "Driver"
            if case .ok(let m) = meResult {
                me = m
                onboardingSkipped = defaults.bool(forKey: skipKey)
                name = nameOf(m)
            }
            finishSignIn(name: name, kind: .account)
            return .signedIn(isNewUser: res.isNewUser ?? false)
        }
    }

    /// The invite code in `convoyage://join/<CODE>` or `https://convoyage.app/join/<CODE>`.
    static func joinCode(from url: URL) -> String? {
        let parts: [String] = url.pathComponents.filter { (c: String) -> Bool in return c != "/" }
        if url.scheme == "convoyage", url.host == "join" {
            return parts.first
        }
        if url.scheme == "https" || url.scheme == "http" {
            if let host = url.host, host == "convoyage.app" || host.hasSuffix(".convoyage.app") {
                if parts.count >= 2 && parts[0] == "join" { return parts[1] }
            }
        }
        return nil
    }

    /// Opens the convoy tab and joins; the join waits for sign-in and for the socket if it has to.
    private func handleJoinLink(_ code: String) {
        convoy.requestJoin(code: code)
        if isSignedIn {
            tab = .convoy
        } else {
            linkStatus = "Sign in to join the convoy."
        }
    }

    /// The `convoyage://auth/magic?token=...` and `convoyage://join/<CODE>` deep links.
    func handleURL(_ url: URL) {
        if let code = AppState.joinCode(from: url) {
            handleJoinLink(code)
            return
        }
        guard url.scheme == "convoyage", url.host == "auth" else { return }
        guard let token = extractMagicToken(url.absoluteString) else {
            linkStatus = "That sign-in link is not valid."
            return
        }
        linkStatus = "Signing in..."
        Task {
            let outcome: SignInOutcome = await verify(token)
            switch outcome {
            case .signedIn: linkStatus = nil
            case .failed(let m): linkStatus = m
            }
        }
    }

    #if DEBUG
    /// Debug-only dev sign-in: the server's `?dev_user=<id>&name=<name>` WebSocket auth. No JWT, so no REST.
    func signInDev(name: String) {
        let trimmed: String = name.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty { return }
        finishSignIn(name: trimmed, kind: .dev)
    }
    #endif

    private func finishSignIn(name: String, kind: AuthKind) {
        displayName = name
        authKind = kind
        defaults.set(name, forKey: "displayName")
        defaults.set(kind.rawValue, forKey: "authKind")
        isSignedIn = true
        linkStatus = nil
        connectSocket()
        recorder.restoreIfAny()
        Task { await garage.load() }
    }

    private func nameOf(_ m: MeDto) -> String {
        let dn: String = m.displayName ?? ""
        if !dn.isEmpty { return dn }
        let h: String = m.handle ?? ""
        return h.isEmpty ? "Driver" : h
    }

    private func connectSocket() {
        guard let kind = authKind else { return }
        convoy.displayName = displayName
        switch kind {
        case .account:
            if tokens.load() != nil { convoy.connect(auth: .account, myId: userId) }
        case .dev:
            convoy.connect(auth: .dev(userId: userId, name: displayName), myId: userId)
        }
    }

    /// App start: bring the socket and the profile up for a stored session.
    func resume() {
        guard isSignedIn else { return }
        connectSocket()
        recorder.restoreIfAny()
        Task {
            await loadMe()
            await garage.load()
        }
    }

    // MARK: Profile

    /// Loads `/v1/me`. Keeps the old value on failure. Returns an error message, or nil.
    @discardableResult
    func loadMe() async -> String? {
        guard authKind == .account else { return nil }
        let r: ApiResult<MeDto> = await api.me()
        switch r {
        case .ok(let m):
            me = m
            onboardingSkipped = defaults.bool(forKey: skipKey)
            let n: String = nameOf(m)
            displayName = n
            convoy.displayName = n
            defaults.set(n, forKey: "displayName")
            return nil
        case .failure(let f):
            return f.isUnauthorized ? nil : f.message // 401 means signed out; sessionLost handles it
        }
    }

    func updateDisplayName(_ name: String) async -> String? {
        let clean: String = name.trimmingCharacters(in: .whitespacesAndNewlines)
        if clean.isEmpty { return "Enter a name" }
        let r: ApiResult<MeDto> = await api.updateDisplayName(String(clean.prefix(60)))
        switch r {
        case .ok(let m):
            me = m
            let n: String = nameOf(m)
            displayName = n
            convoy.displayName = n
            defaults.set(n, forKey: "displayName")
            return nil
        case .failure(let f):
            return f.message
        }
    }

    // MARK: Onboarding

    private var skipKey: String { return "onboardingSkipped." + (me?.id ?? userId) }

    /// True when the account has no real display name yet (unset, or the generated `rider_xxxxxxxx`).
    var needsOnboarding: Bool {
        guard isSignedIn, authKind == .account, let m = me else { return false }
        if onboardingSkipped { return false }
        return Formatters.isGeneratedName(m.displayName ?? "")
    }

    func skipOnboarding() {
        onboardingSkipped = true
        defaults.set(true, forKey: skipKey)
    }

    /// The saved drive / ride / both choice.
    var rideMode: String {
        return defaults.string(forKey: "rideMode") ?? "both"
    }

    /// The routing profile for the saved drive / ride choice: two wheels get the motorcycle profile.
    var routeProfile: String {
        return rideMode == "ride" ? "motorcycle" : "car"
    }

    /// PATCH /v1/me with the chosen name (and handle). Returns an error message, or nil.
    func completeOnboarding(name: String, handle: String, mode: String) async -> String? {
        let clean: String = name.trimmingCharacters(in: .whitespacesAndNewlines)
        if clean.isEmpty { return "Enter a name" }
        let h: String = handle.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        if !h.isEmpty && h.range(of: "^[a-z0-9_]{3,30}$", options: .regularExpression) == nil {
            return "Handles are 3 to 30 characters: letters, digits and underscores"
        }
        let r: ApiResult<MeDto> = await api.updateProfile(displayName: String(clean.prefix(60)), handle: h.isEmpty ? nil : h)
        switch r {
        case .ok(let m):
            me = m
            let n: String = nameOf(m)
            displayName = n
            convoy.displayName = n
            defaults.set(n, forKey: "displayName")
            defaults.set(mode, forKey: "rideMode")
            return nil
        case .failure(let f):
            if f.status == 409 { return "That handle is taken" }
            return f.message
        }
    }

    // MARK: Background services

    /// Background location while a convoy or a recording needs it; crash detection while Ride mode is up.
    func updateServices() {
        let needBackground: Bool = convoy.room != nil || recorder.isActive || nav.isActive
        convoy.location.setBackground(needBackground)
        if needBackground { maybeAskAlways() }
        crash.update(shouldRun: recorder.isActive || showRide)
        if let code = convoy.room?.code, authKind == .account {
            voice.join(code: code)
        } else {
            voice.leave()
        }
    }

    private func maybeAskAlways() {
        guard convoy.location.authorization == .authorizedWhenInUse else { return }
        if defaults.bool(forKey: "alwaysExplained") { return }
        showAlwaysExplainer = true
    }

    /// The answer to the explanation screen. "Allow" asks iOS for Always.
    func answerAlways(allow: Bool) {
        defaults.set(true, forKey: "alwaysExplained")
        showAlwaysExplainer = false
        if allow { convoy.location.requestAlways() }
    }

    // MARK: Sign-out

    /// Signs out on this device at once and tells the server in the background (which revokes the session family).
    func signOut() {
        let refresh: String? = tokens.load()?.refresh
        tokens.save(nil)
        if let rt = refresh {
            let client: ApiClient = api
            Task { await client.logout(refreshToken: rt) }
        }
        clearSession()
    }

    /// The refresh token was refused: the session is gone. Tokens are already cleared.
    func sessionLost() {
        if isSignedIn { clearSession() }
    }

    private func clearSession() {
        nav.end()
        showNav = false
        convoy.disconnect()
        hazards.clear()
        area.clear()
        speedLimits.clear()
        garage.clear()
        feed.clear()
        me = nil
        onboardingSkipped = false
        defaults.removeObject(forKey: "displayName")
        defaults.removeObject(forKey: "authKind")
        displayName = ""
        authKind = nil
        isSignedIn = false
        tab = .home
        showRide = false
    }

    // MARK: Settings

    /// Saves a new server URL and reconnects the socket to it.
    func setServerURL(_ raw: String) {
        serverURL = ServerConfig.save(raw)
        if isSignedIn {
            convoy.disconnect()
            connectSocket()
        }
    }
}
