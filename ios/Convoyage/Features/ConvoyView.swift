import SwiftUI
import UIKit
import CoreLocation

struct ConvoyView: View {
    @Environment(AppState.self) private var app
    @Environment(\.theme) private var theme
    @State private var is3D: Bool = false
    @State private var recenter: Int = 0
    @State private var showJoin: Bool = false
    @State private var showCreate: Bool = false
    @State private var code: String = ""
    @State private var showReport: Bool = false
    @State private var detent: SheetDetent = .peek
    @GestureState private var dragY: CGFloat = 0
    @State private var tab: SheetTab = .roster
    @State private var pendingDest: PendingDestination?
    @State private var keyboard: CGFloat = 0

    private static let headerHeight: CGFloat = 96
    private static let noConvoyHeight: CGFloat = 176

    private var hazardPins: [MapPin] {
        return app.hazards.active.map { (h: HazardDto) -> MapPin in
            return MapPin(id: "hazard-" + h.id,
                          initials: h.isPolice ? "P" : "!",
                          kind: h.isPolice ? .police : .hazard,
                          coordinate: CLLocationCoordinate2D(latitude: h.lat, longitude: h.lng))
        }
    }

    /// Sheet detents for a screen this tall. The bottom `reserve` is where the floating tab bar sits.
    private func metrics(screenHeight: CGFloat, inConvoy: Bool) -> SheetMetrics {
        let reserve: CGFloat = (keyboard > 0 ? 0 : DeviceInsets.bottom) + 68
        let available: CGFloat = screenHeight - keyboard
        let peek: CGFloat = (inConvoy ? ConvoyView.headerHeight : ConvoyView.noConvoyHeight) + reserve
        let full: CGFloat = max(peek, available - DeviceInsets.top - 12)
        let half: CGFloat = min(full, max(peek + 180, available * 0.5))
        return SheetMetrics(peek: peek, half: half, full: full, reserve: reserve)
    }

    var body: some View {
        let vm: ConvoyViewModel = app.convoy
        let rec: RideRecorder = app.recorder
        let inConvoy: Bool = (vm.room != nil)
        let joinPromptBinding: Binding<Bool> = Binding<Bool>(
            get: { return vm.joinPrompt != nil },
            set: { (v: Bool) in
                if !v { vm.joinPrompt = nil }
            }
        )
        GeometryReader { (geo: GeometryProxy) in
            let m: SheetMetrics = metrics(screenHeight: geo.size.height, inConvoy: inConvoy)
            let base: CGFloat = inConvoy ? m.height(for: detent) : m.peek
            let sheetH: CGFloat = inConvoy ? min(max(base - dragY, m.peek), m.full) : m.peek
            let railBottom: CGFloat = min(max(sheetH, m.peek), m.half) + 12 + keyboard
            let railVisible: Bool = sheetH <= m.half + 30

            ZStack(alignment: .bottom) {
                ConvoyMapView(pins: vm.mapPins + hazardPins, is3D: is3D, recenterToken: recenter, theme: theme,
                              userCoordinate: vm.location.latest?.coordinate,
                              route: vm.mapRoute,
                              onViewport: { (box: GeoBox) in
                                  Task { await app.hazards.refresh(box: box) }
                              },
                              onLongPress: { (c: CLLocationCoordinate2D) in
                                  longPressed(c)
                              })
                    .frame(width: geo.size.width, height: geo.size.height)

                topOverlay(vm, rec)
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)

                rail(vm, rec)
                    .padding(.trailing, 16)
                    .padding(.bottom, railBottom)
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomTrailing)
                    .opacity(railVisible ? 1 : 0)
                    .allowsHitTesting(railVisible)

                sheet(vm, m, sheetH)
                    .padding(.bottom, keyboard)
            }
            .frame(width: geo.size.width, height: geo.size.height)
        }
        .ignoresSafeArea()
        .alert("Join a convoy", isPresented: $showJoin) {
            TextField("Invite code", text: $code)
                .textInputAutocapitalization(.characters)
            Button("Join") {
                vm.joinConvoy(code: code)
                code = ""
            }
            Button("Cancel", role: .cancel) {}
        }
        .alert("Switch convoys?", isPresented: joinPromptBinding, presenting: vm.joinPrompt) { (newCode: String) in
            Button("Leave and join") { vm.confirmSwitch(code: newCode) }
            Button("Stay", role: .cancel) {}
        } message: { (newCode: String) in
            Text("You are in \(vm.room?.name ?? "a convoy"). Leave it and join \(newCode)?")
        }
        .sheet(isPresented: $showReport) {
            ReportView()
        }
        .sheet(isPresented: $showCreate) {
            CreateConvoySheet()
                .presentationDetents([.medium, .large])
        }
        .sheet(item: $pendingDest) { (p: PendingDestination) in
            DestinationSheet(pending: p)
                .presentationDetents([.height(320)])
        }
        .photoCapture()
        .onAppear { app.updateServices() }
        .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillChangeFrameNotification)) { (n: Notification) in
            if let f = n.userInfo?[UIResponder.keyboardFrameEndUserInfoKey] as? CGRect {
                keyboard = max(0, DeviceInsets.screenHeight - f.minY)
            }
        }
        .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillHideNotification)) { (_: Notification) in
            keyboard = 0
        }
        .task {
            // Keep the hazard pins fresh while the map is up.
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 60_000_000_000)
                await app.hazards.refreshLast()
            }
        }
    }

    // MARK: Map interaction

    private func longPressed(_ c: CLLocationCoordinate2D) {
        let vm: ConvoyViewModel = app.convoy
        if vm.room == nil {
            vm.lastError = "Start or join a convoy to set a destination"
        } else if !vm.isLeader {
            vm.lastError = "Only the leader can set the destination"
        } else {
            pendingDest = PendingDestination(coordinate: c)
        }
    }

    // MARK: Top overlay

    private func topOverlay(_ vm: ConvoyViewModel, _ rec: RideRecorder) -> some View {
        VStack(spacing: 8) {
            bannerView(vm)
            HStack(spacing: 8) {
                toggleChip("3D", on: is3D) { is3D.toggle() }
                if rec.isActive {
                    recordingChip(rec)
                }
                Spacer()
            }
        }
        .padding(.horizontal, 12)
        .padding(.top, DeviceInsets.top + 8)
    }

    @ViewBuilder
    private func bannerView(_ vm: ConvoyViewModel) -> some View {
        VStack(spacing: 8) {
            HazardAlertBanner()
            StatusBanners()
            VoiceBanner()
            if let m = app.photos.message {
                Banner(title: m, kind: .info, icon: "camera.fill", actionTitle: "OK", action: { app.photos.message = nil })
            }
            if let err = vm.lastError {
                Banner(title: err, subtitle: "Tap to dismiss", kind: .hazard, icon: "exclamationmark.triangle",
                       actionTitle: "OK", action: { vm.lastError = nil })
            } else if let s = vm.straggler, !(vm.room?.resting ?? false) {
                Banner(title: "\(Formatters.shownName(s.member.username)) is \(Formatters.distanceText(s.meters)) away",
                       subtitle: "Regroup and let them catch up?", kind: .pulse,
                       actionTitle: "Regroup", action: { vm.requestRegroup() })
            } else if vm.socketState != .open {
                Banner(title: "Not connected", subtitle: "Server: \(app.serverURL)", kind: .hazard,
                       icon: "wifi.slash", actionTitle: "Retry", action: { vm.reconnect() })
            }
        }
    }

    private func recordingChip(_ rec: RideRecorder) -> some View {
        Button {
            app.showRide = true
        } label: {
            HStack(spacing: 6) {
                Circle().fill(rec.phase == .recording ? theme.pulse : theme.hazard).frame(width: 8, height: 8)
                Text(rec.phase == .recording ? "REC" : "PAUSED")
                    .font(CvFont.label(11))
                    .tracking(1.2)
                Text(Formatters.duration(rec.elapsedS)).font(CvFont.mono(12, weight: .medium))
            }
            .foregroundStyle(theme.ink)
            .padding(.horizontal, 12)
            .frame(minHeight: 36)
            .cvPanel(fill: theme.glass, edge: rec.phase == .recording ? theme.pulse : theme.hazard, cut: Cut.s)
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Open ride mode")
    }

    private func toggleChip(_ title: String, on: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title)
                .font(CvFont.label(12))
                .tracking(1.2)
                .foregroundStyle(on ? theme.signalInk : theme.ink)
                .padding(.horizontal, 14)
                .frame(minHeight: 36)
                .cvPanel(fill: on ? theme.signal : theme.glass, edge: on ? theme.signal : theme.lineStrong, cut: Cut.s)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(on ? "Turn 3D buildings off" : "Turn 3D buildings on")
    }

    // MARK: Right rail (always the same four buttons, always above the sheet)

    private func rail(_ vm: ConvoyViewModel, _ rec: RideRecorder) -> some View {
        VStack(spacing: 10) {
            iconButton("location.north.fill", tint: theme.signal, label: "Centre on my location") { recenter += 1 }
            iconButton("exclamationmark.triangle", tint: theme.hazard, label: "Report a hazard") { showReport = true }
            iconButton(rec.isActive ? "gauge.with.dots.needle.67percent" : "record.circle",
                       tint: rec.isActive ? theme.pulse : theme.signal,
                       label: rec.isActive ? "Open ride mode" : "Start recording a ride") {
                if rec.isActive {
                    app.showRide = true
                } else {
                    rec.start(vehicleId: app.garage.primary?.id, convoyCode: vm.room?.code)
                }
            }
            iconButton("camera.fill", tint: theme.signal, label: "Take a photo") { app.photos.begin() }
        }
    }

    private func iconButton(_ symbol: String, tint: Color, label: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: symbol)
                .font(.system(size: 20, weight: .semibold))
                .foregroundStyle(tint)
                .frame(width: 48, height: 48)
                .cvPanel(fill: theme.glass, edge: tint == theme.hazard ? theme.hazard : theme.lineStrong, cut: Cut.s)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
    }

    // MARK: Bottom sheet (peek / half / full, flush with the screen edge, only the top-left corner cut)

    @ViewBuilder
    private func sheet(_ vm: ConvoyViewModel, _ m: SheetMetrics, _ h: CGFloat) -> some View {
        let shape: TopLeftChamferShape = TopLeftChamferShape(cut: Cut.l)
        Group {
            if let room = vm.room {
                convoySheetContent(vm, room, m, h)
            } else {
                noConvoyContent(vm, m)
            }
        }
        .frame(maxWidth: .infinity)
        .frame(height: h, alignment: .top)
        .background(.ultraThinMaterial, in: shape)
        .background(shape.fill(theme.glass))
        .overlay(shape.stroke(theme.lineStrong, lineWidth: 1))
        .clipShape(shape)
        .animation(.spring(response: 0.35, dampingFraction: 0.85), value: detent)
    }

    private func noConvoyContent(_ vm: ConvoyViewModel, _ m: SheetMetrics) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Capsule().fill(theme.lineStrong).frame(width: 40, height: 4)
                .frame(maxWidth: .infinity)
            Text("No convoy yet").font(CvFont.display(20))
            Text("Start one and share the code, or join with a code.")
                .font(CvFont.text(13))
                .foregroundStyle(theme.inkMuted)
            HStack(spacing: 10) {
                CvButton(title: "Start") { showCreate = true }
                CvButton(title: "Join", kind: .ghost) { showJoin = true }
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 16)
        .padding(.top, 10)
        .padding(.bottom, m.reserve)
    }

    private func convoySheetContent(_ vm: ConvoyViewModel, _ room: Room, _ m: SheetMetrics, _ h: CGFloat) -> some View {
        let showBody: Bool = h > m.peek + 24
        let drag = DragGesture(minimumDistance: 6)
            .updating($dragY) { (value: DragGesture.Value, state: inout CGFloat, _: inout Transaction) in
                state = value.translation.height
            }
            .onEnded { (value: DragGesture.Value) in
                settle(value, m)
            }
        return VStack(spacing: 0) {
            VStack(spacing: 0) {
                Capsule()
                    .fill(theme.lineStrong)
                    .frame(width: 40, height: 4)
                    .frame(maxWidth: .infinity, minHeight: 24)
                    .contentShape(Rectangle())
                    .onTapGesture { cycleDetent() }
                    .accessibilityLabel("Convoy sheet handle")
                    .accessibilityHint("Double tap to change the sheet size")
                    .accessibilityAddTraits(.isButton)
                header(vm, room)
            }
            .gesture(drag)

            if showBody {
                tabBar(vm)
                    .padding(.horizontal, 16)
                    .padding(.top, 6)
                panel(vm)
                    .padding(.horizontal, 16)
                    .padding(.top, 8)
                    .frame(maxHeight: .infinity, alignment: .top)
            } else {
                Spacer(minLength: 0)
            }
        }
        .padding(.bottom, m.reserve)
    }

    private func header(_ vm: ConvoyViewModel, _ room: Room) -> some View {
        HStack(alignment: .center, spacing: 10) {
            VStack(alignment: .leading, spacing: 2) {
                Text(room.name)
                    .font(CvFont.display(20))
                    .lineLimit(1)
                Text("CODE \(room.code)  /  \(vm.onlineCount) of \(room.members.count) online")
                    .font(CvFont.label(11))
                    .tracking(1.0)
                    .foregroundStyle(theme.inkMuted)
                    .lineLimit(1)
            }
            Spacer(minLength: 4)
            Button {
                tab = .chat
                withAnimation(.spring(response: 0.35, dampingFraction: 0.85)) { detent = .half }
            } label: {
                Image(systemName: "bubble.left.fill")
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundStyle(theme.signal)
                    .frame(width: 44, height: 44)
                    .cvPanel(fill: theme.surface200, edge: theme.lineStrong, cut: Cut.s)
                    .overlay(alignment: .topTrailing) {
                        if vm.unreadCount > 0 {
                            Text(vm.unreadCount > 9 ? "9+" : "\(vm.unreadCount)")
                                .font(CvFont.label(10))
                                .foregroundStyle(Color.white)
                                .padding(.horizontal, 5)
                                .frame(minWidth: 18, minHeight: 18)
                                .background(Capsule().fill(theme.pulse))
                                .offset(x: 6, y: -6)
                        }
                    }
            }
            .buttonStyle(.plain)
            .accessibilityLabel(vm.unreadCount > 0 ? "Convoy chat, \(vm.unreadCount) unread" : "Convoy chat")
            ConvoyPTT(size: 56)
        }
        .padding(.horizontal, 16)
        .frame(height: ConvoyView.headerHeight - 24)
        .contentShape(Rectangle())
    }

    private func tabBar(_ vm: ConvoyViewModel) -> some View {
        HStack(spacing: 6) {
            ForEach(SheetTab.allCases, id: \.self) { (t: SheetTab) in
                let on: Bool = (t == tab)
                Button {
                    tab = t
                } label: {
                    Text(t.title.uppercased())
                        .font(CvFont.label(11))
                        .tracking(1.2)
                        .foregroundStyle(on ? theme.signalInk : theme.inkMuted)
                        .frame(maxWidth: .infinity, minHeight: 36)
                        .background(ChamferShape(cut: Cut.s).fill(on ? theme.signal : theme.surface200))
                        .overlay(alignment: .topTrailing) {
                            if t == .chat && vm.unreadCount > 0 {
                                Circle().fill(theme.pulse).frame(width: 10, height: 10).offset(x: -4, y: 4)
                            }
                        }
                }
                .buttonStyle(.plain)
                .accessibilityLabel(t.title)
            }
        }
    }

    @ViewBuilder
    private func panel(_ vm: ConvoyViewModel) -> some View {
        switch tab {
        case .roster: RosterPanel()
        case .chat: ChatPanel()
        case .share: SharePanel()
        case .route: RoutePanel()
        }
    }

    private func cycleDetent() {
        withAnimation(.spring(response: 0.35, dampingFraction: 0.85)) {
            switch detent {
            case .peek: detent = .half
            case .half: detent = .full
            case .full: detent = .peek
            }
        }
    }

    private func settle(_ value: DragGesture.Value, _ m: SheetMetrics) {
        let base: CGFloat = m.height(for: detent)
        let momentum: CGFloat = (value.predictedEndTranslation.height - value.translation.height) * 0.4
        let projected: CGFloat = base - (value.translation.height + momentum)
        var best: SheetDetent = .peek
        var bestGap: CGFloat = abs(m.peek - projected)
        if abs(m.half - projected) < bestGap {
            best = .half
            bestGap = abs(m.half - projected)
        }
        if abs(m.full - projected) < bestGap {
            best = .full
        }
        withAnimation(.spring(response: 0.35, dampingFraction: 0.85)) {
            detent = best
        }
    }
}
