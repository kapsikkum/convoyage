import SwiftUI

extension Color {
    /// 0xRRGGBB
    init(hex: UInt32, opacity: Double = 1) {
        let r: Double = Double((hex >> 16) & 0xFF) / 255.0
        let g: Double = Double((hex >> 8) & 0xFF) / 255.0
        let b: Double = Double(hex & 0xFF) / 255.0
        self.init(.sRGB, red: r, green: g, blue: b, opacity: opacity)
    }
}

/// Design tokens, one set per colour scheme. Mirrors the Android theme.
struct Theme: Equatable {
    let void: Color
    let surface100: Color
    let surface200: Color
    let surface300: Color
    let line: Color
    let lineStrong: Color
    let ink: Color
    let inkMuted: Color
    let signal: Color
    let signalInk: Color
    let pulse: Color
    let hazard: Color
    let police: Color
    let go: Color
    let lead: Color
    let glass: Color

    static let night: Theme = Theme(
        void: Color(hex: 0x05080d),
        surface100: Color(hex: 0x0a1018),
        surface200: Color(hex: 0x101a26),
        surface300: Color(hex: 0x172434),
        line: Color(hex: 0x1e2f42),
        lineStrong: Color(hex: 0x2f4862),
        ink: Color(hex: 0xe8f1fa),
        inkMuted: Color(hex: 0x8ea4ba),
        signal: Color(hex: 0x2de2e6),
        signalInk: Color(hex: 0x021416),
        pulse: Color(hex: 0xff3d8b),
        hazard: Color(hex: 0xffb020),
        police: Color(hex: 0x6f97ff),
        go: Color(hex: 0x46e08c),
        lead: Color(hex: 0xc9b6ff),
        glass: Color(hex: 0x0a1018, opacity: 0.78)
    )

    static let day: Theme = Theme(
        void: Color(hex: 0xe9eff4),
        surface100: Color(hex: 0xffffff),
        surface200: Color(hex: 0xf2f6f9),
        surface300: Color(hex: 0xe2e9ef),
        line: Color(hex: 0xc9d4de),
        lineStrong: Color(hex: 0x8a9cad),
        ink: Color(hex: 0x06121e),
        inkMuted: Color(hex: 0x3d5064),
        signal: Color(hex: 0x00777d),
        signalInk: Color(hex: 0xffffff),
        pulse: Color(hex: 0xc4004f),
        hazard: Color(hex: 0x8f5400),
        police: Color(hex: 0x2443c4),
        go: Color(hex: 0x137a3e),
        lead: Color(hex: 0x5b3fc0),
        glass: Color(hex: 0xffffff, opacity: 0.82)
    )

    static func resolve(_ scheme: ColorScheme) -> Theme {
        return scheme == .dark ? Theme.night : Theme.day
    }
}

private struct ThemeKey: EnvironmentKey {
    static let defaultValue: Theme = Theme.night
}

extension EnvironmentValues {
    var theme: Theme {
        get { return self[ThemeKey.self] }
        set { self[ThemeKey.self] = newValue }
    }
}

/// Injects the Theme matching the current colour scheme.
struct ThemedRoot<Content: View>: View {
    @Environment(\.colorScheme) private var scheme
    let content: Content

    init(@ViewBuilder content: () -> Content) {
        self.content = content()
    }

    var body: some View {
        content.environment(\.theme, Theme.resolve(scheme))
    }
}

/// Fonts. Falls back to the system font silently if a custom font is missing.
/// `missingFamilies()` lists any bundled family iOS did not register (check UIAppFonts in project.yml); the app logs it at launch.
enum CvFont {
    /// Families the app expects, as iOS names them.
    static let families: [String] = ["Chakra Petch", "Manrope", "JetBrains Mono"]

    static func missingFamilies() -> [String] {
        return families.filter { (f: String) -> Bool in return UIFont.fontNames(forFamilyName: f).isEmpty }
    }

    /// Chakra Petch (display). Static weights: 500, 600, 700.
    static func display(_ size: CGFloat, weight: Font.Weight = .bold) -> Font {
        let name: String
        switch weight {
        case .bold, .heavy, .black: name = "ChakraPetch-Bold"
        case .semibold: name = "ChakraPetch-SemiBold"
        default: name = "ChakraPetch-Medium"
        }
        return Font.custom(name, size: size)
    }

    /// Manrope (text). Variable font, so the weight modifier picks the instance.
    static func text(_ size: CGFloat, weight: Font.Weight = .medium) -> Font {
        return Font.custom("Manrope", size: size).weight(weight)
    }

    /// JetBrains Mono (numbers), monospaced digits.
    static func mono(_ size: CGFloat, weight: Font.Weight = .semibold) -> Font {
        return Font.custom("JetBrains Mono", size: size).weight(weight).monospacedDigit()
    }

    /// Small uppercase caption.
    static func label(_ size: CGFloat = 11) -> Font {
        return text(size, weight: .bold)
    }
}
