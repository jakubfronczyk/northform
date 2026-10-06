import ActivityKit
import Foundation
import Northform

/// D11: maps the three Kotlin callbacks to ActivityKit. ActivityKit has no Objective-C API, so this is
/// irreducibly Swift — but it decides nothing: every field comes from Kotlin's `LiveActivityContent`.
/// https://developer.apple.com/documentation/activitykit/displaying-live-data-with-live-activities
final class LiveActivityController: NSObject, LiveActivityListener {
    private var activity: Activity<NorthformAttributes>?
    private var cancellable: Cancellable?

    func attach() {
        cancellable = LiveActivityBridge.shared.observe(listener: self)
    }

    func onStart(content: LiveActivityContent) {
        // Must be requested from the foreground (Apple) — Kotlin only calls onStart when a run starts from the screen.
        guard ActivityAuthorizationInfo().areActivitiesEnabled else { return }
        do {
            activity = try Activity.request(
                attributes: NorthformAttributes(),
                content: .init(state: Self.state(content), staleDate: nil)
            )
        } catch {
            // TODO(spike): read this in Console.app during step 9; on a free team a provisioning refusal shows up here (Q5)
            print("northform/liveactivity request failed: \(error)")
        }
    }

    // Swift 6 strict concurrency: capture locals, not `self`, in the detached Task (the Kotlin bridge
    // calls these from a background coroutine; ActivityKit's async API is fine from any task).
    // `Activity` isn't declared Sendable, although Apple's own samples await it from any task;
    // `nonisolated(unsafe)` records that rather than inventing an actor the seam doesn't need.
    func onUpdate(content: LiveActivityContent) {
        guard let current = activity else { return }
        nonisolated(unsafe) let activity = current
        let state = Self.state(content)
        Task { await activity.update(.init(state: state, staleDate: nil)) }
    }

    func onEnd(content: LiveActivityContent) {
        guard let current = activity else { return }
        nonisolated(unsafe) let activity = current
        let state = Self.state(content)
        Task { await activity.end(.init(state: state, staleDate: nil), dismissalPolicy: .default) }
        self.activity = nil
    }

    /// 1:1 field copy. `KotlinInt?` → `Int?` is a type conversion, not a decision.
    private static func state(_ c: LiveActivityContent) -> NorthformAttributes.ContentState {
        .init(
            startedAt: Date(timeIntervalSince1970: TimeInterval(c.startedAtEpochMs) / 1000),
            totalTimeSec: Int(c.totalTimeSec),
            distanceM: c.distanceM,
            splitPaceSecPerKm: c.splitPaceSecPerKm.map { Int(truncating: $0) },
            hrBpm: c.hrBpm.map { Int(truncating: $0) },
            hrLost: c.hrLost,
            gpsLost: c.gpsLost,
            done: c.phase == .done
        )
    }
}
