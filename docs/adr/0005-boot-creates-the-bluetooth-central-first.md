---
status: proposed
date: 2026-10-06
---

# `boot()` is the first statement at launch and creates the Bluetooth central

Swift's `didFinishLaunching` calls Kotlin `NorthformApp.boot()` before anything else, and `boot()`
creates the `CBCentralManager` with a constant restore identifier. When iOS relaunches the app in the
background to deliver a Bluetooth event, `willRestoreState` is the first callback, so a live Kotlin
delegate must already exist to receive it. The identifier is a constant because scene-based apps
always get `nil` launch options. Nothing resumes recording in the background: continuing an
interrupted recording is the user's choice when the app opens.

Proposed, not accepted: restoration after the system terminates the app has not yet been observed on
a device.

Revisit if: the on-device test shows restoration does not reach the Kotlin delegate, or iOS changes
the relaunch order.
