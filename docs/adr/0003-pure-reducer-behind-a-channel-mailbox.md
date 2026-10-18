---
status: accepted
date: 2026-10-06
---

# A recording is a pure reducer run by one coroutine behind a `Channel` mailbox

The recording logic is a pure function `(State, Input) → (State, Effects)`: no clock, dispatcher or
I/O inside it. `Input`, `Effect` and `Phase` are `sealed interface` hierarchies with `data class`
cases, because the cases carry payloads of different shapes (a countdown's end time, a heart-rate
sample, a batch to persist) that `enum class` cannot hold. One coroutine owns the state and reads
every input from a single `Channel`: sensor samples, GPS fixes, timer ticks, user actions and the
store's own "persisted" acknowledgements.

A lock was rejected because the write loop sends its acknowledgement back into the session while a
save is in flight; Kotlin's `Mutex` is not reentrant, so that would deadlock.

## Consequences

- Fixtures replay through the same reducer as live data, so a recorded run reproduces bit for bit in
  a unit test.
- The reducer's inputs, effects and phases are a frozen contract: changing them changes the fixture
  format.

Revisit if: a producer needs a synchronous answer from the session that a request/reply message on
the mailbox cannot give.
