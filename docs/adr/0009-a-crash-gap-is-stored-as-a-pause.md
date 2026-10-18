---
status: accepted
date: 2026-10-11
---

# A crash gap is stored as a pause

When the app reopens after a crash and the user continues the recording, the time since the last
save is recorded as an automatic pause: a pause event at the last save and a resume event at the
moment the user continues. Every metric that already honours pauses (active time, splits, average
pace, distance) then leaves the gap out the same way, which matches how dedicated sport watches stop
the activity timer while nothing was recording.

## Considered options

- **Detect the gap in each metric:** this is what the summary did first. Different metrics disagreed
  about the same recording, and the heuristic failed when earlier signal losses stretched the gap.
- **Count the gap as recorded time with HR and GPS lost:** the gap would inflate total time and
  lower average pace.

## Consequences

- The fix lives in stored events, so it is permanent for recordings made since; a later rule change
  would need a migration.

Revisit if: the app ever records in the background across a crash, so a gap could contain real data.
