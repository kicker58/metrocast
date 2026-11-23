package com.metrocast.app

import com.metrocast.app.data.Airport
import com.metrocast.app.data.Flights
import com.metrocast.app.data.StationDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.time.LocalDateTime

/** Airports: flight numbers, both tracking services' answers, and when to leave. */
class FlightsTest {
    companion object { @BeforeClass @JvmStatic fun load() = StationDirectory.load(File("src/test/resources/live/stations.json").readText()) }
    private val now = LocalDateTime.of(2026, 11, 23, 8, 0)

    @Test fun flightNumbers() {
        assertEquals("AA1234", Flights.designator("American", "1234"))
        assertEquals("AA1234", Flights.designator("american airlines".take(8), " 1234 "))
        assertEquals("UA88", Flights.designator("", "UA 0088"))
        assertEquals("B61", Flights.designator("JetBlue", "1"))
        assertEquals("DL5", Flights.designator("dl", "005"))
        assertEquals("WN", Flights.airlineCode("South"))
        assertNull(Flights.designator("", "1234"))          // no airline
        assertNull(Flights.designator("American", ""))      // no number
        assertNull(Flights.designator("American", "12345")) // too long
        assertNull(Flights.designator("Not an airline at all", "12"))
        assertEquals("AA 1234", Flights.pretty("AA1234"))
    }

    @Test fun aviationStackPicksTodaysDepartureWithLocalTimes() {
        val f = Flights.parseAviationStack(File("src/test/resources/flights/aviationstack.json").readText(), now)!!
        assertEquals("AA 1234", f.flight); assertEquals("DCA", f.from)
        // "+00:00" on AviationStack is really airport-local time.
        assertEquals(LocalDateTime.of(2026, 11, 23, 13, 5), f.scheduled)
        assertEquals(25, f.delayMin); assertEquals("Delayed 25 min", f.statusText)
        assertEquals("2", f.terminal); assertEquals("23", f.gate)
        assertTrue(f.to.contains("ORD"))
        assertNull(Flights.parseAviationStack("""{"data":[]}""", now))
        assertNull(Flights.parseAviationStack("garbage", now))
        assertTrue(Flights.needsHttp("""{"error":{"code":"https_access_restricted","message":"Access Restricted"}}"""))
        assertTrue(Flights.aviationStackError("""{"error":{"code":"invalid_access_key"}}""")!!.contains("key"))
        assertTrue(Flights.aviationStackError("""{"error":{"code":"usage_limit_reached"}}""")!!.contains("used up"))
        assertNull(Flights.aviationStackError("""{"data":[]}"""))
        assertTrue(Flights.aviationStackUrl("k", "AA1234").startsWith("https://"))
        assertTrue(Flights.aviationStackUrl("k", "AA1234", https = false).startsWith("http://"))
    }

    @Test fun flightAwareUtcToLocalAndDulles() {
        val f = Flights.parseFlightAware(File("src/test/resources/flights/flightaware.json").readText(), now)!!
        assertEquals("UA 2051", f.flight); assertEquals("IAD", f.from); assertEquals(Airport.IAD, Flights.airportOf(f.from))
        assertEquals(LocalDateTime.of(2026, 11, 23, 17, 10), f.scheduled) // 22:10 UTC = 5:10 PM in DC
        assertEquals(40, f.delayMin); assertEquals("C14", f.gate)
        assertTrue(f.to.contains("San Francisco"))
        val canceled = File("src/test/resources/flights/flightaware.json").readText().replace("\"cancelled\":false", "\"cancelled\":true")
        assertEquals("Canceled", Flights.parseFlightAware(canceled, now)!!.statusText)
        assertNull(Flights.parseFlightAware("{}", now))
    }

    @Test fun flightFromElsewhereIsFlagged() {
        val json = File("src/test/resources/flights/flightaware.json").readText().replace("\"IAD\"", "\"BWI\"")
        val f = Flights.parseFlightAware(json, now)!!
        assertNull(Flights.airportOf(f.from))
    }

    @Test fun typedTimes() {
        fun t(h: Int, m: Int) = java.time.LocalTime.of(h, m)
        assertEquals(t(13, 5), Flights.parseTime("1:05", pm = true))
        assertEquals(t(1, 5), Flights.parseTime("1:05", pm = false))
        assertEquals(t(13, 5), Flights.parseTime("105", pm = true))
        assertEquals(t(13, 5), Flights.parseTime("1.05", pm = true))
        assertEquals(t(0, 30), Flights.parseTime("12:30", pm = false))   // 12:30 AM
        assertEquals(t(12, 30), Flights.parseTime("12:30", pm = true))   // 12:30 PM
        assertEquals(t(19, 0), Flights.parseTime("7", pm = true))
        assertEquals(t(10, 45), Flights.parseTime("1045", pm = false))
        // 24-hour clock: no AM/PM.
        assertEquals(t(13, 5), Flights.parseTime("13:05", pm = null))
        assertEquals(t(0, 15), Flights.parseTime("0015", pm = null))
        // Not real times.
        listOf("13:05" to true, "0:30" to false, "1:75" to true, "" to true, "abc" to true, "12345" to false).forEach { (s, p) -> assertNull(s, Flights.parseTime(s, p)) }
        assertNull(Flights.parseTime("24:00", null))
    }

    @Test fun amOrPmIsGuessedSensibly() {
        assertEquals(1, Flights.typedHour("105")); assertEquals(10, Flights.typedHour("1045")); assertEquals(7, Flights.typedHour("7"))
        assertEquals(12, Flights.typedHour("12:30")); assertNull(Flights.typedHour("abc"))
        val day = now.toLocalDate() // 8:00 AM
        assertEquals(false, Flights.guessPm(9, day, now))    // 9 → 9 AM (still ahead today)
        assertEquals(true, Flights.guessPm(7, day, now))     // 7 AM has passed → 7 PM
        assertEquals(true, Flights.guessPm(12, day, now))    // 12 → noon
        assertEquals(true, Flights.guessPm(9, day, now.withHour(22)))  // late evening: 9 PM (both passed)
        // Another day: mornings 5–11, otherwise afternoon/evening.
        assertEquals(false, Flights.guessPm(6, day.plusDays(1), now))
        assertEquals(true, Flights.guessPm(3, day.plusDays(1), now))
        assertEquals(true, Flights.guessPm(12, day.plusDays(1), now))
    }

    @Test fun whenToLeave() {
        val dep = LocalDateTime.of(2026, 11, 23, 13, 5)
        val p = Flights.plan(Airport.DCA, dep, "N06", 120) { _, _ -> 45 to false }!!
        // 45 Metro + 5 wait + 6 walk + 120 early = 176 minutes.
        assertEquals(176, p.totalMin); assertEquals(dep.minusMinutes(176), p.leaveBy)
        // Starting at the airport's own station: no ride.
        val there = Flights.plan(Airport.IAD, dep, "N10", 120) { _, _ -> 99 to false }!!
        assertEquals(0, there.metroMin); assertEquals(0, there.waitMin); assertEquals(130, there.totalMin)
        // No Metro route: nothing to say.
        assertNull(Flights.plan(Airport.DCA, dep, "N06", 120) { _, _ -> null })
    }

    @Test fun airportStationsAreRealMetroStations() {
        Airport.entries.forEach { assertNotNull(it.code, StationDirectory.find(it.metroCode)) }
        assertFalse(Airport.entries.any { it.code == "BWI" })
    }
}
