import SwiftUI
import UIKit

// MARK: - CvButton

enum CvButtonKind {
    case primary
    case ghost
    case alert
}

struct CvButtonStyle: ButtonStyle {
    let kind: CvButtonKind
    @Environment(\.theme) private var theme

    private var fill: Color {
        switch kind {
        case .primary: return theme.signal
        case .ghost: return Color.clear
        case .alert: return theme.pulse
        }
    }

    private var edge: Color {
        switch kind {
        case .primary: return theme.signal
        case .ghost: return theme.lineStrong
        case .alert: return theme.pulse
        }
    }

    private var textColor: Color {
        switch kind {
        case .primary: return theme.signalInk
        case .ghost: return theme.ink
        case .alert: return Color.white
        }
    }

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(CvFont.text(14, weight: .bold))
            .foregroundStyle(textColor)
            .frame(maxWidth: .infinity, minHeight: 48)
            .padding(.horizontal, 12)
            .cvPanel(fill: fill, edge: edge, cut: Cut.m)
            .opacity(configuration.isPressed ? 0.75 : 1)
    }
}

struct CvButton: View {
    let title: String
    var icon: String? = nil
    var kind: CvButtonKind = .primary
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 8) {
                if let icon = icon {
                    Image(systemName: icon)
                }
                Text(title.uppercased()).tracking(1.2)
            }
        }
        .buttonStyle(CvButtonStyle(kind: kind))
    }
}

// MARK: - Segmented

struct Segmented<T: Hashable>: View {
    let items: [T]
    let title: (T) -> String
    @Binding var selection: T
    @Environment(\.theme) private var theme

    var body: some View {
        HStack(spacing: 0) {
            ForEach(items, id: \.self) { (item: T) in
                let on: Bool = (item == selection)
                Button {
                    selection = item
                } label: {
                    Text(title(item).uppercased())
                        .font(CvFont.label(12))
                        .tracking(1.2)
                        .foregroundStyle(on ? theme.signalInk : theme.inkMuted)
                        .frame(maxWidth: .infinity, minHeight: 40)
                        .background(on ? ChamferShape(cut: Cut.s).fill(theme.signal) : ChamferShape(cut: Cut.s).fill(Color.clear))
                }
                .buttonStyle(.plain)
            }
        }
        .padding(2)
        .cvPanel(fill: theme.surface200, edge: theme.lineStrong, cut: Cut.s)
    }
}

// MARK: - Avatar

/// The one avatar every person in the app gets. Round.
/// - `photoURL` (a server media path such as `/v1/media/<id>/thumb`) is the main picture once it has loaded.
/// - `vehicle`, their primary vehicle, is its PS1 render: a badge at the bottom right (about 42% of the diameter,
///   overlapping the edge, ringed in surface-100) when there is a photo, and the main picture when there is not.
/// - With neither, the initials.
struct CvAvatar: View {
    var photoURL: String? = nil
    var vehicle: VehicleBuild? = nil
    let initials: String
    var size: CGFloat = 40
    var ring: Color? = nil
    @Environment(AppState.self) private var app
    @Environment(\.theme) private var theme
    @State private var photo: UIImage?

    private var shownPhoto: UIImage? {
        if (photoURL ?? "").isEmpty { return nil }
        return photo
    }

    var body: some View {
        let hasPhoto: Bool = shownPhoto != nil
        ZStack {
            Circle().fill(theme.surface300)
            if let img = shownPhoto {
                Image(uiImage: img).resizable().scaledToFill()
            } else if let v = vehicle {
                Ps1Thumb(build: v, size: Ps1ThumbSize.badge).padding(size * 0.04)
            } else {
                Text(initials.uppercased())
                    .font(CvFont.display(size * 0.36))
                    .foregroundStyle(theme.ink)
            }
        }
        .frame(width: size, height: size)
        .clipShape(Circle())
        .overlay(Circle().stroke(ring ?? theme.lineStrong, lineWidth: ring == nil ? 1 : 2.5))
        .overlay(alignment: .topLeading) {
            if hasPhoto, let v = vehicle {
                badge(v)
            }
        }
        .task(id: photoURL ?? "") {
            await loadPhoto()
        }
    }

    /// The vehicle badge: its centre sits on the circle's edge at 45 degrees, bottom right.
    private func badge(_ v: VehicleBuild) -> some View {
        let ringWidth: CGFloat = 2
        let inner: CGFloat = size * 0.42
        let whole: CGFloat = inner + ringWidth * 2
        let at: CGFloat = size / 2 + size / 2 * 0.7071 - whole / 2
        return ZStack {
            Circle().fill(theme.surface100)
            Circle().fill(theme.surface300).padding(ringWidth)
            Ps1Thumb(build: v, size: Ps1ThumbSize.badge).padding(ringWidth + 1)
        }
        .frame(width: whole, height: whole)
        .clipShape(Circle())
        .offset(x: at, y: at)
    }

    private func loadPhoto() async {
        guard let path = photoURL, !path.isEmpty else {
            photo = nil
            return
        }
        let key: String = app.serverURL + path
        if let hit = ImageCache.shared.image(for: key) {
            photo = hit
            return
        }
        if let data = await app.api.fetchBytes(path: path), let img = UIImage(data: data) {
            ImageCache.shared.store(img, for: key)
            photo = img
        }
    }
}

// MARK: - RosterChip

enum RosterState {
    case leader
    case online
    case stopped
    case sos
    case offline
}

struct RosterChip: View {
    let initials: String
    let name: String
    let detail: String
    var distance: String? = nil
    var distanceCaption: String? = nil
    var photoURL: String? = nil
    var vehicle: VehicleBuild? = nil
    var state: RosterState = .online
    @Environment(\.theme) private var theme

    private var ring: Color {
        switch state {
        case .leader: return theme.lead
        case .online: return theme.go
        case .stopped: return theme.pulse
        case .sos: return theme.pulse
        case .offline: return theme.lineStrong
        }
    }

    private var alarmed: Bool { return state == .stopped || state == .sos }

    var body: some View {
        HStack(spacing: 12) {
            CvAvatar(photoURL: photoURL, vehicle: vehicle, initials: initials, size: 40, ring: ring)
            VStack(alignment: .leading, spacing: 2) {
                Text(name)
                    .font(CvFont.text(15, weight: .bold))
                    .foregroundStyle(theme.ink)
                Text(detail)
                    .font(CvFont.text(12, weight: .medium))
                    .foregroundStyle(alarmed ? theme.pulse : theme.inkMuted)
            }
            Spacer(minLength: 8)
            if let distance = distance {
                VStack(alignment: .trailing, spacing: 0) {
                    Text(distance)
                        .font(CvFont.mono(15))
                        .foregroundStyle(theme.ink)
                    if let cap = distanceCaption {
                        Text(cap.uppercased())
                            .font(CvFont.label(9))
                            .foregroundStyle(theme.inkMuted)
                    }
                }
            }
        }
        .padding(.horizontal, 12)
        .frame(minHeight: 56)
        .cvPanel(fill: theme.surface200, edge: alarmed ? theme.pulse : theme.line, cut: Cut.s)
    }
}

// MARK: - Banner

enum BannerKind {
    case pulse
    case hazard
    case police
    case go
    case info
}

struct Banner: View {
    let title: String
    var subtitle: String? = nil
    var kind: BannerKind = .info
    var icon: String = "person.3.fill"
    var actionTitle: String? = nil
    var action: (() -> Void)? = nil
    var secondaryTitle: String? = nil
    var secondaryAction: (() -> Void)? = nil
    @Environment(\.theme) private var theme

    private var tint: Color {
        switch kind {
        case .pulse: return theme.pulse
        case .hazard: return theme.hazard
        case .police: return theme.police
        case .go: return theme.go
        case .info: return theme.signal
        }
    }

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: icon)
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(tint == theme.pulse ? Color.white : theme.signalInk)
                .frame(width: 36, height: 36)
                .background(ChamferShape(cut: Cut.s).fill(tint))
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(CvFont.text(14, weight: .bold))
                    .foregroundStyle(theme.ink)
                if let subtitle = subtitle {
                    Text(subtitle)
                        .font(CvFont.text(12, weight: .medium))
                        .foregroundStyle(theme.inkMuted)
                }
            }
            Spacer(minLength: 8)
            if let secondaryTitle = secondaryTitle, let secondaryAction = secondaryAction {
                Button(action: secondaryAction) {
                    Text(secondaryTitle.uppercased())
                        .font(CvFont.label(12))
                        .tracking(1.0)
                        .foregroundStyle(theme.inkMuted)
                        .padding(.horizontal, 8)
                        .frame(minHeight: 44)
                }
                .buttonStyle(.plain)
            }
            if let actionTitle = actionTitle, let action = action {
                Button(action: action) {
                    Text(actionTitle.uppercased())
                        .font(CvFont.label(12))
                        .tracking(1.0)
                        .foregroundStyle(tint)
                        .padding(.horizontal, 8)
                        .frame(minHeight: 44)
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 8)
        .cvPanel(fill: theme.glass, edge: tint, cut: Cut.m)
    }
}

// MARK: - Readout

struct Readout: View {
    let caption: String
    let value: String
    var unit: String? = nil
    var size: CGFloat = 28
    var color: Color? = nil
    var alignment: HorizontalAlignment = .leading
    @Environment(\.theme) private var theme

    var body: some View {
        VStack(alignment: alignment, spacing: 2) {
            Text(caption.uppercased())
                .font(CvFont.label(10))
                .tracking(1.4)
                .foregroundStyle(theme.inkMuted)
            HStack(alignment: .lastTextBaseline, spacing: 3) {
                Text(value)
                    .font(CvFont.mono(size))
                    .foregroundStyle(color ?? theme.ink)
                if let unit = unit {
                    Text(unit.uppercased())
                        .font(CvFont.mono(max(10, size * 0.38), weight: .medium))
                        .foregroundStyle(theme.inkMuted)
                }
            }
        }
    }
}

// MARK: - RideTile

enum RideTileTone {
    case neutral
    case signal
    case hazard
    case police
    case alert
}

struct RideTile: View {
    let caption: String
    var value: String? = nil
    var unit: String? = nil
    var icon: String? = nil
    var tone: RideTileTone = .neutral
    var valueColor: Color? = nil
    var compact: Bool = false
    let action: () -> Void
    @Environment(\.theme) private var theme

    private var edge: Color {
        switch tone {
        case .neutral: return theme.lineStrong
        case .signal: return theme.signal
        case .hazard: return theme.hazard
        case .police: return theme.police
        case .alert: return theme.pulse
        }
    }

    private var iconColor: Color {
        switch tone {
        case .hazard: return theme.hazard
        case .police: return theme.police
        default: return fg
        }
    }

    private var fill: Color {
        return tone == .alert ? theme.pulse : theme.surface200
    }

    private var fg: Color {
        return tone == .alert ? Color.white : theme.ink
    }

    var body: some View {
        Button(action: action) {
            VStack(spacing: 6) {
                if let icon = icon {
                    Image(systemName: icon)
                        .font(.system(size: compact ? 24 : 28, weight: .semibold))
                        .foregroundStyle(iconColor)
                }
                if let value = value {
                    HStack(alignment: .lastTextBaseline, spacing: 3) {
                        Text(value)
                            .font(CvFont.mono(compact ? 28 : 34))
                            .foregroundStyle(valueColor ?? fg)
                        if let unit = unit {
                            Text(unit.uppercased())
                                .font(CvFont.mono(14, weight: .medium))
                                .foregroundStyle(theme.inkMuted)
                        }
                    }
                }
                Text(caption.uppercased())
                    .font(CvFont.label(11))
                    .tracking(1.4)
                    .foregroundStyle(tone == .alert ? Color.white : theme.inkMuted)
            }
            .frame(maxWidth: .infinity, minHeight: compact ? 84 : 104)
            .cvPanel(fill: fill, edge: edge, cut: Cut.m)
        }
        .buttonStyle(.plain)
    }
}

// MARK: - PushToTalk

/// The push-to-talk button. Three looks: idle (signal), live while held (pulse) and receiving someone else (go).
/// `unavailable` greys it out and ignores presses (no voice connection).
struct PushToTalk: View {
    var size: CGFloat = 72
    var label: String = "HOLD"
    var receiving: Bool = false
    var live: Bool = false
    var unavailable: Bool = false
    var onChange: (Bool) -> Void = { (_: Bool) in }
    @State private var pressed: Bool = false
    @Environment(\.theme) private var theme

    var body: some View {
        let tint: Color = unavailable ? theme.inkMuted : (live || pressed ? theme.pulse : (receiving ? theme.go : theme.signal))
        let filled: Bool = (pressed || live) && !unavailable
        VStack(spacing: 2) {
            Image(systemName: unavailable ? "mic.slash.fill" : (receiving && !filled ? "speaker.wave.2.fill" : "mic.fill"))
                .font(.system(size: size * 0.32, weight: .semibold))
            Text(label.uppercased())
                .font(CvFont.label(9))
                .tracking(1.0)
                .lineLimit(1)
                .minimumScaleFactor(0.7)
        }
        .foregroundStyle(filled ? Color.white : tint)
        .frame(width: size, height: size)
        .background(Circle().fill(filled ? tint : theme.glass))
        .overlay(Circle().stroke(tint, lineWidth: 2))
        .shadow(color: tint.opacity(filled ? 0.6 : 0.25), radius: filled ? 16 : 8)
        .contentShape(Circle())
        .gesture(
            DragGesture(minimumDistance: 0)
                .onChanged { (_: DragGesture.Value) in
                    if !pressed {
                        pressed = true
                        onChange(true)
                    }
                }
                .onEnded { (_: DragGesture.Value) in
                    if pressed {
                        pressed = false
                        onChange(false)
                    }
                }
        )
        .accessibilityLabel(unavailable ? "Voice unavailable" : "Hold to talk")
    }
}

/// The push-to-talk button wired to the voice channel: HOLD, LIVE while talking, or the name of who is talking.
struct ConvoyPTT: View {
    var size: CGFloat = 56
    @Environment(AppState.self) private var app

    private struct Look {
        var text: String
        var unavailable: Bool
        var receiving: Bool
    }

    private func look(_ voice: VoiceChannel) -> Look {
        if voice.state != .ready && voice.state != .connecting {
            return Look(text: "N/A", unavailable: true, receiving: false)
        }
        if voice.state == .connecting {
            return Look(text: "...", unavailable: false, receiving: false)
        }
        if voice.talking {
            return Look(text: "LIVE", unavailable: false, receiving: false)
        }
        if let s = voice.speakers.first {
            let known: String = app.convoy.nameOf(userId: s.id)
            let shown: String = (known == "Someone" && !s.name.isEmpty) ? Formatters.shownName(s.name) : known
            return Look(text: String(shown.prefix(7)), unavailable: false, receiving: true)
        }
        return Look(text: "HOLD", unavailable: false, receiving: false)
    }

    var body: some View {
        let voice: VoiceChannel = app.voice
        let l: Look = look(voice)
        PushToTalk(size: size, label: l.text, receiving: l.receiving, live: voice.talking, unavailable: l.unavailable) { (down: Bool) in
            voice.setTalking(down)
        }
    }
}

// MARK: - TabBar

enum AppTab: String, CaseIterable, Hashable {
    case home
    case feed
    case convoy
    case garage
    case profile

    var title: String {
        switch self {
        case .home: return "Home"
        case .feed: return "Feed"
        case .convoy: return "Drive"
        case .garage: return "Garage"
        case .profile: return "Profile"
        }
    }

    var symbol: String {
        switch self {
        case .home: return "house"
        case .feed: return "list.bullet.rectangle"
        case .convoy: return "steeringwheel"
        case .garage: return "car"
        case .profile: return "person.crop.circle"
        }
    }
}

struct TabBar: View {
    @Binding var selection: AppTab
    @Environment(\.theme) private var theme

    var body: some View {
        HStack(spacing: 0) {
            ForEach(AppTab.allCases, id: \.self) { (tab: AppTab) in
                Button {
                    selection = tab
                } label: {
                    if tab == .convoy {
                        Image(systemName: tab.symbol)
                            .font(.system(size: 20, weight: .semibold))
                            .foregroundStyle(theme.signalInk)
                            .frame(width: 52, height: 44)
                            .background(ChamferShape(cut: Cut.m).fill(theme.signal))
                            .frame(maxWidth: .infinity, minHeight: 52)
                    } else {
                        VStack(spacing: 3) {
                            Image(systemName: tab.symbol)
                                .font(.system(size: 18, weight: .medium))
                            Text(tab.title.uppercased())
                                .font(CvFont.label(9))
                                .tracking(1.0)
                        }
                        .foregroundStyle(selection == tab ? theme.signal : theme.inkMuted)
                        .frame(maxWidth: .infinity, minHeight: 52)
                    }
                }
                .buttonStyle(.plain)
                .accessibilityLabel(tab.title)
            }
        }
        .padding(.horizontal, 6)
        .background(.ultraThinMaterial)
        .cvPanel(fill: theme.glass, edge: theme.lineStrong, cut: Cut.l)
    }
}

// MARK: - Misc

struct SectionLabel: View {
    let text: String
    @Environment(\.theme) private var theme

    var body: some View {
        Text(text.uppercased())
            .font(CvFont.label(11))
            .tracking(1.6)
            .foregroundStyle(theme.inkMuted)
    }
}

struct CvField: View {
    let placeholder: String
    @Binding var text: String
    var keyboard: UIKeyboardType = .default
    var autocap: TextInputAutocapitalization = .never
    var mono: Bool = false
    @Environment(\.theme) private var theme

    var body: some View {
        TextField(placeholder, text: $text)
            .font(mono ? CvFont.mono(14, weight: .medium) : CvFont.text(16, weight: .semibold))
            .foregroundStyle(theme.ink)
            .keyboardType(keyboard)
            .textInputAutocapitalization(autocap)
            .autocorrectionDisabled(true)
            .padding(.horizontal, 14)
            .frame(minHeight: 52)
            .cvPanel(fill: theme.surface200, edge: theme.lineStrong, cut: Cut.m)
    }
}
