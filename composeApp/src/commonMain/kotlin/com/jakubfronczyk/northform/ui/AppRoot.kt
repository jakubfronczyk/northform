package com.jakubfronczyk.northform.ui

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.Modifier
import com.jakubfronczyk.northform.core.RecordingId
import com.jakubfronczyk.northform.core.RecordingType
import com.jakubfronczyk.northform.engine.run.Route
import com.jakubfronczyk.northform.engine.run.RunSessionHandle
import com.jakubfronczyk.northform.ui.run.GateSensor
import com.jakubfronczyk.northform.ui.run.RunFlow
import com.jakubfronczyk.northform.ui.theme.Theme

/**
 * Navigation (D77 restated, `App/Sources/NorthformApp.swift`): Today is the root; the run flow is a
 * full-screen cover without swipe dismiss (a `when` on `activeRun`); the Sensor screen is a sheet with
 * Close. The composition root supplies the session, the map and the sensor screen; nothing here decides
 * anything about a run.
 */
@Composable
fun AppRoot(
    activeRun: RunSessionHandle?,
    /** What Today shows (today-screen spec); the root builds it from the saved list and the sensor link. */
    today: TodayModel,
    /** What History shows under a filter (history-screen spec), from the same saved list as [today]. */
    history: (HistoryFilter) -> HistoryModel,
    /** History's Retry after a failed read. */
    retryHistory: () -> Unit,
    /** first-launch-setup: true while no profile is stored, null until the launch read lands (nothing is shown yet). */
    needsSetup: Boolean?,
    /** Setup over everything; `finish` after its last step. The composition root supplies the steps' inputs. */
    setup: @Composable (finish: () -> Unit) -> Unit,
    /** What Settings shows (profile-settings spec). */
    settings: SettingsModel,
    /** Settings' Export backup. */
    exportBackup: () -> Unit,
    /** Settings' Forget sensor, after its confirm sheet. */
    forgetSensor: () -> Unit,
    /** The profile editor over Settings (profile-settings/03); `close` after Save or Back. The root supplies the profile. */
    profileEditor: @Composable (close: () -> Unit) -> Unit,
    /** The zone editor over Settings (profile-settings/04), like [profileEditor]. */
    zoneEditor: @Composable (close: () -> Unit) -> Unit,
    /** A saved recording tapped on Today or History: reopened as a review Summary in the [summary] slot. */
    openRecording: (RecordingId) -> Unit,
    /** The Run or Walk tile: starts a recording of that type. */
    onStartRun: (RecordingType) -> Unit,
    onCloseRun: () -> Unit,
    map: @Composable (route: Route) -> Unit,
    sensorScreen: @Composable (close: () -> Unit) -> Unit,
    /** E1: the gate's sensor row, from the real link; null = unknown (replay has no link to ask). Required so the root can't forget it. */
    gateSensor: GateSensor?,
    /** The gate's "Connect" for a sensor that is not connected. */
    connectSensor: () -> Unit,
    /** The R28 crash prompt while a recording waits for the user's choice: a sheet over Today that cannot be dismissed (Swift D77 restated). */
    crashPrompt: (@Composable () -> Unit)? = null,
    /** R27: the Summary of the run that just finished; shown over Today while non-null. */
    summary: (@Composable () -> Unit)? = null,
) {
    var showSensor by remember { mutableStateOf(false) }
    // History sits under the Summary in the order below, so Back on a reopened Summary falls through to it. Its
    // scroll position lives here, not in the screen, to survive that trip; a fresh one each time History opens.
    var showHistory by remember { mutableStateOf(false) }
    // Settings sits under the Summary too: a Summary found at launch shows over it and returns to it.
    var showSettings by remember { mutableStateOf(false) }
    var showProfileEditor by remember { mutableStateOf(false) }
    var showZoneEditor by remember { mutableStateOf(false) }
    var historyList by remember { mutableStateOf(LazyListState()) }
    var historyFilter by remember { mutableStateOf(HistoryFilter()) }
    // Setup, once started, stays up to its last step: step 1 saves the profile (so it is no longer *needed*), steps 2–4 follow.
    var inSetup by remember { mutableStateOf(false) }
    LaunchedEffect(needsSetup) { if (needsSetup == true) inSetup = true }
    Box(Modifier.fillMaxSize().background(Theme.bg)) {
        when {
            needsSetup == null -> Unit // the launch read hasn't landed: never a flash of Today before Setup
            inSetup || needsSetup == true -> setup { inSetup = false }
            // The Sensor sheet first: the gate's "Pair a sensor" opens it over a run still at the gate, and Close returns there.
            showSensor -> sensorScreen { showSensor = false }
            activeRun != null -> RunFlow(
                activeRun, onClose = onCloseRun, map = map, gateSensor = gateSensor, connectSensor = connectSensor,
                pairSensor = { showSensor = true },
            )
            summary != null -> summary()
            showProfileEditor -> profileEditor { showProfileEditor = false }
            showZoneEditor -> zoneEditor { showZoneEditor = false }
            showSettings -> SettingsScreen(
                settings, back = { showSettings = false }, editProfile = { showProfileEditor = true }, editZones = { showZoneEditor = true }, export = exportBackup, forgetSensor = forgetSensor,
            )
            showHistory -> HistoryScreen(history(historyFilter), onFilter = { historyFilter = it }, back = { showHistory = false }, retry = retryHistory, openRecording = openRecording, listState = historyList)
            else -> {
                TodayScreen(today, start = onStartRun, openSensor = { showSensor = true }, openRecording = openRecording, openHistory = { historyList = LazyListState(); historyFilter = HistoryFilter(); showHistory = true }, openSettings = { showSettings = true })
                crashPrompt?.invoke()
            }
        }
    }
}
