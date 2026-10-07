package com.jakubfronczyk.northform.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.jakubfronczyk.northform.core.roundedHalfAwayFromZero
import kotlin.time.Duration

/** Colour tokens from the style guide (`Features/Style/Theme.swift:4-26`). */
object Theme {
    val bg = Color(0xFF161718)
    val card = Color(0xFF232426)
    val track = Color(0xFF2E2F32)
    val line = Color(0xFF34363A)
    val text = Color(0xFFF4F4F2)
    val text2 = Color(0xFFA9AAA6)
    val text3 = Color(0xFF8E8F8B)
    val dim = Color(0xFF8A8B88)
    val ring = Color(0xFF6A6B6E)
    val accent = Color(0xFFFF6B2C)
    val primary = Color(0xFFC2461C)
    val warn = Color(0xFFE9C46A)
    val ok = Color(0xFF5FD08A)

    class Zone(val name: String, val color: Color)

    val zones = listOf(
        Zone("Rest", Color(0xFF6B6C70)),
        Zone("Very light", Color(0xFF9CA3AF)),
        Zone("Light", Color(0xFF3B82F6)),
        Zone("Moderate", Color(0xFF22C55E)),
        Zone("Hard", Color(0xFFF59E0B)),
        Zone("Maximum", Color(0xFFEF4444)),
    )
}

/** Text styles by role (`Theme.swift:39-56`). Numbers use tabular digits so a changing value doesn't jitter. */
object Type {
    fun number(size: Int, weight: FontWeight = FontWeight.Bold) =
        TextStyle(fontSize = size.sp, fontWeight = weight, fontFeatureSettings = "tnum")

    // Hoisted once: these sit on the 1 Hz live screen.
    val countdown = number(160, FontWeight.Black)
    val number54Black = number(54, FontWeight.Black)
    val number40 = number(40)
    val number22 = number(22)
    val pillLabel = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold)

    val screenTitle = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Black)
    val homeTitle = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Black)
    val sheetTitle = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold)
    val rowLabel = TextStyle(fontSize = 14.sp)
    val secondaryText = TextStyle(fontSize = 15.sp)
    val tertiaryText = TextStyle(fontSize = 13.sp)
    val statusLabel = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    val unitLabel = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium)
}

/** Formatting for run numbers (`Theme.swift:59-78`, metric, D53). Pure string work, no locale. */
object RunFormat {
    /** 5:12 style, or "–:––" when hidden. */
    fun pace(secondsPerKm: Double?): String {
        if (secondsPerKm == null || !secondsPerKm.isFinite() || secondsPerKm >= 3600) return "–:––"
        val total = secondsPerKm.roundedHalfAwayFromZero().toInt()
        return "${total / 60}:${(total % 60).pad2()}"
    }

    /** 6.42 (km). */
    fun distance(metres: Double): String {
        val hundredths = (metres / 1000 * 100).roundedHalfAwayFromZero().toLong()
        return "${hundredths / 100}.${(hundredths % 100).toInt().pad2()}"
    }

    /** 34:18 or 1:02:05. */
    fun time(duration: Duration): String {
        val total = duration.inWholeSeconds
        val h = total / 3600
        val m = total / 60 % 60
        val s = total % 60
        return if (h > 0) "$h:${m.toInt().pad2()}:${s.toInt().pad2()}" else "$m:${s.toInt().pad2()}"
    }

    private fun Int.pad2() = toString().padStart(2, '0')
}
