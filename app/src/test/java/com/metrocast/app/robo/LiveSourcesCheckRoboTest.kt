package com.metrocast.app.robo

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.metrocast.app.data.Http
import com.metrocast.app.data.Repository
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The weekly check (only with -PliveCheck): every real source MetroCast reads, fetched now and read by
 * the app's own code. A source that changed its format (or went away) shows up here before riders notice.
 * Prints one LIVESRC line per source; fails if any is broken.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class LiveSourcesCheckRoboTest {
    @Test fun everySourceStillReads() {
        assumeTrue(System.getProperty("metrocast.livecheck") != null)
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.getSharedPreferences("metrocast", Context.MODE_PRIVATE).edit().clear().putBoolean("onboarded", true).commit()
        com.metrocast.app.ui.Net.testOnline = true
        val r = Repository(ctx)
        val results = mutableListOf<Triple<String, Boolean, String>>()
        fun check(name: String, block: () -> Pair<Boolean, String>) {
            val res = runCatching(block).getOrElse { false to "${it.javaClass.simpleName}: ${it.message?.take(100)}" }
            results += Triple(name, res.first, res.second)
        }
        val hourEt = java.time.LocalTime.now(java.time.ZoneId.of("America/New_York")).hour
        val metroOpen = hourEt in 6..22

        check("WMATA arrivals and train positions") {
            val err = runBlocking { r.refreshTrainsNow() }
            val maps = r.lineMaps(); val trains = maps.values.sumOf { it.trains.size + it.branchTrains.size }
            val arrivals = r.snapshot().trips.sumOf { it.arrivals.size }
            (err == null && (!metroOpen || (trains > 10 && arrivals > 0))) to "error=${err ?: "none"}, $trains trains on maps, $arrivals arrivals on your trips" + if (!metroOpen) " (Metro closed or nearly)" else ""
        }
        check("WMATA alerts") { val list = runBlocking { r.loadIssues() }; true to "${list.size} alerts" }
        check("WMATA elevators") { val list = runBlocking { r.loadElevatorOutages(force = true) }; true to "${list.size} outages" }
        check("Track work (wmata.com)") { val list = r.loadTrackWork(force = true); (list.isNotEmpty()) to "${list.size} advisories" }
        check("Restrooms (wmata.com)") { val ok = r.refreshRestrooms(force = true); ok to if (ok) "page read" else "page changed: using the built-in list" }
        check("Bike & Ride (wmata.com)") { val ok = r.refreshBikes(force = true); ok to if (ok) "page read" else "page changed: using the built-in list" }
        check("Special trains & events (wmata.com)") { val list = r.loadSpecialNews(force = true); true to "${list.size} items" }
        check("Weather (Open-Meteo)") {
            // Open-Meteo limits busy shared addresses (like this test machine's): wait and try again before calling it broken.
            var err: String? = null
            for (wait in listOf(0L, 20_000L, 60_000L)) {
                Thread.sleep(wait)
                err = runBlocking { r.refreshWeatherNow(force = true) }
                if (err == null || !err.contains("Too many")) break
            }
            when {
                err == null && r.snapshot().weather != null -> true to "forecast read"
                err?.contains("Too many") == true -> true to "WARNING: Open-Meteo busy for this test machine (not a problem on your phone)"
                else -> false to (err ?: "no forecast")
            }
        }
        check("Amtrak, MARC & VRE schedules") { val n = r.refreshRail(force = true); val trains = r.railTrains().size; (trains > 50) to "$n schedules downloaded, $trains trains" }
        check("Amtrak live status (Amtraker)") {
            val raw = Http.get("https://api-v3.amtraker.com/v3/trains")
            val n = org.json.JSONObject(raw).length(); (n > 0) to "$n trains reported"
        }
        for ((name, url) in listOf("MARC live (MDOT MTA)" to com.metrocast.app.data.Rail.MARC_LIVE, "VRE live" to com.metrocast.app.data.Rail.VRE_LIVE)) check(name) {
            val bytes = Http.bytes(url, 5_000_000)
            val trips = com.metrocast.app.data.GtfsRealtime.tripUpdates(bytes).size
            // A quiet hour can have no trains running: the feed answering (and reading) is what matters.
            (bytes.isNotEmpty()) to "${bytes.size} bytes, $trips trains with live times"
        }
        check("Capital Bikeshare (GBFS)") {
            val raw = Http.get("https://gbfs.capitalbikeshare.com/gbfs/en/station_status.json")
            val n = org.json.JSONObject(raw).getJSONObject("data").getJSONArray("stations").length(); (n > 100) to "$n docks"
        }

        results.forEach { (name, ok, detail) -> println("LIVESRC ${if (ok) "ok  " else "FAIL"} $name: $detail") }
        val broken = results.filter { !it.second }
        assertTrue("broken: " + broken.joinToString { it.first + " (" + it.third + ")" }, broken.isEmpty())
    }
}
