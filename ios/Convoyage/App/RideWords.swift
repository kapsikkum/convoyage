import Foundation

/// The words the UI uses for the vehicle in use: a motorcycle rides, everything else drives.
struct RideWords: Equatable {
    /// True when the vehicle is a motorcycle.
    let bike: Bool

    /// The pure decision, shared by the app and any future test.
    init(bike: Bool) {
        self.bike = bike
    }

    /// The garage's primary kind wins; with no vehicle on file the saved drive / ride choice decides.
    init(primaryKind: String?, rideMode: String) {
        if let kind: String = primaryKind, !kind.isEmpty {
            self.init(bike: kind == "motorcycle")
        } else {
            self.init(bike: rideMode == "ride")
        }
    }

    var noun: String { return bike ? "ride" : "drive" }
    var Noun: String { return bike ? "Ride" : "Drive" }
    var NOUN: String { return bike ? "RIDE" : "DRIVE" }
    var verb: String { return bike ? "ride" : "drive" }
    var Verb: String { return bike ? "Ride" : "Drive" }
    var record: String { return bike ? "Record a ride" : "Record a drive" }
    var track: String { return bike ? "Track ride" : "Track drive" }
    var hud: String { return bike ? "Ride HUD" : "Drive HUD" }
    var last: String { return bike ? "LAST RIDE" : "LAST DRIVE" }
    var tab: String { return bike ? "Ride" : "Drive" }
    /// One person, and people in general, on this vehicle.
    var person: String { return bike ? "rider" : "driver" }
    var Person: String { return bike ? "Rider" : "Driver" }
    var people: String { return bike ? "riders" : "drivers" }
    var People: String { return bike ? "Riders" : "Drivers" }
    var recap: String { return bike ? "RIDE RECAP" : "DRIVE RECAP" }
    var photos: String {
        return bike
            ? "NO PHOTOS YET. RIDE PHOTOS LIVE ON EACH RIDE'S RECAP."
            : "NO PHOTOS YET. DRIVE PHOTOS LIVE ON EACH DRIVE'S RECAP."
    }
    var showsLean: Bool { return bike }
}
