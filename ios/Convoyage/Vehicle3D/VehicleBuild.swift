import Foundation
import CryptoKit

// What the PS1 renderer needs to draw a vehicle. Port of android/.../render/Ps1Spec.kt.

enum Ps1Kind: String {
    case car = "car"
    case ute = "ute"
    case fourwd = "4wd"
    case motorcycle = "motorcycle"

    static func of(_ wire: String?) -> Ps1Kind {
        return Ps1Kind(rawValue: wire ?? "") ?? Ps1Kind.car
    }
}

enum Ps1Finish: String {
    case gloss = "gloss"
    case satin = "satin"
    case matte = "matte"
    case metallic = "metallic"
    case pearl = "pearl"

    static func of(_ wire: String?) -> Ps1Finish {
        return Ps1Finish(rawValue: wire ?? "") ?? Ps1Finish.gloss
    }
}

/// How a wheel looks: a spoke count, how much of the face is rim lip, and the colour the face is tinted to.
struct Ps1Wheel: Equatable {
    let id: String
    let spokes: Int
    let lip: Float
    let solid: Bool
    let rgb: UInt32
}

enum Ps1Wheels {
    /// Six looks, keyed by the server's catalogue ids (`GET /v1/catalogue`).
    static let all: [Ps1Wheel] = [
        Ps1Wheel(id: "stock", spokes: 5, lip: 0.80, solid: false, rgb: 0xB8BCC2),
        Ps1Wheel(id: "sport5", spokes: 5, lip: 0.84, solid: false, rgb: 0xC08A3E),
        Ps1Wheel(id: "mesh", spokes: 12, lip: 0.86, solid: false, rgb: 0x9AA3B0),
        Ps1Wheel(id: "split_spoke", spokes: 10, lip: 0.84, solid: false, rgb: 0xE9EDF2),
        Ps1Wheel(id: "deep_dish", spokes: 6, lip: 0.55, solid: false, rgb: 0xF4F1EA),
        Ps1Wheel(id: "steelie", spokes: 0, lip: 0.86, solid: true, rgb: 0x7C838E)
    ]

    /// Ids earlier versions of the apps used before the server had a catalogue.
    private static let legacy: [String: String] = [
        "multi_spoke": "mesh",
        "split_five": "split_spoke",
        "forged_bronze": "sport5",
        "forged_5spoke": "sport5",
        "mesh_gold": "mesh"
    ]

    static func of(_ id: String?) -> Ps1Wheel {
        let raw: String = id ?? ""
        let key: String = legacy[raw] ?? raw
        for w in all where w.id == key { return w }
        return all[0]
    }
}

/// Everything about a vehicle that changes how it renders. Decals are not drawn, so they are not part of it.
struct VehicleBuild: Hashable {
    static let defaultPaint: UInt32 = 0xB8BCC2
    /// Bump to throw every cached thumbnail away when the models or the shader change.
    static let rendererVersion: Int = 2

    var kind: Ps1Kind
    /// 0xRRGGBB
    var paint: UInt32
    var finish: Ps1Finish
    var wheelId: String
    /// Millimetres from stock, -200...200.
    var rideHeightMm: Int

    var wheel: Ps1Wheel { return Ps1Wheels.of(wheelId) }

    init(kind: String?, paint: String?, finish: String?, wheel: String?, rideHeightMm: Int?) {
        self.kind = Ps1Kind.of(kind)
        self.paint = VehicleBuild.parseHex(paint)
        self.finish = Ps1Finish.of(finish)
        self.wheelId = Ps1Wheels.of(wheel).id
        self.rideHeightMm = min(200, max(-200, rideHeightMm ?? 0))
    }

    init(vehicle v: VehicleDto) {
        self.init(kind: v.kind, paint: v.build?.paint, finish: v.build?.finish, wheel: v.build?.wheel, rideHeightMm: v.build?.rideHeightMm)
    }

    init(badge b: VehicleBadgeDto) {
        self.init(kind: b.kind, paint: b.build?.paint, finish: b.build?.finish, wheel: b.build?.wheel, rideHeightMm: b.build?.rideHeightMm)
    }

    init(draft d: VehicleDraft) {
        self.init(kind: d.kind, paint: d.paint, finish: d.finish, wheel: d.wheel, rideHeightMm: d.rideHeightMm)
    }

    /// `#RRGGBB` (any case); anything else gives the default paint.
    static func parseHex(_ hex: String?) -> UInt32 {
        var s: String = (hex ?? "").trimmingCharacters(in: .whitespaces)
        if s.hasPrefix("#") { s.removeFirst() }
        if s.count != 6 { return defaultPaint }
        return UInt32(s, radix: 16) ?? defaultPaint
    }

    /// A stable cache key for one render of this build: the same visible build always gives the same key and any
    /// visible change gives another. `variant` separates thumbnail sizes.
    func key(_ variant: String = "") -> String {
        let hex: String = String(format: "%06x", paint & 0xFFFFFF)
        let canonical: String = "v\(VehicleBuild.rendererVersion)|\(kind.rawValue)|\(hex)|\(finish.rawValue)|\(wheelId)|\(rideHeightMm)|\(variant)"
        let digest: SHA256.Digest = SHA256.hash(data: Data(canonical.utf8))
        var out: String = ""
        for byte in digest.prefix(10) {
            out += String(format: "%02x", byte)
        }
        return out
    }
}

enum Ps1Color {
    /// sRGB byte (0...255) to linear 0...1. ps1.html feeds linear colours to the output unencoded, which is where the moody look comes from.
    static func linear(_ v: UInt32) -> Float {
        let c: Float = Float(v) / 255.0
        if c <= 0.04045 { return c / 12.92 }
        return powf((c + 0.055) / 1.055, 2.4)
    }

    /// 0xRRGGBB to linear (r, g, b).
    static func linear(rgb: UInt32) -> SIMD3<Float> {
        return SIMD3<Float>(linear((rgb >> 16) & 0xFF), linear((rgb >> 8) & 0xFF), linear(rgb & 0xFF))
    }
}

extension AppState {
    /// My primary vehicle's build for avatar badges: the garage (fresh) first, else the last `/v1/me`.
    var myVehicleBuild: VehicleBuild? {
        if let v = garage.primary { return VehicleBuild(vehicle: v) }
        if let v = me?.primaryVehicle { return VehicleBuild(vehicle: v) }
        return nil
    }
}
