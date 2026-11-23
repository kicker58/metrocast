package com.metrocast.app

import com.metrocast.app.data.GtfsRealtime
import com.metrocast.app.data.Rail
import com.metrocast.app.data.RailRun
import com.metrocast.app.data.RailService
import com.metrocast.app.data.RailTrain
import com.metrocast.app.data.StationDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime

/** Amtrak, MARC and VRE: reading the real published schedules, leave-by times and live status. */
class RailTest {
    companion object {
        lateinit var amtrak: List<RailTrain>; lateinit var marc: List<RailTrain>; lateinit var vre: List<RailTrain>
        @BeforeClass @JvmStatic fun load() {
            StationDirectory.load(File("src/test/resources/live/stations.json").readText())
            amtrak = Rail.parseGtfs(RailService.AMTRAK, File("src/test/resources/live/rail_amtrak.zip").readBytes())
            marc = Rail.parseGtfs(RailService.MARC, File("src/test/resources/live/rail_marc.zip").readBytes())
            vre = Rail.parseGtfs(RailService.VRE, File("src/test/resources/live/rail_vre.zip").readBytes())
        }
        // A weekday inside every feed's dates.
        val MONDAY: LocalDate = LocalDate.of(2026, 11, 23)
        /** Rough Metro times for tests: 2 min a stop, as the app estimates. */
        fun rides(from: String, to: String): Pair<Int, Boolean> = (if (from == to) 0 else 30) to true
    }

    @Test fun marcLinesStopWhereMetroDoes() {
        assertTrue("MARC trains: ${marc.size}", marc.size > 50)
        val routes = marc.map { it.route }.toSet()
        assertTrue(routes.toString(), routes.containsAll(setOf("Penn Line", "Camden Line", "Brunswick Line")))
        val penn = marc.filter { it.route == "Penn Line" && it.stops.first().metroCode == "B03" }
        assertTrue(penn.isNotEmpty())
        // Northbound Penn Line from Union Station also stops at New Carrollton: an alternative.
        assertTrue(penn.any { t -> t.stops.map { it.metroCode } == listOf("B03", "D13") })
        assertTrue(marc.filter { it.route == "Camden Line" }.flatMap { t -> t.stops.map { it.metroCode } }.toSet().containsAll(setOf("B03", "E09", "E10")))
        assertTrue(marc.filter { it.route == "Brunswick Line" }.flatMap { t -> t.stops.map { it.metroCode } }.toSet().containsAll(setOf("B03", "B08", "A14")))
        // Numbers are plain ("401"), headsigns readable ("Baltimore", not "BALTIMORE").
        assertTrue(marc.all { it.number.all(Char::isDigit) })
        assertTrue(marc.any { it.headsign == "Baltimore" })
        // Trains don't "pick up" at the end of their run.
        assertTrue(marc.none { t -> t.headsign == "Washington" && t.stops.any { it.metroCode == "B03" } })
    }

    @Test fun vreStopsAndAlternatives() {
        assertTrue("VRE trains: ${vre.size}", vre.size >= 20)
        // Outbound Fredericksburg trains: Union Station, L'Enfant, Crystal City, Alexandria, Franconia.
        val fred = vre.filter { it.route == "Fredericksburg Line" && it.stops.first().metroCode == "B03" }
        assertTrue(fred.isNotEmpty())
        assertTrue(fred.any { t -> t.stops.map { it.metroCode } == listOf("B03", "D03", "C09", "C13", "J03") })
        val man = vre.filter { it.route == "Manassas Line" && it.stops.first().metroCode == "B03" }
        assertTrue(man.isNotEmpty()); assertTrue(man.all { t -> t.stops.none { it.metroCode == "J03" } })
        // No VRE on weekends.
        assertTrue(vre.none { it.runsOn(LocalDate.of(2026, 11, 22)) })
        assertTrue(vre.any { it.runsOn(MONDAY) })
        // Thanksgiving: no service (calendar_dates).
        assertTrue(vre.none { it.runsOn(LocalDate.of(2026, 11, 26)) })
    }

    @Test fun amtrakNortheastRegionalFromUnionStationAndNewCarrollton() {
        assertTrue("Amtrak trains: ${amtrak.size}", amtrak.size > 30)
        assertTrue(amtrak.none { it.route.contains("Thruway") })
        assertTrue(amtrak.none { it.route.contains("Commuter") }) // MARC trains are MARC's
        val ner = amtrak.filter { it.route == "Northeast Regional" && it.runsOn(MONDAY) && it.stops.first().metroCode == "B03" }
        assertTrue(ner.isNotEmpty())
        assertTrue(ner.any { t -> t.stops.any { it.metroCode == "D13" } })
        // Searchable by where it goes.
        assertTrue(ner.any { it.matches("new york") }); assertTrue(ner.any { it.matches("boston") })
        assertTrue(amtrak.any { it.matches(it.number) })
        // Live status uses Amtrak's station codes.
        assertTrue(amtrak.flatMap { it.stops }.all { it.stopId in setOf("WAS", "NCR", "ALX", "RKV") })
    }

    @Test fun leaveByFromWiehleForA1pmTrain() {
        // Northbound (Union Station first, then New Carrollton), around 1 pm.
        val ner = amtrak.filter { it.route == "Northeast Regional" && it.runsOn(MONDAY) && it.stops.map { s -> s.metroCode } == listOf("B03", "D13") }
            .minByOrNull { kotlin.math.abs(it.stops.first().dep - 13 * 3600) }!!
        val run = RailRun(ner, MONDAY)
        val now = MONDAY.atTime(8, 0)
        val opts = Rail.options(run, "N06", now, 30) { f, t -> rides(f, t) }
        assertEquals(setOf("B03", "D13"), opts.map { it.station.metroCode }.toSet())
        val union = opts.first { it.station.metroCode == "B03" }
        // 30 min Metro + 5 waiting + 6 walking + 30 early for Amtrak = 71 minutes before it leaves.
        assertEquals(71, union.totalMin)
        assertEquals(run.timeAt(union.stop).minusMinutes(71), union.leaveBy)
        // Best first: the latest you can leave.
        assertTrue(opts.zipWithNext().all { (a, b) -> !a.leaveBy.isBefore(b.leaveBy) })
        // Starting at Union Station itself: no Metro ride, no wait.
        val atUnion = Rail.options(run, "B03", now, 30) { f, t -> rides(f, t) }.first { it.station.metroCode == "B03" }
        assertEquals(0, atUnion.metroMin); assertEquals(0, atUnion.waitMin)
        // Once the train has left a station, that one isn't offered.
        assertTrue(Rail.options(run, "N06", run.timeAt(ner.stops.first()).plusMinutes(1), 30) { f, t -> rides(f, t) }.none { it.station.metroCode == "B03" })
        // A different "be there early" setting changes it.
        assertEquals(56, Rail.options(run, "N06", now, 15) { f, t -> rides(f, t) }.first { it.station.metroCode == "B03" }.totalMin)
    }

    @Test fun upcomingListIsSortedAndSkipsGoneTrains() {
        val all = amtrak + marc + vre
        val now = MONDAY.atTime(12, 0)
        val up = Rail.upcoming(all, now)
        assertTrue(up.size > 40)
        val firstTimes = up.map { r -> r.train.stops.map { r.timeAt(it) }.filter { !it.isBefore(now) }.min() }
        assertEquals(firstTimes.sorted(), firstTimes)
        assertTrue(firstTimes.all { !it.isBefore(now) && it.isBefore(now.plusHours(24)) })
        assertTrue(up.map { it.train.service }.toSet() == RailService.entries.toSet())
        // No double listings.
        assertEquals(up.size, up.map { "${it.train.service}${it.train.number}${it.first}" }.distinct().size)
    }

    @Test fun amtrakAskedOnceAMinute() {
        assertEquals(60, Rail.liveRefreshSeconds(RailService.AMTRAK, 30))
        assertEquals(30, Rail.liveRefreshSeconds(RailService.MARC, 15))   // never faster than 30 s
        assertEquals(60, Rail.liveRefreshSeconds(RailService.VRE, 60))
    }

    @Test fun savedAndLoadedTheSame() {
        val all = amtrak + marc + vre
        assertEquals(all, Rail.fromJson(Rail.toJson(all)))
        assertTrue(Rail.fromJson("garbage").isEmpty()); assertTrue(Rail.fromJson("[{}]").isEmpty())
    }

    @Test fun csvHandlesQuotes() {
        val rows = Rail.csv("a,b,c\r\n\"x, y\",\"he said \"\"hi\"\"\",3\r\n")
        assertEquals("x, y", rows[0]["a"]); assertEquals("he said \"hi\"", rows[0]["b"]); assertEquals("3", rows[0]["c"])
        assertEquals(25 * 3600 + 600, Rail.secs("25:10:00"))
    }

    // ---------------------------------------------------------------- live

    @Test fun marcLiveDelayAndCancel() {
        val t = marc.first { it.runsOn(MONDAY) && it.stops.size >= 2 }
        val run = RailRun(t, MONDAY); val stop = t.stops.first()
        assertEquals(7, Rail.liveFromFeed(feed(trip(t.tripId, stops = listOf(stu(stop.stopId, delay = 420)))), run, stop).delayMin)
        assertEquals("7 min late", Rail.liveFromFeed(feed(trip(t.tripId, stops = listOf(stu(stop.stopId, delay = 420)))), run, stop).text)
        // Only a time given: worked out against the schedule.
        val sched = run.timeAt(stop).atZone(Rail.ZONE).toEpochSecond()
        assertEquals(3, Rail.liveFromFeed(feed(trip(t.tripId, stops = listOf(stu(stop.stopId, time = sched + 180)))), run, stop).delayMin)
        // Delay only for an earlier stop: carried forward.
        assertEquals(4, Rail.liveFromFeed(feed(trip(t.tripId, stops = listOf(stu("other", delay = 240)))), run, stop).delayMin)
        assertEquals("On time", Rail.liveFromFeed(feed(trip(t.tripId, stops = listOf(stu(stop.stopId, delay = 30)))), run, stop).text)
        assertEquals("2 min early", Rail.liveFromFeed(feed(trip(t.tripId, stops = listOf(stu(stop.stopId, delay = -120)))), run, stop).text)
        assertTrue(Rail.liveFromFeed(feed(trip(t.tripId, canceled = true)), run, stop).canceled)
        // Not in the feed (not running yet).
        val none = Rail.liveFromFeed(feed(trip("Train999999")), run, stop)
        assertNull(none.delayMin); assertTrue(none.text.contains("No live update"))
        // Today's real (empty, Sunday) feed and garbage don't break anything.
        assertTrue(GtfsRealtime.tripUpdates(File("src/test/resources/live/marc_tu_sunday.pb").readBytes()).isEmpty())
        assertTrue(GtfsRealtime.tripUpdates(byteArrayOf(0x12, 0x7f, 1, 2)).isEmpty())
        assertTrue(GtfsRealtime.tripUpdates(ByteArray(0)).isEmpty())
    }

    @Test fun vreLiveMatchesItsTripIds() {
        val t = vre.first { it.runsOn(MONDAY) }
        val run = RailRun(t, MONDAY); val stop = t.stops.last()
        assertEquals(12, Rail.liveFromFeed(feed(trip(t.tripId, delay = 720)), run, stop).delayMin)
        // VRE sometimes uses just the train number.
        assertEquals(12, Rail.liveFromFeed(feed(trip("${t.number}-Holiday", delay = 720)), run, stop).delayMin)
    }

    @Test fun amtrakLiveFromAmtraker() {
        val t = amtrak.first { it.runsOn(MONDAY) && it.stops.any { s -> s.stopId == "WAS" } }
        val run = RailRun(t, MONDAY); val stop = t.stops.first { it.stopId == "WAS" }
        val sch = run.timeAt(stop).atZone(Rail.ZONE).toOffsetDateTime()
        fun json(dep: java.time.OffsetDateTime, status: String = "Enroute") =
            """{"${t.number}":[{"trainNum":"${t.number}","stations":[{"code":"WAS","schDep":"$sch","dep":"$dep","status":"$status"}]}]}"""
        assertEquals("On time", Rail.liveFromAmtraker(json(sch), run, stop).text)
        assertEquals(15, Rail.liveFromAmtraker(json(sch.plusMinutes(15)), run, stop).delayMin)
        assertTrue(Rail.liveFromAmtraker(json(sch.plusMinutes(15)), run, stop).late)
        assertTrue(Rail.liveFromAmtraker(json(sch, "Departed"), run, stop).text.contains("Already left") || Rail.liveFromAmtraker(json(sch, "Departed"), run, stop).text == "On time")
        // Another day's run of the same number isn't mixed up with this one.
        assertNull(Rail.liveFromAmtraker(json(sch.plusDays(1)).replace("\"dep\":\"${sch.plusDays(1)}\"", "\"dep\":\"${sch.plusDays(1).plusMinutes(40)}\"").replace("\"schDep\":\"$sch\"", "\"schDep\":\"${sch.plusDays(1)}\""), run, stop).delayMin)
        assertTrue(Rail.liveFromAmtraker("{}", run, stop).text.contains("Not running yet"))
        assertNull(Rail.liveFromAmtraker("garbage", run, stop).delayMin)
        // The real recorded answer (all trains) parses without trouble.
        Rail.liveFromAmtraker(File("src/test/resources/live/amtraker_all.json").readText(), run, stop)
    }

    // ---------------------------------------------------------------- built-in copy

    /** Run with -Prailgen=<dir with gtfs_GTFS.zip, marc.zip, vre.zip> to refresh the app's built-in schedules. */
    @Test fun generateBuiltIn() {
        val dir = System.getProperty("metrocast.railgen"); assumeTrue(dir != null)
        val src = mapOf(RailService.AMTRAK to "gtfs_GTFS.zip", RailService.MARC to "marc.zip", RailService.VRE to "rec/vre.zip")
        src.forEach { (s, f) ->
            // Only runs that haven't ended (old seasonal timetables are dead weight in the app).
            val today = java.time.LocalDate.now().let { it.year * 10000 + it.monthValue * 100 + it.dayOfMonth }
            val trains = Rail.parseGtfs(s, File(dir, f).readBytes()).filter { t -> t.end >= today || t.added.any { it >= today } }
            assertTrue(trains.size > 10)
            File("src/main/assets/rail_${s.name}.json").writeText(Rail.toJson(trains))
        }
    }

    @Test fun builtInCopyIsThereAndReadable() {
        RailService.entries.forEach { s ->
            val f = File("src/main/assets/rail_${s.name}.json")
            assertTrue("$f missing", f.exists())
            val trains = Rail.fromJson(f.readText())
            assertTrue("${s.name}: ${trains.size}", trains.size > 10)
            assertTrue(trains.all { it.service == s })
        }
    }

    // ---- a tiny GTFS-realtime writer, to make live feeds for tests
    private fun varint(o: ByteArrayOutputStream, v0: Long) { var v = v0; while (true) { if (v and 0x7fL.inv() == 0L) { o.write(v.toInt()); return }; o.write(((v and 0x7f) or 0x80).toInt()); v = v ushr 7 } }
    private fun field(o: ByteArrayOutputStream, n: Int, bytes: ByteArray) { varint(o, (n shl 3 or 2).toLong()); varint(o, bytes.size.toLong()); o.write(bytes) }
    private fun num(o: ByteArrayOutputStream, n: Int, v: Long) { varint(o, (n shl 3).toLong()); varint(o, v) }
    private fun msg(b: (ByteArrayOutputStream) -> Unit) = ByteArrayOutputStream().also(b).toByteArray()
    private fun stu(stopId: String, delay: Int? = null, time: Long? = null) = msg { o ->
        num(o, 1, 1)
        field(o, 4, stopId.toByteArray())
        field(o, 3, msg { e -> delay?.let { num(e, 1, it.toLong()) }; time?.let { num(e, 2, it) } })
    }
    private fun trip(id: String, delay: Int? = null, canceled: Boolean = false, stops: List<ByteArray> = emptyList()) = msg { o ->
        field(o, 1, msg { d -> field(d, 1, id.toByteArray()); if (canceled) num(d, 4, 3) })
        stops.forEach { field(o, 2, it) }
        delay?.let { num(o, 5, it.toLong()) }
    }
    private fun feed(vararg trips: ByteArray) = msg { o ->
        field(o, 1, msg { h -> field(h, 1, "2.0".toByteArray()) })
        trips.forEachIndexed { i, t -> field(o, 2, msg { e -> field(e, 1, "e$i".toByteArray()); field(e, 3, t) }) }
    }
}
