import SwiftUI
import UIKit
import CoreLocation
import MapLibre

struct MapPin: Identifiable, Equatable {
    enum Kind {
        case lead
        case member
        case stopped
    }

    let id: String
    let initials: String
    let kind: Kind
    let coordinate: CLLocationCoordinate2D

    static func == (a: MapPin, b: MapPin) -> Bool {
        return a.id == b.id && a.initials == b.initials && a.kind == b.kind
            && a.coordinate.latitude == b.coordinate.latitude
            && a.coordinate.longitude == b.coordinate.longitude
    }
}

/// Annotation carrying the pin's look.
final class PinAnnotation: MLNPointAnnotation {
    var pinId: String = ""
    var initials: String = ""
    var kind: MapPin.Kind = .member
}

/// A round pin: avatar initials on a coloured disc.
final class PinAnnotationView: MLNAnnotationView {
    private let label: UILabel = UILabel()

    override init(annotation: MLNAnnotation?, reuseIdentifier: String?) {
        super.init(annotation: annotation, reuseIdentifier: reuseIdentifier)
        frame = CGRect(x: 0, y: 0, width: 36, height: 36)
        layer.cornerRadius = 18
        layer.borderWidth = 2
        label.frame = bounds
        label.textAlignment = .center
        label.font = UIFont.systemFont(ofSize: 12, weight: .bold)
        addSubview(label)
    }

    required init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) is not used")
    }

    func apply(initials: String, fill: UIColor, edge: UIColor, text: UIColor) {
        label.text = initials
        label.textColor = text
        backgroundColor = fill
        layer.borderColor = edge.cgColor
    }
}

struct ConvoyMapView: UIViewRepresentable {
    let pins: [MapPin]
    let is3D: Bool
    /// Increment to re-centre on the user.
    let recenterToken: Int
    let theme: Theme

    func makeCoordinator() -> Coordinator {
        return Coordinator()
    }

    func makeUIView(context: Context) -> MLNMapView {
        let map: MLNMapView = MLNMapView(frame: .zero, styleURL: URL(string: "https://tiles.openfreemap.org/styles/liberty"))
        map.delegate = context.coordinator
        map.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        map.showsUserLocation = true
        map.logoView.isHidden = false
        map.setCenter(CLLocationCoordinate2D(latitude: -37.8136, longitude: 144.9631), zoomLevel: 12, animated: false)
        context.coordinator.map = map
        return map
    }

    func updateUIView(_ map: MLNMapView, context: Context) {
        let c: Coordinator = context.coordinator
        c.theme = theme
        c.syncPins(pins)
        if c.is3D != is3D {
            c.is3D = is3D
            c.applyBuildings()
            c.applyPitch()
        }
        if c.lastRecenter != recenterToken {
            c.lastRecenter = recenterToken
            map.userTrackingMode = .follow
        }
    }

    final class Coordinator: NSObject, MLNMapViewDelegate {
        weak var map: MLNMapView?
        var theme: Theme = Theme.night
        var is3D: Bool = false
        var lastRecenter: Int = 0
        private var annotations: [String: PinAnnotation] = [:]
        private var buildingLayers: [MLNFillExtrusionStyleLayer] = []

        // MARK: Style, darkened to match design-tools/render.html

        func mapView(_ mapView: MLNMapView, didFinishLoading style: MLNStyle) {
            var extrusions: [MLNFillExtrusionStyleLayer] = []
            var firstAbove: MLNStyleLayer? = nil
            let original: [MLNStyleLayer] = style.layers

            for layer in original {
                let id: String = layer.identifier
                if id.range(of: "^poi|^airport|housenumber|shield", options: .regularExpression) != nil {
                    layer.isVisible = false
                }
                if firstAbove == nil && (layer is MLNFillExtrusionStyleLayer || layer is MLNSymbolStyleLayer) {
                    firstAbove = layer
                }
                if let symbol = layer as? MLNSymbolStyleLayer {
                    let isWater: Bool = id.contains("water")
                    symbol.textColor = NSExpression(forConstantValue: UIColor(hex: isWater ? 0x6f8fb3 : 0xb9c4d2))
                    symbol.textHaloColor = NSExpression(forConstantValue: UIColor(hex: 0x0b0f16))
                    symbol.textHaloWidth = NSExpression(forConstantValue: 1.4)
                }
                if let ext = layer as? MLNFillExtrusionStyleLayer {
                    ext.fillExtrusionColor = NSExpression(forConstantValue: UIColor(hex: 0x3b4357))
                    ext.fillExtrusionOpacity = NSExpression(forConstantValue: 0.9)
                    extrusions.append(ext)
                }
            }
            buildingLayers = extrusions

            // Full-world "mood" tint under buildings and labels.
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
            mood.fillColor = NSExpression(forConstantValue: UIColor(hex: 0x0a0c1a))
            mood.fillOpacity = NSExpression(forConstantValue: 0.62)
            if let above = firstAbove {
                style.insertLayer(mood, below: above)
            } else {
                style.addLayer(mood)
            }

            applyBuildings()
            applyPitch()
        }

        func applyBuildings() {
            for l in buildingLayers {
                l.isVisible = is3D
            }
        }

        func applyPitch() {
            guard let map = map else { return }
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
                    if ann.kind != pin.kind || ann.initials != pin.initials {
                        ann.kind = pin.kind
                        ann.initials = pin.initials
                        if let view = map.view(for: ann) as? PinAnnotationView {
                            restyle(view, ann)
                        }
                    }
                } else {
                    let ann: PinAnnotation = PinAnnotation()
                    ann.pinId = pin.id
                    ann.initials = pin.initials
                    ann.kind = pin.kind
                    ann.coordinate = pin.coordinate
                    annotations[pin.id] = ann
                    map.addAnnotation(ann)
                }
            }
        }

        private func restyle(_ view: PinAnnotationView, _ ann: PinAnnotation) {
            let edge: Color
            switch ann.kind {
            case .lead: edge = theme.lead
            case .member: edge = theme.go
            case .stopped: edge = theme.pulse
            }
            view.apply(initials: ann.initials,
                       fill: UIColor(theme.surface300),
                       edge: UIColor(edge),
                       text: UIColor(theme.ink))
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
