package com.jakubfronczyk.northform.adapters.alerts

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import platform.AudioToolbox.AudioServicesPlaySystemSound
import platform.AudioToolbox.kSystemSoundID_Vibrate
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNUserNotificationCenter
import kotlin.time.Duration.Companion.milliseconds

/**
 * Alert delivery in Kotlin (D14). The DECISION (split crossed, HR lost 10 s) is the run reducer's; this
 * only fires. Locked: local notifications, `trigger = null` = "deliver the notification right away"
 * (https://developer.apple.com/documentation/usernotifications/unnotificationrequest/init(identifier:content:trigger:)),
 * repeated so they are felt in a pocket (D123 pattern: split ×2, HR lost ×3, ~0.6 s apart).
 * Foreground: `AudioServicesPlaySystemSound(kSystemSoundID_Vibrate)` (C API, callable from Kotlin).
 *
 * Spike step 6 uses `notifyLocked` directly; the `AlertPlayer` port + app-state switch is Phase 0.
 */
class NotificationAlertPlayer(private val scope: CoroutineScope, private val log: (String) -> Unit = {}) {

    private val center get() = UNUserNotificationCenter.currentNotificationCenter()

    /** Asked at the first live run start, after location (SU4). */
    fun requestPermission(onResult: (granted: Boolean) -> Unit) {
        center.requestAuthorizationWithOptions(UNAuthorizationOptionAlert or UNAuthorizationOptionSound) { granted, error ->
            log("notifications permission: $granted ${error?.localizedDescription ?: ""}")
            onResult(granted)
        }
    }

    fun vibrate(times: Int) {
        scope.launch {
            repeat(times) { i ->
                if (i > 0) delay(600.milliseconds)
                AudioServicesPlaySystemSound(kSystemSoundID_Vibrate)
            }
        }
    }

    /** `identifier` is fixed per alert kind so re-posting replaces instead of piling up; removed when the run closes. */
    fun notifyLocked(identifier: String, title: String, body: String, times: Int) {
        scope.launch {
            repeat(times) { i ->
                if (i > 0) delay(600.milliseconds)
                val content = UNMutableNotificationContent().apply {
                    setTitle(title)
                    setBody(body)
                    setSound(UNNotificationSound.defaultSound)
                }
                val request = UNNotificationRequest.requestWithIdentifier("$identifier-$i", content, trigger = null)
                center.addNotificationRequest(request) { error -> error?.let { log("notification failed: ${it.localizedDescription}") } }
            }
        }
    }

    fun clear(identifiers: List<String>) = center.removePendingNotificationRequestsWithIdentifiers(identifiers)
}
