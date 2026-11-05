import Foundation
import CoreLocation
import Observation

// MARK: DTOs (GET /v1/routes ...)

struct LatLngDto: Codable, Equatable {
    var lat: Double
    var lng: Double
}

/// A saved route (PhotoScout's model). `waypoints` are what the author placed ([lng, lat]), `geometry` the road line
/// through them (polyline6), and `convoyStops` the at-most-25 stops a convoy follows ([lng, lat]; a circuit closes).
struct SavedRouteDto: Decodable, Identifiable, Equatable {
    var id: String
    var name: String
    var notes: String?
    /// "sprint" (point to point) or "circuit" (ends where it starts).
    var type: String?
    var waypoints: [[Double]]?
    var geometry: String?
    var distanceM: Double?
    var durationS: Double?
    /// Where the convoy gathers before the drive.
    var staging: LatLngDto?
    var convoyStops: [[Double]]?
    var visibility: String?
    /// "convoyage" or "photoscout" (an import).
    var source: String?
    var updatedAt: String?

    var isCircuit: Bool { return type == "circuit" }
    var stops: [[Double]] { return convoyStops ?? [] }

    /// The line to draw: the road geometry, else straight between the waypoints.
    var line: [CLLocationCoordinate2D] {
        if let g = geometry, !g.isEmpty {
            let road: [CLLocationCoordinate2D] = Polyline.decode(g, precision: 6)
            if road.count >= 2 { return road }
        }
        var way: [CLLocationCoordinate2D] = (waypoints ?? []).compactMap { (p: [Double]) -> CLLocationCoordinate2D? in
            return p.count >= 2 ? CLLocationCoordinate2D(latitude: p[1], longitude: p[0]) : nil
        }
        if isCircuit, let first = way.first, way.count >= 2 { way.append(first) }
        return way
    }

    /// "Circuit · 84 km · 1 h 20 min · staging point".
    var stats: String {
        var parts: [String] = [isCircuit ? "Circuit" : "Point to point"]
        if let d = distanceM { parts.append(Formatters.distanceText(d)) }
        if let s = durationS { parts.append(Formatters.duration(s)) }
        if staging != nil { parts.append("staging point") }
        if source == "photoscout" { parts.append("from PhotoScout") }
        return parts.joined(separator: " · ")
    }
}

struct RouteOwnerDto: Decodable, Equatable {
    var handle: String?
    var displayName: String?
}

/// GET /v1/routes/discover: a route somebody else shares, starting `distanceM` from where we asked.
struct DiscoveredRouteDto: Decodable, Identifiable, Equatable {
    var route: SavedRouteDto
    var owner: RouteOwnerDto?
    var distanceM: Double?
    /// "relaxed", "flowing" or "twisty".
    var character: String?

    var id: String { return route.id }
}

/// GET /v1/photoscout/routes: a route saved in PhotoScout, not yet imported.
struct PsRouteDto: Decodable, Identifiable, Equatable {
    var id: String
    var name: String
    var notes: String?
    var type: String?
}

// MARK: Store

/// The routes I saved, loaded on demand.
@Observable
@MainActor
final class SavedRoutesStore {
    var routes: [SavedRouteDto] = []
    var loaded: Bool = false
    var error: String?

    @ObservationIgnored private let api: ApiClient

    init(api: ApiClient) {
        self.api = api
    }

    func refresh() async {
        switch await api.listRoutes() {
        case .ok(let list):
            routes = list
            error = nil
        case .failure(let f):
            error = f.message
        }
        loaded = true
    }

    func clear() {
        routes = []
        loaded = false
        error = nil
    }

    /// Nil when done, else the error.
    func setVisibility(_ id: String, _ visibility: String) async -> String? {
        switch await api.patchRoute(id: id, body: ["visibility": visibility]) {
        case .ok(let r):
            replace(r)
            return nil
        case .failure(let f):
            return f.message
        }
    }

    func delete(_ id: String) async -> String? {
        switch await api.deleteRoute(id: id) {
        case .ok:
            routes.removeAll { (r: SavedRouteDto) -> Bool in return r.id == id }
            return nil
        case .failure(let f):
            return f.message
        }
    }

    /// Saves a copy of someone else's route (private).
    func saveCopy(_ r: SavedRouteDto) async -> String? {
        var body: [String: Any] = ["name": r.name, "waypoints": r.waypoints ?? [], "type": r.type ?? "sprint", "visibility": "private"]
        if let n = r.notes, !n.isEmpty { body["notes"] = n }
        if let g = r.geometry { body["geometry"] = g }
        if let d = r.distanceM { body["distanceM"] = d }
        if let s = r.durationS { body["durationS"] = s }
        if let st = r.staging { body["staging"] = ["lat": st.lat, "lng": st.lng] }
        switch await api.saveRoute(body: body) {
        case .ok(let saved):
            routes.insert(saved, at: 0)
            return nil
        case .failure(let f):
            return f.message
        }
    }

    func importFromPhotoScout(_ id: String) async -> String? {
        switch await api.importPhotoscoutRoute(id: id) {
        case .ok(let saved):
            replace(saved)
            return nil
        case .failure(let f):
            return f.message
        }
    }

    private func replace(_ r: SavedRouteDto) {
        if let i = routes.firstIndex(where: { (x: SavedRouteDto) -> Bool in return x.id == r.id }) {
            routes[i] = r
        } else {
            routes.insert(r, at: 0)
        }
    }
}

/// The saved route this device (as leader) shared in the convoy, with its staging point and end.
struct SharedSavedRoute: Equatable {
    var code: String
    var route: SavedRouteDto
    var staging: Destination?
    var end: Destination
}
