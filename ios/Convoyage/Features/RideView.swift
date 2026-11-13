import SwiftUI
import UIKit

struct RideView: View {
    @Environment(AppState.self) private var app
    @Environment(\.theme) private var theme
    @State private var showReport: Bool = false
    @State private var lean: LeanMonitor = LeanMonitor()

    var body: some View {
        let vm: ConvoyViewModel = app.convoy
        let rec: RideRecorder = app.recorder
        let speed: Int = Formatters.kmh(vm.location.latest?.speed)
        let leaderDist: String? = vm.leaderMember.flatMap { (m: Member) -> String? in
            if m.id == vm.myId { return nil }
            return vm.distance(to: m).map { Formatters.distanceText($0) }
        }
        let lastRider: String? = vm.spreadMeters.map { Formatters.distanceText($0) }
        let recapBinding: Binding<Bool> = Binding<Bool>(
            get: { return rec.showRecap },
            set: { (v: Bool) in
                if !v { rec.closeRecap() }
            }
        )

        VStack(spacing: 14) {
            HStack(spacing: 12) {
                Button {
                    app.showRide = false
                } label: {
                    Image(systemName: "xmark")
                        .font(.system(size: 18, weight: .semibold))
                        .frame(width: 48, height: 48)
                        .cvPanel(fill: theme.surface200, edge: theme.lineStrong, cut: Cut.s)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Exit ride mode")

                HStack(spacing: 10) {
                    if rec.phase == .recording {
                        Circle().fill(theme.pulse).frame(width: 10, height: 10)
                        Text("REC").font(CvFont.label(12)).tracking(1.4).foregroundStyle(theme.pulse)
                    } else {
                        Image(systemName: "speaker.wave.2.fill").foregroundStyle(theme.go)
                    }
                    Text(vm.room?.name ?? "No convoy").font(CvFont.text(14, weight: .bold)).lineLimit(1)
                    Spacer()
                    if app.crash.enabled {
                        Image(systemName: "shield.lefthalf.filled")
                            .foregroundStyle(theme.signal)
                            .accessibilityLabel("Crash detection is on")
                    }
                }
                .padding(.horizontal, 12)
                .frame(minHeight: 48)
                .cvPanel(fill: theme.surface200, edge: rec.phase == .recording ? theme.pulse : theme.go, cut: Cut.s)

                rideMenu
            }

            noticeStack(vm)

            if app.nav.isActive { navCard(app.nav) }

            HStack(alignment: .bottom) {
                VStack(alignment: .leading, spacing: 0) {
                    Text("SPEED").font(CvFont.label(10)).tracking(1.4).foregroundStyle(theme.inkMuted)
                    HStack(alignment: .lastTextBaseline, spacing: 4) {
                        Text("\(speed)").font(CvFont.mono(88)).foregroundStyle(theme.signal)
                        Text("KM/H").font(CvFont.mono(16, weight: .medium)).foregroundStyle(theme.inkMuted)
                    }
                }
                Spacer()
                Readout(caption: "Lean", value: leanText, unit: "deg", size: 28, alignment: .trailing)
            }
            .padding(.top, 4)

            recorderBlock(rec)

            LazyVGrid(columns: [GridItem(.flexible(), spacing: 12), GridItem(.flexible(), spacing: 12)], spacing: 12) {
                RideTile(caption: "To leader", value: leaderDist ?? "--", tone: .signal, valueColor: nil, compact: true, action: {})
                RideTile(caption: "Furthest rider", value: lastRider ?? "--", valueColor: theme.pulse, compact: true, action: {})
                RideTile(caption: "Report", icon: "exclamationmark.triangle", tone: .hazard, compact: true, action: { showReport = true })
                RideTile(caption: vm.iAmStopping ? "Moving again" : "I'm stopping", icon: vm.iAmStopping ? "play.fill" : "stop.fill",
                         tone: .alert, compact: true, action: { vm.setStopping(!vm.iAmStopping) })
                RideTile(caption: "Photo", icon: "camera.fill", tone: .signal, compact: true, action: { app.photos.begin() })
                RideTile(caption: "Regroup", icon: "person.3.fill", compact: true, action: { vm.requestRegroup() })
            }

            Spacer(minLength: 0)

            HStack {
                Readout(caption: "ETA", value: "--:--", size: 24)
                Spacer()
                ConvoyPTT(size: 84)
                Spacer()
                Readout(caption: "Fuel", value: "--", unit: "km", size: 24, alignment: .trailing)
            }
        }
        .padding(.horizontal, 16)
        .padding(.top, 8)
        .padding(.bottom, 16)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(theme.void.ignoresSafeArea())
        .foregroundStyle(theme.ink)
        .overlay { AppOverlays() }
        .onAppear {
            UIApplication.shared.isIdleTimerDisabled = true
            lean.start()
            app.updateServices()
        }
        .onDisappear {
            lean.stop()
            UIApplication.shared.isIdleTimerDisabled = false
            app.updateServices()
        }
        .sheet(isPresented: $showReport) {
            ReportView()
        }
        .fullScreenCover(isPresented: recapBinding) {
            RecapView()
        }
        .photoCapture()
    }

    // MARK: Pieces

    private var leanText: String {
        guard let d = lean.degrees else { return "--" }
        return String(Int(abs(d).rounded()))
    }

    /// Next manoeuvre while navigating, big enough to read at a glance.
    private func navCard(_ nav: NavigationSession) -> some View {
        let step: NavStep? = nav.currentStep
        let dist: Double = nav.progress?.distanceToManeuver ?? 0
        let rounded: Double = dist >= 100 ? (dist / 10).rounded() * 10 : dist.rounded()
        let title: String
        let symbol: String
        switch nav.phase {
        case .routing:
            title = "Finding route..."
            symbol = "location.north.fill"
        case .arrived:
            title = "Arrived"
            symbol = "flag.checkered"
        default:
            title = step.map { (s: NavStep) -> String in return Maneuver.title(for: s) } ?? "Follow route"
            symbol = Maneuver.symbol(type: step?.type ?? "depart", modifier: step?.modifier)
        }
        return HStack(spacing: 14) {
            Image(systemName: symbol)
                .font(.system(size: 34, weight: .bold))
                .foregroundStyle(theme.signal)
                .frame(width: 48, height: 48)
            VStack(alignment: .leading, spacing: 2) {
                if nav.phase == .navigating {
                    Text(Formatters.distanceText(rounded)).font(CvFont.mono(28)).foregroundStyle(theme.signal)
                }
                Text(title).font(CvFont.text(16, weight: .bold)).lineLimit(2)
            }
            Spacer(minLength: 0)
        }
        .padding(12)
        .cvPanel(fill: theme.surface200, edge: theme.signal, cut: Cut.m)
        .accessibilityElement(children: .combine)
    }

    /// Ride settings: automatic recording when a convoy starts or is joined, the opt-in crash detection, and a
    /// DEBUG item that starts the countdown.
    private var rideMenu: some View {
        let autoBinding: Binding<Bool> = Binding<Bool>(
            get: { return app.autoRecordConvoy },
            set: { (v: Bool) in
                app.autoRecordConvoy = v
            }
        )
        let crashBinding: Binding<Bool> = Binding<Bool>(
            get: { return app.crash.enabled },
            set: { (v: Bool) in
                app.crash.setEnabled(v)
                app.updateServices()
            }
        )
        return Menu {
            Toggle("Record convoy rides automatically", isOn: autoBinding)
            Toggle("Crash detection", isOn: crashBinding)
            #if DEBUG
            Button("Test crash alert") { app.crash.debugTrigger() }
            #endif
        } label: {
            Image(systemName: "ellipsis")
                .font(.system(size: 18, weight: .semibold))
                .foregroundStyle(theme.ink)
                .frame(width: 48, height: 48)
                .cvPanel(fill: theme.surface200, edge: theme.lineStrong, cut: Cut.s)
        }
        .accessibilityLabel("Ride settings")
    }

    @ViewBuilder
    private func noticeStack(_ vm: ConvoyViewModel) -> some View {
        VStack(spacing: 8) {
            StatusBanners()
            HazardAlertBanner()
            if let m = app.photos.message {
                Banner(title: m, kind: .info, icon: "camera.fill", actionTitle: "OK", action: { app.photos.message = nil })
            }
            if vm.iAmStopping {
                Banner(title: "You told the convoy you are stopping", kind: .pulse, icon: "stop.fill")
            }
        }
    }

    @ViewBuilder
    private func recorderBlock(_ rec: RideRecorder) -> some View {
        switch rec.phase {
        case .idle:
            CvButton(title: "Start ride", icon: "record.circle") {
                rec.start(vehicleId: app.garage.primary?.id, convoyCode: app.convoy.room?.code)
            }
        case .recording, .paused:
            VStack(spacing: 10) {
                HStack {
                    Readout(caption: "Time", value: Formatters.duration(rec.elapsedS), size: 24)
                    Spacer()
                    let d = Formatters.distance(rec.distanceM)
                    Readout(caption: "Distance", value: d.value, unit: d.unit, size: 24)
                    Spacer()
                    Readout(caption: "Max", value: "\(Formatters.kmh(rec.maxSpeedMps))", unit: "km/h", size: 24, alignment: .trailing)
                }
                if rec.weakGps {
                    Text("WAITING FOR A BETTER GPS FIX")
                        .font(CvFont.label(10))
                        .tracking(1.2)
                        .foregroundStyle(theme.hazard)
                }
                HStack(spacing: 10) {
                    if rec.phase == .recording {
                        CvButton(title: "Pause", icon: "pause.fill", kind: .ghost) { rec.pause() }
                    } else {
                        CvButton(title: "Resume", icon: "play.fill") { rec.resume() }
                    }
                    CvButton(title: "Stop", icon: "stop.fill", kind: .alert) { rec.stop() }
                }
            }
            .padding(12)
            .cvPanel(fill: theme.surface200, edge: rec.phase == .recording ? theme.pulse : theme.lineStrong, cut: Cut.m)
        case .finishing:
            if !rec.showRecap {
                Banner(title: "Ride not uploaded", subtitle: "Saved on this phone", kind: .hazard, icon: "exclamationmark.triangle",
                       actionTitle: "Open", action: { rec.reopenRecap() })
            }
        }
    }
}
