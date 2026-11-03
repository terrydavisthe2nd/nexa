package fish.nexa.data

import android.icu.text.DateFormat
import android.icu.text.RelativeDateTimeFormatter
import fish.nexa.ui.I18n
import fish.nexa.ui.t
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Date

/**
 * What the Countdown and Local Time widgets say (web lib/timeWidgets.ts),
 * worked out against the device's clock corrected by the server's
 * (ServerClock), so a wrong device clock does not move a countdown.
 */
object TimeWidgets {
    private const val DAY = 86_400_000L

    sealed interface State {
        /** Still to come: [left] says how long, in words. */
        data class Coming(val left: String) : State
        /** The day itself, or the moment came within the last day. */
        data object Today : State
        /** Over for more than a day: the widget is not shown. */
        data object Gone : State
    }

    private fun rtf() = RelativeDateTimeFormatter.getInstance(I18n.locale)

    private fun inDays(days: Long): String =
        if (days == 1L) rtf().format(RelativeDateTimeFormatter.Direction.NEXT, RelativeDateTimeFormatter.AbsoluteUnit.DAY)
        else rtf().format(days.toDouble(), RelativeDateTimeFormatter.Direction.NEXT, RelativeDateTimeFormatter.RelativeUnit.DAYS)

    private fun localDay(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()

    /** Where a countdown to [date] (YYYY-MM-DD) or [at] (Unix ms) stands at [now]. */
    fun state(date: String?, at: Long?, now: Long): State {
        if (date != null) {
            val day = runCatching { LocalDate.parse(date) }.getOrNull() ?: return State.Gone
            // whole days in the viewer's calendar, so tomorrow is tomorrow whatever the hour
            val days = ChronoUnit.DAYS.between(localDay(now), day)
            return when {
                days > 0 -> State.Coming(inDays(days))
                days >= -1 -> State.Today
                else -> State.Gone
            }
        }
        val ms = (at ?: 0L) - now
        if (ms <= 0) return if (ms > -DAY) State.Today else State.Gone
        val mins = (ms + 59_999) / 60_000
        if (mins < 60) return State.Coming(rtf().format(mins.toDouble(), RelativeDateTimeFormatter.Direction.NEXT, RelativeDateTimeFormatter.RelativeUnit.MINUTES))
        val hours = Math.round(ms / 3_600_000.0)
        if (hours < 24) return State.Coming(rtf().format(hours.toDouble(), RelativeDateTimeFormatter.Direction.NEXT, RelativeDateTimeFormatter.RelativeUnit.HOURS))
        return State.Coming(inDays(ChronoUnit.DAYS.between(localDay(now), localDay(at!!))))
    }

    /** When a countdown is, as a date (and a time for a moment) in the viewer's words. */
    fun whenText(date: String?, at: Long?): String {
        if (date != null) {
            val day = runCatching { LocalDate.parse(date) }.getOrNull() ?: return date
            val ms = day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            return DateFormat.getDateInstance(DateFormat.FULL, I18n.locale).format(Date(ms))
        }
        return DateFormat.getDateTimeInstance(DateFormat.FULL, DateFormat.SHORT, I18n.locale).format(Date(at ?: 0L))
    }

    fun valid(zone: String): Boolean = runCatching { ZoneId.of(zone) }.isSuccess

    /** A zone's offset from UTC at [now], in minutes. */
    fun offset(zone: String, now: Long): Int = runCatching { ZoneId.of(zone).rules.getOffset(Instant.ofEpochMilli(now)).totalSeconds / 60 }.getOrDefault(0)

    /** The time in a zone at [now], as the viewer writes times. */
    fun time(zone: String, now: Long): String =
        DateFormat.getTimeInstance(DateFormat.SHORT, I18n.locale).apply { timeZone = android.icu.util.TimeZone.getTimeZone(zone) }.format(Date(now))

    /** A zone's place, from its name: "America/Port_of_Spain" is Port of Spain. */
    fun place(zone: String): String = zone.substringAfterLast('/').replace('_', ' ')

    /** How far a zone is from this device's: "8 hours ahead", "30 minutes behind", or "same time as you". */
    fun gap(zone: String, now: Long): String {
        val mine = ZoneId.systemDefault().rules.getOffset(Instant.ofEpochMilli(now)).totalSeconds / 60
        val diff = offset(zone, now) - mine
        if (diff == 0) return t("widgets.localtime.same")
        val abs = Math.abs(diff)
        val h = abs / 60
        val m = abs % 60
        val amount = when {
            h > 0 && m > 0 -> t("widgets.localtime.hoursMinutes", "hours" to h, "minutes" to m)
            h > 0 -> t("widgets.localtime.hours", "count" to h)
            else -> t("widgets.localtime.minutes", "count" to m)
        }
        return if (diff > 0) t("widgets.localtime.ahead", "amount" to amount) else t("widgets.localtime.behind", "amount" to amount)
    }

    private val AREAS = setOf("Africa", "America", "Antarctica", "Arctic", "Asia", "Atlantic", "Australia", "Europe", "Indian", "Pacific")

    /** The zones to pick from, as the web's list has them (places, not aliases), with this device's first. */
    fun zones(): List<String> {
        val own = ZoneId.systemDefault().id
        val list = ZoneId.getAvailableZoneIds().filter { it.substringBefore('/') in AREAS && '/' in it }.sorted().toMutableList()
        list += "UTC"
        if (own !in list) list.add(0, own)
        return list
    }

    /** A zone as the picker lists it: "Anchorage, UTC−9". */
    fun label(zone: String, now: Long): String {
        val off = offset(zone, now)
        val h = Math.abs(off) / 60
        val m = Math.abs(off) % 60
        val utc = if (off == 0) "UTC" else "UTC" + (if (off < 0) "−" else "+") + h + (if (m > 0) ":%02d".format(m) else "")
        return "${place(zone)}, $utc"
    }
}
