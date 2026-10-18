---
status: accepted
date: 2026-10-06
---

# A small Swift forwarder delivers GPS so the `stationary` flag survives

Location updates come from `CLLocationUpdate.liveUpdates(.fitness)`, a Swift-only API, through a
~30-line Swift forwarder that hands each location and its `stationary` flag to a Kotlin callback.
Everything else (authorization, restart backoff, background keep-alive, mapping to the domain fix) is
Kotlin. The flag decides whether distance is counted while standing still; without it, live distance
would differ from the totals the fixtures pin. This sets the minimum iOS version to 18, where the
flag exists.

## Considered options

- **`CLLocationManager` delegate in pure Kotlin:** zero Swift, but every fix arrives with no
  stationary information, so live distance silently diverges while fixture replay still passes.

Revisit if: `CLLocationManager` exposes stationary detection, or the distance rules stop depending on
it.
