package com.jakubfronczyk.northform.engine.run

import com.jakubfronczyk.northform.core.RecordingState
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * R28, the crash prompt: what the app knows about a recording a crash left unfinished, read from the
 * store at launch (nothing runs yet, D9) and shown before the user chooses.
 *
 * Numbers are the rebuilt run's at its last heartbeat (`RunReducer.rebuild(...).snapshot`): the part
 * that is saved. The screen formats them; this type decides nothing.
 */
data class CrashFacts(
    val state: RecordingState,
    /** `Recording.startUtc` + `tzOffset`: the screen shows the start as a local time. */
    val startedAt: Instant,
    val tzOffset: Int,
    /** Metres, as rebuilt from the stored samples. */
    val distance: Double,
    /** Total time at the last heartbeat (pauses included), as rebuilt. */
    val totalTime: Duration,
    /** The last heartbeat the store holds (`RecordingData.lastPersistedAt`, else the start): "saved until". */
    val savedUntil: Instant,
    /** Now minus [savedUntil] at the moment the prompt was built: "interrupted N min ago". */
    val interruptedFor: Duration,
)

/** The three answers of the R28 prompt (`CrashPrompt.dc.html`). */
enum class CrashChoice { Continue, Save, Delete }

/** What the prompt offers, in display order, and which one is the main action. */
data class CrashPrompt(val choices: List<CrashChoice>, val main: CrashChoice) {
    init { require(main in choices) { "the main action must be offered" } }

    companion object {
        /**
         * Continue stays the main action while the run is likely still going on (Swift D75 restated: ◻ 10
         * minutes); after that the run is over and Save leads (`CrashPrompt.dc.html` early/late).
         */
        val continueIsMainWithin: Duration = 10.minutes

        /**
         * The pure rule. Only an `active` recording can continue: `stopping` means hold-to-stop happened
         * (the run is over, the cool-down at most was cut) and `computing` means every write was done, so
         * both offer Save and Delete alone.
         */
        fun of(state: RecordingState, interruptedFor: Duration): CrashPrompt = when (state) {
            RecordingState.Active ->
                if (interruptedFor < continueIsMainWithin) CrashPrompt(listOf(CrashChoice.Continue, CrashChoice.Save, CrashChoice.Delete), main = CrashChoice.Continue)
                else CrashPrompt(listOf(CrashChoice.Save, CrashChoice.Continue, CrashChoice.Delete), main = CrashChoice.Save)
            RecordingState.Stopping, RecordingState.Computing -> CrashPrompt(listOf(CrashChoice.Save, CrashChoice.Delete), main = CrashChoice.Save)
            RecordingState.Saved -> error("a saved recording is finished: nothing to prompt for")
        }

        fun of(facts: CrashFacts): CrashPrompt = of(facts.state, facts.interruptedFor)
    }
}
