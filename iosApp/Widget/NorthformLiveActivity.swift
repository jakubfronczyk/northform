import ActivityKit
import SwiftUI
import WidgetKit

/// Render only (D11, R30: time, distance, the shown current pace (D38), HR, zone; no buttons). The clock is
/// `Text(timerInterval:)` so the lock screen ticks without updates from the app.
///
/// DEV EXPLORATION (2026-10-18): a bold layout using the full 160 pt iOS allows, to see what the lock screen can
/// carry. Not the LockScreen artboard (R30) — Jakub reviews all styling later; this is for building and testing.
@main
struct NorthformWidgetBundle: WidgetBundle {
    var body: some Widget {
        NorthformLiveActivity()
    }
}

struct NorthformLiveActivity: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: NorthformAttributes.self) { context in
            LockScreenCard(state: context.state)
                .activityBackgroundTint(Palette.bg)
                .activitySystemActionForegroundColor(Palette.text)
        } dynamicIsland: { context in
            let s = context.state
            return DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    Clock(state: s, size: 34).padding(.leading, 4)
                }
                DynamicIslandExpandedRegion(.trailing) {
                    HStack(spacing: 4) {
                        Image(systemName: "heart.fill").font(.system(size: 14, weight: .bold))
                        Text(s.hrBpm.map { "\($0)" } ?? "—").font(Fonts.number(30))
                    }
                    .foregroundStyle(Palette.zone(s.zoneNumber))
                    .padding(.trailing, 4)
                }
                DynamicIslandExpandedRegion(.bottom) {
                    HStack(alignment: .firstTextBaseline) {
                        Metric(value: Format.pace(s.paceSecPerKm), unit: "/km", size: 22)
                        Spacer()
                        Metric(value: Format.km(s.distanceM), unit: "km", size: 22)
                        Spacer()
                        ZoneBar(zone: s.zoneNumber, height: 14).frame(width: 90)
                    }
                    .padding(.horizontal, 4)
                }
            } compactLeading: {
                HStack(spacing: 3) {
                    Image(systemName: "figure.run").font(.system(size: 12, weight: .bold))
                    Text(Format.pace(s.paceSecPerKm)).font(Fonts.number(15))
                }
                .foregroundStyle(Palette.accent)
            } compactTrailing: {
                HStack(spacing: 3) {
                    Image(systemName: "heart.fill").font(.system(size: 11, weight: .bold))
                    Text(s.hrBpm.map { "\($0)" } ?? "—").font(Fonts.number(15))
                }
                .foregroundStyle(Palette.zone(s.zoneNumber))
            } minimal: {
                Text(s.hrBpm.map { "\($0)" } ?? "—").font(Fonts.number(13)).foregroundStyle(Palette.zone(s.zoneNumber))
            }
        }
    }
}

// MARK: - Lock screen (≤ 160 pt)

private struct LockScreenCard: View {
    let state: NorthformAttributes.ContentState

    var body: some View {
        let zone = Palette.zone(state.zoneNumber)
        ZStack(alignment: .leading) {
            // A zone-tinted wash from the left so the whole card says "how hard" before any number is read.
            LinearGradient(colors: [zone.opacity(0.42), zone.opacity(0.10), .clear], startPoint: .leading, endPoint: .trailing)
            Rectangle().fill(zone).frame(width: 5) // the zone stripe
            VStack(alignment: .leading, spacing: 10) {
                header
                HStack(alignment: .firstTextBaseline, spacing: 0) {
                    Clock(state: state, size: 56)
                    Spacer(minLength: 8)
                    VStack(alignment: .trailing, spacing: 2) {
                        HStack(spacing: 5) {
                            Image(systemName: "heart.fill").font(.system(size: 16, weight: .heavy)).foregroundStyle(zone)
                            Text(state.hrBpm.map { "\($0)" } ?? "—").font(Fonts.number(44)).foregroundStyle(state.hrBpm == nil ? Palette.text2 : Palette.text)
                        }
                        Text(state.zoneName?.uppercased() ?? (state.hrLost ? "SENSOR LOST" : "NO SENSOR"))
                            .font(.system(size: 11, weight: .heavy)).tracking(1.2)
                            .foregroundStyle(state.hrLost ? Palette.warn : zone)
                    }
                }
                HStack(alignment: .firstTextBaseline, spacing: 18) {
                    Metric(value: Format.pace(state.paceSecPerKm), unit: "/km", size: 28, dim: state.gpsLost || state.paceSecPerKm == nil)
                    Metric(value: Format.km(state.distanceM), unit: "km", size: 28, dim: state.gpsLost)
                    Spacer()
                    ZoneBar(zone: state.zoneNumber, height: 18).frame(width: 118)
                }
            }
            .padding(.leading, 19)
            .padding(.trailing, 16)
            .padding(.vertical, 14)
        }
        .frame(height: 160)
        .opacity(state.paused ? 0.72 : 1)
    }

    private var header: some View {
        HStack(spacing: 8) {
            HStack(spacing: 5) {
                Image(systemName: state.typeName == "Walk" ? "figure.walk" : "figure.run").font(.system(size: 12, weight: .heavy))
                Text(state.typeName.uppercased()).font(.system(size: 12, weight: .heavy)).tracking(1.4)
            }
            .foregroundStyle(Palette.accent)
            if state.paused { Pill("PAUSED", color: Palette.warn) }
            if state.done { Pill("DONE", color: Palette.ok) }
            Spacer()
            if state.gpsLost { Pill("GPS LOST", color: Palette.warn) }
        }
    }
}

// MARK: - Pieces

/// The run's clock: ticking from `startedAt` while live (`totalTime` is wall time, pauses included — the snapshot's
/// definition), frozen at the final total once done.
private struct Clock: View {
    let state: NorthformAttributes.ContentState
    let size: CGFloat

    var body: some View {
        Group {
            if state.done {
                Text(Format.hms(state.totalTimeSec))
            } else {
                Text(timerInterval: state.startedAt...Date.distantFuture, countsDown: false)
            }
        }
        .font(Fonts.number(size))
        .foregroundStyle(Palette.text)
        .lineLimit(1)
        .minimumScaleFactor(0.6)
    }
}

private struct Metric: View {
    let value: String
    let unit: String
    let size: CGFloat
    var dim = false

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 3) {
            Text(value).font(Fonts.number(size)).foregroundStyle(dim ? Palette.text2 : Palette.text)
            Text(unit).font(.system(size: 12, weight: .semibold)).foregroundStyle(Palette.text2)
        }
    }
}

/// Six capsules, Z0 → Z5, lit up to the current zone in its colour, each a step taller than the last.
private struct ZoneBar: View {
    let zone: Int?
    let height: CGFloat

    var body: some View {
        HStack(alignment: .bottom, spacing: 3) {
            ForEach(0..<6, id: \.self) { z in
                let lit = zone.map { z <= $0 } ?? false
                Capsule()
                    .fill(lit ? Palette.zone(zone) : Palette.unlit)
                    .frame(height: height * (0.35 + 0.13 * CGFloat(z)))
            }
        }
    }
}

private struct Pill: View {
    let text: String
    let color: Color
    init(_ text: String, color: Color) { self.text = text; self.color = color }

    var body: some View {
        Text(text).font(.system(size: 10, weight: .heavy)).tracking(1)
            .foregroundStyle(Palette.bg)
            .padding(.horizontal, 7).padding(.vertical, 3)
            .background(color, in: Capsule())
    }
}

// MARK: - Tokens (mirror `composeApp/.../ui/Theme.kt`; the widget links no Kotlin, D16)

private enum Palette {
    static let bg = Color(red: 0x16 / 255, green: 0x17 / 255, blue: 0x18 / 255)
    static let text = Color(red: 0xF4 / 255, green: 0xF4 / 255, blue: 0xF2 / 255)
    static let text2 = Color(red: 0xA9 / 255, green: 0xAA / 255, blue: 0xA6 / 255)
    static let accent = Color(red: 0xFF / 255, green: 0x6B / 255, blue: 0x2C / 255)
    static let warn = Color(red: 0xE9 / 255, green: 0xC4 / 255, blue: 0x6A / 255)
    static let ok = Color(red: 0x5F / 255, green: 0xD0 / 255, blue: 0x8A / 255)
    static let unlit = Color.white.opacity(0.14)
    /// `Theme.zoneColors`, Z0 … Z5. Grey when there is no zone.
    private static let zones: [Color] = [
        Color(red: 0x6B / 255, green: 0x6C / 255, blue: 0x70 / 255),
        Color(red: 0x9C / 255, green: 0xA3 / 255, blue: 0xAF / 255),
        Color(red: 0x3B / 255, green: 0x82 / 255, blue: 0xF6 / 255),
        Color(red: 0x22 / 255, green: 0xC5 / 255, blue: 0x5E / 255),
        Color(red: 0xF5 / 255, green: 0x9E / 255, blue: 0x0B / 255),
        Color(red: 0xEF / 255, green: 0x44 / 255, blue: 0x44 / 255),
    ]
    static func zone(_ n: Int?) -> Color { n.map { zones[max(0, min(5, $0))] } ?? zones[0] }
}

private enum Fonts {
    /// Heavy, rounded, tabular digits: numbers glanceable from an armband. The app bundles no Archivo (yet).
    static func number(_ size: CGFloat) -> Font { .system(size: size, weight: .heavy, design: .rounded).monospacedDigit() }
}

private enum Format {
    static func km(_ m: Double) -> String { String(format: "%.2f", m / 1000) }
    static func pace(_ secPerKm: Int?) -> String { secPerKm.map { String(format: "%d:%02d", $0 / 60, $0 % 60) } ?? "—:——" }
    static func hms(_ s: Int) -> String { s >= 3600 ? String(format: "%d:%02d:%02d", s / 3600, s % 3600 / 60, s % 60) : String(format: "%d:%02d", s / 60, s % 60) }
}
