package com.metrocast.app

import com.metrocast.app.data.LiveEngine
import com.metrocast.app.data.Planner
import com.metrocast.app.data.StationDirectory
import com.metrocast.app.data.TrainTracker
import com.metrocast.app.data.WmataApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Mock live trips with transfers on every line, run on real WMATA data recorded a little under a
 * minute apart (src/test/resources/live). Uses the same code the app runs: planner, next trains
 * per leg, finding your train at the platform, following it, and rating the connection.
 */
class MockLiveTripsTest {
    private val dir = File("src/test/resources/live")
    private fun read(name: String) = File(dir, name).readText()
    private val snapshots = (1..5).filter { File(dir, "pos_$it.json").exists() && File(dir, "pred_$it.json").exists() }
    private val report = StringBuilder()

    private val routes by lazy {
        StationDirectory.load(read("stations.json"))
        TrainTracker.parseRoutes(read("routes.json"))
    }
    private val paths by lazy {
        val r = routes
        Planner.paths(listOf("RD", "OR", "SV", "BL", "YL", "GR").mapNotNull { l ->
            LiveEngine.lineOrder(r, l) { StationDirectory.nameOf(it) }?.let { l to it }
        }.toMap())
    }

    private fun name(code: String) = StationDirectory.find(code)?.name ?: code

    /** Runs one mock trip; returns a one-line summary and checks the basics. */
    private fun trip(label: String, from: String, to: String, expectedTransfers: List<String>, expectedLines: List<Set<String>>) {
        routes // loads the station list and track layout first
        val a = StationDirectory.find(from)!!
        val b = StationDirectory.find(to)!!
        val route = Planner.plan(a, b, paths)
        assertNotNull("$label: no route", route)
        route!!
        val transfers = route.legs.dropLast(1).map { it.to.name }
        report.appendLine("\n=== $label: ${a.name} → ${b.name}")
        report.appendLine("Route: " + route.legs.joinToString("  ⇒  ") { l ->
            l.options.joinToString("/") { it.line } + " (${l.from.name} → ${l.to.name}, ${l.stops} stops, toward " +
                l.options.joinToString("/") { it.towardName } + ")"
        })
        assertEquals("$label transfers", expectedTransfers, transfers)
        route.legs.forEachIndexed { i, l -> assertEquals("$label leg ${i + 1} lines", expectedLines[i], l.options.map { it.line }.toSet()) }

        // Snapshot 1: next trains on each leg.
        val trains1 = WmataApi.parsePredictions(read("pred_${snapshots.first()}.json"))
        val pos1 = TrainTracker.parsePositions(read("pos_${snapshots.first()}.json"))
        val legArr = route.legs.map { LiveEngine.legArrivals(it, trains1, 0, limit = 6) }
        legArr.forEachIndexed { i, arr ->
            report.appendLine("Leg ${i + 1} next trains at ${route.legs[i].from.name}: " +
                (if (arr.isEmpty()) "none predicted" else arr.joinToString(", ") { "${it.line} ${it.arrival.label} → ${it.arrival.shortDestination}" }))
        }
        assertTrue("$label: no next trains predicted for the first leg", legArr[0].isNotEmpty())

        // Board: the train at / closest to the first platform.
        val leg = route.legs[0]
        var boardId = LiveEngine.boardingTrainAny(leg.options, routes, pos1)
        report.appendLine(if (boardId != null) "Boarding train id $boardId" else "Boarding: train not visible at the platform yet — will pick it up once it leaves")

        // Ride: follow that train through the snapshots toward where you get off.
        var lastStops = Float.MAX_VALUE
        var seen = 0
        var lastEta: Int? = null
        for (s in snapshots) {
            val trains = WmataApi.parsePredictions(read("pred_$s.json"))
            val pos = TrainTracker.parsePositions(read("pos_$s.json"))
            if (boardId == null) {
                boardId = LiveEngine.reacquire(leg, routes, pos)
                if (boardId == null) { report.appendLine("  snapshot $s: no train has left ${leg.from.name} toward ${leg.to.name} yet"); continue }
                report.appendLine("  snapshot $s: picked up train $boardId after it left ${leg.from.name}")
            }
            val followed = LiveEngine.track(leg.options, boardId, routes, pos, trains, 0)
            if (followed == null) {
                report.appendLine("  snapshot $s: train not approaching ${leg.to.name} any more (arrived/passed or not yet on the stretch)")
                continue
            }
            val (opt, t) = followed
            seen++
            report.appendLine("  snapshot $s: ${opt.line} train ${t.where} · ${"%.1f".format(t.stopsAway)} stops to ${leg.to.name} · ETA ${t.etaMinutes ?: "–"} min")
            assertTrue("$label: train moved away from ${leg.to.name} (${t.stopsAway} > $lastStops)", t.stopsAway <= lastStops + 0.51f)
            lastStops = t.stopsAway
            lastEta = t.etaMinutes ?: lastEta
        }
        assertTrue("$label: never found the boarded train on its way to ${leg.to.name}", seen > 0 || boardId == null)
        if (seen == 0) report.appendLine("  (no train left during the ~4 recorded minutes — the app would keep watching)")

        // Transfer: rate the connection with the train's ETA.
        if (route.legs.size > 1 && seen > 0) {
            val eta = lastEta ?: kotlin.math.ceil(lastStops * 2.2).toInt()
            val pick = LiveEngine.connection(eta, legArr[1])
            report.appendLine("Transfer at ${route.legs[1].from.name}: arrive ~$eta min" +
                (pick.outMinutes?.let { " → ${pick.outLine} leaves in $it min, ${pick.slack} min to spare → ${pick.verdict}" } ?: " → no connecting train predicted yet (${pick.verdict})"))
        }
    }

    @Test
    fun mockTripsOnEveryLine() {
        assertTrue("no recorded snapshots", snapshots.isNotEmpty())
        val failures = ArrayList<String>()
        fun run(block: () -> Unit) = try { block() } catch (e: Throwable) { failures += (e.message ?: e.toString()); report.appendLine("!! $e") }

        run { trip("Red", "A15", "F05", listOf("Gallery Pl-Chinatown"), listOf(setOf("RD"), setOf("GR"))) }
        run { trip("Orange", "K08", "B03", listOf("Metro Center"), listOf(setOf("OR"), setOf("RD"))) }
        run { trip("Silver", "N06", "B03", listOf("Metro Center"), listOf(setOf("SV"), setOf("RD"))) }
        run { trip("Blue", "J03", "E10", listOf("L'Enfant Plaza"), listOf(setOf("BL"), setOf("GR"))) }
        run { trip("Yellow", "C15", "B11", listOf("Gallery Pl-Chinatown"), listOf(setOf("YL"), setOf("RD"))) }
        run { trip("Green", "F11", "K08", listOf("L'Enfant Plaza"), listOf(setOf("GR"), setOf("OR"))) }
        run { trip("East Falls Church (your case)", "K05", "D04", emptyList(), listOf(setOf("OR", "SV"))) }

        File("build/mock-live-trips.txt").apply { parentFile.mkdirs(); writeText(report.toString()) }
        println(report)
        assertTrue("Mock trip problems:\n" + failures.joinToString("\n"), failures.isEmpty())
    }
}
