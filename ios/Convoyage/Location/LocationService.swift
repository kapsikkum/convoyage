import Foundation
import CoreLocation
import Observation

/// Foreground location. Position is sampled by the view model at about 1 Hz.
/// TODO(background): background location needs UIBackgroundModes plus Always authorisation.
@Observable
final class LocationService: NSObject, CLLocationManagerDelegate {
    var latest: CLLocation?
    var authorization: CLAuthorizationStatus = .notDetermined
    /// Called for every raw fix (the ride recorder filters them itself).
    @ObservationIgnored var onFix: ((CLLocation) -> Void)?

    @ObservationIgnored private let manager: CLLocationManager = CLLocationManager()

    override init() {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyBest
        manager.distanceFilter = kCLDistanceFilterNone
        manager.activityType = .otherNavigation
        manager.pausesLocationUpdatesAutomatically = false
        authorization = manager.authorizationStatus
    }

    func start() {
        if manager.authorizationStatus == .notDetermined {
            manager.requestWhenInUseAuthorization()
        }
        manager.startUpdatingLocation()
    }

    func stop() {
        manager.stopUpdatingLocation()
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        authorization = manager.authorizationStatus
        if authorization == .authorizedWhenInUse || authorization == .authorizedAlways {
            manager.startUpdatingLocation()
        }
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        if let last = locations.last {
            latest = last
        }
        for loc in locations {
            onFix?(loc)
        }
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        // Ignored: a transient failure just means no new fix.
    }
}
