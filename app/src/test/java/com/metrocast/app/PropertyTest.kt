package com.metrocast.app

import com.metrocast.app.data.Fares
import com.metrocast.app.data.History
import com.metrocast.app.data.LiveInputs
import com.metrocast.app.data.LiveState
import com.metrocast.app.data.LiveTripEngine
import com.metrocast.app.data.StationDirectory
import com.metrocast.app.data.TrainTracker
import com.metrocast.app.data.TripRecord
import com.metrocast.app.data.WmataApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.random.Random

/** Thousands of random cases checked against simple, independent rules. */
class PropertyTest {
    private val ny = ZoneId.of("America/New_York")

    @Test fun historyWeeksMatchABruteForceCountAcrossDaylightSaving() {
        val rnd = Random(42)
        // Mar 8 and Nov 1 2026 are daylight-saving changes in Washington.
        val start = LocalDateTime.of(2026, 1, 1, 0, 0).atZone(ny).toInstant().toEpochMilli()
        val end = LocalDateTime.of(2027, 1, 1, 0, 0).atZone(ny).toInstant().toEpochMilli()
        val list = (0 until 10_000).map {
            TripRecord(rnd.nextLong(start, end), "A", "B", rnd.nextInt(5, 90), 1, 0,
                if (rnd.nextInt(20) == 0) -1.0 else listOf(2.25, 2.50, 4.15, 6.75).random(rnd), true)
        }
        for (day in listOf(LocalDate.of(2026, 3, 9), LocalDate.of(2026, 3, 8), LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 2), LocalDate.of(2026, 7, 4), LocalDate.of(2026, 12, 31))) {
            for (ago in 0..2) {
                val got = History.week(list, day, ago, ny)
                // Brute force: same Monday-start week as the record's local date.
                var monday = day; while (monday.dayOfWeek != DayOfWeek.MONDAY) monday = monday.minusDays(1)
                monday = monday.minusWeeks(ago.toLong())
                val inWeek = list.filter { r ->
                    val d = Instant.ofEpochMilli(r.at).atZone(ny).toLocalDate()
                    !d.isBefore(monday) && d.isBefore(monday.plusDays(7))
                }
                assertEquals("$day -$ago trips", inWeek.size, got.trips)
                assertEquals("$day -$ago minutes", inWeek.sumOf { it.minutes }, got.minutes)
                assertEquals("$day -$ago money", inWeek.filter { it.fare >= 0 }.sumOf { it.fare }, got.money, 0.005)
            }
        }
        // 500 saved trips load quickly.
        val enc = History.encode(list.take(500))
        val t = System.nanoTime(); repeat(20) { History.decode(enc) }
        assertTrue("decoding 500 trips is slow", (System.nanoTime() - t) / 20 / 1e6 < 50)
    }

    @Test fun faresMatchTheRuleAtRandomTimes() {
        val rnd = Random(7)
        repeat(10_000) {
            val t = LocalDateTime.of(2026, 1, 1, 0, 0).plusMinutes(rnd.nextLong(0, 365L * 24 * 60))
            val weekend = t.dayOfWeek == DayOfWeek.SATURDAY || t.dayOfWeek == DayOfWeek.SUNDAY
            val m = t.hour * 60 + t.minute
            val peak = !weekend && m >= 300 && m < 1290
            assertEquals("$t", if (peak) Fares.Period.PEAK else Fares.Period.OFF_PEAK, Fares.period(t))
            val people = rnd.nextInt(1, 7)
            val q = Fares.quote(6.75, 2.50, t, people)
            assertEquals((if (peak) 6.75 else 2.50) * people, q.total, 1e-9)
        }
    }

    @Test fun headsUpTextIsAlwaysClean() {
        StressTest.load()
        val rnd = Random(99)
        val stations = StationDirectory.stations.filter { s -> StressTest.paths.any { p -> p.codes.any { it in s.codes } } }
        var texts = 0
        repeat(3000) {
            val a = stations.random(rnd); val b = stations.random(rnd)
            if (a.code == b.code) return@repeat
            val (predJson, posJson) = StressTest.snapshots.random(rnd)
            val pos = TrainTracker.parsePositions(posJson)
            val inp = LiveInputs(StressTest.paths, StressTest.routes, pos, WmataApi.parsePredictions(predJson), 0, { _, _ -> null }, emptyList(), 1_700_000_000_000L)
            val st = LiveState(a.code, b.code, 0, pos.random(rnd).trainId, inp.now - rnd.nextLong(0, 30 * 60_000L), 99f)
            val live = LiveTripEngine.update(st, inp).live ?: return@repeat
            val txt = LiveTripEngine.notificationText(live) ?: return@repeat
            texts++
            for (s in listOf(txt.first, txt.second)) {
                assertFalse("says null: $s", s.contains("null"))
                assertFalse("negative time: $s", Regex("-\\d+ min").containsMatchIn(s))
                assertTrue("starts lower-case: $s", s.first().let { !it.isLetter() || it.isUpperCase() })
                assertFalse("lower-case after a full stop: $s", Regex("\\. [a-z]").containsMatchIn(s))
            }
        }
        assertTrue("too few texts checked: $texts", texts > 500)
        println("Heads-up texts checked: $texts")
    }
}
