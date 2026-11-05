import SwiftUI
import CoreLocation

/// Open Cruise in the no-convoy sheet: on or off, and what it found within 5 km (they show on the map).
struct OpenCruiseRow: View {
    @Environment(AppState.self) private var app
    @Environment(\.theme) private var theme

    var body: some View {
        let vm: ConvoyViewModel = app.convoy
        HStack(alignment: .center, spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text(vm.wantOpenCruise ? "OPEN CRUISE · ON" : "OPEN CRUISE · OFF")
                    .font(CvFont.label(11))
                    .tracking(1.2)
                    .foregroundStyle(vm.wantOpenCruise ? theme.go : theme.inkMuted)
                Text(summary(vm))
                    .font(CvFont.text(13))
                    .foregroundStyle(theme.inkMuted)
                    .lineLimit(1)
            }
            Spacer(minLength: 8)
            Toggle("", isOn: Binding<Bool>(get: { return vm.wantOpenCruise }, set: { (v: Bool) in vm.setOpenCruise(v) }))
                .labelsHidden()
                .tint(theme.signal)
                .accessibilityLabel("Open Cruise")
        }
    }

    private func summary(_ vm: ConvoyViewModel) -> String {
        if !vm.wantOpenCruise { return "See drivers and open convoys nearby" }
        if !vm.openCruise { return "Connecting..." }
        let d: Int = vm.nearbyDrivers.count
        let c: Int = vm.nearbyConvoys.count
        if d == 0 && c == 0 { return "Nobody else cruising within 5 km yet" }
        var parts: [String] = []
        if d > 0 { parts.append("\(d) driver" + (d == 1 ? "" : "s")) }
        if c > 0 { parts.append("\(c) convoy" + (c == 1 ? "" : "s")) }
        return parts.joined(separator: " and ") + " within 5 km, on the map"
    }
}

/// An open convoy nearby, tapped on the map.
struct NearbyConvoySheet: View {
    let convoy: NearbyConvoy
    @Environment(AppState.self) private var app
    @Environment(\.theme) private var theme
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("OPEN CONVOY").font(CvFont.label(12)).tracking(1.2).foregroundStyle(theme.go)
            Text(convoy.name ?? "Convoy").font(CvFont.display(22))
            Text(detail).font(CvFont.text(14)).foregroundStyle(theme.inkMuted)
            CvButton(title: "Join convoy", icon: "person.3.fill") {
                app.convoy.joinOpen(code: convoy.code)
                dismiss()
            }
            Spacer(minLength: 0)
        }
        .padding(20)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(theme.surface100.ignoresSafeArea())
    }

    private var detail: String {
        var parts: [String] = []
        if let n = convoy.members { parts.append("\(n) in") }
        if let d = convoy.distanceM { parts.append(Formatters.distanceText(d) + " away") }
        parts.append(convoy.destination.map { (s: String) -> String in return "heading to " + s } ?? "just cruising")
        return parts.joined(separator: " · ")
    }
}

/// An Open Cruise driver nearby, tapped on the map: invite them once within 500 m.
struct NearbyDriverSheet: View {
    let driver: NearbyDriver
    @Environment(AppState.self) private var app
    @Environment(\.theme) private var theme

    var body: some View {
        let vm: ConvoyViewModel = app.convoy
        let sent: Bool = vm.invited.contains(driver.id)
        let can: Bool = driver.canInvite ?? false
        VStack(alignment: .leading, spacing: 10) {
            Text("CRUISING NEARBY").font(CvFont.label(12)).tracking(1.2).foregroundStyle(theme.go)
            Text(Formatters.shownName(driver.username ?? "")).font(CvFont.display(22))
            Text((driver.distanceM.map { Formatters.distanceText($0) + " away" } ?? "Nearby")
                 + (can ? "" : " · get within 500 m to invite"))
                .font(CvFont.text(14))
                .foregroundStyle(theme.inkMuted)
            if can {
                CvButton(title: sent ? "Invite sent" : "Invite to a convoy", icon: "paperplane.fill", kind: sent ? .ghost : .primary) {
                    if !sent { vm.inviteNearby(driver.id) }
                }
                .disabled(sent)
            }
            Spacer(minLength: 0)
        }
        .padding(20)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(theme.surface100.ignoresSafeArea())
    }
}

extension NearbyDriver {
    var pin: MapPin {
        return MapPin(id: "cruise-d-" + id,
                      initials: Formatters.initials(Formatters.shownName(username ?? "")),
                      kind: .member,
                      coordinate: CLLocationCoordinate2D(latitude: lat, longitude: lng))
    }
}

extension NearbyConvoy {
    var pin: MapPin {
        return MapPin(id: "cruise-c-" + code, initials: "OPEN", kind: .lead,
                      coordinate: CLLocationCoordinate2D(latitude: lat, longitude: lng))
    }
}
