import ActivityKit
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

    func onUpdate(content: LiveActivityContent) {
        guard let activity else { return }
        Task { await activity.update(.init(state: Self.state(content), staleDate: nil)) }
    }

    func onEnd(content: LiveActivityContent) {
        guard let activity else { return }
        Task { await activity.end(.init(state: Self.state(content), staleDate: nil), dismissalPolicy: .default) }
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
