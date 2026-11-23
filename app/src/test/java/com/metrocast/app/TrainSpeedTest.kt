package com.metrocast.app

import com.metrocast.app.data.StationDirectory
import com.metrocast.app.data.TrainSpeed
import com.metrocast.app.data.TrainTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/** Train speed from WMATA's positions: the maths, and real recorded trains. */
class TrainSpeedTest {
    companion object {
        lateinit var routes: List<TrainTracker.Route>
        @BeforeClass @JvmStatic fun load() {
            StationDirectory.load(File("src/test/resources/live2/stations.json").readText())
            routes = TrainTracker.parseRoutes(File("src/test/resources/live2/routes.json").readText())
        }
    }

    @Test fun distancesAlongTheTrack() {
        val sv = routes.filter { it.line == "SV" }.maxBy { it.stationAt.size }
        // Along the line, distance only grows.
        var last = -1.0
        for (i in sv.circuits.indices step 5) { val m = TrainSpeed.metersAlong(sv, i)!!; assertTrue(m >= last); last = m }
        // Ashburn → Downtown Largo is roughly 80 km of track (straight lines between stations, so a bit under).
        val total = TrainSpeed.metersAlong(sv, sv.circuits.size - 1)!! / 1000
        assertTrue("Silver length $total km", total in 55.0..100.0)
        // Wiehle-Reston East → Foggy Bottom-GWU: about 25 km.
        val a = TrainSpeed.metersAlong(sv, sv.stationIndices.getValue("N06").first())!!
        val b = TrainSpeed.metersAlong(sv, sv.stationIndices.getValue("C04").first())!!
        assertTrue("Wiehle→Foggy ${(b - a) / 1000} km", (b - a) / 1000 in 18.0..32.0)
    }

    @Test fun trackerMaths() {
        val t = TrainSpeed.Tracker()
        assertNull(t.mph())
        t.add("1", 0, 0.0); t.add("1", 15_000, 300.0)
        assertNull("too soon to tell", t.mph())
        t.add("1", 30_000, 600.0); t.add("1", 45_000, 900.0); t.add("1", 60_000, 1200.0)
        assertEquals(44.7, t.mph()!!, 0.5)          // 20 m/s ≈ 44.7 mph
        t.add("1", 60_000, 5000.0); assertEquals(44.7, t.mph()!!, 0.5)   // same download twice: ignored
        // Standing at a platform.
        t.add("1", 120_000, 1200.0); t.add("1", 150_000, 1200.0)
        assertTrue(TrainSpeed.Reading(t.mph()!!, false).stopped)
        // A jump no train could make is not shown.
        t.add("1", 180_000, 9000.0); assertNull(t.mph())
        // Another train: starts over.
        t.add("2", 200_000, 0.0); assertNull(t.mph())
        assertEquals("44 mph", TrainSpeed.Reading(44.7, false).text())
        assertEquals("71 km/h", TrainSpeed.Reading(44.7, false).text(metric = true))
    }

    @Test fun realTrainsMoveAtRealisticSpeeds() {
        val snaps = (1..8).mapNotNull { i ->
            val p = File("src/test/resources/live2/pos_$i.json"); val tf = File("src/test/resources/live2/time_$i.txt")
            if (p.exists() && tf.exists()) tf.readText().trim().toLong() * 1000 to TrainTracker.parsePositions(p.readText()) else null
        }
        val speeds = ArrayList<Double>()
        for ((a, b) in snaps.zipWithNext()) {
            val dt = (b.first - a.first) / 1000.0
            if (dt <= 0 || dt > 120) continue
            for (p in a.second) {
                val q = b.second.firstOrNull { it.trainId == p.trainId } ?: continue
                val r = routes.firstOrNull { (p.line == null || it.line == p.line) && it.indexOfCircuit.containsKey(p.circuit) && it.indexOfCircuit.containsKey(q.circuit) } ?: continue
                val m1 = TrainSpeed.metersAlong(r, r.indexOfCircuit.getValue(p.circuit)) ?: continue
                val m2 = TrainSpeed.metersAlong(r, r.indexOfCircuit.getValue(q.circuit)) ?: continue
                speeds += Math.abs(m2 - m1) / dt * TrainSpeed.MS_TO_MPH
            }
        }
        speeds.sort()
        println("Real train speeds: ${speeds.size} readings, median %.0f mph, 90%% %.0f mph, max %.0f mph"
            .format(speeds[speeds.size / 2], speeds[speeds.size * 9 / 10], speeds.last()))
        assertTrue(speeds.size > 100)
        // Over ~40-50 s, Metro trains average roughly 10-45 mph including stops; nothing near-impossible.
        assertTrue(speeds[speeds.size / 2] in 5.0..50.0)
        assertTrue("fastest ${speeds.last()} mph", speeds.count { it > 80 } <= speeds.size / 50)
    }
}
