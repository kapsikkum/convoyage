import Foundation

/// Pure crash heuristic, no CoreMotion or CoreLocation inside so it can be unit-tested with made-up timestamps.
///
/// Rule: a user-acceleration spike above `impactG` while the vehicle was recently moving, followed by
/// `stationaryHoldS` seconds of near-stationary GPS speed, raises one trigger. Anything else (a dropped phone while
/// parked, a pothole at speed that is followed by more driving) does not.
///
/// Times are seconds on any monotonic clock (the monitor uses system uptime).
struct CrashDetector {
    struct Config {
        /// Spike size, in g, of the user acceleration (gravity removed).
        var impactG: Double = 4.0
        /// At or below this ground speed (m/s) the vehicle counts as stopped.
        var stationarySpeedMps: Double = 1.5
        /// How long the speed must stay stationary after the impact.
        var stationaryHoldS: Double = 10.0
        /// Speed (m/s) that counts as "was moving" (about 11 km/h).
        var movingSpeedMps: Double = 3.0
        /// A spike only counts if the vehicle was moving this recently.
        var movingMemoryS: Double = 15.0
        /// Give up waiting for the vehicle to stop this long after the impact.
        var impactWindowS: Double = 60.0
    }

    var config: Config

    private(set) var impactAt: Double?
    private var lastMovingAt: Double?
    private var stillSince: Double?

    init(config: Config = Config()) {
        self.config = config
    }

    /// True while an impact is being checked against the GPS speed.
    var isArmed: Bool { return impactAt != nil }

    mutating func reset() {
        impactAt = nil
        lastMovingAt = nil
        stillSince = nil
    }

    /// One acceleration magnitude sample, in g.
    mutating func addAcceleration(g: Double, at t: Double) {
        guard g > config.impactG else { return }
        guard let moving = lastMovingAt, t - moving <= config.movingMemoryS else { return }
        impactAt = t
        stillSince = nil
    }

    /// One ground-speed reading (m/s, nil when the GPS has none). Returns true exactly when the crash triggers.
    mutating func addSpeed(_ speed: Double?, at t: Double) -> Bool {
        if let s = speed, s >= config.movingSpeedMps {
            lastMovingAt = t
        }
        return evaluate(speed: speed, at: t)
    }

    /// Re-checks the clock without a new speed reading.
    mutating func tick(at t: Double) -> Bool {
        return evaluate(speed: nil, at: t)
    }

    private mutating func evaluate(speed: Double?, at t: Double) -> Bool {
        guard let impact = impactAt else { return false }
        if t - impact > config.impactWindowS {
            impactAt = nil
            stillSince = nil
            return false
        }
        if let s = speed {
            if s <= config.stationarySpeedMps {
                if stillSince == nil { stillSince = max(t, impact) }
            } else {
                stillSince = nil
            }
        }
        if let since = stillSince, t - since >= config.stationaryHoldS {
            impactAt = nil
            stillSince = nil
            return true
        }
        return false
    }
}
