import Foundation

/// The camera bearing for following, Waze / Google Maps style: heading-up while moving (above ~10 km/h, so a parked
/// phone does not spin), held through short slow patches, north-up only once stopped for a while. Pure, fed one fix at
/// a time with the clock passed in.
struct HeadingUp {
    /// Above this, the camera turns to the direction of travel.
    static let movingMps: Double = 2.8
    /// Stay heading-up this long after the last sign of movement.
    static let holdS: TimeInterval = 8

    /// The last heading we turned to; nil means north-up.
    private(set) var held: Double?

    private var slowSince: Date?

    /// Feed each fix; returns the heading to follow with, or nil for north-up. `heading` is nil when the fix has no
    /// usable course (a negative `CLLocation.course`).
    @discardableResult
    mutating func update(speed: Double, heading: Double?, now: Date) -> Double? {
        if heading != nil && speed > HeadingUp.movingMps {
            held = heading
            slowSince = nil
        } else if held != nil {
            let since: Date = slowSince ?? now
            slowSince = since
            if now.timeIntervalSince(since) > HeadingUp.holdS { held = nil }
        }
        return held
    }
}
