import SwiftUI
import Foundation
import CoreLocation

/// One route point in the road's own frame: metres to the right of the heading, and metres along the polyline.
private struct RoadPoint: Equatable {
    let lateral: Double
    let along: Double
}

/// Projects a lat/lng line into that frame. Pure maths, no drawing.
private enum RoadProjection {
    /// How much of the road the strip shows.
    static let horizonM: Double = 180

    private static let metresPerDegree: Double = 111_320

    /// Haversine length along `line` from its first point, and the sideways offset from the initial heading at every
    /// vertex (local flat earth: a few hundred metres is small enough). Stops at the horizon, keeping the vertex that
    /// crosses it so the road still reaches the top of the panel.
    static func project(_ line: [CLLocationCoordinate2D], headingDeg: Double?) -> [RoadPoint] {
        guard line.count >= 2 else { return [] }
        let o: CLLocationCoordinate2D = line[0]
        let oPoint: GeoPoint = GeoPoint(lat: o.latitude, lng: o.longitude)
        let heading: Double = headingDeg
            ?? Geo.bearing(oPoint, GeoPoint(lat: line[1].latitude, lng: line[1].longitude))
        let h: Double = heading * Double.pi / 180
        let sinH: Double = sin(h)
        let cosH: Double = cos(h)
        let cosLat: Double = cos(o.latitude * Double.pi / 180)
        var out: [RoadPoint] = [RoadPoint(lateral: 0, along: 0)]
        var along: Double = 0
        for i in 1..<line.count {
            let a: CLLocationCoordinate2D = line[i - 1]
            let b: CLLocationCoordinate2D = line[i]
            along += Geo.distance(GeoPoint(lat: a.latitude, lng: a.longitude),
                                  GeoPoint(lat: b.latitude, lng: b.longitude))
            let east: Double = (b.longitude - o.longitude) * cosLat * metresPerDegree
            let north: Double = (b.latitude - o.latitude) * metresPerDegree
            // Rotation into the heading frame: +lateral is right of the heading, +along is ahead.
            out.append(RoadPoint(lateral: east * cosH - north * sinH, along: along))
            if along >= horizonM { break }
        }
        return out
    }
}

/// The next ~180 m of route as a flat wireframe "road ahead": two edges that bend with the road, a dashed centreline, a
/// few cross ticks, and the car at the bottom. Deliberately not a second map: no tiles, no textures, no 3D.
///
/// `ahead` is the remaining line, starting at (or near) where we are; `headingDeg` is where we face, so the first
/// metres sit straight up the strip; `step` only says whether a manoeuvre tick belongs on the road.
struct RoadAheadStrip: View {
    private let projected: [RoadPoint]
    private let maneuverM: Double?
    @Environment(\.theme) private var theme

    init(ahead: [CLLocationCoordinate2D], headingDeg: Double?, distToManeuverM: Double?, step: NavStep?) {
        projected = RoadProjection.project(ahead, headingDeg: headingDeg)
        let toTurn: Double? = step == nil ? nil : distToManeuverM
        maneuverM = toTurn.flatMap { (d: Double) -> Double? in
            return (d > 0 && d <= RoadProjection.horizonM) ? d : nil
        }
    }

    var body: some View {
        if projected.count < 2 {
            // No route ahead (or no fix yet): nothing to draw, and nothing to take up space.
            EmptyView()
        } else {
            Canvas { context, size in
                let w: CGFloat = size.width
                let h: CGFloat = size.height
                guard w > 0, h > 0 else { return }
                // Clip all of it to the panel, so a hairpin cannot paint outside the chamfer.
                context.clip(to: Path(CGRect(origin: .zero, size: size)))

                let cx: CGFloat = w / 2
                let topPad: CGFloat = 15
                let bottom: CGFloat = h - 4
                // Points per metre across: one width-relative number, so narrow and wide panels read the same.
                let lateralScale: CGFloat = w / 330
                let halfWide: CGFloat = 16
                let halfNarrow: CGFloat = 4
                let n: Int = projected.count

                func tOf(_ d: Double) -> CGFloat {
                    return CGFloat(max(0, min(1, d / RoadProjection.horizonM)))
                }

                var xs = [CGFloat](repeating: 0, count: n)
                var ys = [CGFloat](repeating: 0, count: n)
                var half = [CGFloat](repeating: 0, count: n)
                var nx = [CGFloat](repeating: 0, count: n)
                var ny = [CGFloat](repeating: 0, count: n)
                for i in 0..<n {
                    let p: RoadPoint = projected[i]
                    let t: CGFloat = tOf(p.along)
                    // Perspective: sideways offset shrinks with distance, and the road narrows as it recedes.
                    xs[i] = cx + CGFloat(p.lateral) * lateralScale * (1 - 0.55 * t)
                    // Distance compresses nonlinearly up the panel, so the near road gets most of the height.
                    ys[i] = bottom - (h - topPad) * CGFloat(pow(Double(t), 0.72))
                    half[i] = halfWide + (halfNarrow - halfWide) * t
                }
                // Screen normal of each vertex, from the chord through its neighbours (the ends borrow their one).
                for i in 0..<n {
                    let a: Int = max(0, i - 1)
                    let b: Int = min(n - 1, i + 1)
                    var tx: CGFloat = xs[b] - xs[a]
                    var ty: CGFloat = ys[b] - ys[a]
                    let len: CGFloat = (tx * tx + ty * ty).squareRoot()
                    if len < 0.001 {
                        tx = 0
                        ty = -1
                    } else {
                        tx /= len
                        ty /= len
                    }
                    nx[i] = -ty
                    ny[i] = tx
                }

                var left = Path()
                var right = Path()
                var area = Path()
                for i in 0..<n {
                    let p = CGPoint(x: xs[i] - nx[i] * half[i], y: ys[i] - ny[i] * half[i])
                    if i == 0 {
                        left.move(to: p)
                        area.move(to: p)
                    } else {
                        left.addLine(to: p)
                        area.addLine(to: p)
                    }
                }
                for i in stride(from: n - 1, through: 0, by: -1) {
                    let p = CGPoint(x: xs[i] + nx[i] * half[i], y: ys[i] + ny[i] * half[i])
                    if i == n - 1 { right.move(to: p) } else { right.addLine(to: p) }
                    area.addLine(to: p)
                }
                area.closeSubpath()

                let signal: Color = theme.signal
                // Faint wash so the wireframe sits on glass rather than floating, then the two edges.
                context.fill(area, with: .color(signal.opacity(0.05)))
                context.stroke(left, with: .color(signal.opacity(0.5)), lineWidth: 1.2)
                context.stroke(right, with: .color(signal.opacity(0.5)), lineWidth: 1.2)

                var centre = Path()
                for i in 0..<n {
                    if i == 0 {
                        centre.move(to: CGPoint(x: xs[i], y: ys[i]))
                    } else {
                        centre.addLine(to: CGPoint(x: xs[i], y: ys[i]))
                    }
                }
                context.stroke(centre, with: .color(signal.opacity(0.9)),
                               style: StrokeStyle(lineWidth: 1.4, lineCap: .butt, dash: [5, 5]))

                // The centreline at a given distance, with the width and normal there: for the cross ticks.
                func crossTick(at metres: Double) -> Path? {
                    guard metres > 0, metres <= RoadProjection.horizonM else { return nil }
                    for i in 0..<(n - 1) {
                        let d0: Double = projected[i].along
                        let d1: Double = projected[i + 1].along
                        if metres < d0 || metres > d1 || d1 <= d0 { continue }
                        let f: CGFloat = CGFloat((metres - d0) / (d1 - d0))
                        var mx: CGFloat = nx[i] + (nx[i + 1] - nx[i]) * f
                        var my: CGFloat = ny[i] + (ny[i + 1] - ny[i]) * f
                        let len: CGFloat = (mx * mx + my * my).squareRoot()
                        if len > 0.001 {
                            mx /= len
                            my /= len
                        }
                        let x: CGFloat = xs[i] + (xs[i + 1] - xs[i]) * f
                        let y: CGFloat = ys[i] + (ys[i + 1] - ys[i]) * f
                        let hw: CGFloat = half[i] + (half[i + 1] - half[i]) * f
                        var tick = Path()
                        tick.move(to: CGPoint(x: x - mx * hw, y: y - my * hw))
                        tick.addLine(to: CGPoint(x: x + mx * hw, y: y + my * hw))
                        return tick
                    }
                    return nil
                }

                // Cross ticks: the retro part of the wireframe, one every so many metres up the road.
                for m in [28.0, 60.0, 100.0, 145.0] {
                    if let t = crossTick(at: m) {
                        context.stroke(t, with: .color(signal.opacity(0.28)), lineWidth: 1)
                    }
                }
                // A brighter bar where the next manoeuvre is, so a turn ahead reads on the road itself.
                if let m = maneuverM, let t = crossTick(at: m) {
                    context.stroke(t, with: .color(signal.opacity(0.65)), lineWidth: 2)
                }

                // The vehicle pointer: a chevron pointing up at the bottom centre, with a cheap, dimmer one behind it.
                let baseY: CGFloat = h - 2
                let halfW: CGFloat = 6
                let notch: CGFloat = 3.5
                let tipY: CGFloat = baseY - 12
                var glow = Path()
                glow.move(to: CGPoint(x: cx, y: tipY - 2))
                glow.addLine(to: CGPoint(x: cx + halfW * 1.7, y: baseY))
                glow.addLine(to: CGPoint(x: cx, y: baseY - notch))
                glow.addLine(to: CGPoint(x: cx - halfW * 1.7, y: baseY))
                glow.closeSubpath()
                var arrow = Path()
                arrow.move(to: CGPoint(x: cx, y: tipY))
                arrow.addLine(to: CGPoint(x: cx + halfW, y: baseY))
                arrow.addLine(to: CGPoint(x: cx, y: baseY - notch))
                arrow.addLine(to: CGPoint(x: cx - halfW, y: baseY))
                arrow.closeSubpath()
                context.fill(glow, with: .color(signal.opacity(0.18)))
                context.fill(arrow, with: .color(signal))
            }
            .frame(maxWidth: .infinity)
            .frame(height: 96)
            .cvPanel(fill: theme.glass, edge: theme.lineStrong, cut: Cut.m)
            .accessibilityHidden(true)
        }
    }
}
