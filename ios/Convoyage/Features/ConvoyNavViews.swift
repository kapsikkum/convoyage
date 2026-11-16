import SwiftUI
import CoreLocation
import MapKit

/// The manoeuvre card of the mockup: arrow, distance in mono, the street, and the step after. Shared by the navigation
/// screen and the convoy view, so turn-by-turn keeps going on the Drive tab.
struct ManeuverCard: View {
    let nav: NavigationSession
    @Environment(\.theme) private var theme

    private var arrived: Bool {
        return nav.phase == .arrived
    }

    private var symbol: String {
        if arrived { return Maneuver.symbol(type: "arrive", modifier: nil) }
        let step: NavStep? = nav.currentStep
        return Maneuver.symbol(type: step?.type ?? "depart", modifier: step?.modifier)
    }

    private var distance: String {
        let dist: Double = arrived ? 0 : (nav.progress?.distanceToManeuver ?? 0)
        return Formatters.distanceText(ManeuverCard.roundTo10(dist))
    }

    /// "Arrived at the pub" once there, otherwise the manoeuvre itself.
    private var headline: String {
        if arrived {
            guard let d = nav.destination else { return "You have arrived" }
            return "Arrived at " + d.title
        }
        guard let s = nav.currentStep else { return "Follow the route" }
        return Maneuver.title(for: s)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 14) {
                Image(systemName: symbol)
                    .font(.system(size: 38, weight: .bold))
                    .foregroundStyle(theme.signal)
                    .frame(width: 56, height: 56)
                VStack(alignment: .leading, spacing: 2) {
                    Text(distance)
                        .font(CvFont.mono(34))
                        .foregroundStyle(theme.signal)
                    Text(headline)
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
                    Text("then " + ManeuverCard.thenText(next))
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

    private static func thenText(_ s: NavStep) -> String {
        let v: String = Maneuver.verb(type: s.type, modifier: s.modifier)
        return s.name.isEmpty ? v : "\(v) \u{00B7} \(s.name)"
    }

    private static func roundTo10(_ m: Double) -> Double {
        return m >= 100 ? (m / 10).rounded() * 10 : m.rounded()
    }
}

/// On a detour: where we carry on to afterwards, and the button to do it (now, or once we have arrived).
struct DetourBanner: View {
    let nav: NavigationSession

    var body: some View {
        if let back = nav.resumeTo {
            switch nav.phase {
            case .arrived:
                Banner(title: "Continue with the route?",
                       subtitle: "On to " + back.title,
                       kind: .hazard,
                       icon: "arrow.triangle.turn.up.right.diamond",
                       actionTitle: "Continue", action: { nav.resume() },
                       secondaryTitle: "End", secondaryAction: { nav.end() })
            case .navigating:
                Banner(title: "Stop on the way",
                       subtitle: "Then on to " + back.title,
                       kind: .hazard,
                       icon: "arrow.triangle.turn.up.right.diamond",
                       actionTitle: "Skip", action: { nav.resume() })
            default:
                EmptyView()
            }
        }
    }
}

/// The always-on search inside a convoy: a compact bar that opens into the field and its results. A pick sets the
/// convoy's destination (or the stop to head for next); closing it clears the query.
struct ConvoySearch: View {
    let label: String
    /// True while the field is open (keyboard up): the convoy screen clears its map furniture then.
    var onEditing: (Bool) -> Void = { (_: Bool) in }
    @Environment(AppState.self) private var app
    @Environment(\.theme) private var theme
    @State private var open: Bool = false
    @State private var query: String = ""

    var body: some View {
        Group {
            if open {
                panel
            } else {
                chip
            }
        }
        .onAppear {
            app.nav.search.bias(to: app.convoy.location.latest?.coordinate)
        }
        .onChange(of: open) { (_: Bool, now: Bool) in
            onEditing(now)
        }
        .onDisappear {
            onEditing(false)
        }
    }

    private var chip: some View {
        Button {
            open = true
        } label: {
            HStack(spacing: 10) {
                Image(systemName: "magnifyingglass")
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundStyle(theme.signal)
                Text(label)
                    .font(CvFont.text(16))
                    .foregroundStyle(theme.inkMuted)
                    .lineLimit(1)
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 14)
            .frame(maxWidth: .infinity, minHeight: 50)
            .background(.ultraThinMaterial, in: ChamferShape(cut: Cut.m))
            .cvPanel(fill: theme.glass, edge: theme.lineStrong, cut: Cut.m)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
    }

    private var panel: some View {
        let binding: Binding<String> = Binding<String>(
            get: { return query },
            set: { (v: String) in
                query = v
                app.nav.search.setQuery(v)
            }
        )
        return VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 10) {
                CvField(placeholder: "Where to?", text: binding, autocap: .words)
                Button {
                    close()
                } label: {
                    Image(systemName: "xmark")
                        .font(.system(size: 18, weight: .semibold))
                        .foregroundStyle(theme.ink)
                        .frame(width: 52, height: 52)
                        .cvPanel(fill: theme.surface200, edge: theme.lineStrong, cut: Cut.s)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Close search")
            }
            if !query.isEmpty {
                results
            }
        }
        .padding(8)
        .cvPanel(fill: theme.surface200, edge: theme.signal, cut: Cut.m)
    }

    private var results: some View {
        let rows: [MKLocalSearchCompletion] = Array(app.nav.search.results.prefix(6))
        return VStack(spacing: 0) {
            if rows.isEmpty {
                Text(app.nav.search.failed ? "Search is not available right now." : "Searching...")
                    .font(CvFont.text(13))
                    .foregroundStyle(theme.inkMuted)
                    .padding(14)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            ForEach(0..<rows.count, id: \.self) { (i: Int) in
                let c: MKLocalSearchCompletion = rows[i]
                Button {
                    choose(c)
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
                    .padding(.horizontal, 6)
                    .frame(minHeight: 48)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
        }
    }

    private func choose(_ c: MKLocalSearchCompletion) {
        let nav: NavigationSession = app.nav
        let here: CLLocationCoordinate2D? = app.convoy.location.latest?.coordinate
        Task {
            let place: NavPlace? = await nav.search.resolve(c, near: here)
            if let p = place {
                app.pickInConvoy(p)
                query = ""
                nav.search.setQuery("")
                open = false
            } else {
                app.convoyHint = "Could not find that place"
            }
        }
    }

    private func close() {
        query = ""
        app.nav.search.setQuery("")
        open = false
    }
}
