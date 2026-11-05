import Foundation
import Observation

/// What is on the roads around the map view, for the Drive map: police, cameras, crashes, closures, roadworks and
/// jams from TfNSW and Waze (our own reports come from `HazardStore`, with voting).
///
/// Loaded for a box a bit bigger than the view, refreshed every minute, and asked again every few seconds while Waze
/// is still scanning a new area (it pans its own map there first).
@Observable
@MainActor
final class AreaConditionsStore {
    var items: [ConditionItemDto] = []
    /// Waze is still scanning this area: more may arrive.
    var scanning: Bool = false
    /// Waze is set up on this server.
    var waze: Bool = false

    @ObservationIgnored private let api: ApiClient
    @ObservationIgnored private var box: GeoBox?
    @ObservationIgnored private var loadedAt: Date = .distantPast
    @ObservationIgnored private var loading: GeoBox?
    @ObservationIgnored private var task: Task<Void, Never>?

    /// The server's limit, degrees a side.
    static let maxDegrees: Double = 0.3

    init(api: ApiClient) {
        self.api = api
    }

    func clear() {
        task?.cancel()
        task = nil
        box = nil
        loading = nil
        loadedAt = .distantPast
        items = []
        scanning = false
    }

    /// The map came to rest on `view`. Off clears what was shown.
    func onViewport(_ view: GeoBox, on: Bool) {
        if !on {
            if !items.isEmpty || box != nil { clear() }
            return
        }
        if let b = box, b.covers(view), Date().timeIntervalSince(loadedAt) < 60 { return }
        if task != nil, let l = loading, l.covers(view) { return }
        let want: GeoBox = view.grown(0.25).atLeast(0.06)
        // Zoomed far out there is too much to show and the server refuses: keep what we have.
        if want.north - want.south > AreaConditionsStore.maxDegrees || want.east - want.west > AreaConditionsStore.maxDegrees { return }
        load(want)
    }

    private func load(_ want: GeoBox) {
        task?.cancel()
        loading = want
        task = Task { @MainActor [weak self] in
            for _ in 0...8 {
                guard let self = self, !Task.isCancelled else { return }
                let r: ApiResult<AreaConditionsDto> = await self.api.areaConditions(box: want)
                if Task.isCancelled { return }
                switch r {
                case .ok(let dto):
                    self.box = want
                    self.loadedAt = Date()
                    let pending: Bool = dto.sources.waze == "pending"
                    let fresh: [ConditionItemDto] = AreaConditionsStore.visible(dto.items)
                    // While Waze scans, keep what it showed before in this box rather than blanking the map.
                    let kept: [ConditionItemDto] = pending
                        ? self.items.filter { (i: ConditionItemDto) -> Bool in return i.source == "waze" && want.contains(lat: i.lat, lng: i.lng) }
                        : []
                    var seen: Set<String> = []
                    self.items = (fresh + kept).filter { (i: ConditionItemDto) -> Bool in return seen.insert(i.id).inserted }
                    self.scanning = pending
                    self.waze = dto.sources.waze != "off"
                    if !pending {
                        self.task = nil
                        self.loading = nil
                        return
                    }
                    try? await Task.sleep(nanoseconds: 5_000_000_000)
                case .failure:
                    // Keep what is shown; the next viewport change tries again.
                    self.task = nil
                    self.loading = nil
                    return
                }
            }
            self?.task = nil
            self?.loading = nil
        }
    }

    /// What the layer draws: our own reports are already on the map (with voting).
    static func visible(_ items: [ConditionItemDto]) -> [ConditionItemDto] {
        var seen: Set<String> = []
        return items.filter { (i: ConditionItemDto) -> Bool in
            return i.source != "convoyage" && seen.insert(i.id).inserted
        }
    }

    /// "POL", "CAM", "JAM"... the short word on the pin.
    nonisolated static func word(_ kind: String) -> String {
        switch kind {
        case "police": return "POL"
        case "camera": return "CAM"
        case "jam": return "JAM"
        case "roadwork": return "WRK"
        case "closure": return "X"
        case "flood": return "H2O"
        case "fire": return "FIRE"
        case "event": return "EVT"
        default: return "!"
        }
    }
}

extension GeoBox {
    func covers(_ inner: GeoBox) -> Bool {
        return inner.south >= south && inner.north <= north && inner.west >= west && inner.east <= east
    }

    /// Bigger by `fraction` of its size on every side.
    func grown(_ fraction: Double) -> GeoBox {
        let dLat: Double = (north - south) * fraction
        let dLng: Double = (east - west) * fraction
        return GeoBox(south: max(south - dLat, -90), west: max(west - dLng, -180), north: min(north + dLat, 90), east: min(east + dLng, 180))
    }

    /// The same centre, at least `degrees` across each way.
    func atLeast(_ degrees: Double) -> GeoBox {
        let cLat: Double = (south + north) / 2
        let cLng: Double = (west + east) / 2
        let hLat: Double = max((north - south) / 2, degrees / 2)
        let hLng: Double = max((east - west) / 2, degrees / 2)
        return GeoBox(south: max(cLat - hLat, -90), west: max(cLng - hLng, -180), north: min(cLat + hLat, 90), east: min(cLng + hLng, 180))
    }
}
