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
    @AppStorage("colorScheme") private var colorSchemePref = "system"

    var body: some View {
        Group {
            if engine.token.isEmpty {
                LoginView()
            } else {
                MainView()
            }
        }
        .preferredColorScheme(colorSchemePref == "light" ? .light : colorSchemePref == "dark" ? .dark : nil)
    }
}
