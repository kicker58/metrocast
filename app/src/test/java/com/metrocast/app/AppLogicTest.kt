package com.metrocast.app

import com.metrocast.app.data.ArrivalKind
import com.metrocast.app.data.Arrivals
import com.metrocast.app.data.Arrival
import com.metrocast.app.data.Branches
import com.metrocast.app.data.DestinationCodes
import com.metrocast.app.data.LegArrival
import com.metrocast.app.data.LineMapBuilder
import com.metrocast.app.data.LiveEngine
import com.metrocast.app.data.Planner
import com.metrocast.app.data.StationDirectory
import com.metrocast.app.data.Stations
import com.metrocast.app.data.TrainTracker
import com.metrocast.app.data.Trip
import com.metrocast.app.data.Verdict
import com.metrocast.app.data.WmataApi
import com.metrocast.app.ui.Format
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/** Checks of the app's logic on real (recorded) WMATA data and on made-up edge cases. */
class AppLogicTest {

    companion object {
        private val dir = File("src/test/resources/live")
        fun read(name: String) = File(dir, name).readText()
        lateinit var routes: List<TrainTracker.Route>
        lateinit var orders: Map<String, List<com.metrocast.app.data.Station>>
        lateinit var paths: List<com.metrocast.app.data.LinePath>

        @BeforeClass @JvmStatic
        fun load() {
            StationDirectory.load(read("stations.json"))
            routes = TrainTracker.parseRoutes(read("routes.json"))
            orders = listOf("RD", "OR", "SV", "BL", "YL", "GR").mapNotNull { l ->
                LiveEngine.lineOrder(routes, l) { StationDirectory.nameOf(it) }?.let { l to it }
            }.toMap()
            paths = Planner.paths(orders)
        }

        fun st(code: String) = StationDirectory.find(code) ?: error("no station $code")

        /** A made-up WMATA prediction. */
        fun raw(loc: String, line: String, dest: String?, min: String, name: String = dest?.let { StationDirectory.nameOf(it) } ?: "", group: String = "1") =
            WmataApi.RawTrain(cars = "8", destinationName = name, destinationCode = dest, group = group, line = line, locationCode = loc, min = min)
    }

    // ---------------------------------------------------------------- 1. destination abbreviations

    @Test fun signAbbreviationsResolve() {
        val cases = mapOf(
            "Fed Ctr SW" to "D04", "New Crlton" to "D13", "NewCrlton" to "D13", "Branch Av" to "F11", "BRANCH AV" to "F11",
            "Greenbelt" to "E10", "Huntington" to "C15", "Mt Vern Sq" to "E01", "MtVern Sq" to "E01", "MT VERNON SQ" to "E01",
            "Shady Grv" to "A15", "Shady Grove" to "A15", "VIENNA" to "K08", "Largo" to "G05", "Ashburn" to "N12",
            "Glenmont" to "B11", "SilvrSpg" to "B08", "Grsvnor" to "A11", "Frnconia" to "J03",
        )
        cases.forEach { (abbr, code) -> assertEquals("'$abbr'", code, DestinationCodes.resolve(abbr, null)) }
        assertNull(DestinationCodes.resolve("No Passenger", "No"))
        assertNull(DestinationCodes.resolve("", "RD"))
        // Every station's own full name resolves to that station.
        StationDirectory.stations.forEach { s ->
            val got = DestinationCodes.resolve(s.name, s.lines.firstOrNull())
            assertTrue("${s.name} → $got", got != null && got in s.codes)
        }
    }

    @Test fun recordedPredictionsAllGetACode() {
        (1..5).forEach { i ->
            val trains = WmataApi.parsePredictions(read("pred_$i.json"))
            val passenger = trains.filter { it.line in setOf("RD", "OR", "SV", "BL", "YL", "GR") }
            val missing = passenger.filter { it.destinationCode == null }.map { it.destinationName }.distinct()
            assertTrue("snapshot $i: trains still without a destination code: $missing", missing.isEmpty())
        }
    }

    // ---------------------------------------------------------------- 2–3. trip planner

    @Test fun everyStationPairHasASensibleRoute() {
        val all = StationDirectory.stations.filter { s -> paths.any { p -> p.codes.any { it in s.codes } } }
        assertTrue("expected ~98 stations served, got ${all.size}", all.size >= 95)
        val t0 = System.currentTimeMillis()
        var count = 0
        var worstTransfers = 0
        for (a in all) for (b in all) {
            if (a.code == b.code) continue
            val r = Planner.plan(a, b, paths)
            assertNotNull("no route ${a.name} → ${b.name}", r)
            r!!
            count++
            worstTransfers = maxOf(worstTransfers, r.transfers)
            assertTrue("${a.name} → ${b.name}: ${r.transfers} transfers", r.transfers <= 2)
            assertEquals(a.code, r.legs.first().from.code)
            assertEquals(b.code, r.legs.last().to.code)
            r.legs.zipWithNext { x, y -> assertEquals("legs don't connect ${a.name} → ${b.name}", x.to.code, y.from.code) }
            r.legs.forEach { l ->
                assertTrue("empty leg in ${a.name} → ${b.name}", l.stops > 0)
                assertTrue("no line option in ${a.name} → ${b.name}", l.options.isNotEmpty())
                l.options.forEach { o -> assertTrue(o.boardCode in l.from.codes && o.alightCode in l.to.codes) }
            }
        }
        val ms = System.currentTimeMillis() - t0
        println("Planned $count trips in $ms ms (worst: $worstTransfers transfers)")
        assertTrue("planner too slow: ${ms / count.coerceAtLeast(1)} ms per trip", ms / count.coerceAtLeast(1) < 20)
    }

    @Test fun knownTripsMatchWhatARiderWouldDo() {
        data class Case(val from: String, val to: String, val transfersAt: List<String>, val firstLines: Set<String>)
        val cases = listOf(
            Case("N06", "C04", emptyList(), setOf("SV")),                  // Wiehle → Foggy Bottom: direct Silver
            Case("C04", "C01", emptyList(), setOf("OR", "SV", "BL")),      // shared track: any of three
            Case("K05", "D04", emptyList(), setOf("OR", "SV")),            // East Falls Church → Federal Center SW
            Case("N06", "B03", listOf("Metro Center"), setOf("SV")),       // to Union Station via Red
            Case("N06", "F05", listOf("L'Enfant Plaza"), setOf("SV")),     // to Navy Yard via Green
            Case("A15", "F05", listOf("Gallery Pl-Chinatown"), setOf("RD")),
            Case("C15", "B11", listOf("Gallery Pl-Chinatown"), setOf("YL")),  // Yellow ends at Mt Vernon Sq
            Case("J03", "E10", listOf("L'Enfant Plaza"), setOf("BL")),
            Case("N06", "D13", emptyList(), setOf("SV")),                  // Silver to New Carrollton branch
            Case("N12", "N06", emptyList(), setOf("SV")),                  // Ashburn → Wiehle
            Case("A01", "B01", emptyList(), setOf("RD")),                  // Metro Center → Gallery Place, Red
        )
        cases.forEach { c ->
            val r = Planner.plan(st(c.from), st(c.to), paths)!!
            assertEquals("${c.from}→${c.to} transfers", c.transfersAt, r.legs.dropLast(1).map { it.to.name })
            assertEquals("${c.from}→${c.to} first-leg lines", c.firstLines, r.legs.first().options.map { it.line }.toSet())
        }
    }

    // ---------------------------------------------------------------- 4. line orders

    @Test fun lineOrdersAreComplete() {
        val expect = mapOf(
            "RD" to Triple("A15", "B11", 27), "OR" to Triple("K08", "D13", 26), "SV" to Triple("N12", "G05", 34),
            "BL" to Triple("J03", "G05", 28), "YL" to Triple("C15", "E01", 13), "GR" to Triple("F11", "E10", 21),
        )
        expect.forEach { (line, e) ->
            val o = orders.getValue(line).map { it.code }
            assertEquals("$line first", e.first, o.first())
            assertEquals("$line last", e.second, o.last())
            assertEquals("$line count", e.third, o.size)
            assertEquals("$line has duplicates", o.size, o.toSet().size)
            orders.getValue(line).forEach { s -> assertFalse("$line: station shown as a code: ${s.name}", s.name.matches(Regex("[A-Z]\\d\\d"))) }
        }
    }

    @Test fun everyStationIsOnEachOfItsLines() {
        val missing = ArrayList<String>()
        StationDirectory.stations.forEach { s ->
            s.lines.forEach { line ->
                // Yellow no longer runs north of Mt Vernon Sq; WMATA still lists Fort Totten etc. as Yellow.
                val o = orders[line]?.map { it.code }.orEmpty() + if (line == "SV") LineMapBuilder.BRANCH.map { it.code } else emptyList()
                if (s.codes.none { it in o } && !(line == "YL" && s.codes.any { it.startsWith("E") && it != "E01" })) missing += "${s.name} ($line)"
            }
        }
        assertTrue("stations missing from their lines: $missing", missing.isEmpty())
        val p = Planner.plan(st("N06"), st("C11"), paths)
        assertNotNull("can't plan a trip to Potomac Yard", p)
    }

    // ---------------------------------------------------------------- 5. next-train filtering

    private fun trip(station: String, toward: String, line: String = "SV") = Trip("t", "", line, station, "", toward, "")
    private val sv = Stations.SILVER.map { it.code }

    @Test fun arrivalsPickTheRightDirectionAndDestinations() {
        val trains = listOf(
            raw("N06", "SV", "D04", "4"),                // to Federal Center SW (east, short trip)
            raw("N06", "SV", "N12", "2", group = "2"),   // to Ashburn (west)
            raw("N06", "SV", "G05", "11"),               // to Largo
            raw("N06", "SV", "D13", "18"),               // to New Carrollton (branch)
            raw("N06", "OR", "D13", "1"),                // other line at another station? (wrong line)
            raw("C04", "SV", "G05", "3"),                // other station
        )
        // To DC (Largo end): every eastbound Silver train, including New Carrollton and short trips.
        val toDc = Arrivals.filter(trip("N06", "G05"), trains, sv, 0).map { it.destination }
        assertEquals(listOf("Federal Center SW", "Downtown Largo", "New Carrollton"), toDc)
        // To Foggy Bottom: Federal Center SW trains get you there too.
        assertEquals(3, Arrivals.filter(trip("N06", "C04"), trains, sv, 0).size)
        // To Benning Road (Largo branch): only the Largo train; Federal Center SW ends too early, New Carrollton goes the other way.
        assertEquals(listOf("Downtown Largo"), Arrivals.filter(trip("N06", "G01"), trains, sv, 0).map { it.destination })
        // To Cheverly (New Carrollton branch): only the New Carrollton train.
        assertEquals(listOf("New Carrollton"), Arrivals.filter(trip("N06", "D11"), trains, sv, 0).map { it.destination })
        // Westbound.
        assertEquals(listOf("Ashburn"), Arrivals.filter(trip("N06", "N12"), trains, sv, 0).map { it.destination })
    }

    @Test fun arrivalsHandleBoardingArrivingAndOldData() {
        val trains = listOf(raw("N06", "SV", "G05", "BRD"), raw("N06", "SV", "G05", "ARR"), raw("N06", "SV", "G05", "6"), raw("N06", "SV", "G05", "---"), raw("N06", "SV", "G05", ""))
        val fresh = Arrivals.filter(trip("N06", "G05"), trains, sv, 0)
        assertEquals(listOf(ArrivalKind.BOARDING, ArrivalKind.ARRIVING, ArrivalKind.MINUTES), fresh.map { it.kind })
        // Two minutes later: the boarding/arriving trains are gone, 6 min becomes 4.
        val old = Arrivals.filter(trip("N06", "G05"), trains, sv, 2)
        assertEquals(listOf(4), old.map { it.minutes })
    }

    @Test fun arrivalsWithMissingCodesUseThePlatformGroup() {
        val trains = listOf(
            raw("N06", "SV", "G05", "5", group = "1"),
            raw("N06", "SV", null, "9", name = "Somewhere", group = "1"),   // unknown, same track as a DC train → counts
            raw("N06", "SV", null, "3", name = "Elsewhere", group = "2"),   // unknown, other track → doesn't
        )
        assertEquals(listOf(5, 9), Arrivals.filter(trip("N06", "G05"), trains, sv, 0).map { it.minutes })
    }

    // ---------------------------------------------------------------- 6. branches and stop counts

    @Test fun branchRulesAndStopCounts() {
        assertTrue(Branches.reaches("SV", sv, "N06", "C04", "D04"))       // Federal Center SW train reaches Foggy Bottom
        assertFalse(Branches.reaches("SV", sv, "N06", "G03", "D04"))      // …but not Addison Road
        assertTrue(Branches.reaches("SV", sv, "N06", "G05", "D13"))       // "To DC" includes New Carrollton trains
        assertFalse(Branches.reaches("SV", sv, "N06", "G02", "D13"))      // but not to a Largo-branch stop
        assertTrue(Branches.reaches("SV", sv, "N06", "D12", "D13"))
        assertFalse(Branches.reaches("SV", sv, "N06", "D12", "G05"))
        assertEquals(14, Branches.stopsBetween("SV", sv, "N06", "C01"))
        assertEquals(27, Branches.stopsBetween("SV", sv, "N06", "D13"))
        assertEquals(6, Branches.stopsBetween("SV", sv, "N12", "N06"))
    }

    // ---------------------------------------------------------------- 7. live train placement

    @Test fun liveTrainsArePlacedOnEveryLine() {
        (1..5).forEach { i ->
            val pos = TrainTracker.parsePositions(read("pos_$i.json"))
            orders.forEach { (line, stations) ->
                val m = LineMapBuilder.build(line, routes, pos, stations, 0L)
                val total = pos.count { it.line == line }
                val placed = m.trains.size + m.branchTrains.size
                if (total > 0) assertTrue("snapshot $i $line: placed $placed of $total", placed >= total * 0.8)
                m.trains.forEach { t -> assertTrue("$line train off the line: ${t.pos}", t.pos in -0.01f..(stations.size - 1 + 0.01f)) }
            }
        }
    }

    // ---------------------------------------------------------------- 8. trip-card live location

    @Test fun liveLocationForDefaultTripsAndEverySilverStation() {
        val pos = TrainTracker.parsePositions(read("pos_1.json"))
        val stations = Stations.SILVER
        // Every Silver station, both directions: works without errors; trains (if any) are in front of you.
        for (s in stations) for (end in listOf("N12", "G05")) {
            if (s.code == end) continue
            val loc = TrainTracker.locate(trip(s.code, end), routes, pos, stations)
            assertNotNull("no location result for ${s.name} toward $end", loc)
            loc!!.trains.forEach { t -> assertTrue("${s.name}: train behind you (${t.stopsAway})", t.stopsAway >= 0f) }
            assertEquals(s.code, loc.stations.last().code)
        }
        // The starting trips, if they're on Silver (they're the owner's own, or Metro Center ⇄ Union Station on Red).
        Stations.DEFAULT_TRIPS.filter { it.lineCode == "SV" }.forEach { t ->
            assertNotNull("default trip ${t.label}", TrainTracker.locate(t, routes, pos, stations))
        }
    }

    // ---------------------------------------------------------------- 9. connection ratings

    private fun la(min: Int) = LegArrival("RD", Arrival(min, ArrivalKind.MINUTES, "Glenmont", "8"))

    @Test fun connectionRatingsFollowTheRules() {
        assertEquals(Verdict.EASY, Verdict.of(6))
        assertEquals(Verdict.WALK_FAST, Verdict.of(5))
        assertEquals(Verdict.WALK_FAST, Verdict.of(3))
        assertEquals(Verdict.RUN, Verdict.of(2))
        assertEquals(Verdict.RUN, Verdict.of(1))
        assertEquals(Verdict.MISS, Verdict.of(null))
        // Arrive in 10: the 10-min train left as you arrive (can't make it), so 12 is the one, 14 the backup.
        val p = LiveEngine.connection(10, listOf(la(4), la(10), la(12), la(14)))
        assertEquals(12, p.outMinutes); assertEquals(2, p.slack); assertEquals(Verdict.RUN, p.verdict); assertEquals(14, p.backup)
        assertEquals(Verdict.EASY, LiveEngine.connection(2, listOf(la(9))).verdict)
        assertEquals(Verdict.MISS, LiveEngine.connection(30, listOf(la(5), la(12))).verdict)
        assertEquals(Verdict.MISS, LiveEngine.connection(5, emptyList()).verdict)
    }

    // ---------------------------------------------------------------- 10. WMATA data readers

    @Test fun readersParseRealResponses() {
        val ride = WmataApi.parseRideInfo(read("ride.json"))
        assertNotNull(ride); assertTrue((ride!!.first ?: 0) in 20..60); assertTrue((ride.second ?: 0.0) > 0.0)
        val exits = WmataApi.parseEntrances(read("entrances.json"), st("C04").codes)
        assertTrue("Foggy Bottom exits: $exits", exits.isNotEmpty())
        assertTrue(exits.any { it.elevator }); assertTrue(exits.any { !it.elevator })
        exits.forEach { assertFalse("exit name still upper-case: ${it.name}", it.name == it.name.uppercase() && it.name.any { c -> c.isLetter() && c !in "NSEW" }) }
        val incidents = WmataApi.parseIncidents(read("incidents.json"))
        incidents.forEach { i -> assertTrue(i.description.isNotBlank()); i.lines.forEach { l -> assertTrue(l in setOf("RD", "OR", "SV", "BL", "YL", "GR")) } }
        assertTrue(WmataApi.parseIncidents("""{"Incidents":[]}""").isEmpty())
        assertTrue(WmataApi.parsePredictions("""{"Trains":[]}""").isEmpty())
    }

    // ---------------------------------------------------------------- 11. formatting

    @Test fun formatting() {
        assertEquals("Not updated yet", Format.updated(0))
        val now = System.currentTimeMillis()
        assertEquals("Updated just now", Format.updated(now - 20_000, now))
        assertEquals("Updated 5 min ago", Format.updated(now - 5 * 60_000, now))
        assertTrue(Format.updated(now - 3 * 60 * 60_000L, now).startsWith("Updated "))
        assertEquals("72°", Format.temp(71.6))
        assertEquals("--°", Format.temp(Double.NaN))
    }
}
