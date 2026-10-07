package com.jakubfronczyk.northform.app

import com.jakubfronczyk.northform.adapters.alerts.NotificationAlertPlayer
import com.jakubfronczyk.northform.core.ports.Alert
import com.jakubfronczyk.northform.core.ports.AlertPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationState
import platform.UIKit.UIImpactFeedbackGenerator
import platform.UIKit.UIImpactFeedbackStyle

/**
 * Run alerts by app state (`App/Sources/RunAlertPlayer.swift`, D14/D105): on screen the system
 * vibration; in the background (locked) local notifications, repeated so they are felt in a pocket
 * (split ×2, HR lost ×3, D123). The decision is the reducer's; this only fires.
 */
class RunAlertPlayer(private val notifications: NotificationAlertPlayer, private val log: (String) -> Unit = {}) : AlertPlayer {

    override suspend fun play(alert: Alert) {
        val onScreen = withContext(Dispatchers.Main) { UIApplication.sharedApplication.applicationState != UIApplicationState.UIApplicationStateBackground }
        if (onScreen) vibrate(alert) else notify(alert)
        log("alert played $alert") // `RecordingSession.swift:287`
    }

    private suspend fun vibrate(alert: Alert) = when (alert) {
        Alert.CountdownTick -> withContext(Dispatchers.Main) { UIImpactFeedbackGenerator(UIImpactFeedbackStyle.UIImpactFeedbackStyleRigid).impactOccurred() }
        Alert.Split -> notifications.vibrate(times = 1)
        Alert.HrLost -> notifications.vibrate(times = 2)
    }

    private fun notify(alert: Alert) = when (alert) {
        Alert.CountdownTick -> Unit // the countdown is always in the foreground
        Alert.Split -> notifications.notifyLocked(SPLIT_ID, "Split", "", times = 2)
        Alert.HrLost -> notifications.notifyLocked(HR_LOST_ID, "Heart rate lost", "", times = 3)
    }

    /** Removes this run's notifications once it closes (`RunAlertPlayer.swift:27`). */
    fun clear() = notifications.clear(IDENTIFIERS)

    companion object {
        const val SPLIT_ID = "run.split"
        const val HR_LOST_ID = "run.hrLost"
        val IDENTIFIERS = listOf(SPLIT_ID, HR_LOST_ID).flatMap { id -> (0 until 3).map { "$id-$it" } }
    }
}
