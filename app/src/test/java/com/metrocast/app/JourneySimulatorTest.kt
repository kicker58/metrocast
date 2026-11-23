package com.metrocast.app

import com.metrocast.app.data.LegOption
import com.metrocast.app.data.LiveEngine
import com.metrocast.app.data.LiveInputs
import com.metrocast.app.data.LiveState
import com.metrocast.app.data.LiveTripEngine
import com.metrocast.app.data.Planner
import com.metrocast.app.data.StationDirectory
import com.metrocast.app.data.TrainTracker
import com.metrocast.app.data.WmataApi
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Whole journeys, start to finish, with simulated trains driven circuit by circuit along the real
 * track every 30 seconds, and WMATA-style predictions that match where the trains are.
 * The rider waits, boards, rides, gets the heads-up, transfers, boards again and arrives.
 */
class JourneySimulatorTest {
    private val log = StringBuilder()
    private val problems = ArrayList<String>()

    private fun setup() { StressTest.load() }
    private val routes get() = StressTest.routes
    private val paths get() = StressTest.paths

    /** A simulated train running along [opt]'s line from a few stations before [opt.boardCode]. */
    private inner class SimTrain(val id: String, val opt: LegOption, startStationsBefore: Int) {
        private val route = routes.filter { it.line == opt.line }.first { r ->
            val a = r.stationIndices[opt.boardCode]?.first(); val b = r.stationIndices[opt.alightCode]?.first()
            a != null && b != null
        }
        private val a = route.stationIndices[opt.boardCode]!!.first()
        private val b = route.stationIndices[opt.alightCode]!!.first()
        val step = if (b > a) 1 else -1
        // Station circuit indices along the direction of travel.
        private val stationIdx = route.stationAt.keys.sorted().let { if (step == 1) it else it.reversed() }
        var idx: Int = run {
            val before = stationIdx.filter { (it - a) * step <= 0 }.takeLast(startStationsBefore + 1)
            before.firstOrNull() ?: a
        }
        val circuit get() = route.circuits[idx]
        val atBoard get() = idx == a
        val passedAlight get() = (idx - b) * step > 0
        /** Stations left before (and including) where you get off. */
        fun stopsTo(target: Int) = stationIdx.count { (it - idx) * step > 0 && (it - target) * step <= 0 }
        fun stopsToAlight() = stopsTo(b)
        /** Moves up to [circuits] circuits, stopping at the next station (like a real train). */
        fun move(circuits: Int) {
            repeat(circuits) {
                val next = (idx + step).coerceIn(0, route.circuits.size - 1)
                idx = next
                if (next in route.stationAt) return
            }
        }
        fun position() = TrainTracker.Position(id, circuit, opt.line, opt.towardCode, 8, if (step == 1) 1 else 2)
    }

    /** Real-world trouble to throw at a journey. */
    data class Trouble(
        val name: String,
        val feedDrops: Boolean = false,   // our train missing from the position feed every 3rd update
        val slowUpdates: Boolean = false, // update once a minute instead of every 30 s
        val crowded: Boolean = false,     // a train just ahead and one just behind ours
        val lateTap: Boolean = false,     // "I'm on the train" tapped after the doors closed
    )

    private fun journey(from: String, to: String, trouble: Trouble = Trouble("normal")) {
        val a = StationDirectory.find(from)!!; val b = StationDirectory.find(to)!!
        val plan0 = Planner.plan(a, b, paths)!!
        log.appendLine("\n=== [${trouble.name}] ${a.name} → ${b.name}: " + plan0.legs.joinToString(" ⇒ ") { l -> l.options.joinToString("/") { it.line } + " to ${l.to.name}" })
        var state = LiveState(a.code, b.code, 0, null, 0L, 99f)
        var now = 1_700_000_000_000L
        var leg = -1
        var train: SimTrain? = null
        var ahead: SimTrain? = null
        var behind: SimTrain? = null
        var tapPending = false
        // Connecting trains at each transfer platform: one every 6 minutes, offset per leg.
        val headsUps = HashMap<Int, Int>() // leg → count
        var lastEta: Int? = null
        var ticks = 0
        while (ticks++ < 400) {
            if (state.leg >= plan0.legs.size) { log.appendLine("  ✓ arrived after ${(ticks / 2)} min"); return }
            val curLeg = plan0.legs[state.leg]
            if (leg != state.leg) {
                leg = state.leg
                train = SimTrain("T${state.leg}", curLeg.options.first(), startStationsBefore = 2)
                if (trouble.crowded || trouble.lateTap) {
                    ahead = SimTrain("A${state.leg}", curLeg.options.first(), startStationsBefore = 0).also { it.move(3) }
                    behind = SimTrain("B${state.leg}", curLeg.options.first(), startStationsBefore = 4)
                }
                lastEta = null
            }
            val t = train!!
            // Predictions: our train at its stations ahead, plus connecting trains at the next platform.
            val preds = ArrayList<WmataApi.RawTrain>()
            val opt = curLeg.options.first()
            fun pred(loc: String, line: String, dest: String, m: Int) =
                WmataApi.RawTrain("8", StationDirectory.nameOf(dest) ?: dest, dest, "1", line, loc, if (m <= 0) "BRD" else "$m")
            if (!t.passedAlight) {
                val toBoard = t.stopsTo(routes.first { it.line == opt.line && it.stationIndices.containsKey(opt.boardCode) }.stationIndices[opt.boardCode]!!.first())
                if (!state.let { it.boardedAt > 0 }) preds += pred(opt.boardCode, opt.line, opt.towardCode, toBoard * 2)
                preds += pred(opt.alightCode, opt.line, opt.towardCode, t.stopsToAlight() * 2)
            }
            for (x in listOfNotNull(ahead, behind)) if (!x.passedAlight) preds += pred(opt.alightCode, opt.line, opt.towardCode, x.stopsToAlight() * 2)
            preds.sortBy { it.min.toIntOrNull() ?: 0 }
            plan0.legs.getOrNull(state.leg + 1)?.let { nl ->
                val o = nl.options.first()
                val phase = ((now / 60_000L) % 6).toInt()
                for (k in 0..3) preds += pred(o.boardCode, o.line, o.towardCode, (6 - phase) % 6 + 6 * k + 1)
            }
            val dropNow = trouble.feedDrops && ticks % 3 == 0
            val positions = listOfNotNull(t.position().takeIf { !dropNow }, ahead?.position(), behind?.position())
            val inp = LiveInputs(paths, routes, positions, preds, 0, { _, _ -> null }, emptyList(), now)

            // Rider boards when the train is at the platform.
            if (state.boardedAt == 0L && (t.atBoard || tapPending)) {
                if (trouble.lateTap && !tapPending) {
                    tapPending = true // rider is on board; taps one tick later, after the doors closed
                } else {
                    val picked = LiveEngine.boardingTrainAny(curLeg.options, routes, positions)
                    if (!trouble.lateTap && !(trouble.feedDrops && picked == null) && picked != t.id) problems += "[${trouble.name}] ${a.name}→${b.name} leg ${state.leg + 1}: picked train $picked instead of ${t.id}"
                    state = state.copy(trainId = picked, boardedAt = now)
                    tapPending = false
                    log.appendLine("  [${ticks / 2} min] boarded at ${curLeg.from.name} (app follows $picked)")
                }
            }

            if (trouble.slowUpdates && ticks % 2 == 1 && state.boardedAt != 0L) {
                if (!(state.boardedAt == 0L && t.atBoard)) t.move(4); ahead?.move(4); behind?.move(4)
                now += 30_000L; continue
            }
            val before = state
            val r = LiveTripEngine.update(state, inp)
            state = r.state
            val live = r.live!!
            if (live.onBoard && !live.arrived) {
                val eta = live.etaMinutes
                if (eta == null) problems += "${a.name}→${b.name}: no ETA while riding"
                val slack = if (trouble.feedDrops || trouble.lateTap) 4 else 1
                if (eta != null && lastEta != null && eta > lastEta + slack) problems += "[${trouble.name}] ${a.name}→${b.name}: ETA went up $lastEta → $eta"
                lastEta = eta
                if (LiveTripEngine.notificationDue(live)) {
                    val stops = live.track?.stopsAway ?: 0f
                    if (stops > 2.0f) problems += "${a.name}→${b.name}: heads-up too early ($stops stops)"
                    val txt = LiveTripEngine.notificationText(live)
                    if (txt == null) problems += "${a.name}→${b.name}: heads-up without text"
                    else {
                        if (live.connectionOut != null && !txt.second.contains("min")) problems += "${a.name}→${b.name}: heads-up without times"
                        if (headsUps[state.leg] == null) log.appendLine("  [${ticks / 2} min] 🔔 ${txt.first} — ${txt.second}")
                    }
                    headsUps[state.leg] = (headsUps[state.leg] ?: 0) + 1
                }
                if (LiveTripEngine.shouldAdvance(before, state, live, now)) {
                    if (!t.passedAlight) problems += "[${trouble.name}] ${a.name}→${b.name}: moved on before the train reached ${curLeg.to.name}"
                    log.appendLine("  [${ticks / 2} min] reached ${curLeg.to.name}")
                    state = state.copy(leg = state.leg + 1, trainId = null, boardedAt = 0L, lastStops = 99f, minStopsSeen = 99f, lastEta = null, lastEtaAt = 0L)
                }
            }
            // Move the train ~1 station every 2 minutes (circuits between stations vary).
            if (state.boardedAt == 0L && t.atBoard) {
                // waiting at the platform for the rider
            } else t.move(4)
            if (!(tapPending && false)) { ahead?.move(4); behind?.move(4) }
            if (trouble.feedDrops && state.boardedAt != 0L && now - state.boardedAt > 3 * 60_000L && state.trainId != t.id && !t.passedAlight) {
                problems += "[${trouble.name}] ${a.name}→${b.name}: not following your train 3 min after boarding (following ${state.trainId})"
                state = state.copy(trainId = t.id)
            }
            // After a late tap the app must end up following the rider's train.
            if (trouble.lateTap && state.boardedAt != 0L && now - state.boardedAt > 4 * 60_000L && state.trainId != t.id && !t.passedAlight) {
                problems += "[${trouble.name}] ${a.name}→${b.name}: still following ${state.trainId}, not your train ${t.id}, 4 min after the late tap"
                state = state.copy(trainId = t.id) // keep going to find later problems
            }
            now += 30_000L
        }
        problems += "[${trouble.name}] ${a.name}→${b.name}: never arrived (stuck at leg ${state.leg + 1})"
    }

    @Test fun wholeJourneysThroughEveryTransfer() {
        setup()
        listOf(
            "N06" to "B03",  // Silver → Red at Metro Center
            "B11" to "N06",  // Red → Silver at Metro Center
            "A15" to "F05",  // Red → Green at Gallery Place
            "C15" to "B11",  // Yellow → Red at Gallery Place
            "J03" to "E10",  // Blue → Green at L'Enfant
            "F11" to "K08",  // Green → Orange at L'Enfant
            "K08" to "F11",  // Orange → Green at L'Enfant
            "C02" to "F05",  // McPherson → Navy Yard (your ride)
            "G05" to "A15",  // Blue/Silver → Red at Metro Center
            "E10" to "C04",  // Green → Orange/Silver/Blue
            "D13" to "A15",  // Orange → Red
            "N12" to "F11",  // Ashburn → Branch Ave: Silver → Green
        ).let { trips ->
            val troubles = listOf(
                Trouble("normal"), Trouble("feed drops", feedDrops = true), Trouble("slow updates", slowUpdates = true),
                Trouble("crowded track", crowded = true), Trouble("late tap", lateTap = true),
            )
            for (tr in troubles) for ((f, t) in trips) try { journey(f, t, tr) } catch (e: Throwable) { problems += "[${tr.name}] $f→$t crashed: $e" }
        }
        File("build/journey-simulation.txt").apply { parentFile.mkdirs(); writeText(log.toString() + "\nProblems:\n" + problems.joinToString("\n")) }
        println(log)
        assertTrue("Problems:\n" + problems.joinToString("\n"), problems.isEmpty())
    }
}
