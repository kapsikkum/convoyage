import SwiftUI
import CoreLocation
import Observation

/// Police, cameras and traffic, and PhotoScout's spots and events, shared by the convoy and navigation maps: the pins,
/// what a tap on one opens, and the hint that offers to frame what is nearby when the map follows you at street level.
enum MapLayerPins {
    /// Police and traffic around the map, when that layer is on.
    @MainActor
    static func areaPins(_ app: AppState) -> [MapPin] {
        guard app.layerTraffic else { return [] }
        return app.area.items.map { (i: ConditionItemDto) -> MapPin in return i.pin }
    }

    /// PhotoScout spots and events, when linked and switched on.
    @MainActor
    static func photoPins(_ app: AppState) -> [MapPin] {
        let ps: PhotoScoutLayers = app.photoscout
        guard ps.available else { return [] }
        let spots: [MapPin] = app.layerSpots ? ps.spots.map { (s: PsSpot) -> MapPin in return s.pin } : []
        let events: [MapPin] = app.layerEvents ? ps.events.compactMap { (e: PsEvent) -> MapPin? in return e.pin } : []
        return spots + events
    }

    /// Everything both layers put on the map.
    @MainActor
    static func pins(_ app: AppState) -> [MapPin] {
        return areaPins(app) + photoPins(app)
    }
}

/// Any of the shared layers on, for the rail button's tint.
@MainActor
func layersAnyOn(_ app: AppState) -> Bool {
    return app.layerTraffic || (app.photoscout.available && (app.layerSpots || app.layerEvents))
}

/// What a tap on a layer pin opens: a traffic item, a PhotoScout spot or an event.
@Observable
@MainActor
final class MapLayerSelection {
    var openItem: ConditionItemDto?
    var openSpot: PsSpot?
    var openEvent: PsEvent?

    /// Parses the `area-`, `spot-` and `event-` pin ids; false when the pin belongs to something else.
    func handlePinTap(_ id: String, app: AppState) -> Bool {
        if id.hasPrefix("area-") {
            let key: String = String(id.dropFirst(5))
            openItem = app.area.items.first { (i: ConditionItemDto) -> Bool in return i.id == key }
            return true
        }
        if id.hasPrefix("spot-") {
            let key: String = String(id.dropFirst(5))
            openSpot = app.photoscout.spots.first { (s: PsSpot) -> Bool in return s.id == key }
            return true
        }
        if id.hasPrefix("event-") {
            let key: String = String(id.dropFirst(6))
            openEvent = app.photoscout.events.first { (e: PsEvent) -> Bool in return e.id == key }
            return true
        }
        return false
    }
}

/// The three sheets a layer pin opens, attached to whichever map is showing them.
struct MapLayerSheets: ViewModifier {
    let selection: MapLayerSelection

    func body(content: Content) -> some View {
        @Bindable var sel: MapLayerSelection = selection
        return content
            .sheet(item: $sel.openItem) { (i: ConditionItemDto) in
                ConditionSheet(item: i)
                    .presentationDetents([.height(220)])
            }
            .sheet(item: $sel.openSpot) { (s: PsSpot) in
                SpotSheet(spot: s)
                    .presentationDetents([.medium, .large])
            }
            .sheet(item: $sel.openEvent) { (e: PsEvent) in
                EventSheet(event: e)
                    .presentationDetents([.medium, .large])
            }
    }
}

extension View {
    /// The condition, spot and event sheets, for a map with the shared layers on it.
    func mapLayerSheets(_ selection: MapLayerSelection) -> some View {
        return modifier(MapLayerSheets(selection: selection))
    }
}

/// Layers are on but nothing they loaded is in view (the map follows you at street level): say what is nearby and offer
/// to show it. Tapping hands the host the points to frame.
struct LayerHint: View {
    @Environment(AppState.self) private var app
    @Environment(\.theme) private var theme
    /// What the map is showing; nil until it first comes to rest.
    let viewport: GeoBox?
    let onShow: ([CLLocationCoordinate2D]) -> Void

    var body: some View {
        let pins: [MapPin] = offscreenPins
        let text: String = LayerHint.hintText(pins)
        if !text.isEmpty {
            Button {
                onShow(pins.map { (p: MapPin) -> CLLocationCoordinate2D in return p.coordinate })
            } label: {
                Text(text)
                    .font(CvFont.label(12))
                    .tracking(1.1)
                    .foregroundStyle(theme.signal)
                    .padding(.horizontal, 14)
                    .frame(minHeight: 36)
                    .cvPanel(fill: theme.surface100, edge: theme.signal, cut: Cut.s)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(text)
        }
    }

    /// The spot and event pins on show, unless one of them is already in the view.
    private var offscreenPins: [MapPin] {
        let pins: [MapPin] = MapLayerPins.photoPins(app)
        guard !pins.isEmpty, let box = viewport else { return [] }
        let inView: Bool = pins.contains { (p: MapPin) -> Bool in
            return box.contains(lat: p.coordinate.latitude, lng: p.coordinate.longitude)
        }
        return inView ? [] : pins
    }

    /// "3 SPOTS · 1 EVENT NEARBY · SHOW", or empty when there is nothing to count.
    private static func hintText(_ pins: [MapPin]) -> String {
        let spots: Int = pins.filter { (p: MapPin) -> Bool in return p.kind == .spot }.count
        let events: Int = pins.filter { (p: MapPin) -> Bool in return p.kind == .event }.count
        var parts: [String] = []
        if spots > 0 { parts.append("\(spots) SPOT" + (spots == 1 ? "" : "S")) }
        if events > 0 { parts.append("\(events) EVENT" + (events == 1 ? "" : "S")) }
        guard !parts.isEmpty else { return "" }
        return parts.joined(separator: " · ") + " NEARBY · SHOW"
    }
}
