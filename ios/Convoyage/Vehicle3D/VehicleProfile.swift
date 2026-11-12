import Foundation

// Port of android/.../render/Ps1Profile.kt: a body as curves, Gran Turismo style.

/// A point on a profile curve: `d` metres back from the nose, `v` a height or half width in metres.
struct Pt {
    var d: Float
    var v: Float
}

/// A vehicle's body as curves: what it looks like from the side, from above, and where its glass is.
/// All in metres from the front bumper and the ground.
///
/// `top` is the centreline from the nose over the roof to the tail (or the back of a ute's cab). The cabin markers are
/// `cowl` (foot of the windscreen), `roofF` and `roofR` (ends of the roof) and `glassEnd` (foot of the rear glass).
/// `bot` is the underside, `plan` the half width at the widest line, `belt` the side window line in the cabin, and
/// `windows` the side glass between pillars.
struct Ps1Profile {
    var top: [Pt]
    var bot: [Pt]
    var plan: [Pt]
    var belt: [Pt]
    var cowl: Float
    var roofF: Float
    var roofR: Float
    var glassEnd: Float
    var windows: [ClosedRange<Float>]
    /// Roof half width as a share of the body's.
    var tumble: Float

    /// Back of the shell: the tail, or the back of a ute's cab.
    var end: Float { return top.last?.d ?? 0 }
    var hasCabin: Bool { return roofR > roofF }

    func topAt(_ d: Float) -> Float { return Ps1Profile.at(top, d) }
    func botAt(_ d: Float) -> Float { return Ps1Profile.at(bot, d) }
    func wAt(_ d: Float) -> Float { return Ps1Profile.at(plan, d) }
    func beltAt(_ d: Float) -> Float { return belt.isEmpty ? topAt(d) : Ps1Profile.at(belt, d) }

    /// The same body stretched to other dimensions (a sibling model, or a variant a few centimetres off).
    func scaled(_ len: Float, _ wid: Float, _ hgt: Float) -> Ps1Profile {
        let sl: Float = len / lenOf()
        let sw: Float = wid / 2 / (plan.map { (p: Pt) -> Float in return p.v }.max() ?? 1)
        let sh: Float = hgt / (top.map { (p: Pt) -> Float in return p.v }.max() ?? 1)
        func l(_ p: [Pt], _ sv: Float) -> [Pt] {
            return p.map { (q: Pt) -> Pt in return Pt(d: q.d * sl, v: q.v * sv) }
        }
        return Ps1Profile(top: l(top, sh), bot: l(bot, sh), plan: l(plan, sw), belt: l(belt, sh),
                          cowl: cowl * sl, roofF: roofF * sl, roofR: roofR * sl, glassEnd: glassEnd * sl,
                          windows: windows.map { (r: ClosedRange<Float>) -> ClosedRange<Float> in
                              return (r.lowerBound * sl)...(r.upperBound * sl)
                          },
                          tumble: tumble)
    }

    /// The authored overall length: the tail, or for a ute the furthest the body curves reach.
    private func lenOf() -> Float {
        return max(top.last?.d ?? 0, max(bot.last?.d ?? 0, plan.last?.d ?? 0))
    }

    static func at(_ p: [Pt], _ d: Float) -> Float {
        guard let first = p.first else { return 0 }
        if d <= first.d { return first.v }
        for i in 1..<p.count where d <= p[i].d {
            let a: Pt = p[i - 1]
            let b: Pt = p[i]
            let t: Float = (b.d - a.d < 1e-5) ? 1 : (d - a.d) / (b.d - a.d)
            return a.v + (b.v - a.v) * t
        }
        return p.last?.v ?? first.v
    }

    /// "0:.5 1.4c:.97 2.2f:1.43": points, with c/f/r/b marking cowl, roof front, roof rear and glass end.
    static func parse(_ s: String) -> (pts: [Pt], marks: [Character: Float]) {
        var marks: [Character: Float] = [:]
        var pts: [Pt] = []
        for tok in s.trimmingCharacters(in: .whitespaces).split(whereSeparator: { (c: Character) -> Bool in return c.isWhitespace }) {
            let parts: [Substring] = tok.split(separator: ":")
            guard parts.count == 2 else { continue }
            var dp: String = String(parts[0])
            var mark: Character? = nil
            if let last = dp.last, last.isLetter {
                mark = last
                dp.removeLast()
            }
            let d: Float = Float(dp) ?? 0
            let v: Float = Float(String(parts[1])) ?? 0
            if let m = mark { marks[m] = d }
            pts.append(Pt(d: d, v: v))
        }
        return (pts, marks)
    }

    /// A hand-authored profile; see the struct for the curves.
    static func of(top: String, bot: String, plan: String, belt: String, win: String, tumble: Float = 0.75) -> Ps1Profile {
        let t: (pts: [Pt], marks: [Character: Float]) = parse(top)
        let c: Float = t.marks["c"] ?? (t.pts.first?.d ?? 0)
        let f: Float = t.marks["f"] ?? c
        let r: Float = t.marks["r"] ?? f
        let b: Float = t.marks["b"] ?? r
        var wins: [ClosedRange<Float>] = []
        for tok in win.trimmingCharacters(in: .whitespaces).split(whereSeparator: { (ch: Character) -> Bool in return ch.isWhitespace }) {
            let ab: [Substring] = tok.split(separator: "-")
            guard ab.count == 2, let a = Float(String(ab[0])), let z = Float(String(ab[1])) else { continue }
            wins.append(a...z)
        }
        return Ps1Profile(top: t.pts, bot: parse(bot).pts, plan: parse(plan).pts, belt: parse(belt).pts,
                          cowl: c, roofF: f, roofR: r, glassEnd: b, windows: wins, tumble: tumble)
    }

    /// The profile of a shape with no hand-made one, from its body template and real dimensions.
    static func from(_ sh: Ps1Shape) -> Ps1Profile {
        let t: Ps1Tpl = sh.tpl
        let L: Float = sh.len
        let H: Float = sh.hgt
        let hw: Float = sh.wid / 2
        let sill: Float = sh.sill
        let nose: Float = sh.nose
        let belt: Float = sh.belt
        let deck: Float = sh.deck
        let ws: Float = sh.ws
        let rf: Float = sh.roofF
        let end: Float = t.cab > 0 ? sh.cab : L
        let rr: Float = min(sh.roofR, end - 0.05)
        var top: [Pt] = []
        top.append(Pt(d: 0, v: nose - 0.15))
        top.append(Pt(d: 0.16, v: nose - 0.03))
        top.append(Pt(d: ws * 0.55, v: nose + (belt - nose) * 0.6))
        top.append(Pt(d: ws, v: belt))
        var gEnd: Float
        if t.open {
            top.append(Pt(d: L - 0.14, v: deck))
            top.append(Pt(d: L, v: deck - 0.09))
            return Ps1Profile(top: top, bot: bottom(sill, L), plan: planCurve(sh, hw, L), belt: [],
                              cowl: ws, roofF: ws, roofR: ws, glassEnd: ws, windows: [], tumble: 0.75)
        }
        top.append(Pt(d: rf, v: H))
        top.append(Pt(d: (rf + rr) / 2, v: H))
        top.append(Pt(d: rr, v: H - 0.03))
        if t.cab > 0 {
            gEnd = end
            top.append(Pt(d: end, v: belt + 0.06))
        } else if t.rw >= 0.995 {
            gEnd = L - 0.06
            top.append(Pt(d: gEnd, v: belt + 0.03))
            top.append(Pt(d: L, v: deck - 0.12))
        } else if L - sh.rw < 0.3 {
            gEnd = sh.rw
            top.append(Pt(d: gEnd, v: deck + 0.03))
            top.append(Pt(d: L, v: deck - 0.12))
        } else {
            gEnd = sh.rw
            top.append(Pt(d: gEnd, v: deck + 0.02))
            top.append(Pt(d: L - 0.14, v: deck))
            top.append(Pt(d: L, v: deck - 0.09))
        }
        var wins: [ClosedRange<Float>] = []
        if t.blind {
            wins.append((rf + 0.08)...(rf + 0.9))
        } else if t.doors >= 4 {
            let pb: Float = rf + (rr - rf) * (t.longRoof ? 0.40 : 0.50)
            wins.append((rf + 0.1)...(pb - 0.05))
            if t.longRoof {
                let pc: Float = rf + (rr - rf) * 0.78
                wins.append((pb + 0.05)...(pc - 0.05))
                wins.append((pc + 0.05)...(rr - 0.06))
            } else {
                wins.append((pb + 0.05)...(rr - 0.1))
            }
        } else {
            wins.append((rf + 0.1)...(rr - (t.cab > 0 ? 0.06 : 0.15)))
        }
        return Ps1Profile(top: top, bot: bottom(sill, end), plan: planCurve(sh, hw, L),
                          belt: [Pt(d: ws, v: belt), Pt(d: L, v: belt + 0.04)],
                          cowl: ws, roofF: rf, roofR: rr, glassEnd: gEnd, windows: wins, tumble: 0.76)
    }

    private static func bottom(_ sill: Float, _ end: Float) -> [Pt] {
        return [Pt(d: 0, v: sill + 0.18), Pt(d: 0.16, v: sill + 0.03),
                Pt(d: end - 0.16, v: sill + 0.03), Pt(d: end, v: sill + 0.16)]
    }

    private static func planCurve(_ sh: Ps1Shape, _ hw: Float, _ L: Float) -> [Pt] {
        return [Pt(d: 0, v: hw * 0.84), Pt(d: 0.15, v: hw * 0.95), Pt(d: sh.fo, v: hw),
                Pt(d: sh.fo + sh.wb / 2, v: hw * 0.985), Pt(d: sh.fo + sh.wb, v: hw),
                Pt(d: L - 0.15, v: hw * 0.95), Pt(d: L, v: hw * 0.86)]
    }
}

/// Hand-made bodies for catalogue models, authored at each car's real dimensions. A sibling model can borrow one (it
/// is stretched to its own length, width and height).
enum Ps1Profiles {
    private static let focusRs: Ps1Profile = Ps1Profile.of(
        top: "0:.48 .06:.62 .3:.76 .8:.86 1.42c:.97 2.2f:1.43 2.75:1.472 3.5r:1.41 4.12b:1.06 4.3:1.03 4.39:.84",
        bot: "0:.2 .25:.14 4.15:.16 4.39:.36",
        plan: "0:.62 .2:.82 .55:.88 .88:.915 1.3:.9 2.6:.895 3.53:.915 4.05:.88 4.3:.78 4.39:.68",
        belt: "1.42:.97 2.3:1.0 3.55:1.07", win: "1.65-2.55 2.65-3.3 3.36-3.6", tumble: 0.74)
    private static let i30: Ps1Profile = Ps1Profile.of(
        top: "0:.5 .06:.63 .3:.75 .8:.85 1.4c:.96 2.2f:1.42 2.8:1.455 3.48r:1.39 4.1b:1.05 4.26:1.02 4.34:.84",
        bot: "0:.24 .25:.16 4.1:.17 4.34:.38",
        plan: "0:.6 .2:.8 .6:.87 .88:.8975 2.6:.89 3.53:.8975 4.05:.87 4.3:.76 4.34:.66",
        belt: "1.4:.96 2.3:.99 3.5:1.05", win: "1.62-2.5 2.6-3.25 3.32-3.55", tumble: 0.75)
    private static let golf: Ps1Profile = Ps1Profile.of(
        top: "0:.5 .06:.63 .3:.76 .8:.86 1.38c:.98 2.15f:1.44 2.8:1.478 3.6r:1.42 4.1b:1.07 4.22:1.03 4.289:.85",
        bot: "0:.2 .25:.14 4.1:.16 4.289:.36",
        plan: "0:.62 .2:.82 .6:.875 .85:.8945 2.6:.885 3.48:.8945 4:.87 4.289:.72",
        belt: "1.38:.98 3.6:1.05", win: "1.6-2.5 2.6-3.25 3.32-3.62", tumble: 0.76)
    private static let grYaris: Ps1Profile = Ps1Profile.of(
        top: "0:.5 .06:.62 .25:.73 .7:.82 1.2c:.95 1.95f:1.42 2.4:1.455 2.95r:1.38 3.75b:1.04 3.9:1 3.995:.82",
        bot: "0:.18 .25:.13 3.8:.16 3.995:.36",
        plan: "0:.6 .2:.82 .55:.88 .75:.9025 1.2:.87 2.4:.86 3.31:.9025 3.8:.85 3.995:.72",
        belt: "1.2:.95 3:1.05", win: "1.45-2.75", tumble: 0.7)
    private static let bmwG80: Ps1Profile = Ps1Profile.of(
        top: "0:.52 .05:.63 .25:.74 .9:.83 1.65c:.96 2.45f:1.4 3:1.433 3.6r:1.39 4.18b:1.02 4.45:1 4.7:.97 4.794:.8",
        bot: "0:.17 .3:.13 4.5:.15 4.794:.36",
        plan: "0:.66 .2:.86 .55:.92 .85:.95 1.4:.93 2.6:.92 3.7:.95 4.3:.93 4.65:.84 4.794:.74",
        belt: "1.65:.96 2.6:.98 3.8:1.02", win: "1.9-2.85 2.95-3.55 3.58-3.75", tumble: 0.72)
    private static let bmwE46: Ps1Profile = Ps1Profile.of(
        top: "0:.5 .08:.62 .3:.72 .9:.8 1.5c:.92 2.25f:1.33 2.8:1.372 3.35r:1.33 3.85b:.98 4.35:.96 4.492:.78",
        bot: "0:.18 .3:.13 4.2:.15 4.492:.34",
        plan: "0:.62 .2:.82 .55:.87 .85:.89 1.3:.88 2.6:.875 3.6:.89 4.2:.86 4.492:.7",
        belt: "1.5:.92 3.4:.98", win: "1.75-2.9 2.95-3.25", tumble: 0.74)
    private static let wrx: Ps1Profile = Ps1Profile.of(
        top: "0:.52 .06:.64 .3:.75 .9:.85 1.5c:.97 2.25f:1.43 2.8:1.465 3.35r:1.42 4.05b:1.03 4.5:1.01 4.67:.84",
        bot: "0:.2 .3:.15 4.4:.16 4.67:.38",
        plan: "0:.64 .2:.84 .6:.9 .95:.9125 1.4:.89 2.6:.885 3.62:.9125 4.2:.88 4.67:.74",
        belt: "1.5:.97 3.4:1.02", win: "1.75-2.65 2.75-3.35", tumble: 0.73)
    private static let mustang: Ps1Profile = Ps1Profile.of(
        top: "0:.6 .05:.72 .3:.82 1.1:.9 1.9c:.98 2.55f:1.35 2.95:1.381 3.3r:1.33 4.2b:1 4.6:.99 4.784:.85",
        bot: "0:.18 .3:.14 4.5:.16 4.784:.36",
        plan: "0:.7 .25:.88 .6:.94 .95:.958 1.5:.93 2.8:.93 3.67:.958 4.3:.93 4.784:.8",
        belt: "1.9:.98 3.3:1.03", win: "2.15-3.2", tumble: 0.7)
    private static let commodore: Ps1Profile = Ps1Profile.of(
        top: "0:.55 .06:.67 .3:.77 1:.86 1.6c:.98 2.4f:1.43 3:1.471 3.65r:1.42 4.35b:1.05 4.8:1.03 4.964:.86",
        bot: "0:.2 .3:.15 4.7:.17 4.964:.38",
        plan: "0:.66 .2:.86 .6:.93 .95:.949 1.5:.93 2.8:.925 3.87:.949 4.5:.92 4.964:.78",
        belt: "1.6:.98 3.7:1.03", win: "1.85-2.85 2.95-3.6", tumble: 0.73)
    private static let patrolY62: Ps1Profile = Ps1Profile.of(
        top: "0:.78 .08:.95 .3:1.07 1:1.15 1.55c:1.24 2.25f:1.86 3:1.94 4.95r:1.9 5.07b:1.3 5.12:1.22 5.165:1.05",
        bot: "0:.42 .35:.38 4.8:.42 5.165:.55",
        plan: "0:.8 .2:.95 .95:.9975 2.5:.99 4.03:.9975 5:.97 5.165:.9",
        belt: "1.55:1.24 2.6:1.28 5:1.33", win: "1.85-2.85 2.95-3.85 3.95-4.85", tumble: 0.8)
    private static let lc300: Ps1Profile = Ps1Profile.of(
        top: "0:.82 .08:.98 .3:1.1 1:1.17 1.5c:1.26 2.15f:1.85 2.9:1.95 4.8r:1.92 4.92b:1.32 4.96:1.25 4.98:1.05",
        bot: "0:.45 .35:.4 4.7:.45 4.98:.55",
        plan: "0:.82 .2:.96 .95:.99 4:.99 4.85:.95 4.98:.88",
        belt: "1.5:1.26 4.8:1.33", win: "1.8-2.75 2.85-3.75 3.85-4.7", tumble: 0.8)
    // Utes: the cab only; the tub or tray runs from the end of `top` to the tail.
    private static let hilux: Ps1Profile = Ps1Profile.of(
        top: "0:.8 .08:.98 .35:1.08 1.2:1.13 1.45c:1.17 2.05f:1.77 2.6:1.815 3.45r:1.79 3.52b:1.25",
        bot: "0:.45 .35:.42 3.52:.45",
        plan: "0:.78 .25:.9 .95:.9275 3.52:.92 5.325:.92",
        belt: "1.45:1.17 3.5:1.22", win: "2.05-2.8 2.9-3.42", tumble: 0.82)
    private static let ranger: Ps1Profile = Ps1Profile.of(
        top: "0:.85 .08:1.02 .3:1.12 1.25:1.18 1.5c:1.21 2.1f:1.84 2.7:1.884 3.55r:1.86 3.62b:1.3",
        bot: "0:.45 .35:.42 3.62:.46",
        plan: "0:.82 .25:.94 .95:.959 3.62:.955 5.37:.955",
        belt: "1.5:1.21 3.6:1.26", win: "2.1-2.9 3-3.52", tumble: 0.82)
    private static let lc79: Ps1Profile = Ps1Profile.of(
        top: "0:.85 .05:1 .2:1.12 1.25:1.22 1.55c:1.27 1.95f:1.9 2.2:1.955 2.4r:1.93 2.45b:1.3",
        bot: "0:.47 .3:.45 2.45:.48",
        plan: "0:.88 .3:.92 2.45:.935 5.22:.935",
        belt: "1.55:1.27 2.45:1.3", win: "1.95-2.38", tumble: 0.86)

    private static let hand: [String: Ps1Profile] = [
        "ford-focus-rs-mk3": focusRs, "ford-focus-rs-mk2": focusRs, "ford-focus-st-mk4": focusRs, "ford-focus-mk4": focusRs,
        "hyundai-i30-pd": i30, "hyundai-i30n-pd": i30,
        "vw-golf-gti-mk8": golf, "vw-golf-gti-mk7": golf, "vw-golf-r-mk8": golf, "vw-golf-mk8": golf,
        "toyota-gr-yaris": grYaris,
        "bmw-m3-g80": bmwG80, "bmw-3-g20": bmwG80, "bmw-5-g30": bmwG80,
        "bmw-m3-e46": bmwE46,
        "subaru-wrx-vb": wrx, "subaru-wrx-va": wrx, "subaru-wrx-sti-va": wrx,
        "ford-mustang-s550": mustang, "ford-mustang-s650": mustang,
        "holden-commodore-vf": commodore, "ford-falcon-fg": commodore,
        "nissan-patrol-y62": patrolY62,
        "toyota-lc300": lc300, "toyota-lc200": lc300,
        "toyota-hilux-an120": hilux,
        "ford-ranger-p703": ranger, "ford-ranger-raptor-p703": ranger, "vw-amarok-2": ranger,
        "toyota-lc79": lc79,
    ]

    nonisolated(unsafe) private static var cache: [String: Ps1Profile] = [:]
    private static let lock: NSLock = NSLock()

    /// The body curves of `sh`: its hand-made profile stretched to its dimensions, else one built from its template.
    static func of(_ sh: Ps1Shape) -> Ps1Profile {
        lock.lock()
        defer { lock.unlock() }
        if let hit = cache[sh.id] { return hit }
        let p: Ps1Profile = hand[sh.id]?.scaled(sh.len, sh.wid, sh.hgt) ?? Ps1Profile.from(sh)
        cache[sh.id] = p
        return p
    }

    static func isHandMade(_ id: String) -> Bool {
        return hand[id] != nil
    }
}
