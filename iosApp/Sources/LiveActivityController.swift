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
        endOrphans()
    }

    /// A run that died with the process (crash, system kill) leaves its activity on the lock screen, frozen. Only this
    /// process can end it, so every launch sweeps what the previous one left. Kotlin decides nothing here: there is no
    /// run yet to decide with (D9: nothing resumes on its own).
    private func endOrphans() {
        let orphans = Activity<NorthformAttributes>.activities
        guard !orphans.isEmpty else { return }
        Task {
            for orphan in orphans {
                nonisolated(unsafe) let orphan = orphan
                await orphan.end(nil, dismissalPolicy: .immediate)
            }
        }
    }

    func onStart(content: LiveActivityContent) {
        // Must be requested from the foreground (Apple) — Kotlin only calls onStart when a run starts from the screen.
        guard ActivityAuthorizationInfo().areActivitiesEnabled else { return }
        if activity != nil { onEnd(content: content) } // one lock screen: a new run replaces a leftover
        do {
            activity = try Activity.request(
                attributes: NorthformAttributes(),
                content: .init(state: Self.state(content), staleDate: nil)
            )
        } catch {
            // Read in Console.app (subsystem com.jakubfronczyk.northform); on a free team a provisioning refusal shows up here (Q5)
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
            paceSecPerKm: c.paceSecPerKm.map { Int(truncating: $0) },
            hrBpm: c.hrBpm.map { Int(truncating: $0) },
            hrLost: c.hrLost,
            gpsLost: c.gpsLost,
            done: c.phase == .done
        )
    }
}
