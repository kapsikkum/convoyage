import Foundation
import CoreLocation
import Observation

// MARK: DTOs (POST /v1/conditions, GET /v1/routes/:id/conditions)
// Every field a source may leave out is optional: each of traffic, police, weather, roads and stops fails on its
// own and the answer still goes out without it (`sources` says which ones were heard from).

/// One thing found on or near the route: police, camera, hazard, crash, jam, closure, roadwork, event, flood,
/// fire or alpine, from `tfnsw`, `waze` or `convoyage`.
struct RouteConditionItemDto: Decodable, Identifiable, Equatable {
    var id: String
    /// tfnsw, waze or convoyage.
    var source: String
    /// police, camera, hazard, crash, jam, closure, roadwork, event, flood, fire, alpine.
    var kind: String
    var title: String
    var detail: String
    var lat: Double
    var lng: Double
    /// Kilometres from the start of the route.
    var km: Double
    /// When it was reported, if known (ISO).
    var reportedAt: String?
}

struct RouteTrafficDto: Decodable, Equatable {
    /// clear, light, moderate or heavy.
    var level: String
    var delayMin: Double
    var summary: String
    var items: [RouteConditionItemDto]
}

/// The weather where the car should be, at the time it should be there (Open-Meteo).
struct RouteWeatherPointDto: Decodable, Equatable {
    var km: Double
    var lat: Double
    var lng: Double
    /// When the car should be here (ISO).
    var at: String?
    var tempC: Double?
    var precipProbPct: Double?
    var precipMm: Double?
    /// WMO weather code.
    var code: Int?
    var windKmh: Double?
    var gustKmh: Double?
    var visibilityM: Double?
    /// e.g. "Light rain", "Fog", "Clear".
    var label: String
    var isDay: Bool?
}

struct RouteWeatherDto: Decodable, Equatable {
    var summary: String
    /// How much the worst point matters, 0 (nothing) to 3 (take care).
    var worst: Int
    var points: [RouteWeatherPointDto]
}

/// Kilometres of route in each grade. The server always sends all four; the defaults only guard old snapshots.
struct RouteRoadKmDto: Decodable, Equatable {
    var good: Double = 0
    var fair: Double = 0
    var poor: Double = 0
    var unknown: Double = 0

    private enum CodingKeys: String, CodingKey {
        case good
        case fair
        case poor
        case unknown
    }

    init() {}

    init(from decoder: Decoder) throws {
        let c: KeyedDecodingContainer<RouteRoadKmDto.CodingKeys> = try decoder.container(keyedBy: RouteRoadKmDto.CodingKeys.self)
        good = try c.decodeIfPresent(Double.self, forKey: .good) ?? 0
        fair = try c.decodeIfPresent(Double.self, forKey: .fair) ?? 0
        poor = try c.decodeIfPresent(Double.self, forKey: .poor) ?? 0
        unknown = try c.decodeIfPresent(Double.self, forKey: .unknown) ?? 0
    }
}

/// One stretch of the route at one grade; `line` is polyline precision 6, for colouring the route.
struct RouteRoadRunDto: Decodable, Equatable {
    /// good, fair, poor or unknown.
    var grade: String
    var km: Double
    var name: String
    var surface: String
    var line: String
}

/// One of the roughest stretches, worst first.
struct RouteRoadWorstDto: Decodable, Equatable {
    var name: String
    var km: Double
    var surface: String
}

struct RouteRoadsDto: Decodable, Equatable {
    var km: RouteRoadKmDto
    var unpavedKm: Double
    var runs: [RouteRoadRunDto]
    var worst: [RouteRoadWorstDto]
    var fetchedAt: String?
}

/// A servo, eatery, cafe, toilets, rest area or charger on the route (OpenStreetMap, prices from NSW FuelCheck).
struct RouteStopDto: Decodable, Identifiable, Equatable {
    var id: String
    /// fuel, food, cafe, toilets, rest or ev.
    var kind: String
    var name: String
    var brand: String
    var lat: Double
    var lng: Double
    /// Open around the clock, when the map says so.
    var open24: Bool
    /// Cents per litre by FuelCheck fuel code (servos with prices only).
    var prices: [String: Double]
    var pricesAt: String?
    /// Kilometres from the start of the route.
    var km: Double
    /// Metres off the route.
    var offM: Double?
}

/// Per source: ok, off, error, partial or pending (still being fetched; ask again shortly).
struct RouteSourcesDto: Decodable, Equatable {
    var tfnsw: String
    var waze: String
    var weather: String
    var roads: String
    var stops: String
    var fuel: String
}

struct RouteConditionsDto: Decodable, Equatable {
    var computedAt: String
    var traffic: RouteTrafficDto
    var police: [RouteConditionItemDto]
    var hazards: [RouteConditionItemDto]
    var weather: RouteWeatherDto
    /// Nil while Overpass is still grading (sources.roads == "pending") or when it failed.
    var roads: RouteRoadsDto?
    var stops: [RouteStopDto]
    var sources: RouteSourcesDto
    /// True when the server answered from the saved snapshot.
    var cached: Bool?
}

// MARK: Derived shapes for the map and the card

extension RouteConditionsDto {
    /// Everything found on the route (traffic, police and hazards), unique by id, in distance order.
    var found: [RouteConditionItemDto] {
        var seen: Set<String> = []
        let all: [RouteConditionItemDto] = traffic.items + police + hazards
        return all
            .filter { (i: RouteConditionItemDto) -> Bool in return seen.insert(i.id).inserted }
            .sorted { (a: RouteConditionItemDto, b: RouteConditionItemDto) -> Bool in return a.km < b.km }
    }

    /// Police, cameras, hazards and the crashes and closures in traffic as map pins.
    /// `kind` is "police" or "hazard"; the label is the short word on the pin.
    func conditionPins() -> [(id: String, kind: String, label: String, lat: Double, lng: Double)] {
        typealias Pin = (id: String, kind: String, label: String, lat: Double, lng: Double)
        let policePins: [Pin] = police.map { (i: RouteConditionItemDto) -> Pin in
            return ("c-" + i.id, "police", i.kind == "camera" ? "CAM" : "POL", i.lat, i.lng)
        }
        let other: [RouteConditionItemDto] = hazards + traffic.items.filter { (i: RouteConditionItemDto) -> Bool in
            return i.kind == "crash" || i.kind == "closure"
        }
        let hazardPins: [Pin] = other.map { (i: RouteConditionItemDto) -> Pin in
            return ("c-" + i.id, "hazard", "!", i.lat, i.lng)
        }
        return policePins + hazardPins
    }

    /// Stops as map pins: the kind as a word, a servo's cheapest regular fuel as its tag.
    func stopPins() -> [(id: String, label: String, lat: Double, lng: Double, tag: String?)] {
        typealias Pin = (id: String, label: String, lat: Double, lng: Double, tag: String?)
        return stops.map { (s: RouteStopDto) -> Pin in
            let price: Double? = s.prices["U91"] ?? s.prices["E10"] ?? s.prices["DL"]
            return ("s-" + s.id, stopLabel(kind: s.kind), s.lat, s.lng, price.map { (p: Double) -> String in return String(format: "%.1f", p) })
        }
    }

    /// The route split where the grade changes, decoded for drawing (polyline precision 6). Runs that decode to
    /// less than two points are dropped.
    var roadSegments: [(grade: String, line: [CLLocationCoordinate2D])] {
        guard let runs: [RouteRoadRunDto] = roads?.runs else { return [] }
        var out: [(grade: String, line: [CLLocationCoordinate2D])] = []
        for run in runs {
            let pts: [CLLocationCoordinate2D] = Polyline.decode(run.line, precision: 6)
            if pts.count >= 2 { out.append((run.grade, pts)) }
        }
        return out
    }

    /// 0xRRGGBB for a graded run on the map: green good, amber fair, pink poor, slate unknown.
    static func roadGradeColor(_ grade: String) -> UInt32 {
        switch grade {
        case "good": return 0x46e08c
        case "fair": return 0xffb020
        case "poor": return 0xff3d8b
        default: return 0x4a5878
        }
    }
}

// MARK: Words and numbers shared with the card

/// The fuels people ask about, in the order we show them. FuelCheck's DL is diesel.
private let fuelOrder: [String] = ["U91", "E10", "P95", "P98", "DL", "PDL", "LPG"]

/// Short word on a stop's pin.
func stopLabel(kind: String) -> String {
    switch kind {
    case "fuel": return "FUEL"
    case "food": return "EAT"
    case "cafe": return "CAFE"
    case "toilets": return "WC"
    case "rest": return "REST"
    case "ev": return "EV"
    default: return "•"
    }
}

/// FuelCheck's code as a name ("DL" is Diesel).
func fuelName(code: String) -> String {
    switch code {
    case "U91": return "U91"
    case "E10": return "E10"
    case "P95": return "P95"
    case "P98": return "P98"
    case "DL": return "Diesel"
    case "PDL": return "Prem diesel"
    case "LPG": return "LPG"
    default: return code
    }
}

/// "U91 199.9 · Diesel 210.5" for a servo, at most `max` fuels.
func priceLine(stop: RouteStopDto, max: Int = 3) -> String {
    let parts: [String] = fuelOrder.compactMap { (code: String) -> String? in
        guard let price: Double = stop.prices[code] else { return nil }
        return "\(fuelName(code: code)) \(String(format: "%.1f", price))"
    }
    return parts.prefix(max).joined(separator: " · ")
}

/// The next servo at or ahead of `atKm` on the route, and how far away it is.
func nextFuel(stops: [RouteStopDto], atKm: Double) -> (stop: RouteStopDto, kmAway: Double)? {
    let ahead: [RouteStopDto] = stops.filter { (s: RouteStopDto) -> Bool in return s.kind == "fuel" && s.km >= atKm - 0.05 }
    guard let servo: RouteStopDto = ahead.min(by: { (a: RouteStopDto, b: RouteStopDto) -> Bool in return a.km < b.km }) else { return nil }
    return (servo, Swift.max(0.0, servo.km - atKm))
}

/// One decimal under 10 km, rounded above.
func fmtKm(_ km: Double) -> String {
    return km < 10 ? String(format: "%.1f", km) : String(Int(km.rounded()))
}

// MARK: Store

/// Route conditions as they load: the saved snapshot first (instant), then a fresh check. When the server says
/// road quality or stops are still loading (Overpass is slow), asks again a little later, up to three times.
@Observable
@MainActor
final class RouteConditionsStore {
    var value: RouteConditionsDto?
    var loading: Bool = false
    var error: String?

    @ObservationIgnored private let api: ApiClient
    @ObservationIgnored private var task: Task<Void, Never>?

    init(api: ApiClient) {
        self.api = api
    }

    func clear() {
        task?.cancel()
        task = nil
        value = nil
        loading = false
        error = nil
    }

    /// A saved route: the last snapshot at once (a 404 there is just "nothing saved yet"), then the live check.
    func loadSaved(routeId: String) async {
        await startSaved(routeId: routeId, forceRefresh: false, cachedFirst: true)
    }

    /// Force a fresh check of a saved route, keeping what is shown while it loads.
    func refresh(routeId: String) async {
        await startSaved(routeId: routeId, forceRefresh: true, cachedFirst: false)
    }

    /// A route that is not saved (the builder): checked once the line settles.
    func loadLine(geometry: String, durationS: Double?) async {
        task?.cancel()
        error = nil
        let run: Task<Void, Never> = Task { @MainActor [weak self] in
            guard let self = self else { return }
            // Let the person stop placing points.
            try? await Task.sleep(nanoseconds: 1_500_000_000)
            if Task.isCancelled { return }
            self.loading = true
            switch await self.api.routeConditions(geometry: geometry, durationS: durationS) {
            case .ok(let dto):
                self.value = dto
            case .failure(let failure):
                self.error = failure.message
            }
            if Task.isCancelled { return }
            self.loading = false
        }
        task = run
        await run.value
    }

    private func startSaved(routeId: String, forceRefresh: Bool, cachedFirst: Bool) async {
        task?.cancel()
        error = nil
        let run: Task<Void, Never> = Task { @MainActor [weak self] in
            guard let self = self else { return }
            // A cancelled task leaves `loading` to whoever cancelled it: clear() or the load that replaced it.
            if Task.isCancelled { return }
            if cachedFirst {
                self.loading = true
                let cached: ApiResult<RouteConditionsDto> = await self.api.savedRouteConditions(id: routeId, cachedOnly: true)
                if Task.isCancelled { return }
                if case .ok(let snapshot) = cached { self.value = snapshot }
            }
            var tries: Int = 0
            while !Task.isCancelled {
                self.loading = true
                let result: ApiResult<RouteConditionsDto> = await self.api.savedRouteConditions(id: routeId, refresh: forceRefresh && tries == 0)
                if Task.isCancelled { return }
                switch result {
                case .ok(let dto):
                    self.value = dto
                case .failure(let failure):
                    self.error = failure.message
                    self.loading = false
                    return
                }
                let pending: Bool = self.value?.sources.roads == "pending" || self.value?.sources.stops == "pending"
                tries += 1
                guard pending, tries <= 3 else {
                    self.loading = false
                    return
                }
                // Show what we have while Overpass grades the roads, then ask again in 30 s.
                self.loading = false
                try? await Task.sleep(nanoseconds: 30_000_000_000)
            }
        }
        task = run
        await run.value
    }
}
