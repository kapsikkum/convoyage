import SwiftUI

struct WelcomeView: View {
    @Environment(AppState.self) private var app
    @Environment(\.theme) private var theme
    @State private var email: String = ""
    @State private var pasted: String = ""
    @State private var sentTo: String?
    @State private var busy: Bool = false
    @State private var progress: String?
    @State private var errorText: String?
    @State private var showServer: Bool = false
    #if DEBUG
    @State private var devOpen: Bool = false
    @State private var devName: String = "Dev Driver"
    #endif

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                header
                    .padding(.top, 24)

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
                .padding(.top, 48)

                signInForm
                    .padding(.top, 36)

                serverRow
                    .padding(.top, 20)

                #if DEBUG
                devSection
                    .padding(.top, 20)
                #endif
            }
            .padding(.horizontal, 24)
            .padding(.bottom, 32)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .scrollDismissesKeyboard(.interactively)
        .background(theme.void.ignoresSafeArea())
        .foregroundStyle(theme.ink)
        .sheet(isPresented: $showServer) {
            ServerSettingsView()
        }
    }

    // MARK: Pieces

    private var header: some View {
        HStack(spacing: 10) {
            ChamferShape(cut: 8)
                .fill(theme.signal)
                .frame(width: 28, height: 28)
            Text("CONVOYAGE")
                .font(CvFont.display(18))
                .tracking(2.4)
            Spacer()
            Button {
                showServer = true
            } label: {
                Image(systemName: "gearshape")
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundStyle(theme.inkMuted)
                    .frame(width: 44, height: 44)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Server settings")
        }
    }

    @ViewBuilder
    private var signInForm: some View {
        VStack(alignment: .leading, spacing: 12) {
            if let status = app.linkStatus {
                Banner(title: status, kind: .info, icon: "link")
            }
            if let sent = sentTo {
                Banner(title: "Check your email",
                       subtitle: "We sent a sign-in link to \(sent). Open it on this phone to finish. It works once and expires in 15 minutes.",
                       kind: .go, icon: "envelope.fill")
                if let p = progress {
                    Banner(title: p, kind: .hazard, icon: "lock.fill")
                }
                SectionLabel(text: "Or paste the link or code")
                CvField(placeholder: "convoyage://auth/magic?token=...", text: $pasted, keyboard: .URL, mono: true)
                CvButton(title: busy ? "Signing in..." : "Sign in") {
                    runVerify(pasted)
                }
                .opacity(busy || pasted.trimmingCharacters(in: .whitespaces).isEmpty ? 0.5 : 1)
                .disabled(busy || pasted.trimmingCharacters(in: .whitespaces).isEmpty)
                CvButton(title: "Use a different email", kind: .ghost) {
                    sentTo = nil
                    progress = nil
                    errorText = nil
                }
                .disabled(busy)
            } else {
                SectionLabel(text: "Sign in")
                CvField(placeholder: "you@example.com", text: $email, keyboard: .emailAddress)
                CvButton(title: busy ? "Sending..." : "Email me a sign-in link", icon: "envelope") {
                    sendLink()
                }
                .opacity(busy ? 0.5 : 1)
                .disabled(busy)
            }
            if let e = errorText {
                Banner(title: e, kind: .pulse, icon: "exclamationmark.triangle")
            }
        }
    }

    private var serverRow: some View {
        Button {
            showServer = true
        } label: {
            HStack(spacing: 8) {
                Image(systemName: "server.rack")
                    .foregroundStyle(theme.inkMuted)
                Text(app.serverURL)
                    .font(CvFont.mono(12, weight: .medium))
                    .foregroundStyle(theme.inkMuted)
                    .lineLimit(1)
                Spacer()
                Text("CHANGE")
                    .font(CvFont.label(11))
                    .tracking(1.2)
                    .foregroundStyle(theme.signal)
            }
            .padding(.horizontal, 12)
            .frame(minHeight: 44)
            .cvPanel(fill: theme.surface200, edge: theme.line, cut: Cut.s)
        }
        .buttonStyle(.plain)
    }

    #if DEBUG
    @ViewBuilder
    private var devSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            Button {
                devOpen.toggle()
            } label: {
                Text("DEV SIGN-IN (DEBUG BUILD ONLY)")
                    .font(CvFont.label(11))
                    .tracking(1.4)
                    .foregroundStyle(theme.hazard)
                    .frame(minHeight: 36)
            }
            .buttonStyle(.plain)
            if devOpen {
                Text("Server WebSocket dev auth (CONVOYAGE_DEV_AUTH=1). There is no account behind it, so REST features refuse it.")
                    .font(CvFont.text(12))
                    .foregroundStyle(theme.inkMuted)
                CvField(placeholder: "Display name", text: $devName, autocap: .words)
                CvButton(title: "Enter as \(devName)", kind: .ghost) {
                    app.signInDev(name: devName)
                }
                .opacity(devName.trimmingCharacters(in: .whitespaces).isEmpty ? 0.5 : 1)
            }
        }
    }
    #endif

    // MARK: Actions

    private func sendLink() {
        let trimmed: String = email.trimmingCharacters(in: .whitespacesAndNewlines)
        if !trimmed.contains("@") || trimmed.count < 5 {
            errorText = "Enter a valid email address"
            return
        }
        busy = true
        errorText = nil
        Task {
            let outcome: MagicLinkOutcome = await app.requestMagicLink(email: trimmed)
            switch outcome {
            case .failed(let message):
                busy = false
                errorText = message
            case .sent(let devToken):
                sentTo = trimmed
                if let token = devToken {
                    // MAIL_DEV=1: the server handed the token back, so there is no inbox to open.
                    progress = "Dev server returned the link, signing in..."
                    await verifyNow(token)
                } else {
                    busy = false
                }
            }
        }
    }

    private func runVerify(_ text: String) {
        busy = true
        errorText = nil
        progress = "Signing in..."
        Task {
            await verifyNow(text)
        }
    }

    private func verifyNow(_ text: String) async {
        let outcome: SignInOutcome = await app.verify(text)
        busy = false
        switch outcome {
        case .signedIn:
            progress = nil // RootView sees the new session and moves to Home
        case .failed(let message):
            progress = nil
            errorText = message
        }
    }
}
