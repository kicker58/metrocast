package com.metrocast.app.ui

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

object Format {
    // Rebuilt only when the phone's language changes.
    /** 24-hour times; set from Settings. */
    @Volatile var use24h: Boolean = false
        set(v) { field = v; fmts = null }
    @Volatile private var fmts: Pair<Locale, Array<DateTimeFormatter>>? = null
    private fun fmt(i: Int): DateTimeFormatter {
        val l = Locale.getDefault()
        val f = fmts?.takeIf { it.first == l } ?: (l to arrayOf(
            DateTimeFormatter.ofPattern(if (use24h) "HH:00" else "h a", l), DateTimeFormatter.ofPattern(if (use24h) "HH:mm" else "h:mm a", l), DateTimeFormatter.ofPattern("EEE", l),
        )).also { fmts = it }
        return f.second[i]
    }
    /** "1:45 PM" or "13:45". */
    fun time(t: java.time.LocalTime): String = t.format(fmt(1))

    private val hourFmt get() = fmt(0)
    private val clockFmt get() = fmt(1)
    private val dayFmt get() = fmt(2)

    /** "700 ft", "0.3 mi" — or "210 m", "1.2 km" with metric units. */
    fun distance(meters: Double, metric: Boolean): String = when {
        metric && meters < 1000 -> "${(meters / 10).toInt() * 10} m"
        metric -> "%.1f km".format(meters / 1000)
        meters < 300 -> "${((meters * 3.28084) / 10).toInt() * 10} ft"
        else -> "%.1f mi".format(meters / 1609.34)
    }

    fun temp(t: Double): String = if (t.isNaN()) "--°" else "${t.roundToInt()}°"

    fun hour(t: LocalDateTime, first: Boolean): String = if (first) "Now" else t.format(hourFmt)

    fun day(d: LocalDate, today: LocalDate): String = when (d) {
        today -> "Today"
        today.plusDays(1) -> "Tomorrow"
        else -> d.format(dayFmt)
    }

    fun clock(epochMs: Long): String =
        Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).format(clockFmt)

    fun updated(epochMs: Long, now: Long = System.currentTimeMillis()): String {
        if (epochMs <= 0) return "Not updated yet"
        val mins = (now - epochMs) / 60_000
        return when {
            mins < 1 -> "Updated just now"
            mins < 60 -> "Updated $mins min ago"
            else -> "Updated ${clock(epochMs)}"
        }
    }
}
