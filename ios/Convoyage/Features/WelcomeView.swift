import SwiftUI

struct WelcomeView: View {
    @Environment(AppState.self) private var app
    @Environment(\.theme) private var theme
    @State private var name: String = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 10) {
                ChamferShape(cut: 8)
                    .fill(theme.signal)
                    .frame(width: 28, height: 28)
                Text("CONVOYAGE")
                    .font(CvFont.display(18))
                    .tracking(2.4)
            }
            .padding(.top, 24)

            Spacer()

            VStack(alignment: .leading, spacing: 14) {
                Text("GROUP DRIVES / GROUP RIDES")
                    .font(CvFont.label(11))
                    .tracking(1.8)
                    .foregroundStyle(theme.signal)
                Text("NEVER LOSE\nTHE CONVOY.")
                    .font(CvFont.display(44))
                Text("Live positions, push-to-talk, regroup alerts and hazard calls for everyone in the group, on four wheels or two.")
                    .font(CvFont.text(15))
                    .foregroundStyle(theme.inkMuted)
            }

            Spacer()

            VStack(alignment: .leading, spacing: 12) {
                SectionLabel(text: "Dev sign-in")
                CvField(placeholder: "Your name", text: $name)
                CvButton(title: "Get started") {
                    app.signIn(name: name)
                }
                .opacity(name.trimmingCharacters(in: .whitespaces).isEmpty ? 0.5 : 1)
                Text("Development sign-in only. Real accounts come later.")
                    .font(CvFont.text(12))
                    .foregroundStyle(theme.inkMuted)
            }
            .padding(.bottom, 24)
        }
        .padding(.horizontal, 24)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
        .background(theme.void.ignoresSafeArea())
    }
}
