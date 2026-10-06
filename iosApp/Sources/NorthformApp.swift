import SwiftUI

/// The Swift shell (D16): no logic, three Kotlin symbols. SwiftUI lifecycle is scene-based, which is
/// why the BLE restore identifier is a Kotlin constant and never read from launchOptions (always nil
/// for scene apps — Apple, CBCentralManagerOptionRestoreIdentifierKey).
@main
struct NorthformIOSApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate

    var body: some Scene {
        WindowGroup {
            ComposeHost()
                .ignoresSafeArea()
        }
    }
}
