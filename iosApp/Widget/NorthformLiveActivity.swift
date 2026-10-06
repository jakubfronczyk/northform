import ActivityKit
import SwiftUI
import WidgetKit

/// Render only (D11, R30: total time, distance, split pace, HR; no buttons). The clock is
/// `Text(timerInterval:)` so the lock screen ticks without updates from the app.
@main
struct NorthformWidgetBundle: WidgetBundle {
    var body: some Widget {
        NorthformLiveActivity()
    }
}

struct NorthformLiveActivity: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: NorthformAttributes.self) { context in
            LockScreenView(state: context.state)
                .padding()
        } dynamicIsland: { context in
            DynamicIsland {
                DynamicIslandExpandedRegion(.center) { LockScreenView(state: context.state) }
            } compactLeading: {
                Text(context.state.hrBpm.map { "\($0)" } ?? "—")
            } compactTrailing: {
                Text(Self.km(context.state.distanceM))
            } minimal: {
                Text(context.state.hrBpm.map { "\($0)" } ?? "—")
            }
        }
    }

    static func km(_ m: Double) -> String { String(format: "%.2f km", m / 1000) }
}

private struct LockScreenView: View {
    let state: NorthformAttributes.ContentState

    var body: some View {
        HStack(alignment: .firstTextBaseline) {
            VStack(alignment: .leading) {
                if state.done {
                    Text(Self.hms(state.totalTimeSec)).font(.title2.monospacedDigit())
                } else {
                    Text(timerInterval: state.startedAt...Date.distantFuture, countsDown: false)
                        .font(.title2.monospacedDigit())
                }
                Text(NorthformLiveActivity.km(state.distanceM)).font(.headline)
            }
            Spacer()
            VStack(alignment: .trailing) {
                Text(state.hrBpm.map { "\($0) bpm" } ?? (state.hrLost ? "HR lost" : "—"))
                Text(state.splitPaceSecPerKm.map(Self.pace) ?? (state.gpsLost ? "GPS lost" : "—"))
            }
        }
    }

    static func pace(_ secPerKm: Int) -> String { String(format: "%d:%02d /km", secPerKm / 60, secPerKm % 60) }
    static func hms(_ s: Int) -> String { s >= 3600 ? String(format: "%d:%02d:%02d", s / 3600, s % 3600 / 60, s % 60) : String(format: "%d:%02d", s / 60, s % 60) }
}
