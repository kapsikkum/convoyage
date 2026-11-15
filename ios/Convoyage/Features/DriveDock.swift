import SwiftUI

/// Shortcuts under "Where to?": saved routes, Home, Work and the last few places.
struct DriveChips: View {
    @Environment(AppState.self) private var app
    @Environment(\.theme) private var theme

    var body: some View {
        let recents: [NavPlace] = recentChips
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                chip(title: "Saved routes", icon: "point.topleft.down.curvedto.point.bottomright.up", dim: false) {
                    app.showRoutes = true
                }
                ForEach(SavedSlot.allCases, id: \.self) { (slot: SavedSlot) in
                    slotChip(slot)
                }
                ForEach(0..<recents.count, id: \.self) { (i: Int) in
                    let place: NavPlace = recents[i]
                    chip(title: place.title, icon: nil, dim: false) {
                        app.navigate(to: place)
                    }
                }
            }
            .padding(.vertical, 2)
        }
    }

    /// The last few places, without any that are already a Home or Work.
    private var recentChips: [NavPlace] {
        let saved: [SavedSlot: NavPlace] = app.places.saved
        let keep: [NavPlace] = app.places.recent.filter { (r: NavPlace) -> Bool in
            return !saved.values.contains { (p: NavPlace) -> Bool in
                return p.coordinate.latitude == r.coordinate.latitude && p.coordinate.longitude == r.coordinate.longitude
            }
        }
        return Array(keep.prefix(3))
    }

    private func slotChip(_ slot: SavedSlot) -> some View {
        let place: NavPlace? = app.places.saved[slot]
        return chip(title: slot.label, icon: slotIcon(slot), dim: place == nil) {
            pick(slot)
        }
    }

    private func pick(_ slot: SavedSlot) {
        let place: NavPlace? = app.places.saved[slot]
        if let p = place {
            app.navigate(to: p)
        } else {
            app.convoyHint = "Search for " + slot.label.lowercased() + ", then tap Set as " + slot.label
        }
    }

    private func slotIcon(_ slot: SavedSlot) -> String {
        switch slot {
        case .home: return "house.fill"
        case .work: return "briefcase.fill"
        }
    }

    private func chip(title: String, icon: String?, dim: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 6) {
                if let icon = icon {
                    Image(systemName: icon)
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(dim ? theme.inkMuted : theme.signal)
                }
                Text(title)
                    .font(CvFont.label(12))
                    .tracking(1.2)
                    .lineLimit(1)
                    .foregroundStyle(dim ? theme.inkMuted : theme.ink)
            }
            .padding(.horizontal, 12)
            .frame(minHeight: 36)
            .cvPanel(fill: theme.glass, edge: theme.lineStrong, cut: Cut.s)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(title)
    }
}

/// The Drive dock pulled up: Open Cruise finds, the drive actions, saved places and recent destinations.
struct DriveDockContent: View {
    @Environment(AppState.self) private var app
    @Environment(\.theme) private var theme

    var body: some View {
        let vm: ConvoyViewModel = app.convoy
        let rec: RideRecorder = app.recorder
        let recents: [NavPlace] = app.places.recent
        ScrollView {
            VStack(alignment: .leading, spacing: 8) {
                OpenCruiseRow()

                if vm.wantOpenCruise && !vm.nearbyConvoys.isEmpty {
                    SectionLabel(text: "Open convoys nearby").padding(.top, 4)
                    ForEach(vm.nearbyConvoys) { (c: NearbyConvoy) in
                        convoyRow(c, vm)
                    }
                }

                if vm.wantOpenCruise && !vm.nearbyDrivers.isEmpty {
                    SectionLabel(text: "Drivers cruising nearby").padding(.top, 4)
                    ForEach(vm.nearbyDrivers) { (d: NearbyDriver) in
                        driverRow(d, vm)
                    }
                }

                SectionLabel(text: "Drive").padding(.top, 4)
                HStack(spacing: 10) {
                    CvButton(title: rec.isActive ? "Recording" : "Track drive",
                             icon: "record.circle",
                             kind: rec.isActive ? CvButtonKind.alert : CvButtonKind.ghost) {
                        record(rec)
                    }
                    CvButton(title: "Route builder",
                             icon: "point.topleft.down.curvedto.point.bottomright.up",
                             kind: .ghost) {
                        app.showRouteBuilder = true
                    }
                }

                SectionLabel(text: "Saved places").padding(.top, 4)
                ForEach(SavedSlot.allCases, id: \.self) { (slot: SavedSlot) in
                    slotRow(slot)
                }
                rowButton(icon: "point.topleft.down.curvedto.point.bottomright.up",
                          title: "Saved routes",
                          subtitle: "Drive one solo or as a convoy",
                          tint: theme.signal) {
                    app.showRoutes = true
                }

                if !recents.isEmpty {
                    SectionLabel(text: "Recent").padding(.top, 4)
                    ForEach(0..<recents.count, id: \.self) { (i: Int) in
                        recentRow(recents[i])
                    }
                }
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 8)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    // MARK: Actions

    private func record(_ rec: RideRecorder) {
        if rec.isActive {
            app.showRide = true
        } else {
            app.recorder.start(vehicleId: app.garage.primary?.id, convoyCode: nil)
        }
    }

    // MARK: Open Cruise rows

    private func convoyRow(_ c: NearbyConvoy, _ vm: ConvoyViewModel) -> some View {
        let title: String = convoyName(c)
        let detail: String = convoyDetail(c)
        return nearbyRow(icon: "person.3.fill", title: title, subtitle: detail, tint: theme.go,
                         action: "Join", filled: true) {
            vm.joinOpen(code: c.code)
        }
    }

    private func driverRow(_ d: NearbyDriver, _ vm: ConvoyViewModel) -> some View {
        let title: String = Formatters.shownName(d.username ?? "")
        let canInvite: Bool = d.canInvite ?? false
        let sent: Bool = vm.invited.contains(d.id)
        let action: String? = canInvite ? (sent ? "Sent" : "Invite") : nil
        let detail: String = driverDetail(d, canInvite: canInvite)
        return nearbyRow(icon: "car.fill", title: title, subtitle: detail, tint: theme.go,
                         action: action, filled: false) {
            if canInvite && !sent { vm.inviteNearby(d.id) }
        }
    }

    private func convoyName(_ c: NearbyConvoy) -> String {
        let name: String = (c.name ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        return name.isEmpty ? "Convoy" : name
    }

    private func convoyDetail(_ c: NearbyConvoy) -> String {
        var parts: [String] = []
        if let count = c.members { parts.append("\(count) IN") }
        if let distance = c.distanceM { parts.append(Formatters.distanceText(distance).uppercased()) }
        let dest: String = (c.destination ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        parts.append(dest.isEmpty ? "JUST CRUISING" : dest.uppercased())
        return parts.joined(separator: " · ")
    }

    private func driverDetail(_ d: NearbyDriver, canInvite: Bool) -> String {
        var parts: [String] = []
        if let distance = d.distanceM { parts.append(Formatters.distanceText(distance).uppercased()) }
        if !canInvite { parts.append("GET WITHIN 500 M TO INVITE") }
        return parts.joined(separator: " · ")
    }

    private func nearbyRow(icon: String, title: String, subtitle: String, tint: Color,
                           action: String?, filled: Bool, perform: @escaping () -> Void) -> some View {
        HStack(spacing: 12) {
            Image(systemName: icon)
                .font(.system(size: 18, weight: .semibold))
                .foregroundStyle(tint)
                .frame(width: 22)
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(CvFont.text(15, weight: .bold))
                    .foregroundStyle(theme.ink)
                    .lineLimit(1)
                if !subtitle.isEmpty {
                    Text(subtitle)
                        .font(CvFont.mono(11, weight: .medium))
                        .foregroundStyle(theme.inkMuted)
                        .lineLimit(1)
                }
            }
            Spacer(minLength: 8)
            if let action = action {
                Button(action: perform) {
                    Text(action.uppercased())
                        .font(CvFont.label(11))
                        .tracking(1.0)
                        .foregroundStyle(filled ? theme.signalInk : theme.ink)
                        .padding(.horizontal, 12)
                        .frame(minHeight: 32)
                        .cvPanel(fill: filled ? theme.signal : Color.clear,
                                 edge: filled ? theme.signal : theme.lineStrong, cut: Cut.s)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(action + " " + title)
            }
        }
        .padding(.horizontal, 12)
        .frame(minHeight: 56)
        .cvPanel(fill: theme.surface200, edge: theme.line, cut: Cut.m)
    }

    // MARK: Saved place and recent rows

    private func slotRow(_ slot: SavedSlot) -> some View {
        let place: NavPlace? = app.places.saved[slot]
        let subtitle: String = place?.title ?? ("Not set: search a place, then Set as " + slot.label)
        let tint: Color = place == nil ? theme.inkMuted : theme.signal
        return rowButton(icon: slotIcon(slot), title: slot.label, subtitle: subtitle,
                         tint: tint, enabled: place != nil) {
            if let p = place { app.navigate(to: p) }
        }
    }

    private func slotIcon(_ slot: SavedSlot) -> String {
        switch slot {
        case .home: return "house.fill"
        case .work: return "briefcase.fill"
        }
    }

    private func rowButton(icon: String, title: String, subtitle: String, tint: Color,
                           enabled: Bool = true, perform: @escaping () -> Void) -> some View {
        Button(action: perform) {
            HStack(spacing: 12) {
                Image(systemName: icon)
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundStyle(enabled ? tint : theme.inkMuted)
                    .frame(width: 22)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .font(CvFont.text(15, weight: .bold))
                        .foregroundStyle(theme.ink)
                        .lineLimit(1)
                    if !subtitle.isEmpty {
                        Text(subtitle)
                            .font(CvFont.text(12))
                            .foregroundStyle(theme.inkMuted)
                            .lineLimit(1)
                    }
                }
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 12)
            .frame(minHeight: 52)
            .frame(maxWidth: .infinity, alignment: .leading)
            .cvPanel(fill: theme.surface200, edge: theme.line, cut: Cut.m)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .accessibilityLabel(subtitle.isEmpty ? title : title + ". " + subtitle)
    }

    private func recentRow(_ place: NavPlace) -> some View {
        HStack(spacing: 4) {
            Button {
                app.navigate(to: place)
            } label: {
                HStack(spacing: 12) {
                    Image(systemName: "clock")
                        .font(.system(size: 18, weight: .semibold))
                        .foregroundStyle(theme.signal)
                        .frame(width: 22)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(place.title)
                            .font(CvFont.text(15, weight: .bold))
                            .foregroundStyle(theme.ink)
                            .lineLimit(1)
                        if !place.subtitle.isEmpty {
                            Text(place.subtitle)
                                .font(CvFont.text(12))
                                .foregroundStyle(theme.inkMuted)
                                .lineLimit(1)
                        }
                    }
                    Spacer(minLength: 0)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Navigate to " + place.title)
            Button {
                app.places.forget(place)
            } label: {
                Image(systemName: "xmark")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(theme.inkMuted)
                    .frame(width: 36, height: 36)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Remove " + place.title + " from recent")
        }
        .padding(.leading, 12)
        .padding(.trailing, 4)
        .frame(minHeight: 52)
        .cvPanel(fill: theme.surface200, edge: theme.line, cut: Cut.m)
    }
}
