import SwiftUI

/// Shown after sign-in while the account has no real display name (unset, or the generated `rider_xxxxxxxx`).
/// PATCHes /v1/me. Drive / ride / both has no server field yet, so it is kept on this device.
struct OnboardingView: View {
    @Environment(AppState.self) private var app
    @Environment(\.theme) private var theme
    @State private var name: String = ""
    @State private var handle: String = ""
    @State private var mode: String = "both"
    @State private var busy: Bool = false
    @State private var errorText: String?

    private var canContinue: Bool {
        return !busy && !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                VStack(alignment: .leading, spacing: 6) {
                    SectionLabel(text: "Welcome")
                    Text("What should the convoy call you?")
                        .font(CvFont.display(32))
                    Text("This is the name on the roster and in chat. You can change it later in Profile.")
                        .font(CvFont.text(14))
                        .foregroundStyle(theme.inkMuted)
                }

                VStack(alignment: .leading, spacing: 8) {
                    SectionLabel(text: "Name")
                    CvField(placeholder: "Alex", text: $name, autocap: .words)
                }

                VStack(alignment: .leading, spacing: 8) {
                    SectionLabel(text: "Handle (optional)")
                    CvField(placeholder: "alex_r", text: $handle, mono: true)
                    Text("Letters, digits and underscores, 3 to 30. Leave empty to keep the one we made.")
                        .font(CvFont.text(12))
                        .foregroundStyle(theme.inkMuted)
                }

                VStack(alignment: .leading, spacing: 8) {
                    SectionLabel(text: "You mostly")
                    Segmented(items: ["drive", "ride", "both"],
                              title: { (m: String) -> String in return m.capitalized },
                              selection: $mode)
                    Text(mode == "drive" ? "Four wheels."
                         : mode == "ride" ? "Two wheels. Crash detection is in Ride mode (off until you turn it on)."
                         : "Both. Crash detection is in Ride mode (off until you turn it on).")
                        .font(CvFont.text(12))
                        .foregroundStyle(theme.inkMuted)
                }

                if let e = errorText {
                    Banner(title: e, kind: .pulse, icon: "exclamationmark.triangle")
                }

                CvButton(title: busy ? "Saving..." : "Continue") {
                    busy = true
                    errorText = nil
                    Task {
                        let err: String? = await app.completeOnboarding(name: name, handle: handle, mode: mode)
                        busy = false
                        errorText = err
                    }
                }
                .opacity(canContinue ? 1 : 0.5)
                .disabled(!canContinue)

                CvButton(title: "Skip for now", kind: .ghost) { app.skipOnboarding() }
                    .disabled(busy)
            }
            .padding(24)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .scrollDismissesKeyboard(.interactively)
        .background(theme.void.ignoresSafeArea())
        .foregroundStyle(theme.ink)
        .onAppear {
            mode = app.rideMode
            let existing: String = app.me?.displayName ?? ""
            if !Formatters.isGeneratedName(existing) { name = existing }
        }
    }
}
