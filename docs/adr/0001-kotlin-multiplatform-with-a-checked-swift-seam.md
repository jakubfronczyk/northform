---
status: accepted
date: 2026-10-06
---

# Kotlin Multiplatform, with Swift only behind a checked seam

The app is Kotlin Multiplatform with a Compose Multiplatform UI: domain logic, Bluetooth, GPS
handling, storage, alerts and screens are Kotlin, shared by iOS and Android. Swift exists only where
an Apple API has no Objective-C surface (ActivityKit for the Live Activity, `CLLocationUpdate` for
GPS) plus the app shell. The Live Activity follows the same split: Kotlin computes and throttles its
content, and a SwiftUI view only renders it.

The seam is a rule, not an aspiration: Swift may reference only an allow-listed set of Kotlin symbols
and may not touch Bluetooth, notifications, `CLLocationManager`, timers or SQLite. `tools/check-seam.sh`
fails CI otherwise. Adding a Swift file is a recorded decision.

## Considered options

- **Native app per platform (SwiftUI + Jetpack Compose):** two copies of the recording logic, the
  part that must be identical and is hardest to test.
- **Shared Kotlin logic, native UI per platform:** keeps two UI codebases for screens that are the
  same on both platforms.
- **Swift owns platform I/O, Kotlin owns logic:** most of the platform APIs needed (CoreBluetooth,
  `UNUserNotificationCenter`, `CLLocationManager`) are Objective-C and callable from Kotlin/Native, so
  this would put code in Swift with no reason.

## Consequences

- Android reuses everything except a thin adapter layer.
- The Kotlin framework crosses into Swift through hand-written callback interfaces, not `Flow` or
  `suspend` (they export to Swift as opaque types).

Revisit if: an iOS feature v1.x needs (e.g. a watch app) has no Objective-C API and cannot sit behind
a logic-free Swift adapter.
