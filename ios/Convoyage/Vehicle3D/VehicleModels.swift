import Foundation

// Port of android/.../render/Ps1Models.kt: the vehicles, built in code in the PS1 manner - low-poly lofted bodies, boxes
// and 8-sided wheels. x is forward (the lights at +x), y is up and z is across (+z is the right-hand side). Four-wheelers
// are built from a Ps1Shape in metres at K units per metre, centred on their length, with the wheel centres at y = 0.

enum Ps1Models {
    /// Model units per metre.
    static let K: Float = 1.5

    /// Body height change per millimetre of ride height.
    static let mm: Float = 0.0016

    static let tyre: Surf = Surf.srgb(0x16181C)
    static let glass: Surf = Surf.srgb(0x0E1A28, flag: 3)
    static let dark: Surf = Surf.srgb(0x0A0D12)
    static let arch: Surf = Surf.srgb(0x050608)
    static let trim: Surf = Surf.srgb(0x3A414C)
    static let chrome: Surf = Surf.srgb(0xB8BCC2)
    static let alloy: Surf = Surf.srgb(0x8E959F)
    static let plate: Surf = Surf.srgb(0xE8EAEE)
    static let seat: Surf = Surf.srgb(0x15171C)
    static let head: Surf = Surf.srgb(0xDFF9FF, flag: 2)
    static let tail: Surf = Surf.srgb(0xFF3D5A, flag: 2)
    static let ground: Surf = Surf.srgb(0xFFFFFF, tex: 3)
    static let shadow: Surf = Surf.srgb(0x05080D)
    static let fog: Surf = Surf.srgb(0xFFD27A, flag: 2)
    static let rotor: Surf = Surf.srgb(0x5A5F66)
    static let red: Surf = Surf.srgb(0xB0141E)

    // MARK: Framing

    static func frame(_ spec: VehicleBuild?) -> Ps1Frame {
        if let sh = spec?.shape { return frame(sh) }
        return bikeFrame(spec?.body ?? .sedan)
    }

    static func frame(_ body: Ps1Body) -> Ps1Frame {
        if let sh = Ps1Shapes.generic(body) { return frame(sh) }
        return bikeFrame(body)
    }

    static func frame(_ sh: Ps1Shape) -> Ps1Frame {
        let fit: Float = hypotf(sh.len / 2, sh.hgt / 2) * K * 1.02
        return Ps1Frame(fitRadius: fit, groundY: -sh.tyre / 2 * K, groundRadius: fit * 1.3,
                        lookY: (sh.hgt * 0.42 - sh.tyre / 2) * K)
    }

    private static func bikeFrame(_ body: Ps1Body) -> Ps1Frame {
        let r: Float = bikeSpec(body).wheelR
        let big: Bool = body == .cruiser || body == .tourer
        return Ps1Frame(fitRadius: big ? 2.9 : 2.7, groundY: -r, groundRadius: 3.6, lookY: 0.45)
    }

    // MARK: Build

    /// Wheels and size of a motorcycle: how big the wheels are and where the axles sit.
    struct BikeSpec {
        let wheelR: Float
        let rearX: Float
        let frontX: Float
        let wheelW: Float
    }

    static func bikeSpec(_ body: Ps1Body) -> BikeSpec {
        switch body {
        case .cruiser: return BikeSpec(wheelR: 0.6, rearX: -1.75, frontX: 1.9, wheelW: 0.3)
        case .tourer: return BikeSpec(wheelR: 0.62, rearX: -1.75, frontX: 1.85, wheelW: 0.3)
        case .adventure: return BikeSpec(wheelR: 0.7, rearX: -1.55, frontX: 1.65, wheelW: 0.22)
        case .dirt: return BikeSpec(wheelR: 0.72, rearX: -1.5, frontX: 1.6, wheelW: 0.18)
        case .scooter: return BikeSpec(wheelR: 0.42, rearX: -1.3, frontX: 1.3, wheelW: 0.26)
        default: return BikeSpec(wheelR: 0.62, rearX: -1.5, frontX: 1.55, wheelW: 0.24)
        }
    }

    static func build(_ spec: VehicleBuild) -> Ps1Mesh {
        let mb: MeshBuilder = MeshBuilder()
        let f: Ps1Frame = frame(spec)
        Ps1Geo.disc(mb, y: f.groundY, rx: f.groundRadius, rz: f.groundRadius, segments: 12, ground, uvScale: 6)
        let groundIndices: Int = mb.indexCount

        let paint: Surf = Surf.srgb(spec.paint, tex: 1, flag: 1)
        let rim: Surf = Surf.srgb(spec.wheel.rgb, tex: 2)
        let dy: Float = Float(spec.rideHeightMm) * mm
        let sh: Ps1Shape? = spec.shape
        let modelled: Ps1CarModel? = spec.modelId.flatMap { (id: String) -> Ps1CarModel? in return Ps1CarModels.of(id) }
        if let s = sh {
            if let cm = modelled {
                // Modelled bodies are smooth clear-coated paint, no texture.
                modelledCar(mb, cm, Surf.srgb(spec.paint, flag: 1), rim, dy)
            } else {
                Car(mb, s, paint, rim, dy).build()
            }
        } else {
            bike(mb, spec.body, bikeSpec(spec.body), paint, rim, dy)
        }

        // The shadow blob under the vehicle, a hair above the ground.
        if let s = sh {
            Ps1Geo.disc(mb, y: f.groundY + 0.012, rx: s.len / 2 * K * 1.02, rz: s.wid / 2 * K * 1.12, segments: 10, shadow)
        } else {
            Ps1Geo.disc(mb, y: f.groundY + 0.012, rx: 2.5, rz: 0.75, segments: 10, shadow)
        }
        return mb.build(groundIndexCount: groundIndices)
    }

    /// A body modelled in Blender: its parts take the paint, glass and lamp surfaces; the wheels go in its arches.
    private static func modelledCar(_ mb: MeshBuilder, _ cm: Ps1CarModel, _ paint: Surf, _ rim: Surf, _ dyIn: Float) {
        let h0: Float = cm.tyre / 2
        let dy: Float = max(dyIn, -0.06 * K)
        for part in cm.parts {
            let s: Surf
            switch part.material {
            case "glass": s = glass
            case "dark": s = arch
            case "black": s = dark
            case "trim": s = trim
            case "chrome": s = chrome
            case "head": s = head
            case "tail": s = tail
            case "fog": s = fog
            case "plate": s = plate
            case "red": s = red
            default: s = paint
            }
            let pos: [Float] = part.positions
            let nrm: [Float] = part.normals
            var ids: [Int] = []
            ids.reserveCapacity(pos.count / 3)
            for i in 0..<(pos.count / 3) {
                let p: V3 = V3(pos[i * 3] * K, (pos[i * 3 + 1] - h0) * K + dy, pos[i * 3 + 2] * K)
                let n: V3 = i * 3 + 2 < nrm.count ? V3(nrm[i * 3], nrm[i * 3 + 1], nrm[i * 3 + 2]) : V3(0, 1, 0)
                ids.append(mb.vertex(p, n, V2(p.x / 7 + 0.5, p.y / 2), s))
            }
            let ix: [Int] = part.indices
            var t: Int = 0
            while t + 2 < ix.count {
                mb.tri(ids[ix[t]], ids[ix[t + 1]], ids[ix[t + 2]])
                t += 3
            }
        }
        let r: Float = cm.tyre / 2 * K
        let ww: Float = cm.tyreW * K
        for (i, d) in [cm.fo, cm.fo + cm.wb].enumerated() {
            let ax: Float = (cm.len / 2 - d) * K
            let ht: Float = cm.halfTrack[min(i, cm.halfTrack.count - 1)] * K
            for side: Float in [1, -1] {
                Ps1Geo.wheel(mb, ax, 0, side * ht, radius: r, width: ww, tyre, rim, segments: 16)
                // Brake disc and caliper behind the spokes.
                Ps1Geo.wheel(mb, ax, 0, side * (ht - ww * 0.3), radius: r * 0.62, width: 0.05, rotor, rotor, segments: 12)
                Ps1Geo.box(mb, ax - r * 0.42, r * 0.2, side * (ht - ww * 0.22), r * 0.22, r * 0.42, 0.06, tail)
            }
        }
    }

    // MARK: Car

    /// Builds one four-wheeler from its shape and profile. Helpers take metres (back from the nose, up from the ground).
    private final class Car {
        private let mb: MeshBuilder
        private let sh: Ps1Shape
        private let paint: Surf
        private let rim: Surf
        /// Lowering stops just short of the sill touching the ground.
        private let dy: Float
        private let t: Ps1Tpl
        private let p: Ps1Profile
        private let L: Float
        private let r: Float
        /// Back of the body shell: the cab on a ute, else the tail.
        private let end: Float
        private let ute: Bool
        private let kit: Set<Ps1Kit>

        init(_ mb: MeshBuilder, _ sh: Ps1Shape, _ paint: Surf, _ rim: Surf, _ dyIn: Float) {
            let profile: Ps1Profile = Ps1Profiles.of(sh)
            let lower: Float = max(dyIn, -(sh.sill - 0.06) * Ps1Models.K)
            self.mb = mb
            self.sh = sh
            self.paint = paint
            self.rim = rim
            self.dy = lower
            self.t = sh.tpl
            self.p = profile
            self.L = sh.len
            self.r = sh.tyre / 2 * Ps1Models.K
            self.end = profile.end
            self.ute = profile.end < sh.len - 0.3
            self.kit = sh.kit
        }

        private func x(_ d: Float) -> Float { return (L / 2 - d) * Ps1Models.K }
        private func y(_ h: Float) -> Float { return (h - sh.tyre / 2) * Ps1Models.K + dy }
        private func m(_ v: Float) -> Float { return v * Ps1Models.K }
        private func half(_ d: Float) -> Float { return p.wAt(d) * Ps1Models.K }

        /// Which part of the glasshouse a stretch of body is: 0 body, 1 windscreen, 2 roof, 3 rear glass.
        private func zone(_ d: Float) -> Int {
            if !p.hasCabin { return 0 }
            if d < p.cowl { return 0 }
            if d < p.roofF { return 1 }
            if d < p.roofR { return 2 }
            if d < p.glassEnd { return 3 }
            return 0
        }

        /// The cross-section at `d`: (y, z) from the rocker on the +z side up to the crown; the -z side mirrors it.
        private func ring(_ d: Float) -> [(Float, Float)] {
            let top: Float = p.topAt(d)
            let bot: Float = p.botAt(d)
            let w: Float = half(d)
            let cabin: Bool = zone(d) != 0 || (d == p.glassEnd && p.hasCabin)
            let belt: Float = cabin ? min(p.beltAt(d), top - 0.01) : top - 0.06
            let shoulder: Float = min(belt - 0.07, bot + (belt - bot) * 0.7)
            let mid: Float = bot + (shoulder - bot) * 0.45
            var pts: [(Float, Float)] = []
            pts.reserveCapacity(9)
            pts.append((bot, w * 0.88))
            pts.append((min(bot + 0.07, mid), w * 0.98))
            pts.append((mid, w))
            pts.append((shoulder, w * 0.985))
            pts.append((belt, w * 0.95))
            if cabin {
                // Side glass leans in a little (it reads small from above if it leans in a lot); the roof edge rounds over.
                let tw: Float = w * min(0.97, p.tumble + 0.1)
                pts.append((max(top - 0.07, belt + 0.005), tw))
                pts.append((max(top - 0.02, belt + 0.01), tw * 0.88))
            } else {
                pts.append((top - 0.025, w * 0.82))
                pts.append((top - 0.008, w * 0.5))
            }
            pts.append((top, 0))
            return pts.map { (q: (Float, Float)) -> (Float, Float) in return (y(q.0), q.1) }
        }

        /// Where to cut the body: every curve point and marker, then no gap wider than 30 cm.
        private func stations() -> [Float] {
            var keys: Set<Float> = Set<Float>()
            for list: [Pt] in [p.top, p.bot, p.plan, p.belt] {
                for q in list where q.d <= end { keys.insert(q.d) }
            }
            let marks: [Float] = [0, end, p.cowl, p.roofF, p.roofR, p.glassEnd, sh.fo, sh.fo + sh.wb]
            for k in marks { keys.insert(k) }
            for w: ClosedRange<Float> in p.windows {
                keys.insert(w.lowerBound)
                keys.insert(w.upperBound)
            }
            let base: [Float] = keys.filter { (d: Float) -> Bool in return d >= 0 && d <= end }.sorted()
            var out: [Float] = []
            for i in 0..<base.count {
                if i > 0 {
                    let a: Float = base[i - 1]
                    let b: Float = base[i]
                    let n: Int = Int((b - a) / 0.3)
                    if n > 0 {
                        for k in 1...n {
                            out.append(a + (b - a) * Float(k) / Float(n + 1))
                        }
                    }
                }
                out.append(base[i])
            }
            var res: [Float] = []
            for d in out where res.isEmpty || d - (res.last ?? 0) > 0.005 { res.append(d) }
            return res
        }

        private func uv(_ xx: Float, _ yy: Float) -> V2 {
            return V2(xx / 7 + 0.5, yy / 2)
        }

        private func uv(_ v: V3) -> V2 {
            return uv(v.x, v.y)
        }

        private func body() {
            let ds: [Float] = stations()
            let rings: [[(Float, Float)]] = ds.map { (d: Float) -> [(Float, Float)] in return ring(d) }
            for i in 0..<(ds.count - 1) {
                let da: Float = ds[i]
                let db: Float = ds[i + 1]
                let dm: Float = (da + db) / 2
                let zn: Int = zone(dm)
                // Side glass wherever a window spans, under the A pillar and into the C pillar too.
                let window: Bool = zn != 0 && p.windows.contains(where: { (w: ClosedRange<Float>) -> Bool in return w.contains(dm) })
                let ra: [(Float, Float)] = rings[i]
                let rb: [(Float, Float)] = rings[i + 1]
                let xa: Float = x(da)
                let xb: Float = x(db)
                let yc: Float = (ra[0].0 + (ra.last?.0 ?? 0)) / 2
                for k in 0..<(ra.count - 1) {
                    let s: Surf
                    switch k {
                    case 4: s = window ? Ps1Models.glass : paint
                    // The edge strip is the A/C pillar or the drip rail; the middle is the windscreen or rear glass.
                    case 5: s = paint
                    case 6: s = (zn == 1 || zn == 3) ? Ps1Models.glass : paint
                    default: s = paint
                    }
                    for side: Float in [1, -1] {
                        let p0: V3 = V3(xa, ra[k].0, ra[k].1 * side)
                        let p1: V3 = V3(xa, ra[k + 1].0, ra[k + 1].1 * side)
                        let q0: V3 = V3(xb, rb[k].0, rb[k].1 * side)
                        let q1: V3 = V3(xb, rb[k + 1].0, rb[k + 1].1 * side)
                        let hy: Float = (ra[k].0 + ra[k + 1].0) / 2 - yc
                        let hz: Float = (ra[k].1 + ra[k + 1].1) / 2 * side
                        let h: V3 = V3(0, hy, hz)
                        Ps1Geo.flatTri(mb, s, p0, q0, q1, uv(p0), uv(q0), uv(q1), h)
                        Ps1Geo.flatTri(mb, s, p0, q1, p1, uv(p0), uv(q1), uv(p1), h)
                    }
                }
                // Underside.
                let a0: V3 = V3(xa, ra[0].0, ra[0].1)
                let a1: V3 = V3(xa, ra[0].0, -ra[0].1)
                let b0: V3 = V3(xb, rb[0].0, rb[0].1)
                let b1: V3 = V3(xb, rb[0].0, -rb[0].1)
                let z: V2 = V2(0, 0)
                Ps1Geo.flatTri(mb, Ps1Models.dark, a0, b0, b1, z, z, z, V3(0, -1, 0))
                Ps1Geo.flatTri(mb, Ps1Models.dark, a0, b1, a1, z, z, z, V3(0, -1, 0))
            }
            // Ends: fans from the middle of the first and last rings. A cab's back carries its rear window.
            let first: [(Float, Float)] = rings[0]
            let last: [(Float, Float)] = rings[rings.count - 1]
            for (rng, dir, xe) in [(first, Float(1), x(ds[0])), (last, Float(-1), x(ds[ds.count - 1]))] {
                var full: [(Float, Float)] = rng
                full.append(contentsOf: rng.reversed().map { (q: (Float, Float)) -> (Float, Float) in return (q.0, -q.1) })
                let cy: Float = ((rng[0].0 + (rng.last?.0 ?? 0)) / 2)
                let c: V3 = V3(xe, cy, 0)
                for k in 0..<(full.count - 1) {
                    let a: V3 = V3(xe, full[k].0, full[k].1)
                    let b: V3 = V3(xe, full[k + 1].0, full[k + 1].1)
                    let isGlass: Bool = dir < 0 && ute && p.hasCabin && min(full[k].0, full[k + 1].0) > y(p.beltAt(end) + 0.04)
                    Ps1Geo.flatTri(mb, isGlass ? Ps1Models.glass : paint, c, a, b, uv(c), uv(a), uv(b), V3(dir, 0, 0))
                }
            }
        }

        func build() {
            body()
            let fx: Float = x(sh.fo)
            let axles: [Float] = [fx - m(sh.wb), fx]
            let ww: Float = m(sh.tyreW) * 1.15
            for (i, ax) in axles.enumerated() {
                let d: Float = i == 1 ? sh.fo : sh.fo + sh.wb
                let hw: Float = half(d)
                let underDeck: Bool = i == 0 && t.flatDeck
                let wz: Float = hw - ww / 2 + 0.05
                for side: Float in [1, -1] {
                    if kit.contains(.flares) && !underDeck {
                        arch(mb, ax, 0, side * (hw + 0.01), r * 1.36, side, Ps1Models.dark)
                    }
                    if !underDeck {
                        arch(mb, ax, 0, side * (hw + 0.018), r * 1.18, side, Ps1Models.arch)
                    }
                    Ps1Geo.wheel(mb, ax, 0, side * wz, radius: r, width: ww, Ps1Models.tyre, rim, segments: 12)
                }
            }
            if ute {
                if t.flatDeck { flatDeck() } else { tub() }
            }
            face()
            if !ute { tail() }
            mirrors()
            if !p.hasCabin { cockpit() }
            kits(axles)
        }

        // MARK: The ends

        private func box(_ d: Float, _ h: Float, _ z: Float, _ sx: Float, _ sy: Float, _ sz: Float, _ s: Surf, rotZ: Float = 0) {
            Ps1Geo.box(mb, x(d), y(h), z, sx, sy, sz, s, rotZ: rotZ)
        }

        /// How far back from the nose the body reaches height `h` (where a lamp at that height sits).
        private func frontAt(_ h: Float) -> Float {
            var d: Float = 0
            while d < 1.2 && (p.topAt(d) < h || p.botAt(d) > h) { d += 0.02 }
            return d
        }

        /// How far back the tail reaches height `h`.
        private func rearAt(_ h: Float) -> Float {
            var d: Float = end
            while d > end - 1.2 && (p.topAt(d) < h || p.botAt(d) > h) { d -= 0.02 }
            return d
        }

        private func lamp(_ h: Float, _ z: Float, _ radius: Float) {
            Ps1Geo.wheel(mb, x(frontAt(h)) + 0.03, y(h), z, radius: radius, width: 0.06,
                         Ps1Models.head, Ps1Models.head, segments: 8, rotY: Float.pi / 2)
        }

        /// A flat feature on the nose at height `h`, `tall` and `wide` in metres, pushed onto the body surface.
        private func front(_ h: Float, _ z: Float, _ tall: Float, _ wide: Float, _ s: Surf, out: Float = 0) {
            let d: Float = min(frontAt(h + tall / 2), max(frontAt(h - tall / 2), 0))
            box(d + 0.02 - out, h, z, 0.08, m(tall), m(wide), s)
        }

        private func face() {
            let n: Float = p.topAt(0.3)
            let s: Float = p.botAt(0.3)
            let mid: Float = (s + n) / 2
            let hw: Float = p.wAt(0.15)
            func lights(_ h: Float, _ tall: Float, _ wide: Float, _ at: Float = 0.66) {
                for z: Float in [hw * at, -hw * at] { front(h, m(z), tall, wide, Ps1Models.head) }
            }
            switch sh.face {
            case .generic:
                lights(n - 0.13, 0.1, hw * 0.42)
                front(mid + 0.02, 0, 0.15, hw * 0.9, Ps1Models.dark)
            case .kidney, .kidneyTall:
                lights(n - 0.12, 0.08, hw * 0.42, 0.68)
                let tall: Float = sh.face == .kidneyTall ? 0.34 : 0.13
                let h: Float = n - 0.06 - tall / 2
                for z: Float in [0.13, -0.13] {
                    front(h, m(z), tall + 0.03, 0.25, Ps1Models.chrome)
                    front(h, m(z), tall, 0.21, Ps1Models.dark, out: 0.01)
                }
                front(s + 0.15, 0, 0.1, hw * 1.1, Ps1Models.dark)
            case .wide:
                lights(n - 0.1, 0.075, hw * 0.45, 0.68)
                front(mid - 0.03, 0, 0.28, hw * 1.25, Ps1Models.dark)
            case .singleframe:
                lights(n - 0.1, 0.075, hw * 0.42, 0.7)
                front(mid, 0, 0.36, hw * 0.95, Ps1Models.dark)
            case .sevenSlot:
                for i in -3...3 { front(n - 0.2, m(0.1 * Float(i)), 0.3, 0.05, Ps1Models.dark) }
                for z: Float in [hw * 0.74, -hw * 0.74] { lamp(n - 0.2, m(z), m(0.09)) }
                front(s + 0.15, 0, 0.12, hw * 1.2, Ps1Models.trim)
            case .round:
                for z: Float in [hw * 0.66, -hw * 0.66] { lamp(n - 0.15, m(z), m(0.09)) }
                front(mid, 0, 0.14, hw * 0.7, Ps1Models.dark)
            case .cClamp:
                front(n - 0.22, 0, 0.3, hw * 1.2, Ps1Models.dark)
                for sz: Float in [1, -1] {
                    front(n - 0.2, m(sz * hw * 0.86), 0.28, 0.05, Ps1Models.head, out: 0.01)
                    front(n - 0.07, m(sz * hw * 0.78), 0.05, 0.18, Ps1Models.head, out: 0.01)
                    front(n - 0.33, m(sz * hw * 0.78), 0.05, 0.18, Ps1Models.head, out: 0.01)
                }
            case .lightBar:
                front(n - 0.06, 0, 0.035, hw * 1.9, Ps1Models.head)
                lights(n - 0.11, 0.06, hw * 0.36, 0.7)
                front(mid - 0.04, 0, 0.2, hw * 1.1, Ps1Models.dark)
            case .slim:
                lights(n - 0.09, 0.055, hw * 0.4, 0.68)
                front(s + 0.17, 0, 0.12, hw * 1.2, Ps1Models.dark)
            }
            front(s + 0.2, 0, 0.11, 0.37, Ps1Models.plate, out: 0.02)
        }

        private func tail() {
            let deck: Float = p.topAt(end - 0.15)
            let upright: Bool = p.hasCabin && end - p.glassEnd < 0.2
            let h: Float = upright ? p.beltAt(end) - 0.05 : deck - 0.08
            let hw: Float = p.wAt(end - 0.15)
            func back(_ hh: Float, _ z: Float, _ tall: Float, _ wide: Float, _ s: Surf) {
                let d: Float = max(rearAt(hh + tall / 2), rearAt(hh - tall / 2))
                box(d - 0.02, hh, z, 0.08, m(tall), m(wide), s)
            }
            if kit.contains(.triTail) {
                for sz: Float in [1, -1] {
                    for k in 0...2 { back(h, m(sz * hw * (0.55 + 0.1 * Float(k))), 0.15, 0.045, Ps1Models.tail) }
                }
            } else if upright {
                for z: Float in [hw * 0.82, -hw * 0.82] { back(h, m(z), 0.22, hw * 0.28, Ps1Models.tail) }
            } else {
                for z: Float in [hw * 0.7, -hw * 0.7] { back(h, m(z), 0.09, hw * 0.42, Ps1Models.tail) }
            }
            if kit.contains(.rearBar) { back(h + 0.05, 0, 0.035, hw * 1.8, Ps1Models.tail) }
            back(p.botAt(end) + 0.15, 0, 0.11, 0.37, Ps1Models.plate)
        }

        private func mirrors() {
            let dm: Float = p.cowl + 0.2
            let hw: Float = p.wAt(dm)
            for side: Float in [1, -1] {
                box(dm, p.beltAt(dm) + 0.06, side * m(hw + 0.07), 0.16, 0.11, 0.16, paint)
            }
        }

        /// A ute tub from the back of the cab to the tail: floor, liner, sides and tailgate.
        private func tub() {
            let x0: Float = x(end) - 0.04
            let x1: Float = x(L)
            let len: Float = x0 - x1
            let cx: Float = (x0 + x1) / 2
            let hw: Float = half(L - 0.5)
            let floorY: Float = y(sh.sill + 0.25)
            let topY: Float = y(sh.deck)
            Ps1Geo.box(mb, cx, floorY - 0.18, 0, len, 0.36, hw * 1.9, Ps1Models.trim)
            Ps1Geo.box(mb, cx, floorY + 0.02, 0, len, 0.06, hw * 1.86, Ps1Models.dark)
            let wallH: Float = topY - floorY + 0.36
            for z: Float in [hw - 0.06, -(hw - 0.06)] {
                Ps1Geo.box(mb, cx, topY - wallH / 2, z, len, wallH, 0.12, paint)
            }
            Ps1Geo.box(mb, x1 + 0.06, topY - wallH / 2, 0, 0.12, wallH, hw * 2, paint)
            for z: Float in [hw * 0.82, -hw * 0.82] {
                Ps1Geo.box(mb, x1 - 0.01, topY - 0.15, z, 0.06, m(0.2), hw * 0.25, Ps1Models.tail)
            }
            Ps1Geo.box(mb, x1 - 0.02, floorY - 0.1, 0, 0.04, m(0.11), m(0.37), Ps1Models.plate)
            if kit.contains(.canopy) {
                let ch: Float = y(p.topAt(p.roofR) - 0.03) - topY
                Ps1Geo.box(mb, cx, topY + ch / 2, 0, len, ch, hw * 2, paint)
                for z: Float in [hw + 0.005, -hw - 0.005] {
                    Ps1Geo.box(mb, cx, topY + ch * 0.55, z, len * 0.8, ch * 0.55, 0.01, Ps1Models.glass)
                }
            }
        }

        /// A cab-chassis: rails, an alloy tray above the wheels, drop sides and a headboard.
        private func flatDeck() {
            let x0: Float = x(end) - 0.06
            let x1: Float = x(L)
            let len: Float = x0 - x1
            let cx: Float = (x0 + x1) / 2
            let hw: Float = half(end) * 1.02
            for z: Float in [m(0.42), -m(0.42)] {
                Ps1Geo.box(mb, (x(sh.fo) + x1) / 2, y(sh.sill + 0.12), z, x(sh.fo) - x1, m(0.18), m(0.08), Ps1Models.dark)
            }
            let deckY: Float = y(sh.tyre + 0.1)
            Ps1Geo.box(mb, cx, deckY, 0, len, m(0.1), hw * 2.06, Ps1Models.alloy)
            let sideH: Float = m(0.3)
            for z: Float in [hw * 1.02, -hw * 1.02] {
                Ps1Geo.box(mb, cx, deckY + sideH / 2, z, len, sideH, 0.06, Ps1Models.alloy)
            }
            Ps1Geo.box(mb, x1 + 0.03, deckY + sideH / 2, 0, 0.06, sideH, hw * 2.06, Ps1Models.alloy)
            let headTop: Float = y(sh.hgt - 0.12)
            Ps1Geo.box(mb, x0 - 0.04, (deckY + headTop) / 2, 0, 0.08, headTop - deckY, hw * 2.02, Ps1Models.trim)
            for z: Float in [hw * 0.8, -hw * 0.8] {
                Ps1Geo.box(mb, x1 + 0.02, deckY - m(0.12), z, 0.06, m(0.12), hw * 0.28, Ps1Models.tail)
            }
            Ps1Geo.box(mb, x1 + 0.02, deckY - m(0.25), 0, 0.04, m(0.11), m(0.37), Ps1Models.plate)
        }

        /// Open cars: the cockpit, seats with hoops, a raked windscreen frame and, on convertibles, the folded hood.
        private func cockpit() {
            let ws: Float = p.cowl
            let seatD: Float = ws + (L - ws) * 0.42
            let bt: Float = p.topAt(seatD)
            let hw: Float = half(seatD)
            box((ws + seatD + 0.4) / 2, bt - 0.01, 0, m(seatD + 0.4 - ws - 0.1), 0.04, hw * 1.55, Ps1Models.dark)
            for z: Float in [m(0.3), -m(0.3)] {
                box(seatD, bt + 0.15, z, m(0.17), m(0.33), m(0.37), Ps1Models.seat)
                box(seatD + 0.2, bt + 0.25, z, 0.08, m(0.4), 0.08, Ps1Models.chrome)
            }
            Ps1Geo.box(mb, x(ws + 0.1), y(p.topAt(ws) + 0.17), 0, 0.05, m(0.34), half(ws) * 1.75, Ps1Models.glass, rotZ: 0.55)
            if sh.body == .convertible {
                box(seatD + 0.55, bt + 0.05, 0, m(0.5), m(0.1), hw * 1.6, Ps1Models.dark)
            }
        }

        // MARK: Kit

        private func kits(_ axles: [Float]) {
            let rf: Float = p.roofF
            let rr: Float = p.roofR
            let roofW: Float = half((rf + rr) / 2) * p.tumble
            let deck: Float = p.topAt(end - 0.2)
            let hatchback: Bool = p.hasCabin && end - p.glassEnd < 0.35
            for k in kit {
                switch k {
                case .roofRails:
                    if p.hasCabin {
                        for z: Float in [roofW * 0.78, -roofW * 0.78] {
                            box((rf + rr) / 2, p.topAt((rf + rr) / 2) + 0.02, z, m(rr - rf - 0.2), 0.06, 0.08, Ps1Models.dark)
                        }
                    }
                case .roofRack:
                    if p.hasCabin {
                        let h: Float = p.topAt((rf + rr) / 2) + 0.07
                        box((rf + rr) / 2 + 0.1, h, 0, m(rr - rf - 0.35), 0.06, roofW * 1.9, Ps1Models.dark)
                        for z: Float in [roofW * 0.95, -roofW * 0.95] {
                            box((rf + rr) / 2 + 0.1, h + 0.04, z, m(rr - rf - 0.35), 0.08, 0.05, Ps1Models.dark)
                        }
                    }
                case .roofSpoiler:
                    if p.hasCabin {
                        Ps1Geo.box(mb, x(rr) - 0.1, y(p.topAt(rr)) - 0.03, 0, 0.3, 0.04, half(rr) * 1.7, paint, rotZ: 0.12)
                    }
                case .wing:
                    let wd: Float = end - 0.25
                    let base: Float = p.topAt(wd)
                    for z: Float in [half(wd) * 0.6, -half(wd) * 0.6] {
                        box(wd, base + 0.12, z, 0.1, m(0.24), 0.06, Ps1Models.dark)
                    }
                    box(wd, base + 0.25, 0, m(0.3), 0.05, half(wd) * 1.85, paint)
                case .bootLip:
                    if !hatchback { box(end - 0.1, deck + 0.02, 0, 0.18, 0.05, half(end - 0.1) * 1.6, paint) }
                case .ducktail:
                    box(end - 0.18, deck + 0.03, 0, 0.32, 0.08, half(end - 0.18) * 1.7, paint, rotZ: -0.25)
                case .scoop:
                    let sd: Float = p.cowl * 0.5
                    let h: Float = p.topAt(sd) + 0.04
                    box(sd, h, 0, m(0.36), 0.1, m(0.4), paint)
                    box(sd - 0.17, h, 0, 0.03, 0.07, m(0.34), Ps1Models.dark)
                case .bulge:
                    box(p.cowl * 0.5, p.topAt(p.cowl * 0.5) + 0.02, 0, m(p.cowl * 0.8), 0.08, half(p.cowl * 0.5) * 0.6, paint)
                case .stripes:
                    stripes()
                case .rooBar:
                    let b: Float = p.botAt(0)
                    box(-0.08, b + 0.12, 0, 0.14, m(0.36), half(0.2) * 1.95, Ps1Models.dark)
                    for z: Float in [m(0.3), -m(0.3)] { box(-0.1, p.topAt(0.3) - 0.05, z, 0.08, m(0.36), 0.08, Ps1Models.dark) }
                    box(-0.1, p.topAt(0.3) + 0.1, 0, 0.08, 0.08, m(0.68), Ps1Models.dark)
                case .snorkel:
                    let sd: Float = p.cowl + 0.12
                    let top: Float = (p.hasCabin ? p.topAt(sd) : p.topAt(p.cowl)) + 0.05
                    let b: Float = p.beltAt(sd) - 0.35
                    let z: Float = -(half(sd) + m(0.06))
                    box(sd, (b + top) / 2, z, 0.14, m(top - b), 0.14, Ps1Models.dark)
                    box(sd - 0.08, top, z, m(0.22), 0.14, 0.16, Ps1Models.dark)
                case .spare:
                    Ps1Geo.wheel(mb, x(end) - 0.17, y(p.beltAt(end) - 0.12), 0, radius: r * 0.95, width: m(sh.tyreW),
                                 Ps1Models.tyre, rim, segments: 12, rotY: Float.pi / 2)
                case .sportsBar:
                    if ute && !t.flatDeck {
                        let bd: Float = end + 0.3
                        let top: Float = p.topAt(p.roofR) - 0.06
                        let hw: Float = half(L - 0.5)
                        for z: Float in [hw * 0.85, -hw * 0.85] { box(bd, (sh.deck + top) / 2, z, 0.1, m(top - sh.deck), 0.1, Ps1Models.dark) }
                        box(bd, top, 0, 0.1, 0.1, hw * 1.8, Ps1Models.dark)
                    }
                case .steps:
                    let len: Float = m(sh.wb - sh.tyre - 0.1)
                    let hw: Float = half(sh.fo + sh.wb / 2)
                    for side: Float in [1, -1] {
                        Ps1Geo.box(mb, (axles[0] + axles[1]) / 2, y(p.botAt(sh.fo + sh.wb / 2) - 0.03), side * (hw + 0.06),
                                   len, 0.05, 0.18, Ps1Models.dark)
                    }
                default:
                    break
                }
            }
        }

        /// Twin stripes over every painted top surface, nose to tail, skipping the glass.
        private func stripes() {
            let ds: [Float] = stations()
            for i in 0..<(ds.count - 1) {
                let zn: Int = zone((ds[i] + ds[i + 1]) / 2)
                if zn == 1 || zn == 3 { continue }
                let ya: Float = y(p.topAt(ds[i])) + 0.012
                let yb: Float = y(p.topAt(ds[i + 1])) + 0.012
                let xa: Float = x(ds[i])
                let xb: Float = x(ds[i + 1])
                for (z0, z1) in [(Float(0.06), Float(0.26)), (Float(-0.26), Float(-0.06))] {
                    let uv: V2 = V2(0.5, 0.5)
                    Ps1Geo.flatTri(mb, Ps1Models.plate, V3(xa, ya, z0), V3(xb, yb, z0), V3(xb, yb, z1), uv, uv, uv, V3(0, 1, 0))
                    Ps1Geo.flatTri(mb, Ps1Models.plate, V3(xa, ya, z0), V3(xb, yb, z1), V3(xa, ya, z1), uv, uv, uv, V3(0, 1, 0))
                }
            }
        }
    }

    /// The dark wheel-arch opening on a body side: a fan over the top of the wheel, facing out along `side` z.
    private static func arch(_ mb: MeshBuilder, _ cx: Float, _ cy: Float, _ z: Float, _ r: Float, _ side: Float, _ s: Surf = Ps1Models.arch) {
        let n: Int = 8
        let centre: Int = mb.vertex(V3(cx, cy - r * 0.15, z), V3(0, 0, side), V2(0.5, 0.5), s)
        var ring: [Int] = []
        for i in 0...n {
            let a: Float = Float.pi * (-0.08 + 1.16 * Float(i) / Float(n))
            ring.append(mb.vertex(V3(cx + r * cosf(a), cy + r * sinf(a), z), V3(0, 0, side), V2(0, 0), s))
        }
        for i in 0..<n {
            if side > 0 {
                mb.tri(centre, ring[i], ring[i + 1])
            } else {
                mb.tri(centre, ring[i + 1], ring[i])
            }
        }
    }

    // MARK: Two wheels

    /// Motorcycle shapes in model units (pixel coordinates 75 + 20x, 48 - 20y for Ps1Geo.extrude).
    private static func u(_ v: [Float]) -> [V2] {
        var out: [V2] = []
        var i: Int = 0
        while i + 1 < v.count {
            out.append(V2(75 + v[i] * 20, 48 - v[i + 1] * 20))
            i += 2
        }
        return out
    }

    private static func bike(_ mb: MeshBuilder, _ body: Ps1Body, _ s: BikeSpec, _ paint: Surf, _ rim: Surf, _ dy: Float) {
        let rearX: Float = s.rearX
        let frontX: Float = s.frontX
        let seat: Surf = Ps1Models.seat
        switch body {
        case .naked:
            Ps1Geo.extrude(mb, u([-0.5, 0.78, -0.4, 1.1, 0.2, 1.26, 0.85, 1.2, 1.0, 1.0, 0.85, 0.82]), depth: 0.62, taper: 0.8, paint, taperY: 0.95, dy: dy)
            Ps1Geo.extrude(mb, u([-2.05, 0.86, -1.95, 1.12, -1.2, 1.2, -0.5, 1.1, -0.5, 0.84]), depth: 0.46, taper: 0.8, paint, taperY: 1.0, dy: dy)
            Ps1Geo.extrude(mb, u([-1.5, 1.2, -0.5, 1.12, -0.45, 0.98, -1.5, 1.0]), depth: 0.5, taper: 0.9, seat, taperY: 5, dy: dy)
            Ps1Geo.box(mb, 1.02, 1.28 + dy, 0, 0.1, 0.1, 1.2, Ps1Models.dark)
            Ps1Geo.box(mb, 1.22, 1.0 + dy, 0, 0.14, 0.28, 0.28, Ps1Models.head)
        case .sportBike:
            // Full fairing from the nose down round the engine, a tall tail, clip-ons under the screen.
            Ps1Geo.extrude(mb, u([-0.6, 0.3, -0.6, 0.95, -0.2, 1.18, 0.7, 1.2, 1.45, 1.0, 1.6, 0.75, 1.3, 0.35, 0.4, 0.15]), depth: 0.7, taper: 0.75, paint, taperY: 0.9, dy: dy)
            Ps1Geo.extrude(mb, u([1.05, 1.2, 1.45, 1.0, 1.25, 1.42]), depth: 0.45, taper: 0.8, Ps1Models.glass, taperY: 5, dy: dy + 0.01)
            Ps1Geo.extrude(mb, u([-2.15, 1.05, -2.0, 1.32, -1.1, 1.25, -0.6, 1.0, -0.6, 0.85]), depth: 0.44, taper: 0.8, paint, taperY: 1.0, dy: dy)
            Ps1Geo.extrude(mb, u([-1.4, 1.24, -0.55, 1.14, -0.5, 1.02, -1.4, 1.06]), depth: 0.42, taper: 0.9, seat, taperY: 5, dy: dy)
            Ps1Geo.box(mb, 1.58, 0.82 + dy, 0, 0.08, 0.16, 0.4, Ps1Models.head)
        case .cruiser:
            // Long and low: teardrop tank, low scooped seat, big fenders, raked forks, wide bars.
            Ps1Geo.extrude(mb, u([-0.6, 0.95, -0.4, 1.2, 0.4, 1.28, 1.0, 1.15, 0.9, 0.95]), depth: 0.66, taper: 0.8, paint, taperY: 1.0, dy: dy)
            Ps1Geo.extrude(mb, u([-1.5, 0.88, -0.6, 0.95, -0.6, 0.82, -1.4, 0.75]), depth: 0.62, taper: 0.9, seat, taperY: 5, dy: dy)
            Ps1Geo.extrude(mb, u([-2.4, 0.35, -2.3, 0.75, -1.75, 0.92, -1.2, 0.8, -1.6, 0.68, -2.2, 0.4]), depth: 0.5, taper: 0.9, paint, taperY: 5, dy: dy)
            Ps1Geo.extrude(mb, u([1.45, 0.62, 1.75, 0.72, 2.2, 0.6, 2.35, 0.25, 2.0, 0.5]), depth: 0.36, taper: 0.9, paint, taperY: 5, dy: dy)
            Ps1Geo.box(mb, 1.25, 1.42 + dy, 0, 0.1, 0.1, 1.5, Ps1Models.chrome)
            Ps1Geo.box(mb, 1.55, 1.05 + dy, 0, 0.24, 0.3, 0.3, Ps1Models.head)
            for z: Float in [0.32, -0.32] {
                Ps1Geo.box(mb, -1.2, 0.32 + dy, z, 2.2, 0.16, 0.14, Ps1Models.chrome, rotZ: 0.04)
            }
        case .adventure:
            // Tall: a big tank, a beak, a high screen, panniers on a rack.
            Ps1Geo.extrude(mb, u([-0.55, 0.9, -0.45, 1.3, 0.3, 1.45, 0.95, 1.35, 1.1, 1.0, 0.9, 0.85]), depth: 0.72, taper: 0.82, paint, taperY: 1.1, dy: dy)
            Ps1Geo.extrude(mb, u([1.1, 1.05, 1.7, 0.95, 1.15, 0.85]), depth: 0.3, taper: 0.9, paint, taperY: 5, dy: dy)
            Ps1Geo.extrude(mb, u([1.0, 1.4, 1.25, 1.45, 1.05, 2.0, 0.85, 1.95]), depth: 0.5, taper: 0.85, Ps1Models.glass, taperY: 5, dy: dy + 0.01)
            Ps1Geo.extrude(mb, u([-1.9, 1.15, -0.5, 1.32, -0.45, 1.18, -1.9, 1.05]), depth: 0.48, taper: 0.9, seat, taperY: 5, dy: dy)
            for z: Float in [0.48, -0.48] {
                Ps1Geo.box(mb, -1.55, 0.95 + dy, z, 0.9, 0.62, 0.3, Ps1Models.trim)
            }
            Ps1Geo.box(mb, 0.98, 1.5 + dy, 0, 0.1, 0.1, 1.2, Ps1Models.dark)
            Ps1Geo.box(mb, 1.25, 1.12 + dy, 0, 0.12, 0.22, 0.3, Ps1Models.head)
        case .tourer:
            // A full fairing with a tall screen, a long two-up seat, panniers and a top box.
            Ps1Geo.extrude(mb, u([-0.6, 0.5, -0.6, 1.1, 0.2, 1.35, 1.0, 1.3, 1.5, 1.0, 1.55, 0.6, 0.9, 0.35]), depth: 0.82, taper: 0.8, paint, taperY: 1.0, dy: dy)
            Ps1Geo.extrude(mb, u([1.0, 1.32, 1.35, 1.05, 1.15, 1.85, 0.95, 1.8]), depth: 0.55, taper: 0.85, Ps1Models.glass, taperY: 5, dy: dy + 0.01)
            Ps1Geo.extrude(mb, u([-1.6, 1.25, -0.55, 1.2, -0.5, 1.05, -1.6, 1.08]), depth: 0.55, taper: 0.9, seat, taperY: 5, dy: dy)
            for z: Float in [0.5, -0.5] {
                Ps1Geo.box(mb, -1.55, 0.85 + dy, z, 0.95, 0.6, 0.32, paint)
            }
            Ps1Geo.box(mb, -2.05, 1.45 + dy, 0, 0.6, 0.45, 0.6, paint)
            Ps1Geo.box(mb, 1.55, 1.0 + dy, 0, 0.1, 0.18, 0.5, Ps1Models.head)
        case .dirt:
            // Tall and thin: shrouds, a long flat seat, high fenders and a number board.
            Ps1Geo.extrude(mb, u([-0.4, 1.1, -0.2, 1.4, 0.6, 1.45, 0.95, 1.25, 0.5, 0.95]), depth: 0.5, taper: 0.85, paint, taperY: 1.2, dy: dy)
            Ps1Geo.extrude(mb, u([-1.9, 1.45, -0.2, 1.5, 0.3, 1.42, -1.9, 1.35]), depth: 0.3, taper: 0.9, seat, taperY: 5, dy: dy)
            Ps1Geo.extrude(mb, u([-2.35, 1.45, -1.5, 1.52, -1.5, 1.38, -2.25, 1.38]), depth: 0.26, taper: 0.9, paint, taperY: 5, dy: dy)
            Ps1Geo.extrude(mb, u([1.2, 1.0, 1.7, 1.06, 1.95, 0.96, 1.3, 0.92]), depth: 0.2, taper: 0.9, paint, taperY: 5, dy: dy)
            Ps1Geo.box(mb, 1.12, 1.25 + dy, 0, 0.06, 0.35, 0.3, Ps1Models.plate)
            Ps1Geo.box(mb, 0.95, 1.65 + dy, 0, 0.08, 0.08, 1.3, Ps1Models.dark)
        case .scooter:
            // Step-through: a leg shield, a floorboard, and the body over the engine at the back.
            Ps1Geo.extrude(mb, u([0.55, 0.25, 0.7, 1.2, 1.0, 1.28, 1.1, 0.45]), depth: 0.5, taper: 0.85, paint, taperY: 5, dy: dy)
            Ps1Geo.box(mb, -0.15, 0.35 + dy, 0, 1.3, 0.12, 0.45, paint)
            Ps1Geo.extrude(mb, u([-1.8, 0.4, -1.9, 0.95, -1.5, 1.1, -0.45, 1.0, -0.45, 0.4]), depth: 0.55, taper: 0.85, paint, taperY: 0.9, dy: dy)
            Ps1Geo.extrude(mb, u([-1.6, 1.18, -0.55, 1.1, -0.55, 1.0, -1.6, 1.06]), depth: 0.45, taper: 0.9, seat, taperY: 5, dy: dy)
            Ps1Geo.box(mb, 0.85, 1.42 + dy, 0, 0.08, 0.08, 0.8, Ps1Models.chrome)
            Ps1Geo.box(mb, 1.02, 1.18 + dy, 0, 0.12, 0.18, 0.3, Ps1Models.head)
        default:
            break
        }
        // Engine, swing arm and exhaust, forks, tail light, then the wheels.
        if body != .scooter {
            Ps1Geo.box(mb, 0.1, 0.5 + dy, 0, 1.2, 0.7, 0.56, Ps1Models.trim)
        }
        for z: Float in [0.25, -0.25] {
            Ps1Geo.box(mb, (rearX + 0.1) / 2, 0.3 + dy * 0.5, z, -rearX + 0.2, 0.1, 0.08, Ps1Models.trim, rotZ: 0.12)
        }
        if body != .cruiser && body != .scooter {
            Ps1Geo.box(mb, -0.9, (body == .dirt ? 0.75 + dy : 0.3 + dy), 0.34, 1.7, 0.15, 0.14, Ps1Models.chrome,
                       rotZ: body == .sportBike ? 0.35 : 0.08)
        }
        let rake: Float = body == .cruiser ? 0.62 : 0.4
        let forkLen: Float = (body == .adventure || body == .dirt) ? 1.5 : (body == .scooter ? 1.0 : 1.3)
        for z: Float in [0.2, -0.2] {
            Ps1Geo.box(mb, frontX - 0.25, 0.6 + dy * 0.5, z, 0.1, forkLen, 0.09, Ps1Models.chrome, rotZ: rake)
        }
        Ps1Geo.box(mb, rearX - 0.55, 1.0 + dy, 0, 0.1, 0.14, 0.3, Ps1Models.tail)
        Ps1Geo.wheel(mb, rearX, 0, 0, radius: s.wheelR, width: s.wheelW * (body == .cruiser ? 1.3 : 1), Ps1Models.tyre, rim)
        Ps1Geo.wheel(mb, frontX, 0, 0, radius: s.wheelR, width: s.wheelW, Ps1Models.tyre, rim)
    }
}
