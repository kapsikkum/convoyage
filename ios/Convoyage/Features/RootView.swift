import SwiftUI

struct RootView: View {
    @Environment(AppState.self) private var app
    @Environment(\.theme) private var theme

    var body: some View {
        let tabBinding: Binding<AppTab> = Binding<AppTab>(
            get: { app.tab },
            set: { (newValue: AppTab) in app.tab = newValue }
        )
        let rideBinding: Binding<Bool> = Binding<Bool>(
            get: { app.showRide },
            set: { (newValue: Bool) in app.showRide = newValue }
        )

        ZStack(alignment: .bottom) {
            theme.void.ignoresSafeArea()
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
                TabBar(selection: tabBinding)
                    .padding(.horizontal, 12)
                    .padding(.bottom, 8)
            } else {
                WelcomeView()
            }
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
