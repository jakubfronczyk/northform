---
status: accepted
date: 2026-10-06
---

# Kotlin owns Bluetooth over CoreBluetooth; no vendor SDK in v1.0

The heart-rate sensor is read over the standard Bluetooth GATT services (Heart Rate `0x180D`,
Battery `0x180F`, Device Information `0x180A`), from Kotlin through `platform.CoreBluetooth`. The
vendor's own SDK reads heart rate over the same standard service, so it adds nothing for live heart
rate. Pure GATT parsers live in `:core`, the connection state machine (connect, reconnect with
backoff, Bluetooth off versus drop, battery) in `:engine`, and a logic-free CoreBluetooth delegate in
`iosMain`. The same state machine will drive Android's `BluetoothGatt`.

Setting the sensor's clock is the only feature that needs the vendor protocol, and a live recording
never reads that clock, so it moves to the release that adds offline sync from the sensor.

## Considered options

- **Vendor SDK behind a Swift wrapper:** a second Bluetooth owner, a Swift dependency in the core
  path, and its release cycle gating Xcode upgrades.
- **Kable (KMP Bluetooth library):** no iOS state restoration and no reconnect handling, both of which
  a locked-screen recording needs.

## Consequences

- When offline sync arrives, the app must decide who owns the connection while the vendor SDK runs.

Revisit if: a feature needs the vendor's proprietary streams (raw PPI, accelerometer) and a Kotlin
implementation of that protocol proves unreliable.
