import Foundation
import CoreLocation
import Observation

/// The two saved places the Drive dock offers one tap away (Android's SavedSlot).
enum SavedSlot: String, CaseIterable {
    case home
    case work

    var label: String {
        switch self {
        case .home: return "Home"
        case .work: return "Work"
        }
    }

    /// Where this slot's place sits in UserDefaults.
    var key: String {
        return "places.slot." + rawValue
    }
}

/// How a place is written to disk: NavPlace itself is not Codable.
private struct StoredPlace: Codable {
    var name: String
    var detail: String
    var lat: Double
    var lng: Double

    init(_ p: NavPlace) {
        name = p.title
        detail = p.subtitle
        lat = p.coordinate.latitude
        lng = p.coordinate.longitude
    }

    var place: NavPlace {
        return NavPlace(coordinate: CLLocationCoordinate2D(latitude: lat, longitude: lng),
                        title: name,
                        subtitle: detail)
    }
}

/// Saved Home and Work places and recent destinations, on this device only (port of Android's PlacesStore).
@Observable
final class PlacesStore {
    private(set) var saved: [SavedSlot: NavPlace] = [:]
    private(set) var recent: [NavPlace] = []

    /// A dropped pin is not worth a one-tap row.
    static let droppedPin: String = "Dropped pin"
    /// How many recent destinations are kept.
    static let maxRecent: Int = 8
    /// An earlier entry this close to a new one is the same place.
    static let samePlaceM: Double = 50

    private static let recentKey: String = "places.recent"

    @ObservationIgnored private let defaults: UserDefaults

    init() {
        let store: UserDefaults = UserDefaults.standard
        defaults = store
        var bySlot: [SavedSlot: NavPlace] = [:]
        for slot in SavedSlot.allCases {
            if let place: NavPlace = PlacesStore.load(store, slot.key) { bySlot[slot] = place }
        }
        saved = bySlot
        recent = PlacesStore.loadList(store, PlacesStore.recentKey)
    }

    func save(_ slot: SavedSlot, _ place: NavPlace) {
        if let data = try? JSONEncoder().encode(StoredPlace(place)) {
            defaults.set(data, forKey: slot.key)
        }
        saved[slot] = place
    }

    func clear(_ slot: SavedSlot) {
        defaults.removeObject(forKey: slot.key)
        saved[slot] = nil
    }

    /// Puts the place first, drops any earlier entry within 50 m of it and keeps at most eight.
    func remember(_ place: NavPlace) {
        if place.title == PlacesStore.droppedPin { return }
        var list: [NavPlace] = [place]
        list.append(contentsOf: recent.filter { (r: NavPlace) -> Bool in
            let metres: Double = GeoMath.distanceM(r.coordinate.latitude, r.coordinate.longitude,
                                                  place.coordinate.latitude, place.coordinate.longitude)
            return metres >= PlacesStore.samePlaceM
        })
        recent = Array(list.prefix(PlacesStore.maxRecent))
        persistRecent()
    }

    func forget(_ place: NavPlace) {
        recent.removeAll { (r: NavPlace) -> Bool in return r == place }
        persistRecent()
    }

    private func persistRecent() {
        let stored: [StoredPlace] = recent.map { (p: NavPlace) -> StoredPlace in return StoredPlace(p) }
        if let data = try? JSONEncoder().encode(stored) {
            defaults.set(data, forKey: PlacesStore.recentKey)
        }
    }

    private static func load(_ store: UserDefaults, _ key: String) -> NavPlace? {
        guard let data = store.data(forKey: key) else { return nil }
        guard let stored = try? JSONDecoder().decode(StoredPlace.self, from: data) else { return nil }
        return stored.place
    }

    private static func loadList(_ store: UserDefaults, _ key: String) -> [NavPlace] {
        guard let data = store.data(forKey: key) else { return [] }
        guard let stored = try? JSONDecoder().decode([StoredPlace].self, from: data) else { return [] }
        return stored.map { (s: StoredPlace) -> NavPlace in return s.place }
    }
}
