import Foundation
import CoreLocation
import Observation

/// Map box in degrees. The server wants `south,west,north,east` and refuses more than 10 degrees each way.
struct GeoBox: Equatable {
    var south: Double
    var west: Double
    var north: Double
    var east: Double

    func param() -> String {
        return String(format: "%.5f,%.5f,%.5f,%.5f", south, west, north, east)
    }

    func contains(lat: Double, lng: Double) -> Bool {
        if lat < south || lat > north { return false }
        if west <= east { return lng >= west && lng <= east }
        return lng >= west || lng <= east
    }

    /// Same centre, but at most `maxDegrees` across, so the server never answers 400 for a zoomed-out map.
    func clamped(maxDegrees: Double = 8.0) -> GeoBox {
        let cLat: Double = (south + north) / 2
        let cLng: Double = (west + east) / 2
        let halfLat: Double = min((north - south) / 2, maxDegrees / 2)
        let halfLng: Double = min((east - west) / 2, maxDegrees / 2)
        return GeoBox(south: max(cLat - halfLat, -90), west: max(cLng - halfLng, -180),
                      north: min(cLat + halfLat, 90), east: min(cLng + halfLng, 180))
    }
}

/// A hazard a convoy member just reported, waiting for Confirm / Dismiss.
struct HazardAlert: Equatable {
    var hazard: HazardDto
    var room: String
    var error: String?
}

/// "Debris", "Speed camera", "Road works"... from the server's kind + subkind.
func hazardLabel(kind: String, subkind: String?) -> String {
    let sub: String = subkind ?? ""
    if !sub.isEmpty {
        let spaced: String = sub.replacingOccurrences(of: "_", with: " ")
        return spaced.prefix(1).uppercased() + spaced.dropFirst()
    }
    switch kind {
    case HazardKinds.police: return "Police"
    case HazardKinds.hazard: return "Hazard"
    case HazardKinds.works: return "Road works"
    case HazardKinds.fuel: return "Fuel"
    case HazardKinds.rest: return "Rest stop"
    case HazardKinds.crash: return "Crash"
    default: return kind.prefix(1).uppercased() + kind.dropFirst()
    }
}

/// Active hazards for the map plus the live "a member reported a hazard" alert.
///
/// Sources: `GET /v1/hazards?bbox=` for what is on screen, the socket's `hazard` frames for live reports and our own
/// POST responses. Everything is merged by id; expired hazards and ones we dismissed are filtered out.
@Observable
@MainActor
final class HazardStore {
    var active: [HazardDto] = []
    var alert: HazardAlert?

    @ObservationIgnored private let api: ApiClient
    @ObservationIgnored private var byId: [String: HazardDto] = [:]
    /// Ids of hazards I reported: the server echoes them to the whole room, me included, and I do not need a banner.
    @ObservationIgnored private var mine: Set<String> = []
    /// Reports whose POST has not answered yet. The server pushes the `hazard` frame BEFORE it replies to the POST,
    /// so my own report can arrive on the socket first; match it by kind and place.
    @ObservationIgnored private var inFlight: [(kind: String, lat: Double, lng: Double)] = []
    @ObservationIgnored private var lastBox: GeoBox?

    private static let ownReportRadiusM: Double = 30

    init(api: ApiClient) {
        self.api = api
    }

    /// Folds a live report in. A report from someone else raises `alert`.
    func onSocketHazard(room: String, hazard: HazardDto) {
        upsert(hazard)
        let here: CLLocation = CLLocation(latitude: hazard.lat, longitude: hazard.lng)
        for r in inFlight where r.kind == hazard.kind {
            if CLLocation(latitude: r.lat, longitude: r.lng).distance(from: here) < HazardStore.ownReportRadiusM {
                mine.insert(hazard.id)
            }
        }
        let seen: Bool = mine.contains(hazard.id) || hazard.myVote != nil
        if !seen && !isExpired(hazard) {
            alert = HazardAlert(hazard: hazard, room: room, error: nil)
        }
    }

    /// Replaces what we know inside `box` with the server's answer; hazards outside it are kept.
    func refresh(box: GeoBox) async {
        lastBox = box
        let result: ApiResult<[HazardDto]> = await api.listHazards(bbox: box.clamped().param())
        guard case .ok(let fetched) = result else { return }
        var fetchedById: [String: HazardDto] = [:]
        for h in fetched { fetchedById[h.id] = h }
        var next: [String: HazardDto] = [:]
        for (id, h) in byId where !box.contains(lat: h.lat, lng: h.lng) && fetchedById[id] == nil {
            next[id] = h
        }
        for (id, h) in fetchedById { next[id] = h }
        byId = next
        publish()
    }

    /// Re-reads the last box (after a report, a vote, or coming back to the map).
    func refreshLast() async {
        if let b = lastBox { await refresh(box: b) }
    }

    /// Sends a report; returns an error message, or nil on success.
    func report(kind: String, subkind: String, lat: Double, lng: Double, heading: Double?) async -> String? {
        var h: Double? = nil
        if let hd = heading, hd >= 0 {
            h = hd.truncatingRemainder(dividingBy: 360)
        }
        let key: (kind: String, lat: Double, lng: Double) = (kind: kind, lat: lat, lng: lng)
        inFlight.append(key)
        let result: ApiResult<HazardDto> = await api.createHazard(kind: kind, subkind: subkind, lat: lat, lng: lng, heading: h)
        if let idx = inFlight.firstIndex(where: { $0.kind == key.kind && $0.lat == key.lat && $0.lng == key.lng }) {
            inFlight.remove(at: idx)
        }
        switch result {
        case .ok(let hazard):
            mine.insert(hazard.id)
            clearAlert(id: hazard.id)
            upsert(hazard)
            return nil
        case .failure(let f):
            if f.isUnauthorized { return "Sign in with your email to report hazards." }
            if f.status == 429 { return "Slow down, too many reports." }
            return f.message
        }
    }

    func confirm(id: String) {
        let client: ApiClient = api
        vote(id: id) { await client.confirmHazard(id: id) }
    }

    func dismiss(id: String) {
        let client: ApiClient = api
        vote(id: id) { await client.dismissHazard(id: id) }
    }

    /// Hides the banner without voting.
    func clearAlert(id: String?) {
        if id == nil || alert?.hazard.id == id {
            alert = nil
        }
    }

    func clear() {
        byId = [:]
        mine = []
        alert = nil
        lastBox = nil
        publish()
    }

    private func vote(id: String, call: @escaping () async -> ApiResult<HazardDto>) {
        Task { [weak self] in
            let result: ApiResult<HazardDto> = await call()
            guard let self = self else { return }
            switch result {
            case .ok(let h):
                self.upsert(h)
                self.clearAlert(id: id)
            case .failure(let f):
                // 409 = I already voted / it is my own report; 404 = it expired. Either way the banner has done its job.
                if f.status == 409 || f.status == 404 {
                    if f.status == 404 {
                        self.byId.removeValue(forKey: id)
                        self.publish()
                    }
                    self.clearAlert(id: id)
                } else if var a = self.alert, a.hazard.id == id {
                    a.error = f.message
                    self.alert = a
                }
            }
        }
    }

    private func upsert(_ h: HazardDto) {
        byId[h.id] = h
        publish()
    }

    private func isExpired(_ h: HazardDto) -> Bool {
        if let d = h.expiresAtDate { return d <= Date() }
        return false
    }

    private func publish() {
        let list: [HazardDto] = byId.values.filter { (h: HazardDto) -> Bool in
            return !isExpired(h) && h.myVote != "dismiss"
        }
        active = list.sorted { (a: HazardDto, b: HazardDto) -> Bool in return a.id < b.id }
    }
}
