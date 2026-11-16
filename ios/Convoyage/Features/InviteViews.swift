import SwiftUI
import UIKit

// MARK: - Invite entry points
//
// The invite code, QR and live link are reachable from the convoy sheet itself (header and roster), not only from
// the Share tab. These pieces are shared by both.

extension InviteLinks {
    /// The one invite text every share path hands to the share sheet: the Share tab, the header and the roster.
    static func shareText(_ room: Room) -> String {
        return message(name: room.name, code: room.code)
    }
}

// MARK: - Live link

/// The live link: people without the app follow the convoy on a map in their browser.
/// Owns the fetch, the URL and the copied feedback, so the Share tab and the roster invite block share one path.
struct LiveLinkSection: View {
    let room: Room
    /// Off where the block already sits under its own heading, as in the roster invite row.
    var showsHeading: Bool = true

    @Environment(AppState.self) private var app
    @Environment(\.theme) private var theme
    @State private var url: String?
    @State private var error: String?
    @State private var busy: Bool = false
    @State private var copied: Bool = false
    @State private var copyTask: Task<Void, Never>?

    var body: some View {
        VStack(spacing: 8) {
            if showsHeading {
                SectionLabel(text: "Live link")
            }
            if let link = url {
                let name: String = room.name.isEmpty ? "our convoy" : room.name
                let text: String = "Follow \(name) live on the map (no app needed): \(link)"
                HStack(spacing: 10) {
                    ShareLink(item: text, subject: Text("Follow \(name) live")) {
                        HStack(spacing: 8) {
                            Image(systemName: "dot.radiowaves.left.and.right")
                            Text("SHARE LIVE").tracking(1.2)
                        }
                    }
                    .buttonStyle(CvButtonStyle(kind: .primary))
                    CvButton(title: copied ? "Copied" : "Copy", icon: "doc.on.doc", kind: .ghost) {
                        UIPasteboard.general.string = link
                        markCopied()
                    }
                }
                Text(link).font(CvFont.mono(11, weight: .medium)).foregroundStyle(theme.inkMuted).lineLimit(1)
            } else {
                CvButton(title: busy ? "Getting link..." : "Live link for people without the app",
                         icon: "dot.radiowaves.left.and.right", kind: .ghost) {
                    busy = true
                    Task {
                        switch await app.api.liveLink(code: room.code) {
                        case .ok(let l):
                            url = l.url
                            error = nil
                        case .failure(let f):
                            error = f.message
                        }
                        busy = false
                    }
                }
                .disabled(busy)
            }
            if let e = error {
                Text(e).font(CvFont.text(12)).foregroundStyle(theme.pulse)
            }
        }
        .onChange(of: room.code) { (_: String, _: String) in
            url = nil
            error = nil
            copyTask?.cancel()
            copied = false
        }
        .onDisappear {
            copyTask?.cancel()
            copied = false
        }
    }

    private func markCopied() {
        copyTask?.cancel()
        copied = true
        copyTask = Task { @MainActor in
            try? await Task.sleep(nanoseconds: 1_500_000_000)
            if !Task.isCancelled { copied = false }
        }
    }
}

// MARK: - Roster invite block

/// The live link, at the top of the roster. The code, the share sheet and the QR are already in the header and the
/// Share tab, so they are not repeated here.
struct InviteRow: View {
    let room: Room

    var body: some View {
        LiveLinkSection(room: room, showsHeading: false)
            .padding(.bottom, 4)
    }
}

// MARK: - QR sheet

/// Scan to join: the code, the QR and the share and copy buttons, scrollable in a medium or large sheet.
struct InviteQRSheet: View {
    let room: Room

    @Environment(\.theme) private var theme
    @State private var copied: Bool = false
    @State private var copyTask: Task<Void, Never>?

    var body: some View {
        let message: String = InviteLinks.shareText(room)
        ScrollView {
            VStack(spacing: 14) {
                VStack(spacing: 2) {
                    SectionLabel(text: "Convoy")
                    Text(room.name).font(CvFont.display(22)).lineLimit(1)
                }
                if let img = InviteLinks.qr(InviteLinks.deepLink(room.code)) {
                    Image(uiImage: img)
                        .interpolation(.none)
                        .resizable()
                        .scaledToFit()
                        .frame(width: 148, height: 148)
                        .padding(10)
                        .background(Color.white)
                        .accessibilityLabel("QR code to join \(room.name)")
                }
                Text(room.code)
                    .font(CvFont.mono(30))
                    .tracking(1.0)
                    .foregroundStyle(theme.signal)
                Text("Scan with a camera, or open \(InviteLinks.webLink(room.code))")
                    .font(CvFont.text(12))
                    .foregroundStyle(theme.inkMuted)
                    .multilineTextAlignment(.center)
                HStack(spacing: 10) {
                    ShareLink(item: message, subject: Text("Join my convoy")) {
                        HStack(spacing: 8) {
                            Image(systemName: "square.and.arrow.up")
                            Text("SHARE").tracking(1.2)
                        }
                    }
                    .buttonStyle(CvButtonStyle(kind: .primary))
                    CvButton(title: copied ? "Copied" : "Copy", icon: "doc.on.doc", kind: .ghost) {
                        UIPasteboard.general.string = message
                        markCopied()
                    }
                }
            }
            .frame(maxWidth: .infinity)
            .padding(20)
        }
        .background(theme.surface100.ignoresSafeArea())
        .foregroundStyle(theme.ink)
        .onDisappear {
            copyTask?.cancel()
            copied = false
        }
    }

    private func markCopied() {
        copyTask?.cancel()
        copied = true
        copyTask = Task { @MainActor in
            try? await Task.sleep(nanoseconds: 1_500_000_000)
            if !Task.isCancelled { copied = false }
        }
    }
}
