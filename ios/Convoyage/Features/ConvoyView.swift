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
    /// The convoy search field is open: the keyboard is up and the map furniture steps out of the way.
    @State private var searchActive: Bool = false
    /// The map keeps you in the middle until you move it yourself; Recentre turns it back on.
    @State private var follow: Bool = true
    /// The bearing the map turns to while moving: heading-up, held through slow patches.
    @State private var headingUp: HeadingUp = HeadingUp()
    @State private var showLayers: Bool = false
    @State private var layers: MapLayerSelection = MapLayerSelection()
    /// Where the map is looking, for the "nearby" layer hint.
    @State private var viewport: GeoBox?
    /// The points the hint asked to frame, and the bump that makes the map fit them.
    @State private var fitCoords: [CLLocationCoordinate2D] = []
    @State private var fitToken: Int = 0
    @State private var openNearbyConvoy: NearbyConvoy?
    @State private var openNearbyDriver: NearbyDriver?
    /// The code chip in the sheet header shows COPIED for a moment after a tap.
    @State private var copiedCode: Bool = false
    @State private var copyTask: Task<Void, Never>?
    @State private var showInviteQR: Bool = false

    private static let headerHeight: CGFloat = 96
    private static let noConvoyHeight: CGFloat = 228

    private var hazardPins: [MapPin] {
        return app.hazards.active.map { (h: HazardDto) -> MapPin in
            return MapPin(id: "hazard-" + h.id,
                          initials: h.isPolice ? "P" : "!",
                          kind: h.isPolice ? .police : .hazard,
                          coordinate: CLLocationCoordinate2D(latitude: h.lat, longitude: h.lng))
        }
    }

    /// Where I am, as my own vehicle (Android's "me" pin). Empty when there is no car on file, which leaves the dot.
    private var selfPin: [MapPin] {
        let vm: ConvoyViewModel = app.convoy
        guard let fix = vm.location.latest, let vehicle = app.myVehicleBuild else { return [] }
        let heading: Double? = fix.course >= 0 ? fix.course : nil
        return [MapPin(id: "me", initials: "", kind: .me, coordinate: fix.coordinate, vehicle: vehicle, heading: heading)]
    }

    /// Open Cruise: drivers and open convoys nearby, while not in a convoy.
    private var cruisePins: [MapPin] {
        let vm: ConvoyViewModel = app.convoy
        guard vm.room == nil, vm.openCruise else { return [] }
        return vm.nearbyConvoys.map { (c: NearbyConvoy) -> MapPin in return c.pin }
            + vm.nearbyDrivers.map { (d: NearbyDriver) -> MapPin in return d.pin }
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
            let sheetH: CGFloat = min(max(m.height(for: detent) - dragY, m.peek), m.full)
            let railBottom: CGFloat = min(max(sheetH, m.peek), m.half) + 12
            // Settled, not live: a drag through a threshold must not toggle furniture under the finger.
            let railVisible: Bool = detent == .peek && !searchActive
            let hint: (from: CLLocationCoordinate2D, to: CLLocationCoordinate2D)? = rejoinHint(vm)
            let guiding: Bool = vm.room != nil && app.nav.guiding

            ZStack(alignment: .bottom) {
                ConvoyMapView(pins: MapLayerPins.pins(app) + hazardPins + cruisePins + selfPin + vm.mapPins, is3D: is3D, recenterToken: recenter, theme: theme,
                              userCoordinate: vm.location.latest?.coordinate,
                              route: guiding ? app.nav.remainingCoordinates : vm.mapRoute,
                              rejoinFrom: hint?.from,
                              rejoinTo: hint?.to,
                              onViewport: { (box: GeoBox) in
                                  viewport = box
                                  Task { await app.hazards.refresh(box: box) }
                                  app.area.onViewport(box, on: app.layerTraffic)
                                  app.photoscout.onViewport(box, spots: app.layerSpots, events: app.layerEvents)
                              },
                              onLongPress: { (c: CLLocationCoordinate2D) in
                                  longPressed(c)
                              },
                              follow: followCamera(vm, guiding: guiding),
                              fitToken: fitToken,
                              fitCoordinates: fitCoords,
                              followUser: follow && !guiding,
                              onUserMoved: { follow = false },
                              onPinTap: { (id: String) in pinTapped(id) })
                    .frame(width: geo.size.width, height: geo.size.height)

                speedRow(vm)
                    .padding(.leading, 16)
                    .padding(.bottom, railBottom)
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomLeading)
                    .opacity(railVisible ? 1 : 0)

                topOverlay(vm, rec, searchActive)
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)

                rail(vm, rec)
                    .padding(.trailing, 16)
                    .padding(.bottom, railBottom)
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomTrailing)
                    .opacity(railVisible ? 1 : 0)
                    .allowsHitTesting(railVisible)

                layerHint(railVisible, railBottom)

                sheet(vm, m, sheetH)
                    .padding(.bottom, searchActive ? 0 : keyboard)
                    .opacity(searchActive ? 0 : 1)
                    .allowsHitTesting(!searchActive)
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
        .sheet(isPresented: $showLayers) {
            LayersSheet(mapCentre: viewport?.center)
                .presentationDetents([.height(340), .medium])
        }
        .sheet(isPresented: $showInviteQR) {
            if let room = app.convoy.room {
                InviteQRSheet(room: room)
                    .presentationDetents([.medium, .large])
            }
        }
        .mapLayerSheets(layers)
        .sheet(item: $openNearbyConvoy) { (c: NearbyConvoy) in
            NearbyConvoySheet(convoy: c)
                .presentationDetents([.height(230)])
        }
        .sheet(item: $openNearbyDriver) { (d: NearbyDriver) in
            NearbyDriverSheet(driver: d)
                .presentationDetents([.height(230)])
        }
        .task { await app.photoscout.refreshStatus() }
        .onChange(of: vm.location.latest) { (_: CLLocation?, l: CLLocation?) in
            // The signed limit of the road you are on, matched from each fix.
            guard let l = l else { return }
            app.speedLimits.onFix(lat: l.coordinate.latitude, lng: l.coordinate.longitude,
                                  headingDeg: l.course >= 0 ? l.course : nil)
            headingUp.update(speed: max(0, l.speed), heading: l.course >= 0 ? l.course : nil, now: Date())
        }
        .onChange(of: app.layerTraffic) { (_: Bool, on: Bool) in
            if !on { app.area.clear() }
        }
        .onChange(of: vm.room?.code) { (_: String?, new: String?) in
            // Both dock states share one detent: a room appearing or going starts at the peek.
            detent = .peek
            // The QR sheet has no room to show once you have left.
            if new == nil { showInviteQR = false }
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

    private func pinTapped(_ id: String) {
        if layers.handlePinTap(id, app: app) { return }
        if id.hasPrefix("cruise-c-") {
            let key: String = String(id.dropFirst(9))
            openNearbyConvoy = app.convoy.nearbyConvoys.first { (c: NearbyConvoy) -> Bool in return c.code == key }
        } else if id.hasPrefix("cruise-d-") {
            let key: String = String(id.dropFirst(9))
            openNearbyDriver = app.convoy.nearbyDrivers.first { (d: NearbyDriver) -> Bool in return d.id == key }
        }
    }

    /// "N spots nearby": frames them when tapped, and stops the map following you so they stay in view.
    private func layerHint(_ visible: Bool, _ bottom: CGFloat) -> some View {
        LayerHint(viewport: viewport) { (points: [CLLocationCoordinate2D]) in
            follow = false
            fitCoords = ConvoyView.withMe(points, app.convoy.location.latest?.coordinate)
            fitToken += 1
        }
        .padding(.horizontal, 48)
        // Above the speed readout, which owns the bottom-left corner of the same band.
        .padding(.bottom, bottom + 68)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottom)
        .opacity(visible ? 1 : 0)
        .allowsHitTesting(visible)
    }

    /// What to frame: the pins the hint found, plus where we are, so even one pin is two points to fit.
    private static func withMe(_ points: [CLLocationCoordinate2D], _ me: CLLocationCoordinate2D?) -> [CLLocationCoordinate2D] {
        guard let me = me else { return points }
        return points + [me]
    }

    /// Speed, and the road's limit beside it, once you are moving.
    @ViewBuilder
    private func speedRow(_ vm: ConvoyViewModel) -> some View {
        let speed: Double = max(0, vm.location.latest?.speed ?? 0)
        if speed > 1.5 {
            HStack(spacing: 8) {
                let limit: Int? = app.speedLimits.limit
                SpeedReadout(speedMps: speed, over: limit != nil && speed * 3.6 > Double(limit ?? 0) + 2)
                if let l = limit { SpeedLimitSign(kmh: l) }
            }
        }
    }

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

    private func topOverlay(_ vm: ConvoyViewModel, _ rec: RideRecorder, _ typing: Bool) -> some View {
        VStack(spacing: 8) {
            if vm.room == nil && app.nav.phase == .idle {
                WhereToBar { app.showNav = true }
                DriveChips()
            }
            if vm.room != nil {
                if app.nav.guiding && !typing {
                    ManeuverCard(nav: app.nav)
                    DetourBanner(nav: app.nav)
                }
                ConvoySearch(label: searchLabel(vm)) { (open: Bool) in
                    searchActive = open
                    if open {
                        // The sheet would sit between the field and the results, and over the keyboard.
                        withAnimation(.spring(response: 0.3, dampingFraction: 0.9)) { detent = .peek }
                    }
                }
            }
            if !typing {
                bannerView(vm)
                HStack(spacing: 8) {
                    toggleChip("3D", on: is3D) { is3D.toggle() }
                    if rec.isActive {
                        recordingChip(rec)
                    }
                    Spacer()
                }
            }
        }
        .padding(.horizontal, 12)
        .padding(.top, DeviceInsets.top + 8)
    }

    /// "Add a stop" once there is something to head for, else the plain search.
    private func searchLabel(_ vm: ConvoyViewModel) -> String {
        return (app.nav.guiding || vm.room?.destination != nil) ? "Add a stop" : "Where to?"
    }

    /// Where the map should hint a way back to the line, while we are off it.
    private func rejoinHint(_ vm: ConvoyViewModel) -> (from: CLLocationCoordinate2D, to: CLLocationCoordinate2D)? {
        guard vm.room != nil, let me = app.convoy.location.latest?.coordinate else { return nil }
        let line: [CLLocationCoordinate2D] = app.nav.guiding ? app.nav.routeCoordinates : vm.mapRoute
        guard let t = Rejoin.target(line: line, me: me) else { return nil }
        return (from: me, to: t)
    }

    /// The camera to look through: the navigation one while guiding, else heading-up once you are moving, so the map
    /// turns with the road the way it does on Android. nil leaves the plain north-up follow.
    private func followCamera(_ vm: ConvoyViewModel, guiding: Bool) -> FollowCamera? {
        if guiding { return app.nav.followCamera }
        guard follow, let h: Double = headingUp.held, let me = vm.location.latest?.coordinate else { return nil }
        return FollowCamera(coordinate: me, heading: h, altitude: 2500)
    }

    @ViewBuilder
    private func bannerView(_ vm: ConvoyViewModel) -> some View {
        VStack(spacing: 8) {
            HazardAlertBanner()
            StatusBanners()
            VoiceBanner()
            if let hint = app.convoyHint {
                Banner(title: hint, kind: .info, icon: "flag.fill",
                       actionTitle: "OK", action: { app.convoyHint = nil })
            }
            if let inv = vm.invite, vm.room == nil {
                Banner(title: (inv.fromName.map { Formatters.shownName($0) } ?? "A driver") + " invited you",
                       subtitle: "Join " + ((inv.name ?? "").isEmpty ? "their convoy" : inv.name!) + "?",
                       kind: .pulse, icon: "person.3.fill",
                       actionTitle: "Join", action: { vm.joinOpen(code: inv.code) },
                       secondaryTitle: "No", secondaryAction: { vm.invite = nil })
            }
            if let n = app.photoscoutNote {
                Banner(title: n, kind: .info, icon: "camera.aperture", actionTitle: "OK", action: { app.photoscoutNote = nil })
            }
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
        .accessibilityLabel("Open " + app.rideWords.noun + " mode")
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
            iconButton("location.north.fill", tint: follow ? theme.signal : theme.ink, label: "Centre on my location") {
                follow = true
                recenter += 1
            }
            iconButton("square.3.layers.3d", tint: layersAnyOn(app) ? theme.signal : theme.ink, label: "Map layers") { showLayers = true }
            iconButton("exclamationmark.triangle", tint: theme.hazard, label: "Report a hazard") { showReport = true }
            iconButton(rec.isActive ? "gauge.with.dots.needle.67percent" : "record.circle",
                       tint: rec.isActive ? theme.pulse : theme.signal,
                       label: rec.isActive ? "Open " + app.rideWords.noun + " mode" : "Start recording a " + app.rideWords.noun) {
                if rec.isActive {
                    app.showRide = true
                } else {
                    rec.start(vehicleId: app.garage.primary?.id, convoyCode: vm.room?.code, words: app.rideWords)
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
                noConvoyDock(m, h)
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

    /// No convoy: the peek is the start / join prompt; pulled up, the same detents as a convoy sheet.
    private func noConvoyDock(_ m: SheetMetrics, _ h: CGFloat) -> some View {
        // Settled detent, not the live height: content that appears mid-drag changes the sheet's own layout.
        let showBody: Bool = detent != .peek
        return VStack(spacing: 0) {
            VStack(alignment: .leading, spacing: 10) {
                Capsule()
                    .fill(theme.lineStrong)
                    .frame(width: 40, height: 4)
                    .frame(maxWidth: .infinity, minHeight: 24)
                    .contentShape(Rectangle())
                    .onTapGesture { cycleDetent() }
                    .accessibilityLabel(app.rideWords.Noun + " sheet handle")
                    .accessibilityHint("Double tap to change the sheet size")
                    .accessibilityAddTraits(.isButton)
                Text("No convoy yet").font(CvFont.display(20))
                Text("Start one and share the code, or join with a code.")
                    .font(CvFont.text(13))
                    .foregroundStyle(theme.inkMuted)
                HStack(spacing: 10) {
                    CvButton(title: "Start") { showCreate = true }
                    CvButton(title: "Join", kind: .ghost) { showJoin = true }
                    CvButton(title: "Routes", kind: .ghost) { app.showRoutes = true }
                }
            }
            .padding(.horizontal, 16)
            .gesture(sheetDrag(m))

            if showBody {
                DriveDockContent()
                    .padding(.top, 4)
                    .frame(maxHeight: .infinity, alignment: .top)
            } else {
                Spacer(minLength: 0)
            }
        }
        .padding(.top, 10)
        .padding(.bottom, m.reserve)
    }

    private func sheetDrag(_ m: SheetMetrics) -> some Gesture {
        return DragGesture(minimumDistance: 6)
            .updating($dragY) { (value: DragGesture.Value, state: inout CGFloat, _: inout Transaction) in
                state = value.translation.height
            }
            .onEnded { (value: DragGesture.Value) in
                settle(value, m)
            }
    }

    private func convoySheetContent(_ vm: ConvoyViewModel, _ room: Room, _ m: SheetMetrics, _ h: CGFloat) -> some View {
        // Settled detent, not the live height: content (and the header's Share/QR buttons) that appears mid-drag
        // re-lays-out the sheet under the finger, which is what made the drawer jump.
        let showBody: Bool = detent != .peek
        let drag = sheetDrag(m)
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
                header(vm, room, showBody)
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

    private func header(_ vm: ConvoyViewModel, _ room: Room, _ expanded: Bool) -> some View {
        HStack(alignment: .center, spacing: expanded ? 6 : 10) {
            VStack(alignment: .leading, spacing: 2) {
                Text(room.name)
                    .font(CvFont.display(20))
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
                HStack(spacing: 8) {
                    Button {
                        UIPasteboard.general.string = room.code
                        markCodeCopied()
                    } label: {
                        Text(copiedCode ? "COPIED" : room.code)
                            .font(CvFont.mono(12, weight: .medium))
                            .tracking(1.0)
                            .foregroundStyle(copiedCode ? theme.go : theme.signal)
                            .lineLimit(1)
                            .padding(.horizontal, 6)
                            .frame(minHeight: 24)
                            .cvPanel(fill: theme.surface200, edge: copiedCode ? theme.go : theme.lineStrong, cut: Cut.s)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Copy invite code \(room.code)")
                    .layoutPriority(2)
                    Text("\(vm.onlineCount) of \(room.members.count) online")
                        .font(CvFont.label(11))
                        .tracking(1.0)
                        .foregroundStyle(theme.inkMuted)
                        .lineLimit(1)
                        .minimumScaleFactor(0.6)
                }
            }
            .layoutPriority(0)
            Spacer(minLength: 0)
            if expanded {
                ShareLink(item: InviteLinks.shareText(room), subject: Text("Join my convoy")) {
                    Image(systemName: "square.and.arrow.up")
                        .font(.system(size: 18, weight: .semibold))
                        .foregroundStyle(theme.ink)
                        .frame(width: 40, height: 40)
                        .cvPanel(fill: theme.surface200, edge: theme.lineStrong, cut: Cut.s)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Share the invite")
                .fixedSize()
                .layoutPriority(1)
                Button {
                    showInviteQR = true
                } label: {
                    Image(systemName: "qrcode")
                        .font(.system(size: 18, weight: .semibold))
                        .foregroundStyle(theme.ink)
                        .frame(width: 40, height: 40)
                        .cvPanel(fill: theme.surface200, edge: theme.lineStrong, cut: Cut.s)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Show QR code")
                .fixedSize()
                .layoutPriority(1)
            }
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
            .fixedSize()
            .layoutPriority(1)
            ConvoyPTT(size: 56)
                .layoutPriority(1)
        }
        .padding(.horizontal, 16)
        .frame(height: ConvoyView.headerHeight - 24)
        .contentShape(Rectangle())
        .onDisappear {
            copyTask?.cancel()
            copiedCode = false
        }
    }

    /// "COPIED" on the code chip for a moment after a tap.
    private func markCodeCopied() {
        copyTask?.cancel()
        copiedCode = true
        copyTask = Task { @MainActor in
            try? await Task.sleep(nanoseconds: 1_500_000_000)
            if !Task.isCancelled { copiedCode = false }
        }
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
