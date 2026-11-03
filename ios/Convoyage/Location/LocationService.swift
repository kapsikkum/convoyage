import Foundation
import CoreLocation
import Observation

/// Location. Position is sampled by the view model at about 1 Hz.
/// Background updates are on only while a convoy or a ride recording needs them (`setBackground`), with the blue
/// status-bar indicator. "When in use" plus the `location` background mode keeps updates coming once the app was
/// started in the foreground; "Always" (asked after an explanation screen) also lets iOS wake the app again.
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
        setBackground(false)
    }

    /// True while background updates are on.
    var backgroundOn: Bool = false

    /// Background updates, only while a convoy or a recording needs them. Needs UIBackgroundModes `location`.
    func setBackground(_ on: Bool) {
        if on == backgroundOn { return }
        backgroundOn = on
        manager.allowsBackgroundLocationUpdates = on
        manager.showsBackgroundLocationIndicator = on
    }

    /// Asks for "Always". Call only after the explanation screen.
    func requestAlways() {
        manager.requestAlwaysAuthorization()
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
