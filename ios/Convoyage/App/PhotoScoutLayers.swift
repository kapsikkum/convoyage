import Foundation
import Observation

// MARK: DTOs (GET /v1/photoscout/...)

/// `configured` false: this server has no PhotoScout (every other PhotoScout call answers 503).
struct PhotoScoutStatusDto: Decodable {
    var configured: Bool
    var linked: Bool
    var username: String?
}

struct PhotoScoutAuthorizeDto: Decodable {
    var authorizeUrl: String
}

struct PsGoodTimes: Decodable, Equatable {
    var phases: [String]?
    var months: [Int]?
}

struct PsPhoto: Decodable, Equatable {
    var id: String
    var thumbUrl: String
    var url: String
}

/// A photo spot. Photo URLs are this server's and need the bearer token.
struct PsSpot: Decodable, Identifiable, Equatable {
    var id: String
    var name: String
    var notes: String?
    var lat: Double
    var lng: Double
    var tags: [String]?
    var goodTimes: PsGoodTimes?
    var photoCount: Int?
    var photo: PsPhoto?
}

struct PsMeet: Decodable, Equatable {
    var lat: Double
    var lng: Double
    var note: String?
}

/// A community event's cruise: its convoy opens with the socket's `convoy.cruise`.
struct PsCruise: Decodable, Equatable {
    var routeName: String?
    var meet: PsMeet
    var rollOut: String?
}

/// A community or crawled event.
struct PsEvent: Decodable, Identifiable, Equatable {
    var id: String
    var source: String?
    var title: String
    var description: String?
    var startTime: String
    var endTime: String?
    var venue: String?
    var address: String?
    var lat: Double?
    var lng: Double?
    var url: String?
    var going: Int?
    var interested: Int?
    var myRsvp: String?
    var cruise: PsCruise?
    var distanceKm: Double?

    var isCommunity: Bool { return (source ?? "community") == "community" }
}

struct PsRsvpDto: Decodable {
    var myRsvp: String?
    var going: Int?
    var interested: Int?
}

struct OkDto: Decodable {
    var ok: Bool?
}

// MARK: Store

/// PhotoScout spots and events for the Drive map, once the account is linked. Spots load for a box around the
/// view, events within a radius of its centre; both are kept for five minutes and as long as the view stays inside.
@Observable
@MainActor
final class PhotoScoutLayers {
    /// This server has PhotoScout: the layers are offered (they ask to link first).
    var configured: Bool = false
    var linked: Bool = false
    var username: String?
    var spots: [PsSpot] = []
    var events: [PsEvent] = []
    var error: String?

    var available: Bool { return configured && linked }

    @ObservationIgnored private let api: ApiClient
    @ObservationIgnored private var spotsBox: GeoBox?
    @ObservationIgnored private var spotsAt: Date = .distantPast
    @ObservationIgnored private var eventsCentre: (Double, Double)?
    @ObservationIgnored private var eventsRadiusKm: Double = 0
    @ObservationIgnored private var eventsAt: Date = .distantPast
    @ObservationIgnored private var spotsTask: Task<Void, Never>?
    @ObservationIgnored private var eventsTask: Task<Void, Never>?

    private static let maxAge: TimeInterval = 300

    init(api: ApiClient) {
        self.api = api
    }

    func refreshStatus() async {
        guard case .ok(let s) = await api.photoscoutStatus() else { return }
        configured = s.configured
        linked = s.configured && s.linked
        username = linked ? s.username : nil
        if linked {
            spotsAt = .distantPast
            eventsAt = .distantPast
        } else {
            spots = []
            events = []
        }
    }

    func clear() {
        spotsTask?.cancel()
        eventsTask?.cancel()
        spotsBox = nil
        eventsCentre = nil
        configured = false
        linked = false
        username = nil
        spots = []
        events = []
        error = nil
    }

    /// The map came to rest on `view`; load whichever layers are on.
    func onViewport(_ view: GeoBox, spots wantSpots: Bool, events wantEvents: Bool) {
        guard available else { return }
        let now: Date = Date()
        if wantSpots {
            let fresh: Bool = spotsBox.map { $0.covers(view) } ?? false && now.timeIntervalSince(spotsAt) < PhotoScoutLayers.maxAge
            if !fresh {
                // A margin around the view, so small pans need nothing new; at least ~30 km at street level.
                let box: GeoBox = view.grown(0.5).atLeast(0.3).clamped(maxDegrees: 2.0)
                spotsTask?.cancel()
                spotsTask = Task { @MainActor [weak self] in
                    guard let self = self else { return }
                    switch await self.api.photoscoutSpots(box: box) {
                    case .ok(let list):
                        self.spotsBox = box
                        self.spotsAt = Date()
                        self.spots = list
                        self.error = nil
                    case .failure(let f):
                        if !Task.isCancelled { self.error = f.message }
                    }
                }
            }
        }
        if wantEvents {
            let cLat: Double = (view.south + view.north) / 2
            let cLng: Double = (view.west + view.east) / 2
            let half: Double = view.halfDiagonalKm()
            let needKm: Double = max(50, half * 1.5)
            var stale: Bool = true
            if let c = eventsCentre {
                let moved: Double = GeoMath.distanceM(c.0, c.1, cLat, cLng) / 1000
                stale = now.timeIntervalSince(eventsAt) > PhotoScoutLayers.maxAge || needKm > eventsRadiusKm || moved > eventsRadiusKm - half
            }
            if stale {
                let radius: Double = min(needKm * 2, 500)
                eventsTask?.cancel()
                eventsTask = Task { @MainActor [weak self] in
                    guard let self = self else { return }
                    switch await self.api.photoscoutEvents(lat: cLat, lng: cLng, radiusKm: radius) {
                    case .ok(let list):
                        self.eventsCentre = (cLat, cLng)
                        self.eventsRadiusKm = radius
                        self.eventsAt = Date()
                        self.events = list.filter { (e: PsEvent) -> Bool in return e.lat != nil && e.lng != nil }
                        self.error = nil
                    case .failure(let f):
                        if !Task.isCancelled { self.error = f.message }
                    }
                }
            }
        }
    }

    /// Upcoming events within 150 km for the Home tab (soonest first, at most five).
    var homeEvents: [PsEvent] = []

    func loadHomeEvents(lat: Double, lng: Double) async {
        guard available else { homeEvents = []; return }
        guard case .ok(let list) = await api.photoscoutEvents(lat: lat, lng: lng, radiusKm: 150) else { return }
        let now: Date = Date().addingTimeInterval(-3600)
        let upcoming: [PsEvent] = list.filter { (e: PsEvent) -> Bool in
            guard let d = ConditionSheet.parse(e.startTime) else { return false }
            return d >= now
        }
        let sorted: [PsEvent] = upcoming.sorted { (a: PsEvent, b: PsEvent) -> Bool in return a.startTime < b.startTime }
        homeEvents = Array(sorted.prefix(5))
    }

    /// The consent page to open in the browser, or an error to show.
    func connectURL() async -> Result<URL, ApiFailure> {
        switch await api.photoscoutConnect() {
        case .ok(let d):
            guard let u = URL(string: d.authorizeUrl) else { return .failure(ApiFailure(status: 0, message: "Bad link from the server")) }
            return .success(u)
        case .failure(let f):
            return .failure(f)
        }
    }

    /// Nil when unlinked, else the error.
    func unlink() async -> String? {
        switch await api.photoscoutUnlink() {
        case .ok:
            let wasConfigured: Bool = configured
            clear()
            configured = wasConfigured
            return nil
        case .failure(let f):
            return f.message
        }
    }

    /// Going / interested / take it back (`status` nil). The list updates with the server's counts.
    func rsvp(_ event: PsEvent, status: String?) async -> String? {
        switch await api.photoscoutRsvp(eventId: event.id, status: status) {
        case .ok(let r):
            events = events.map { (e: PsEvent) -> PsEvent in
                guard e.id == event.id else { return e }
                var c: PsEvent = e
                c.myRsvp = r.myRsvp
                c.going = r.going
                c.interested = r.interested
                return c
            }
            return nil
        case .failure(let f):
            return f.message
        }
    }
}

extension GeoBox {
    func halfDiagonalKm() -> Double {
        return GeoMath.distanceM(south, west, north, east) / 2000
    }
}

enum GeoMath {
    static func distanceM(_ lat1: Double, _ lng1: Double, _ lat2: Double, _ lng2: Double) -> Double {
        let r: Double = 6_371_000
        let p1: Double = lat1 * .pi / 180
        let p2: Double = lat2 * .pi / 180
        let dp: Double = (lat2 - lat1) * .pi / 180
        let dl: Double = (lng2 - lng1) * .pi / 180
        let a: Double = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * r * atan2(sqrt(a), sqrt(1 - a))
    }
}
