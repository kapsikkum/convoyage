import SwiftUI

struct RootView: View {
    @Environment(AppState.self) private var app
    @Environment(\.theme) private var theme

    var body: some View {
        let tabBinding: Binding<AppTab> = Binding<AppTab>(
            get: { app.tab },
            set: { (newValue: AppTab) in app.tab = newValue }
        )
        let navBinding: Binding<Bool> = Binding<Bool>(
            get: { app.showNav },
            set: { (newValue: Bool) in app.showNav = newValue }
        )
        let rideBinding: Binding<Bool> = Binding<Bool>(
            get: { app.showRide },
            set: { (newValue: Bool) in app.showRide = newValue }
        )

        ZStack(alignment: .bottom) {
            theme.void.ignoresSafeArea()
                .fullScreenCover(isPresented: navBinding) {
                    NavigationScreen()
                }
            if app.isSignedIn {
                Group {
                    switch app.tab {
                    case .home: HomeView()
                    case .feed: FeedView()
                    case .convoy: ConvoyView()
                    case .garage: GarageView()
                    case .profile: ProfileView()
                    }
                }
                VStack(spacing: 8) {
                    if app.nav.phase != .idle && !app.showNav && (app.tab == .home || app.tab == .convoy) {
                        NavMiniBar()
                    }
                    TabBar(selection: tabBinding)
                }
                .padding(.horizontal, 12)
                .padding(.bottom, 8)
            } else {
                WelcomeView()
            }
            if app.needsOnboarding {
                OnboardingView()
                    .transition(.opacity)
            }
            AppOverlays()
        }
        .foregroundStyle(theme.ink)
        .fullScreenCover(isPresented: rideBinding) {
            RideView()
        }
    }
}

/// Shared page chrome for the simple tabs.
struct PageScaffold<Content: View>: View {
    let eyebrow: String
    let title: String
    let content: Content
    @Environment(\.theme) private var theme

    init(eyebrow: String, title: String, @ViewBuilder content: () -> Content) {
        self.eyebrow = eyebrow
        self.title = title
        self.content = content()
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                VStack(alignment: .leading, spacing: 4) {
                    SectionLabel(text: eyebrow)
                    Text(title)
                        .font(CvFont.display(30))
                        .foregroundStyle(theme.ink)
                }
                content
            }
            .padding(.horizontal, 16)
            .padding(.top, 16)
            .padding(.bottom, 120)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .background(theme.surface100.ignoresSafeArea())
    }
}

/// The slim "navigating" bar that stays on Home and Convoy after the driving screen is minimised: the next
/// manoeuvre and the arrival time. Tapping it reopens navigation.
struct NavMiniBar: View {
    @Environment(AppState.self) private var app
    @Environment(\.theme) private var theme

    var body: some View {
        let nav: NavigationSession = app.nav
        let step: NavStep? = nav.currentStep
        let seconds: Double = nav.progress?.remainingDuration ?? nav.route?.durationS ?? 0
        let title: String = miniTitle(nav, step)
        Button {
            app.showNav = true
        } label: {
            HStack(spacing: 10) {
                Image(systemName: miniSymbol(nav, step))
                    .font(.system(size: 20, weight: .bold))
                    .foregroundStyle(theme.signal)
                    .frame(width: 28)
                VStack(alignment: .leading, spacing: 1) {
                    Text(title)
                        .font(CvFont.text(14, weight: .bold))
                        .lineLimit(1)
                    Text(miniSubtitle(nav, seconds))
                        .font(CvFont.text(11))
                        .foregroundStyle(theme.inkMuted)
                        .lineLimit(1)
                }
                Spacer(minLength: 6)
                Image(systemName: "chevron.up")
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(theme.inkMuted)
            }
            .padding(.horizontal, 12)
            .frame(minHeight: 48)
            .foregroundStyle(theme.ink)
            .background(.ultraThinMaterial)
            .cvPanel(fill: theme.glass, edge: theme.signal, cut: Cut.s)
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Navigating. Open navigation")
    }

    private func miniSymbol(_ nav: NavigationSession, _ step: NavStep?) -> String {
        if nav.phase == .navigating, let s = step {
            return Maneuver.symbol(type: s.type, modifier: s.modifier)
        }
        if nav.phase == .arrived { return "flag.checkered" }
        return "location.north.fill"
    }

    private func miniTitle(_ nav: NavigationSession, _ step: NavStep?) -> String {
        switch nav.phase {
        case .routing: return "Finding the route..."
        case .arrived: return "Arrived"
        default:
            guard let s = step else { return "Navigating" }
            let dist: Double = nav.progress?.distanceToManeuver ?? 0
            let rounded: Double = dist >= 100 ? (dist / 10).rounded() * 10 : dist.rounded()
            return Formatters.distanceText(rounded) + "  " + Maneuver.title(for: s)
        }
    }

    private func miniSubtitle(_ nav: NavigationSession, _ seconds: Double) -> String {
        let place: String = nav.destination?.title ?? "Navigating"
        if nav.phase == .navigating {
            return "Arrive " + NavFormat.arrival(after: seconds) + "  /  " + place
        }
        return place
    }
}
