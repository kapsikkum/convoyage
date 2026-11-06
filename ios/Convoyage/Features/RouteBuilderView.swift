import SwiftUI
import CoreLocation

/// Build a route on the map: tap to place points (snapped to the roads), set it as a circuit, mark where the convoy
/// gathers, name it and save. Port of Android's RouteBuilderScreen (without the road-surface overlay).
struct RouteBuilderView: View {
    @Environment(AppState.self) private var app
    @Environment(\.theme) private var theme
    @Environment(\.dismiss) private var dismiss
    @State private var points: [CLLocationCoordinate2D] = []
    @State private var staging: CLLocationCoordinate2D?
    @State private var placingStaging: Bool = false
    @State private var circuit: Bool = false
    @State private var followRoads: Bool = true
    @State private var name: String = ""
    @State private var road: RouteDto?
    @State private var roadLine: [CLLocationCoordinate2D] = []
    @State private var routing: Bool = false
    @State private var error: String?
    @State private var saving: Bool = false
    @State private var fitToken: Int = 0

    /// Snapping more points than this is left to straight lines (the router's limit).
    private static let maxSnapped: Int = 25

    private var straight: [CLLocationCoordinate2D] {
        if circuit, let first = points.first, points.count >= 2 { return points + [first] }
        return points
    }

    private var line: [CLLocationCoordinate2D] { return roadLine.count >= 2 ? roadLine : straight }

    private var snapKey: String {
        let p: String = points.map { (c: CLLocationCoordinate2D) -> String in
            return String(format: "%.5f,%.5f", c.latitude, c.longitude)
        }.joined(separator: ";")
        return p + "|\(circuit)|\(followRoads)"
    }

    var body: some View {
        ZStack(alignment: .top) {
            ConvoyMapView(pins: pins, is3D: false, recenterToken: 0, theme: theme,
                          userCoordinate: app.convoy.location.latest?.coordinate,
                          route: line,
                          onTap: { (c: CLLocationCoordinate2D) in tapped(c) },
                          fitToken: fitToken,
                          fitCoordinates: app.convoy.location.latest.map { [$0.coordinate] } ?? [])
                .ignoresSafeArea()
            HStack(spacing: 10) {
                CvButton(title: "Back", icon: "chevron.left", kind: .ghost) { dismiss() }
                    .frame(width: 110)
                Text(hint)
                    .font(CvFont.text(13))
                    .padding(12)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .cvPanel(fill: theme.glass, edge: theme.lineStrong, cut: Cut.s)
            }
            .padding(.horizontal, 12)
            .padding(.top, 8)
            VStack {
                Spacer()
                controls
            }
        }
        .background(theme.void.ignoresSafeArea())
        .onAppear { fitToken += 1 }
        .task(id: snapKey) { await snap() }
    }

    private var hint: String {
        if placingStaging { return "Tap where the convoy gathers." }
        if points.isEmpty { return "Tap the map to place the start." }
        if points.count == 1 { return "Tap to add the next point." }
        if routing { return "Following the roads..." }
        var s: String = "\(points.count) points"
        if let r = road { s += " · " + Formatters.distanceText(r.distanceM) + " · " + Formatters.duration(r.durationS) }
        return s
    }

    private var controls: some View {
        let canSave: Bool = points.count >= 2 && !name.trimmingCharacters(in: .whitespaces).isEmpty && !saving && !routing
        return VStack(alignment: .leading, spacing: 10) {
            CvField(placeholder: "Route name, e.g. Mount Panorama lap", text: $name, autocap: .sentences)
            Picker("", selection: $circuit) {
                Text("Point to point").tag(false)
                Text("Circuit").tag(true)
            }
            .pickerStyle(.segmented)
            HStack(spacing: 8) {
                CvButton(title: "Follow roads", kind: followRoads ? .primary : .ghost) { followRoads.toggle() }
                CvButton(title: staging != nil ? "Staging set" : "Staging point",
                         kind: (placingStaging || staging != nil) ? .primary : .ghost) {
                    if staging != nil && !placingStaging { staging = nil } else { placingStaging.toggle() }
                }
            }
            if followRoads && points.count > RouteBuilderView.maxSnapped {
                Text("Over \(RouteBuilderView.maxSnapped) points: the line is drawn straight between them.")
                    .font(CvFont.text(12)).foregroundStyle(theme.inkMuted)
            }
            if let e = error { Text(e).font(CvFont.text(12)).foregroundStyle(theme.pulse) }
            HStack(spacing: 8) {
                CvButton(title: "Undo", kind: .ghost) { if !points.isEmpty { points.removeLast() } }
                    .disabled(points.isEmpty)
                CvButton(title: "Reverse", kind: .ghost) { points.reverse() }
                    .disabled(points.count < 2)
                CvButton(title: "Clear", kind: .ghost) {
                    points = []
                    staging = nil
                }
                .disabled(points.isEmpty && staging == nil)
                CvButton(title: saving ? "Saving" : "Save") { Task { await save() } }
                    .disabled(!canSave)
                    .opacity(canSave ? 1 : 0.5)
            }
        }
        .padding(16)
        .background(theme.surface100.opacity(0.96), in: TopLeftChamferShape(cut: Cut.l))
    }

    private var pins: [MapPin] {
        var out: [MapPin] = []
        for (i, p) in points.enumerated() {
            let kind: MapPin.Kind = i == 0 ? .lead : ((i == points.count - 1 && !circuit) ? .destination : .member)
            out.append(MapPin(id: "w\(i)", initials: "\(i + 1)", kind: kind, coordinate: p))
        }
        if let s = staging { out.append(MapPin(id: "staging", initials: "MEET", kind: .rest, coordinate: s)) }
        return out
    }

    private func tapped(_ c: CLLocationCoordinate2D) {
        error = nil
        if placingStaging {
            staging = c
            placingStaging = false
        } else if points.count < 100 {
            points.append(c)
        }
    }

    /// Snap to the roads a moment after the last change.
    private func snap() async {
        road = nil
        roadLine = []
        guard followRoads, points.count >= 2, points.count <= RouteBuilderView.maxSnapped else { return }
        try? await Task.sleep(nanoseconds: 400_000_000)
        if Task.isCancelled { return }
        let from: CLLocationCoordinate2D = points[0]
        let to: CLLocationCoordinate2D = circuit ? points[0] : points[points.count - 1]
        let via: [CLLocationCoordinate2D] = circuit ? Array(points.dropFirst()) : Array(points.dropFirst().dropLast())
        routing = true
        let r: ApiResult<RouteDto> = await app.api.route(fromLng: from.longitude, fromLat: from.latitude,
                                                         toLng: to.longitude, toLat: to.latitude,
                                                         profile: app.routeProfile, via: via)
        if Task.isCancelled { return }
        routing = false
        switch r {
        case .ok(let dto):
            road = dto
            roadLine = Polyline.decode(dto.geometry, precision: dto.geometryPrecision ?? 6)
        case .failure(let f):
            error = "Could not follow the roads: " + f.message
        }
    }

    private func save() async {
        saving = true
        var body: [String: Any] = [
            "name": name.trimmingCharacters(in: .whitespaces),
            "waypoints": points.map { (c: CLLocationCoordinate2D) -> [Double] in return [c.longitude, c.latitude] },
            "type": circuit ? "circuit" : "sprint"
        ]
        if let r = road {
            body["geometry"] = r.geometry
            body["distanceM"] = r.distanceM
            body["durationS"] = r.durationS
        }
        if let s = staging { body["staging"] = ["lat": s.latitude, "lng": s.longitude] }
        switch await app.api.saveRoute(body: body) {
        case .ok:
            await app.savedRoutes.refresh()
            dismiss()
        case .failure(let f):
            error = f.message
            saving = false
        }
    }
}
