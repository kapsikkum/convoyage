import Foundation
import CoreLocation

/// The way back to a route after leaving it: the nearest point on the line, preferring the part still ahead. Shown as a
/// dashed line from the car, a hint of which way to head rather than a turn-by-turn order. Pure, so it is tested.
enum Rejoin {
    /// Further than this from the line counts as off track.
    static let offTrackM: Double = 40

    /// Where to rejoin `line` from `me`, or nil when on it (within `offTrackM`) or the line is unusable. `fromIndex`
    /// (the segment we were last on) keeps the target ahead of us on a route that doubles back.
    static func target(line: [CLLocationCoordinate2D], me: CLLocationCoordinate2D, fromIndex: Int = 0,
                       offTrackM: Double = Rejoin.offTrackM) -> CLLocationCoordinate2D? {
        if line.count < 2 { return nil }
        // Local equirectangular metres around `me`, the same approximation the route snapping uses.
        let k: Double = cos(me.latitude * Double.pi / 180) * mPerDeg
        var best: CLLocationCoordinate2D? = nil
        var bestD: Double = Double.greatestFiniteMagnitude
        let start: Int = max(0, min(fromIndex, line.count - 2))
        for i in start..<(line.count - 1) {
            let a: CLLocationCoordinate2D = line[i]
            let b: CLLocationCoordinate2D = line[i + 1]
            let ax: Double = (a.longitude - me.longitude) * k
            let ay: Double = (a.latitude - me.latitude) * mPerDeg
            let bx: Double = (b.longitude - me.longitude) * k
            let by: Double = (b.latitude - me.latitude) * mPerDeg
            let dx: Double = bx - ax
            let dy: Double = by - ay
            let len2: Double = dx * dx + dy * dy
            var t: Double = 0
            if len2 >= 1e-9 {
                t = max(0, min(1, -(ax * dx + ay * dy) / len2))
            }
            let gx: Double = ax + t * dx
            let gy: Double = ay + t * dy
            let d: Double = (gx * gx + gy * gy).squareRoot()
            if d < bestD {
                bestD = d
                best = CLLocationCoordinate2D(latitude: a.latitude + (b.latitude - a.latitude) * t,
                                              longitude: a.longitude + (b.longitude - a.longitude) * t)
            }
        }
        return bestD > offTrackM ? best : nil
    }

    private static let mPerDeg: Double = 111_320
}
