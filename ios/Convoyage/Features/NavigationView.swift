import SwiftUI
import UIKit
import CoreLocation
import MapKit

/// Turn-by-turn navigation: pick a place, see the route, follow it.
struct NavigationScreen: View {
    @Environment(AppState.self) private var app
    @Environment(\.theme) private var theme
    @State private var query: String = ""
    @State private var picked: NavPlace?
    @State private var resolving: Bool = false
    @State private var showReport: Bool = false
    @State private var showInvite: Bool = false
    @State private var fitToken: Int = 0
    @State private var recenter: Int = 0

    private var hazardPins: [MapPin] {
        return app.hazards.active.map { (h: HazardDto) -> MapPin in
            return MapPin(id: "hazard-" + h.id,
                          initials: h.isPolice ? "P" : "!",
                          kind: h.isPolice ? .police : .hazard,
                          coordinate: CLLocationCoordinate2D(latitude: h.lat, longitude: h.lng))
        }
    }

    /// The place being looked at (search result or long press) or the active destination, as a flag on the map.
    private var destinationPins: [MapPin] {
        let nav: NavigationSession = app.nav
        var place: NavPlace? = picked
        if nav.phase != .idle { place = nav.destination }
        guard let p = place else { return [] }
        return [MapPin(id: "nav-destination", initials: "", kind: .destination, coordinate: p.coordinate, symbol: "flag.checkered")]
    }

    var body: some View {
        let nav: NavigationSession = app.nav
        let vm: ConvoyViewModel = app.convoy
        ZStack(alignment: .top) {
            ConvoyMapView(pins: vm.mapPins + hazardPins + destinationPins,
                          is3D: nav.phase == .navigating,
                          recenterToken: recenter,
                          theme: theme,
                          userCoordinate: vm.location.latest?.coordinate,
                          route: nav.routeCoordinates,
                          onViewport: { (box: GeoBox) in
                              Task { await app.hazards.refresh(box: box) }
                          },
                          onLongPress: { (c: CLLocationCoordinate2D) in
                              longPressed(c)
                          },
                          follow: nav.followCamera,
                          fitToken: fitToken,
                          fitCoordinates: nav.routeCoordinates)
                .ignoresSafeArea()

            switch nav.phase {
            case .idle:
                searchLayer(nav)
            case .routing:
                routingLayer(nav)
            case .navigating:
                drivingLayer(nav, vm)
            case .arrived:
                arrivedLayer(nav)
            }
        }
        .background(theme.void.ignoresSafeArea())
        .foregroundStyle(theme.ink)
        .overlay { AppOverlays() }
        .sheet(isPresented: $showReport) {
            ReportView()
        }
        .sheet(isPresented: $showInvite) {
            InviteSheet()
                .presentationDetents([.large])
        }
        .onAppear {
            UIApplication.shared.isIdleTimerDisabled = true
            nav.search.bias(to: vm.location.latest?.coordinate)
            app.updateServices()
        }
        .onDisappear {
            UIApplication.shared.isIdleTimerDisabled = false
        }
        .task {
            // Keep the hazard pins fresh while the map is up.
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 60_000_000_000)
                await app.hazards.refreshLast()
            }
        }
    }

    // MARK: Search

    private func longPressed(_ c: CLLocationCoordinate2D) {
        if app.nav.phase != .idle { return }
        resolving = true
        Task {
            let place: NavPlace = await DestinationSearch.reverse(c)
            picked = place
            resolving = false
        }
    }

    private func searchLayer(_ nav: NavigationSession) -> some View {
        let queryBinding: Binding<String> = Binding<String>(
            get: { return query },
            set: { (v: String) in
                query = v
                nav.search.setQuery(v)
            }
        )
        return VStack(spacing: 10) {
            HStack(spacing: 10) {
                CvField(placeholder: "Where to?", text: queryBinding, autocap: .words)
                closeButton()
            }
            if let e = nav.errorText {
                Banner(title: e, kind: .hazard, icon: "exclamationmark.triangle",
                       actionTitle: "OK", action: { nav.errorText = nil })
            }
            if picked == nil && !query.isEmpty {
                resultsList(nav)
            } else if picked == nil {
                Text("Search for an address or place, or press and hold on the map to drop a pin.")
                    .font(CvFont.text(12))
                    .foregroundStyle(theme.inkMuted)
                    .padding(10)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .cvPanel(fill: theme.glass, edge: theme.line, cut: Cut.s)
            }
            Spacer(minLength: 0)
            if let p = picked {
                pickedCard(p, nav)
            } else if resolving {
                Banner(title: "Looking up that place...", kind: .info, icon: "mappin.and.ellipse")
            }
        }
        .padding(.horizontal, 12)
        .padding(.top, DeviceInsets.top + 8)
        .padding(.bottom, 16)
    }

    private func resultsList(_ nav: NavigationSession) -> some View {
        let rows: [MKLocalSearchCompletion] = Array(nav.search.results.prefix(6))
        return VStack(spacing: 0) {
            if rows.isEmpty {
                Text(nav.search.failed ? "Search is not available right now." : "Searching...")
                    .font(CvFont.text(13))
                    .foregroundStyle(theme.inkMuted)
                    .padding(14)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            ForEach(0..<rows.count, id: \.self) { (i: Int) in
                let c: MKLocalSearchCompletion = rows[i]
                Button {
                    choose(c, nav)
                } label: {
                    HStack(spacing: 12) {
                        Image(systemName: "mappin.circle.fill")
                            .font(.system(size: 20))
                            .foregroundStyle(theme.signal)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(c.title).font(CvFont.text(15, weight: .bold)).lineLimit(1)
                            if !c.subtitle.isEmpty {
                                Text(c.subtitle).font(CvFont.text(12)).foregroundStyle(theme.inkMuted).lineLimit(1)
                            }
                        }
                        Spacer(minLength: 0)
                    }
                    .padding(.horizontal, 14)
                    .frame(minHeight: 52)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
        }
        .cvPanel(fill: theme.glass, edge: theme.lineStrong, cut: Cut.m)
    }

    private func choose(_ c: MKLocalSearchCompletion, _ nav: NavigationSession) {
        resolving = true
        let here: CLLocationCoordinate2D? = app.convoy.location.latest?.coordinate
        Task {
            let place: NavPlace? = await nav.search.resolve(c, near: here)
            resolving = false
            if let p = place {
                picked = p
                query = ""
                nav.search.setQuery("")
            } else {
                nav.errorText = "Could not find that place"
            }
        }
    }

    private func pickedCard(_ p: NavPlace, _ nav: NavigationSession) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                SectionLabel(text: "Destination")
                Text(p.title).font(CvFont.display(22)).lineLimit(2)
                if !p.subtitle.isEmpty {
                    Text(p.subtitle).font(CvFont.text(13)).foregroundStyle(theme.inkMuted).lineLimit(2)
                }
            }
            HStack(spacing: 10) {
                CvButton(title: "Cancel", kind: .ghost) { picked = nil }
                CvButton(title: "Go", icon: "location.north.fill") {
                    nav.start(to: p)
                    picked = nil
                }
            }
        }
        .padding(16)
        .cvPanel(fill: theme.surface200, edge: theme.signal, cut: Cut.l)
    }

    // MARK: Routing

    private func routingLayer(_ nav: NavigationSession) -> some View {
        VStack(spacing: 10) {
            Banner(title: "Finding the route...", subtitle: nav.destination.map { (d: NavPlace) -> String in return "To " + d.title },
                   kind: .info, icon: "location.north.line.fill", actionTitle: "Cancel", action: { nav.end() })
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 12)
        .padding(.top, DeviceInsets.top + 8)
    }

    // MARK: Driving

    private func drivingLayer(_ nav: NavigationSession, _ vm: ConvoyViewModel) -> some View {
        ZStack {
            VStack(spacing: 8) {
                maneuverCard(nav)
                if let h = nav.hazardAhead {
                    let dto: HazardDto = h.hazard
                    Banner(title: "\(hazardLabel(kind: dto.kind, subkind: dto.subkind)) ahead",
                           subtitle: Formatters.distanceText(roundTo10(h.meters)) + " on your route",
                           kind: dto.isPolice ? .police : .hazard,
                           icon: dto.isPolice ? "shield.lefthalf.filled" : "exclamationmark.triangle.fill",
                           actionTitle: "OK", action: { nav.dismissHazard() })
                }
                HazardAlertBanner()
                StatusBanners()
                if let e = nav.errorText {
                    Banner(title: e, kind: .hazard, icon: "exclamationmark.triangle",
                           actionTitle: "OK", action: { nav.errorText = nil })
                }
                if nav.rerouting {
                    Banner(title: "Finding a new route...", kind: .info, icon: "arrow.triangle.2.circlepath")
                }
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 12)
            .padding(.top, DeviceInsets.top + 8)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)

            VStack(spacing: 10) {
                railButton("chevron.down", tint: theme.ink, label: "Minimise navigation") { app.showNav = false }
                railButton(nav.muted ? "speaker.slash.fill" : "speaker.wave.2.fill",
                           tint: nav.muted ? theme.inkMuted : theme.ink,
                           label: nav.muted ? "Unmute voice" : "Mute voice") { nav.setMuted(!nav.muted) }
                railButton("exclamationmark.triangle", tint: theme.hazard, label: "Report a hazard") { showReport = true }
                railButton(nav.overview ? "location.north.fill" : "map.fill",
                           tint: theme.signal,
                           label: nav.overview ? "Follow my position" : "Show the whole route") {
                    nav.overview.toggle()
                    if nav.overview { fitToken += 1 }
                }
            }
            .padding(.trailing, 12)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .trailing)

            VStack {
                Spacer(minLength: 0)
                etaSheet(nav, vm)
            }
        }
    }

    private func roundTo10(_ m: Double) -> Double {
        return m >= 100 ? (m / 10).rounded() * 10 : m.rounded()
    }

    private func maneuverCard(_ nav: NavigationSession) -> some View {
        let step: NavStep? = nav.currentStep
        let dist: Double = nav.progress?.distanceToManeuver ?? 0
        return VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 14) {
                Image(systemName: Maneuver.symbol(type: step?.type ?? "depart", modifier: step?.modifier))
                    .font(.system(size: 38, weight: .bold))
                    .foregroundStyle(theme.signal)
                    .frame(width: 56, height: 56)
                VStack(alignment: .leading, spacing: 2) {
                    Text(Formatters.distanceText(roundTo10(dist)))
                        .font(CvFont.mono(34))
                        .foregroundStyle(theme.signal)
                    Text(step.map { (s: NavStep) -> String in return Maneuver.title(for: s) } ?? "Follow the route")
                        .font(CvFont.display(18))
                        .lineLimit(2)
                }
                Spacer(minLength: 0)
            }
            if let next = nav.followingStep {
                HStack(spacing: 8) {
                    Image(systemName: Maneuver.symbol(type: next.type, modifier: next.modifier))
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(theme.inkMuted)
                    Text("then " + thenText(next))
                        .font(CvFont.text(13))
                        .foregroundStyle(theme.inkMuted)
                        .lineLimit(1)
                    Spacer(minLength: 0)
                }
                .padding(.top, 8)
                .overlay(alignment: .top) {
                    Rectangle().fill(theme.line).frame(height: 1)
                }
            }
        }
        .padding(14)
        .cvPanel(fill: theme.surface200, edge: theme.signal, cut: Cut.l)
        .shadow(color: Color.black.opacity(0.4), radius: 12, y: 6)
    }

    private func thenText(_ s: NavStep) -> String {
        let v: String = Maneuver.verb(type: s.type, modifier: s.modifier)
        return s.name.isEmpty ? v : "\(v) \u{00B7} \(s.name)"
    }

    private func railButton(_ symbol: String, tint: Color, label: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: symbol)
                .font(.system(size: 20, weight: .semibold))
                .foregroundStyle(tint)
                .frame(width: 48, height: 48)
                .cvPanel(fill: theme.glass, edge: tint == theme.hazard ? theme.hazard : theme.lineStrong, cut: Cut.s)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
    }

    private func etaSheet(_ nav: NavigationSession, _ vm: ConvoyViewModel) -> some View {
        let p: NavProgress? = nav.progress
        let remaining: Double = p?.remainingDistance ?? nav.route?.distanceM ?? 0
        let seconds: Double = p?.remainingDuration ?? nav.route?.durationS ?? 0
        let time: (value: String, unit: String) = NavFormat.remainingTime(seconds)
        let dist: (value: String, unit: String) = Formatters.distance(remaining)
        let shape: TopLeftChamferShape = TopLeftChamferShape(cut: Cut.l)
        return VStack(spacing: 12) {
            Capsule().fill(theme.lineStrong).frame(width: 40, height: 4)
            HStack(alignment: .bottom) {
                Readout(caption: "Arrive", value: NavFormat.arrival(after: seconds), color: theme.signal)
                Spacer()
                Readout(caption: "Time", value: time.value, unit: time.unit)
                Spacer()
                Readout(caption: "Distance", value: dist.value, unit: dist.unit)
            }
            if let d = nav.destination {
                Text("To " + d.title)
                    .font(CvFont.text(12))
                    .foregroundStyle(theme.inkMuted)
                    .lineLimit(1)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            HStack(spacing: 12) {
                CvButton(title: "Invite friends", icon: "person.2.fill", kind: .ghost) { invite(nav, vm) }
                CvButton(title: "End", kind: .alert) {
                    nav.end()
                }
            }
        }
        .padding(.horizontal, 16)
        .padding(.top, 10)
        .padding(.bottom, DeviceInsets.bottom + 8)
        .frame(maxWidth: .infinity)
        .background(.ultraThinMaterial, in: shape)
        .background(shape.fill(theme.glass))
        .overlay(shape.stroke(theme.lineStrong, lineWidth: 1))
        .clipShape(shape)
    }

    /// Starts a convoy for this trip (or reuses mine), keeps navigating and opens the share sheet.
    private func invite(_ nav: NavigationSession, _ vm: ConvoyViewModel) {
        if let d = nav.destination {
            vm.startConvoyForNavigation(place: d, line: nav.routeCoordinates)
        }
        showInvite = true
    }

    // MARK: Arrived

    private func arrivedLayer(_ nav: NavigationSession) -> some View {
        VStack {
            Spacer(minLength: 0)
            VStack(alignment: .leading, spacing: 12) {
                SectionLabel(text: "Arrived")
                Text(nav.destination?.title ?? "You have arrived").font(CvFont.display(26))
                CvButton(title: "Done") {
                    nav.end()
                    app.showNav = false
                }
            }
            .padding(16)
            .cvPanel(fill: theme.surface200, edge: theme.go, cut: Cut.l)
            .padding(.horizontal, 12)
            .padding(.bottom, DeviceInsets.bottom + 12)
        }
    }

    // MARK: Chrome

    private func closeButton() -> some View {
        Button {
            app.showNav = false
        } label: {
            Image(systemName: "xmark")
                .font(.system(size: 18, weight: .semibold))
                .frame(width: 52, height: 52)
                .cvPanel(fill: theme.surface200, edge: theme.lineStrong, cut: Cut.s)
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Close")
    }
}

/// The share sheet that opens from "Invite friends": waits for the convoy to exist, then shows the code, QR and link.
struct InviteSheet: View {
    @Environment(AppState.self) private var app
    @Environment(\.theme) private var theme
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack {
                VStack(alignment: .leading, spacing: 4) {
                    SectionLabel(text: "Convoy")
                    Text(app.convoy.room?.name ?? "Starting a convoy...").font(CvFont.display(26)).lineLimit(1)
                }
                Spacer()
                Button {
                    dismiss()
                } label: {
                    Image(systemName: "xmark")
                        .font(.system(size: 18, weight: .semibold))
                        .frame(width: 48, height: 48)
                        .cvPanel(fill: theme.surface200, edge: theme.lineStrong, cut: Cut.s)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Close")
            }
            if app.convoy.room == nil {
                Banner(title: "Starting the convoy...", subtitle: "Navigation keeps going.", kind: .info, icon: "person.3.fill")
            } else {
                Text("Friends who join see your destination and the route.")
                    .font(CvFont.text(13))
                    .foregroundStyle(theme.inkMuted)
                SharePanel()
            }
            Spacer(minLength: 0)
        }
        .padding(20)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background(theme.surface100.ignoresSafeArea())
        .foregroundStyle(theme.ink)
    }
}
