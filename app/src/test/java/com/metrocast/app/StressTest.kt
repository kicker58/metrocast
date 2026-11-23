package com.metrocast.app

import com.metrocast.app.data.Arrival
import com.metrocast.app.data.ArrivalKind
import com.metrocast.app.data.ApproachingTrain
import com.metrocast.app.data.Arrivals
import com.metrocast.app.data.DestinationCodes
import com.metrocast.app.data.LineMapBuilder
import com.metrocast.app.data.LiveEngine
import com.metrocast.app.data.LiveInputs
import com.metrocast.app.data.LiveState
import com.metrocast.app.data.LiveTripEngine
import com.metrocast.app.data.Planner
import com.metrocast.app.data.StationDirectory
import com.metrocast.app.data.Stations
import com.metrocast.app.data.TrainTracker
import com.metrocast.app.data.Trip
import com.metrocast.app.data.Verdict
import com.metrocast.app.data.WmataApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import kotlin.random.Random

/** Randomized and exhaustive checks across the whole network and every recorded snapshot. */
class StressTest {
    companion object {
        lateinit var routes: List<TrainTracker.Route>
        lateinit var orders: Map<String, List<com.metrocast.app.data.Station>>
        lateinit var paths: List<com.metrocast.app.data.LinePath>
        val snapshots = mutableListOf<Pair<String, String>>() // (predictions json, positions json)

        @BeforeClass @JvmStatic
        fun load() {
            StationDirectory.load(File("src/test/resources/live/stations.json").readText())
            routes = TrainTracker.parseRoutes(File("src/test/resources/live/routes.json").readText())
            orders = listOf("RD", "OR", "SV", "BL", "YL", "GR").mapNotNull { l ->
                LiveEngine.lineOrder(routes, l) { StationDirectory.nameOf(it) }?.let { l to it }
            }.toMap()
            paths = Planner.paths(orders)
            for (d in listOf("live", "live2")) for (i in 1..8) {
                val p = File("src/test/resources/$d/pred_$i.json"); val q = File("src/test/resources/$d/pos_$i.json")
                if (p.exists() && q.exists()) snapshots += p.readText() to q.readText()
            }
        }
    }

    // ---------------------------------------------------------------- randomized live trips

    @Test fun randomLiveTripsKeepTheRules() {
        val stations = StationDirectory.stations.filter { s -> paths.any { p -> p.codes.any { it in s.codes } } }
        var checked = 0
        for (seed in listOf(20261121, 1, 2, 3, 4)) { val rnd = Random(seed)
        repeat(400) { n ->
            val a = stations.random(rnd); val b = stations.random(rnd)
            if (a.code == b.code) return@repeat
            val (predJson, posJson) = snapshots.random(rnd)
            val positions = TrainTracker.parsePositions(posJson).shuffled(rnd).take(rnd.nextInt(0, 200))
            val trains = WmataApi.parsePredictions(predJson).filter { rnd.nextInt(10) > 0 } // drop ~10%
            val inp = LiveInputs(paths, routes, positions.takeIf { rnd.nextInt(6) > 0 }, trains, rnd.nextInt(0, 3), { _, _ -> null }, emptyList(), 1_700_000_000_000L)
            val plan = LiveTripEngine.buildPlan(a.code, b.code, inp)!!
            val legs = plan.legs.size
            val leg = rnd.nextInt(0, legs + 1)
            val onBoard = rnd.nextBoolean()
            val anyTrain = positions.randomOrNull(rnd)?.trainId
            val state = LiveState(a.code, b.code, leg, if (onBoard) anyTrain else null, if (onBoard) inp.now - rnd.nextLong(0, 40 * 60_000L) else 0L, rnd.nextFloat() * 10f)
            val r = LiveTripEngine.update(state, inp)
            val live = r.live!!
            checked++
            if (live.arrived) return@repeat
            // Rules that must always hold.
            if (live.onBoard) assertNotNull("#$n ${a.name}→${b.name}: on board without an ETA", live.etaMinutes)
            live.etaMinutes?.let { assertTrue("#$n negative ETA", it >= 0) }
            if (live.connectionOut != null) {
                assertTrue("#$n connection before you arrive", live.connectionOut!! >= (live.etaMinutes ?: 0) + 1)
                assertEquals(Verdict.of(live.slackMinutes), live.verdict)
            }
            if (live.isLastLeg) assertTrue("#$n last leg has a connection", live.connectionOut == null)
            if (LiveTripEngine.notificationDue(live)) assertNotNull("#$n heads-up without text", LiveTripEngine.notificationText(live))
            live.track?.let { assertTrue("#$n train behind your stop", it.stopsAway >= 0f) }
            live.plan.legs.forEach { l -> l.arrivals.zipWithNext { x, y -> assertTrue("#$n next trains out of order", x.arrival.minutes <= y.arrival.minutes) } }
            // Moving on needs evidence: the train seen at the stop, or seen beyond it — never just missing.
            if (LiveTripEngine.shouldAdvance(state, r.state, live, inp.now)) {
                assertTrue("#$n moved on without the train reaching the stop", r.state.minStopsSeen <= 0.5f || live.trainPassedStop)
                assertTrue("#$n moved on while the train was missing from the feed", live.trainPassedStop || live.trainVisible)
            }
        }
        }
        println("Random live trips checked: $checked")
        assertTrue(checked > 1500)
    }

    // ---------------------------------------------------------------- every station, line and direction

    @Test fun nextTrainsAtEveryStationInEverySnapshot() {
        var combos = 0
        for ((predJson, _) in snapshots) {
            val trains = WmataApi.parsePredictions(predJson)
            for ((line, st) in orders) {
                val codes = st.map { it.code }
                for (s in st) for (end in listOf(codes.first(), codes.last())) {
                    if (s.code == end) continue
                    val got = Arrivals.filter(Trip("x", "", line, s.code, "", end, ""), trains, codes, 0, limit = 10)
                    combos++
                    got.zipWithNext { x, y -> assertTrue("$line ${s.name}: not sorted", x.minutes <= y.minutes) }
                    got.forEach { a -> assertTrue("$line ${s.name}: negative minutes", a.minutes >= 0) }
                    // Never more trains than WMATA actually listed for that platform and line.
                    val raw = trains.count { it.locationCode == s.code && it.line == line }
                    assertTrue("$line ${s.name}: invented trains", got.size <= raw)
                }
            }
        }
        println("Checked $combos station/line/direction combinations")
        assertTrue(combos > 1000)
    }

    @Test fun mapDirectionAgreesWithWmata() {
        var agree = 0; var total = 0
        for ((_, posJson) in snapshots) {
            val pos = TrainTracker.parsePositions(posJson)
            val byId = pos.associateBy { it.trainId }
            for ((line, st) in orders) {
                val m = LineMapBuilder.build(line, routes, pos, st, 0L)
                for (t in m.trains) {
                    val p = byId[t.id] ?: continue
                    if (p.directionNum !in 1..2) continue
                    total++
                    // WMATA: direction 1 = toward the line's north/east end — the end the app calls "last".
                    if ((p.directionNum == 1) == t.eastbound) agree++
                }
            }
        }
        println("Map direction agrees with WMATA for $agree of $total trains")
        assertTrue("direction disagrees too often: $agree/$total", total == 0 || agree >= total * 0.85)
    }

    // ---------------------------------------------------------------- names and labels

    @Test fun messyDestinationNames() {
        val cases = mapOf(
            "  Fed Ctr SW " to "D04", "FED CTR SW" to "D04", "fed-ctr-sw" to "D04", "New Carrollton" to "D13",
            "Branch Ave" to "F11", "Mt. Vernon Sq" to "E01", "Vienna/Fairfax-GMU" to "K08", "Shady Gr" to "A15",
            "Silver Spring" to "B08", "Franconia-Springfield" to "J03", "Downtown Largo" to "G05",
        )
        cases.forEach { (name, code) -> assertEquals("'$name'", code, DestinationCodes.resolve(name, null)) }
        listOf("No Passenger", "Train", "", "   ", "---").forEach { assertEquals("'$it'", null, DestinationCodes.resolve(it, null)) }
    }

    @Test fun arrivalAndDistanceLabels() {
        assertEquals("Boarding", Arrival(0, ArrivalKind.BOARDING, "x", "8").label)
        assertEquals("Arriving", Arrival(0, ArrivalKind.ARRIVING, "x", "8").label)
        assertEquals("Due", Arrival(0, ArrivalKind.MINUTES, "x", "8").label)
        assertEquals("7 min", Arrival(7, ArrivalKind.MINUTES, "x", "8").label)
        assertEquals("Largo", Arrival(7, ArrivalKind.MINUTES, "Downtown Largo", "8").shortDestination)
        assertEquals("At your station", ApproachingTrain(0f, "", 8, "").stopsLabel)
        assertEquals("1 stop away", ApproachingTrain(0.5f, "", 8, "").stopsLabel)
        assertEquals("1 stop away", ApproachingTrain(1f, "", 8, "").stopsLabel)
        assertEquals("3 stops away", ApproachingTrain(2.5f, "", 8, "").stopsLabel)
    }

    // ---------------------------------------------------------------- the planner, randomly

    @Test fun plannerIsSymmetricAndConsistent() {
        val rnd = Random(7)
        val stations = StationDirectory.stations.filter { s -> paths.any { p -> p.codes.any { it in s.codes } } }
        repeat(500) {
            val a = stations.random(rnd); val b = stations.random(rnd)
            if (a.code == b.code) return@repeat
            val there = Planner.plan(a, b, paths)!!
            val back = Planner.plan(b, a, paths)!!
            assertEquals("${a.name}↔${b.name}: transfers differ", there.transfers, back.transfers)
            assertEquals("${a.name}↔${b.name}: stops differ", there.stops, back.stops)
        }
    }

    @Test fun silverStationListMatchesTheTrackData() {
        // The built-in Silver order (Ashburn → Largo) must match WMATA's track circuits.
        val r = routes.filter { it.line == "SV" }.maxByOrNull { it.stationAt.size }!!
        val fromTrack = ArrayList<String>()
        r.stationAt.toSortedMap().values.forEach { c -> if (fromTrack.lastOrNull() != c) fromTrack.add(c) }
        assertEquals(Stations.SILVER.map { it.code }, fromTrack)
    }
}
