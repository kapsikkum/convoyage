import Foundation

// Port of android/.../render/Ps1Shape.kt and the Ps1Body part of Ps1Spec.kt: a vehicle's real dimensions and where its
// glass, roof and tray sit, as fractions of its length and height.

/// Body styles, each belonging to one kind. `rawValue` matches the server's `build.body`.
enum Ps1Body: String, CaseIterable {
    case sedan
    case hatch
    case hotHatch = "hot_hatch"
    case wagon
    case liftback
    case coupe
    case convertible
    case roadster
    case muscle
    case suv
    case peopleMover = "people_mover"
    case van
    case singleCab = "single_cab"
    case extraCab = "extra_cab"
    case dualCab = "dual_cab"
    case cabChassis = "cab_chassis"
    case fourwdWagon = "4wd_wagon"
    case fourwdUte = "4wd_ute"
    case fourwdCabChassis = "4wd_cab_chassis"
    case naked
    case sportBike = "sport_bike"
    case cruiser
    case tourer
    case adventure
    case dirt
    case scooter

    var kind: Ps1Kind {
        switch self {
        case .sedan, .hatch, .hotHatch, .wagon, .liftback, .coupe, .convertible, .roadster, .muscle, .suv,
             .peopleMover, .van:
            return .car
        case .singleCab, .extraCab, .dualCab, .cabChassis:
            return .ute
        case .fourwdWagon, .fourwdUte, .fourwdCabChassis:
            return .fourwd
        case .naked, .sportBike, .cruiser, .tourer, .adventure, .dirt, .scooter:
            return .motorcycle
        }
    }

    var label: String {
        switch self {
        case .sedan: return "Sedan"
        case .hatch: return "Hatch"
        case .hotHatch: return "Hot hatch"
        case .wagon: return "Wagon"
        case .liftback: return "Liftback"
        case .coupe: return "Coupe"
        case .convertible: return "Convertible"
        case .roadster: return "Roadster"
        case .muscle: return "Muscle"
        case .suv: return "SUV"
        case .peopleMover: return "People mover"
        case .van: return "Van"
        case .singleCab: return "Single cab"
        case .extraCab: return "Extra cab"
        case .dualCab: return "Dual cab"
        case .cabChassis: return "Cab chassis"
        case .fourwdWagon: return "Wagon"
        case .fourwdUte: return "Dual cab ute"
        case .fourwdCabChassis: return "Cab chassis"
        case .naked: return "Naked"
        case .sportBike: return "Sport"
        case .cruiser: return "Cruiser"
        case .tourer: return "Tourer"
        case .adventure: return "Adventure"
        case .dirt: return "Dirt"
        case .scooter: return "Scooter"
        }
    }

    static func defaultFor(_ kind: Ps1Kind) -> Ps1Body {
        switch kind {
        case .car: return .sedan
        case .ute: return .dualCab
        case .fourwd: return .fourwdWagon
        case .motorcycle: return .naked
        }
    }

    static func forKind(_ kind: Ps1Kind) -> [Ps1Body] {
        return allCases.filter { (b: Ps1Body) -> Bool in return b.kind == kind }
    }

    /// The body for `wire` if it belongs to `kind`, else the kind's default.
    static func of(_ wire: String?, _ kind: Ps1Kind) -> Ps1Body {
        return allCases.first(where: { (b: Ps1Body) -> Bool in return b.rawValue == wire && b.kind == kind }) ?? defaultFor(kind)
    }
}

/// The front of a vehicle: grille and headlight style. Generic shapes, never badges or logos.
enum Ps1Face {
    /// Two lights and a slot grille.
    case generic
    /// Two small kidney grilles.
    case kidney
    /// Two tall kidney grilles reaching down the bumper.
    case kidneyTall
    /// One big, wide lower mouth.
    case wide
    /// One tall six-sided grille from the bonnet to the bumper.
    case singleframe
    /// Seven vertical slots between round lights.
    case sevenSlot
    /// Round headlights.
    case round
    /// Tall C-shaped lights either side of a big grille.
    case cClamp
    /// A light strip across the whole nose.
    case lightBar
    /// Thin lights and a low intake, no real grille.
    case slim
}

/// Bits a body carries beyond its shell.
enum Ps1Kit: String, CaseIterable {
    case roofRails
    case roofRack
    case roofSpoiler
    case wing
    case bootLip
    case ducktail
    case scoop
    case bulge
    case stripes
    case rooBar
    case snorkel
    case spare
    case sportsBar
    case canopy
    case flares
    case steps
    case rearBar
    case triTail
}

/// Where things sit along a body, as fractions: heights of the overall height, positions of the overall length from the
/// nose. Each body has one; catalogue models adjust it.
struct Ps1Tpl {
    /// Share of the overhangs (length minus wheelbase) that is at the front.
    var frontShare: Float
    /// Bottom of the body side.
    var sill: Float
    /// Top of the bonnet at the nose.
    var nose: Float
    /// Window line.
    var belt: Float
    /// Top of the boot, tailgate or tray at the back.
    var deck: Float
    /// Foot of the windscreen.
    var ws: Float
    /// Front and back of the flat roof.
    var roofF: Float
    var roofR: Float
    /// Foot of the rear glass; 1 for a vertical tailgate.
    var rw: Float
    /// Utes: the back of the cab (the tray runs from here to the tail). 0 = no tray.
    var cab: Float = 0
    var doors: Int = 4
    /// A pillar behind the rear doors and a quarter window (wagons, SUVs, people movers).
    var longRoof: Bool = false
    /// Panels instead of side glass behind the front doors (vans).
    var blind: Bool = false
    /// No roof (roadsters, convertibles).
    var open: Bool = false
    /// A flat tray deck instead of a tub (cab-chassis utes).
    var flatDeck: Bool = false

    init(_ frontShare: Float, _ sill: Float, _ nose: Float, _ belt: Float, _ deck: Float,
         ws: Float, roofF: Float, roofR: Float, rw: Float,
         cab: Float = 0, doors: Int = 4, longRoof: Bool = false, blind: Bool = false,
         open: Bool = false, flatDeck: Bool = false) {
        self.frontShare = frontShare
        self.sill = sill
        self.nose = nose
        self.belt = belt
        self.deck = deck
        self.ws = ws
        self.roofF = roofF
        self.roofR = roofR
        self.rw = rw
        self.cab = cab
        self.doors = doors
        self.longRoof = longRoof
        self.blind = blind
        self.open = open
        self.flatDeck = flatDeck
    }
}

/// One vehicle's shape in metres: real dimensions plus where its glass, roof and tray are.
final class Ps1Shape {
    /// Catalogue id, or `generic-<body>`.
    let id: String
    let body: Ps1Body
    let len: Float
    let wid: Float
    let hgt: Float
    let wb: Float
    /// Overall tyre diameter and tread width.
    let tyre: Float
    let tyreW: Float
    let tpl: Ps1Tpl
    let face: Ps1Face
    let kit: Set<Ps1Kit>

    var fo: Float { return (len - wb) * tpl.frontShare }
    var sill: Float { return tpl.sill * hgt }
    var nose: Float { return tpl.nose * hgt }
    var belt: Float { return tpl.belt * hgt }
    var deck: Float { return tpl.deck * hgt }
    var ws: Float { return tpl.ws * len }
    var roofF: Float { return tpl.roofF * len }
    var roofR: Float { return tpl.roofR * len }
    var rw: Float { return tpl.rw * len }
    var cab: Float { return tpl.cab * len }

    init(_ id: String, _ body: Ps1Body, _ len: Float, _ wid: Float, _ hgt: Float, _ wb: Float,
         _ tyre: Float, _ tyreW: Float, _ tpl: Ps1Tpl, _ face: Ps1Face, _ kit: Set<Ps1Kit>) {
        self.id = id
        self.body = body
        self.len = len
        self.wid = wid
        self.hgt = hgt
        self.wb = wb
        self.tyre = tyre
        self.tyreW = tyreW
        self.tpl = tpl
        self.face = face
        self.kit = kit
    }

    /// `225/45R17` to overall diameter and tread width in metres.
    static func tyreSize(_ code: String) -> (d: Float, w: Float) {
        let upper: String = code.uppercased()
        let re: NSRegularExpression? = try? NSRegularExpression(pattern: "(\\d{3})/(\\d{2})\\s*Z?R\\s*(\\d{2})")
        let ns: NSString = upper as NSString
        guard let m = re?.firstMatch(in: upper, range: NSRange(location: 0, length: ns.length)) else {
            return (0.65, 0.225)
        }
        let w: Float = Float(ns.substring(with: m.range(at: 1))) ?? 225
        let ar: Float = Float(ns.substring(with: m.range(at: 2))) ?? 45
        let rim: Float = Float(ns.substring(with: m.range(at: 3))) ?? 17
        return ((rim * 25.4 + 2 * w * ar / 100) / 1000, w / 1000)
    }

    static func of(_ id: String, _ body: Ps1Body, _ mm: [Int], _ tyre: String, _ tpl: Ps1Tpl,
                   _ face: Ps1Face, _ kit: Set<Ps1Kit>) -> Ps1Shape {
        let t: (d: Float, w: Float) = Ps1Shape.tyreSize(tyre)
        return Ps1Shape(id, body, Float(mm[0]) / 1000, Float(mm[1]) / 1000, Float(mm[2]) / 1000, Float(mm[3]) / 1000,
                        t.d, t.w, tpl, face, kit)
    }
}

/// Every four-wheeled body's proportions, taken from a typical car of that body, and the generic shapes built on them.
enum Ps1Shapes {
    static func tpl(_ body: Ps1Body) -> Ps1Tpl {
        switch body {
        // Corolla sedan
        case .sedan:
            return Ps1Tpl(0.49, 0.13, 0.50, 0.66, 0.68, ws: 0.30, roofF: 0.45, roofR: 0.73, rw: 0.855)
        // i30 hatch: the roof ends early and the glass slopes down to a short tail.
        case .hatch:
            return Ps1Tpl(0.53, 0.13, 0.50, 0.65, 0.70, ws: 0.29, roofF: 0.44, roofR: 0.80, rw: 0.965)
        case .hotHatch:
            return Ps1Tpl(0.53, 0.12, 0.49, 0.65, 0.70, ws: 0.29, roofF: 0.44, roofR: 0.80, rw: 0.965)
        // Golf wagon: the roof runs to the tail, glass almost upright.
        case .wagon:
            return Ps1Tpl(0.46, 0.13, 0.50, 0.64, 0.66, ws: 0.28, roofF: 0.42, roofR: 0.955, rw: 1, longRoof: true)
        // Stinger: a long sloping hatch.
        case .liftback:
            return Ps1Tpl(0.48, 0.13, 0.50, 0.68, 0.70, ws: 0.29, roofF: 0.44, roofR: 0.66, rw: 0.95)
        // GR86
        case .coupe:
            return Ps1Tpl(0.50, 0.12, 0.48, 0.67, 0.70, ws: 0.31, roofF: 0.46, roofR: 0.66, rw: 0.86, doors: 2)
        case .convertible:
            return Ps1Tpl(0.50, 0.12, 0.50, 0.68, 0.70, ws: 0.33, roofF: 0.42, roofR: 0.42, rw: 0.86, doors: 2, open: true)
        // MX-5
        case .roadster:
            return Ps1Tpl(0.46, 0.12, 0.52, 0.70, 0.72, ws: 0.38, roofF: 0.45, roofR: 0.45, rw: 0.86, doors: 2, open: true)
        // Mustang fastback
        case .muscle:
            return Ps1Tpl(0.50, 0.12, 0.56, 0.70, 0.72, ws: 0.37, roofF: 0.50, roofR: 0.66, rw: 0.88, doors: 2)
        // RAV4
        case .suv:
            return Ps1Tpl(0.52, 0.20, 0.58, 0.65, 0.68, ws: 0.27, roofF: 0.40, roofR: 0.93, rw: 0.985, longRoof: true)
        // Carnival
        case .peopleMover:
            return Ps1Tpl(0.50, 0.15, 0.55, 0.60, 0.62, ws: 0.19, roofF: 0.33, roofR: 0.96, rw: 1, longRoof: true)
        // HiAce LWB: cab over the front wheels, panel sides.
        case .van:
            return Ps1Tpl(0.44, 0.10, 0.45, 0.52, 0.52, ws: 0.05, roofF: 0.16, roofR: 0.99, rw: 1, doors: 2, blind: true)
        // Car-based ute (Commodore, Falcon)
        case .singleCab:
            return Ps1Tpl(0.46, 0.12, 0.50, 0.66, 0.66, ws: 0.30, roofF: 0.43, roofR: 0.55, rw: 1, cab: 0.585, doors: 2)
        // Hilux dual cab: tub behind a four-door cab.
        case .dualCab:
            return Ps1Tpl(0.43, 0.19, 0.59, 0.66, 0.63, ws: 0.25, roofF: 0.36, roofR: 0.655, rw: 1, cab: 0.68)
        case .extraCab:
            return Ps1Tpl(0.43, 0.19, 0.59, 0.66, 0.63, ws: 0.25, roofF: 0.36, roofR: 0.57, rw: 1, cab: 0.60, doors: 2)
        case .cabChassis:
            return Ps1Tpl(0.43, 0.19, 0.59, 0.66, 0.55, ws: 0.25, roofF: 0.36, roofR: 0.50, rw: 1, cab: 0.53, doors: 2, flatDeck: true)
        // LandCruiser 300
        case .fourwdWagon:
            return Ps1Tpl(0.43, 0.24, 0.60, 0.66, 0.64, ws: 0.27, roofF: 0.38, roofR: 0.97, rw: 1, longRoof: true)
        case .fourwdUte:
            return Ps1Tpl(0.43, 0.22, 0.60, 0.66, 0.63, ws: 0.25, roofF: 0.36, roofR: 0.655, rw: 1, cab: 0.68)
        // LandCruiser 79 single cab: long bonnet, short cab, flat tray.
        case .fourwdCabChassis:
            return Ps1Tpl(0.40, 0.24, 0.60, 0.64, 0.55, ws: 0.24, roofF: 0.33, roofR: 0.45, rw: 1, cab: 0.48, doors: 2, flatDeck: true)
        default:
            return Ps1Tpl(0.5, 0.13, 0.5, 0.66, 0.68, ws: 0.3, roofF: 0.45, roofR: 0.73, rw: 0.855)
        }
    }

    /// Typical dimensions (L, W, H, WB in millimetres), tyre, face and kit of each body when no model is chosen.
    private static func typical(_ body: Ps1Body) -> (mm: [Int], tyre: String, face: Ps1Face, kit: Set<Ps1Kit>) {
        switch body {
        case .sedan: return ([4630, 1780, 1435, 2700], "225/45R17", .generic, [])
        case .hatch: return ([4340, 1795, 1455, 2650], "225/45R17", .generic, [])
        case .hotHatch: return ([4340, 1800, 1440, 2650], "235/35R19", .wide, [.roofSpoiler])
        case .wagon: return ([4630, 1790, 1460, 2670], "225/45R17", .generic, [.roofRails])
        case .liftback: return ([4830, 1870, 1400, 2905], "225/40R19", .generic, [])
        case .coupe: return ([4265, 1775, 1310, 2575], "215/40R18", .slim, [.bootLip])
        case .convertible: return ([4500, 1820, 1390, 2700], "225/45R18", .generic, [])
        case .roadster: return ([3915, 1735, 1225, 2310], "205/45R17", .slim, [])
        case .muscle: return ([4784, 1916, 1381, 2720], "255/40R19", .wide, [.bulge, .stripes, .triTail])
        case .suv: return ([4600, 1855, 1685, 2690], "225/60R18", .generic, [.roofRails])
        case .peopleMover: return ([5155, 1995, 1775, 3090], "235/55R19", .wide, [])
        case .van: return ([5265, 1950, 1990, 3210], "215/70R16", .generic, [])
        case .singleCab: return ([5040, 1899, 1494, 3009], "245/40R19", .wide, [])
        case .dualCab: return ([5325, 1855, 1815, 3085], "265/60R18", .wide, [])
        case .extraCab: return ([5330, 1855, 1810, 3085], "265/65R17", .wide, [])
        case .cabChassis: return ([5330, 1855, 1800, 3085], "265/65R17", .generic, [])
        case .fourwdWagon: return ([4980, 1980, 1950, 2850], "265/70R17", .generic, [.rooBar, .snorkel, .roofRack, .spare, .flares])
        case .fourwdUte: return ([5370, 1918, 1884, 3270], "265/70R17", .generic, [.rooBar, .snorkel, .sportsBar, .flares])
        case .fourwdCabChassis: return ([5220, 1870, 1955, 3180], "265/70R16", .generic, [.snorkel, .rooBar])
        default: return ([4630, 1780, 1435, 2700], "225/45R17", .generic, [])
        }
    }

    nonisolated(unsafe) private static var built: [Ps1Body: Ps1Shape] = [:]
    private static let lock: NSLock = NSLock()

    /// The shape of a body with no model chosen. Nil for motorcycles.
    static func generic(_ body: Ps1Body) -> Ps1Shape? {
        if body.kind == .motorcycle { return nil }
        lock.lock()
        defer { lock.unlock() }
        if let hit = built[body] { return hit }
        let t: (mm: [Int], tyre: String, face: Ps1Face, kit: Set<Ps1Kit>) = typical(body)
        let sh: Ps1Shape = Ps1Shape.of("generic-\(body.rawValue)", body, t.mm, t.tyre, tpl(body), t.face, t.kit)
        built[body] = sh
        return sh
    }
}
