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
    }

    let id: String
    let initials: String
    let kind: Kind
    let coordinate: CLLocationCoordinate2D
    /// An SF Symbol drawn instead of the initials.
    var symbol: String? = nil

    static func == (a: MapPin, b: MapPin) -> Bool {
        return a.id == b.id && a.initials == b.initials && a.kind == b.kind && a.symbol == b.symbol
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
}

/// A round pin: avatar initials (or a symbol) on a coloured disc.
final class PinAnnotationView: MLNAnnotationView {
    private let label: UILabel = UILabel()
    private let icon: UIImageView = UIImageView()

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
    }

    required init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) is not used")
    }

    func apply(initials: String, symbol: String?, fill: UIColor, edge: UIColor, text: UIColor) {
        label.text = initials
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
    static let minorRoadHex: UInt32 = 0x3a4660
    static let majorRoadHex: UInt32 = 0x56627e
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
    /// Called (debounced) when the visible area changes, and once the style loads.
    var onViewport: ((GeoBox) -> Void)? = nil
    /// Called when the person long-presses the map.
    var onLongPress: ((CLLocationCoordinate2D) -> Void)? = nil
    /// Navigation: follow the person from behind, pitched to 60 degrees. Nil leaves the camera alone.
    var follow: FollowCamera? = nil
    /// Increment to fit `fitCoordinates` (the whole route) in view.
    var fitToken: Int = 0
    var fitCoordinates: [CLLocationCoordinate2D] = []

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
        context.coordinator.map = map
        return map
    }

    func updateUIView(_ map: MLNMapView, context: Context) {
        let c: Coordinator = context.coordinator
        c.theme = theme
        c.onViewport = onViewport
        c.onLongPress = onLongPress
        c.syncPins(pins)
        c.setRoute(route)
        c.setUser(userCoordinate)
        c.applyFollow(follow)
        if c.lastFit != fitToken {
            c.lastFit = fitToken
            c.fit(fitCoordinates)
        }
        if c.is3D != is3D {
            c.is3D = is3D
            c.applyBuildings()
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
        private var followApplied: FollowCamera?
        var onViewport: ((GeoBox) -> Void)?
        var onLongPress: ((CLLocationCoordinate2D) -> Void)?
        private var viewportTask: Task<Void, Never>?
        private var annotations: [String: PinAnnotation] = [:]
        private var buildingLayers: [MLNFillExtrusionStyleLayer] = []
        private var routeSource: MLNShapeSource?
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
                if id.range(of: "^poi|^airport|housenumber|shield", options: .regularExpression) != nil {
                    layer.isVisible = false
                }
                if let symbol = layer as? MLNSymbolStyleLayer {
                    let isWater: Bool = id.contains("water")
                    symbol.textColor = NSExpression(forConstantValue: UIColor(hex: isWater ? 0x6f8fb3 : 0xb9c4d2))
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
            addPuck(style, below: firstText)

            applyBuildings()
            applyPitch()
            applyRoute()
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

        /// My position as circle layers inserted below the first text layer, so place labels sit on top of it.
        private func addPuck(_ style: MLNStyle, below: MLNStyleLayer?) {
            let src: MLNShapeSource = MLNShapeSource(identifier: "puck", shape: nil, options: nil)
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

        func setUser(_ c: CLLocationCoordinate2D?) {
            userCoord = c
            applyUser()
            if let c = c, !centeredOnUser, let map = map {
                centeredOnUser = true
                map.setCenter(c, zoomLevel: 14, animated: false)
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
            map.setCenter(c, zoomLevel: max(map.zoomLevel, 15), animated: true)
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

        @objc func handleLongPress(_ g: UILongPressGestureRecognizer) {
            guard g.state == .began, let map = map else { return }
            let point: CGPoint = g.location(in: map)
            let coordinate: CLLocationCoordinate2D = map.convert(point, toCoordinateFrom: map)
            onLongPress?(coordinate)
        }

        // MARK: Viewport (hazards are fetched for what is on screen)

        func mapView(_ mapView: MLNMapView, regionDidChangeAnimated animated: Bool) {
            reportViewport(mapView)
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
                    if ann.kind != pin.kind || ann.initials != pin.initials || ann.symbol != pin.symbol {
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
            }
            view.apply(initials: ann.initials,
                       symbol: ann.symbol,
                       fill: UIColor(fill),
                       edge: UIColor(edge),
                       text: UIColor(text))
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
