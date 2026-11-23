import SwiftUI

@main
struct Gen5WDigitalControlApp: App {
    @StateObject private var vault = KeychainVault()

    var body: some Scene {
        WindowGroup {
            MainDashboardView(vault: vault)
                .preferredColorScheme(.dark)
        }
    }
}
