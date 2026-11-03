import SwiftUI

/// Server URL setting, reachable from Welcome and Profile. Persisted; the default is a placeholder.
struct ServerSettingsView: View {
    @Environment(AppState.self) private var app
    @Environment(\.theme) private var theme
    @Environment(\.dismiss) private var dismiss
    @State private var text: String = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            VStack(alignment: .leading, spacing: 4) {
                SectionLabel(text: "Settings")
                Text("Server").font(CvFont.display(28))
            }

            VStack(alignment: .leading, spacing: 8) {
                SectionLabel(text: "Server URL")
                CvField(placeholder: ServerConfig.defaultURLString, text: $text, keyboard: .URL, mono: true)
                Text("Use http://192.168.x.x:8080 for a server on your LAN. HTTPS is recommended once it is set up.")
                    .font(CvFont.text(12))
                    .foregroundStyle(theme.inkMuted)
            }

            CvButton(title: "Save") {
                app.setServerURL(text)
                dismiss()
            }
            CvButton(title: "Use default (\(ServerConfig.defaultURLString))", kind: .ghost) {
                text = ServerConfig.defaultURLString
                app.setServerURL("")
                dismiss()
            }
            CvButton(title: "Cancel", kind: .ghost) { dismiss() }
            Spacer()
        }
        .padding(20)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background(theme.surface100.ignoresSafeArea())
        .foregroundStyle(theme.ink)
        .onAppear { text = app.serverURL }
    }
}
