package com.metrocast.app.robo

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.metrocast.app.data.Repository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/** Far more data than WMATA ever sends (thousands of trains): the app stays quick and small. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class HugeDataRoboTest {
    @After fun done() = FakeWmata.uninstall()

    @Test fun thousandsOfTrainsStayQuick() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.getSharedPreferences("metrocast", Context.MODE_PRIVATE).edit().clear().putBoolean("onboarded", true).commit()
        FakeWmata.install()
        val r = Repository(ctx)
        runBlocking { r.refresh(extras = false) } // normal first, so the track layout is on file
        val one = """{"TrainId":"%d","TrainNumber":"%d","CarCount":8,"DirectionNum":%d,"CircuitId":%d,"DestinationStationCode":"A15","LineCode":"%s","SecondsAtLocation":5,"ServiceType":"Normal"}"""
        val lines = listOf("RD", "OR", "SV", "BL", "YL", "GR")
        val rnd = java.util.Random(1)
        val pos = (1..3000).joinToString(",", "{\"TrainPositions\":[", "]}") { one.format(it, it, 1 + it % 2, 1000 + rnd.nextInt(3000), lines[it % 6]) }
        val pred = File("src/test/resources/live2/pred_1.json").readText().let { p -> val body = p.substringAfter("[").substringBeforeLast("]"); "{\"Trains\":[" + List(200) { body }.joinToString(",") + "]}" }
        FakeWmata.bodies = mapOf("TrainPositions?" to pos, "GetPrediction" to pred)
        runBlocking { r.refresh(extras = false) }
        val rt = Runtime.getRuntime()
        fun used(): Long { repeat(3) { System.gc(); Thread.sleep(50) }; return rt.totalMemory() - rt.freeMemory() }
        val before = used()
        val t0 = System.nanoTime()
        val kept = (1..3).map { r.snapshot() to r.lineMaps() } // what the screen holds on to
        val ms = (System.nanoTime() - t0) / 1_000_000 / 3
        val mb = (used() - before) / 1_048_576
        println("HUGE: $ms ms per screen build, ${mb} MB kept for 3 builds, ${kept.last().second.values.sumOf { it.trains.size + it.branchTrains.size }} trains on the maps")
        assertTrue(kept.all { it.first.trips.isNotEmpty() && it.second.isNotEmpty() })
        assertTrue("one screen build took $ms ms", ms < 3_000)
        assertTrue("kept $mb MB", mb < 40)
        // An absurdly large answer (18 MB of arrivals) is refused: the last good one stays, with a friendly message.
        val flood = "{\"Trains\":[" + List(200) { File("src/test/resources/live2/pred_1.json").readText().substringAfter("[").substringBeforeLast("]") }.joinToString(",") + "]}"
        FakeWmata.bodies = mapOf("GetPrediction" to flood)
        val res = runBlocking { r.refresh(extras = false) }
        assertTrue("a friendly message, not a crash", res.trainError?.contains("broken") == true)
        assertTrue(r.snapshot().trips.isNotEmpty())
        // At most 200 trains are ever placed, however many WMATA sends.
        assertTrue(kept.last().second.values.sumOf { it.trains.size + it.branchTrains.size } <= 200)
    }

    @Test fun settingsFileStaysSmallAndOldCachesMoveOver() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val sp = ctx.getSharedPreferences("metrocast", Context.MODE_PRIVATE)
        // An older version kept downloads in the settings file: they move to their own files on first read.
        sp.edit().clear().putBoolean("onboarded", true).putString("cache_incidents", "{\"Incidents\":[]}").putLong("cache_ts_incidents", System.currentTimeMillis()).commit()
        FakeWmata.install()
        val r = Repository(ctx)
        assertTrue(r.prefs.cached("incidents") == "{\"Incidents\":[]}")
        assertTrue("old copy removed", sp.getString("cache_incidents", null) == null)
        repeat(5) { runBlocking { r.refresh(forceWeather = true, extras = false) } }
        // Live trains, weather and alerts are all cached…
        assertTrue(r.snapshot().trips.any { it.arrivals.isNotEmpty() } && r.lineMaps().isNotEmpty() && r.snapshot().weather != null)
        // …but none of it is in the settings file, which stays small.
        val size = sp.all.entries.sumOf { (k, v) -> k.length + v.toString().length }
        assertTrue("settings file holds $size characters", size < 20_000)
        assertTrue(sp.all.keys.none { it.startsWith("cache_") && !it.startsWith("cache_ts_") && !it.startsWith("cache_tag_") })
    }
}
