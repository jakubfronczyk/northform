# Northform

A training app built around a heart-rate sensor and the phone's GPS. It records runs and walks first;
gym and custom training come after them. One word,
one meaning: the words the code and docs use, defined once here. Why the system is shaped the way it
is lives in `docs/adr/`.

## Language

### Recording

**Recording**:
One recorded activity, from Start to save, of any type. The umbrella word.
_Avoid_: activity, session, workout; "run" when a walk is meant too

**Run**:
A recording of type run. The only type that gets a run tag.
_Avoid_: using it for a walk. The code's `Run*` names (`RunReducer`, `RunState`, `RunSummary`) are
older and cover any recording.

**Walk**:
A recording of type walk. Recorded the same way as a run, with no run tag.

**Run tag**:
The kind of training a run was: Easy, Long, Tempo or Intervals. Suggested from zones and time,
editable by the user.
_Avoid_: run type, workout type

**RPE**:
Rate of perceived exertion, 0–10, entered by the user after a recording.

### Lifecycle

**Gate**:
The screen before Start that waits until HR and GPS are both ready. The user may start anyway.
_Avoid_: pre-run check, start gate

**Countdown**:
The 5 s between pressing Start and the recording beginning.

**Pause**:
A stretch of a recording that does not count toward active time, pace or splits. Manual when the user
pauses; automatic for a crash gap.

**Hold-to-stop**:
The press-and-hold that ends a recording. Its moment is the recording's end time.
_Avoid_: finish, end button

**Cool-down**:
The 60 s of heart rate kept after hold-to-stop to measure HR recovery. The user may skip it.

**Computing**:
The state after the cool-down while the recording's metrics are worked out, before it is saved.

**Saved**:
A finished recording with its metrics, shown in History.

### Signals and loss

**Split**:
One kilometre of a recording, marked when it is completed.
_Avoid_: lap

**Current pace**:
Pace over a short window of recent distance; hidden when paused or not moving. Displayed rounded to
5 s/km.
_Avoid_: shown pace, instant pace

**Split pace**:
Pace of the kilometre in progress.

**Average pace**:
Active time divided by distance for the whole recording, pauses left out.

**HR lost**:
No heart-rate sample for 5 s, whatever the cause. What the runner sees.
_Avoid_: sensor lost

**GPS lost**:
No location fix for 10 s, or a fix less accurate than 50 m.

**Drop**:
The Bluetooth link to the sensor lost without the user asking. Counted in the reliability log.
_Avoid_: sensor lost, disconnect

**Reconnect**:
The Bluetooth link coming back after a drop or after Bluetooth off.

**Bluetooth off**:
The phone's Bluetooth radio switched off while the recording needed the sensor. Counted apart from
drops.

**Crash**:
The app stopping mid-recording without hold-to-stop, found when it reopens. Every crash leaves a mark
on the recording.

**Last save**:
The most recent moment the recording was written to storage, about every 5 s. A crashed recording
ends here.
_Avoid_: heartbeat

**Crash gap**:
The time between the last save and the user choosing Continue. Stored as a pause.

**Reliability log**:
Per recording: drops, reconnects and how long they took, HR-lost and GPS-lost time, Bluetooth-off
count, crashes.

### Heart

**Profile**:
The user's resting HR, HRmax, sex and zone bounds that heart metrics are computed with. A saved
recording keeps the profile it was computed with.

**HRmax**:
The user's maximum heart rate. The app may suggest a new one when a recording goes higher.

**Resting HR**:
The user's heart rate at rest; the floor of the heart-rate reserve.

**Heart-rate reserve**:
HRmax minus resting HR. Zones and TRIMP are fractions of it.
_Avoid_: HRR when writing for users

**Zone**:
One of six heart-rate bands, Z0–Z5, named Rest, Very light, Light, Moderate, Hard, Maximum. Bounded
by fractions of the heart-rate reserve.
_Avoid_: Easy/Tempo as zone names (those are run tags)

**Time in zone**:
How long a recording spent in each zone.

**TRIMP**:
Training load of one recording: time weighted by how hard the heart worked (Banister).
_Avoid_: load score, effort score

**HR recovery**:
Heart rate at hold-to-stop minus heart rate 60 s later. Absent if the cool-down was skipped.

### Architecture

**Swift seam**:
The only Swift in the app: a fixed, small set of files that call into Kotlin and contain no
decisions.
_Avoid_: native layer, bridge

**Fixture**:
A recording captured from a real run or walk, kept as a file, with the totals it must produce.

**Replay**:
Feeding a fixture through the app in place of the live sensor and GPS, so a recording can be
reproduced without a phone.
_Avoid_: simulation, mock run

**Frozen contract**:
An interface or format other parts depend on that changes only by a recorded decision: the ports,
the fixture format, the recording reducer's inputs, effects and phases.
