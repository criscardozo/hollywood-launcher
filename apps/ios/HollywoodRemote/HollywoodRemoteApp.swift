import SwiftUI

@main
struct HollywoodRemoteApp: App {
    @State private var model = RemoteModel()
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(model)
                .preferredColorScheme(.dark)
                .tint(Theme.accent)
        }
        .onChange(of: scenePhase) { _, phase in
            // The TV drops idle clients; reconnect whenever the app comes back.
            if phase == .active { Task { await model.connectIfNeeded() } }
            if phase == .background { model.disconnect() }
        }
    }
}

struct RootView: View {
    @Environment(RemoteModel.self) private var model

    var body: some View {
        switch model.phase {
        case .noTV, .pairing, .enteringCode:
            PairingView()
        default:
            RemoteView()
        }
    }
}
