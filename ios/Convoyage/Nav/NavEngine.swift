import Foundation

// Pure navigation logic: no UIKit, no CoreLocation, no clocks of its own. Everything here takes plain numbers and
// dates so it can be reasoned about (and tested) without a device.

struct GeoPoint: Equatable {
    var lat: Double
    var lng: Double
}

enum Geo {
    static let earthRadiusM: Double = 6_371_000

    /// Initial bearing from `a` to `b`, degrees clockwise from north (0..<360).
    static func bearing(_ a: GeoPoint, _ b: GeoPoint) -> Double {
        let p1: Double = a.lat * Double.pi / 180
        let p2: Double = b.lat * Double.pi / 180
        let dl: Double = (b.lng - a.lng) * Double.pi / 180
        let y: Double = sin(dl) * cos(p2)
        let x: Double = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        let deg: Double = atan2(y, x) * 180 / Double.pi
        return (deg + 360).truncatingRemainder(dividingBy: 360)
    }

    /// Great-circle distance, metres.
    static func distance(_ a: GeoPoint, _ b: GeoPoint) -> Double {
        let p1: Double = a.lat * Double.pi / 180
        let p2: Double = b.lat * Double.pi / 180
        let dp: Double = (b.lat - a.lat) * Double.pi / 180
        let dl: Double = (b.lng - a.lng) * Double.pi / 180
        let h: Double = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * earthRadiusM * asin(min(1, sqrt(h)))
    }
}

// MARK: - Route line

/// A polyline with cumulative distances, so a position can be snapped to it and measured along it.
struct RouteLine {
    let points: [GeoPoint]
    /// `cumulative[i]` is the distance in metres from the start to `points[i]`.
    let cumulative: [Double]

    init(points: [GeoPoint]) {
        self.points = points
        var acc: [Double] = []
        acc.reserveCapacity(points.count)
        var total: Double = 0
        for (i, p) in points.enumerated() {
            if i > 0 { total += Geo.distance(points[i - 1], p) }
            acc.append(total)
        }
        self.cumulative = acc
    }

    var length: Double { return cumulative.last ?? 0 }

    struct Snap: Equatable {
        /// Metres from the start of the route to the snapped point.
        var along: Double
        /// Metres between the position and the route.
        var crossTrack: Double
        var segment: Int
        var point: GeoPoint
    }

    /// The closest point on the route to `p`, looking only at the part of the route between `minAlong` and `maxAlong`.
    func snap(_ p: GeoPoint, minAlong: Double = 0, maxAlong: Double = Double.greatestFiniteMagnitude) -> Snap? {
        if points.count < 2 { return nil }
        let cosLat: Double = cos(p.lat * Double.pi / 180)
        var best: Snap? = nil
        for i in 0..<(points.count - 1) {
            if cumulative[i + 1] < minAlong { continue }
            if cumulative[i] > maxAlong { break }
            let a: GeoPoint = points[i]
            let b: GeoPoint = points[i + 1]
            // Local flat projection with `a` as the origin, in metres.
            let bx: Double = (b.lng - a.lng) * Double.pi / 180 * Geo.earthRadiusM * cosLat
            let by: Double = (b.lat - a.lat) * Double.pi / 180 * Geo.earthRadiusM
            let px: Double = (p.lng - a.lng) * Double.pi / 180 * Geo.earthRadiusM * cosLat
            let py: Double = (p.lat - a.lat) * Double.pi / 180 * Geo.earthRadiusM
            let len2: Double = bx * bx + by * by
            var t: Double = 0
            if len2 > 0 { t = max(0, min(1, (px * bx + py * by) / len2)) }
            let dx: Double = px - t * bx
            let dy: Double = py - t * by
            let cross: Double = (dx * dx + dy * dy).squareRoot()
            if best == nil || cross < best!.crossTrack {
                let segLen: Double = cumulative[i + 1] - cumulative[i]
                let snapped: GeoPoint = GeoPoint(lat: a.lat + (b.lat - a.lat) * t, lng: a.lng + (b.lng - a.lng) * t)
                best = Snap(along: cumulative[i] + segLen * t, crossTrack: cross, segment: i, point: snapped)
            }
        }
        return best
    }

    /// Snap that prefers the part of the route just ahead of where we were (so a road that doubles back, or passes
    /// close to itself, cannot pull the position forward). Falls back to the whole route when that is far off.
    func snap(_ p: GeoPoint, near hint: Double) -> Snap? {
        if let local = snap(p, minAlong: max(0, hint - 250), maxAlong: hint + 3000), local.crossTrack <= 80 {
            return local
        }
        return snap(p)
    }
}

// MARK: - Route with steps

struct NavStep: Equatable {
    var type: String
    var modifier: String?
    var name: String
    var distanceM: Double
    var durationS: Double
    var instruction: String
    var location: GeoPoint?
    /// Metres from the start of the route to where this step's manoeuvre happens.
    var along: Double = 0
}

/// A fetched route ready to follow.
struct NavRoute {
    let line: RouteLine
    let steps: [NavStep]
    let distanceM: Double
    let durationS: Double
    let profile: String

    init(line: RouteLine, steps: [NavStep], distanceM: Double, durationS: Double, profile: String) {
        self.line = line
        self.distanceM = distanceM
        self.durationS = durationS
        self.profile = profile
        // Place every manoeuvre on the line. OSRM's step distances add up to the route's, but the line is measured
        // with haversine, so snap the manoeuvre points instead when they are on the line, else use the running sum.
        var placed: [NavStep] = []
        var running: Double = 0
        var last: Double = 0
        for (i, s) in steps.enumerated() {
            var step: NavStep = s
            var along: Double = running
            if i == 0 {
                along = 0
            } else if let loc = s.location, let snap = line.snap(loc, near: last), snap.crossTrack < 60 {
                along = snap.along
            }
            along = max(along, last)
            step.along = min(along, line.length)
            last = step.along
            running += s.distanceM
            placed.append(step)
        }
        self.steps = placed
    }

    var destination: GeoPoint? { return line.points.last }
}

// MARK: - Progress

struct NavProgress: Equatable {
    var along: Double
    var crossTrack: Double
    var remainingDistance: Double
    var remainingDuration: Double
    /// Index of the next manoeuvre in `steps` (the last step is "arrive").
    var stepIndex: Int
    var distanceToManeuver: Double
    var arrived: Bool
}

/// Follows a position along a `NavRoute`. Progress only moves forward, except after `reset`.
struct RouteTracker {
    let route: NavRoute
    private(set) var along: Double = 0
    private(set) var stepIndex: Int = 1

    static let arriveRadiusM: Double = 25
    /// A manoeuvre counts as done this far past its point.
    static let passedM: Double = 12

    init(route: NavRoute) {
        self.route = route
        self.stepIndex = route.steps.count > 1 ? 1 : 0
    }

    mutating func update(_ p: GeoPoint) -> NavProgress? {
        guard let snap = route.line.snap(p, near: along) else { return nil }
        // Never go backwards by more than GPS noise.
        if snap.along >= along - 30 { along = max(along, snap.along) }
        let steps: [NavStep] = route.steps
        let lastIndex: Int = max(0, steps.count - 1)
        while stepIndex < lastIndex && steps[stepIndex].along + RouteTracker.passedM < along {
            stepIndex += 1
        }
        let remaining: Double = max(0, route.line.length - along)
        let toManeuver: Double = steps.isEmpty ? remaining : max(0, steps[min(stepIndex, lastIndex)].along - along)
        let arrived: Bool = remaining <= RouteTracker.arriveRadiusM
        return NavProgress(along: along,
                           crossTrack: snap.crossTrack,
                           remainingDistance: remaining,
                           remainingDuration: remainingDuration(remaining: remaining),
                           stepIndex: stepIndex,
                           distanceToManeuver: toManeuver,
                           arrived: arrived)
    }

    /// Sum of the durations of the steps still to drive, the current one pro rata.
    private func remainingDuration(remaining: Double) -> Double {
        let steps: [NavStep] = route.steps
        if steps.isEmpty || route.distanceM <= 0 { return 0 }
        var total: Double = 0
        for i in 0..<steps.count {
            let start: Double = steps[i].along
            let end: Double = (i + 1 < steps.count) ? steps[i + 1].along : route.line.length
            if end <= along { continue }
            let span: Double = max(1, end - start)
            let left: Double = min(span, end - max(along, start))
            total += steps[i].durationS * (left / span)
        }
        return total
    }

    mutating func reset() {
        along = 0
        stepIndex = route.steps.count > 1 ? 1 : 0
    }
}

// MARK: - Off-route detection

/// "More than 50 m off the route for more than 8 s". Fixes with a poor accuracy say nothing either way.
struct OffRouteDetector {
    var thresholdM: Double = 50
    var holdS: Double = 8
    var maxAccuracyM: Double = 65
    private var since: Date?

    /// True when it is time to ask for a new route.
    mutating func update(crossTrack: Double, accuracy: Double?, at now: Date) -> Bool {
        if let a = accuracy, a > maxAccuracyM { return false }
        if crossTrack <= thresholdM {
            since = nil
            return false
        }
        if since == nil { since = now }
        if let s = since, now.timeIntervalSince(s) > holdS { return true }
        return false
    }

    mutating func reset() {
        since = nil
    }
}

// MARK: - Voice prompts

enum PromptStage: Int {
    case far
    case near
    case now
}

struct Prompt: Equatable {
    var stepIndex: Int
    var stage: PromptStage
    /// Distance to speak for "far" and "near" (already rounded), metres.
    var meters: Int
}

/// Decides when to speak: about 800 m before a manoeuvre, at 200 m, and at the turn. Each stage once per step.
struct PromptPlanner {
    private var done: Set<String> = []

    static let farM: Double = 800
    static let farMinM: Double = 400
    static let nearM: Double = 200
    static let nowM: Double = 35

    mutating func next(stepIndex: Int, distance: Double) -> Prompt? {
        if distance <= PromptPlanner.nowM {
            if mark(stepIndex, .now) {
                // Anything further out is moot now.
                _ = mark(stepIndex, .near)
                _ = mark(stepIndex, .far)
                return Prompt(stepIndex: stepIndex, stage: .now, meters: 0)
            }
            return nil
        }
        if distance <= PromptPlanner.nearM {
            if mark(stepIndex, .near) {
                _ = mark(stepIndex, .far)
                return Prompt(stepIndex: stepIndex, stage: .near, meters: PromptPlanner.roundMeters(distance))
            }
            return nil
        }
        if distance <= PromptPlanner.farM && distance > PromptPlanner.farMinM {
            if mark(stepIndex, .far) {
                return Prompt(stepIndex: stepIndex, stage: .far, meters: PromptPlanner.roundMeters(distance))
            }
        }
        return nil
    }

    /// True the first time a stage is marked for a step.
    private mutating func mark(_ step: Int, _ stage: PromptStage) -> Bool {
        let key: String = "\(step)/\(stage.rawValue)"
        if done.contains(key) { return false }
        done.insert(key)
        return true
    }

    mutating func reset() {
        done = []
    }

    /// 50 m steps up to 1 km; 100 m steps above that.
    static func roundMeters(_ d: Double) -> Int {
        if d >= 1000 { return Int((d / 100).rounded()) * 100 }
        return max(50, Int((d / 50).rounded()) * 50)
    }
}

// MARK: - Manoeuvre icons and words

enum Maneuver {
    /// SF Symbol for an OSRM manoeuvre type and modifier.
    static func symbol(type: String, modifier: String?) -> String {
        let mod: String = modifier ?? ""
        switch type {
        case "arrive":
            return "flag.checkered"
        case "depart":
            return "arrow.up"
        case "roundabout", "rotary", "roundabout turn", "exit roundabout", "exit rotary":
            return "arrow.triangle.2.circlepath"
        default:
            break
        }
        switch mod {
        case "uturn": return "arrow.uturn.left"
        case "sharp right": return "arrow.turn.down.right"
        case "right": return "arrow.turn.up.right"
        case "slight right": return "arrow.up.right"
        case "straight": return "arrow.up"
        case "slight left": return "arrow.up.left"
        case "left": return "arrow.turn.up.left"
        case "sharp left": return "arrow.turn.down.left"
        default: return "arrow.up"
        }
    }

    /// "turn right", "keep left", "continue straight"... the verb phrase, without the street.
    static func verb(type: String, modifier: String?) -> String {
        let mod: String = modifier ?? "straight"
        let side: String
        switch mod {
        case "uturn": side = "around"
        case "sharp right": side = "sharp right"
        case "slight right": side = "slightly right"
        case "right": side = "right"
        case "sharp left": side = "sharp left"
        case "slight left": side = "slightly left"
        case "left": side = "left"
        default: side = "straight"
        }
        switch type {
        case "arrive":
            return "arrive at your destination"
        case "depart":
            return "head out"
        case "roundabout", "rotary", "roundabout turn":
            return "enter the roundabout"
        case "exit roundabout", "exit rotary":
            return "take the exit"
        case "merge":
            return mod == "straight" ? "merge" : "merge \(side)"
        case "fork":
            if mod.contains("left") { return "keep left" }
            if mod.contains("right") { return "keep right" }
            return "keep straight"
        case "on ramp":
            return mod == "straight" ? "take the ramp" : "take the ramp \(side)"
        case "off ramp":
            return mod == "straight" ? "take the exit" : "take the exit \(side)"
        case "end of road", "turn", "new name", "continue", "notification":
            if mod == "uturn" { return "make a U-turn" }
            if mod == "straight" { return type == "new name" || type == "continue" ? "continue straight" : "go straight" }
            if mod.hasPrefix("slight") { return "bear \(mod.replacingOccurrences(of: "slight ", with: ""))" }
            return "turn \(side)"
        default:
            if mod == "uturn" { return "make a U-turn" }
            return mod == "straight" ? "continue straight" : "turn \(side)"
        }
    }

    /// A short line for the manoeuvre card: "Right onto Spring St".
    static func title(for step: NavStep) -> String {
        if step.type == "arrive" { return step.name.isEmpty ? "Arrive" : "Arrive at \(step.name)" }
        let v: String = verb(type: step.type, modifier: step.modifier)
        let capital: String = v.prefix(1).uppercased() + v.dropFirst()
        if step.name.isEmpty { return capital }
        return "\(capital) onto \(step.name)"
    }

    /// What the voice says: "In 200 metres, turn right onto Spring Street".
    static func spoken(for step: NavStep, stage: PromptStage, meters: Int) -> String {
        let v: String = verb(type: step.type, modifier: step.modifier)
        var phrase: String = v
        if step.type == "arrive" {
            phrase = step.name.isEmpty ? "you will arrive at your destination" : "you will arrive at \(step.name)"
        } else if !step.name.isEmpty {
            phrase += " onto \(step.name)"
        }
        switch stage {
        case .now:
            if step.type == "arrive" { return "You have arrived" }
            return phrase.prefix(1).uppercased() + phrase.dropFirst()
        case .far, .near:
            return "In \(spokenDistance(meters)), \(phrase)"
        }
    }

    static func spokenDistance(_ meters: Int) -> String {
        if meters >= 1000 {
            let km: Double = Double(meters) / 1000
            if meters % 1000 == 0 { return "\(meters / 1000) kilometre" + (meters / 1000 == 1 ? "" : "s") }
            return String(format: "%.1f kilometres", km)
        }
        return "\(meters) metres"
    }
}

// MARK: - Hazards ahead

struct HazardPoint: Equatable {
    var id: String
    var kind: String
    var subkind: String?
    var point: GeoPoint
}

enum HazardAhead {
    static let lookAheadM: Double = 1000
    static let offRouteM: Double = 40

    /// The nearest hazard that lies on the route within `lookAheadM` ahead of `along`.
    static func nearest(_ hazards: [HazardPoint], on line: RouteLine, along: Double) -> (hazard: HazardPoint, ahead: Double)? {
        var best: (hazard: HazardPoint, ahead: Double)? = nil
        for h in hazards {
            guard let s = line.snap(h.point, minAlong: along - 5, maxAlong: along + lookAheadM + 50) else { continue }
            if s.crossTrack > offRouteM { continue }
            let ahead: Double = s.along - along
            if ahead < 0 || ahead > lookAheadM { continue }
            if best == nil || ahead < best!.ahead { best = (hazard: h, ahead: ahead) }
        }
        return best
    }
}

// MARK: - Misc helpers

enum NavFormat {
    /// Arrival time of day for `seconds` from now, in the device's 12/24 hour style.
    static func arrival(after seconds: Double, now: Date = Date()) -> String {
        let f: DateFormatter = DateFormatter()
        f.dateStyle = .none
        f.timeStyle = .short
        return f.string(from: now.addingTimeInterval(seconds))
    }

    /// "12" and "MIN", or "1:05" and "H".
    static func remainingTime(_ seconds: Double) -> (value: String, unit: String) {
        let minutes: Int = max(1, Int((seconds / 60).rounded()))
        if minutes >= 60 {
            return (String(format: "%d:%02d", minutes / 60, minutes % 60), "H")
        }
        return (String(minutes), "MIN")
    }
}
