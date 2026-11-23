package com.metrocast.app

import com.metrocast.app.data.LiveEngine
import com.metrocast.app.data.Planner
import com.metrocast.app.data.StationDirectory
import com.metrocast.app.data.TrainTracker
import com.metrocast.app.data.WmataApi
import org.junit.Test
import java.io.File

/** Your ride right now: McPherson Square → L'Enfant Plaza → Green. What does the app compute? */
class QuickLEnfantTest {
    @Test fun mcphersonToGreen() {
        StationDirectory.load(File("src/test/resources/live/stations.json").readText())
        val routes = TrainTracker.parseRoutes(File("src/test/resources/live/routes.json").readText())
        val orders = listOf("RD", "OR", "SV", "BL", "YL", "GR").mapNotNull { l -> LiveEngine.lineOrder(routes, l) { StationDirectory.nameOf(it) }?.let { l to it } }.toMap()
        val paths = Planner.paths(orders)
        val trains = WmataApi.parsePredictions(File("src/test/resources/quick/predLE.json").readText())
        println("RAW at F03: " + trains.filter { it.locationCode == "F03" }.joinToString { "${it.line} ${it.destinationCode} ${it.destinationName} ${it.min} g${it.group}" })
        for (dest in listOf("F05", "E10", "F11", "E01", "C15", "F04", "F07", "E05")) {
            val r = Planner.plan(StationDirectory.find("C02")!!, StationDirectory.find(dest)!!, paths)
            if (r == null) { println("no route to $dest"); continue }
            println("\nC02 → ${StationDirectory.nameOf(dest)}: " + r.legs.joinToString(" ⇒ ") { l -> l.options.joinToString("/") { "${it.line}[${it.boardCode}→${it.alightCode} toward ${it.towardCode}]" } })
            r.legs.forEachIndexed { i, l ->
                val arr = LiveEngine.legArrivals(l, trains, 0, limit = 6)
                println("  leg ${i + 1} at ${l.from.name}: " + (if (arr.isEmpty()) "NONE" else arr.joinToString { "${it.line} ${it.arrival.label}→${it.arrival.destination}" }))
            }
        }
    }
}
