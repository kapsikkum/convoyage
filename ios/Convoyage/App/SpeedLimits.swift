import Foundation
import CoreLocation
import Observation

/// One mapped road near you: its signed limit (km/h, nil when unsigned) and its line as (lng, lat) pairs.
struct LimitWay {
    var limitKmh: Int?
    var name: String
    var line: [(Double, Double)]
}

/// The signed speed limit of the road you are on, from OpenStreetMap `maxspeed` (through the server's road layer).
/// Loads the roads in a small box around you and matches each fix to the nearest one running your way; a new box
/// loads near the edge. Unsigned roads show nothing rather than a guessed default.
@Observable
@MainActor
final class SpeedLimits {
    var limit: Int?

    @ObservationIgnored private let api: ApiClient
    @ObservationIgnored private var ways: [LimitWay] = []
    @ObservationIgnored private var box: GeoBox?
    @ObservationIgnored private var task: Task<Void, Never>?
    @ObservationIgnored private var last: Int = -1

    private static let half: Double = 0.03
    private static let edge: Double = 0.008
    /// Further than this from every road (a car park, a servo): no limit.
    static let maxOffM: Double = 25

    init(api: ApiClient) {
        self.api = api
    }

    func clear() {
        task?.cancel()
        task = nil
        ways = []
        box = nil
        last = -1
        limit = nil
    }

    func onFix(lat: Double, lng: Double, headingDeg: Double?) {
        let near: Bool
        if let b = box {
            near = lat < b.south + SpeedLimits.edge || lat > b.north - SpeedLimits.edge || lng < b.west + SpeedLimits.edge || lng > b.east - SpeedLimits.edge
        } else {
            near = true
        }
        if near && task == nil { load(lat: lat, lng: lng) }
        let i: Int = SpeedLimits.match(ways, lat: lat, lng: lng, headingDeg: headingDeg, previous: last)
        last = i
        let found: Int? = i >= 0 ? ways[i].limitKmh : nil
        if found != limit { limit = found }
    }

    private func load(lat: Double, lng: Double) {
        let want: GeoBox = GeoBox(south: lat - SpeedLimits.half, west: lng - SpeedLimits.half, north: lat + SpeedLimits.half, east: lng + SpeedLimits.half)
        task = Task { @MainActor [weak self] in
            guard let self = self else { return }
            let r: ApiResult<RoadsDto> = await self.api.roads(box: want)
            if case .ok(let dto) = r {
                self.ways = SpeedLimits.ways(dto)
                self.box = want
                self.last = -1
            }
            self.task = nil
        }
    }

    static func ways(_ dto: RoadsDto) -> [LimitWay] {
        return dto.features.compactMap { (f: RoadsDto.Feature) -> LimitWay? in
            let line: [(Double, Double)] = f.geometry.coordinates.compactMap { (p: [Double]) -> (Double, Double)? in
                return p.count >= 2 ? (p[0], p[1]) : nil
            }
            return line.count >= 2 ? LimitWay(limitKmh: f.properties.maxspeed, name: f.properties.name ?? "", line: line) : nil
        }
    }

    /// The index of the way you are on, or -1. Distance to each segment in local metres, plus a penalty when it runs
    /// across your heading (either direction along it is fine), and a small bonus for the way you were on, so a side
    /// street you pass does not flicker in.
    static func match(_ ways: [LimitWay], lat: Double, lng: Double, headingDeg: Double?, previous: Int) -> Int {
        let kx: Double = 111_320.0 * cos(lat * .pi / 180)
        let ky: Double = 110_540.0
        var best: Int = -1
        var bestScore: Double = .greatestFiniteMagnitude
        for (wi, w) in ways.enumerated() {
            for i in 0..<(w.line.count - 1) {
                let ax: Double = (w.line[i].0 - lng) * kx
                let ay: Double = (w.line[i].1 - lat) * ky
                let dx: Double = (w.line[i + 1].0 - lng) * kx - ax
                let dy: Double = (w.line[i + 1].1 - lat) * ky - ay
                let len2: Double = dx * dx + dy * dy
                if len2 < 1e-6 { continue }
                let t: Double = min(1, max(0, -(ax * dx + ay * dy) / len2))
                let d: Double = hypot(ax + dx * t, ay + dy * t)
                if d > maxOffM { continue }
                var score: Double = d
                if let h = headingDeg {
                    let seg: Double = atan2(dx, dy) * 180 / .pi
                    var diff: Double = abs((((h - seg).truncatingRemainder(dividingBy: 180)) + 180).truncatingRemainder(dividingBy: 180))
                    if diff > 90 { diff = 180 - diff }
                    score += diff / 90.0 * 20.0
                }
                if wi == previous { score -= 4 }
                if score < bestScore {
                    bestScore = score
                    best = wi
                }
            }
        }
        return best
    }
}
