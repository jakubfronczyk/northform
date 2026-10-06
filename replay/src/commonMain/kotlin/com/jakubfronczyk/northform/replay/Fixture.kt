package com.jakubfronczyk.northform.replay

/**
 * Fixture format (frozen day 1 in the Swift build; read-compatible here). One JSON object per line:
 *   {"t":0.0,"k":"meta","v":1,"type":"run","profile":{"hrMax":187,"hrRest":60,"sex":"m"}}
 *   {"t":0.4,"k":"hr","bpm":131}
 *   {"t":1.0,"k":"gps","lat":52.5163,"lon":13.3777,"hAcc":4.8,"alt":34.2,"speed":2.9,"still":false}
 *   {"t":612.0,"k":"sensor","state":"lost"}
 *   {"t":640.0,"k":"user","a":"pause"}
 * `t` = seconds from start. No ticks: the replay player generates 1 Hz ticks.
 *
 * TODO(spike): step 3 — parse with kotlinx.serialization (READ only; byte-identical WRITE needs a
 * deterministic encoder, Phase 0), replay through RunReducer, diff against the pinned totals
 * run-1-park 4,830 · run-2-walk 2,988 · straight-run 477. Requires the full reducer port (Phase 0 step 2),
 * so in the spike this proves the pipeline shape on one fixture, not all three totals.
 */
object Fixture
