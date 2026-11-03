import SwiftUI
import CoreLocation

struct ConvoyView: View {
    @Environment(AppState.self) private var app
    @Environment(\.theme) private var theme
    @State private var is3D: Bool = false
    @State private var recenter: Int = 0
    @State private var sheetExpanded: Bool = false
    @State private var showJoin: Bool = false
    @State private var code: String = ""
    @State private var talking: Bool = false
    @State private var showReport: Bool = false

    private var hazardPins: [MapPin] {
        return app.hazards.active.map { (h: HazardDto) -> MapPin in
            return MapPin(id: "hazard-" + h.id,
                          initials: h.isPolice ? "P" : "!",
                          kind: h.isPolice ? .police : .hazard,
                          coordinate: CLLocationCoordinate2D(latitude: h.lat, longitude: h.lng))
        }
    }

    var body: some View {
        let vm: ConvoyViewModel = app.convoy
        let rec: RideRecorder = app.recorder
        ZStack {
            ConvoyMapView(pins: vm.mapPins + hazardPins, is3D: is3D, recenterToken: recenter, theme: theme,
                          onViewport: { (box: GeoBox) in
                              Task { await app.hazards.refresh(box: box) }
                          })
                .ignoresSafeArea()

            VStack(spacing: 8) {
                bannerView(vm)
                HStack(spacing: 8) {
                    toggleChip("3D", on: is3D) { is3D.toggle() }
                    if rec.isActive {
                        recordingChip(rec)
                    }
                    Spacer()
                }
                Spacer()
            }
            .padding(.horizontal, 12)
            .padding(.top, 8)

            HStack {
                Spacer()
                VStack(spacing: 10) {
                    Spacer()
                    iconButton("location.north.fill", tint: theme.signal) { recenter += 1 }
                    iconButton("camera.fill", tint: theme.signal) { app.photos.begin() }
                    iconButton("exclamationmark.triangle", tint: theme.hazard) { showReport = true }
                    PushToTalk(size: 72, label: talking ? "LIVE" : "HOLD") { (down: Bool) in
                        talking = down
                    }
                    Color.clear.frame(height: 250)
                }
            }
            .padding(.trailing, 16)

            VStack {
                Spacer()
                sheet(vm)
                    .padding(.horizontal, 8)
                    .padding(.bottom, 84)
            }
        }
        .alert("Join a convoy", isPresented: $showJoin) {
            TextField("Invite code", text: $code)
                .textInputAutocapitalization(.characters)
            Button("Join") {
                vm.joinConvoy(code: code)
                code = ""
            }
            Button("Cancel", role: .cancel) {}
        }
        .sheet(isPresented: $showReport) {
            ReportView()
        }
        .photoCapture()
        .task {
            // Keep the hazard pins fresh while the map is up.
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 60_000_000_000)
                await app.hazards.refreshLast()
            }
        }
    }

    // MARK: Pieces

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

    @ViewBuilder
    private func bannerView(_ vm: ConvoyViewModel) -> some View {
        VStack(spacing: 8) {
            HazardAlertBanner()
            if let n = vm.stopNotice {
                Banner(title: n, subtitle: "Slow down and find them", kind: .pulse, icon: "stop.fill",
                       actionTitle: "OK", action: { vm.stopNotice = nil })
            }
            if let m = app.photos.message {
                Banner(title: m, kind: .info, icon: "camera.fill", actionTitle: "OK", action: { app.photos.message = nil })
            }
            if let err = vm.lastError {
                Banner(title: err, subtitle: "Tap to dismiss", kind: .hazard, icon: "exclamationmark.triangle",
                       actionTitle: "OK", action: { vm.lastError = nil })
            } else if let s = vm.straggler {
                Banner(title: "\(s.member.username) is \(Formatters.distanceText(s.meters)) away",
                       subtitle: "Regroup and let them catch up?", kind: .pulse,
                       actionTitle: "Regroup", action: { vm.requestRegroup() })
            } else if vm.socketState != .open {
                Banner(title: "Not connected", subtitle: "Server: \(app.serverURL)", kind: .hazard,
                       icon: "wifi.slash", actionTitle: "Retry", action: { vm.reconnect() })
            }
        }
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
    }

    private func iconButton(_ symbol: String, tint: Color, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: symbol)
                .font(.system(size: 20, weight: .semibold))
                .foregroundStyle(tint)
                .frame(width: 48, height: 48)
                .cvPanel(fill: theme.glass, edge: tint == theme.hazard ? theme.hazard : theme.lineStrong, cut: Cut.s)
        }
        .buttonStyle(.plain)
    }

    @ViewBuilder
    private func sheet(_ vm: ConvoyViewModel) -> some View {
        VStack(spacing: 12) {
            Capsule()
                .fill(theme.lineStrong)
                .frame(width: 40, height: 4)
                .onTapGesture { sheetExpanded.toggle() }

            if let room = vm.room {
                HStack(alignment: .center) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(room.name).font(CvFont.display(20))
                        Text("\(vm.onlineCount) of \(room.members.count) online  /  CODE \(room.code)")
                            .font(CvFont.label(11))
                            .tracking(1.0)
                            .foregroundStyle(theme.inkMuted)
                    }
                    Spacer()
                    Button {
                        app.showRide = true
                    } label: {
                        Image(systemName: "gauge.with.dots.needle.67percent")
                            .font(.system(size: 18, weight: .semibold))
                            .foregroundStyle(theme.signal)
                            .frame(width: 44, height: 44)
                            .cvPanel(fill: theme.surface200, edge: theme.lineStrong, cut: Cut.s)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Ride mode")
                }
                ScrollView {
                    VStack(spacing: 8) {
                        ForEach(vm.members) { (m: Member) in
                            memberChip(vm, m)
                        }
                    }
                }
                .frame(maxHeight: sheetExpanded ? 320 : 130)
                CvButton(title: "Leave convoy", kind: .ghost) { vm.leaveConvoy() }
            } else {
                Text("No convoy yet").font(CvFont.display(20))
                Text("Start one and share the code, or join with a code.")
                    .font(CvFont.text(13))
                    .foregroundStyle(theme.inkMuted)
                HStack(spacing: 10) {
                    CvButton(title: "Start") { vm.createConvoy(name: "") }
                    CvButton(title: "Join", kind: .ghost) { showJoin = true }
                }
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity)
        .background(.ultraThinMaterial)
        .cvPanel(fill: theme.glass, edge: theme.lineStrong, cut: Cut.l)
    }

    private func memberChip(_ vm: ConvoyViewModel, _ m: Member) -> some View {
        let isLeader: Bool = (m.id == vm.room?.leader)
        let state: RosterState
        if !m.online { state = .offline }
        else if isLeader { state = .leader }
        else if vm.isStopped(m) { state = .stopped }
        else { state = .online }

        var detail: String = isLeader ? "Leading" : "Online"
        if !m.online { detail = "Offline" }
        else if state == .stopped { detail = "Stopped" }
        else if let s = m.position?.speed { detail += "  /  \(Formatters.kmh(s)) km/h" }

        let dist: String? = (m.id == vm.myId) ? nil : vm.distance(to: m).map { Formatters.distanceText($0) }
        let name: String = (m.id == vm.myId) ? "\(m.username) (you)" : m.username

        return RosterChip(initials: Formatters.initials(m.username),
                          name: name,
                          detail: detail,
                          distance: dist,
                          distanceCaption: dist == nil ? nil : "away",
                          state: state)
    }
}
