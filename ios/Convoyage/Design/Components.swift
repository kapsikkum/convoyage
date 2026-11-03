import SwiftUI

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

struct CvAvatar: View {
    let initials: String
    var size: CGFloat = 40
    var ring: Color? = nil
    @Environment(\.theme) private var theme

    var body: some View {
        Text(initials.uppercased())
            .font(CvFont.display(size * 0.36))
            .foregroundStyle(theme.ink)
            .frame(width: size, height: size)
            .background(Circle().fill(theme.surface300))
            .overlay(Circle().stroke(ring ?? theme.lineStrong, lineWidth: ring == nil ? 1 : 2.5))
    }
}

// MARK: - RosterChip

enum RosterState {
    case leader
    case online
    case stopped
    case offline
}

struct RosterChip: View {
    let initials: String
    let name: String
    let detail: String
    var distance: String? = nil
    var distanceCaption: String? = nil
    var state: RosterState = .online
    @Environment(\.theme) private var theme

    private var ring: Color {
        switch state {
        case .leader: return theme.lead
        case .online: return theme.go
        case .stopped: return theme.pulse
        case .offline: return theme.lineStrong
        }
    }

    var body: some View {
        HStack(spacing: 12) {
            CvAvatar(initials: initials, size: 40, ring: ring)
            VStack(alignment: .leading, spacing: 2) {
                Text(name)
                    .font(CvFont.text(15, weight: .bold))
                    .foregroundStyle(theme.ink)
                Text(detail)
                    .font(CvFont.text(12, weight: .medium))
                    .foregroundStyle(state == .stopped ? theme.pulse : theme.inkMuted)
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
        .cvPanel(fill: theme.surface200, edge: state == .stopped ? theme.pulse : theme.line, cut: Cut.s)
    }
}

// MARK: - Banner

enum BannerKind {
    case pulse
    case hazard
    case info
}

struct Banner: View {
    let title: String
    var subtitle: String? = nil
    var kind: BannerKind = .info
    var icon: String = "person.3.fill"
    var actionTitle: String? = nil
    var action: (() -> Void)? = nil
    @Environment(\.theme) private var theme

    private var tint: Color {
        switch kind {
        case .pulse: return theme.pulse
        case .hazard: return theme.hazard
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
            if let actionTitle = actionTitle, let action = action {
                Button(action: action) {
                    Text(actionTitle.uppercased())
                        .font(CvFont.label(12))
                        .tracking(1.0)
                        .foregroundStyle(tint)
                        .padding(.horizontal, 10)
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
    case alert
}

struct RideTile: View {
    let caption: String
    var value: String? = nil
    var unit: String? = nil
    var icon: String? = nil
    var tone: RideTileTone = .neutral
    var valueColor: Color? = nil
    let action: () -> Void
    @Environment(\.theme) private var theme

    private var edge: Color {
        switch tone {
        case .neutral: return theme.lineStrong
        case .signal: return theme.signal
        case .hazard: return theme.hazard
        case .alert: return theme.pulse
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
                        .font(.system(size: 28, weight: .semibold))
                        .foregroundStyle(tone == .hazard ? theme.hazard : fg)
                }
                if let value = value {
                    HStack(alignment: .lastTextBaseline, spacing: 3) {
                        Text(value)
                            .font(CvFont.mono(34))
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
            .frame(maxWidth: .infinity, minHeight: 104)
            .cvPanel(fill: fill, edge: edge, cut: Cut.m)
        }
        .buttonStyle(.plain)
    }
}

// MARK: - PushToTalk

/// UI only. TODO(voice): wire to audio capture and the voice channel.
struct PushToTalk: View {
    var size: CGFloat = 72
    var label: String = "HOLD"
    var receiving: Bool = false
    var onChange: (Bool) -> Void = { (_: Bool) in }
    @State private var pressed: Bool = false
    @Environment(\.theme) private var theme

    var body: some View {
        let tint: Color = receiving ? theme.go : theme.signal
        VStack(spacing: 2) {
            Image(systemName: receiving ? "speaker.wave.2.fill" : "mic.fill")
                .font(.system(size: size * 0.32, weight: .semibold))
            Text(label.uppercased())
                .font(CvFont.label(9))
                .tracking(1.2)
        }
        .foregroundStyle(pressed ? theme.signalInk : tint)
        .frame(width: size, height: size)
        .background(Circle().fill(pressed ? tint : theme.glass))
        .overlay(Circle().stroke(tint, lineWidth: 2))
        .shadow(color: tint.opacity(pressed ? 0.6 : 0.25), radius: pressed ? 16 : 8)
        .gesture(
            DragGesture(minimumDistance: 0)
                .onChanged { (_: DragGesture.Value) in
                    if !pressed {
                        pressed = true
                        onChange(true)
                    }
                }
                .onEnded { (_: DragGesture.Value) in
                    pressed = false
                    onChange(false)
                }
        )
        .accessibilityLabel("Hold to talk")
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
        case .convoy: return "Convoy"
        case .garage: return "Garage"
        case .profile: return "Profile"
        }
    }

    var symbol: String {
        switch self {
        case .home: return "house"
        case .feed: return "list.bullet.rectangle"
        case .convoy: return "person.3.fill"
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
    @Environment(\.theme) private var theme

    var body: some View {
        TextField(placeholder, text: $text)
            .font(CvFont.text(16, weight: .semibold))
            .foregroundStyle(theme.ink)
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled(true)
            .padding(.horizontal, 14)
            .frame(minHeight: 52)
            .cvPanel(fill: theme.surface200, edge: theme.lineStrong, cut: Cut.m)
    }
}
