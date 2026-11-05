import SwiftUI
import CoreLocation

/// The "Where to?" bar on the Drive map: opens search (the navigation screen starts on it).
struct WhereToBar: View {
    let action: () -> Void
    @Environment(\.theme) private var theme

    var body: some View {
        Button(action: action) {
            HStack(spacing: 10) {
                Image(systemName: "magnifyingglass")
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundStyle(theme.signal)
                Text("Where to?")
                    .font(CvFont.text(16))
                    .foregroundStyle(theme.inkMuted)
                Spacer()
            }
            .padding(.horizontal, 14)
            .frame(maxWidth: .infinity, minHeight: 50)
            .background(.ultraThinMaterial, in: ChamferShape(cut: Cut.m))
            .cvPanel(fill: theme.glass, edge: theme.signal, cut: Cut.m)
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Search for a destination")
    }
}

/// Your speed in km/h, amber when over the road's limit.
struct SpeedReadout: View {
    let speedMps: Double
    var over: Bool = false
    @Environment(\.theme) private var theme

    var body: some View {
        VStack(spacing: 0) {
            Text("\(Int((speedMps * 3.6).rounded()))")
                .font(CvFont.mono(28, weight: .bold))
                .foregroundStyle(over ? theme.hazard : theme.ink)
            Text("KM/H")
                .font(CvFont.label(10))
                .tracking(1.2)
                .foregroundStyle(theme.inkMuted)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 6)
        .cvPanel(fill: theme.glass, edge: over ? theme.hazard : theme.lineStrong, cut: Cut.m)
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(Int((speedMps * 3.6).rounded())) kilometres an hour")
    }
}

/// The road's signed limit, drawn like the sign itself (a physically round thing, so it is round).
struct SpeedLimitSign: View {
    let kmh: Int

    var body: some View {
        Text("\(kmh)")
            .font(CvFont.mono(kmh >= 100 ? 18 : 22, weight: .bold))
            .foregroundStyle(Color.black)
            .frame(width: 56, height: 56)
            .background(Circle().fill(Color.white))
            .overlay(Circle().stroke(Color(hex: 0xd7262e), lineWidth: 5))
            .accessibilityLabel("Speed limit \(kmh)")
    }
}

/// The map's layer switches.
struct LayersSheet: View {
    @Environment(AppState.self) private var app
    @Environment(\.theme) private var theme

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            SectionLabel(text: "Map layers")
            let from: String = app.area.waze ? "Waze and Live Traffic NSW" : "Live Traffic NSW"
            LayerSwitchRow(title: "Police and traffic",
                           sub: "Police, speed cameras, crashes, closures, roadworks and jams around you, from \(from). Tap one for details.",
                           on: app.layerTraffic) {
                app.layerTraffic.toggle()
            }
            if app.layerTraffic && app.area.scanning {
                Text("WAZE IS SCANNING THIS AREA")
                    .font(CvFont.label(11))
                    .tracking(1.0)
                    .foregroundStyle(theme.inkMuted)
            }
            PhotoScoutLayerRows()
            Spacer(minLength: 0)
        }
        .padding(20)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(theme.surface100.ignoresSafeArea())
    }
}

/// A police report, camera or traffic item tapped on the map.
struct ConditionSheet: View {
    let item: ConditionItemDto
    @Environment(\.theme) private var theme

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(eyebrow)
                .font(CvFont.label(12))
                .tracking(1.2)
                .foregroundStyle(item.kind == "police" || item.kind == "camera" ? theme.police : theme.hazard)
            Text(item.title).font(CvFont.display(22))
            if !item.detail.isEmpty {
                Text(item.detail).font(CvFont.text(15)).foregroundStyle(theme.inkMuted)
            }
            Spacer(minLength: 0)
        }
        .padding(20)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(theme.surface100.ignoresSafeArea())
    }

    private var eyebrow: String {
        let source: String
        switch item.source {
        case "waze": source = "WAZE"
        case "tfnsw": source = "LIVE TRAFFIC NSW"
        default: source = "CONVOYAGE"
        }
        guard let raw = item.reportedAt, let at = ConditionSheet.parse(raw) else { return source }
        let min: Int = max(0, Int(Date().timeIntervalSince(at) / 60))
        let age: String
        if min < 1 { age = "JUST NOW" }
        else if min < 60 { age = "\(min) MIN AGO" }
        else if min < 48 * 60 { age = "\(min / 60) H AGO" }
        else { age = "\(min / 1440) DAYS AGO" }
        return source + " · " + age
    }

    static func parse(_ s: String) -> Date? {
        let f: ISO8601DateFormatter = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let d = f.date(from: s) { return d }
        f.formatOptions = [.withInternetDateTime]
        return f.date(from: s)
    }
}

extension ConditionItemDto {
    /// The map pin for this item: police and cameras as police pins, the rest as hazard pins, with a short word.
    var pin: MapPin {
        let police: Bool = kind == "police" || kind == "camera"
        return MapPin(id: "area-" + id,
                      initials: AreaConditionsStore.word(kind),
                      kind: police ? .police : .hazard,
                      coordinate: CLLocationCoordinate2D(latitude: lat, longitude: lng))
    }
}
