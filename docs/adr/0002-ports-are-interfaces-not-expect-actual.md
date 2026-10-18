---
status: accepted
date: 2026-10-06
---

# Ports are plain interfaces injected at the edge, not `expect`/`actual`

Every boundary the core talks through (heart-rate sensor, location, recording store, alerts, wall
clock) is a plain Kotlin `interface` in `:core`, and each platform passes its implementation in by
constructor. `expect`/`actual` binds exactly one implementation per platform at compile time, but the
app needs several live at once: the real sensor, a replay of a fixture, and fakes in tests are chosen
at runtime on the same platform.

`expect`/`actual` is kept for leaf factories that genuinely have one implementation per platform: the
SQLite driver and the platform wall clock.

## Considered options

- **`expect`/`actual` for every port:** the textbook KMP answer, but it cannot express "replay on the
  simulator, real sensor on the phone" without build variants.
- **A DI framework (Koin):** the object graph is about a dozen objects; manual constructor injection
  is enough and adds no dependency.

Revisit if: the graph grows to where hand-wiring `AppDeps` becomes the main source of wiring bugs.
