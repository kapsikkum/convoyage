import SwiftUI
import UIKit
import CoreLocation
import MapLibre

struct MapPin: Identifiable, Equatable {
    enum Kind {
        case lead
        case member
        case stopped
        case sos
        case hazard
        case police
        case destination
        case rest
        /// A PhotoScout photo spot.
        case spot
        /// A PhotoScout event or cruise.
        case event
    }

    let id: String
    let initials: String
    let kind: Kind
    let coordinate: CLLocationCoordinate2D
    /// An SF Symbol drawn instead of the initials.
    var symbol: String? = nil
    /// The member's primary vehicle, drawn as a small badge on the pin.
    var vehicle: VehicleBuild? = nil
    /// Degrees from north the member is driving; turns the top-down car marker.
    var heading: Double? = nil

    /// The heading in 6 degree steps, so tiny changes do not restyle the pin.
    var headingStep: Int? { return heading.map { (h: Double) -> Int in return Int((h / 6).rounded()) } }

    static func == (a: MapPin, b: MapPin) -> Bool {
        return a.id == b.id && a.initials == b.initials && a.kind == b.kind && a.symbol == b.symbol && a.vehicle == b.vehicle
            && a.headingStep == b.headingStep
            && a.coordinate.latitude == b.coordinate.latitude
            && a.coordinate.longitude == b.coordinate.longitude
    }
}

/// Annotation carrying the pin's look.
final class PinAnnotation: MLNPointAnnotation {
    var pinId: String = ""
    var initials: String = ""
    var kind: MapPin.Kind = .member
    var symbol: String?
    var vehicle: VehicleBuild?
    var heading: Double?
    var headingStep: Int? { return heading.map { (h: Double) -> Int in return Int((h / 6).rounded()) } }
}

/// A round pin: avatar initials (or a symbol) on a coloured disc.
final class PinAnnotationView: MLNAnnotationView {
    private let label: UILabel = UILabel()
    private let icon: UIImageView = UIImageView()
    private let badge: UIImageView = UIImageView()
    private var badgeKey: String?
    /// Car mode: the top-down render, a glow under it and the initials as a tag.
    private let car: UIImageView = UIImageView()
    private let glow: CAGradientLayer = CAGradientLayer()
    private let tagLabel: UILabel = UILabel()
    private var carKey: String?

    override init(annotation: MLNAnnotation?, reuseIdentifier: String?) {
        super.init(annotation: annotation, reuseIdentifier: reuseIdentifier)
        frame = CGRect(x: 0, y: 0, width: 36, height: 36)
        layer.cornerRadius = 18
        layer.borderWidth = 2
        label.frame = bounds
        label.textAlignment = .center
        label.font = UIFont.systemFont(ofSize: 12, weight: .bold)
        addSubview(label)
        icon.frame = CGRect(x: 8, y: 8, width: 20, height: 20)
        icon.contentMode = .scaleAspectFit
        addSubview(icon)
        badge.frame = CGRect(x: 21, y: 21, width: 18, height: 18)
        badge.layer.cornerRadius = 9
        badge.layer.borderWidth = 1.5
        badge.layer.borderColor = UIColor(white: 0.04, alpha: 1).cgColor
        badge.layer.magnificationFilter = CALayerContentsFilter.nearest
        badge.layer.masksToBounds = true
        badge.backgroundColor = UIColor(white: 0.09, alpha: 1)
        badge.contentMode = .scaleAspectFit
        badge.isHidden = true
        addSubview(badge)

        glow.type = .radial
        glow.startPoint = CGPoint(x: 0.5, y: 0.5)
        glow.endPoint = CGPoint(x: 1, y: 1)
        glow.isHidden = true
        layer.insertSublayer(glow, at: 0)
        car.contentMode = .scaleAspectFit
        car.layer.magnificationFilter = CALayerContentsFilter.nearest
        car.isHidden = true
        addSubview(car)
        tagLabel.font = UIFont.systemFont(ofSize: 9, weight: .bold)
        tagLabel.textAlignment = .center
        tagLabel.layer.borderWidth = 1
        tagLabel.layer.masksToBounds = true
        tagLabel.isHidden = true
        addSubview(tagLabel)
    }

    /// Draws the member as their car (nil goes back to the round pin). On a flat map it is the plan view turned to their
    /// heading; on a tilted map it is a frame drawn from the camera's angle for that heading and tilt.
    func setCar(_ build: VehicleBuild?, heading: Double?, edge: UIColor, initials: String, tilt: Float, bearing: Double) {
        guard let b = build else {
            if !car.isHidden {
                car.isHidden = true
                glow.isHidden = true
                tagLabel.isHidden = true
                label.isHidden = false
                layer.borderWidth = 2
                rotatesToMatchCamera = false
                car.transform = .identity
                frame = CGRect(x: frame.minX, y: frame.minY, width: 36, height: 36)
                layer.cornerRadius = 18
            }
            carKey = nil
            return
        }
        frame = CGRect(x: frame.minX, y: frame.minY, width: 56, height: 56)
        layer.cornerRadius = 28
        backgroundColor = .clear
        layer.borderWidth = 0
        label.isHidden = true
        icon.isHidden = true
        badge.isHidden = true
        glow.isHidden = false
        glow.frame = bounds.insetBy(dx: 7, dy: 7)
        glow.colors = [edge.withAlphaComponent(0.45).cgColor, edge.withAlphaComponent(0).cgColor]
        car.isHidden = false
        tagLabel.isHidden = initials.isEmpty
        tagLabel.text = initials
        tagLabel.textColor = .white
        tagLabel.backgroundColor = UIColor(white: 0.06, alpha: 0.9)
        tagLabel.layer.borderColor = edge.cgColor
        tagLabel.frame = CGRect(x: 14, y: 46, width: 28, height: 13)
        if tilt < 10 {
            // Flat map: one plan view, turned smoothly with the heading.
            rotatesToMatchCamera = true
            car.frame = CGRect(x: 17, y: 2, width: 22, height: 52)
            car.transform = CGAffineTransform(rotationAngle: CGFloat((heading ?? 0) * Double.pi / 180))
            let key: String = b.key("Top")
            if key == carKey && car.image != nil { return }
            carKey = key
            car.image = Ps1Thumbs.cached(b, Ps1ThumbSize.top)
            if car.image == nil {
                Task { @MainActor in
                    let img: UIImage? = await Ps1Thumbs.shared.image(b, Ps1ThumbSize.top)
                    if self.carKey == key { self.car.image = img }
                }
            }
            return
        }
        // Tilted map: the frame is drawn for the heading relative to the camera, and sits square on the screen.
        let rel: Double = (heading ?? 0) - bearing
        let step: Double = 360.0 / Double(Ps1Thumbs.angleSteps)
        var yaw: Int = Int((rel / step).rounded())
        yaw = ((yaw % Ps1Thumbs.angleSteps) + Ps1Thumbs.angleSteps) % Ps1Thumbs.angleSteps
        var pitch: Int = 0
        var best: Float = Float.greatestFiniteMagnitude
        for (i, t) in Ps1Thumbs.angleTilts.enumerated() where abs(t - tilt) < best {
            best = abs(t - tilt)
            pitch = i
        }
        rotatesToMatchCamera = false
        car.transform = .identity
        car.frame = CGRect(x: 0, y: 0, width: 56, height: 56)
        let key: String = b.key("a\(yaw).\(pitch)")
        if key == carKey && car.image != nil { return }
        carKey = key
        car.image = Ps1Thumbs.cachedAngle(b, yaw: yaw, pitch: pitch)
        if car.image == nil {
            Task { @MainActor in
                let img: UIImage? = await Ps1Thumbs.shared.angle(b, yaw: yaw, pitch: pitch)
                if self.carKey == key { self.car.image = img }
            }
        }
    }

    required init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) is not used")
    }

    /// A small render of the member's vehicle at the pin's bottom right.
    func setVehicle(_ build: VehicleBuild?) {
        guard let b = build else {
            badge.isHidden = true
            badge.image = nil
            badgeKey = nil
            return
        }
        let key: String = b.key("Badge")
        if key == badgeKey && badge.image != nil { return }
        badgeKey = key
        badge.isHidden = false
        badge.image = Ps1Thumbs.cached(b, Ps1ThumbSize.badge)
        if badge.image == nil {
            Task { @MainActor in
                let img: UIImage? = await Ps1Thumbs.shared.image(b, Ps1ThumbSize.badge)
                if self.badgeKey == key { self.badge.image = img }
            }
        }
    }

    func apply(initials: String, symbol: String?, fill: UIColor, edge: UIColor, text: UIColor) {
        label.text = initials
        label.font = UIFont.systemFont(ofSize: initials.count >= 3 ? 10 : 12, weight: .bold)
        label.textColor = text
        if let name = symbol {
            icon.image = UIImage(systemName: name)
            icon.tintColor = text
            icon.isHidden = false
            label.isHidden = true
        } else {
            icon.image = nil
            icon.isHidden = true
            label.isHidden = false
        }
        backgroundColor = fill
        layer.borderColor = edge.cgColor
    }
}

/// Night-map colours. The "mood" tint is drawn over the base map, so road colours are pre-compensated to land on
/// the colours the design asks for after the tint.
enum NightMap {
    static let moodHex: UInt32 = 0x0a0c1a
    static let moodOpacity: CGFloat = 0.62
    /// What roads should look like on screen (brighter than before so they read on the night map).
    static let minorRoadHex: UInt32 = 0x4a5878
    static let majorRoadHex: UInt32 = 0x7583a2
    static let casingHex: UInt32 = 0x1a2232

    private static func comps(_ hex: UInt32) -> (CGFloat, CGFloat, CGFloat) {
        return (CGFloat((hex >> 16) & 0xFF) / 255.0, CGFloat((hex >> 8) & 0xFF) / 255.0, CGFloat(hex & 0xFF) / 255.0)
    }

    /// The layer colour that renders as `hex` once the mood tint is on top of it.
    static func preTint(_ hex: UInt32) -> UIColor {
        let t = comps(hex)
        let m = comps(moodHex)
        func ch(_ target: CGFloat, _ mood: CGFloat) -> CGFloat {
            let v: CGFloat = (target - moodOpacity * mood) / (1 - moodOpacity)
            return min(1, max(0, v))
        }
        return UIColor(red: ch(t.0, m.0), green: ch(t.1, m.1), blue: ch(t.2, m.2), alpha: 1)
    }

    static func isRoadLine(_ id: String) -> Bool {
        if id.range(of: "^(road|bridge|tunnel)_", options: .regularExpression) == nil { return false }
        if id.range(of: "rail|transit|path|pedestrian|aerialway|ferry|oneway|arrow|name|label", options: .regularExpression) != nil { return false }
        return true
    }

    static func isMajorRoad(_ id: String) -> Bool {
        return id.range(of: "motorway|trunk|primary|secondary", options: .regularExpression) != nil
    }
}

/// Where the navigation camera looks: at the person, turned to their heading, tilted to 60 degrees.
struct FollowCamera: Equatable {
    var coordinate: CLLocationCoordinate2D
    var heading: Double

    static func == (a: FollowCamera, b: FollowCamera) -> Bool {
        return a.coordinate.latitude == b.coordinate.latitude && a.coordinate.longitude == b.coordinate.longitude
            && a.heading == b.heading
    }
}

/// A stretch of route coloured by road quality (from `RouteConditionsDto.roadSegments`).
struct MapRoadSegment: Equatable {
    var grade: String
    var line: [CLLocationCoordinate2D]

    static func == (a: MapRoadSegment, b: MapRoadSegment) -> Bool {
        if a.grade != b.grade || a.line.count != b.line.count { return false }
        return zip(a.line, b.line).allSatisfy { (p: (CLLocationCoordinate2D, CLLocationCoordinate2D)) -> Bool in
            return p.0.latitude == p.1.latitude && p.0.longitude == p.1.longitude
        }
    }
}

struct ConvoyMapView: UIViewRepresentable {
    let pins: [MapPin]
    let is3D: Bool
    /// Increment to re-centre on the user.
    let recenterToken: Int
    let theme: Theme
    /// My position, drawn as a layer under the place labels (not as an overlay on top of them).
    var userCoordinate: CLLocationCoordinate2D? = nil
    /// The shared convoy route.
    var route: [CLLocationCoordinate2D] = []
    /// The route split by road quality, drawn over the plain route line.
    var roadSegments: [MapRoadSegment] = []
    /// Called (debounced) when the visible area changes, and once the style loads.
    var onViewport: ((GeoBox) -> Void)? = nil
    /// Called when the person long-presses the map.
    var onLongPress: ((CLLocationCoordinate2D) -> Void)? = nil
    /// Called when the person taps the map (not a pin). Only set where taps place things (the route builder).
    var onTap: ((CLLocationCoordinate2D) -> Void)? = nil
    /// Navigation: follow the person from behind, pitched to 60 degrees. Nil leaves the camera alone.
    var follow: FollowCamera? = nil
    /// Increment to fit `fitCoordinates` (the whole route) in view.
    var fitToken: Int = 0
    var fitCoordinates: [CLLocationCoordinate2D] = []
    /// The Drive map: keep the person in the middle as they move, until they pan or zoom the map themselves.
    var followUser: Bool = false
    /// The person moved the map with a gesture (following stops).
    var onUserMoved: (() -> Void)? = nil
    /// A pin was tapped (its id).
    var onPinTap: ((String) -> Void)? = nil

    func makeCoordinator() -> Coordinator {
        return Coordinator()
    }

    func makeUIView(context: Context) -> MLNMapView {
        let map: MLNMapView = MLNMapView(frame: .zero, styleURL: URL(string: "https://tiles.openfreemap.org/styles/liberty"))
        map.delegate = context.coordinator
        map.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        // The puck is a style layer (see Coordinator.addPuck) so place labels draw over it.
        map.showsUserLocation = false
        map.logoView.isHidden = false
        // Default view before a GPS fix: Bathurst, NSW (home of Mount Panorama).
        map.setCenter(CLLocationCoordinate2D(latitude: -33.4193, longitude: 149.5775), zoomLevel: 12, animated: false)
        let press: UILongPressGestureRecognizer = UILongPressGestureRecognizer(target: context.coordinator,
                                                                             action: #selector(Coordinator.handleLongPress(_:)))
        press.minimumPressDuration = 0.6
        map.addGestureRecognizer(press)
        if onTap != nil {
            let tap: UITapGestureRecognizer = UITapGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.handleTap(_:)))
            for r in map.gestureRecognizers ?? [] {
                if let t = r as? UITapGestureRecognizer, t.numberOfTapsRequired == 2 { tap.require(toFail: t) }
            }
            map.addGestureRecognizer(tap)
        }
        context.coordinator.map = map
        return map
    }

    func updateUIView(_ map: MLNMapView, context: Context) {
        let c: Coordinator = context.coordinator
        c.theme = theme
        c.onViewport = onViewport
        c.onLongPress = onLongPress
        c.onTap = onTap
        c.onUserMoved = onUserMoved
        c.onPinTap = onPinTap
        c.followUser = followUser
        c.syncPins(pins)
        c.setRoute(route)
        c.setRoadSegments(roadSegments)
        c.setUser(userCoordinate)
        c.applyFollow(follow)
        if c.lastFit != fitToken {
            c.lastFit = fitToken
            c.fit(fitCoordinates)
        }
        if c.is3D != is3D {
            let flatChanged: Bool = c.is3D != is3D
            c.is3D = is3D
            c.applyBuildings()
            if flatChanged { c.restyleAll() }
            c.applyPitch()
        }
        if c.lastRecenter != recenterToken {
            c.lastRecenter = recenterToken
            c.recenter()
        }
    }

    final class Coordinator: NSObject, MLNMapViewDelegate {
        weak var map: MLNMapView?
        var theme: Theme = Theme.night
        var is3D: Bool = false
        var lastRecenter: Int = 0
        var lastFit: Int = 0
        private var following: Bool = false
        /// The map bearing and tilt the car-marker frames were last drawn for.
        private var carBearing: Double = 0
        private var carTilt: Float = 0
        private var followApplied: FollowCamera?
        var onViewport: ((GeoBox) -> Void)?
        var onLongPress: ((CLLocationCoordinate2D) -> Void)?
        var onTap: ((CLLocationCoordinate2D) -> Void)?
        var onUserMoved: (() -> Void)?
        var onPinTap: ((String) -> Void)?
        var followUser: Bool = false
        private var viewportTask: Task<Void, Never>?
        private var annotations: [String: PinAnnotation] = [:]
        private var buildingLayers: [MLNFillExtrusionStyleLayer] = []
        private var routeSource: MLNShapeSource?
        private var roadSources: [String: MLNShapeSource] = [:]
        private var roadApplied: [MapRoadSegment] = []
        private var puckSource: MLNShapeSource?
        private var routeCoords: [CLLocationCoordinate2D] = []
        private var routeApplied: [CLLocationCoordinate2D] = []
        private var userCoord: CLLocationCoordinate2D?
        private var userApplied: CLLocationCoordinate2D?
        private var centeredOnUser: Bool = false

        // MARK: Style, darkened to match design-tools/render.html

        func mapView(_ mapView: MLNMapView, didFinishLoading style: MLNStyle) {
            var extrusions: [MLNFillExtrusionStyleLayer] = []
            let original: [MLNStyleLayer] = style.layers
            var firstText: MLNStyleLayer? = nil

            for layer in original {
                let id: String = layer.identifier
                // Landmarks stay (higher-ranked POIs, transit, airports); minor POIs, house numbers and shields are clutter.
                if id.range(of: "^poi_r20|housenumber|shield", options: .regularExpression) != nil {
                    layer.isVisible = false
                }
                if let symbol = layer as? MLNSymbolStyleLayer {
                    let isWater: Bool = id.contains("water")
                    let isPoi: Bool = id.hasPrefix("poi") || id.hasPrefix("airport")
                    let hex: UInt32 = isWater ? 0x6f8fb3 : (isPoi ? 0x8e9cb4 : 0xb9c4d2)
                    symbol.textColor = NSExpression(forConstantValue: UIColor(hex: hex))
                    if isPoi { symbol.iconOpacity = NSExpression(forConstantValue: 0.7) }
                    symbol.textHaloColor = NSExpression(forConstantValue: UIColor(hex: 0x0b0f16))
                    symbol.textHaloWidth = NSExpression(forConstantValue: 1.4)
                    if firstText == nil && symbol.text != nil {
                        firstText = symbol
                    }
                }
                if let ext = layer as? MLNFillExtrusionStyleLayer {
                    ext.fillExtrusionColor = NSExpression(forConstantValue: UIColor(hex: 0x3b4357))
                    ext.fillExtrusionOpacity = NSExpression(forConstantValue: 0.9)
                    extrusions.append(ext)
                }
                if let line = layer as? MLNLineStyleLayer, NightMap.isRoadLine(id) {
                    // Roads read on the night map: minor #3a4660, major #56627e (after the tint).
                    let hex: UInt32
                    if id.contains("casing") {
                        hex = NightMap.casingHex
                    } else {
                        hex = NightMap.isMajorRoad(id) ? NightMap.majorRoadHex : NightMap.minorRoadHex
                    }
                    line.lineColor = NSExpression(forConstantValue: NightMap.preTint(hex))
                }
            }
            buildingLayers = extrusions

            // Full-world "mood" tint. It must cover every fill and line (parks, water, all roads and bridges) and sit
            // under only the 3D buildings and the labels: below the first extrusion, else below the first symbol
            // after the last flat layer.
            var world: [CLLocationCoordinate2D] = [
                CLLocationCoordinate2D(latitude: -85, longitude: -180),
                CLLocationCoordinate2D(latitude: -85, longitude: 180),
                CLLocationCoordinate2D(latitude: 85, longitude: 180),
                CLLocationCoordinate2D(latitude: 85, longitude: -180),
                CLLocationCoordinate2D(latitude: -85, longitude: -180)
            ]
            let polygon: MLNPolygon = MLNPolygon(coordinates: &world, count: UInt(world.count))
            let source: MLNShapeSource = MLNShapeSource(identifier: "mood", shape: polygon, options: nil)
            style.addSource(source)
            let mood: MLNFillStyleLayer = MLNFillStyleLayer(identifier: "mood", source: source)
            mood.fillColor = NSExpression(forConstantValue: UIColor(hex: NightMap.moodHex))
            mood.fillOpacity = NSExpression(forConstantValue: NightMap.moodOpacity)

            var insertIndex: Int = original.count
            if let ext = original.firstIndex(where: { (l: MLNStyleLayer) -> Bool in return l is MLNFillExtrusionStyleLayer }) {
                insertIndex = ext
            } else {
                var lastFlat: Int = -1
                for (i, l) in original.enumerated() where !(l is MLNSymbolStyleLayer) {
                    lastFlat = i
                }
                insertIndex = lastFlat + 1
            }
            if insertIndex < original.count {
                style.insertLayer(mood, below: original[insertIndex])
                // Flat layers still above the mood (boundary lines) would stay bright: fade them.
                for l in original[insertIndex...] {
                    if let line = l as? MLNLineStyleLayer {
                        line.lineOpacity = NSExpression(forConstantValue: 0.35)
                    } else if let fill = l as? MLNFillStyleLayer {
                        fill.fillOpacity = NSExpression(forConstantValue: 0.35)
                    }
                }
            } else {
                style.addLayer(mood)
            }

            addRoute(style, below: firstText)
            addRoadQuality(style, below: firstText)
            addPuck(style, below: firstText)

            applyBuildings()
            applyPitch()
            applyRoute()
            applyRoadSegments()
            applyUser()
            reportViewport(mapView)
        }

        private func place(_ layer: MLNStyleLayer, in style: MLNStyle, below: MLNStyleLayer?) {
            if let b = below {
                style.insertLayer(layer, below: b)
            } else {
                style.addLayer(layer)
            }
        }

        /// The convoy route: a signal-coloured line with a soft halo, under the labels.
        private func addRoute(_ style: MLNStyle, below: MLNStyleLayer?) {
            let src: MLNShapeSource = MLNShapeSource(identifier: "convoy-route", shape: nil, options: nil)
            style.addSource(src)
            routeSource = src
            let halo: MLNLineStyleLayer = MLNLineStyleLayer(identifier: "convoy-route-halo", source: src)
            halo.lineColor = NSExpression(forConstantValue: UIColor(hex: 0x2de2e6))
            halo.lineWidth = NSExpression(forConstantValue: 14)
            halo.lineOpacity = NSExpression(forConstantValue: 0.16)
            halo.lineBlur = NSExpression(forConstantValue: 2)
            place(halo, in: style, below: below)
            let line: MLNLineStyleLayer = MLNLineStyleLayer(identifier: "convoy-route", source: src)
            line.lineColor = NSExpression(forConstantValue: UIColor(hex: 0x2de2e6))
            line.lineWidth = NSExpression(forConstantValue: 4)
            place(line, in: style, below: below)
        }

        /// The route coloured by road surface: one source per grade, over the plain route line and under the labels.
        private func addRoadQuality(_ style: MLNStyle, below: MLNStyleLayer?) {
            let colours: [(grade: String, hex: UInt32)] = [
                ("good", 0x46e08c), ("fair", 0xffb020), ("poor", 0xff3d8b), ("unknown", 0x4a5878)
            ]
            for c in colours {
                let src: MLNShapeSource = MLNShapeSource(identifier: "road-\(c.grade)", shape: nil, options: nil)
                style.addSource(src)
                roadSources[c.grade] = src
                let line: MLNLineStyleLayer = MLNLineStyleLayer(identifier: "road-\(c.grade)", source: src)
                line.lineColor = NSExpression(forConstantValue: UIColor(hex: c.hex))
                line.lineWidth = NSExpression(forConstantValue: 5)
                line.lineOpacity = NSExpression(forConstantValue: 0.95)
                place(line, in: style, below: below)
            }
        }

        /// My position as circle layers inserted below the first text layer, so place labels sit on top of it.
        private func addPuck(_ style: MLNStyle, below: MLNStyleLayer?) {            let src: MLNShapeSource = MLNShapeSource(identifier: "puck", shape: nil, options: nil)
            style.addSource(src)
            puckSource = src
            let halo: MLNCircleStyleLayer = MLNCircleStyleLayer(identifier: "puck-halo", source: src)
            halo.circleColor = NSExpression(forConstantValue: UIColor(hex: 0x2de2e6))
            halo.circleRadius = NSExpression(forConstantValue: 18)
            halo.circleOpacity = NSExpression(forConstantValue: 0.22)
            place(halo, in: style, below: below)
            let dot: MLNCircleStyleLayer = MLNCircleStyleLayer(identifier: "puck", source: src)
            dot.circleColor = NSExpression(forConstantValue: UIColor(hex: 0x2de2e6))
            dot.circleRadius = NSExpression(forConstantValue: 7)
            dot.circleStrokeColor = NSExpression(forConstantValue: UIColor(hex: 0x05080d))
            dot.circleStrokeWidth = NSExpression(forConstantValue: 2.5)
            place(dot, in: style, below: below)
        }

        // MARK: Route and user position

        func setRoute(_ coords: [CLLocationCoordinate2D]) {
            routeCoords = coords
            applyRoute()
        }

        private func applyRoute() {
            guard let src = routeSource else { return }
            let same: Bool = routeApplied.count == routeCoords.count
                && zip(routeApplied, routeCoords).allSatisfy { (p: (CLLocationCoordinate2D, CLLocationCoordinate2D)) -> Bool in
                    return p.0.latitude == p.1.latitude && p.0.longitude == p.1.longitude
                }
            if same { return }
            routeApplied = routeCoords
            if routeCoords.count < 2 {
                src.shape = nil
            } else {
                var pts: [CLLocationCoordinate2D] = routeCoords
                src.shape = MLNPolylineFeature(coordinates: &pts, count: UInt(pts.count))
            }
        }

        // MARK: Road quality

        func setRoadSegments(_ segs: [MapRoadSegment]) {
            if segs == roadApplied { return }
            roadApplied = segs
            applyRoadSegments()
        }

        private func applyRoadSegments() {
            guard !roadSources.isEmpty else { return }
            var byGrade: [String: [MLNPolylineFeature]] = [:]
            for s in roadApplied where s.line.count >= 2 {
                var pts: [CLLocationCoordinate2D] = s.line
                byGrade[s.grade, default: []].append(MLNPolylineFeature(coordinates: &pts, count: UInt(pts.count)))
            }
            for (grade, src) in roadSources {
                let shapes: [MLNPolylineFeature] = byGrade[grade] ?? []
                src.shape = shapes.isEmpty ? nil : MLNShapeCollectionFeature(shapes: shapes.map { (f: MLNPolylineFeature) -> MLNShape in
                    return f
                })
            }
        }

        func setUser(_ c: CLLocationCoordinate2D?) {            let moved: Bool = !(userCoord?.latitude == c?.latitude && userCoord?.longitude == c?.longitude)
            userCoord = c
            applyUser()
            guard let c = c, let map = map else { return }
            if !centeredOnUser {
                centeredOnUser = true
                map.setCenter(c, zoomLevel: followUser ? 16 : 14, animated: false)
            } else if followUser && moved && !following {
                map.setCenter(c, animated: true)
            }
        }

        private func applyUser() {
            guard let src = puckSource else { return }
            if let a = userApplied, let b = userCoord, a.latitude == b.latitude, a.longitude == b.longitude { return }
            if userApplied == nil && userCoord == nil { return }
            userApplied = userCoord
            if let c = userCoord {
                let f: MLNPointFeature = MLNPointFeature()
                f.coordinate = c
                src.shape = f
            } else {
                src.shape = nil
            }
        }

        func recenter() {
            guard let map = map, let c = userCoord else { return }
            map.setCenter(c, zoomLevel: max(map.zoomLevel, followUser ? 16 : 15), animated: true)
        }

        // MARK: Navigation camera

        func applyFollow(_ f: FollowCamera?) {
            guard let map = map else { return }
            guard let target = f else {
                if following {
                    following = false
                    followApplied = nil
                    let cam: MLNMapCamera = map.camera
                    cam.pitch = is3D ? 60 : 0
                    cam.heading = 0
                    map.setCamera(cam, animated: true)
                }
                return
            }
            following = true
            if let last = followApplied, last == target { return }
            followApplied = target
            let cam: MLNMapCamera = MLNMapCamera(lookingAtCenter: target.coordinate, altitude: 900, pitch: 60, heading: target.heading)
            // The top padding moves the person down the screen so there is more road to see ahead.
            map.setCamera(cam,
                          withDuration: 1.0,
                          animationTimingFunction: CAMediaTimingFunction(name: .linear),
                          edgePadding: UIEdgeInsets(top: 260, left: 0, bottom: 0, right: 0),
                          completionHandler: nil)
        }

        /// Shows the whole route: north up, flat, with room for the cards at the top and the sheet at the bottom.
        func fit(_ coords: [CLLocationCoordinate2D]) {
            guard let map = map, coords.count >= 2 else { return }
            var pts: [CLLocationCoordinate2D] = stride(from: 0, to: coords.count, by: max(1, coords.count / 300)).map { (i: Int) -> CLLocationCoordinate2D in
                return coords[i]
            }
            if let last = coords.last { pts.append(last) }
            let cam: MLNMapCamera = map.camera
            cam.pitch = 0
            cam.heading = 0
            map.setCamera(cam, animated: false)
            map.setVisibleCoordinates(&pts,
                                      count: UInt(pts.count),
                                      edgePadding: UIEdgeInsets(top: 200, left: 40, bottom: 340, right: 40),
                                      animated: true)
        }

        // MARK: Long press

        @objc func handleTap(_ g: UITapGestureRecognizer) {
            guard g.state == .ended, let map = map else { return }
            onTap?(map.convert(g.location(in: map), toCoordinateFrom: map))
        }

        @objc func handleLongPress(_ g: UILongPressGestureRecognizer) {
            guard g.state == .began, let map = map else { return }
            let point: CGPoint = g.location(in: map)
            let coordinate: CLLocationCoordinate2D = map.convert(point, toCoordinateFrom: map)
            onLongPress?(coordinate)
        }

        // MARK: Viewport (hazards are fetched for what is on screen)

        func mapView(_ mapView: MLNMapView, regionWillChangeWith reason: MLNCameraChangeReason, animated: Bool) {
            let gesture: MLNCameraChangeReason = [.gesturePan, .gesturePinch, .gestureRotate, .gestureZoomIn, .gestureZoomOut, .gestureOneFingerZoom, .gestureTilt]
            if !reason.intersection(gesture).isEmpty { onUserMoved?() }
        }

        func mapView(_ mapView: MLNMapView, didSelect annotation: MLNAnnotation) {
            if let ann = annotation as? PinAnnotation { onPinTap?(ann.pinId) }
            mapView.deselectAnnotation(annotation, animated: false)
        }

        func mapView(_ mapView: MLNMapView, regionDidChangeAnimated animated: Bool) {
            reportViewport(mapView)
            // Keep the camera-angle car frames in step as the map turns or tilts.
            let bearing: Double = mapView.camera.heading
            let tilt: Float = Float(mapView.camera.pitch)
            if !following && (abs(bearing - carBearing) >= 2 || abs(tilt - carTilt) >= 5) {
                carBearing = bearing
                carTilt = tilt
                restyleAll()
            }
        }

        private func reportViewport(_ mapView: MLNMapView) {
            viewportTask?.cancel()
            viewportTask = Task { @MainActor [weak self, weak mapView] in
                try? await Task.sleep(nanoseconds: 600_000_000)
                if Task.isCancelled { return }
                guard let self = self, let mapView = mapView, let handler = self.onViewport else { return }
                let b: MLNCoordinateBounds = mapView.visibleCoordinateBounds
                handler(GeoBox(south: b.sw.latitude, west: b.sw.longitude, north: b.ne.latitude, east: b.ne.longitude))
            }
        }

        func applyBuildings() {
            for l in buildingLayers {
                l.isVisible = is3D
            }
        }

        func applyPitch() {
            guard let map = map else { return }
            if following { return }
            let cam: MLNMapCamera = map.camera
            cam.pitch = is3D ? 60 : 0
            map.setCamera(cam, animated: true)
        }

        // MARK: Pins

        func syncPins(_ pins: [MapPin]) {
            guard let map = map else { return }
            let ids: Set<String> = Set(pins.map { $0.id })
            for (id, ann) in annotations where !ids.contains(id) {
                map.removeAnnotation(ann)
                annotations.removeValue(forKey: id)
            }
            for pin in pins {
                if let ann = annotations[pin.id] {
                    ann.coordinate = pin.coordinate
                    if ann.kind != pin.kind || ann.initials != pin.initials || ann.symbol != pin.symbol || ann.vehicle != pin.vehicle
                        || ann.headingStep != pin.headingStep {
                        ann.vehicle = pin.vehicle
                        ann.heading = pin.heading
                        ann.kind = pin.kind
                        ann.initials = pin.initials
                        ann.symbol = pin.symbol
                        if let view = map.view(for: ann) as? PinAnnotationView {
                            restyle(view, ann)
                        }
                    }
                } else {
                    let ann: PinAnnotation = PinAnnotation()
                    ann.pinId = pin.id
                    ann.initials = pin.initials
                    ann.kind = pin.kind
                    ann.symbol = pin.symbol
                    ann.vehicle = pin.vehicle
                    ann.heading = pin.heading
                    ann.coordinate = pin.coordinate
                    annotations[pin.id] = ann
                    map.addAnnotation(ann)
                }
            }
        }

        private func restyle(_ view: PinAnnotationView, _ ann: PinAnnotation) {
            let edge: Color
            var fill: Color = theme.surface300
            var text: Color = theme.ink
            switch ann.kind {
            case .lead: edge = theme.lead
            case .member: edge = theme.go
            case .stopped: edge = theme.pulse
            case .sos:
                edge = theme.pulse
                fill = theme.pulse
                text = Color.white
            case .hazard:
                edge = theme.hazard
                fill = theme.hazard
                text = Color(hex: 0x05080d)
            case .police:
                edge = theme.police
                fill = theme.police
                text = Color(hex: 0x05080d)
            case .destination:
                edge = theme.signal
                fill = theme.signal
                text = theme.signalInk
            case .rest:
                edge = theme.hazard
                fill = theme.hazard
                text = Color(hex: 0x05080d)
            case .spot:
                edge = theme.signal
                text = theme.signal
            case .event:
                edge = theme.lead
                text = theme.lead
            }
            view.apply(initials: ann.initials,
                       symbol: ann.symbol,
                       fill: UIColor(fill),
                       edge: UIColor(edge),
                       text: UIColor(text))
            view.setVehicle(ann.symbol == nil ? ann.vehicle : nil)
            // Car markers: a plan view on a flat map, a camera-angle frame on a tilted one.
            let carKind: Bool = ann.kind == .lead || ann.kind == .member || ann.kind == .stopped || ann.kind == .sos
            let useCar: Bool = carKind && ann.symbol == nil && !following && ann.vehicle != nil
            let tilt: Float = Float(map?.camera.pitch ?? 0)
            let bearing: Double = map?.camera.heading ?? 0
            view.setCar(useCar ? ann.vehicle : nil, heading: ann.heading, edge: UIColor(edge), initials: ann.initials,
                        tilt: tilt, bearing: bearing)
        }

        /// The map went flat or tilted: pins switch between car and round.
        func restyleAll() {
            guard let map = map else { return }
            for ann in annotations.values {
                if let view = map.view(for: ann) as? PinAnnotationView { restyle(view, ann) }
            }
        }

        func mapView(_ mapView: MLNMapView, viewFor annotation: MLNAnnotation) -> MLNAnnotationView? {
            guard let ann = annotation as? PinAnnotation else { return nil }
            let reuse: String = "pin"
            let view: PinAnnotationView
            if let existing = mapView.dequeueReusableAnnotationView(withIdentifier: reuse) as? PinAnnotationView {
                view = existing
            } else {
                view = PinAnnotationView(annotation: ann, reuseIdentifier: reuse)
            }
            restyle(view, ann)
            return view
        }

        func mapView(_ mapView: MLNMapView, annotationCanShowCallout annotation: MLNAnnotation) -> Bool {
            return false
        }
    }
}

extension UIColor {
    convenience init(hex: UInt32) {
        let r: CGFloat = CGFloat((hex >> 16) & 0xFF) / 255.0
        let g: CGFloat = CGFloat((hex >> 8) & 0xFF) / 255.0
        let b: CGFloat = CGFloat(hex & 0xFF) / 255.0
        self.init(red: r, green: g, blue: b, alpha: 1)
    }
}
