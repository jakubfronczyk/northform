package com.jakubfronczyk.northform.core.ports

/** `Core/Ports/AlertPlayer.swift` — what the run can ask to be felt. Payload-free, so a real enum class. */
enum class Alert { CountdownTick, Split, HrLost }

/** The adapter picks haptic / vibration / notification (D14); the decision is the reducer's. */
fun interface AlertPlayer {
    suspend fun play(alert: Alert)
}
