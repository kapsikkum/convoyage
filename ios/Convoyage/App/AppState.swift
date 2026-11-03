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
    var tab: AppTab = .home
    var showRide: Bool = false
    /// The last `/v1/me` we fetched (nil until loaded or after sign-out).
    var me: MeDto?
    /// A deep-link sign-in in progress or failed: shown on Welcome.
    var linkStatus: String?
    var serverURL: String

    let userId: String
    let tokens: TokenStore
    let api: ApiClient
    let convoy: ConvoyViewModel
    let hazards: HazardStore
    let recorder: RideRecorder
    let garage: GarageStore
    let feed: FeedStore
    let photos: PhotoCapture

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
        api = client
        convoy = convoyModel
        hazards = HazardStore(api: client)
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
        convoyModel.onHazard = { [weak self] (room: String, hazard: HazardDto) in
            self?.hazards.onSocketHazard(room: room, hazard: hazard)
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
                name = nameOf(m)
            }
            finishSignIn(name: name, kind: .account)
            return .signedIn(isNewUser: res.isNewUser ?? false)
        }
    }

    /// The `convoyage://auth/magic?token=...` deep link.
    func handleURL(_ url: URL) {
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
        convoy.disconnect()
        hazards.clear()
        garage.clear()
        feed.clear()
        me = nil
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
