import Foundation
import simd

// The four vehicles, built in code the way design-tools/ps1.html builds its car: extruded side profiles, boxes and
// 8-sided wheels. Port of android/.../render/Ps1Mesh.kt and Ps1Models.kt.
// x is forward (the lights at +x), y is up and z is across. Model units are ps1.html's, about 20 px each in the profiles.

typealias V2 = SIMD2<Float>
typealias V3 = SIMD3<Float>

/// One surface's look: a linear tint, a texture id (0 none, 1 body, 2 rim, 3 ground) and a flag (0 plain, 1 paint, 2 unlit).
struct Surf {
    var r: Float
    var g: Float
    var b: Float
    var tex: Int
    var flag: Int

    static func srgb(_ rgb: UInt32, tex: Int = 0, flag: Int = 0) -> Surf {
        let l: SIMD3<Float> = Ps1Color.linear(rgb: rgb)
        return Surf(r: l.x, g: l.y, b: l.z, tex: tex, flag: flag)
    }

    /// The shader reads this from the vertex colour's alpha.
    var code: Float { return Float(tex * 4 + flag) }
}

/// A finished model: interleaved vertices (position 3, normal 3, uv 2, tint + code 4) and indices.
/// The first `groundIndexCount` indices are the static ground; the rest turn with the vehicle.
struct Ps1Mesh {
    static let stride: Int = 12

    var vertices: [Float]
    var indices: [UInt32]
    var groundIndexCount: Int

    var vertexCount: Int { return vertices.count / Ps1Mesh.stride }
}

/// Per-kind framing numbers for the camera and the ground.
struct Ps1Frame {
    /// Radius of a sphere that holds the vehicle; the camera backs off to fit it.
    let fitRadius: Float
    /// Height of the ground disc (the wheels' lowest point).
    let groundY: Float
    let groundRadius: Float
    /// Height the camera looks at.
    let lookY: Float
}

/// Collects vertices and triangles.
final class MeshBuilder {
    private(set) var data: [Float] = []
    private(set) var indices: [UInt32] = []
    private(set) var vertexCount: Int = 0

    var indexCount: Int { return indices.count }

    @discardableResult
    func vertex(_ p: V3, _ n: V3, _ uv: V2, _ s: Surf) -> Int {
        data.append(p.x)
        data.append(p.y)
        data.append(p.z)
        data.append(n.x)
        data.append(n.y)
        data.append(n.z)
        data.append(uv.x)
        data.append(uv.y)
        data.append(s.r)
        data.append(s.g)
        data.append(s.b)
        data.append(s.code)
        vertexCount += 1
        return vertexCount - 1
    }

    func tri(_ a: Int, _ b: Int, _ c: Int) {
        indices.append(UInt32(a))
        indices.append(UInt32(b))
        indices.append(UInt32(c))
    }

    /// Rotates (about z, then y) and moves every vertex added since `from`, normals too.
    func xform(from: Int, rotY: Float = 0, rotZ: Float = 0, tx: Float = 0, ty: Float = 0, tz: Float = 0) {
        let cz: Float = cosf(rotZ)
        let sz: Float = sinf(rotZ)
        let cy: Float = cosf(rotY)
        let sy: Float = sinf(rotY)
        var i: Int = from
        while i < vertexCount {
            let o: Int = i * Ps1Mesh.stride
            for k in [0, 3] {
                let x: Float = data[o + k]
                let y: Float = data[o + k + 1]
                let z: Float = data[o + k + 2]
                let x1: Float = x * cz - y * sz
                let y1: Float = x * sz + y * cz
                let x2: Float = x1 * cy + z * sy
                let z2: Float = -x1 * sy + z * cy
                data[o + k] = x2
                data[o + k + 1] = y1
                data[o + k + 2] = z2
            }
            data[o] += tx
            data[o + 1] += ty
            data[o + 2] += tz
            i += 1
        }
    }

    func build(groundIndexCount: Int) -> Ps1Mesh {
        return Ps1Mesh(vertices: data, indices: indices, groundIndexCount: groundIndexCount)
    }
}

/// Shape helpers. The car is about 7 long and the wheels sit on y = 0 - radius.
enum Ps1Geo {
    /// The profile pixel space of ps1.html (20 px per unit, x centre 75, y baseline 48) to model units.
    static func profile(_ px: [V2]) -> [V2] {
        return px.map { (q: V2) -> V2 in return V2((q.x - 75) / 20, (48 - q.y) / 20) }
    }

    static func area(_ p: [V2]) -> Float {
        var a: Float = 0
        for i in 0..<p.count {
            let p0: V2 = p[i]
            let p1: V2 = p[(i + 1) % p.count]
            a += p0.x * p1.y - p1.x * p0.y
        }
        return a / 2
    }

    private static func cross(_ a: V2, _ b: V2, _ c: V2) -> Float {
        return (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)
    }

    private static func inside(_ p: V2, _ a: V2, _ b: V2, _ c: V2) -> Bool {
        return cross(a, b, p) >= 0 && cross(b, c, p) >= 0 && cross(c, a, p) >= 0
    }

    /// Ear-clipping triangulation of a simple polygon; returns index triples into `poly` (either winding).
    static func triangulate(_ poly: [V2]) -> [[Int]] {
        var idx: [Int] = Array(0..<poly.count)
        if !(area(poly) > 0) { idx.reverse() }
        var out: [[Int]] = []
        var guardCount: Int = 0
        while idx.count > 3 && guardCount < 10_000 {
            guardCount += 1
            var clipped: Bool = false
            for i in 0..<idx.count {
                let ia: Int = idx[(i + idx.count - 1) % idx.count]
                let ib: Int = idx[i]
                let ic: Int = idx[(i + 1) % idx.count]
                let a: V2 = poly[ia]
                let b: V2 = poly[ib]
                let c: V2 = poly[ic]
                if cross(a, b, c) <= 1e-6 { continue }
                var blocked: Bool = false
                for j in idx where j != ia && j != ib && j != ic {
                    if inside(poly[j], a, b, c) {
                        blocked = true
                        break
                    }
                }
                if blocked { continue }
                out.append([ia, ib, ic])
                idx.remove(at: i)
                clipped = true
                break
            }
            if !clipped {
                // Degenerate input: drop the strictest test rather than loop forever.
                out.append([idx[idx.count - 1], idx[0], idx[1]])
                idx.remove(at: 0)
            }
        }
        if idx.count == 3 { out.append([idx[0], idx[1], idx[2]]) }
        return out
    }

    /// A flat triangle with its normal from the geometry, turned to face `h`. Degenerate triangles are dropped.
    static func flatTri(_ mb: MeshBuilder, _ s: Surf, _ a: V3, _ b: V3, _ c: V3, _ uva: V2, _ uvb: V2, _ uvc: V2, _ h: V3) {
        let u: V3 = b - a
        let v: V3 = c - a
        var n: V3 = V3(u.y * v.z - u.z * v.y, u.z * v.x - u.x * v.z, u.x * v.y - u.y * v.x)
        let len: Float = (n.x * n.x + n.y * n.y + n.z * n.z).squareRoot()
        if len < 1e-7 { return }
        n = n / len
        let flip: Bool = (n.x * h.x + n.y * h.y + n.z * h.z) < 0
        if flip { n = -n }
        let ia: Int = mb.vertex(a, n, uva, s)
        let ib: Int = mb.vertex(b, n, uvb, s)
        let ic: Int = mb.vertex(c, n, uvc, s)
        if flip { mb.tri(ia, ic, ib) } else { mb.tri(ia, ib, ic) }
    }

    /// The extruded side profile of ps1.html: a polygon (in its pixel space) pushed out along z by `depth`, every vertex
    /// above `taperY` pulled towards the middle by `taper` so the roof is narrower. UVs project the profile from the
    /// side. Normals are flat, taken after the taper.
    static func extrude(_ mb: MeshBuilder, _ px: [V2], depth: Float, taper: Float, _ s: Surf, taperY: Float = 0.95, dy: Float = 0) {
        var p: [V2] = profile(px)
        if area(p) < 0 { p.reverse() }
        let half: Float = depth / 2
        func at(_ i: Int, _ z: Float) -> V3 {
            let q: V2 = p[i]
            return V3(q.x, q.y + dy, q.y > taperY ? z * taper : z)
        }
        func uv(_ i: Int) -> V2 {
            return V2(p[i].x / 7 + 0.5, p[i].y / 2)
        }
        for t in triangulate(p) {
            let i: Int = t[0]
            let j: Int = t[1]
            let k: Int = t[2]
            flatTri(mb, s, at(i, half), at(j, half), at(k, half), uv(i), uv(j), uv(k), V3(0, 0, 1))
            flatTri(mb, s, at(i, -half), at(j, -half), at(k, -half), uv(i), uv(j), uv(k), V3(0, 0, -1))
        }
        for i in 0..<p.count {
            let j: Int = (i + 1) % p.count
            let ex: Float = p[j].x - p[i].x
            let ey: Float = p[j].y - p[i].y
            // The outward normal of a counter-clockwise edge is (dy, -dx).
            let h: V3 = V3(ey, -ex, 0)
            let a0: V3 = at(i, half)
            let b0: V3 = at(j, half)
            let a1: V3 = at(i, -half)
            let b1: V3 = at(j, -half)
            flatTri(mb, s, a0, b0, b1, uv(i), uv(j), uv(j), h)
            flatTri(mb, s, a0, b1, a1, uv(i), uv(j), uv(i), h)
        }
    }

    /// A box centred on (cx, cy, cz), optionally turned `rotZ` radians about its own centre. Flat faces, UVs 0...1.
    static func box(_ mb: MeshBuilder, _ cx: Float, _ cy: Float, _ cz: Float, _ sx: Float, _ sy: Float, _ sz: Float, _ s: Surf, rotZ: Float = 0) {
        let from: Int = mb.vertexCount
        let x: Float = sx / 2
        let y: Float = sy / 2
        let z: Float = sz / 2
        func face(_ n: V3, _ c: [Float]) {
            var ids: [Int] = []
            for k in 0..<4 {
                let uv: V2 = V2((k == 1 || k == 2) ? 1 : 0, k >= 2 ? 1 : 0)
                ids.append(mb.vertex(V3(c[k * 3], c[k * 3 + 1], c[k * 3 + 2]), n, uv, s))
            }
            mb.tri(ids[0], ids[1], ids[2])
            mb.tri(ids[0], ids[2], ids[3])
        }
        face(V3(0, 0, 1), [-x, -y, z, x, -y, z, x, y, z, -x, y, z])
        face(V3(0, 0, -1), [x, -y, -z, -x, -y, -z, -x, y, -z, x, y, -z])
        face(V3(1, 0, 0), [x, -y, z, x, -y, -z, x, y, -z, x, y, z])
        face(V3(-1, 0, 0), [-x, -y, -z, -x, -y, z, -x, y, z, -x, y, -z])
        face(V3(0, 1, 0), [-x, y, z, x, y, z, x, y, -z, -x, y, -z])
        face(V3(0, -1, 0), [-x, -y, -z, x, -y, -z, x, -y, z, -x, -y, z])
        mb.xform(from: from, rotZ: rotZ, tx: cx, ty: cy, tz: cz)
    }

    /// A `segments`-sided wheel with its axle along z at (cx, cy, cz). Smooth normals round the tyre, flat normals and
    /// a circular UV map on the two faces. `rotY` turns the axle (for a spare wheel on a tailgate).
    static func wheel(_ mb: MeshBuilder, _ cx: Float, _ cy: Float, _ cz: Float, radius: Float, width: Float, _ tyre: Surf, _ rim: Surf, segments: Int = 8, rotY: Float = 0) {
        let from: Int = mb.vertexCount
        let hw: Float = width / 2
        let seg: Float = Float(segments)
        for i in 0..<segments {
            let a0: Float = Float(i) * 2 * Float.pi / seg
            let a1: Float = Float(i + 1) * 2 * Float.pi / seg
            let c0: Float = cosf(a0)
            let s0: Float = sinf(a0)
            let c1: Float = cosf(a1)
            let s1: Float = sinf(a1)
            let u0: Float = Float(i) / seg
            let u1: Float = Float(i + 1) / seg
            let v00: Int = mb.vertex(V3(radius * c0, radius * s0, hw), V3(c0, s0, 0), V2(u0, 1), tyre)
            let v10: Int = mb.vertex(V3(radius * c1, radius * s1, hw), V3(c1, s1, 0), V2(u1, 1), tyre)
            let v01: Int = mb.vertex(V3(radius * c0, radius * s0, -hw), V3(c0, s0, 0), V2(u0, 0), tyre)
            let v11: Int = mb.vertex(V3(radius * c1, radius * s1, -hw), V3(c1, s1, 0), V2(u1, 0), tyre)
            mb.tri(v00, v10, v11)
            mb.tri(v00, v11, v01)
        }
        for side in [1, -1] {
            let z: Float = hw * Float(side)
            let sideF: Float = Float(side)
            let centre: Int = mb.vertex(V3(0, 0, z), V3(0, 0, sideF), V2(0.5, 0.5), rim)
            var ring: [Int] = []
            for i in 0..<segments {
                let a: Float = Float(i) * 2 * Float.pi / seg
                ring.append(mb.vertex(V3(radius * cosf(a), radius * sinf(a), z), V3(0, 0, sideF), V2(cosf(a) * 0.5 + 0.5, sinf(a) * 0.5 + 0.5), rim))
            }
            for i in 0..<segments {
                mb.tri(centre, ring[i], ring[(i + 1) % segments])
            }
        }
        mb.xform(from: from, rotY: rotY, tx: cx, ty: cy, tz: cz)
    }

    /// A flat fan on the XZ plane at height `y`; UVs are the circle mapped to 0...uvScale.
    static func disc(_ mb: MeshBuilder, y: Float, rx: Float, rz: Float, segments: Int, _ s: Surf, uvScale: Float = 1) {
        let centre: Int = mb.vertex(V3(0, y, 0), V3(0, 1, 0), V2(0.5 * uvScale, 0.5 * uvScale), s)
        var ring: [Int] = []
        let seg: Float = Float(segments)
        for i in 0..<segments {
            let a: Float = Float(i) * 2 * Float.pi / seg
            ring.append(mb.vertex(V3(rx * cosf(a), y, rz * sinf(a)), V3(0, 1, 0),
                                  V2((cosf(a) * 0.5 + 0.5) * uvScale, (sinf(a) * 0.5 + 0.5) * uvScale), s))
        }
        for i in 0..<segments {
            mb.tri(centre, ring[(i + 1) % segments], ring[i])
        }
    }
}

enum Ps1Models {
    private static func pts(_ v: [Float]) -> [V2] {
        var out: [V2] = []
        var i: Int = 0
        while i + 1 < v.count {
            out.append(V2(v[i], v[i + 1]))
            i += 2
        }
        return out
    }

    // Profiles in ps1.html's pixel space.
    private static let carBody: [V2] = pts([8, 48, 9, 38, 12, 34, 29, 30, 47, 18.5, 60, 14, 72, 13.5, 96, 13.5, 110, 16.5, 116, 21.5, 129, 30, 139, 33, 142, 40.5, 142, 48])
    private static let carGlass: [V2] = pts([42, 29.6, 51, 19.6, 72, 15.6, 95, 15.6, 106, 19.5, 113, 29.2])

    private static let uteCab: [V2] = pts([62, 48, 62, 20, 66, 14.5, 74, 13, 96, 13, 106, 17, 116, 28, 138, 31, 147, 35, 150, 41, 150, 48])
    private static let uteGlass: [V2] = pts([66, 22, 69, 16.5, 75, 15, 96, 15, 104, 18.5, 111, 26])

    private static let fwdBody: [V2] = pts([10, 48, 10, 14, 14, 9, 24, 7, 104, 7, 112, 12, 122, 24, 145, 27, 152, 31, 154, 40, 154, 48])
    private static let fwdPaneA: [V2] = pts([15, 14, 18, 10.5, 54, 10.5, 54, 22, 15, 22])
    private static let fwdPaneB: [V2] = pts([60, 10.5, 101, 10.5, 110, 16, 116, 22, 60, 22])

    // Motorcycle shapes, directly in model units (px coordinates 75 + 20x, 48 - 20y).
    private static func unitsToPx(_ v: [Float]) -> [V2] {
        var out: [V2] = []
        var i: Int = 0
        while i + 1 < v.count {
            out.append(V2(75 + v[i] * 20, 48 - v[i + 1] * 20))
            i += 2
        }
        return out
    }

    private static let bikeTank: [V2] = unitsToPx([-0.5, 0.78, -0.4, 1.1, 0.2, 1.26, 0.85, 1.2, 1.0, 1.0, 0.85, 0.82])
    private static let bikeTail: [V2] = unitsToPx([-2.05, 0.86, -1.95, 1.12, -1.2, 1.2, -0.5, 1.1, -0.5, 0.84])
    private static let bikeSeat: [V2] = unitsToPx([-1.5, 1.2, -0.5, 1.12, -0.45, 0.98, -1.5, 1.0])

    static func frame(_ kind: Ps1Kind) -> Ps1Frame {
        switch kind {
        case .car: return Ps1Frame(fitRadius: 3.6, groundY: -0.53, groundRadius: 4.6, lookY: 0.35)
        case .ute: return Ps1Frame(fitRadius: 3.9, groundY: -0.58, groundRadius: 5.0, lookY: 0.4)
        case .fourwd: return Ps1Frame(fitRadius: 3.9, groundY: -0.68, groundRadius: 5.0, lookY: 0.75)
        case .motorcycle: return Ps1Frame(fitRadius: 2.7, groundY: -0.62, groundRadius: 3.6, lookY: 0.35)
        }
    }

    /// Body height change per millimetre of ride height.
    private static let mm: Float = 0.0016

    private static let tyre: Surf = Surf.srgb(0x16181C)
    private static let glass: Surf = Surf.srgb(0x0B1622)
    private static let dark: Surf = Surf.srgb(0x0A0D12)
    private static let trim: Surf = Surf.srgb(0x4A515C)
    private static let head: Surf = Surf.srgb(0xDFF9FF, flag: 2)
    private static let tail: Surf = Surf.srgb(0xFF3D5A, flag: 2)
    private static let ground: Surf = Surf.srgb(0xFFFFFF, tex: 3)
    private static let shadow: Surf = Surf.srgb(0x05080D)

    static func build(_ spec: VehicleBuild) -> Ps1Mesh {
        let mb: MeshBuilder = MeshBuilder()
        let f: Ps1Frame = frame(spec.kind)
        Ps1Geo.disc(mb, y: f.groundY, rx: f.groundRadius, rz: f.groundRadius, segments: 12, ground, uvScale: 6)
        let groundIndices: Int = mb.indexCount

        let wheel: Ps1Wheel = spec.wheel
        let paint: Surf = Surf.srgb(spec.paint, tex: 1, flag: 1)
        let rim: Surf = Surf.srgb(wheel.rgb, tex: 2)
        let dy: Float = Float(spec.rideHeightMm) * mm
        switch spec.kind {
        case .car: car(mb, paint, rim, dy)
        case .ute: ute(mb, paint, rim, dy)
        case .fourwd: fourwd(mb, paint, rim, dy)
        case .motorcycle: bike(mb, paint, rim, dy)
        }
        // The shadow blob under the vehicle, a hair above the ground.
        let sx: Float = spec.kind == .motorcycle ? 2.5 : 3.4
        let sz: Float = spec.kind == .motorcycle ? 0.75 : 1.45
        Ps1Geo.disc(mb, y: f.groundY + 0.012, rx: sx, rz: sz, segments: 10, shadow)
        return mb.build(groundIndexCount: groundIndices)
    }

    private static func car(_ mb: MeshBuilder, _ paint: Surf, _ rim: Surf, _ dy: Float) {
        Ps1Geo.extrude(mb, carBody, depth: 3.1, taper: 0.78, paint, dy: dy)
        Ps1Geo.extrude(mb, carGlass, depth: 3.18, taper: 0.8, glass, dy: dy + 0.01)
        for z: Float in [0.95, -0.95] { Ps1Geo.box(mb, 3.32, 0.62 + dy, z, 0.12, 0.16, 0.7, head) }
        for z: Float in [1.0, -1.0] { Ps1Geo.box(mb, -3.36, 0.62 + dy, z, 0.1, 0.14, 0.6, tail) }
        Ps1Geo.box(mb, 3.37, 0.32 + dy, 0, 0.06, 0.28, 1.5, dark)
        for x: Float in [-1.9, 2.05] {
            for z: Float in [1.38, -1.38] { Ps1Geo.wheel(mb, x, 0, z, radius: 0.53, width: 0.42, tyre, rim) }
        }
    }

    private static func ute(_ mb: MeshBuilder, _ paint: Surf, _ rim: Surf, _ dy: Float) {
        Ps1Geo.extrude(mb, uteCab, depth: 3.1, taper: 0.8, paint, dy: dy)
        Ps1Geo.extrude(mb, uteGlass, depth: 3.18, taper: 0.82, glass, dy: dy + 0.01)
        // The tray: chassis rail, dark liner, two side walls and a tailgate.
        Ps1Geo.box(mb, -1.7, 0.25 + dy, 0, 3.4, 0.5, 3.0, trim)
        Ps1Geo.box(mb, -1.7, 0.56 + dy, 0, 3.4, 0.12, 2.9, dark)
        for z: Float in [1.46, -1.46] { Ps1Geo.box(mb, -1.7, 0.9 + dy, z, 3.4, 0.58, 0.14, paint) }
        Ps1Geo.box(mb, -3.34, 0.9 + dy, 0, 0.14, 0.58, 3.0, paint)
        for z: Float in [1.0, -1.0] { Ps1Geo.box(mb, 3.78, 0.62 + dy, z, 0.12, 0.16, 0.6, head) }
        for z: Float in [1.3, -1.3] { Ps1Geo.box(mb, -3.42, 0.9 + dy, z, 0.1, 0.16, 0.35, tail) }
        Ps1Geo.box(mb, 3.78, 0.32 + dy, 0, 0.06, 0.28, 1.4, dark)
        for x: Float in [-2.25, 2.4] {
            for z: Float in [1.4, -1.4] { Ps1Geo.wheel(mb, x, 0, z, radius: 0.58, width: 0.46, tyre, rim) }
        }
    }

    private static func fourwd(_ mb: MeshBuilder, _ paint: Surf, _ rim: Surf, _ dy: Float) {
        let lift: Float = 0.3 + dy
        Ps1Geo.extrude(mb, fwdBody, depth: 3.4, taper: 0.88, paint, dy: lift)
        Ps1Geo.extrude(mb, fwdPaneA, depth: 3.48, taper: 0.9, glass, dy: lift + 0.01)
        Ps1Geo.extrude(mb, fwdPaneB, depth: 3.48, taper: 0.9, glass, dy: lift + 0.01)
        // Underbody between the lifted body and the axles.
        Ps1Geo.box(mb, 0.1, 0.16 + dy, 0, 6.9, 0.42, 2.9, trim)
        // Roof rack, bull bar and a spare on the tailgate.
        Ps1Geo.box(mb, -0.2, 2.5 + dy, 0, 3.8, 0.09, 2.3, dark)
        Ps1Geo.box(mb, 4.0, 0.62 + lift, 0, 0.16, 0.5, 2.6, dark)
        for z: Float in [1.2, -1.2] { Ps1Geo.box(mb, 3.95, 1.2 + lift, z, 0.12, 0.28, 0.5, head) }
        for z: Float in [1.25, -1.25] { Ps1Geo.box(mb, -3.3, 1.2 + lift, z, 0.1, 0.26, 0.4, tail) }
        Ps1Geo.wheel(mb, -3.55, 1.15 + lift, 0, radius: 0.62, width: 0.3, tyre, rim, rotY: Float.pi / 2)
        for x: Float in [-2.2, 2.3] {
            for z: Float in [1.64, -1.64] { Ps1Geo.wheel(mb, x, 0, z, radius: 0.68, width: 0.56, tyre, rim) }
        }
    }

    private static func bike(_ mb: MeshBuilder, _ paint: Surf, _ rim: Surf, _ dy: Float) {
        let silver: Surf = Surf.srgb(0xB8BCC2)
        // Tank and tail in paint, seat dark.
        Ps1Geo.extrude(mb, bikeTank, depth: 0.62, taper: 0.8, paint, taperY: 0.95, dy: dy)
        Ps1Geo.extrude(mb, bikeTail, depth: 0.46, taper: 0.8, paint, taperY: 1.0, dy: dy)
        Ps1Geo.extrude(mb, bikeSeat, depth: 0.5, taper: 0.9, Surf.srgb(0x15171C), taperY: 5, dy: dy)
        // Engine, swing arm and exhaust.
        Ps1Geo.box(mb, 0.1, 0.5 + dy, 0, 1.2, 0.7, 0.56, trim)
        Ps1Geo.box(mb, -0.2, 0.9 + dy, 0, 0.7, 0.2, 0.36, trim)
        for z: Float in [0.25, -0.25] { Ps1Geo.box(mb, -0.85, 0.34 + dy * 0.5, z, 1.9, 0.1, 0.08, trim, rotZ: 0.12) }
        for z: Float in [0.34, -0.34] { Ps1Geo.box(mb, -0.9, 0.3 + dy, z, 1.7, 0.15, 0.14, silver, rotZ: 0.08) }
        // Forks from the hub up to the bars, leaning back.
        for z: Float in [0.2, -0.2] { Ps1Geo.box(mb, 1.3, 0.6 + dy * 0.5, z, 0.1, 1.3, 0.09, silver, rotZ: 0.4) }
        Ps1Geo.box(mb, 1.02, 1.28 + dy, 0, 0.1, 0.1, 1.2, dark)
        Ps1Geo.box(mb, 1.22, 1.0 + dy, 0, 0.14, 0.28, 0.28, head)
        Ps1Geo.box(mb, -2.1, 1.0 + dy, 0, 0.1, 0.14, 0.3, tail)
        Ps1Geo.wheel(mb, -1.5, 0, 0, radius: 0.62, width: 0.24, tyre, rim)
        Ps1Geo.wheel(mb, 1.55, 0, 0, radius: 0.62, width: 0.24, tyre, rim)
    }
}
