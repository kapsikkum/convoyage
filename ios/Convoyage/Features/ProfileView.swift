import SwiftUI

struct ProfileView: View {
    @Environment(AppState.self) private var app
    @Environment(\.theme) private var theme
    @State private var editing: Bool = false
    @State private var draft: String = ""
    @State private var saving: Bool = false
    @State private var saveError: String?
    @State private var loadError: String?
    @State private var showServer: Bool = false

    private var shownName: String {
        let n: String = app.me?.displayName ?? ""
        if !n.isEmpty { return n }
        return app.displayName
    }

    private var isAccount: Bool { return app.authKind == .account }

    var body: some View {
        PageScaffold(eyebrow: (app.me?.isPrivate ?? false) ? "Private account" : "You", title: shownName) {
            HStack(spacing: 14) {
                CvAvatar(photoURL: app.me?.avatarPath, vehicle: app.myVehicleBuild, initials: Formatters.initials(shownName), size: 64)
                VStack(alignment: .leading, spacing: 4) {
                    if let handle = app.me?.handle, !handle.isEmpty {
                        Text("@\(handle)").font(CvFont.text(14, weight: .bold))
                    } else if !isAccount {
                        Text("Dev sign-in, no account").font(CvFont.text(13)).foregroundStyle(theme.inkMuted)
                    }
                    HStack(spacing: 14) {
                        Text("\(app.me?.followerCount ?? 0) followers").font(CvFont.text(12))
                        Text("\(app.me?.followingCount ?? 0) following").font(CvFont.text(12))
                    }
                    .foregroundStyle(theme.inkMuted)
                }
                Spacer()
            }

            if let err = loadError {
                Banner(title: "Could not load your profile", subtitle: err, kind: .hazard, icon: "exclamationmark.triangle",
                       actionTitle: "Retry", action: {
                    Task { loadError = await app.loadMe() }
                })
            }

            if isAccount {
                nameEditor
            }

            primaryVehicleCard

            VStack(alignment: .leading, spacing: 10) {
                SectionLabel(text: "This device")
                Button {
                    showServer = true
                } label: {
                    HStack(spacing: 8) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Server").font(CvFont.label(10)).tracking(1.2).foregroundStyle(theme.inkMuted)
                            Text(app.serverURL).font(CvFont.mono(13, weight: .medium)).foregroundStyle(theme.ink).lineLimit(1)
                        }
                        Spacer()
                        Image(systemName: "chevron.right").foregroundStyle(theme.inkMuted)
                    }
                    .padding(.horizontal, 14)
                    .frame(minHeight: 56)
                    .cvPanel(fill: theme.surface200, edge: theme.line)
                }
                .buttonStyle(.plain)
                Text("User id  \(app.userId)").font(CvFont.mono(11, weight: .medium)).foregroundStyle(theme.inkMuted)
            }

            VStack(alignment: .leading, spacing: 10) {
                SectionLabel(text: "Convoy voice")
                @Bindable var a: AppState = app
                Toggle(isOn: $a.convoyAnnounce) {
                    Text("Speak convoy events").font(CvFont.text(14, weight: .bold))
                }
                .tint(theme.signal)
                Toggle(isOn: $a.convoyReadChat) {
                    Text("Read chat aloud").font(CvFont.text(14, weight: .bold))
                }
                .tint(theme.signal)
            }

            PhotoScoutSettings()

            CvButton(title: "Sign out", kind: .ghost) { app.signOut() }
        }
        .task {
            loadError = await app.loadMe()
        }
        .sheet(isPresented: $showServer) {
            ServerSettingsView()
        }
    }

    @ViewBuilder
    private var nameEditor: some View {
        if !editing {
            CvButton(title: "Edit display name", icon: "pencil", kind: .ghost) {
                draft = shownName
                saveError = nil
                editing = true
            }
        } else {
            VStack(alignment: .leading, spacing: 10) {
                SectionLabel(text: "Display name")
                CvField(placeholder: "Alex", text: $draft, autocap: .words)
                HStack(spacing: 10) {
                    CvButton(title: "Cancel", kind: .ghost) { editing = false }
                        .disabled(saving)
                    CvButton(title: saving ? "Saving..." : "Save") {
                        saving = true
                        saveError = nil
                        Task {
                            let err: String? = await app.updateDisplayName(draft)
                            saving = false
                            if let e = err {
                                saveError = e
                            } else {
                                editing = false
                            }
                        }
                    }
                    .opacity(saving || draft.trimmingCharacters(in: .whitespaces).isEmpty ? 0.5 : 1)
                    .disabled(saving || draft.trimmingCharacters(in: .whitespaces).isEmpty)
                }
                if let e = saveError {
                    Banner(title: e, kind: .pulse, icon: "exclamationmark.triangle")
                }
            }
        }
    }

    @ViewBuilder
    private var primaryVehicleCard: some View {
        if let v = app.garage.primary {
            Button {
                app.tab = .garage
            } label: {
                HStack(spacing: 12) {
                    Circle()
                        .fill(Color(hexString: v.build?.paint ?? "#9aa3ad"))
                        .frame(width: 28, height: 28)
                        .overlay(Circle().stroke(theme.lineStrong, lineWidth: 1))
                    VStack(alignment: .leading, spacing: 2) {
                        SectionLabel(text: "Primary / \(app.garage.vehicles.count) in garage")
                        Text(v.title).font(CvFont.display(18)).foregroundStyle(theme.ink)
                    }
                    Spacer()
                    Image(systemName: "chevron.right").foregroundStyle(theme.inkMuted)
                }
                .padding(.horizontal, 14)
                .frame(minHeight: 64)
                .cvPanel(fill: theme.surface200, edge: theme.lineStrong)
            }
            .buttonStyle(.plain)
        }
    }
}

extension Color {
    /// `#RRGGBB` (the server's paint format); falls back to grey.
    init(hexString: String) {
        var s: String = hexString.trimmingCharacters(in: .whitespaces)
        if s.hasPrefix("#") { s.removeFirst() }
        if s.count == 6, let v = UInt32(s, radix: 16) {
            self.init(hex: v)
        } else {
            self.init(hex: 0x9aa3ad)
        }
    }
}
