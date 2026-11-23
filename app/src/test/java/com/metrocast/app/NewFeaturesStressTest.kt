package com.metrocast.app

import com.metrocast.app.data.Flights
import com.metrocast.app.data.GtfsRealtime
import com.metrocast.app.data.Rail
import com.metrocast.app.data.RailRun
import com.metrocast.app.data.RailService
import com.metrocast.app.data.StationDirectory
import com.metrocast.app.data.Updater
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.time.LocalDateTime
import kotlin.random.Random

/** Stress: garbage into every new reader, and a whole week of real schedules checked hour by hour. */
class NewFeaturesStressTest {
    companion object {
        val all by lazy {
            RailService.entries.flatMap { s -> Rail.fromJson(File("src/main/assets/rail_${s.name}.json").readText()) }
        }
        @BeforeClass @JvmStatic fun load() = StationDirectory.load(File("src/test/resources/live/stations.json").readText())
    }
    private val rnd = Random(20261122)
    private fun junk(n: Int) = ByteArray(n) { rnd.nextInt(256).toByte() }
    private fun junkText(n: Int) = String(CharArray(n) { "{}[]\":,0123456789abcxyz-.+ \n\t\\/".random(rnd) })

    @Test fun liveFeedReaderNeverCrashes() {
        repeat(3000) { assertTrue(GtfsRealtime.tripUpdates(junk(rnd.nextInt(0, 400))).size >= 0) }
        // Real feed bytes, cut off at every length.
        val real = File("src/test/resources/live/marc_tu_sunday.pb").readBytes()
        for (n in 0..real.size) GtfsRealtime.tripUpdates(real.copyOf(n))
        // A huge length prefix (a lie) doesn't blow up.
        assertTrue(GtfsRealtime.tripUpdates(byteArrayOf(0x12, 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0x0f)).isEmpty())
    }

    @Test fun textReadersNeverCrash() {
        val now = LocalDateTime.of(2026, 11, 23, 8, 0)
        repeat(2000) {
            val t = junkText(rnd.nextInt(0, 300))
            Rail.csv(t); Rail.fromJson(t); Rail.secs(t)
            Flights.parseAviationStack(t, now); Flights.parseFlightAware(t, now); Flights.aviationStackError(t)
            Updater.parse(t); Updater.base(t)
            Flights.designator(t.take(10), t.takeLast(6)); Flights.airlineCode(t.take(12))
        }
        // Schedule zips: garbage and cut-off real ones.
        repeat(200) { assertTrue(Rail.parseGtfs(RailService.VRE, junk(rnd.nextInt(0, 2000))).isEmpty()) }
        val vre = File("src/test/resources/live/rail_vre.zip").readBytes()
        listOf(10, 100, 1000, vre.size / 2).forEach { Rail.parseGtfs(RailService.VRE, vre.copyOf(it)) }
    }

    @Test fun aWholeWeekHourByHour() {
        val start = LocalDateTime.of(2026, 11, 22, 0, 0) // Sunday through Saturday, incl. Thanksgiving
        val begin = System.nanoTime()
        for (h in 0 until 7 * 24) {
            val now = start.plusHours(h.toLong())
            val up = Rail.upcoming(all, now)
            // Sorted, within 24 hours, no doubles.
            val next = up.map { r -> r.train.stops.map { r.timeAt(it) }.filter { !it.isBefore(now) }.min() }
            assertEquals("hour $h", next.sorted(), next)
            assertTrue(next.all { it.isBefore(now.plusHours(24)) })
            assertEquals(up.size, up.map { "${it.train.service}${it.train.number}${it.first}" }.distinct().size)
            // No VRE on Thanksgiving or weekends.
            up.filter { it.train.service == RailService.VRE }.forEach { r ->
                val d = r.serviceDate
                assertTrue("$d", d.dayOfWeek.value <= 5 && d != java.time.LocalDate.of(2026, 11, 26))
            }
            // Leave-by arithmetic holds for every option from a few stations.
            for (from in listOf("N06", "B03", "C09")) up.take(15).forEach { r ->
                Rail.options(r, from, now, 30) { f, t -> if (f == t) 0 to false else 20 to true }.forEach { o ->
                    assertEquals(o.trainLeaves.minusMinutes(o.totalMin.toLong()), o.leaveBy)
                    assertTrue(!o.trainLeaves.isBefore(now))
                }
            }
        }
        val ms = (System.nanoTime() - begin) / 1_000_000
        assertTrue("a week of lookups took $ms ms", ms < 60_000)
    }

    @Test fun everyLineHasStationsAndTrains() {
        val lines = all.groupBy { it.service to it.route }
        assertTrue(lines.size >= 8)
        lines.forEach { (k, ts) -> assertTrue("$k", ts.flatMap { t -> t.stops.map { it.metroCode } }.all { Rail.station(it) != null }) }
        // Every connecting station is used by at least one line.
        Rail.STATIONS.forEach { st -> assertTrue(st.name, all.any { t -> t.stops.any { it.metroCode == st.metroCode } }) }
        // Searching by number finds that train.
        all.shuffled(rnd).take(100).forEach { t -> assertTrue(t.matches(t.number)) }
        assertTrue(RailRun(all.first(), java.time.LocalDate.of(2026, 11, 23)).first.isAfter(LocalDateTime.of(2026, 11, 22, 23, 0)))
    }

    @Test fun everyTypedTime() {
        // Every hour and minute, typed several ways, on both clocks.
        for (h in 0..23) for (m in 0..59) {
            val want = java.time.LocalTime.of(h, m)
            val h12 = if (h % 12 == 0) 12 else h % 12
            val pm = h >= 12
            for (typed in listOf("$h12:%02d".format(m), "$h12%02d".format(m), "$h12.%02d".format(m))) assertEquals(typed, want, Flights.parseTime(typed, pm))
            assertEquals(want, Flights.parseTime("$h:%02d".format(m), null))
            assertEquals(want, Flights.parseTime("%02d%02d".format(h, m), null))
        }
        repeat(5000) { val t = junkText(rnd.nextInt(0, 8)); Flights.parseTime(t, rnd.nextBoolean()); Flights.parseTime(t, null) }
    }
}
