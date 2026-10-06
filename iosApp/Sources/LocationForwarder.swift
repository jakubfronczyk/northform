import CoreLocation
import Northform

/// D10: the one Swift file with a product reason to exist. `CLLocationUpdate.liveUpdates(_:)` is
/// Swift-only and its `stationary` flag feeds the distance rules, so this forwards it RAW to Kotlin.
/// Zero decisions: no filtering, no backoff, no mapping — `LiveLocation` (Kotlin) owns all of that.
/// https://developer.apple.com/documentation/corelocation/cllocationupdate/liveupdates(_:)
final class LocationForwarder: NSObject, LocationUpdates {
    private var task: Task<Void, Never>?

    func start(sink: any LocationSink) {
        stop()
        // Swift 6 can't see that the Kotlin sink is thread-safe (it only does a channel `trySend`);
        // `nonisolated(unsafe)` states that fact instead of adding a lock Kotlin doesn't need.
        nonisolated(unsafe) let sink = sink
        task = Task { @MainActor in
            do {
                for try await update in CLLocationUpdate.liveUpdates(.fitness) {
                    guard let location = update.location else { continue }
                    sink.onUpdate(location: location, stationary: update.stationary)
                }
                sink.onEnded(message: nil)
            } catch {
                sink.onEnded(message: String(describing: error))
            }
        }
    }

    func stop() {
        task?.cancel()
        task = nil
    }
}
