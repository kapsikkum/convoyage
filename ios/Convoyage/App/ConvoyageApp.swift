import SwiftUI
import UIKit
import UserNotifications

/// Asks for notifications and hands the APNs token to `AppState`, which registers it with the server.
final class AppDelegate: NSObject, UIApplicationDelegate {
    /// Set when the app's first `.task` runs: `AppState` is created by the SwiftUI app, not by this delegate.
    static weak var appState: AppState?

    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { (_, _) in }
        application.registerForRemoteNotifications()
        return true
    }

    func application(_ application: UIApplication, didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        let hex: String = deviceToken.map { (b: UInt8) -> String in String(format: "%02x", b) }.joined()
        MainActor.assumeIsolated {
            AppDelegate.appState?.pushTokenReceived(hex)
        }
    }

    func application(_ application: UIApplication, didFailToRegisterForRemoteNotificationsWithError error: Error) {
        // Unsigned builds and the simulator always fail here: there is nothing to do about it.
    }
}

@main
struct ConvoyageApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var delegate: AppDelegate
    @State private var appState: AppState = AppState()
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            ThemedRoot {
                RootView()
                    .environment(appState)
            }
            .task {
                let missing: [String] = CvFont.missingFamilies()
                if !missing.isEmpty { NSLog("Convoyage: fonts not registered: %@", missing.joined(separator: ", ")) }
                AppDelegate.appState = appState
                appState.resume()
            }
            .onOpenURL { (url: URL) in
                appState.handleURL(url)
            }
            .onChange(of: scenePhase) { (_: ScenePhase, newPhase: ScenePhase) in
                if newPhase != .active {
                    appState.recorder.persist()
                }
            }
        }
    }
}
