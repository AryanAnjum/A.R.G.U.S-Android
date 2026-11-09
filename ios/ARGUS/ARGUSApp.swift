import SwiftUI

@main
struct ARGUSApp: App {
    @StateObject private var model = ArgusViewModel()
    @Environment(\.scenePhase) private var phase

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(model)
                .preferredColorScheme(.dark)
                .onAppear {
                    UIApplication.shared.isIdleTimerDisabled = true  // the screen stays on while ARGUS watches
                    model.connect()
                }
                .onChange(of: phase) { p in
                    if p == .active { model.connect() }
                }
        }
    }
}
