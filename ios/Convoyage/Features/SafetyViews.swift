import SwiftUI
import UIKit

/// Full-screen layers that must sit above everything, including Ride mode: the crash countdown and the
/// "keep sharing with the screen off" explanation. Added as an overlay in both RootView and RideView.
struct AppOverlays: View {
    @Environment(AppState.self) private var app

    var body: some View {
        ZStack {
            if app.crash.phase != .idle {
                CrashAlertView()
                    .transition(.opacity)
            } else if app.showAlwaysExplainer {
                AlwaysExplainerView()
                    .transition(.opacity)
            }
        }
    }
}

// MARK: - Crash countdown

/// 30 second countdown with a big "I'm OK" button. If it runs out, the convoy gets an SOS. Never calls emergency services.
struct CrashAlertView: View {
    @Environment(AppState.self) private var app
    @Environment(\.theme) private var theme

    var body: some View {
        let crash: CrashMonitor = app.crash
        ZStack {
            Color(hex: 0x1a0610).ignoresSafeArea()
            VStack(spacing: 20) {
                Spacer(minLength: 0)
                switch crash.phase {
                case .countdown(let left):
                    Image(systemName: "exclamationmark.triangle.fill")
                        .font(.system(size: 44, weight: .bold))
                        .foregroundStyle(theme.pulse)
                    Text("CRASH DETECTED")
                        .font(CvFont.display(30))
                        .tracking(2)
                        .foregroundStyle(Color.white)
                    Text("\(left)")
                        .font(CvFont.mono(120))
                        .foregroundStyle(theme.pulse)
                        .accessibilityLabel("\(left) seconds left")
                    Text(app.convoy.room != nil
                         ? "If you do not answer, your convoy gets an SOS with your location."
                         : "You are not in a convoy, so nobody will be alerted.")
                        .font(CvFont.text(15))
                        .foregroundStyle(Color.white.opacity(0.8))
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 24)
                    Spacer(minLength: 0)
                    Button {
                        crash.cancel()
                    } label: {
                        Text("I'M OK")
                            .font(CvFont.display(40))
                            .tracking(2)
                            .foregroundStyle(theme.signalInk)
                            .frame(maxWidth: .infinity, minHeight: 140)
                            .background(ChamferShape(cut: Cut.l).fill(theme.go))
                    }
                    .buttonStyle(.plain)
                    .padding(.horizontal, 20)
                    .accessibilityLabel("I'm OK, cancel the alert")
                case .expired(let alerted):
                    Image(systemName: alerted ? "dot.radiowaves.left.and.right" : "exclamationmark.triangle.fill")
                        .font(.system(size: 44, weight: .bold))
                        .foregroundStyle(theme.pulse)
                    Text(alerted ? "CONVOY ALERTED" : "NO ONE ALERTED")
                        .font(CvFont.display(30))
                        .tracking(2)
                        .foregroundStyle(Color.white)
                    Text(alerted
                         ? "Your convoy has your SOS and location. This app does not call emergency services: if you need them, call them yourself."
                         : "You are not in a convoy. This app does not call emergency services: if you need them, call them yourself.")
                        .font(CvFont.text(15))
                        .foregroundStyle(Color.white.opacity(0.8))
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 24)
                    Spacer(minLength: 0)
                    Button {
                        if alerted { app.convoy.setStopping(false) }
                        crash.dismissExpired()
                    } label: {
                        Text("I'M OK NOW")
                            .font(CvFont.display(28))
                            .tracking(2)
                            .foregroundStyle(theme.signalInk)
                            .frame(maxWidth: .infinity, minHeight: 100)
                            .background(ChamferShape(cut: Cut.l).fill(theme.go))
                    }
                    .buttonStyle(.plain)
                    .padding(.horizontal, 20)
                    .accessibilityLabel("I'm OK now, close")
                case .idle:
                    EmptyView()
                }
            }
            .padding(.vertical, 24)
        }
    }
}

// MARK: - Always-location explanation

struct AlwaysExplainerView: View {
    @Environment(AppState.self) private var app
    @Environment(\.theme) private var theme

    var body: some View {
        ZStack {
            Color.black.opacity(0.6).ignoresSafeArea()
            VStack(alignment: .leading, spacing: 14) {
                Image(systemName: "location.fill")
                    .font(.system(size: 26, weight: .semibold))
                    .foregroundStyle(theme.signal)
                Text("Keep sharing with the screen off")
                    .font(CvFont.display(22))
                    .foregroundStyle(theme.ink)
                Text("Your convoy and your ride recording need your position even when the screen is locked or another app is open. Convoyage only does this while you are in a convoy or recording, and iOS shows a blue indicator while it does.")
                    .font(CvFont.text(14))
                    .foregroundStyle(theme.inkMuted)
                Text("On the next screen choose Change to Always Allow.")
                    .font(CvFont.text(13, weight: .bold))
                    .foregroundStyle(theme.ink)
                CvButton(title: "Continue") { app.answerAlways(allow: true) }
                CvButton(title: "Not now", kind: .ghost) { app.answerAlways(allow: false) }
            }
            .padding(20)
            .cvPanel(fill: theme.surface100, edge: theme.lineStrong, cut: Cut.l)
            .padding(.horizontal, 20)
        }
    }
}
