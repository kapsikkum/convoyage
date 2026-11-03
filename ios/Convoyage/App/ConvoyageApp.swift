import SwiftUI

@main
struct ConvoyageApp: App {
    @State private var appState: AppState = AppState()
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            ThemedRoot {
                RootView()
                    .environment(appState)
            }
            .task {
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
