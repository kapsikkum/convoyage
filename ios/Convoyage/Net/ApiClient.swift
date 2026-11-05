import Foundation

/// Why a request failed. `status` is the HTTP status, or 0 when the server could not be reached at all.
struct ApiFailure: Error {
    var status: Int
    var message: String

    var isNetwork: Bool { return status == 0 }
    var isUnauthorized: Bool { return status == 401 }
}

enum ApiResult<T> {
    case ok(T)
    case failure(ApiFailure)

    var value: T? {
        if case .ok(let v) = self { return v }
        return nil
    }

    var failure: ApiFailure? {
        if case .failure(let f) = self { return f }
        return nil
    }
}

/// Exchanges the refresh token for a new pair, one caller at a time.
///
/// The server rotates refresh tokens and treats a second use of an old one as theft (it revokes the whole session
/// family). So two requests that both get a 401 must NOT both refresh: the first one rotates, the second finds
/// that the stored access token is no longer the one it sent and reuses the new one. Concurrent callers also share
/// the one in-flight refresh. Every rotation is saved to the Keychain before anyone sees the new access token.
actor TokenManager {
    private let store: TokenStore
    private var inflight: Task<String?, Never>?
    private var onSessionLost: (@Sendable () -> Void)?

    init(store: TokenStore) {
        self.store = store
    }

    func setOnSessionLost(_ handler: @escaping @Sendable () -> Void) {
        onSessionLost = handler
    }

    /// An access token that is not `stale`, refreshing if `stale` is still the current one. Nil if there is nothing
    /// to refresh with, the server refused (session lost) or it was unreachable.
    func refresh(stale: String?) async -> String? {
        guard let current = store.load() else { return nil }
        if let s = stale, current.access != s { return current.access }
        if let running = inflight { return await running.value }
        let work: Task<String?, Never> = Task { await self.performRefresh(current) }
        inflight = work
        return await work.value
    }

    private func performRefresh(_ current: Tokens) async -> String? {
        defer { inflight = nil }
        guard let url = ServerConfig.url(path: "/auth/refresh") else { return nil }
        var req: URLRequest = URLRequest(url: url)
        req.httpMethod = "POST"
        req.timeoutInterval = 20
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.setValue("application/json", forHTTPHeaderField: "Accept")
        req.httpBody = try? JSONSerialization.data(withJSONObject: ["refreshToken": current.refresh], options: [])
        do {
            let (data, resp) = try await URLSession.shared.data(for: req)
            let status: Int = (resp as? HTTPURLResponse)?.statusCode ?? 0
            if (200..<300).contains(status) {
                guard let pair = try? JSONDecoder().decode(TokenPairDto.self, from: data) else { return nil }
                // Always keep the NEW refresh token.
                store.save(Tokens(access: pair.accessToken, refresh: pair.refreshToken))
                return pair.accessToken
            }
            if status == 400 || status == 401 || status == 403 {
                store.save(nil)
                onSessionLost?()
                return nil
            }
            return nil // 429 / 5xx: try again later, keep the tokens
        } catch {
            return nil // offline: keep the tokens
        }
    }
}

/// The REST client for protocol/openapi.json. Authenticated calls send `Authorization: Bearer <access>`; a 401 triggers
/// one single-flight refresh and one retry of the request.
final class ApiClient: @unchecked Sendable {
    let tokens: TokenStore
    private let manager: TokenManager
    private let session: URLSession
    private let decoder: JSONDecoder = JSONDecoder()

    init(tokens: TokenStore) {
        self.tokens = tokens
        self.manager = TokenManager(store: tokens)
        let cfg: URLSessionConfiguration = URLSessionConfiguration.default
        cfg.timeoutIntervalForRequest = 20
        cfg.timeoutIntervalForResource = 120
        self.session = URLSession(configuration: cfg)
    }

    /// Called (on an arbitrary thread) when the server says the refresh token is no good. Tokens are already cleared.
    func setSessionLostHandler(_ handler: @escaping @Sendable () -> Void) {
        let m: TokenManager = manager
        Task { await m.setOnSessionLost(handler) }
    }

    // MARK: Socket support

    /// An access token good for the next minute, refreshing first if it is about to run out (or `force`: the server
    /// just refused the one we have). Nil when signed out or the refresh failed.
    func freshAccessToken(force: Bool) async -> String? {
        guard let current = tokens.load() else { return nil }
        if !force && !Jwt.expiresWithin(current.access, seconds: 60) { return current.access }
        if let fresh = await manager.refresh(stale: current.access) { return fresh }
        if !force && !Jwt.expiresWithin(current.access, seconds: 0) { return current.access }
        return nil
    }

    // MARK: Auth (no bearer)

    func requestMagicLink(email: String) async -> ApiResult<MagicLinkResponse> {
        return await send("POST", "/auth/magic-link", json: ["email": email.trimmingCharacters(in: .whitespaces)], auth: false, as: MagicLinkResponse.self)
    }

    func verifyMagicLink(token: String) async -> ApiResult<AuthResultDto> {
        return await send("POST", "/auth/magic-link/verify", json: ["token": token.trimmingCharacters(in: .whitespacesAndNewlines)], auth: false, as: AuthResultDto.self)
    }

    /// Revokes the session family of `refreshToken`. Idempotent on the server; failures are not interesting.
    func logout(refreshToken: String) async {
        _ = await send("POST", "/auth/logout", json: ["refreshToken": refreshToken], auth: false, as: Empty.self)
    }

    // MARK: Me

    func me() async -> ApiResult<MeDto> {
        return await send("GET", "/v1/me", as: MeDto.self)
    }

    func updateDisplayName(_ name: String) async -> ApiResult<MeDto> {
        return await send("PATCH", "/v1/me", json: ["displayName": name], as: MeDto.self)
    }

    /// `PATCH /v1/me` with a display name and (optionally) a handle. A taken handle answers 409.
    func updateProfile(displayName: String, handle: String?) async -> ApiResult<MeDto> {
        var body: [String: Any] = ["displayName": displayName]
        if let h = handle, !h.isEmpty { body["handle"] = h }
        return await send("PATCH", "/v1/me", json: body, as: MeDto.self)
    }

    // MARK: Hazards

    func createHazard(kind: String, subkind: String?, lat: Double, lng: Double, heading: Double?) async -> ApiResult<HazardDto> {
        var body: [String: Any] = ["kind": kind, "lat": lat, "lng": lng]
        if let s = subkind, !s.isEmpty { body["subkind"] = s }
        if let h = heading { body["heading"] = h }
        return await send("POST", "/v1/hazards", json: body, as: HazardDto.self)
    }

    func listHazards(bbox: String) async -> ApiResult<[HazardDto]> {
        return await send("GET", "/v1/hazards", query: [URLQueryItem(name: "bbox", value: bbox)], as: [HazardDto].self)
    }

    /// Votes carry no body and no content type: Fastify answers 400 for an empty body with application/json.
    func confirmHazard(id: String) async -> ApiResult<HazardDto> {
        return await send("POST", "/v1/hazards/\(id)/confirm", as: HazardDto.self)
    }

    func dismissHazard(id: String) async -> ApiResult<HazardDto> {
        return await send("POST", "/v1/hazards/\(id)/dismiss", as: HazardDto.self)
    }

    // MARK: Vehicles

    /// `GET /v1/catalogue`: the ids paint, finish, wheel and decal fields carry. Public.
    func catalogue() async -> ApiResult<CatalogueDto> {
        return await send("GET", "/v1/catalogue", auth: false, as: CatalogueDto.self)
    }

    func vehicles() async -> ApiResult<[VehicleDto]> {
        return await send("GET", "/v1/vehicles", as: [VehicleDto].self)
    }

    func createVehicle(_ body: [String: Any]) async -> ApiResult<VehicleDto> {
        return await send("POST", "/v1/vehicles", json: body, as: VehicleDto.self)
    }

    func updateVehicle(id: String, _ body: [String: Any]) async -> ApiResult<VehicleDto> {
        return await send("PATCH", "/v1/vehicles/\(id)", json: body, as: VehicleDto.self)
    }

    func deleteVehicle(id: String) async -> ApiResult<Empty> {
        return await send("DELETE", "/v1/vehicles/\(id)", as: Empty.self)
    }

    func makePrimary(id: String) async -> ApiResult<VehicleDto> {
        return await send("POST", "/v1/vehicles/\(id)/primary", as: VehicleDto.self)
    }

    func markSold(id: String) async -> ApiResult<VehicleDto> {
        return await send("POST", "/v1/vehicles/\(id)/sold", json: [:], as: VehicleDto.self)
    }

    // MARK: Rides

    func startRide(_ body: [String: Any]) async -> ApiResult<RideDto> {
        return await send("POST", "/v1/rides", json: body, as: RideDto.self)
    }

    func finishRide(id: String, _ body: [String: Any]) async -> ApiResult<RideDto> {
        return await send("POST", "/v1/rides/\(id)/finish", json: body, as: RideDto.self)
    }

    func ride(id: String) async -> ApiResult<RideDto> {
        return await send("GET", "/v1/rides/\(id)", as: RideDto.self)
    }

    func updateRide(id: String, _ body: [String: Any]) async -> ApiResult<RideDto> {
        return await send("PATCH", "/v1/rides/\(id)", json: body, as: RideDto.self)
    }

    func attachPhoto(rideId: String, mediaId: String, lat: Double?, lng: Double?, takenAt: String?) async -> ApiResult<RidePhotoDto> {
        var body: [String: Any] = ["mediaId": mediaId]
        if let la = lat, let ln = lng {
            body["lat"] = la
            body["lng"] = ln
        }
        if let t = takenAt { body["takenAt"] = t }
        return await send("POST", "/v1/rides/\(rideId)/photos", json: body, as: RidePhotoDto.self)
    }

    // MARK: Media

    /// `POST /v1/media`, multipart field "file". JPEG, PNG or WebP up to 15 MB (HEIC is refused by the server).
    func uploadImage(_ jpeg: Data) async -> ApiResult<MediaDto> {
        let boundary: String = "convoyage-" + UUID().uuidString
        var body: Data = Data()
        body.append(Data("--\(boundary)\r\n".utf8))
        body.append(Data("Content-Disposition: form-data; name=\"file\"; filename=\"photo.jpg\"\r\n".utf8))
        body.append(Data("Content-Type: image/jpeg\r\n\r\n".utf8))
        body.append(jpeg)
        body.append(Data("\r\n--\(boundary)--\r\n".utf8))
        return await sendData("POST", "/v1/media", body: body, contentType: "multipart/form-data; boundary=\(boundary)", as: MediaDto.self)
    }

    /// Authenticated GET of raw bytes (media are private).
    func fetchBytes(path: String) async -> Data? {
        let r: ApiResult<Data> = await raw("GET", path, query: [], body: nil, contentType: nil, auth: true, accept: "*/*")
        return r.value
    }

    // MARK: Posts and feed

    func feed(scope: String, before: String?, lat: Double?, lng: Double?) async -> ApiResult<FeedPageDto> {
        var q: [URLQueryItem] = [URLQueryItem(name: "scope", value: scope), URLQueryItem(name: "limit", value: "20")]
        if let b = before { q.append(URLQueryItem(name: "before", value: b)) }
        if scope == "nearby", let la = lat, let ln = lng {
            q.append(URLQueryItem(name: "lat", value: String(la)))
            q.append(URLQueryItem(name: "lng", value: String(ln)))
            q.append(URLQueryItem(name: "radiusKm", value: "100"))
        }
        return await send("GET", "/v1/feed", query: q, as: FeedPageDto.self)
    }

    func createPost(_ body: [String: Any]) async -> ApiResult<PostDto> {
        return await send("POST", "/v1/posts", json: body, as: PostDto.self)
    }

    func deletePost(id: String) async -> ApiResult<Empty> {
        return await send("DELETE", "/v1/posts/\(id)", as: Empty.self)
    }

    func like(postId: String, on: Bool) async -> ApiResult<LikeStateDto> {
        return await send(on ? "POST" : "DELETE", "/v1/posts/\(postId)/like", as: LikeStateDto.self)
    }

    func comments(postId: String, before: String?) async -> ApiResult<CommentPageDto> {
        var q: [URLQueryItem] = [URLQueryItem(name: "limit", value: "50")]
        if let b = before { q.append(URLQueryItem(name: "before", value: b)) }
        return await send("GET", "/v1/posts/\(postId)/comments", query: q, as: CommentPageDto.self)
    }

    func addComment(postId: String, body: String) async -> ApiResult<CommentDto> {
        return await send("POST", "/v1/posts/\(postId)/comments", json: ["body": body], as: CommentDto.self)
    }

    func deleteComment(id: String) async -> ApiResult<Empty> {
        return await send("DELETE", "/v1/comments/\(id)", as: Empty.self)
    }

    func report(targetType: String, targetId: String, reason: String, detail: String?) async -> ApiResult<Empty> {
        var body: [String: Any] = ["targetType": targetType, "targetId": targetId, "reason": reason]
        if let d = detail, !d.isEmpty { body["detail"] = d }
        return await send("POST", "/v1/reports", json: body, as: Empty.self)
    }

    // MARK: Live share link

    func liveLink(code: String) async -> ApiResult<LiveLinkDto> {
        let c: String = code.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? code
        return await send("POST", "/v1/convoys/\(c)/live", as: LiveLinkDto.self)
    }

    // MARK: PhotoScout

    func photoscoutStatus() async -> ApiResult<PhotoScoutStatusDto> {
        return await send("GET", "/v1/photoscout/status", as: PhotoScoutStatusDto.self)
    }

    /// The PhotoScout consent page to open in the browser; it comes back to `convoyage://photoscout/linked`.
    func photoscoutConnect() async -> ApiResult<PhotoScoutAuthorizeDto> {
        return await send("GET", "/v1/photoscout/connect", as: PhotoScoutAuthorizeDto.self)
    }

    func photoscoutUnlink() async -> ApiResult<OkDto> {
        return await send("DELETE", "/v1/photoscout/link", as: OkDto.self)
    }

    func photoscoutSpots(box: GeoBox) async -> ApiResult<[PsSpot]> {
        return await send("GET", "/v1/photoscout/spots", query: [URLQueryItem(name: "bbox", value: box.param())], as: [PsSpot].self)
    }

    /// Upcoming events within `radiusKm` of a point, soonest first.
    func photoscoutEvents(lat: Double, lng: Double, radiusKm: Double) async -> ApiResult<[PsEvent]> {
        let q: [URLQueryItem] = [
            URLQueryItem(name: "near", value: String(format: "%.5f,%.5f", lat, lng)),
            URLQueryItem(name: "radiusKm", value: String(format: "%.0f", radiusKm))
        ]
        return await send("GET", "/v1/photoscout/events", query: q, as: [PsEvent].self)
    }

    /// `status` "going" or "interested" (community events: going only); nil takes the RSVP back.
    func photoscoutRsvp(eventId: String, status: String?) async -> ApiResult<PsRsvpDto> {
        var body: [String: Any] = ["going": false]
        if let st = status { body = ["status": st] }
        let id: String = eventId.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? eventId
        return await send("POST", "/v1/photoscout/events/\(id)/rsvp", json: body, as: PsRsvpDto.self)
    }

    // MARK: Conditions around the map

    /// `GET /v1/conditions/area`: police, cameras, hazards and traffic in a box (up to 0.3 degrees a side).
    func areaConditions(box: GeoBox) async -> ApiResult<AreaConditionsDto> {
        return await send("GET", "/v1/conditions/area", query: [URLQueryItem(name: "bbox", value: box.param())], as: AreaConditionsDto.self)
    }

    /// `GET /v1/roads`: OpenStreetMap roads in a box (up to 0.15 degrees a side) with their signed limits.
    func roads(box: GeoBox) async -> ApiResult<RoadsDto> {
        return await send("GET", "/v1/roads", query: [URLQueryItem(name: "bbox", value: box.param())], as: RoadsDto.self)
    }

    // MARK: Routing and voice

    /// `GET /v1/route`. Points are `lng,lat`; `profile` is "car" or "motorcycle".
    func route(fromLng: Double, fromLat: Double, toLng: Double, toLat: Double, profile: String) async -> ApiResult<RouteDto> {
        let q: [URLQueryItem] = [
            URLQueryItem(name: "from", value: String(format: "%.6f,%.6f", fromLng, fromLat)),
            URLQueryItem(name: "to", value: String(format: "%.6f,%.6f", toLng, toLat)),
            URLQueryItem(name: "profile", value: profile)
        ]
        return await send("GET", "/v1/route", query: q, as: RouteDto.self)
    }

    /// `POST /v1/voice/token`: a LiveKit token for the push-to-talk room of `convoyCode`.
    func voiceToken(convoyCode: String) async -> ApiResult<VoiceTokenDto> {
        return await send("POST", "/v1/voice/token", json: ["convoyCode": convoyCode], as: VoiceTokenDto.self)
    }

    // MARK: Plumbing

    private func send<T: Decodable>(_ method: String, _ path: String, query: [URLQueryItem] = [], json: [String: Any]? = nil, auth: Bool = true, as type: T.Type) async -> ApiResult<T> {
        var data: Data? = nil
        if let j = json {
            data = try? JSONSerialization.data(withJSONObject: j, options: [])
        }
        return await sendData(method, path, query: query, body: data, contentType: data == nil ? nil : "application/json", auth: auth, as: type)
    }

    private func sendData<T: Decodable>(_ method: String, _ path: String, query: [URLQueryItem] = [], body: Data?, contentType: String?, auth: Bool = true, as type: T.Type) async -> ApiResult<T> {
        let r: ApiResult<Data> = await raw(method, path, query: query, body: body, contentType: contentType, auth: auth, accept: "application/json")
        switch r {
        case .failure(let f):
            return .failure(f)
        case .ok(let data):
            do {
                return .ok(try decoder.decode(T.self, from: data))
            } catch {
                return .failure(ApiFailure(status: 502, message: "Unexpected response from the server"))
            }
        }
    }

    /// One request, with one refresh-and-retry on a 401. A bodiless request carries no body and no content type.
    private func raw(_ method: String, _ path: String, query: [URLQueryItem], body: Data?, contentType: String?, auth: Bool, accept: String) async -> ApiResult<Data> {
        guard let url = ServerConfig.url(path: path, query: query) else {
            return .failure(ApiFailure(status: 0, message: "Server URL is not valid"))
        }
        var attempt: Int = 0
        while true {
            var req: URLRequest = URLRequest(url: url)
            req.httpMethod = method
            req.setValue(accept, forHTTPHeaderField: "Accept")
            if let b = body {
                req.httpBody = b
                req.setValue(contentType ?? "application/json", forHTTPHeaderField: "Content-Type")
            }
            var sent: String? = nil
            if auth {
                guard let t = tokens.load() else {
                    return .failure(ApiFailure(status: 401, message: "Not signed in"))
                }
                sent = t.access
                req.setValue("Bearer \(t.access)", forHTTPHeaderField: "Authorization")
            }
            do {
                let (data, resp) = try await session.data(for: req)
                let status: Int = (resp as? HTTPURLResponse)?.statusCode ?? 0
                if status == 401 && auth && attempt == 0 {
                    attempt = 1
                    if await manager.refresh(stale: sent) != nil { continue }
                    return .failure(ApiFailure(status: 401, message: ApiClient.errorMessage(data, status: 401)))
                }
                if (200..<300).contains(status) { return .ok(data) }
                return .failure(ApiFailure(status: status, message: ApiClient.errorMessage(data, status: status)))
            } catch {
                return .failure(ApiFailure(status: 0, message: "Cannot reach the server (\(error.localizedDescription))"))
            }
        }
    }

    /// `message` from the standard error body, else a generic line for the status.
    static func errorMessage(_ data: Data, status: Int) -> String {
        if let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
           let m = obj["message"] as? String, !m.isEmpty {
            return m
        }
        return "Server answered \(status)"
    }
}
