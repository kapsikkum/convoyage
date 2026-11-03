import Foundation
import Observation

@Observable
@MainActor
final class AppState {
    var displayName: String
    var isSignedIn: Bool
    var tab: AppTab = .home
    var showRide: Bool = false

    let userId: String
    let convoy: ConvoyViewModel = ConvoyViewModel()

    @ObservationIgnored private let defaults: UserDefaults = UserDefaults.standard

    init() {
        let store: UserDefaults = UserDefaults.standard
        if let existing = store.string(forKey: "userId") {
            userId = existing
        } else {
            let fresh: String = UUID().uuidString.lowercased()
            store.set(fresh, forKey: "userId")
            userId = fresh
        }
        displayName = store.string(forKey: "displayName") ?? ""
        isSignedIn = !(store.string(forKey: "displayName") ?? "").isEmpty
    }

    /// Dev sign-in: a name and the locally generated UUID.
    /// TODO(auth): replace with the real sign-in flow when the server has one.
    func signIn(name: String) {
        let trimmed: String = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        displayName = trimmed
        defaults.set(trimmed, forKey: "displayName")
        isSignedIn = true
        convoy.connect(userId: userId, name: trimmed)
    }

    func resume() {
        if isSignedIn {
            convoy.connect(userId: userId, name: displayName)
        }
    }

    func signOut() {
        convoy.disconnect()
        defaults.removeObject(forKey: "displayName")
        displayName = ""
        isSignedIn = false
        tab = .home
        showRide = false
    }
}
