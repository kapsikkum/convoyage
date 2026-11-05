import SwiftUI
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
    @Environment(AppState.self) private var app
    @Environment(\.theme) private var theme

    var body: some View {
        let ps: PhotoScoutLayers = app.photoscout
        if ps.linked {
            LayerSwitchRow(title: "Photo spots",
                           sub: "PhotoScout places to shoot, with their best times. Tap one for its photo and directions.",
                           on: app.layerSpots) { app.layerSpots.toggle() }
            LayerSwitchRow(title: "Events",
                           sub: "Meets and cruises coming up nearby. Tap one to RSVP, drive there or join its convoy.",
                           on: app.layerEvents) { app.layerEvents.toggle() }
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
                    Text(tags.prefix(8).joined(separator: " · ")).font(CvFont.text(13)).foregroundStyle(theme.inkMuted)
                }
                CvButton(title: "Navigate here", icon: "location.fill") {
                    app.nav.start(to: NavPlace(coordinate: CLLocationCoordinate2D(latitude: spot.lat, longitude: spot.lng),
                                               title: spot.name, subtitle: "PhotoScout spot"))
                    dismiss()
                    app.showNav = true
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
                mo = ms.map { (m: Int) -> String in return months[m - 1] }.joined(separator: ", ")
            }
        }
        let parts: [String] = [ph.uppercased(), mo].filter { (s: String) -> Bool in return !s.isEmpty }
        return parts.isEmpty ? nil : parts.joined(separator: " · ")
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
                let place: String = [e.venue ?? "", e.address ?? ""].filter { (s: String) -> Bool in return !s.isEmpty }.joined(separator: ", ")
                if !place.isEmpty { Text(place).font(CvFont.text(14)).foregroundStyle(theme.inkMuted) }
                if let d = e.description, !d.isEmpty {
                    Text(d).font(CvFont.text(15)).foregroundStyle(theme.inkMuted).lineLimit(5)
                }
                Text(counts(e)).font(CvFont.label(12)).tracking(1.0).foregroundStyle(theme.signal)
                HStack(spacing: 10) {
                    if e.myRsvp == nil {
                        CvButton(title: "Going") { rsvp(e, "going") }.disabled(busy)
                        if !e.isCommunity {
                            CvButton(title: "Interested", kind: .ghost) { rsvp(e, "interested") }.disabled(busy)
                        }
                    } else {
                        CvButton(title: e.myRsvp == "going" ? "You're going · Undo" : "Interested · Undo", kind: .ghost) { rsvp(e, nil) }.disabled(busy)
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
        app.nav.start(to: NavPlace(coordinate: c, title: title, subtitle: subtitle))
        dismiss()
        app.showNav = true
    }

    /// "SUN 8 NOV · 9:00 AM".
    static func when(_ iso: String) -> String {
        guard let d = ConditionSheet.parse(iso) else { return "" }
        let f: DateFormatter = DateFormatter()
        f.dateFormat = "EEE d MMM · h:mm a"
        return f.string(from: d).uppercased()
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
