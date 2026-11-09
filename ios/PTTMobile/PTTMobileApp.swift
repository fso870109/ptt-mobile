import SwiftUI

@main
struct PTTMobileApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) var appDelegate

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(PttEngine.shared)
        }
    }
}

struct RootView: View {
    @EnvironmentObject var engine: PttEngine

    var body: some View {
        if engine.token.isEmpty {
            LoginView()
        } else {
            MainView()
        }
    }
}
