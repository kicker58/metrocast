package com.metrocast.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** One finished trip, kept only on this phone (when trip history is on). */
data class TripRecord(
    /** When you boarded the first train (epoch ms). */
    val at: Long,
    val from: String,
    val to: String,
    /** Boarding the first train → reaching the destination. */
    val minutes: Int,
    val people: Int,
    val transfers: Int,
    /** Total fare for everyone, one way (peak or off-peak by when you boarded); negative = not known. */
    val fare: Double,
    val peak: Boolean,
)

data class HistoryTotals(val trips: Int, val minutes: Int, val money: Double)

object History {
    const val MAX_RECORDS = 500

    fun encode(list: List<TripRecord>): String = JSONArray().apply {
        list.forEach { r ->
            put(JSONObject().put("at", r.at).put("f", r.from).put("t", r.to).put("m", r.minutes)
                .put("p", r.people).put("x", r.transfers).put("$", r.fare).put("pk", r.peak))
        }
    }.toString()

    fun decode(raw: String?): List<TripRecord> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
                TripRecord(o.getLong("at"), o.getString("f"), o.getString("t"), o.getInt("m"),
                    o.optInt("p", 1), o.optInt("x", 0), o.getDouble("$"), o.optBoolean("pk", true))
            }
        }.getOrDefault(emptyList())
    }

    /** Newest first, capped. */
    fun add(list: List<TripRecord>, r: TripRecord): List<TripRecord> = (listOf(r) + list).take(MAX_RECORDS)

    /** Money counts only trips whose fare is known. */
    fun totals(list: List<TripRecord>): HistoryTotals =
        HistoryTotals(list.size, list.sumOf { it.minutes }, Math.round(list.filter { it.fare >= 0 }.sumOf { it.fare } * 100.0) / 100.0)

    /** Monday that starts the week containing [day]. */
    fun weekStart(day: LocalDate): LocalDate = day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    private fun dayOf(r: TripRecord, zone: ZoneId) = Instant.ofEpochMilli(r.at).atZone(zone).toLocalDate()

    /** Trips in the week (Mon–Sun) that is [weeksAgo] weeks before the one containing [today]. */
    fun week(list: List<TripRecord>, today: LocalDate, weeksAgo: Int = 0, zone: ZoneId = ZoneId.systemDefault()): HistoryTotals {
        val start = weekStart(today).minusWeeks(weeksAgo.toLong())
        val end = start.plusDays(7)
        return totals(list.filter { dayOf(it, zone).let { d -> !d.isBefore(start) && d.isBefore(end) } })
    }

    /** The last [weeks] weeks including this one. */
    fun lastWeeks(list: List<TripRecord>, today: LocalDate, weeks: Int, zone: ZoneId = ZoneId.systemDefault()): HistoryTotals {
        val start = weekStart(today).minusWeeks((weeks - 1).toLong())
        return totals(list.filter { !dayOf(it, zone).isBefore(start) })
    }
}
