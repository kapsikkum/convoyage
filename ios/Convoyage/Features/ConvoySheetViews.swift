import SwiftUI
import UIKit
import CoreLocation
import CoreImage
import CoreImage.CIFilterBuiltins

// MARK: - Layout helpers

/// Safe-area insets of the device, read from the window (the convoy screen lays itself out edge to edge).
enum DeviceInsets {
    private static func window() -> UIWindow? {
        for scene in UIApplication.shared.connectedScenes {
            if let ws = scene as? UIWindowScene {
                if let key = ws.windows.first(where: { (w: UIWindow) -> Bool in return w.isKeyWindow }) { return key }
                if let first = ws.windows.first { return first }
            }
        }
        return nil
    }

    static var screenHeight: CGFloat { return window()?.bounds.height ?? 844 }
    static var top: CGFloat { return window()?.safeAreaInsets.top ?? 47 }
    static var bottom: CGFloat { return window()?.safeAreaInsets.bottom ?? 34 }
}

enum SheetDetent: Hashable {
    case peek
    case half
    case full
}

enum SheetTab: String, CaseIterable, Hashable {
    case roster
    case chat
    case share
    case route

    var title: String {
        switch self {
        case .roster: return "Roster"
        case .chat: return "Chat"
        case .share: return "Share"
        case .route: return "Route"
        }
    }
}

struct SheetMetrics {
    var peek: CGFloat
    var half: CGFloat
    var full: CGFloat
    /// Space kept clear at the bottom for the floating tab bar.
    var reserve: CGFloat

    func height(for detent: SheetDetent) -> CGFloat {
        switch detent {
        case .peek: return peek
        case .half: return half
        case .full: return full
        }
    }
}

// MARK: - Create a convoy

/// Name (an editable value, pre-filled) and automatic regroup settings. The server fixes regroup at creation.
struct CreateConvoySheet: View {
    @Environment(AppState.self) private var app
    @Environment(\.theme) private var theme
    @Environment(\.dismiss) private var dismiss
    @State private var name: String = ""
    @State private var regroupOn: Bool = true
    @State private var separationKm: Double = 2
    var onCreated: () -> Void = {}

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            VStack(alignment: .leading, spacing: 4) {
                SectionLabel(text: "New convoy")
                Text("Start a convoy").font(CvFont.display(28))
            }
            VStack(alignment: .leading, spacing: 8) {
                SectionLabel(text: "Convoy name")
                CvField(placeholder: "Convoy name", text: $name, autocap: .words)
            }
            Toggle(isOn: $regroupOn) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Automatic regroup").font(CvFont.text(15, weight: .bold))
                    Text("Calls a rest stop when the group stays split.").font(CvFont.text(12)).foregroundStyle(theme.inkMuted)
                }
            }
            .tint(theme.signal)
            if regroupOn {
                VStack(alignment: .leading, spacing: 6) {
                    HStack {
                        SectionLabel(text: "Split distance")
                        Spacer()
                        Text(String(format: "%.0f km", separationKm)).font(CvFont.mono(14))
                    }
                    Slider(value: $separationKm, in: 1...20, step: 1)
                        .tint(theme.signal)
                    Text("Set when the convoy starts; the server cannot change it afterwards.")
                        .font(CvFont.text(11))
                        .foregroundStyle(theme.inkMuted)
                }
            }
            Spacer(minLength: 0)
            HStack(spacing: 10) {
                CvButton(title: "Cancel", kind: .ghost) { dismiss() }
                CvButton(title: "Start") {
                    app.convoy.createConvoy(name: name, regroupEnabled: regroupOn, separationM: separationKm * 1000)
                    dismiss()
                    onCreated()
                }
            }
        }
        .padding(20)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background(theme.surface100.ignoresSafeArea())
        .foregroundStyle(theme.ink)
        .onAppear {
            if name.isEmpty { name = app.convoy.defaultConvoyName }
        }
    }
}

// MARK: - Destination from a long press

struct PendingDestination: Identifiable {
    let id: UUID = UUID()
    let coordinate: CLLocationCoordinate2D
}

struct DestinationSheet: View {
    let pending: PendingDestination
    @Environment(AppState.self) private var app
    @Environment(\.theme) private var theme
    @Environment(\.dismiss) private var dismiss
    @State private var label: String = ""
    @State private var looking: Bool = true

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            VStack(alignment: .leading, spacing: 4) {
                SectionLabel(text: "Leader")
                Text("Set destination").font(CvFont.display(28))
            }
            VStack(alignment: .leading, spacing: 8) {
                SectionLabel(text: looking ? "Looking up the address..." : "Label")
                CvField(placeholder: "Destination", text: $label, autocap: .words)
            }
            Text(String(format: "%.5f, %.5f", pending.coordinate.latitude, pending.coordinate.longitude))
                .font(CvFont.mono(12, weight: .medium))
                .foregroundStyle(theme.inkMuted)
            Spacer(minLength: 0)
            HStack(spacing: 10) {
                CvButton(title: "Cancel", kind: .ghost) { dismiss() }
                CvButton(title: "Set") {
                    app.convoy.setDestination(pending.coordinate, label: label)
                    dismiss()
                }
            }
        }
        .padding(20)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background(theme.surface100.ignoresSafeArea())
        .foregroundStyle(theme.ink)
        .task {
            let loc: CLLocation = CLLocation(latitude: pending.coordinate.latitude, longitude: pending.coordinate.longitude)
            var found: String = ""
            if let marks = try? await CLGeocoder().reverseGeocodeLocation(loc), let p = marks.first {
                var parts: [String] = []
                if let n = p.name { parts.append(n) }
                if let l = p.locality, !parts.contains(l) { parts.append(l) }
                found = parts.joined(separator: ", ")
            }
            if label.isEmpty { label = found.isEmpty ? "Dropped pin" : found }
            looking = false
        }
    }
}

// MARK: - Status banners (shared by the map and Ride mode)

/// SOS calls, "is stopping" notices and the regroup banner.
struct StatusBanners: View {
    @Environment(AppState.self) private var app

    var body: some View {
        let vm: ConvoyViewModel = app.convoy
        VStack(spacing: 8) {
            ForEach(vm.sosNotices.keys.sorted(), id: \.self) { (id: String) in
                Banner(title: vm.sosNotices[id] ?? "Someone needs help", subtitle: "Check on them now",
                       kind: .pulse, icon: "exclamationmark.octagon.fill",
                       actionTitle: "Dismiss", action: { vm.sosNotices.removeValue(forKey: id) })
            }
            if let n = vm.stopNotice {
                Banner(title: n, subtitle: "Slow down and find them", kind: .pulse, icon: "stop.fill",
                       actionTitle: "OK", action: { vm.stopNotice = nil })
            }
            if let r = vm.room, r.resting {
                if vm.isLeader {
                    Banner(title: "Regroup at \(r.destination?.label ?? "the rest stop")",
                           subtitle: "Continue when everyone is in", kind: .hazard, icon: "cup.and.saucer.fill",
                           actionTitle: "Continue", action: { vm.restContinue() })
                } else {
                    Banner(title: "Regroup at \(r.destination?.label ?? "the rest stop")",
                           subtitle: "The leader called a rest stop", kind: .hazard, icon: "cup.and.saucer.fill")
                }
            }
        }
    }
}

// MARK: - Roster

struct RosterPanel: View {
    @Environment(AppState.self) private var app
    @Environment(\.theme) private var theme
    @State private var removeTarget: Member?

    var body: some View {
        let vm: ConvoyViewModel = app.convoy
        let removeBinding: Binding<Bool> = Binding<Bool>(
            get: { return removeTarget != nil },
            set: { (v: Bool) in
                if !v { removeTarget = nil }
            }
        )
        ScrollView {
            VStack(spacing: 8) {
                ForEach(vm.members) { (m: Member) in
                    memberRow(vm, m)
                }
                HStack(spacing: 10) {
                    CvButton(title: vm.iAmStopping ? "Moving again" : "I'm stopping",
                             icon: vm.iAmStopping ? "play.fill" : "stop.fill",
                             kind: vm.iAmStopping ? .primary : .alert) {
                        vm.setStopping(!vm.iAmStopping)
                    }
                    CvButton(title: "Ride mode", icon: "gauge.with.dots.needle.67percent", kind: .ghost) {
                        app.showRide = true
                    }
                }
                .padding(.top, 4)
                CvButton(title: "Leave convoy", kind: .ghost) { vm.leaveConvoy() }
            }
            .padding(.bottom, 8)
        }
        .alert("Remove from convoy?", isPresented: removeBinding, presenting: removeTarget) { (m: Member) in
            Button("Remove", role: .destructive) { vm.removeMember(m.id) }
            Button("Cancel", role: .cancel) {}
        } message: { (m: Member) in
            Text("\(Formatters.shownName(m.username)) will not be able to rejoin with the code.")
        }
    }

    private func memberRow(_ vm: ConvoyViewModel, _ m: Member) -> some View {
        let isMe: Bool = (m.id == vm.myId)
        let isLeader: Bool = (m.id == vm.room?.leader)
        let sk: MemberStatusKind? = vm.statusKind(of: m)
        let state: RosterState
        if !m.online { state = .offline }
        else if sk == .sos { state = .sos }
        else if sk == .stopping { state = .stopped }
        else if isLeader { state = .leader }
        else if vm.isStopped(m) { state = .stopped }
        else { state = .online }

        var detail: String = isLeader ? "Leading" : "Online"
        if !m.online {
            detail = "Offline"
        } else if state == .sos {
            detail = "SOS"
            if let n = vm.statuses[m.id]?.note, !n.isEmpty { detail += "  /  \(n)" }
        } else if sk == .stopping {
            detail = "Stopping"
        } else if state == .stopped {
            detail = "Stopped"
        } else if let s = m.position?.speed {
            detail += "  /  \(Formatters.kmh(s)) km/h"
        }

        let dist: String? = isMe ? nil : vm.distance(to: m).map { Formatters.distanceText($0) }
        let baseName: String = isMe && !app.displayName.isEmpty ? app.displayName : m.username
        let shown: String = Formatters.shownName(baseName)
        let name: String = isMe ? "\(shown) (you)" : shown

        return HStack(spacing: 6) {
            RosterChip(initials: Formatters.initials(shown),
                       name: name,
                       detail: detail,
                       distance: dist,
                       distanceCaption: dist == nil ? nil : "away",
                       state: state)
            if vm.isLeader && !isMe {
                Menu {
                    Button("Make leader") { vm.handOver(to: m.id) }
                    Button("Remove from convoy", role: .destructive) { removeTarget = m }
                } label: {
                    Image(systemName: "ellipsis")
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundStyle(theme.ink)
                        .frame(width: 44, height: 44)
                        .cvPanel(fill: theme.surface200, edge: theme.line, cut: Cut.s)
                }
                .accessibilityLabel("Leader actions for \(shown)")
            }
        }
    }
}

// MARK: - Chat

struct ChatPanel: View {
    @Environment(AppState.self) private var app
    @Environment(\.theme) private var theme
    @State private var draft: String = ""

    private let quickReplies: [String] = ["Fuel stop", "Pull over", "All good", "Slow down"]

    var body: some View {
        let vm: ConvoyViewModel = app.convoy
        let msgs: [ConvoyChatMessage] = vm.chatMessages
        VStack(spacing: 8) {
            ScrollViewReader { (proxy: ScrollViewProxy) in
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 8) {
                        if msgs.isEmpty {
                            Text("No messages yet. Tap a quick reply or write one.")
                                .font(CvFont.text(13))
                                .foregroundStyle(theme.inkMuted)
                                .padding(.top, 8)
                        }
                        ForEach(msgs) { (msg: ConvoyChatMessage) in
                            bubble(vm, msg).id(msg.id)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                .onChange(of: msgs.count) { (_: Int, _: Int) in
                    if let last = msgs.last {
                        withAnimation { proxy.scrollTo(last.id, anchor: .bottom) }
                    }
                    vm.markChatRead()
                }
                .onAppear {
                    if let last = msgs.last { proxy.scrollTo(last.id, anchor: .bottom) }
                    vm.markChatRead()
                }
            }
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(quickReplies, id: \.self) { (text: String) in
                        Button {
                            vm.sendChat(text)
                        } label: {
                            Text(text)
                                .font(CvFont.text(13, weight: .bold))
                                .foregroundStyle(theme.ink)
                                .padding(.horizontal, 14)
                                .frame(minHeight: 40)
                                .cvPanel(fill: theme.surface300, edge: theme.lineStrong, cut: Cut.s)
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel("Send \(text)")
                    }
                }
            }
            HStack(spacing: 8) {
                TextField("Message the convoy", text: $draft)
                    .font(CvFont.text(15, weight: .semibold))
                    .foregroundStyle(theme.ink)
                    .padding(.horizontal, 12)
                    .frame(minHeight: 44)
                    .cvPanel(fill: theme.surface200, edge: theme.lineStrong, cut: Cut.s)
                    .submitLabel(.send)
                    .onSubmit { send(vm) }
                Button {
                    send(vm)
                } label: {
                    Image(systemName: "paperplane.fill")
                        .font(.system(size: 17, weight: .semibold))
                        .foregroundStyle(theme.signalInk)
                        .frame(width: 44, height: 44)
                        .cvPanel(fill: theme.signal, edge: theme.signal, cut: Cut.s)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Send message")
            }
        }
    }

    private func send(_ vm: ConvoyViewModel) {
        vm.sendChat(draft)
        draft = ""
    }

    private func bubble(_ vm: ConvoyViewModel, _ msg: ConvoyChatMessage) -> some View {
        let mine: Bool = (msg.userId == vm.myId)
        let who: String = mine ? "You" : Formatters.shownName(msg.username)
        return VStack(alignment: .leading, spacing: 2) {
            Text(who.uppercased())
                .font(CvFont.label(10))
                .tracking(1.2)
                .foregroundStyle(mine ? theme.signal : theme.inkMuted)
            Text(msg.text)
                .font(CvFont.text(14, weight: .medium))
                .foregroundStyle(theme.ink)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .frame(maxWidth: .infinity, alignment: .leading)
        .cvPanel(fill: theme.surface200, edge: mine ? theme.signal : theme.line, cut: Cut.s)
    }
}

// MARK: - Share

enum InviteLinks {
    static func deepLink(_ code: String) -> String { return "convoyage://join/" + code }
    static func webLink(_ code: String) -> String { return "https://convoyage.app/join/" + code }

    static func message(name: String, code: String) -> String {
        return "Join my convoy \"\(name)\" on Convoyage. Code \(code)\n\(webLink(code))\nAlready have the app? \(deepLink(code))"
    }

    static func qr(_ text: String) -> UIImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(text.utf8)
        filter.correctionLevel = "M"
        guard let out = filter.outputImage else { return nil }
        let scaled: CIImage = out.transformed(by: CGAffineTransform(scaleX: 10, y: 10))
        let ctx: CIContext = CIContext()
        guard let cg = ctx.createCGImage(scaled, from: scaled.extent) else { return nil }
        return UIImage(cgImage: cg)
    }
}

struct SharePanel: View {
    @Environment(AppState.self) private var app
    @Environment(\.theme) private var theme
    @State private var copied: Bool = false

    var body: some View {
        let vm: ConvoyViewModel = app.convoy
        if let room = vm.room {
            let message: String = InviteLinks.message(name: room.name, code: room.code)
            ScrollView {
                VStack(spacing: 14) {
                    VStack(spacing: 2) {
                        SectionLabel(text: "Invite code")
                        Text(room.code).font(CvFont.mono(30)).foregroundStyle(theme.signal)
                    }
                    if let img = InviteLinks.qr(InviteLinks.deepLink(room.code)) {
                        Image(uiImage: img)
                            .interpolation(.none)
                            .resizable()
                            .scaledToFit()
                            .frame(width: 168, height: 168)
                            .padding(10)
                            .background(Color.white)
                            .accessibilityLabel("QR code to join \(room.name)")
                    }
                    Text("Scan with the Camera app on a phone that has Convoyage.")
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
                            copied = true
                        }
                    }
                    Text(InviteLinks.webLink(room.code))
                        .font(CvFont.mono(11, weight: .medium))
                        .foregroundStyle(theme.inkMuted)
                    Text(InviteLinks.deepLink(room.code))
                        .font(CvFont.mono(11, weight: .medium))
                        .foregroundStyle(theme.inkMuted)
                }
                .frame(maxWidth: .infinity)
                .padding(.bottom, 8)
            }
        }
    }
}

// MARK: - Route and leader tools

struct RoutePanel: View {
    @Environment(AppState.self) private var app
    @Environment(\.theme) private var theme

    var body: some View {
        let vm: ConvoyViewModel = app.convoy
        if let room = vm.room {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    VStack(alignment: .leading, spacing: 4) {
                        SectionLabel(text: room.resting ? "Rest stop" : "Destination")
                        if let d = room.destination {
                            Text(d.label).font(CvFont.display(20))
                        } else {
                            Text("None set").font(CvFont.display(20))
                        }
                        Text(vm.isLeader ? "Long-press the map to set the destination." : "Only the leader sets the destination.")
                            .font(CvFont.text(12))
                            .foregroundStyle(theme.inkMuted)
                    }
                    .padding(12)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .cvPanel(fill: theme.surface200, edge: theme.line, cut: Cut.s)

                    VStack(alignment: .leading, spacing: 4) {
                        SectionLabel(text: "Regroup")
                        if room.automaticRegroup.enabled {
                            Text("Automatic, at \(Formatters.distanceText(room.automaticRegroup.separationM)) apart")
                                .font(CvFont.text(14, weight: .bold))
                        } else {
                            Text("Automatic regroup is off").font(CvFont.text(14, weight: .bold))
                        }
                        Text("Set when the convoy started. The route line below is needed for rest stops.")
                            .font(CvFont.text(12))
                            .foregroundStyle(theme.inkMuted)
                    }
                    .padding(12)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .cvPanel(fill: theme.surface200, edge: theme.line, cut: Cut.s)

                    if vm.isLeader {
                        leaderTools(vm, room)
                    }
                    CvButton(title: "Find a rest stop", icon: "cup.and.saucer.fill", kind: .ghost) { vm.requestRegroup() }
                }
                .padding(.bottom, 8)
            }
        }
    }

    @ViewBuilder
    private func leaderTools(_ vm: ConvoyViewModel, _ room: Room) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            SectionLabel(text: "Leader tools")
            if room.destination != nil {
                CvButton(title: "Clear destination", icon: "xmark", kind: .ghost) { vm.clearDestination() }
                if room.route == nil {
                    CvButton(title: "Share route line", icon: "point.topleft.down.curvedto.point.bottomright.up", kind: .ghost) {
                        vm.shareRouteToDestination()
                    }
                }
            }
            if room.resting {
                CvButton(title: "Continue to destination", icon: "play.fill") { vm.restContinue() }
            }
        }
    }
}
