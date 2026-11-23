package com.metrocast.app

import com.metrocast.app.data.LinePath
import com.metrocast.app.data.TrackWork
import com.metrocast.app.data.TrackWork.Kind
import com.metrocast.app.data.TrackWork.Level
import com.metrocast.app.data.Trip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.time.LocalDate
import kotlin.random.Random

/** WMATA's planned work, parsed from the real recorded pages, and what it means for different trips. */
class TrackWorkTest {
    companion object {
        lateinit var paths: List<LinePath>
        lateinit var list: List<TrackWork.Advisory>
        @BeforeClass @JvmStatic fun load() {
            StressTest.load()
            paths = StressTest.paths
            val raw = File("src/test/resources/live/advisories.json").readText()
            list = TrackWork.parseList(raw).map { a ->
                when (a.kind) {
                    Kind.WEEKEND -> a.copy(changes = TrackWork.parsePage(File("src/test/resources/live/weekend.html").readText(), a.start.year))
                    Kind.WEEKDAY -> a.copy(changes = TrackWork.parsePage(File("src/test/resources/live/weekday.html").readText(), a.start.year))
                    else -> a
                }
            }
        }
        val THU = LocalDate.of(2026, 11, 19)
    }

    private fun trip(from: String, to: String, label: String = Trip.GOING): Trip {
        val r = com.metrocast.app.data.Planner.plan(com.metrocast.app.data.StationDirectory.find(from)!!, com.metrocast.app.data.StationDirectory.find(to)!!, paths)!!
        val o = r.legs.first().options.first()
        return Trip("$from-$to", label, o.line, o.boardCode, from, o.alightCode, "", finalCode = if (r.legs.size > 1) to else null, finalName = if (r.legs.size > 1) to else null)
    }

    private fun impacts(t: Trip, today: LocalDate = THU) = TrackWork.impacts(list, listOf(t to TrackWork.route(t, paths)!!), paths, today)

    // ---------------------------------------------------------------- reading WMATA's pages

    @Test fun listHasRailWorkWithDatesLinesAndStations() {
        val weekend = list.single { it.kind == Kind.WEEKEND }
        assertEquals(LocalDate.of(2026, 11, 21), weekend.start); assertEquals(LocalDate.of(2026, 11, 22), weekend.end)
        assertEquals(setOf("SV", "BL", "OR"), weekend.lines.toSet())
        val weekday = list.single { it.kind == Kind.WEEKDAY }
        assertEquals(LocalDate.of(2026, 11, 23), weekday.start); assertEquals(LocalDate.of(2026, 11, 27), weekday.end)
        val major = list.single { it.kind == Kind.MAJOR }
        assertEquals(LocalDate.of(2026, 12, 18), major.start); assertEquals(LocalDate.of(2026, 12, 30), major.end)
        assertTrue(major.stations.containsAll(listOf("A01", "C01", "C02", "D01", "E04")))
        // Station notices (escalators, parking, canopies) keep their stations; bus-only items are dropped.
        assertTrue(list.any { it.kind == Kind.STATION && it.stations == listOf("C04") && it.title.contains("Foggy Bottom") })
        assertTrue(list.all { it.lines.isNotEmpty() && it.url.startsWith("https://www.wmata.com/") })
    }

    @Test fun weekendPageDetails() {
        val c = list.single { it.kind == Kind.WEEKEND }.changes
        assertEquals(listOf("OR", "SV", "BL"), c.map { it.line })
        val sv = c.single { it.line == "SV" }
        assertTrue(sv.text, sv.text.startsWith("Modified service: All trains run from Ashburn to Federal Center SW only"))
        assertTrue(sv.text.endsWith("Expect 24 minutes between trains"))
        assertTrue(sv.onlyBetween); assertEquals("N12", sv.from); assertEquals("D04", sv.to)
        val or = c.single { it.line == "OR" }
        assertEquals("Single tracking: Eastern Market to Minnesota Av · Expect 24 minutes between trains", or.text)
        assertEquals("D06", or.from); assertEquals("D09", or.to); assertTrue(!or.onlyBetween)
        assertEquals("G01", c.single { it.line == "BL" }.to)
        c.forEach { assertEquals(LocalDate.of(2026, 11, 21), it.start); assertEquals(LocalDate.of(2026, 11, 22), it.end); assertTrue(it.days.startsWith("Saturday - Sunday")) }
    }

    @Test fun weekdayPageOnlyFridayIsImpacted() {
        val c = list.single { it.kind == Kind.WEEKDAY }.changes
        assertEquals(listOf("YL", "GR"), c.map { it.line })
        c.forEach { assertEquals(LocalDate.of(2026, 11, 27), it.start); assertEquals(it.start, it.end) }
        val yl = c[0]; assertTrue(yl.onlyBetween); assertEquals("C15", yl.from); assertEquals("E01", yl.to)
        assertTrue(yl.text.startsWith("After 10 pm"))
        assertEquals("E08", c[1].from); assertEquals("E09", c[1].to)
    }

    // ---------------------------------------------------------------- what it means for a trip

    @Test fun wiehleToFoggyBottomSilverStillRunsButLongerWaits() {
        val i = impacts(trip("N06", "C04"))
        val weekend = i.filter { it.advisory.kind == Kind.WEEKEND }
        assertEquals(listOf("SV" to Level.LINE), weekend.map { it.change!!.line to it.level })
        // Foggy Bottom escalator work shows as a notice at your station; Winter construction is on the line.
        assertTrue(i.any { it.level == Level.STATION && it.advisory.title.contains("Foggy Bottom") })
        assertEquals(Level.LINE, i.single { it.advisory.kind == Kind.MAJOR }.level)
        assertTrue(i.none { it.advisory.kind == Kind.WEEKDAY })
    }

    @Test fun wiehleToEasternMarketIsCutOff() {
        assertEquals(Level.BLOCKED, impacts(trip("N06", "D06")).first { it.advisory.kind == Kind.WEEKEND }.level)
    }

    @Test fun orangeIntoTheSingleTrackingIsInTheZone() {
        val i = impacts(trip("K08", "D09")).filter { it.advisory.kind == Kind.WEEKEND }
        assertEquals(Level.IN_ZONE, i.single().level)
    }

    @Test fun sharedTrackHasAnotherLine() {
        // Foggy Bottom → Eastern Market: Orange and Blue run it too, so the Silver cut isn't a dead end.
        val i = impacts(trip("C04", "D06")).filter { it.advisory.kind == Kind.WEEKEND }
        assertTrue(i.none { it.level == Level.BLOCKED })
    }

    @Test fun greenFridayAndWinterConstruction() {
        val i = impacts(trip("E10", "F05"))
        assertEquals(Level.IN_ZONE, i.single { it.advisory.kind == Kind.WEEKDAY }.level)
        assertEquals(LocalDate.of(2026, 11, 27), i.single { it.advisory.kind == Kind.WEEKDAY }.start)
        // The trip back passes through the same work.
        assertEquals(Level.IN_ZONE, impacts(trip("F05", "E10", Trip.BACK)).single { it.advisory.kind == Kind.WEEKDAY }.level)
        // Via Gallery Place/L'Enfant during the Winter work: stations in the work zone.
        assertEquals(Level.IN_ZONE, impacts(trip("N06", "F05")).single { it.advisory.kind == Kind.MAJOR }.level)
    }

    @Test fun redLineTripIsClearAndStationNoticesOnlyAtYourStops() {
        val i = impacts(trip("A15", "B11"))
        assertTrue(i.toString(), i.none { it.level != Level.STATION })
        // Rockville's elevator work: a notice when you board there, nothing when you ride through.
        assertTrue(impacts(trip("A14", "A01")).any { it.level == Level.STATION && it.advisory.title.contains("Rockville") })
        assertTrue(i.none { it.advisory.title.contains("Rockville") })
    }

    @Test fun finishedWorkDisappears() {
        val after = impacts(trip("N06", "C04"), today = LocalDate.of(2026, 11, 23))
        assertTrue(after.none { it.advisory.kind == Kind.WEEKEND })
        assertTrue(impacts(trip("N06", "C04"), today = LocalDate.of(2027, 2, 1)).none { it.advisory.kind.service })
    }

    @Test fun randomTripsNeverCrashAndStayConsistent() {
        val st = com.metrocast.app.data.StationDirectory.stations.filter { s -> paths.any { p -> p.codes.any { it in s.codes } } }
        val rnd = Random(9); var n = 0
        repeat(600) {
            val a = st.random(rnd); val b = st.random(rnd); if (a.code == b.code) return@repeat
            val t = trip(a.code, b.code)
            val route = TrackWork.route(t, paths)!!
            val used = route.flatMap { it.lines }.toSet()
            for (i in impacts(t)) {
                n++
                assertTrue(i.end >= THU)
                if (i.level != Level.STATION) assertTrue("${a.name}→${b.name}: ${i.advisory.title} isn't on its lines", (i.change?.line?.let { listOf(it) } ?: i.advisory.lines).any { it in used })
                if (i.level == Level.BLOCKED) assertTrue(i.change!!.onlyBetween)
            }
        }
        println("Track-work impacts checked: $n")
        assertTrue(n > 300)
    }

    // ---------------------------------------------------------------- dates, labels, bad data

    @Test fun datesAndLabels() {
        assertEquals(LocalDate.of(2026, 11, 21) to LocalDate.of(2026, 11, 22), TrackWork.dateRange("November 21 - 22", 2026))
        assertEquals(LocalDate.of(2026, 11, 27) to LocalDate.of(2026, 11, 27), TrackWork.dateRange("November 27", 2026))
        assertEquals(LocalDate.of(2026, 11, 30) to LocalDate.of(2026, 12, 2), TrackWork.dateRange("Nov. 30 - Dec. 2", 2026))
        assertEquals(LocalDate.of(2026, 12, 30) to LocalDate.of(2027, 1, 2), TrackWork.dateRange("December 30 – January 2", 2026))
        assertEquals(null to null, TrackWork.dateRange("soon", 2026))
        assertEquals("Sat–Sun, Nov 21 – 22", TrackWork.dates(LocalDate.of(2026, 11, 21), LocalDate.of(2026, 11, 22)))
        assertEquals("Fri, Nov 27", TrackWork.dates(LocalDate.of(2026, 11, 27), LocalDate.of(2026, 11, 27)))
        assertEquals("Dec 18 – 30", TrackWork.dates(LocalDate.of(2026, 12, 18), LocalDate.of(2026, 12, 30)))
        assertEquals("In 2 days", TrackWork.whenLabel(LocalDate.of(2026, 11, 21), LocalDate.of(2026, 11, 22), THU))
        assertEquals("Tomorrow", TrackWork.whenLabel(LocalDate.of(2026, 11, 20), LocalDate.of(2026, 11, 20), THU))
        assertEquals("Happening now", TrackWork.whenLabel(LocalDate.of(2026, 11, 18), LocalDate.of(2026, 11, 22), THU))
        assertEquals("In 4 weeks", TrackWork.whenLabel(LocalDate.of(2026, 12, 18), LocalDate.of(2026, 12, 30), THU))
    }

    @Test fun badDataNeverCrashes() {
        for (g in listOf("", "{", "null", "[]", "{}", """{"advisoryResults":"x"}""", """{"advisoryResults":[{}]}""",
            """{"advisoryResults":[{"properties":{"relatedRailLines":[{"longName":"Purple"}],"effectiveDateStartEpoch":"x"}}]}""", "<html>503</html>")) {
            assertTrue(TrackWork.parseList(g).isEmpty())
            TrackWork.parsePage(g, 2026)
        }
        assertTrue(TrackWork.parsePage("<div class=\"cmp-text\"><h2>Sat: Impacted service</h2></div><div class=\"cmp-text\"><p>Teal Line:</p><ul><li>x</li></ul></div>", 2026).isEmpty())
        assertTrue(TrackWork.fromJson("garbage").isEmpty())
    }

    @Test fun cacheRoundTripKeepsEverything() {
        assertEquals(list, TrackWork.fromJson(TrackWork.toJson(list)))
        // Only public wmata.com pages are fetched; nothing about you goes in the address.
        assertTrue(TrackWork.LIST_URL.startsWith("https://www.wmata.com/") && '?' !in TrackWork.LIST_URL)
    }
}
