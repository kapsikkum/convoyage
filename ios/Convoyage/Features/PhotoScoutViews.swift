import SwiftUI
import PhotosUI
import UIKit
import CoreLocation

/// Opens PhotoScout's consent page in the browser; it comes back to `convoyage://photoscout/linked`.
struct PhotoScoutConnectButton: View {
    var title: String = "Connect PhotoScout"
    @Environment(AppState.self) private var app
    @Environment(\.openURL) private var openURL
    @State private var error: String?
    @State private var busy: Bool = false

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            CvButton(title: busy ? "Opening..." : title) {
                busy = true
                error = nil
                Task {
                    switch await app.photoscout.connectURL() {
                    case .success(let url): openURL(url)
                    case .failure(let f): error = f.message
                    }
                    busy = false
                }
            }
            .disabled(busy)
            if let e = error {
                Text(e.uppercased()).font(CvFont.label(11)).foregroundStyle(Color(hex: 0xff3d71))
            }
        }
    }
}

/// The PhotoScout rows of the layers sheet: the two switches once linked, else what they are and how to link.
struct PhotoScoutLayerRows: View {
    /// Where the map is looking, for "Map centre" in Report a spot.
    var mapCentre: CLLocationCoordinate2D? = nil
    @Environment(AppState.self) private var app
    @Environment(\.theme) private var theme
    @State private var showReport: Bool = false

    var body: some View {
        let ps: PhotoScoutLayers = app.photoscout
        Group {
            if ps.linked {
                LayerSwitchRow(title: "Photo spots",
                               sub: "PhotoScout places to shoot, with their best times. Tap one for its photo and directions.",
                               on: app.layerSpots) { app.layerSpots.toggle() }
                LayerSwitchRow(title: "Events",
                               sub: "Meets and cruises coming up nearby. Tap one to RSVP, drive there or join its convoy.",
                               on: app.layerEvents) { app.layerEvents.toggle() }
                reportRow(ps)
                if let e = ps.error {
                    Text(e.uppercased()).font(CvFont.label(11)).foregroundStyle(theme.hazard)
                }
            } else if ps.configured {
                VStack(alignment: .leading, spacing: 8) {
                    Text("Photo spots and events").font(CvFont.text(16, weight: .bold))
                    Text("From PhotoScout: link your account to show its photo spots (with the best times to shoot them) and upcoming events, and to join cruise convoys from them.")
                        .font(CvFont.text(13))
                        .foregroundStyle(theme.inkMuted)
                    PhotoScoutConnectButton()
                }
            }
        }
        .sheet(isPresented: $showReport) {
            ReportSpotSheet(mapCentre: mapCentre)
                .presentationDetents([.large])
        }
    }

    /// "Report a spot", or a relink prompt when the PhotoScout link predates the `spots:suggest` scope.
    @ViewBuilder
    private func reportRow(_ ps: PhotoScoutLayers) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Report a spot").font(CvFont.text(16, weight: .bold))
            Text(ps.canSuggest
                 ? "Send a new spot to PhotoScout. A moderator checks it before it shows on the map."
                 : "Relink PhotoScout to report spots")
                .font(CvFont.text(13))
                .foregroundStyle(theme.inkMuted)
            if ps.canSuggest {
                CvButton(title: "Report a spot", kind: .ghost) { showReport = true }
            } else {
                PhotoScoutConnectButton(title: "Relink PhotoScout")
            }
        }
    }
}

/// One layer switch: title, what it shows, ON / OFF.
struct LayerSwitchRow: View {
    let title: String
    let sub: String
    let on: Bool
    let toggle: () -> Void
    @Environment(\.theme) private var theme

    var body: some View {
        HStack(alignment: .center, spacing: 12) {
            VStack(alignment: .leading, spacing: 3) {
                Text(title).font(CvFont.text(16, weight: .bold))
                Text(sub).font(CvFont.text(13)).foregroundStyle(theme.inkMuted)
            }
            Spacer(minLength: 8)
            Button(action: toggle) {
                Text(on ? "ON" : "OFF")
                    .font(CvFont.label(12))
                    .tracking(1.2)
                    .foregroundStyle(on ? theme.signalInk : theme.ink)
                    .padding(.horizontal, 14)
                    .frame(minHeight: 36)
                    .cvPanel(fill: on ? theme.signal : theme.glass, edge: on ? theme.signal : theme.lineStrong, cut: Cut.s)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("\(title): \(on ? "on" : "off")")
        }
    }
}

/// A PhotoScout spot tapped on the map: its photo, notes, good times and directions.
struct SpotSheet: View {
    let spot: PsSpot
    @Environment(AppState.self) private var app
    @Environment(\.theme) private var theme
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                let n: Int = spot.photoCount ?? 0
                SectionLabel(text: "PhotoScout spot" + (n > 0 ? " · \(n) photo\(n == 1 ? "" : "s")" : ""))
                Text(spot.name).font(CvFont.display(22)).lineLimit(2)
                if let p = spot.photo {
                    RemoteImage(path: p.thumbUrl)
                        .frame(height: 180)
                        .frame(maxWidth: .infinity)
                        .clipShape(ChamferShape(cut: Cut.m))
                }
                if let notes = spot.notes, !notes.isEmpty {
                    Text(notes).font(CvFont.text(15)).foregroundStyle(theme.inkMuted).lineLimit(4)
                }
                if let g = SpotSheet.goodTimes(spot.goodTimes) {
                    Text("GOOD " + g).font(CvFont.label(12)).tracking(1.0).foregroundStyle(theme.signal)
                }
                if let tags = spot.tags, !tags.isEmpty {
                    TagFlow(spacing: 8) {
                        ForEach(tags.prefix(8), id: \.self) { (t: String) in
                            Text(t)
                                .font(CvFont.label(11))
                                .tracking(1.0)
                                .foregroundStyle(theme.inkMuted)
                                .padding(.horizontal, 10)
                                .frame(minHeight: 28)
                                .cvPanel(fill: theme.glass, edge: theme.lineStrong, cut: Cut.s)
                        }
                    }
                }
                CvButton(title: "Navigate here", icon: "location.fill") {
                    app.goToLayerPlace(NavPlace(coordinate: CLLocationCoordinate2D(latitude: spot.lat, longitude: spot.lng),
                                                title: spot.name, subtitle: "PhotoScout spot"))
                    dismiss()
                }
            }
            .padding(20)
        }
        .background(theme.surface100.ignoresSafeArea())
    }

    private static let phases: [String: String] = [
        "golden": "golden hour", "blue": "blue hour", "night": "at night", "sunrise": "sunrise", "sunset": "sunset", "day": "by day"
    ]
    private static let months: [String] = ["JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC"]

    /// "GOLDEN HOUR, SUNSET · MAY-AUG"-style summary, or nil when there is none.
    static func goodTimes(_ g: PsGoodTimes?) -> String? {
        guard let g = g else { return nil }
        let ph: String = (g.phases ?? []).map { (p: String) -> String in return phases[p] ?? p }.joined(separator: ", ")
        let ms: [Int] = (g.months ?? []).filter { (m: Int) -> Bool in return m >= 1 && m <= 12 }.sorted()
        var mo: String = ""
        if !ms.isEmpty && ms.count < 12 {
            if ms.count > 2 && ms.count == ms[ms.count - 1] - ms[0] + 1 {
                mo = months[ms[0] - 1] + "-" + months[ms[ms.count - 1] - 1]
            } else {
                mo = ms.map { (m: Int) -> String in return months[m - 1] }.joined(separator: " ")
            }
        }
        let co: String = (g.conditions ?? []).joined(separator: ", ")
        let parts: [String] = [ph, mo, co].filter { (s: String) -> Bool in return !s.isEmpty }
        return parts.isEmpty ? nil : parts.joined(separator: " · ").uppercased()
    }
}

/// Chips laid out left to right, wrapping onto new lines (Android's FlowRow).
private struct TagFlow: Layout {
    var spacing: CGFloat = 8

    /// The chips' sizes, the rows of indices they wrap into, and the total height at `width`.
    private func arrange(_ subviews: Subviews, width: CGFloat) -> (rows: [[Int]], sizes: [CGSize], height: CGFloat) {
        var rows: [[Int]] = []
        var row: [Int] = []
        var sizes: [CGSize] = []
        var x: CGFloat = 0
        var lineHeight: CGFloat = 0
        var height: CGFloat = 0
        for (i, sub) in subviews.enumerated() {
            let size: CGSize = sub.sizeThatFits(.unspecified)
            sizes.append(size)
            if !row.isEmpty && x + spacing + size.width > width {
                rows.append(row)
                row = []
                height += lineHeight + spacing
                x = 0
                lineHeight = 0
            }
            x += (row.isEmpty ? 0 : spacing) + size.width
            lineHeight = max(lineHeight, size.height)
            row.append(i)
        }
        if !row.isEmpty {
            rows.append(row)
            height += lineHeight
        }
        return (rows, sizes, height)
    }

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let maxWidth: CGFloat = proposal.width ?? .infinity
        let laid: (rows: [[Int]], sizes: [CGSize], height: CGFloat) = arrange(subviews, width: maxWidth)
        var widest: CGFloat = 0
        for row in laid.rows {
            var w: CGFloat = 0
            for (j, i) in row.enumerated() {
                w += (j == 0 ? 0 : spacing) + laid.sizes[i].width
            }
            widest = max(widest, w)
        }
        return CGSize(width: widest, height: laid.height)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        let laid: (rows: [[Int]], sizes: [CGSize], height: CGFloat) = arrange(subviews, width: bounds.width)
        var y: CGFloat = bounds.minY
        for row in laid.rows {
            var x: CGFloat = bounds.minX
            var lineHeight: CGFloat = 0
            for (j, i) in row.enumerated() {
                let size: CGSize = laid.sizes[i]
                if j > 0 { x += spacing }
                subviews[i].place(at: CGPoint(x: x, y: y), anchor: .topLeading, proposal: ProposedViewSize(size))
                x += size.width
                lineHeight = max(lineHeight, size.height)
            }
            y += lineHeight + spacing
        }
    }
}

/// A PhotoScout event tapped on the map: when and where, RSVP, drive there, and its convoy if it is a cruise.
struct EventSheet: View {
    let event: PsEvent
    @Environment(AppState.self) private var app
    @Environment(\.theme) private var theme
    @Environment(\.dismiss) private var dismiss
    @State private var busy: Bool = false
    @State private var error: String?

    var body: some View {
        // The live copy (RSVP counts change while the sheet is up).
        let e: PsEvent = app.photoscout.events.first { (x: PsEvent) -> Bool in return x.id == event.id } ?? event
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                SectionLabel(text: (e.cruise != nil ? "Cruise" : "Event") + " · " + EventSheet.when(e.startTime))
                Text(e.title).font(CvFont.display(22)).lineLimit(3)
                let secondary: String = EventSheet.secondary(e)
                if !secondary.isEmpty { SectionLabel(text: secondary) }
                if let cr = e.cruise {
                    let lines: [String] = EventSheet.cruiseLines(cr)
                    if !lines.isEmpty {
                        Text(lines.joined(separator: "\n")).font(CvFont.text(15)).foregroundStyle(theme.ink)
                    }
                }
                if let d = e.description, !d.isEmpty {
                    Text(d).font(CvFont.text(15)).foregroundStyle(theme.inkMuted).lineLimit(5)
                }
                Text(counts(e)).font(CvFont.label(12)).tracking(1.0).foregroundStyle(theme.signal)
                HStack(spacing: 10) {
                    CvButton(title: "Going", kind: e.myRsvp == "going" ? .primary : .ghost) {
                        rsvp(e, e.myRsvp == "going" ? nil : "going")
                    }.disabled(busy)
                    if !e.isCommunity {
                        CvButton(title: "Interested", kind: e.myRsvp == "interested" ? .primary : .ghost) {
                            rsvp(e, e.myRsvp == "interested" ? nil : "interested")
                        }.disabled(busy)
                    }
                }
                if let c = e.cruise {
                    CvButton(title: "Join the cruise convoy", icon: "person.3.fill") {
                        app.convoy.joinCruise(eventId: e.id)
                        app.tab = .convoy
                        dismiss()
                    }
                    CvButton(title: "Navigate to the meet", icon: "location.fill", kind: .ghost) {
                        go(CLLocationCoordinate2D(latitude: c.meet.lat, longitude: c.meet.lng), e.title, c.meet.note ?? "Cruise meet")
                    }
                } else if let lat = e.lat, let lng = e.lng {
                    CvButton(title: "Navigate here", icon: "location.fill", kind: .ghost) {
                        go(CLLocationCoordinate2D(latitude: lat, longitude: lng), e.title, e.venue ?? "Event")
                    }
                }
                if let err = error {
                    Text(err.uppercased()).font(CvFont.label(11)).foregroundStyle(theme.hazard)
                }
            }
            .padding(20)
        }
        .background(theme.surface100.ignoresSafeArea())
    }

    /// "VENUE · 12 KM AWAY", each part only when known (the start time is already in the label above the title).
    private static func secondary(_ e: PsEvent) -> String {
        let km: String = e.distanceKm.map { (d: Double) -> String in return String(format: "%.0f KM AWAY", d) } ?? ""
        let parts: [String] = [nonBlank(e.venue) ?? "", km].filter { (s: String) -> Bool in return !s.isEmpty }
        return parts.joined(separator: " · ")
    }

    /// A cruise's route, meet note and roll-out, one per line, each only when it has it.
    private static func cruiseLines(_ c: PsCruise) -> [String] {
        let parts: [String?] = [
            nonBlank(c.routeName).map { (s: String) -> String in return "Route: " + s },
            nonBlank(c.meet.note).map { (s: String) -> String in return "Meet: " + s },
            nonBlank(c.rollOut).map { (s: String) -> String in return "Rolling out " + s }
        ]
        return parts.compactMap { (s: String?) -> String? in return s }
    }

    private static func nonBlank(_ s: String?) -> String? {
        guard let t = s?.trimmingCharacters(in: .whitespacesAndNewlines), !t.isEmpty else { return nil }
        return t
    }

    private func counts(_ e: PsEvent) -> String {
        var parts: [String] = ["\(e.going ?? 0) GOING"]
        if !e.isCommunity { parts.append("\(e.interested ?? 0) INTERESTED") }
        return parts.joined(separator: " · ")
    }

    private func rsvp(_ e: PsEvent, _ status: String?) {
        busy = true
        error = nil
        Task {
            error = await app.photoscout.rsvp(e, status: status)
            busy = false
        }
    }

    private func go(_ c: CLLocationCoordinate2D, _ title: String, _ subtitle: String) {
        app.goToLayerPlace(NavPlace(coordinate: c, title: title, subtitle: subtitle))
        dismiss()
    }

    /// "SUN 8 NOV · 9:00 AM".
    static func when(_ iso: String) -> String {
        guard let d = ConditionSheet.parse(iso) else { return "" }
        let f: DateFormatter = DateFormatter()
        f.dateFormat = "EEE d MMM · h:mm a"
        return f.string(from: d).uppercased()
    }
}

/// Report a new spot to PhotoScout: where, its name, notes, tags and an optional photo. It waits for a PhotoScout
/// moderator, so it is not on the map (or in the spots layer) yet; the success line goes up as a hint on the map.
struct ReportSpotSheet: View {
    /// Where the map is looking, for "Map centre"; nil until the map has settled once.
    var mapCentre: CLLocationCoordinate2D? = nil
    @Environment(AppState.self) private var app
    @Environment(\.theme) private var theme
    @Environment(\.dismiss) private var dismiss
    @State private var place: SpotPlace = .here
    @State private var name: String = ""
    @State private var notes: String = ""
    @State private var tagText: String = ""
    @State private var pick: PhotosPickerItem?
    @State private var jpeg: Data?
    @State private var sending: Bool = false
    @State private var errorText: String?

    static let sentLine: String = "Sent to PhotoScout. It shows on the map once a moderator approves it."

    /// Where the suggested spot is: at the phone, or under the middle of the map.
    private enum SpotPlace: String, CaseIterable, Hashable {
        case here
        case centre

        var title: String {
            switch self {
            case .here: return "Where I am"
            case .centre: return "Map centre"
            }
        }
    }

    var body: some View {
        let placeBinding: Binding<SpotPlace> = Binding<SpotPlace>(
            get: { return place },
            set: { (p: SpotPlace) in place = p }
        )
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                SectionLabel(text: "PhotoScout")
                Text("Report a spot").font(CvFont.display(26))
                Segmented(items: SpotPlace.allCases, title: { (p: SpotPlace) -> String in return p.title }, selection: placeBinding)
                CvField(placeholder: "Name", text: $name, autocap: .words)
                notesField
                CvField(placeholder: "Tags (comma separated)", text: $tagText)
                photoSection
                if let e = errorText {
                    Text(e).font(CvFont.text(13)).foregroundStyle(theme.hazard)
                }
                CvButton(title: sending ? "Sending..." : "Report spot") { send() }
                    .opacity(sending ? 0.5 : 1)
                    .disabled(sending)
                CvButton(title: "Cancel", kind: .ghost) { dismiss() }
                    .disabled(sending)
            }
            .padding(20)
        }
        .background(theme.surface100.ignoresSafeArea())
        .foregroundStyle(theme.ink)
        .interactiveDismissDisabled(sending)
        .onChange(of: pick) { (_: PhotosPickerItem?, newValue: PhotosPickerItem?) in
            guard let p = newValue else { return }
            Task { await loadPhoto(p) }
        }
    }

    /// A multi-line notes box (the server takes 1000 characters, so it is cut there).
    private var notesField: some View {
        ZStack(alignment: .topLeading) {
            TextEditor(text: $notes)
                .font(CvFont.text(16))
                .scrollContentBackground(.hidden)
                .padding(8)
                .frame(minHeight: 96)
                .onChange(of: notes) { (_: String, newValue: String) in
                    if newValue.count > 1000 { notes = String(newValue.prefix(1000)) }
                }
            if notes.isEmpty {
                Text("Notes")
                    .font(CvFont.text(16))
                    .foregroundStyle(theme.inkMuted)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 16)
                    .allowsHitTesting(false)
            }
        }
        .cvPanel(fill: theme.surface200, edge: theme.lineStrong, cut: Cut.m)
    }

    private var photoSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            PhotosPicker(selection: $pick, matching: .images) {
                HStack(spacing: 8) {
                    Image(systemName: "photo.on.rectangle")
                    Text(jpeg == nil ? "Add a photo" : "Change the photo").tracking(1.2)
                }
                .font(CvFont.text(14, weight: .bold))
                .foregroundStyle(theme.ink)
                .frame(maxWidth: .infinity, minHeight: 48)
                .cvPanel(fill: Color.clear, edge: theme.lineStrong, cut: Cut.m)
            }
            if jpeg != nil {
                Button {
                    pick = nil
                    jpeg = nil
                } label: {
                    Text("REMOVE PHOTO")
                        .font(CvFont.label(11))
                        .tracking(1.0)
                        .foregroundStyle(theme.inkMuted)
                        .frame(minHeight: 36)
                }
                .buttonStyle(.plain)
            }
        }
    }

    private func loadPhoto(_ item: PhotosPickerItem) async {
        guard let data = try? await item.loadTransferable(type: Data.self), let img = UIImage(data: data) else {
            errorText = "That photo could not be read."
            return
        }
        jpeg = ImagePrep.jpeg(img, maxEdge: 2560)
        errorText = jpeg == nil ? "Could not prepare the photo." : nil
    }

    private func send() {
        var trimmed: String = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            errorText = "Give the spot a name."
            return
        }
        if trimmed.count > 120 { trimmed = String(trimmed.prefix(120)) }
        let coordinate: CLLocationCoordinate2D?
        switch place {
        case .here: coordinate = app.convoy.location.latest?.coordinate
        case .centre: coordinate = mapCentre
        }
        guard let c = coordinate else {
            errorText = place == .here ? "Waiting for a location fix. Check that location is on, then try again."
                                       : "The map has not settled yet. Move it a little and try again."
            return
        }
        let body: String = notes.trimmingCharacters(in: .whitespacesAndNewlines)
        let tags: [String] = PsSuggestion.parseTags(tagText)
        sending = true
        errorText = nil
        Task {
            let err: String? = await app.photoscout.reportSpot(name: trimmed, notes: body.isEmpty ? nil : body,
                                                               coordinate: c, tags: tags, jpeg: jpeg)
            sending = false
            if let e = err {
                errorText = e
            } else {
                app.convoyHint = ReportSpotSheet.sentLine
                dismiss()
            }
        }
    }
}

/// Settings: link or unlink PhotoScout. Hidden on servers without PhotoScout.
struct PhotoScoutSettings: View {
    @Environment(AppState.self) private var app
    @Environment(\.theme) private var theme
    @State private var unlinkError: String?

    var body: some View {
        let ps: PhotoScoutLayers = app.photoscout
        Group {
            if ps.configured {
                VStack(alignment: .leading, spacing: 8) {
                    SectionLabel(text: "PhotoScout")
                    if ps.linked {
                        Text("Linked" + (ps.username.map { " as \($0)" } ?? "") + ". Spots, events and cruises come from your PhotoScout account.")
                            .font(CvFont.text(13))
                            .foregroundStyle(theme.inkMuted)
                        CvButton(title: "Unlink PhotoScout", kind: .ghost) {
                            Task { unlinkError = await app.photoscout.unlink() }
                        }
                        if let e = unlinkError {
                            Text(e.uppercased()).font(CvFont.label(11)).foregroundStyle(theme.hazard)
                        }
                    } else {
                        Text("Link your PhotoScout account for photo spots and events on the map, and cruise convoys.")
                            .font(CvFont.text(13))
                            .foregroundStyle(theme.inkMuted)
                        PhotoScoutConnectButton()
                    }
                }
            }
        }
        .task { await app.photoscout.refreshStatus() }
    }
}

extension PsSpot {
    var pin: MapPin {
        return MapPin(id: "spot-" + id, initials: "", kind: .spot,
                      coordinate: CLLocationCoordinate2D(latitude: lat, longitude: lng), symbol: "camera.fill")
    }
}

extension PsEvent {
    var pin: MapPin? {
        guard let la = lat, let ln = lng else { return nil }
        return MapPin(id: "event-" + id, initials: "", kind: .event,
                      coordinate: CLLocationCoordinate2D(latitude: la, longitude: ln), symbol: "flag.fill")
    }
}
