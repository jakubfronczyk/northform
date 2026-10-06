import ActivityKit
import Foundation

/// Mirror of Kotlin's `LiveActivityContent` (D11). Compiled into BOTH the app and the widget extension
/// (project.yml) — ActivityKit requires the same type on both sides. Data only.
struct NorthformAttributes: ActivityAttributes {
    struct ContentState: Codable, Hashable {
        var startedAt: Date
        var totalTimeSec: Int
        var distanceM: Double
        var splitPaceSecPerKm: Int?
        var hrBpm: Int?
        var hrLost: Bool
        var gpsLost: Bool
        var done: Bool
    }
}
