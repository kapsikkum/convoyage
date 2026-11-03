import Foundation
import MapKit
import CoreLocation
import Observation

/// A place picked by search or by a long press on the map.
struct NavPlace: Equatable {
    var coordinate: CLLocationCoordinate2D
    var title: String
    var subtitle: String

    static func == (a: NavPlace, b: NavPlace) -> Bool {
        return a.coordinate.latitude == b.coordinate.latitude && a.coordinate.longitude == b.coordinate.longitude
            && a.title == b.title && a.subtitle == b.subtitle
    }
}

/// Address and place search while typing (MKLocalSearchCompleter), biased to where the person is.
/// Apple's search is built in: no key and no server.
@Observable
final class DestinationSearch: NSObject, MKLocalSearchCompleterDelegate {
    var results: [MKLocalSearchCompletion] = []
    var failed: Bool = false

    @ObservationIgnored private let completer: MKLocalSearchCompleter = MKLocalSearchCompleter()

    override init() {
        super.init()
        completer.delegate = self
        completer.resultTypes = [.address, .pointOfInterest, .query]
    }

    /// Bias results to a 60 km box around `center`.
    func bias(to center: CLLocationCoordinate2D?) {
        guard let c = center else { return }
        completer.region = MKCoordinateRegion(center: c, latitudinalMeters: 60_000, longitudinalMeters: 60_000)
    }

    func setQuery(_ text: String) {
        let t: String = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if t.isEmpty {
            results = []
            failed = false
            completer.queryFragment = ""
        } else {
            completer.queryFragment = t
        }
    }

    /// Turns a suggestion into coordinates. Nil when the search finds nothing.
    func resolve(_ completion: MKLocalSearchCompletion, near center: CLLocationCoordinate2D?) async -> NavPlace? {
        let request: MKLocalSearch.Request = MKLocalSearch.Request(completion: completion)
        if let c = center {
            request.region = MKCoordinateRegion(center: c, latitudinalMeters: 60_000, longitudinalMeters: 60_000)
        }
        do {
            let response: MKLocalSearch.Response = try await MKLocalSearch(request: request).start()
            guard let item = response.mapItems.first else { return nil }
            return NavPlace(coordinate: item.placemark.coordinate,
                            title: completion.title,
                            subtitle: completion.subtitle)
        } catch {
            return nil
        }
    }

    /// A place for a spot on the map: the nearest address, else a plain "Dropped pin".
    static func reverse(_ coordinate: CLLocationCoordinate2D) async -> NavPlace {
        let loc: CLLocation = CLLocation(latitude: coordinate.latitude, longitude: coordinate.longitude)
        var title: String = ""
        var subtitle: String = ""
        if let marks = try? await CLGeocoder().reverseGeocodeLocation(loc), let p = marks.first {
            var street: String = ""
            if let n = p.name { street = n }
            title = street
            var rest: [String] = []
            if let l = p.locality { rest.append(l) }
            if let a = p.administrativeArea { rest.append(a) }
            subtitle = rest.joined(separator: ", ")
        }
        if title.isEmpty { title = "Dropped pin" }
        return NavPlace(coordinate: coordinate, title: title, subtitle: subtitle)
    }

    // MARK: MKLocalSearchCompleterDelegate

    func completerDidUpdateResults(_ completer: MKLocalSearchCompleter) {
        results = completer.results
        failed = false
    }

    func completer(_ completer: MKLocalSearchCompleter, didFailWithError error: Error) {
        failed = true
    }
}
