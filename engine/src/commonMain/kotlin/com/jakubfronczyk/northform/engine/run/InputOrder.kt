package com.jakubfronczyk.northform.engine.run

/**
 * The ONE rule for inputs that share an instant, used wherever inputs are replayed rather than
 * received live: by [rebuild] (store → inputs, crash resume) and by the test harness (script + ticks).
 * Samples go first, then the user's and the sensor's events, ticks last; otherwise time, then the
 * order given. One rule, so "rebuild = live" cannot pass because two copies happen to agree (D94).
 */
object InputOrder {
    /** Equal-time rank. Store confirmations carry no time and are never replayed, so ranking one is a bug. */
    fun rank(input: RunInput): Int = when (input) {
        is RunInput.Hr, is RunInput.Fix -> 0
        is RunInput.User, is RunInput.Sensor, is RunInput.Backgrounded -> 1
        is RunInput.Tick -> 2
        is RunInput.Persisted, is RunInput.PersistFailed -> error("a store confirmation has no time to order by: $input")
    }

    /** By (time, rank); `sortedWith` is stable, so equal keys keep the order given (the Swift sort keyed on the offset too). */
    fun sorted(inputs: List<RunInput>): List<RunInput> =
        inputs.sortedWith(compareBy({ it.time ?: error("a store confirmation has no time to order by: $it") }, { rank(it) }))
}
