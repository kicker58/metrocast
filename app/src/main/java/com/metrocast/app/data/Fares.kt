package com.metrocast.app.data

import java.time.DayOfWeek
import java.time.LocalDateTime

/**
 * Metrorail pricing, kept simple: peak or off-peak, by day of the week and time of day, and the
 * distance between your two stations (WMATA's exact peak and off-peak fare for that station pair).
 *  - Peak: weekdays 5 am – 9:30 pm
 *  - Off-peak: weekday nights after 9:30 pm, and all weekend
 */
object Fares {
    enum class Period(val label: String, val hours: String) {
        PEAK("Peak", "weekdays 5 am – 9:30 pm"),
        OFF_PEAK("Off-peak", "weekday nights after 9:30 pm and weekends"),
    }

    /** Peak or off-peak when you tap in at [time]. */
    fun period(time: LocalDateTime): Period {
        if (time.dayOfWeek == DayOfWeek.SATURDAY || time.dayOfWeek == DayOfWeek.SUNDAY) return Period.OFF_PEAK
        val m = time.hour * 60 + time.minute
        return if (m in (5 * 60) until (21 * 60 + 30)) Period.PEAK else Period.OFF_PEAK
    }

    data class Quote(val period: Period, val perPerson: Double, val total: Double)

    /** The exact one-way cost for [people] tapping in at [time], given the pair's peak/off-peak fares. */
    fun quote(peak: Double, offPeak: Double, time: LocalDateTime, people: Int = 1): Quote {
        val p = period(time)
        val each = if (p == Period.PEAK) peak else offPeak
        return Quote(p, each, Math.round(each * people.coerceAtLeast(1) * 100.0) / 100.0)
    }
}
