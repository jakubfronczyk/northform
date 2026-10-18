import ActivityKit
import Foundation

/// Mirror of Kotlin's `LiveActivityContent` (D11). Compiled into BOTH the app and the widget extension
/// (project.yml) — ActivityKit requires the same type on both sides. Data only.
struct NorthformAttributes: ActivityAttributes {
    struct ContentState: Codable, Hashable {
        var startedAt: Date
        var totalTimeSec: Int
        var distanceM: Double
        var paceSecPerKm: Int?
        var hrBpm: Int?
        var hrLost: Bool
        var gpsLost: Bool
        /// Zone 0–5 and its name (D23, D29); nil with `hrBpm`.
        var zoneNumber: Int?
        var zoneName: String?
        /// "Run" / "Walk" (D35).
        var typeName: String
        var paused: Bool
        var done: Bool
    }
}
