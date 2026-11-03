import SwiftUI

struct FeedView: View {
    @Environment(\.theme) private var theme

    var body: some View {
        PageScaffold(eyebrow: "Community", title: "Feed") {
            // TODO(feed): posts, ride recaps and photo spots.
            Text("The feed is coming soon.")
                .font(CvFont.text(15))
                .foregroundStyle(theme.inkMuted)
                .frame(maxWidth: .infinity, minHeight: 160)
                .cvPanel(fill: theme.surface200, edge: theme.line)
        }
    }
}

struct ProfileView: View {
    @Environment(AppState.self) private var app
    @Environment(\.theme) private var theme

    var body: some View {
        PageScaffold(eyebrow: "You", title: app.displayName) {
            // TODO(profile): avatar, stats, settings.
            VStack(alignment: .leading, spacing: 8) {
                SectionLabel(text: "Server")
                Text(ServerConfig.baseURL.absoluteString).font(CvFont.mono(13, weight: .medium))
                SectionLabel(text: "User id")
                Text(app.userId).font(CvFont.mono(11, weight: .medium)).foregroundStyle(theme.inkMuted)
            }
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .cvPanel(fill: theme.surface200, edge: theme.line)

            CvButton(title: "Sign out", kind: .ghost) { app.signOut() }
        }
    }
}
