import SwiftUI
import CoreLocation

/// Saved routes: drives you can lead again. Mine, or shared by others near me; open one to drive it as a convoy.
struct RoutesView: View {
    @Environment(AppState.self) private var app
    @Environment(\.theme) private var theme
    @Environment(\.dismiss) private var dismiss
    @State private var tab: Int = 0
    @State private var radiusKm: Int = 50
    @State private var found: [DiscoveredRouteDto]?
    @State private var foundError: String?
    @State private var open: SavedRouteDto?
    @State private var openFound: DiscoveredRouteDto?
    @State private var importing: Bool = false
    @State private var building: Bool = false

    var body: some View {
        let store: SavedRoutesStore = app.savedRoutes
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        SectionLabel(text: "Saved routes · \(store.routes.count)")
                        Text("Routes").font(CvFont.display(26))
                    }
                    Spacer()
                    CvButton(title: "Done", kind: .ghost) { dismiss() }
                        .frame(width: 100)
                }
                Picker("", selection: $tab) {
                    Text("Mine").tag(0)
                    Text("Near me").tag(1)
                }
                .pickerStyle(.segmented)
                if tab == 0 {
                    if let e = store.error {
                        Banner(title: "Could not load your routes", subtitle: e, kind: .hazard, icon: "exclamationmark.triangle",
                               actionTitle: "Retry", action: { Task { await store.refresh() } })
                    }
                    if !store.loaded && store.error == nil { SectionLabel(text: "Loading...") }
                    ForEach(store.routes) { (r: SavedRouteDto) in
                        row(r.name, r.stats) { open = r }
                    }
                    if store.loaded && store.routes.isEmpty {
                        Text("No saved routes yet. Draw one on the map, or bring your PhotoScout routes across.")
                            .font(CvFont.text(14)).foregroundStyle(theme.inkMuted)
                    }
                    CvButton(title: "Build a route", icon: "point.topleft.down.curvedto.point.bottomright.up") { building = true }
                    if app.photoscout.available {
                        CvButton(title: "Import from PhotoScout", icon: "square.and.arrow.down", kind: .ghost) { importing = true }
                    }
                } else {
                    HStack(spacing: 8) {
                        ForEach([25, 50, 150], id: \.self) { (km: Int) in
                            CvButton(title: "\(km) km", kind: radiusKm == km ? .primary : .ghost) { radiusKm = km }
                        }
                    }
                    if let e = foundError {
                        Text(e).font(CvFont.text(13)).foregroundStyle(theme.pulse)
                    }
                    if let list = found {
                        if list.isEmpty {
                            Text("Nobody has shared a route starting within \(radiusKm) km yet. Make yours public and it shows up here for others.")
                                .font(CvFont.text(14)).foregroundStyle(theme.inkMuted)
                        }
                        ForEach(list) { (d: DiscoveredRouteDto) in
                            let by: String = d.owner?.handle.map { "@" + $0 + " · " } ?? ""
                            let away: String = d.distanceM.map { Formatters.distanceText($0) + " away · " } ?? ""
                            row(d.route.name, by + away + (d.character ?? "relaxed")) { openFound = d }
                        }
                    } else if foundError == nil {
                        SectionLabel(text: "Looking nearby...")
                    }
                }
            }
            .padding(20)
        }
        .background(theme.void.ignoresSafeArea())
        .task { await store.refresh() }
        .task(id: "\(tab)-\(radiusKm)") { await discover() }
        .sheet(item: $open) { (r: SavedRouteDto) in
            RouteDetailSheet(route: r, found: nil) { dismiss() }
                .presentationDetents([.large])
        }
        .sheet(item: $openFound) { (d: DiscoveredRouteDto) in
            RouteDetailSheet(route: d.route, found: d) { dismiss() }
                .presentationDetents([.large])
        }
        .fullScreenCover(isPresented: $building) {
            RouteBuilderView()
        }
        .sheet(isPresented: $importing) {
            PhotoScoutImportSheet()
                .presentationDetents([.medium, .large])
        }
    }

    private func row(_ title: String, _ sub: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack {
                VStack(alignment: .leading, spacing: 3) {
                    Text(title).font(CvFont.text(16, weight: .bold)).foregroundStyle(theme.ink)
                    Text(sub).font(CvFont.text(13)).foregroundStyle(theme.inkMuted).lineLimit(1)
                }
                Spacer()
                Image(systemName: "chevron.right").foregroundStyle(theme.inkMuted)
            }
            .padding(14)
            .frame(maxWidth: .infinity)
            .cvPanel(fill: theme.surface200, edge: theme.line)
        }
        .buttonStyle(.plain)
    }

    private func discover() async {
        guard tab == 1 else { return }
        guard let here = app.convoy.location.latest else {
            foundError = "Waiting for your location"
            return
        }
        found = nil
        foundError = nil
        switch await app.api.discoverRoutes(lat: here.coordinate.latitude, lng: here.coordinate.longitude, radiusKm: radiusKm) {
        case .ok(let list): found = list
        case .failure(let f): foundError = f.message
        }
    }
}

/// One route: its map, stats and notes; drive it as a convoy, change who sees it, delete it or save a copy.
struct RouteDetailSheet: View {
    let route: SavedRouteDto
    let found: DiscoveredRouteDto?
    /// Close the whole Routes screen (after Drive).
    let onDrive: () -> Void
    @Environment(AppState.self) private var app
    @Environment(\.theme) private var theme
    @Environment(\.dismiss) private var dismiss
    @State private var error: String?
    @State private var visibility: String = ""
    @State private var confirmDelete: Bool = false
    @State private var saved: Bool = false
    @State private var conditions: RouteConditionsStore?

    var body: some View {
        let line: [CLLocationCoordinate2D] = route.line
        let inConvoy: Bool = app.convoy.room != nil
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Text(route.name).font(CvFont.display(22)).lineLimit(2)
                if let f = found {
                    SectionLabel(text: "By @" + (f.owner?.handle ?? "driver") + " · " + (f.character ?? "relaxed"))
                }
                Text(route.stats).font(CvFont.text(13)).foregroundStyle(theme.inkMuted)
                if let n = route.notes, !n.isEmpty { Text(n).font(CvFont.text(15)) }
                ConvoyMapView(pins: pins(line, conditions?.value), is3D: false, recenterToken: 0, theme: theme,
                              route: line, roadSegments: roadSegments, fitToken: 1,
                              fitCoordinates: line + (route.staging.map { [CLLocationCoordinate2D(latitude: $0.lat, longitude: $0.lng)] } ?? []))
                    .frame(height: 220)
                    .allowsHitTesting(false)
                if let c = conditions {
                    ConditionsCard(store: c) {
                        Task { await c.refresh(routeId: route.id) }
                    }
                }
                if let e = error { Text(e).font(CvFont.text(13)).foregroundStyle(theme.pulse) }
                CvButton(title: inConvoy ? "Share with the convoy" : "Drive as a convoy", icon: "person.3.fill") {
                    error = app.convoy.driveSavedRoute(route)
                    if error == nil {
                        app.tab = .convoy
                        dismiss()
                        onDrive()
                    }
                }
                if route.staging != nil {
                    Text("Everyone is sent to the staging point first. Roll out from the leader tools when you're all there.")
                        .font(CvFont.text(12)).foregroundStyle(theme.inkMuted)
                }
                if found == nil {
                    SectionLabel(text: "Who can see it")
                    HStack(spacing: 8) {
                        ForEach([("private", "Only me"), ("followers", "Followers"), ("public", "Everyone")], id: \.0) { (v: (String, String)) in
                            CvButton(title: v.1, kind: visibility == v.0 ? .primary : .ghost) {
                                Task {
                                    if let e = await app.savedRoutes.setVisibility(route.id, v.0) { error = e } else { visibility = v.0 }
                                }
                            }
                        }
                    }
                    if confirmDelete {
                        HStack(spacing: 8) {
                            CvButton(title: "Keep it", kind: .ghost) { confirmDelete = false }
                            CvButton(title: "Delete", kind: .alert) {
                                Task {
                                    if let e = await app.savedRoutes.delete(route.id) { error = e; confirmDelete = false } else { dismiss() }
                                }
                            }
                        }
                    } else {
                        CvButton(title: "Delete route", icon: "trash", kind: .ghost) { confirmDelete = true }
                    }
                } else {
                    CvButton(title: saved ? "Saved to your routes" : "Save to my routes", icon: "bookmark", kind: .ghost) {
                        Task {
                            if let e = await app.savedRoutes.saveCopy(route) { error = e } else { saved = true }
                        }
                    }
                    .disabled(saved)
                }
            }
            .padding(20)
        }
        .background(theme.surface100.ignoresSafeArea())
        .onAppear { visibility = route.visibility ?? "followers" }
        .task {
            let store: RouteConditionsStore
            if let existing = conditions {
                store = existing
            } else {
                store = RouteConditionsStore(api: app.api)
                conditions = store
            }
            await store.loadSaved(routeId: route.id)
        }
    }

    private var roadSegments: [MapRoadSegment] {
        let segs: [(grade: String, line: [CLLocationCoordinate2D])] = conditions?.value?.roadSegments ?? []
        return segs.map { (s: (grade: String, line: [CLLocationCoordinate2D])) -> MapRoadSegment in
            return MapRoadSegment(grade: s.grade, line: s.line)
        }
    }

    private func pins(_ line: [CLLocationCoordinate2D], _ c: RouteConditionsDto?) -> [MapPin] {
        var out: [MapPin] = []
        if let s = line.first { out.append(MapPin(id: "start", initials: "GO", kind: .lead, coordinate: s)) }
        if let e = line.last, !route.isCircuit { out.append(MapPin(id: "end", initials: "END", kind: .destination, coordinate: e)) }
        if let st = route.staging {
            out.append(MapPin(id: "staging", initials: "MEET", kind: .rest, coordinate: CLLocationCoordinate2D(latitude: st.lat, longitude: st.lng)))
        }
        if let c = c {
            for p in c.conditionPins() {
                out.append(MapPin(id: p.id, initials: p.label, kind: p.kind == "police" ? .police : .hazard,
                                  coordinate: CLLocationCoordinate2D(latitude: p.lat, longitude: p.lng)))
            }
            for s in c.stopPins() {
                out.append(MapPin(id: s.id, initials: s.label, kind: .rest,
                                  coordinate: CLLocationCoordinate2D(latitude: s.lat, longitude: s.lng)))
            }
        }
        return out
    }
}

/// Bring routes saved in PhotoScout across.
struct PhotoScoutImportSheet: View {
    @Environment(AppState.self) private var app
    @Environment(\.theme) private var theme
    @State private var list: [PsRouteDto]?
    @State private var error: String?
    @State private var done: Set<String> = []

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Text("Import from PhotoScout").font(CvFont.display(22))
                if let e = error { Text(e).font(CvFont.text(13)).foregroundStyle(theme.pulse) }
                if let l = list {
                    if l.isEmpty { Text("No routes in PhotoScout yet.").foregroundStyle(theme.inkMuted) }
                    ForEach(l) { (r: PsRouteDto) in
                        HStack {
                            Text(r.name).font(CvFont.text(15, weight: .bold))
                            Spacer()
                            CvButton(title: done.contains(r.id) ? "Imported" : "Import", kind: done.contains(r.id) ? .ghost : .primary) {
                                Task {
                                    if let e = await app.savedRoutes.importFromPhotoScout(r.id) { error = e } else { done.insert(r.id) }
                                }
                            }
                            .frame(width: 130)
                            .disabled(done.contains(r.id))
                        }
                    }
                } else if error == nil {
                    SectionLabel(text: "Loading...")
                }
            }
            .padding(20)
        }
        .background(theme.surface100.ignoresSafeArea())
        .task {
            switch await app.api.photoscoutRoutes() {
            case .ok(let l): list = l
            case .failure(let f): error = f.message
            }
        }
    }
}
