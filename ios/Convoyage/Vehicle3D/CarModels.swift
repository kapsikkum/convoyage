import Foundation

/// A vehicle body modelled in Blender (design-tools/cars/build_car.py), shipped as cars/<id>.glb: one primitive per
/// named material, metres, x forward, y up, z right, ground at y = 0, centred on its length. Port of Ps1Glb.kt.
struct Ps1CarModel {
    struct Part {
        var material: String
        var positions: [Float]
        var normals: [Float]
        var indices: [Int]
    }

    var parts: [Part]
    /// Overall length in metres.
    var len: Float
    /// Metres back from the nose to the front axle, wheelbase, tyre diameter and width.
    var fo: Float
    var wb: Float
    var tyre: Float
    var tyreW: Float
    /// Wheel centre to the middle line, front then rear.
    var halfTrack: [Float]
    /// Roof height in metres (from the vertices).
    var height: Float
}

enum Ps1Glb {
    static func parse(_ data: Data) -> Ps1CarModel? {
        let b: [UInt8] = [UInt8](data)
        func u32(_ o: Int) -> UInt32 {
            return UInt32(b[o]) | UInt32(b[o + 1]) << 8 | UInt32(b[o + 2]) << 16 | UInt32(b[o + 3]) << 24
        }
        guard b.count > 28, u32(0) == 0x46546C67, u32(16) == 0x4E4F534A else { return nil }
        let jsonLen: Int = Int(u32(12))
        guard let doc = (try? JSONSerialization.jsonObject(with: Data(b[20..<(20 + jsonLen)]))) as? [String: Any],
              let accessors = doc["accessors"] as? [[String: Any]],
              let views = doc["bufferViews"] as? [[String: Any]],
              let meshes = doc["meshes"] as? [[String: Any]] else { return nil }
        let bin: Int = 20 + jsonLen + 8
        let materials: [String] = ((doc["materials"] as? [[String: Any]]) ?? []).map { (m: [String: Any]) -> String in
            return (m["name"] as? String) ?? "paint"
        }

        func view(_ acc: [String: Any]) -> (Int, Int) {
            let v: [String: Any] = views[(acc["bufferView"] as? Int) ?? 0]
            let off: Int = bin + ((v["byteOffset"] as? Int) ?? 0) + ((acc["byteOffset"] as? Int) ?? 0)
            return (off, (acc["count"] as? Int) ?? 0)
        }
        func floats(_ i: Int, _ comps: Int) -> [Float] {
            let (off, count) = view(accessors[i])
            return (0..<(count * comps)).map { (k: Int) -> Float in return Float(bitPattern: u32(off + k * 4)) }
        }
        func ints(_ i: Int) -> [Int] {
            let acc: [String: Any] = accessors[i]
            let (off, count) = view(acc)
            switch (acc["componentType"] as? Int) ?? 0 {
            case 5121: return (0..<count).map { (k: Int) -> Int in return Int(b[off + k]) }
            case 5123: return (0..<count).map { (k: Int) -> Int in return Int(b[off + k * 2]) | Int(b[off + k * 2 + 1]) << 8 }
            default: return (0..<count).map { (k: Int) -> Int in return Int(u32(off + k * 4)) }
            }
        }

        var parts: [Ps1CarModel.Part] = []
        var top: Float = 0
        for mesh in meshes {
            for p in (mesh["primitives"] as? [[String: Any]]) ?? [] {
                guard let attrs = p["attributes"] as? [String: Any], let pi = attrs["POSITION"] as? Int else { continue }
                let pos: [Float] = floats(pi, 3)
                let nrm: [Float] = (attrs["NORMAL"] as? Int).map { floats($0, 3) } ?? [Float](repeating: 0, count: pos.count)
                let idx: [Int] = (p["indices"] as? Int).map { ints($0) } ?? Array(0..<(pos.count / 3))
                var mat: String = (p["material"] as? Int).flatMap { $0 < materials.count ? materials[$0] : nil } ?? "paint"
                if let dot = mat.firstIndex(of: ".") { mat = String(mat[..<dot]) }
                var k: Int = 1
                while k < pos.count { top = max(top, pos[k]); k += 3 }
                parts.append(Ps1CarModel.Part(material: mat, positions: pos, normals: nrm, indices: idx))
            }
        }
        var extras: [String: Any] = [:]
        for n in (doc["nodes"] as? [[String: Any]]) ?? [] {
            if let e = n["extras"] as? [String: Any], let s = e["convoyage"] as? String,
               let o = (try? JSONSerialization.jsonObject(with: Data(s.utf8))) as? [String: Any] {
                extras = o
                break
            }
        }
        func f(_ k: String, _ d: Float) -> Float {
            if let n = extras[k] as? NSNumber { return n.floatValue }
            if let s = extras[k] as? String, let v = Float(s) { return v }
            return d
        }
        let ht: [Float] = ((extras["halfTrack"] as? [NSNumber]) ?? []).map { (n: NSNumber) -> Float in return n.floatValue }
        return Ps1CarModel(parts: parts, len: f("len", 4.5), fo: f("fo", 0.9), wb: f("wb", 2.7), tyre: f("tyre", 0.65),
                           tyreW: f("tyreW", 0.235), halfTrack: ht.count >= 2 ? ht : [0.75, 0.75], height: max(top, 1.2))
    }
}

/// The Blender models in the bundle (cars/<id>.glb), parsed once.
enum Ps1CarModels {
    private static let lock: NSLock = NSLock()
    nonisolated(unsafe) private static var cache: [String: Ps1CarModel?] = [:]

    static func of(_ id: String) -> Ps1CarModel? {
        lock.lock()
        defer { lock.unlock() }
        if let hit = cache[id] { return hit }
        var m: Ps1CarModel? = nil
        if let url = Bundle.main.url(forResource: id, withExtension: "glb", subdirectory: "cars"),
           let data = try? Data(contentsOf: url) {
            m = Ps1Glb.parse(data)
        }
        cache[id] = m
        return m
    }

    /// The modelled cars and how people type them: (id, make, aliases longest first, years).
    private static let known: [(id: String, make: String, aliases: [String], years: ClosedRange<Int>)] = [
        ("hyundai-i30n-cn7", "hyundai", ["i30 sedan n", "i30 n sedan", "elantra n"], 2022...2100),
        ("hyundai-i30-cn7", "hyundai", ["i30 sedan", "elantra"], 2020...2100),
        ("hyundai-i30n-pd", "hyundai", ["i30 n", "i30n"], 2018...2100),
        ("hyundai-i30-pd", "hyundai", ["i30"], 2017...2100),
        ("ford-focus-rs-mk3", "ford", ["focus rs mk3", "rs mk3", "focus rs"], 2016...2018),
        ("ford-focus-rs-mk2", "ford", ["focus rs mk2", "rs mk2", "focus rs"], 2009...2011),
        ("ford-focus-st-mk4", "ford", ["focus st"], 2020...2024),
        ("ford-focus-mk4", "ford", ["focus"], 2019...2024),
        ("nissan-skyline-r33", "nissan", ["skyline r33", "r33", "skyline gtr", "skyline gt r", "skyline"], 1995...1998)
    ]

    /// Lower case, single spaces, punctuation gone.
    private static func norm(_ s: String) -> String {
        let mapped: String = String(s.lowercased().map { (c: Character) -> Character in return (c.isLetter || c.isNumber) ? c : " " })
        return mapped.split(separator: " ").joined(separator: " ")
    }

    /// A modelled car for what the owner typed, or nil. A shape id the server already matched wins.
    static func match(shape: String?, make: String?, model: String?, year: Int?) -> String? {
        if let s = shape, known.contains(where: { $0.id == s }) { return s }
        let mk: String = norm(make ?? "")
        let md: String = " " + norm(model ?? "") + " "
        var best: (id: String, score: Int)? = nil
        for k in known where mk.contains(k.make) || md.contains(" " + k.make + " ") {
            for a in k.aliases where md.contains(" " + a + " ") {
                var score: Int = a.count * 10
                if let y = year { score += k.years.contains(y) ? 5 : -20 }
                // Ties keep the first (newer) generation.
                if best == nil || score > best!.score { best = (k.id, score) }
            }
        }
        return best?.id
    }
}
