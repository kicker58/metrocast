package com.metrocast.app

import com.metrocast.app.data.History
import com.metrocast.app.data.TripRecord
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class HistoryTest {
    private val zone = ZoneId.of("America/New_York")
    private fun rec(y: Int, mo: Int, d: Int, h: Int, min: Int, fare: Double) =
        TripRecord(LocalDateTime.of(y, mo, d, h, 0).atZone(zone).toInstant().toEpochMilli(), "Wiehle-Reston East", "Foggy Bottom-GWU", min, 1, 0, fare, true)

    @Test fun weeksRunMondayToSunday() {
        // Today: Wednesday Nov 18 2026. This week = Mon 16 – Sun 22; last week = Mon 9 – Sun 15.
        val today = LocalDate.of(2026, 11, 18)
        val list = listOf(
            rec(2026, 11, 16, 8, 37, 6.75),   // Mon this week
            rec(2026, 11, 17, 18, 40, 6.75),  // Tue this week
            rec(2026, 11, 15, 12, 35, 2.50),  // Sun last week
            rec(2026, 11, 9, 8, 38, 6.75),    // Mon last week
            rec(2026, 11, 8, 23, 36, 2.50),   // Sun two weeks ago
            rec(2026, 10, 20, 8, 37, 6.75),   // long ago
        )
        val w0 = History.week(list, today, 0, zone)
        assertEquals(2, w0.trips); assertEquals(77, w0.minutes); assertEquals(13.50, w0.money, 1e-9)
        val w1 = History.week(list, today, 1, zone)
        assertEquals(2, w1.trips); assertEquals(9.25, w1.money, 1e-9)
        val four = History.lastWeeks(list, today, 4, zone)
        assertEquals(5, four.trips)                      // Oct 20 is outside the last 4 weeks
        assertEquals(LocalDate.of(2026, 11, 16), History.weekStart(LocalDate.of(2026, 11, 22))) // Sunday → that week's Monday
    }

    @Test fun unknownFaresDontCountAsMoney() {
        val known = rec(2026, 11, 16, 8, 37, 6.75)
        val unknown = rec(2026, 11, 16, 18, 40, -1.0)
        val t = History.totals(listOf(known, unknown))
        assertEquals(2, t.trips); assertEquals(77, t.minutes); assertEquals(6.75, t.money, 1e-9)
    }

    @Test fun saveLoadAndCap() {
        val r = rec(2026, 11, 16, 8, 37, 6.75)
        assertEquals(listOf(r), History.decode(History.encode(listOf(r))))
        assertEquals(emptyList<TripRecord>(), History.decode("not json"))
        assertEquals(emptyList<TripRecord>(), History.decode(null))
        var list = emptyList<TripRecord>()
        repeat(History.MAX_RECORDS + 20) { i -> list = History.add(list, r.copy(at = r.at + i)) }
        assertEquals(History.MAX_RECORDS, list.size)
        assertEquals(r.at + History.MAX_RECORDS + 19, list.first().at) // newest first
    }
}
