# Architecture Decision Records

Public, tracked. One ADR per decision that is hard to reverse, surprising without context and the
result of a real trade-off; anything less stays out. Numbered `NNNN-title.md`, append-only.

## Rules

- **Status:** `proposed` until the decision is proven by code or on the device, then `accepted`.
  A changed decision is a new ADR that supersedes the old one (`superseded by ADR-NNNN`); the old
  one is never rewritten.
- **Revisit if:** every ADR ends with the one condition that would reopen it.
- Reasons are technical or product reasons only.

## Index

| ADR | Decision | Status |
|---|---|---|
| [0001](0001-kotlin-multiplatform-with-a-checked-swift-seam.md) | Kotlin Multiplatform, with Swift only behind a checked seam | accepted |
| [0002](0002-ports-are-interfaces-not-expect-actual.md) | Ports are plain interfaces injected at the edge, not `expect`/`actual` | accepted |
| [0003](0003-pure-reducer-behind-a-channel-mailbox.md) | A recording is a pure reducer run by one coroutine behind a `Channel` mailbox | accepted |
| [0004](0004-kotlin-owns-bluetooth.md) | Kotlin owns Bluetooth over CoreBluetooth; no vendor SDK in v1.0 | accepted |
| [0005](0005-boot-creates-the-bluetooth-central-first.md) | `boot()` is the first statement at launch and creates the Bluetooth central | proposed |
| [0006](0006-swift-gps-forwarder-keeps-the-stationary-flag.md) | A small Swift forwarder delivers GPS so the `stationary` flag survives | accepted |
| [0007](0007-sqldelight-for-storage.md) | SQLDelight for storage | accepted |
| [0008](0008-dynamic-kotlin-framework-embedded-by-xcode.md) | The Kotlin framework is dynamic and Xcode embeds and signs it | accepted |
| [0009](0009-a-crash-gap-is-stored-as-a-pause.md) | A crash gap is stored as a pause | accepted |
