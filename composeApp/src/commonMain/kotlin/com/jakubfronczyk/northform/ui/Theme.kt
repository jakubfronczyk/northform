package com.jakubfronczyk.northform.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.jakubfronczyk.northform.core.RecordingType
import com.jakubfronczyk.northform.core.roundedHalfAwayFromZero
import kotlin.time.Duration
import kotlin.time.Instant

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
    /** The outline of an unselected pill and the Walk tile (`#707175` on the Today and History artboards). */
    val outline = Color(0xFF707175)
    val accent = Color(0xFFFF6B2C)
    val primary = Color(0xFFC2461C)
    val warn = Color(0xFFE9C46A)
    val ok = Color(0xFF5FD08A)

    /** A recording type's colour (the gate chip, Today's dots and thumbnail): a run in the accent, a walk in grey. */
    fun of(type: RecordingType) = if (type == RecordingType.Run) accent else text2

    /** Zone colours by number, Z0 first. The names live on `core` `Zone.name` (D29): one table. */
    val zoneColors = listOf(
        Color(0xFF6B6C70),
        Color(0xFF9CA3AF),
        Color(0xFF3B82F6),
        Color(0xFF22C55E),
        Color(0xFFF59E0B),
        Color(0xFFEF4444),
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
    val number24 = number(24)
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

    /** HH:MM in a recording's own time zone (`tzOffset` seconds east of UTC): the time the runner saw. */
    fun localTime(instant: Instant, tzOffset: Int): String {
        val secondsOfDay = ((instant.epochSeconds + tzOffset) % 86_400 + 86_400) % 86_400
        return "${(secondsOfDay / 3600).toInt().pad2()}:${(secondsOfDay / 60 % 60).toInt().pad2()}"
    }

    /** "Tue 30 Sep" in a recording's own time zone (the Summary's date line, R27); [withYear] adds " 2024". */
    fun date(instant: Instant, tzOffset: Int, withYear: Boolean = false): String {
        val d = civil(instant, tzOffset)
        return "${WEEKDAYS[d.weekday]} ${d.day} ${MONTHS[d.month - 1]}" + if (withYear) " ${d.year}" else ""
    }

    /** "14.03.1996": a birth date (stored as UTC midnight, so read in UTC), as the profile form types it. */
    fun birthDate(instant: Instant): String = civil(instant, 0).let { "${it.day.pad2()}.${it.month.pad2()}.${it.year}" }

    /** "14.03.1996" → that day at UTC midnight; null unless it is DD.MM.YYYY and a real day (no 31.02). */
    fun parseBirthDate(text: String): Instant? {
        val m = Regex("""(\d{2})\.(\d{2})\.(\d{4})""").matchEntire(text.trim()) ?: return null
        val (d, mo, y) = m.destructured.toList().map(String::toInt)
        if (mo !in 1..12 || d !in 1..31) return null
        // Days since the epoch from the civil date (Hinnant's days_from_civil), then back: a day the month lacks won't round-trip.
        val yy = (if (mo <= 2) y - 1 else y).toLong()
        val era = yy.floorDiv(400L)
        val yoe = yy - era * 400
        val doy = (153 * (if (mo > 2) mo - 3 else mo + 9) + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        val instant = Instant.fromEpochSeconds((era * 146_097 + doe - 719_468) * 86_400)
        return instant.takeIf { civil(it, 0).let { c -> c.day == d && c.month == mo && c.year == y.toLong() } }
    }

    /** Whole years from [birth] (UTC midnight) to [now] in the phone's zone: the age turns on the birthday, not before. */
    fun age(birth: Instant, now: Instant, tzOffset: Int): Int {
        val b = civil(birth, 0)
        val n = civil(now, tzOffset)
        return (n.year - b.year).toInt() - if (n.month < b.month || (n.month == b.month && n.day < b.day)) 1 else 0
    }

    /** The calendar year at [instant] in a zone [tzOffset] seconds east of UTC. */
    fun year(instant: Instant, tzOffset: Int): Long = civil(instant, tzOffset).year

    /** "156 bpm", or null without heart rate: a row or card leaves it out rather than showing a dash. */
    fun bpm(avgHr: Int?): String? = avgHr?.let { "$it bpm" }

    /** A calendar date: weekday 0 = Sunday. */
    internal class Civil(val year: Long, val month: Int, val day: Int, val weekday: Int)

    /** Civil date from days since the epoch (Howard Hinnant's algorithm); no date library. */
    internal fun civil(instant: Instant, tzOffset: Int): Civil {
        val days = (instant.epochSeconds + tzOffset).floorDiv(86_400L)
        val z = days + 719_468
        val era = z.floorDiv(146_097L)
        val doe = z - era * 146_097
        val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val day = doy - (153 * mp + 2) / 5 + 1
        val month = if (mp < 10) mp + 3 else mp - 9
        val year = yoe + era * 400 + if (month <= 2) 1 else 0
        return Civil(year, month.toInt(), day.toInt(), weekday = (days + 4).mod(7L).toInt()) // 1970-01-01 was a Thursday
    }

    private val WEEKDAYS = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
    private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    private fun Int.pad2() = toString().padStart(2, '0')
}
