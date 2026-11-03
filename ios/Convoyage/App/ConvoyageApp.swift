import SwiftUI

@main
struct ConvoyageApp: App {
    @State private var appState: AppState = AppState()

    var body: some Scene {
        WindowGroup {
            ThemedRoot {
                RootView()
                    .environment(appState)
            }
            .task {
                appState.resume()
            }
        }
    }
}
