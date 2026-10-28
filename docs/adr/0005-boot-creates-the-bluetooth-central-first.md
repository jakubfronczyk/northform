---
status: proposed
date: 2026-10-06
amended: 2026-10-28
---

# `boot()` is the first statement at launch and creates the Bluetooth central

Swift's `didFinishLaunching` calls Kotlin `NorthformApp.boot()` before anything else, and `boot()`
creates the `CBCentralManager` with a constant restore identifier. When iOS relaunches the app in the
background to deliver a Bluetooth event, `willRestoreState` is the first callback, so a live Kotlin
delegate must already exist to receive it. The identifier is a constant because scene-based apps
always get `nil` launch options. Nothing resumes recording in the background: continuing an
interrupted recording is the user's choice when the app opens.

## Amendment (2026-10-28): only once iOS has an answer about Bluetooth

Creating a `CBCentralManager` is what makes iOS show its Bluetooth prompt, so creating it at every
launch put the prompt over Setup's first step on a fresh install. `boot()` now reads
`CBManager.authorization` (a class property, which never prompts) and creates the central at once
only when it is decided (allowed, denied or restricted). While it is not determined, the central is
created when the runner taps Bluetooth's Allow in Setup's permissions step, or opens the Pair step or
the Sensor sheet, which cannot work without the radio. The restore identifier is passed whenever the
central is created.

Restoration is unaffected: it can only matter for an install with a paired sensor, and no sensor can
be paired before iOS has an answer, so every install that could be relaunched for a Bluetooth event
still gets its central inside `boot()`.

Considered: keeping the prompt at launch (a cold system dialog before the app has said what Bluetooth
is for); creating the central without a restore identifier first and a second one later (two managers,
and restoration lost for the install that was just authorized until the next launch).

Consequences: until the central exists, `CoreBluetoothCentral` drops every command (the link re-issues
what it still wants on the first power-on) and knows no peripheral, so Today and the gate show no
connected sensor. If the runner resets Bluetooth permission in iOS Settings while a sensor is
paired, the next launch waits for the Sensor sheet or Setup to ask again.

Proposed, not accepted: restoration after the system terminates the app has not yet been observed on
a device, nor has the prompt's timing on a fresh install.

Revisit if: the on-device test shows restoration does not reach the Kotlin delegate, or iOS changes
the relaunch order.
