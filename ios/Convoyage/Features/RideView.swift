import SwiftUI
import UIKit

struct RideView: View {
    @Environment(AppState.self) private var app
    @Environment(\.theme) private var theme

    var body: some View {
        let vm: ConvoyViewModel = app.convoy
        let speed: Int = Formatters.kmh(vm.location.latest?.speed)
        let leaderDist: String? = vm.leaderMember.flatMap { (m: Member) -> String? in
            if m.id == vm.myId { return nil }
            return vm.distance(to: m).map { Formatters.distanceText($0) }
        }
        let lastRider: String? = vm.spreadMeters.map { Formatters.distanceText($0) }

        VStack(spacing: 16) {
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
                    Image(systemName: "speaker.wave.2.fill").foregroundStyle(theme.go)
                    Text(vm.room?.name ?? "No convoy").font(CvFont.text(14, weight: .bold))
                    Text("quiet").font(CvFont.text(13)).foregroundStyle(theme.inkMuted)
                    Spacer()
                }
                .padding(.horizontal, 12)
                .frame(minHeight: 48)
                .cvPanel(fill: theme.surface200, edge: theme.go, cut: Cut.s)
            }

            // TODO(nav): turn-by-turn instruction card goes here.

            HStack(alignment: .bottom) {
                VStack(alignment: .leading, spacing: 0) {
                    Text("SPEED").font(CvFont.label(10)).tracking(1.4).foregroundStyle(theme.inkMuted)
                    HStack(alignment: .lastTextBaseline, spacing: 4) {
                        Text("\(speed)").font(CvFont.mono(96)).foregroundStyle(theme.signal)
                        Text("KM/H").font(CvFont.mono(16, weight: .medium)).foregroundStyle(theme.inkMuted)
                    }
                }
                Spacer()
                // TODO(lean): lean angle needs CoreMotion.
                Readout(caption: "Lean", value: "--", unit: "deg", size: 28, alignment: .trailing)
            }
            .padding(.top, 8)

            LazyVGrid(columns: [GridItem(.flexible(), spacing: 12), GridItem(.flexible(), spacing: 12)], spacing: 12) {
                RideTile(caption: "To leader", value: leaderDist ?? "--", tone: .signal) {}
                RideTile(caption: "Furthest rider", value: lastRider ?? "--", valueColor: theme.pulse) {}
                RideTile(caption: "Report", icon: "exclamationmark.triangle", tone: .hazard) {
                    // TODO(report): hazard reporting.
                }
                RideTile(caption: "I'm stopping", icon: "stop.fill", tone: .alert) {
                    // TODO(stop): tell the convoy you are stopping.
                }
            }

            Spacer()

            HStack {
                Readout(caption: "ETA", value: "--:--", size: 28)
                Spacer()
                PushToTalk(size: 96, label: "HOLD")
                Spacer()
                Readout(caption: "Fuel", value: "--", unit: "km", size: 28, alignment: .trailing)
            }
        }
        .padding(.horizontal, 16)
        .padding(.top, 8)
        .padding(.bottom, 20)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(theme.void.ignoresSafeArea())
        .foregroundStyle(theme.ink)
        .onAppear { UIApplication.shared.isIdleTimerDisabled = true }
        .onDisappear { UIApplication.shared.isIdleTimerDisabled = false }
    }
}
