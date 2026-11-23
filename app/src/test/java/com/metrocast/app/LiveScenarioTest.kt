package com.metrocast.app

import com.metrocast.app.data.LiveEngine
import com.metrocast.app.data.LiveInputs
import com.metrocast.app.data.LiveState
import com.metrocast.app.data.LiveTripEngine
import com.metrocast.app.data.Planner
import com.metrocast.app.data.StationDirectory
import com.metrocast.app.data.TrainTracker
import com.metrocast.app.data.Verdict
import com.metrocast.app.data.WmataApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/**
 * Made-up live rides on the real track layout: trains are placed on chosen track circuits and
 * predictions are invented, so each tricky situation can be forced and checked.
 * Main ride: McPherson Square → Navy Yard (Silver/Orange/Blue to L'Enfant Plaza, then Green).
 */
class LiveScenarioTest {

    companion object {
        lateinit var routes: List<TrainTracker.Route>
        lateinit var paths: List<com.metrocast.app.data.LinePath>

        @BeforeClass @JvmStatic
        fun load() {
            StationDirectory.load(File("src/test/resources/live/stations.json").readText())
            routes = TrainTracker.parseRoutes(File("src/test/resources/live/routes.json").readText())
            paths = Planner.paths(listOf("RD", "OR", "SV", "BL", "YL", "GR").mapNotNull { l ->
                LiveEngine.lineOrder(routes, l) { StationDirectory.nameOf(it) }?.let { l to it }
            }.toMap())
        }
    }

    private val t0 = 1_700_000_000_000L
    private fun min(m: Double) = t0 + (m * 60_000).toLong()

    /** Track circuit [offset] circuits from [station] on [line]'s track 1 (+ = toward the line's east/north end). */
    private fun circuit(line: String, station: String, offset: Int = 0): Int {
        val r = routes.filter { it.line == line }.maxByOrNull { it.stationAt.size }!!
        val i = r.stationIndices[station]!!.first() + offset
        return r.circuits[i]
    }

    private fun train(id: String, line: String, station: String, offset: Int, dest: String, dir: Int = 1) =
        TrainTracker.Position(id, circuit(line, station, offset), line, dest, 8, dir)

    private fun pred(loc: String, line: String, dest: String?, m: String, group: String = "1") =
        WmataApi.RawTrain("8", dest?.let { StationDirectory.nameOf(it) } ?: "", dest, group, line, loc, m)

    private fun inputs(now: Long, positions: List<TrainTracker.Position>?, preds: List<WmataApi.RawTrain>) =
        LiveInputs(paths, routes, positions, preds, 0, { _, _ -> null }, emptyList(), now)

    private val ride = LiveState("C02", "F05", leg = 0, trainId = null, boardedAt = 0L, lastStops = 99f)
    // Green trains at L'Enfant toward Branch Ave (your connection), and one the other way (not yours).
    private fun greenAt(vararg mins: Int) = mins.map { pred("F03", "GR", "F11", "$it", "2") } + pred("F03", "GR", "E10", "3", "1")

    // ---------------------------------------------------------------- the normal ride

    @Test fun normalRideShowsTransferTimesAndAdvancesOnlyAtTheStop() {
        // Board the Silver train at McPherson Square.
        val atPlatform = listOf(train("S1", "SV", "C02", 0, "D04"))
        val boardId = LiveEngine.boardingTrainAny(LiveTripEngine.buildPlan("C02", "F05", inputs(t0, atPlatform, emptyList()))!!.legs[0].leg.options, routes, atPlatform)
        assertEquals("S1", boardId)
        var state = ride.copy(trainId = boardId, boardedAt = t0)

        // Ride: Federal Triangle (2 stops before L'Enfant), Smithsonian (1), at L'Enfant (0), then gone.
        val steps = listOf(
            Triple(2.0, "D01", listOf(pred("D03", "SV", "D04", "4"))),
            Triple(4.0, "D02", listOf(pred("D03", "SV", "D04", "2"))),
            Triple(6.0, "D03", listOf(pred("D03", "SV", "D04", "BRD"))),
        )
        var notified = 0
        for ((m, station, p) in steps) {
            val before = state
            val r = LiveTripEngine.update(state, inputs(min(m), listOf(train("S1", "SV", station, 0, "D04")), p + greenAt(5, 12)))
            state = r.state
            val live = r.live!!
            assertTrue(live.onBoard)
            assertNotNull("arrival time at $station", live.etaMinutes)
            assertNotNull("Green connection at $station", live.connectionOut)
            assertEquals("GR", live.connectionLine)
            assertNotNull(live.verdict)
            if (LiveTripEngine.notificationDue(live)) {
                notified++
                val (title, body) = LiveTripEngine.notificationText(live)!!
                assertTrue("title has the rating: $title", title.contains("Green"))
                assertTrue("body has times: $body", body.contains("min"))
                assertTrue("sentences start upper-case: $body", !body.contains(". y"))
            }
            assertFalse("moved on early at $station", LiveTripEngine.shouldAdvance(before, state, live, min(m)))
        }
        assertTrue("heads-up never due", notified > 0)

        // Train has left L'Enfant (no longer approaching it): now it's safe to move on.
        val before = state
        val r = LiveTripEngine.update(state, inputs(min(8.0), listOf(train("S1", "SV", "D04", 0, "D04")), greenAt(3, 10)))
        assertTrue("should move on after reaching L'Enfant", LiveTripEngine.shouldAdvance(before, r.state, r.live!!, min(8.0)))
    }

    @Test fun connectionRatingsAtTheTransfer() {
        val state = ride.copy(trainId = "S1", boardedAt = t0)
        fun verdictWith(greenMin: Int): Verdict? = LiveTripEngine.update(
            state, inputs(min(3.0), listOf(train("S1", "SV", "D02", 0, "D04")), listOf(pred("D03", "SV", "D04", "2")) + greenAt(greenMin)),
        ).live!!.verdict
        assertEquals(Verdict.EASY, verdictWith(9))      // arrive 2, leaves 9 → 7 min
        assertEquals(Verdict.WALK_FAST, verdictWith(6)) // 4 min
        assertEquals(Verdict.RUN, verdictWith(4))       // 2 min
        assertEquals(Verdict.MISS, verdictWith(2))      // leaves as you arrive
    }

    // ---------------------------------------------------------------- things going wrong

    @Test fun tappedAfterTheDoorsClosedSwitchesToTheTrainAhead() {
        // You're on S1 (just left McPherson); the app grabbed S2, still a stop before McPherson.
        val positions = listOf(train("S1", "SV", "C01", 0, "D04"), train("S2", "SV", "C03", 0, "D04"))
        val state = ride.copy(trainId = "S2", boardedAt = min(0.0))
        val r = LiveTripEngine.update(state, inputs(min(2.0), positions, emptyList()))
        assertEquals("should switch to the train that just left", "S1", r.state.trainId)
        assertEquals("S1", r.live!!.track?.trainId)
    }

    @Test fun noLivePositionsStillGivesAnArrivalTimeAndConnection() {
        val state = ride.copy(trainId = "S1", boardedAt = min(0.0))
        val r = LiveTripEngine.update(state, inputs(min(3.0), null, greenAt(9, 16)))
        val live = r.live!!
        assertNull(live.track)
        assertNotNull("ETA from the schedule", live.etaMinutes)
        assertFalse(live.etaIsLive)
        assertNotNull("connection still rated", live.verdict)
        assertFalse("can't be sure it arrived: no moving on", LiveTripEngine.shouldAdvance(state, r.state, live, min(3.0)))
    }

    @Test fun lostTrainBeforeReachingTheStopDoesNotMoveOn() {
        var state = ride.copy(trainId = "S1", boardedAt = min(0.0))
        state = LiveTripEngine.update(state, inputs(min(2.0), listOf(train("S1", "SV", "D01", 0, "D04")), emptyList())).state
        // Train disappears from the feed while still 2 stops away.
        val before = state
        val r = LiveTripEngine.update(state, inputs(min(4.0), emptyList(), emptyList()))
        assertFalse(LiveTripEngine.shouldAdvance(before, r.state, r.live!!, min(4.0)))
    }

    @Test fun mismatchedPredictionListsDoNotGiveTheNextTrainsTime() {
        // A train waiting AT L'Enfant (S0) is in the approaching list but WMATA's predictions skip it:
        // pairing by position would give S1 the 14-minute time. It must get ~4 min.
        val positions = listOf(train("S0", "SV", "D03", 0, "D04"), train("S1", "SV", "D01", 0, "D04"))
        val preds = listOf(pred("D03", "SV", "D04", "4"), pred("D03", "SV", "D04", "14"))
        val r = LiveTripEngine.update(ride.copy(trainId = "S1", boardedAt = min(0.0)), inputs(min(1.0), positions, preds + greenAt(8)))
        val eta = r.live!!.etaMinutes!!
        assertTrue("ETA should be ~4, was $eta", eta in 3..6)
        assertEquals(Verdict.WALK_FAST, r.live!!.verdict)
    }

    @Test fun tooEarlyToTellIsNotNoConnection() {
        // Far away: arrival in ~20 min, Green predicted only up to 12 min ahead.
        val r = LiveTripEngine.update(
            ride.copy(trainId = "S1", boardedAt = min(0.0)),
            inputs(min(1.0), listOf(train("S1", "SV", "C05", 0, "D04")), greenAt(5, 12)),
        )
        val live = r.live!!
        assertTrue("should be 'too early', ETA ${live.etaMinutes}", live.tooEarlyForConnection)
        assertFalse(LiveTripEngine.notificationDue(live))
    }

    @Test fun missingDestinationCodesStillFindTheGreenLine() {
        // Exactly what WMATA sent at L'Enfant: no codes, just "Branch Av" / "Greenbelt".
        val raw = """{"Trains":[
            {"Car":"8","Destination":"Branch Av","DestinationCode":null,"DestinationName":"Branch Av","Group":"2","Line":"GR","LocationCode":"F03","LocationName":"L'Enfant Plaza","Min":"5"},
            {"Car":"8","Destination":"Greenbelt","DestinationCode":null,"DestinationName":"Greenbelt","Group":"1","Line":"GR","LocationCode":"F03","LocationName":"L'Enfant Plaza","Min":"3"},
            {"Car":"8","Destination":"Fed Ctr SW","DestinationCode":null,"DestinationName":"Fed Ctr SW","Group":"1","Line":"SV","LocationCode":"D03","LocationName":"L'Enfant Plaza","Min":"2"}]}"""
        val r = LiveTripEngine.update(
            ride.copy(trainId = "S1", boardedAt = min(0.0)),
            inputs(min(1.0), listOf(train("S1", "SV", "D02", 0, "D04")), WmataApi.parsePredictions(raw)),
        )
        val next = r.live!!.plan.legs[1].arrivals
        assertEquals("only the Branch Ave Green train is yours", listOf("Branch Ave"), next.map { it.arrival.destination })
        assertEquals(5, r.live!!.connectionOut)
    }

    // ---------------------------------------------------------------- other shapes of trip

    @Test fun ridingOrangeOnASharedLegFollowsOrange() {
        val positions = listOf(train("O1", "OR", "D01", 0, "D13"))
        val r = LiveTripEngine.update(ride.copy(trainId = "O1", boardedAt = min(0.0)), inputs(min(1.0), positions, greenAt(9)))
        assertEquals("OR", r.live!!.rideLine)
        assertNotNull(r.live!!.track)
    }

    @Test fun directTripHasNoTransferOrHeadsUp() {
        val state = LiveState("C02", "D02", 0, "S1", min(0.0), 99f)
        val live = LiveTripEngine.update(state, inputs(min(1.0), listOf(train("S1", "SV", "D01", 0, "D04")), emptyList())).live!!
        assertTrue(live.isLastLeg)
        assertFalse(LiveTripEngine.notificationDue(live))
        assertNull(live.connectionOut)
    }

    @Test fun autoEndCountdown() {
        val arrived = t0
        assertNull(LiveTripEngine.minutesUntilAutoEnd(0L, t0))
        assertEquals(7, LiveTripEngine.minutesUntilAutoEnd(arrived, arrived))
        assertEquals(4, LiveTripEngine.minutesUntilAutoEnd(arrived, arrived + 3 * 60_000L))
        assertEquals(1, LiveTripEngine.minutesUntilAutoEnd(arrived, arrived + 6 * 60_000L + 30_000L))
        assertEquals(0, LiveTripEngine.minutesUntilAutoEnd(arrived, arrived + 7 * 60_000L))
        assertEquals(0, LiveTripEngine.minutesUntilAutoEnd(arrived, arrived + 30 * 60_000L))
    }

    @Test fun pastTheLastLegIsArrived() {
        val live = LiveTripEngine.update(ride.copy(leg = 2), inputs(min(1.0), emptyList(), emptyList())).live!!
        assertTrue(live.arrived)
    }

    @Test fun waitingToBoardShowsNextTrainsForEveryLine() {
        val preds = listOf(pred("C02", "OR", "D13", "3"), pred("C02", "SV", "D04", "6"), pred("C02", "BL", "G05", "9"), pred("C02", "OR", "K08", "2", "2"))
        val live = LiveTripEngine.update(ride, inputs(min(0.0), emptyList(), preds + greenAt(20))).live!!
        assertFalse(live.onBoard)
        assertEquals(listOf("OR", "SV", "BL"), live.plan.legs[0].arrivals.map { it.line })
        assertNotNull("estimated arrival before boarding", live.etaMinutes)
    }

    @Test fun everyRideStepOnEveryLineDoesNotCrash() {
        // Smoke test: for a spread of trips, place a train at every station of the first leg in turn.
        val trips = listOf("N06" to "B03", "A15" to "F05", "K08" to "B11", "J03" to "E10", "C15" to "B11", "F11" to "K08", "G05" to "N12", "D13" to "A15")
        for ((f, t) in trips) {
            val plan = LiveTripEngine.buildPlan(f, t, inputs(t0, emptyList(), emptyList()))!!
            val leg = plan.legs[0].leg
            for (opt in leg.options) {
                val codes = opt.path.codes
                val a = codes.indexOf(opt.boardCode); val b = codes.indexOf(opt.alightCode)
                val range = if (b > a) a..b else b..a
                for (i in range) {
                    val code = codes[i]
                    val circ = routes.filter { it.line == opt.line }.firstNotNullOfOrNull { r -> r.stationIndices[code]?.first()?.let { r.circuits[it] } } ?: continue
                    val pos = listOf(TrainTracker.Position("X", circ, opt.line, opt.towardCode, 8, 1))
                    val live = LiveTripEngine.update(LiveState(f, t, 0, "X", t0, 99f), inputs(min(5.0), pos, emptyList())).live
                    assertNotNull("$f→$t at $code", live)
                    assertNotNull("$f→$t at $code: no ETA", live!!.etaMinutes)
                }
            }
        }
    }
}

class LiveSpeedTest {
    @Test fun liveUpdateIsFast() {
        LiveScenarioTest.load()
        val dir = File("src/test/resources/live2")
        val inp = LiveInputs(
            LiveScenarioTest.paths, LiveScenarioTest.routes, TrainTracker.parsePositions(File(dir, "pos_1.json").readText()),
            WmataApi.parsePredictions(File(dir, "pred_1.json").readText()), 0, { _, _ -> null }, emptyList(), 1_700_000_000_000L,
        )
        val state = LiveState("C02", "F05", 0, "318", 1_700_000_000_000L - 60_000, 99f)
        repeat(20) { LiveTripEngine.update(state, inp) } // warm up
        val t = System.nanoTime()
        val n = 200
        repeat(n) { LiveTripEngine.update(state, inp) }
        val ms = (System.nanoTime() - t) / 1e6 / n
        println("Live update: %.2f ms each".format(ms))
        assertTrue("live update too slow: $ms ms", ms < 25)
    }
}
