# Northform

Run and walk recorder for iPhone in Kotlin Multiplatform: heart rate from a Polar Verity Sense
over raw Bluetooth GATT, GPS, a pure recording engine that replays real runs bit for bit.

**Two real recordings, a 4.8 km run and a 3.0 km walk of about 40 minutes each, replay through the
engine and reproduce all 5,143 pace ticks of the reference implementation exactly**, with distance
pinned to ±0.5 m and each run's sensor drop and reconnect counted. 599 tests, none of which needs a
phone or a sensor. 97% of the app is Kotlin; Swift is 406 lines behind a checked seam.

| Claim | Where it comes from |
|---|---|
| 5,143 pace ticks identical (2,613 + 2,530), FNV-1a hash of the whole series | `RealRecordingsTest.pace_and_splits_on_real_recordings_stay_exactly` |
| 4,829.7 m and 2,987.5 m, ±0.5 m | `RealRecordingsTest.real_recording_totals_stay_put` |
| 1 drop, 1 reconnect, its duration, 0 crashes per recording | `ReliabilityPinTest` |
| state rebuilt from the database equals the live state at save checkpoints | `RealRecordingsTest.rebuild_matches_live_on_real_runs` |
| 599 tests: 380 on the JVM, 219 in the iOS simulator | the command under [Run it](#run-it-without-a-phone) |
| 14,890 lines of Kotlin (tests excluded), 406 of Swift | `git ls-files '*.kt' \| grep -vE '/src/[a-zA-Z]*Test/' \| xargs cat \| wc -l`, same for `'*.swift'` |

The reference is the native Swift build this app was ported from: its pace series was hashed and
pinned before the port, and the Kotlin engine has to land on the same hash.

---

## The problem

A heart-rate recording fails in ways that are hard to see after the fact. The sensor drops for 15
seconds, iOS holds GPS fixes while the phone is locked and delivers them late, the app is killed
mid-run. Each one silently bends the numbers: a lost minute inflates average pace, a late sample
counted at its arrival time shifts a zone, a crash leaves a recording that never ends.

None of that can be tested on a phone you carry around a park. So the recording logic is a pure
function, every input carries the time it was taken, and every real run can be saved as a file and
fed back through the same code in a unit test.

```
  Verity Sense            engine (time the input was taken)     reliability log
     │ HR samples ──────▶ │                                     │
     │ ✗ link lost        │ 60.2 s  sensorLost                  │ 1 drop
     │                    │ 65.0 s  hrLost   (5 s, no sample)   │ reconnect 15.0 s
     │ ─ reconnect ─────▶ │ 75.2 s  sensorReconnected           │ HR lost 65.0 → 75.5 s
     │ HR samples ──────▶ │ 75.5 s  hrBack                      │
                          │ 125.25 s pause → 145.25 s resume    │ pause: not moving time
```

That trace is `straight-run-3min`, asserted event by event in
`RealRecordingsTest.straight_run_records_the_right_events`.

---

## Architecture

```
  Polar Verity Sense                         iPhone GPS
        │ GATT 0x180D / 0x180F / 0x180A           │ CLLocationUpdate (Swift-only API)
        ▼                                         ▼
  CoreBluetoothCentral   (Kotlin, iosMain)   LocationForwarder.swift ──▶ LiveLocation (Kotlin)
        │ logic-free delegate                                              │
        ▼                                                                  │
  SensorLink   connect · reconnect 2·4·8·16·30 s · Bluetooth off ≠ drop    │
        │                                                                  │
        └──────── HR samples ──┐          ┌──────── fixes ─────────────────┘
                               ▼          ▼
     ticks, user actions ──▶ Channel mailbox (one coroutine owns the state)
                               │
                               ▼
                    RunReducer: (State, Input) → (State, Effects)     pure, no clock, no I/O
                               │
          ┌────────────────────┼──────────────────────┬──────────────────────┐
          ▼                    ▼                      ▼                      ▼
   SqlRecordingStore     alerts (HR lost,      snapshot ──▶ Compose UI   LiveActivityContent
   SQLDelight, a save    countdown)                                         │ typed callback
   every ~5 s, acked                                                        ▼
   back into the mailbox                                       SwiftUI Live Activity (renders only)
          │
          ▼
   metrics: Karvonen zones · time in zone · TRIMP (Banister) · HR recovery · splits
```

In the simulator `ReplaySensor` and `ReplayLocation` replace the two inputs at the top; nothing
below them changes.

### Modules

The dependency rule is the Gradle module graph (`settings.gradle.kts`): a module sees only what its
build file declares, so breaking the rule is a compile error rather than a review comment.

| Module | Holds | Depends on |
|---|---|---|
| `:core` | model, port interfaces, GATT parsers, metrics | nothing |
| `:engine` | reducer, session mailbox, `SensorLink` | `:core` |
| `:persistence` | SQLDelight store, schema migrations, backup codec | `:core` |
| `:replay` | fixture codec, bundled fixtures, the real-recording tests | `:core`, `:engine`, `:persistence` |
| `:adapters` | iOS CoreBluetooth, location, notifications, Live Activity bridge | `:core`, `:engine` |
| `:composeApp` | Compose Multiplatform UI | `:core`, `:engine`, never `:adapters` |
| `:app` | composition root, the dynamic framework Xcode embeds | all of the above |

`:core`, `:engine`, `:persistence` and `:replay` also compile for the JVM, which is why most of the
suite runs without Xcode.

### The Swift seam

Swift exists only where an Apple API has no Objective-C surface: ActivityKit for the Live Activity,
`CLLocationUpdate` for GPS (its `stationary` flag is lost through the Objective-C delegate), plus the
app shell. Seven files.

`tools/check-seam.sh` enforces it: Swift may reference only an allow-listed set of Kotlin symbols
and may not touch `CBCentralManager`, `UNUserNotificationCenter`, `CLLocationManager`, timers,
SQLite or `UserDefaults`. `tools/check-style.sh` does the same for the UI: no colour, text style,
corner radius or named size outside `ui/theme/` (47 UI files checked).

### Reliability

| Failure | What the code does | Test |
|---|---|---|
| Sensor drops | `SensorLink` reconnects with backoff; drop, reconnect time and HR-lost time go to a per-recording reliability log | `ReliabilityPinTest` |
| Bluetooth switched off | stored as `bluetoothOff`, counted apart from drops | `RealRecordingsTest` |
| Late sample (phone locked) | judged by the time it was taken, never by arrival | `RunReducerTest.a_late_heart_rate_sample_ends_a_loss_only_if_it_would_have_prevented_it` |
| A save fails in SQLite | the batch is retried, nothing lost or stored twice | `SqlWriteFailureTest`, `failed_write_is_retried_without_loss_or_duplicates` |
| App backgrounded mid-save | a background task is held until the batch is confirmed | `BackgroundFlushTest` |
| App killed mid-run | found at launch; Continue stores the gap as a pause (ADR 0009), live state is rebuilt from the database | `CrashResumeAppTest` |
| Schema change | numbered SQLDelight migrations, checked against a snapshot | `MigrationTest`, `MigrationSnapshotTest` |

Why each piece is shaped this way is in [`docs/adr/`](docs/adr/README.md); the vocabulary is in
[`GLOSSARY.md`](GLOSSARY.md).

---

## Run it without a phone

Needs a Mac with Xcode. Tool versions are pinned in `mise.toml` (JDK 21, Gradle 9.2.0, just,
xcodegen).

```bash
mise install          # the pinned JDK, Gradle, just and xcodegen
just test             # :core :engine :replay :persistence on the JVM, no Xcode needed
just test-sim         # Kotlin/Native tests of :app in the iOS simulator (boot order, crash resume)
just check-seam       # the Swift seam rule
just check-style      # the one-theme rule
just open             # generate the Xcode project from iosApp/project.yml and open it
```

In Xcode pick the `Northform` scheme and any iPhone simulator, then ⌘R. The app detects the
simulator and records from a bundled fixture instead of Bluetooth and GPS: tap Run, and
`straight-run-3min` plays in real time through the live engine, store and Live Activity, with its
sensor drop at 60 s and its pause. `NorthformApp.HARDCODED_PROFILE` (in
`app/src/iosMain/.../NorthformApp.kt`) starts the install with a sample profile so it opens on Today;
set it to `false` to go through first-launch Setup.

The full suite and its count:

```bash
./gradlew jvmTest :composeApp:iosSimulatorArm64Test :app:iosSimulatorArm64Test
cat */build/test-results/*/TEST-*.xml | grep -o '<testsuite [^>]*tests="[0-9]*"' \
  | grep -o 'tests="[0-9]*"' | tr -dc '0-9\n' | paste -sd+ - | bc      # 599
```

### On your own iPhone

A free Apple ID is enough. In Xcode set Signing → Team to your Personal Team on both the `Northform`
and `NorthformWidget` targets, change the bundle identifiers if Xcode reports them taken, and run on
the device. Free provisioning expires after 7 days; run from Xcode again to renew. On a device the
app uses the real sensor and GPS, and debug builds write each recording as a replay fixture to
Documents/Recordings, visible in the Files app.

---

## Why not Strava, Garmin Connect, Polar Flow or Apple Workout

For everyday training, use them: they record more sports, sync with watches and services, and are
maintained by teams. They are closed apps, though, and this repo exists to own the mechanism under
the numbers:

| This repo owns | Which means |
|---|---|
| raw GATT over CoreBluetooth and its own reconnect state machine | drop versus Bluetooth off, reconnect backoff and iOS state restoration are code you can read and test |
| a per-recording reliability log | every drop, reconnect time, HR-lost and GPS-lost stretch and crash is stored next to the recording, not smoothed away |
| a pure reducer with timestamped inputs | a recorded run is a file; replaying it through the same code is a unit test, not a trip to the park |
| the metrics in plain Kotlin | Karvonen zones on heart-rate reserve, Banister TRIMP and HR recovery; time in zone and TRIMP are pinned on the real recordings |

Polar's own BLE SDK was the obvious dependency. It reads live heart rate over the same standard
Heart Rate service, so it added nothing for a live recording while bringing a second Bluetooth owner
and a Swift dependency into the core path (ADR 0004). Kable, the KMP Bluetooth library, has no iOS
state restoration or reconnect handling, both of which a locked-screen recording needs.

---

## What I deliberately left out

- **Android.** The module graph is built for it (only `:adapters` and the shell are iOS), but it
  waits until the iOS app is complete.
- **Polar's proprietary protocol.** Setting the sensor's clock and offline sync from the sensor need
  it; a live recording does not, so they belong to a later release.
- **Restore from backup.** Export writes one versioned JSON document; restore needs a conflict rule
  and is not built.
- **GPX/FIT export, imperial units, other zone models.**
- **Recording in the background across a crash.** A crash gap is stored as a pause; resuming is the
  user's choice when the app reopens.
- **History editing, search and charts.** History lists saved recordings; editing a tag or notes
  after saving needs a store write that is not in the current interface.
