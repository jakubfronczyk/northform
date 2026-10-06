import UIKit
import Northform

/// D9: `boot()` is the FIRST statement. Kotlin creates the CBCentralManager inside it, so a background
/// relaunch's `willRestoreState` lands in a live Kotlin delegate. Nothing else belongs in this file, ever.
final class AppDelegate: NSObject, UIApplicationDelegate {
    private let liveActivity = LiveActivityController()

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        NorthformApp.shared.boot(locationUpdates: LocationForwarder())
        liveActivity.attach()
        return true
    }
}
