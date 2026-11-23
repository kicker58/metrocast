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
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * Mock rides through the full live-trip engine on a fresh recording (live2: 8 snapshots ~40 s apart).
 * Each ride starts 1–4 stops before a transfer, waits for a train at the platform, "boards" it, then
 * steps through the snapshots checking what the live screen and the notification would show.
 */
class LiveRideSimulationTest {
    private val dir = File("src/test/resources/live2").takeIf { File(it, "pos_1.json").exists() } ?: File("src/test/resources/live")
    private fun read(n: String) = File(dir, n).readText()
    private val snaps = (1..8).filter { File(dir, "pos_$it.json").exists() && File(dir, "pred_$it.json").exists() }
    private val report = StringBuilder()
    private val problems = ArrayList<String>()

    private val routes by lazy {
        StationDirectory.load(read("stations.json"))
        TrainTracker.parseRoutes(read("routes.json"))
    }
    private val paths by lazy {
        Planner.paths(listOf("RD", "OR", "SV", "BL", "YL", "GR").mapNotNull { l ->
            LiveEngine.lineOrder(routes, l) { StationDirectory.nameOf(it) }?.let { l to it }
        }.toMap())
    }
    private fun inputs(k: Int): LiveInputs {
        val trains = WmataApi.parsePredictions(read("pred_$k.json"))
        val time = File(dir, "time_$k.txt").takeIf { it.exists() }?.readText()?.trim()?.toLong()?.times(1000) ?: (k * 40_000L)
        return LiveInputs(
            paths = paths, routes = routes, positions = TrainTracker.parsePositions(read("pos_$k.json")),
            trains = trains, ageMin = 0, ride = { _, _ -> null },
            incidents = runCatching { WmataApi.parseIncidents(read("incidents.json")) }.getOrDefault(emptyList()),
            now = time,
        )
    }

    private fun problem(msg: String) { problems += msg; report.appendLine("   !! $msg") }

    /** Trains WMATA predicts at [code] on [lines] heading from [code] toward [toward] (independent of the app's filter). */
    private fun rawTrainsGoing(k: Int, code: String, lines: Set<String>, pathCodes: List<String>, toward: String): Int {
        val trains = WmataApi.parsePredictions(read("pred_$k.json"))
        val here = pathCodes.indexOf(code); val to = pathCodes.indexOf(toward)
        return trains.count { t ->
            t.locationCode == code && t.line in lines && t.min.isNotBlank() && t.min != "---" && t.destinationCode != null &&
                pathCodes.indexOf(t.destinationCode).let { d -> d >= 0 && (d - here) * (to - here) > 0 }
        }
    }

    private fun ride(from: String, to: String) {
        routes
        val a = StationDirectory.find(from)!!; val b = StationDirectory.find(to)!!
        val plan = Planner.plan(a, b, paths)
        report.appendLine("\n=== ${a.name} → ${b.name}")
        if (plan == null) { problem("no route ${a.name} → ${b.name}"); return }
        report.appendLine("Route: " + plan.legs.joinToString(" ⇒ ") { l -> l.options.joinToString("/") { it.line } + " to ${l.to.name}" })

        var state = LiveState(a.code, b.code, 0, null, 0L, 99f)
        var boardedAt = -1
        for (k in snaps) {
            val inp = inputs(k)
            val before = state
            val r = LiveTripEngine.update(state, inp)
            state = r.state
            val live = r.live ?: run { problem("snapshot $k: no live trip computed"); null } ?: continue
            if (live.arrived) { report.appendLine("  [$k] arrived"); break }
            val ls = live.legStatus!!
            val next = live.plan.legs.getOrNull(live.leg + 1)

            // Check: connecting trains shown whenever WMATA has some going the right way.
            if (next != null) {
                val opt = next.leg.options.first()
                val raw = next.leg.options.sumOf { o -> rawTrainsGoing(k, o.boardCode, setOf(o.line), o.path.codes, o.alightCode) }
                if (raw > 0 && next.arrivals.isEmpty()) problem("snapshot $k: WMATA has $raw ${opt.line} train(s) at ${next.leg.from.name} going your way, but the app shows none")
            }

            if (!live.onBoard) {
                // Board when a train is at (or about to reach) the platform.
                val at = ls.leg.options.mapNotNull { LiveEngine.boardingTrain(it, routes, inp.positions!!) }.minByOrNull { it.second }
                if (at != null && at.second <= 0.6f) {
                    state = state.copy(trainId = at.first, boardedAt = inp.now)
                    boardedAt = k
                    report.appendLine("  [$k] boarded train ${at.first} at ${ls.leg.from.name}")
                } else {
                    report.appendLine("  [$k] waiting at ${ls.leg.from.name}: next " + (ls.arrivals.firstOrNull()?.let { "${it.line} ${it.arrival.label}" } ?: "none"))
                }
                continue
            }

            val t = live.track
            val conn = live.connectionOut?.let { "${live.connectionLine} in $it min, ${live.slackMinutes} spare → ${live.verdict}" } ?: (live.verdict?.toString() ?: "–")
            report.appendLine(
                "  [$k] leg ${live.leg + 1}: " + (t?.let { "${it.where} · ${"%.1f".format(it.stopsAway)} stops" } ?: "no live position") +
                    " · ETA ${live.etaMinutes ?: "–"}${if (live.etaIsLive) "" else " (est)"}" +
                    (if (next != null) " · connection: $conn" else "")
            )

            // Checks while riding.
            if (live.etaMinutes == null) problem("snapshot $k: on board but no arrival time")
            if (t != null && live.etaMinutes != null) {
                val est = t.stopsAway * 2.2f
                if (abs(live.etaMinutes - est) > maxOf(4f, est * 0.6f)) problem("snapshot $k: ETA ${live.etaMinutes} min doesn't fit ${"%.1f".format(t.stopsAway)} stops away")
            }
            if (next != null && live.etaMinutes != null) {
                val catchable = next.arrivals.any { it.arrival.minutes >= live.etaMinutes + 1 }
                if (catchable && (live.verdict == null || live.verdict == Verdict.MISS)) problem("snapshot $k: a connecting train is catchable but no run/hurry/take-your-time rating")
            }
            if (LiveTripEngine.notificationDue(live)) {
                val txt = LiveTripEngine.notificationText(live)
                report.appendLine("       🔔 ${txt?.first} — ${txt?.second}")
                if (txt == null) problem("snapshot $k: notification due but no text")
                else if (live.connectionOut != null && !txt.second.contains("min")) problem("snapshot $k: notification has no times: ${txt.second}")
            }
            if (t != null && before.lastStops < 90f && t.stopsAway > before.lastStops + 0.51f) problem("snapshot $k: train moved away (${before.lastStops} → ${t.stopsAway})")

            // Auto-advance only once the train really reached the stop.
            if (LiveTripEngine.shouldAdvance(before, state, live, inp.now)) {
                if (state.minStopsSeen > 0.5f) problem("snapshot $k: moved on before the train reached ${ls.leg.to.name}")
                report.appendLine("  [$k] → reached ${ls.leg.to.name}, moving to the next step")
                state = state.copy(leg = state.leg + 1, trainId = null, boardedAt = 0L, lastStops = 99f, minStopsSeen = 99f)
            }
        }
        if (boardedAt < 0) report.appendLine("  (no train reached ${a.name} during the recording)")
    }

    @Test fun mockRidesThroughTransfers() {
        assertTrue("no recording", snaps.isNotEmpty())
        listOf(
            "C02" to "F05",  // McPherson Sq → Navy Yard (your ride: Silver/Orange/Blue → Green at L'Enfant)
            "C02" to "E10",  // McPherson Sq → Greenbelt
            "C03" to "F05",  // Farragut West → Navy Yard
            "D02" to "F05",  // Smithsonian → Navy Yard (1 stop to L'Enfant)
            "D01" to "F07",  // Federal Triangle → Congress Heights
            "D05" to "E10",  // Capitol South → Greenbelt
            "D06" to "F07",  // Eastern Market → Congress Heights
            "C04" to "B03",  // Foggy Bottom → Union Station (Metro Center)
            "B03" to "C04",  // Union Station → Foggy Bottom
            "B02" to "F05",  // Judiciary Sq → Navy Yard (Gallery Place)
            "A03" to "F05",  // Dupont Circle → Navy Yard
            "F04" to "C02",  // Waterfront → McPherson Sq (Green → Orange/Silver/Blue)
            "E03" to "D02",  // U Street → Smithsonian
            "C07" to "B03",  // Pentagon → Union Station (Yellow → Red at Gallery Place)
            "B05" to "C04",  // Brookland → Foggy Bottom
        ).forEach { (f, t) -> try { ride(f, t) } catch (e: Throwable) { problem("${f}→$t crashed: $e") } }
        File("build/live-ride-simulation.txt").apply { parentFile.mkdirs(); writeText(report.toString()) }
        println(report)
        assertTrue("Problems found:\n" + problems.joinToString("\n"), problems.isEmpty())
    }
}
