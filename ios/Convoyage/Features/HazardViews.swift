import SwiftUI
import CoreLocation

/// One tile on the report screen: what the server calls it (kind + subkind) and what we show.
struct HazardPreset: Identifiable {
    let id: String
    let label: String
    let kind: String
    /// Must match ^[a-z0-9_]{0,24}$ (protocol/openapi.json).
    let subkind: String
    let icon: String
    let tone: RideTileTone
}

enum HazardPresets {
    static let all: [HazardPreset] = [
        HazardPreset(id: "debris", label: "Debris", kind: HazardKinds.hazard, subkind: "debris", icon: "exclamationmark.triangle", tone: .hazard),
        HazardPreset(id: "gravel", label: "Gravel", kind: HazardKinds.hazard, subkind: "gravel", icon: "exclamationmark.triangle", tone: .hazard),
        HazardPreset(id: "oil", label: "Oil", kind: HazardKinds.hazard, subkind: "oil", icon: "drop.fill", tone: .hazard),
        HazardPreset(id: "wet_ice", label: "Wet / ice", kind: HazardKinds.hazard, subkind: "wet_ice", icon: "snowflake", tone: .hazard),
        HazardPreset(id: "police", label: "Police", kind: HazardKinds.police, subkind: "", icon: "shield.lefthalf.filled", tone: .police),
        HazardPreset(id: "speed_camera", label: "Speed camera", kind: HazardKinds.police, subkind: "speed_camera", icon: "camera.fill", tone: .police),
        HazardPreset(id: "crash", label: "Crash", kind: HazardKinds.crash, subkind: "", icon: "exclamationmark.octagon.fill", tone: .alert),
        HazardPreset(id: "works", label: "Road works", kind: HazardKinds.works, subkind: "", icon: "cone.fill", tone: .hazard)
    ]
}

/// Hazard report picker. A tap sends `POST /v1/hazards` with the current location and heading; convoy members get it
/// live over the socket and everyone nearby sees it on the map for 30 minutes.
struct ReportView: View {
    @Environment(AppState.self) private var app
    @Environment(\.theme) private var theme
    @Environment(\.dismiss) private var dismiss
    @State private var sending: String?
    @State private var sent: String?
    @State private var errorText: String?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                HStack {
                    VStack(alignment: .leading, spacing: 4) {
                        SectionLabel(text: "Hazard")
                        Text("Report").font(CvFont.display(30))
                    }
                    Spacer()
                    Button {
                        dismiss()
                    } label: {
                        Image(systemName: "xmark")
                            .font(.system(size: 18, weight: .semibold))
                            .frame(width: 48, height: 48)
                            .cvPanel(fill: theme.surface200, edge: theme.lineStrong, cut: Cut.s)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Close")
                }

                if app.authKind != .account {
                    Banner(title: "Sign in with your email to report",
                           subtitle: "Dev sign-in has no account behind it, so the server cannot take reports.",
                           kind: .hazard, icon: "lock.fill")
                }
                if let s = sending {
                    Banner(title: "Sending \(s)...", kind: .hazard, icon: "exclamationmark.triangle")
                }
                if let s = sent {
                    Banner(title: "\(s) reported", subtitle: "Visible to nearby drivers for 30 minutes.", kind: .go, icon: "checkmark.circle")
                }
                if let e = errorText {
                    Banner(title: e, kind: .pulse, icon: "exclamationmark.triangle")
                }

                LazyVGrid(columns: [GridItem(.flexible(), spacing: 12), GridItem(.flexible(), spacing: 12)], spacing: 12) {
                    ForEach(HazardPresets.all) { (preset: HazardPreset) in
                        RideTile(caption: preset.label, icon: preset.icon, tone: preset.tone, action: { report(preset) })
                    }
                }
            }
            .padding(20)
        }
        .background(theme.surface100.ignoresSafeArea())
        .foregroundStyle(theme.ink)
    }

    private func report(_ preset: HazardPreset) {
        if sending != nil { return }
        guard let loc = app.convoy.location.latest, abs(loc.timestamp.timeIntervalSinceNow) < 120 else {
            errorText = "Waiting for a location fix. Check that location is on, then try again."
            sent = nil
            return
        }
        sending = preset.label
        sent = nil
        errorText = nil
        let heading: Double? = loc.course >= 0 ? loc.course : nil
        Task {
            let err: String? = await app.hazards.report(kind: preset.kind, subkind: preset.subkind,
                                                        lat: loc.coordinate.latitude, lng: loc.coordinate.longitude, heading: heading)
            sending = nil
            if let e = err {
                errorText = e
            } else {
                sent = preset.label
                try? await Task.sleep(nanoseconds: 1_400_000_000)
                dismiss()
            }
        }
    }
}

/// Glass banner for the newest hazard a convoy member reported: hazard amber (police blue for police) with Confirm
/// ("still there") and Dismiss ("not there"). Shows nothing when there is no unanswered report.
struct HazardAlertBanner: View {
    @Environment(AppState.self) private var app

    var body: some View {
        if let a = app.hazards.alert {
            let h: HazardDto = a.hazard
            let away: String? = app.convoy.location.latest.map { (loc: CLLocation) -> String in
                return Formatters.distanceText(loc.distance(from: CLLocation(latitude: h.lat, longitude: h.lng))) + " away"
            }
            let sub: String = a.error ?? [away, "Still there?"].compactMap { (s: String?) -> String? in return s }.joined(separator: "  /  ")
            Banner(title: hazardLabel(kind: h.kind, subkind: h.subkind) + " reported",
                   subtitle: sub,
                   kind: h.isPolice ? .police : .hazard,
                   icon: "exclamationmark.triangle.fill",
                   actionTitle: "Confirm",
                   action: { app.hazards.confirm(id: h.id) },
                   secondaryTitle: "Dismiss",
                   secondaryAction: { app.hazards.dismiss(id: h.id) })
                .task(id: h.id) {
                    // Nobody is going to answer a banner they did not see; do not leave it up for ever.
                    try? await Task.sleep(nanoseconds: 30_000_000_000)
                    app.hazards.clearAlert(id: h.id)
                }
        }
    }
}
