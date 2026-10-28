package com.jakubfronczyk.northform.ui

import com.jakubfronczyk.northform.core.Profile
import com.jakubfronczyk.northform.core.Sex
import com.jakubfronczyk.northform.ui.sensor.PairedSensorInfo
import kotlin.time.Instant

/** Where Export backup is (profile-settings/02): the app model holds it, Settings draws it. */
sealed interface ExportState {
    data object Idle : ExportState
    data object Busy : ExportState
    /** Written to Files as [fileName]. */
    data class Saved(val fileName: String) : ExportState
    data class Failed(val reason: String) : ExportState
}

/**
 * Everything the Settings artboard (`Settings.dc.html`) shows, as plain values (profile-settings spec): one pure
 * function of the profile, the paired sensor and the export, so the screen only lays out ([TodayModel]'s pattern).
 * Not shown, by the spec: Permissions and "max estimated" (first-launch Setup), "clock set" (D17), "Last export".
 */
data class SettingsModel(
    /** "30 · 78 kg · 182 cm". */
    val profileValue: String,
    /** "born 14.03.1996 · male". */
    val profileNote: String,
    /** "max 187 · rest 60". */
    val heartRate: String,
    /** "Couldn't save your profile: …" under the profile card; null when the last save worked. */
    val profileError: String?,
    /** The paired sensor; null when none is paired. */
    val sensor: Sensor?,
    val units: String,
    val backupDetail: String,
    val export: Export,
    val version: String,
) {
    /** "Polar Verity Sense", "ID A1B2C3D4 · firmware 2.2.1", "82 %" (null while not connected). */
    data class Sensor(val name: String, val detail: String, val battery: String?)

    /** The Export backup button and the line under it. A failed export can be tried again. */
    data class Export(val label: String, val enabled: Boolean, val line: String?)

    companion object {
        const val ZONES_SUB = "Karvonen · edit boundaries"

        /** @param now and [tzOffset] are the phone's: they decide the age. [battery] and [firmware] come from the live link. */
        fun from(
            profile: Profile, now: Instant, tzOffset: Int,
            sensor: PairedSensorInfo?, battery: Int?, firmware: String?,
            export: ExportState, version: String, profileSaveError: String? = null,
        ) = SettingsModel(
            profileValue = "${RunFormat.age(profile.birthDate, now, tzOffset)} · ${RunFormat.number(profile.weightKg)} kg · ${RunFormat.number(profile.heightCm)} cm",
            profileNote = "born ${RunFormat.birthDate(profile.birthDate)} · ${if (profile.sex == Sex.Male) "male" else "female"}",
            heartRate = "max ${profile.hrMax} · rest ${profile.hrRest}",
            profileError = profileSaveError?.let { "Couldn't save your profile: $it" },
            sensor = sensor?.let { s ->
                Sensor(s.displayName, listOfNotNull("ID ${s.code}", firmware?.let { "firmware $it" }).joinToString(" · "), battery?.let { "$it %" })
            },
            units = "Metric (km, kg, 24 h)",
            // Backup v1 carries the runs and their streams, not the current profile (D26): the copy says what it holds.
            backupDetail = "All runs and raw streams, saved to Files.",
            export = when (export) {
                ExportState.Idle -> Export("Export backup", enabled = true, line = null)
                ExportState.Busy -> Export("Exporting…", enabled = false, line = null)
                is ExportState.Saved -> Export("Export backup", enabled = true, line = "Saved ${export.fileName} to Files")
                is ExportState.Failed -> Export("Export backup", enabled = true, line = "Couldn't export: ${export.reason}")
            },
            version = version,
        )

    }
}
