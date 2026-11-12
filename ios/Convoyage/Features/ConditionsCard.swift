import SwiftUI

/// Relative times for condition timestamps: ISO-8601 in, "2 hr ago" out.
private enum ConditionTime {
    private static let relative: RelativeDateTimeFormatter = {
        let f: RelativeDateTimeFormatter = RelativeDateTimeFormatter()
        f.unitsStyle = .abbreviated
        return f
    }()

    /// "" when the timestamp cannot be read.
    static func ago(_ iso: String) -> String {
        guard let date: Date = Iso.parse(iso) else { return "" }
        return relative.localizedString(for: date, relativeTo: Date()).lowercased()
    }
}

/// A coloured status word: CLEAR / LIGHT / BUSY / HEAVY, NONE / n POLICE, FINE / DAMP / CARE.
private struct StatusTag: View {
    let text: String
    let color: Color

    var body: some View {
        Text(text)
            .font(CvFont.label(11))
            .tracking(1.0)
            .foregroundStyle(color)
            .lineLimit(1)
            .minimumScaleFactor(0.7)
            .frame(width: 84, height: 26)
            .background(ChamferShape(cut: Cut.s).fill(color.opacity(0.16)))
    }
}

/// A swatch and its kilometres, under the road-surface bar.
private struct RoadLegend: View {
    let label: String
    let km: Double
    let color: Color
    @Environment(\.theme) private var theme

    var body: some View {
        HStack(alignment: .center, spacing: 4) {
            Rectangle()
                .fill(color)
                .frame(width: 8, height: 8)
            Text("\(label) \(fmtKm(km))")
                .font(CvFont.label(11))
                .foregroundStyle(theme.inkMuted)
        }
    }
}

/// One thing found on the route: kilometres, an icon, the title and where it came from.
private struct FoundItem: View {
    let item: RouteConditionItemDto
    @Environment(\.theme) private var theme

    private var tint: Color {
        switch item.kind {
        case "police", "camera": return theme.police
        case "closure", "crash": return theme.pulse
        default: return theme.hazard
        }
    }

    private var symbol: String {
        switch item.kind {
        case "police", "camera": return "exclamationmark.shield"
        case "closure", "crash": return "nosign"
        case "roadwork": return "flag"
        default: return "exclamationmark.triangle"
        }
    }

    private var sub: String {
        var parts: [String] = []
        let detail: String = item.detail.trimmingCharacters(in: .whitespaces)
        if !detail.isEmpty { parts.append(detail) }
        if let at: String = item.reportedAt {
            let ago: String = ConditionTime.ago(at)
            if !ago.isEmpty { parts.append("reported " + ago) }
        }
        switch item.source {
        case "tfnsw": parts.append("Live Traffic NSW")
        case "waze": parts.append("Waze")
        default: parts.append("Convoyage")
        }
        return parts.joined(separator: " · ")
    }

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Text("KM \(fmtKm(item.km))")
                .font(CvFont.mono(13))
                .foregroundStyle(theme.inkMuted)
                .lineLimit(1)
                .frame(width: 64, alignment: .leading)
            Image(systemName: symbol)
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(tint)
                .frame(width: 16, alignment: .leading)
            VStack(alignment: .leading, spacing: 2) {
                Text(item.title)
                    .font(CvFont.text(13, weight: .bold))
                    .foregroundStyle(theme.ink)
                    .lineLimit(1)
                Text(sub)
                    .font(CvFont.text(12))
                    .foregroundStyle(theme.inkMuted)
                    .lineLimit(2)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}

/// Servos first (the cheapest U91 called out), then a line each for food, cafes, toilets, rest areas and chargers.
struct StopsList: View {
    let stops: [RouteStopDto]
    var fuelOff: Bool = false
    var max: Int = 6
    @Environment(\.theme) private var theme

    private struct StopKindLine: Identifiable {
        let kind: String
        let label: String
        var id: String { return kind }
    }

    private static let otherKinds: [StopsList.StopKindLine] = [
        StopKindLine(kind: "food", label: "Food"),
        StopKindLine(kind: "cafe", label: "Cafes"),
        StopKindLine(kind: "toilets", label: "Toilets"),
        StopKindLine(kind: "rest", label: "Rest areas"),
        StopKindLine(kind: "ev", label: "EV charging"),
    ]

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            SectionLabel(text: "Stops on the way")
            let servos: [RouteStopDto] = stops.filter { (s: RouteStopDto) -> Bool in return s.kind == "fuel" }
            let priced: [RouteStopDto] = servos.filter { (s: RouteStopDto) -> Bool in return s.prices["U91"] != nil }
            if let cheapest: RouteStopDto = priced.min(by: { (a: RouteStopDto, b: RouteStopDto) -> Bool in return (a.prices["U91"] ?? 0) < (b.prices["U91"] ?? 0) }),
               let u91: Double = cheapest.prices["U91"] {
                Text("Cheapest U91: \(displayName(cheapest, fallback: "servo")) at km \(fmtKm(cheapest.km)), \(String(format: "%.1f", u91))")
                    .font(CvFont.text(13, weight: .bold))
                    .foregroundStyle(theme.go)
            }
            ForEach(Array(servos.prefix(max))) { (s: RouteStopDto) in
                HStack(alignment: .top, spacing: 12) {
                    Text("KM \(fmtKm(s.km))")
                        .font(CvFont.mono(13))
                        .foregroundStyle(theme.inkMuted)
                        .lineLimit(1)
                        .frame(width: 64, alignment: .leading)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(displayName(s, fallback: "Servo") + (s.open24 ? " · 24/7" : ""))
                            .font(CvFont.text(13, weight: .bold))
                            .foregroundStyle(theme.ink)
                            .lineLimit(1)
                        let prices: String = priceLine(stop: s)
                        if !prices.isEmpty {
                            Text(prices)
                                .font(CvFont.mono(12, weight: .medium))
                                .foregroundStyle(theme.signal)
                                .lineLimit(1)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
            if servos.count > max {
                Text("and \(servos.count - max) more servos")
                    .font(CvFont.text(13))
                    .foregroundStyle(theme.inkMuted)
            }
            ForEach(StopsList.otherKinds) { (line: StopsList.StopKindLine) in
                let these: [RouteStopDto] = stops.filter { (s: RouteStopDto) -> Bool in return s.kind == line.kind }
                if !these.isEmpty {
                    Text(otherLine(these, label: line.label))
                        .font(CvFont.text(13))
                        .foregroundStyle(theme.inkMuted)
                        .lineLimit(2)
                }
            }
            if fuelOff && !servos.isEmpty {
                Text("Fuel prices need a NSW FuelCheck key on the server.")
                    .font(CvFont.label(11))
                    .foregroundStyle(theme.inkMuted)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func displayName(_ s: RouteStopDto, fallback: String) -> String {
        let name: String = s.name.trimmingCharacters(in: .whitespaces)
        if !name.isEmpty { return name }
        let brand: String = s.brand.trimmingCharacters(in: .whitespaces)
        return brand.isEmpty ? fallback : brand
    }

    /// "Food: Roadhouse km 12 · Cafe km 40 +2".
    private func otherLine(_ these: [RouteStopDto], label: String) -> String {
        let fallback: String = label.hasSuffix("s") ? String(label.dropLast()) : label
        let names: String = these.prefix(3).map { (s: RouteStopDto) -> String in
            return "\(displayName(s, fallback: fallback)) km \(fmtKm(s.km))"
        }.joined(separator: " · ")
        let extra: String = these.count > 3 ? " +\(these.count - 3)" : ""
        return "\(label): " + names + extra
    }
}

/// Traffic, police, weather, road surfaces and stops along a route, at a glance, with what was found listed by
/// distance. Sources that are not set up or did not answer are said in one line at the bottom.
struct ConditionsCard: View {
    let store: RouteConditionsStore
    var onRefresh: (() -> Void)? = nil
    @Environment(\.theme) private var theme

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            header
            if let v: RouteConditionsDto = store.value {
                content(v)
            } else if let message: String = store.error {
                Text(message)
                    .font(CvFont.text(13))
                    .foregroundStyle(theme.pulse)
            } else {
                Text("Looking up traffic, police, weather and road surfaces along the route.")
                    .font(CvFont.text(13))
                    .foregroundStyle(theme.inkMuted)
            }
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .cvPanel(fill: theme.surface200, edge: theme.line, cut: Cut.l)
    }

    // MARK: Header

    private var header: some View {
        HStack(alignment: .center, spacing: 8) {
            SectionLabel(text: "Conditions")
            Spacer(minLength: 8)
            Text(checkedText)
                .font(CvFont.label(11))
                .foregroundStyle(theme.inkMuted)
            if let onRefresh = onRefresh {
                Button(action: onRefresh) {
                    Image(systemName: "arrow.clockwise")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(theme.inkMuted)
                        .frame(width: 32, height: 32)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Check again")
            }
        }
    }

    private var checkedText: String {
        if store.loading && store.value == nil { return "Checking..." }
        if store.loading { return "Updating..." }
        if let v: RouteConditionsDto = store.value {
            let ago: String = ConditionTime.ago(v.computedAt).uppercased()
            return ago.isEmpty ? "CHECKED" : "CHECKED " + ago
        }
        return ""
    }

    // MARK: Sections

    @ViewBuilder
    private func content(_ v: RouteConditionsDto) -> some View {
        trafficRow(v)
        policeRow(v)
        weatherSection(v)
        roadsSection(v)
        stopsSection(v)
        foundSection(v)
        footerSection(v)
    }

    private func trafficRow(_ v: RouteConditionsDto) -> some View {
        return HStack(alignment: .center, spacing: 12) {
            StatusTag(text: levelWord(v.traffic.level), color: levelColor(v.traffic.level))
            Text(v.traffic.summary)
                .font(CvFont.text(13))
                .foregroundStyle(theme.ink)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private func policeRow(_ v: RouteConditionsDto) -> some View {
        let empty: Bool = v.police.isEmpty
        let text: String = empty
            ? "No police or cameras reported on the route"
            : v.police.prefix(3).map { (i: RouteConditionItemDto) -> String in return "\(i.title) at km \(fmtKm(i.km))" }
                .joined(separator: " · ")
        return HStack(alignment: .center, spacing: 12) {
            StatusTag(text: empty ? "NONE" : "\(v.police.count) POLICE", color: empty ? theme.go : theme.police)
            Text(text)
                .font(CvFont.text(13))
                .foregroundStyle(theme.ink)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    @ViewBuilder
    private func weatherSection(_ v: RouteConditionsDto) -> some View {
        if !v.weather.points.isEmpty {
            HStack(alignment: .center, spacing: 12) {
                StatusTag(text: weatherWord(v.weather.worst), color: weatherColor(v.weather.worst))
                Text(v.weather.summary)
                    .font(CvFont.text(13))
                    .foregroundStyle(theme.ink)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            HStack(alignment: .top, spacing: 8) {
                ForEach(Array(v.weather.points.prefix(5)), id: \.km) { (p: RouteWeatherPointDto) in
                    VStack(alignment: .leading, spacing: 2) {
                        Text("KM \(fmtKm(p.km))")
                            .font(CvFont.label(10))
                            .foregroundStyle(theme.inkMuted)
                            .lineLimit(1)
                        Text(p.tempC.map { (t: Double) -> String in return "\(Int(t.rounded()))°" } ?? "–")
                            .font(CvFont.mono(15))
                            .foregroundStyle(theme.ink)
                            .lineLimit(1)
                        Text(p.label)
                            .font(CvFont.label(10))
                            .foregroundStyle(theme.inkMuted)
                            .lineLimit(1)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
        }
    }

    private struct RoadBar: Identifiable {
        let id: String
        let km: Double
        let color: Color
    }

    @ViewBuilder
    private func roadsSection(_ v: RouteConditionsDto) -> some View {
        if let roads: RouteRoadsDto = v.roads {
            let total: Double = Swift.max(0.01, roads.km.good + roads.km.fair + roads.km.poor + roads.km.unknown)
            VStack(alignment: .leading, spacing: 6) {
                HStack(alignment: .center, spacing: 8) {
                    SectionLabel(text: "Road surface")
                    Spacer(minLength: 8)
                    Text(unsealedText(roads, total: total))
                        .font(CvFont.label(11))
                        .foregroundStyle(roads.unpavedKm > 0 ? theme.hazard : theme.inkMuted)
                }
                GeometryReader { (proxy: GeometryProxy) in
                    HStack(alignment: .center, spacing: 2) {
                        ForEach(roadBars(roads)) { (bar: ConditionsCard.RoadBar) in
                            Rectangle()
                                .fill(bar.color)
                                .frame(width: Swift.max(0, proxy.size.width * CGFloat(bar.km / total) - 2))
                        }
                    }
                }
                .frame(height: 8)
                HStack(alignment: .center, spacing: 12) {
                    RoadLegend(label: "Good", km: roads.km.good, color: theme.go)
                    RoadLegend(label: "Fair", km: roads.km.fair, color: theme.hazard)
                    RoadLegend(label: "Rough", km: roads.km.poor, color: theme.pulse)
                    if roads.km.unknown > 0 {
                        RoadLegend(label: "Unknown", km: roads.km.unknown, color: theme.inkMuted)
                    }
                    Spacer(minLength: 0)
                }
                if let w: RouteRoadWorstDto = roads.worst.first {
                    Text(roughestLine(w))
                        .font(CvFont.text(13))
                        .foregroundStyle(theme.inkMuted)
                }
            }
        } else if v.sources.roads == "pending" {
            Text("Road surfaces are still loading.")
                .font(CvFont.text(13))
                .foregroundStyle(theme.inkMuted)
        }
    }

    private func roadBars(_ roads: RouteRoadsDto) -> [ConditionsCard.RoadBar] {
        return [
            ConditionsCard.RoadBar(id: "good", km: roads.km.good, color: theme.go),
            ConditionsCard.RoadBar(id: "fair", km: roads.km.fair, color: theme.hazard),
            ConditionsCard.RoadBar(id: "poor", km: roads.km.poor, color: theme.pulse),
            ConditionsCard.RoadBar(id: "unknown", km: roads.km.unknown, color: theme.lineStrong),
        ].filter { (bar: ConditionsCard.RoadBar) -> Bool in return bar.km > 0 }
    }

    private func unsealedText(_ roads: RouteRoadsDto, total: Double) -> String {
        if roads.unpavedKm > 0 { return "\(fmtKm(roads.unpavedKm)) km unsealed" }
        return "Sealed all the way" + (roads.km.unknown / total > 0.3 ? " (as far as we know)" : "")
    }

    private func roughestLine(_ w: RouteRoadWorstDto) -> String {
        let name: String = w.name.trimmingCharacters(in: .whitespaces).isEmpty ? "unnamed road" : w.name
        let surface: String = w.surface.trimmingCharacters(in: .whitespaces).isEmpty
            ? ""
            : " of " + w.surface.replacingOccurrences(of: "_", with: " ")
        return "Roughest: \(name), \(fmtKm(w.km)) km" + surface
    }

    @ViewBuilder
    private func stopsSection(_ v: RouteConditionsDto) -> some View {
        if !v.stops.isEmpty {
            StopsList(stops: v.stops, fuelOff: v.sources.fuel == "off")
        } else if v.sources.stops == "pending" {
            Text("Servos and stops are still loading.")
                .font(CvFont.text(13))
                .foregroundStyle(theme.inkMuted)
        }
    }

    @ViewBuilder
    private func foundSection(_ v: RouteConditionsDto) -> some View {
        let found: [RouteConditionItemDto] = v.found
        if !found.isEmpty {
            VStack(alignment: .leading, spacing: 8) {
                SectionLabel(text: "On the route")
                ForEach(Array(found.prefix(12))) { (item: RouteConditionItemDto) in
                    FoundItem(item: item)
                }
                if found.count > 12 {
                    Text("and \(found.count - 12) more")
                        .font(CvFont.text(13))
                        .foregroundStyle(theme.inkMuted)
                }
            }
        }
    }

    @ViewBuilder
    private func footerSection(_ v: RouteConditionsDto) -> some View {
        let missing: [String] = missingSources(v)
        VStack(alignment: .leading, spacing: 8) {
            if !missing.isEmpty {
                Text("Not answering right now: \(missing.joined(separator: ", ")).")
                    .font(CvFont.text(13))
                    .foregroundStyle(theme.inkMuted)
            }
            Text(fromLine(v))
                .font(CvFont.label(11))
                .foregroundStyle(theme.inkMuted)
        }
    }

    private func missingSources(_ v: RouteConditionsDto) -> [String] {
        var out: [String] = []
        if v.sources.tfnsw == "error" { out.append("Live Traffic NSW") }
        if v.sources.waze == "error" { out.append("Waze") }
        if v.sources.weather == "error" { out.append("weather") }
        if v.sources.roads == "error" { out.append("road surfaces") }
        if v.sources.stops == "error" { out.append("stops") }
        if v.sources.fuel == "error" { out.append("FuelCheck") }
        return out
    }

    private func fromLine(_ v: RouteConditionsDto) -> String {
        var parts: [String] = []
        if v.sources.tfnsw == "ok" || v.sources.tfnsw == "partial" { parts.append("Live Traffic NSW") }
        if v.sources.waze == "ok" || v.sources.waze == "partial" { parts.append("Waze") }
        parts.append("Convoyage reports")
        if !v.weather.points.isEmpty { parts.append("Open-Meteo") }
        if v.roads != nil || !v.stops.isEmpty { parts.append("OpenStreetMap") }
        if v.sources.fuel == "ok" && v.stops.contains(where: { (s: RouteStopDto) -> Bool in return !s.prices.isEmpty }) {
            parts.append("NSW FuelCheck")
        }
        return "From " + parts.joined(separator: ", ") + "."
    }

    // MARK: Colours and words

    private func levelColor(_ level: String) -> Color {
        switch level {
        case "clear": return theme.go
        case "light": return theme.signal
        case "moderate": return theme.hazard
        default: return theme.pulse
        }
    }

    private func levelWord(_ level: String) -> String {
        switch level {
        case "clear": return "CLEAR"
        case "light": return "LIGHT"
        case "moderate": return "BUSY"
        default: return "HEAVY"
        }
    }

    private func weatherWord(_ worst: Int) -> String {
        if worst == 0 { return "FINE" }
        if worst == 1 { return "DAMP" }
        return "CARE"
    }

    private func weatherColor(_ worst: Int) -> Color {
        if worst == 0 { return theme.go }
        if worst == 1 { return theme.signal }
        return theme.hazard
    }
}
