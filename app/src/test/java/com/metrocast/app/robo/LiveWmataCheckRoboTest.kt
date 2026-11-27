package com.metrocast.app.robo

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.metrocast.app.data.Repository
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Diagnosis only (-PliveCheck): the app's own code against the real, live WMATA. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class LiveWmataCheckRoboTest {
    @Test fun liveTrainsFromRealWmata() {
        assumeTrue(System.getProperty("metrocast.livecheck") != null)
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.getSharedPreferences("metrocast", Context.MODE_PRIVATE).edit().clear().putBoolean("onboarded", true).commit()
        com.metrocast.app.ui.Net.testOnline = true
        val r = Repository(ctx)
        val res = runBlocking { r.refresh() }
        println("LIVECHECK refresh: weatherError=${res.weatherError} trainError=${res.trainError}")
        val s = r.snapshot()
        s.trips.forEach { t -> println("LIVECHECK trip ${t.trip.stationName}: ${t.arrivals.size} arrivals, location=${t.location?.trains?.size}") }
        val maps = r.lineMaps()
        println("LIVECHECK positions feed down: ${com.metrocast.app.data.Repository.lastPositionsDown}")
        println("LIVECHECK line maps: " + maps.entries.joinToString { "${it.key}=${it.value.trains.size + it.value.branchTrains.size}" })
    }

    /** How long each step of opening the app takes against the real WMATA (times only, nothing else printed). */
    @Test fun openTimings() {
        assumeTrue(System.getProperty("metrocast.livecheck") != null)
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.getSharedPreferences("metrocast", Context.MODE_PRIVATE).edit().clear().putBoolean("onboarded", true).commit()
        com.metrocast.app.ui.Net.testOnline = true
        val key = com.metrocast.app.BuildConfig.WMATA_KEY
        fun <T> t(label: String, f: () -> T): T { val a = System.nanoTime(); val r = f(); println("LIVETIME $label: ${(System.nanoTime() - a) / 1_000_000} ms"); return r }
        t("stations") { com.metrocast.app.data.WmataApi.allStationsRaw(key) }
        t("standard routes") { com.metrocast.app.data.WmataApi.standardRoutesRaw(key) }
        t("predictions (2 stations)") { com.metrocast.app.data.WmataApi.predictionsRaw(listOf("A01", "B03"), key) }
        t("train positions") { com.metrocast.app.data.WmataApi.trainPositionsRaw(key) }
        t("incidents") { com.metrocast.app.data.WmataApi.incidentsRaw(key) }
        t("weather") { com.metrocast.app.data.WeatherApi.forecastRaw(38.8983, -77.0281, true) }
        val r = Repository(ctx)
        t("first open: whole refresh (nothing cached)") { runBlocking { r.refresh(extras = false) } }
        // Opening after a while: everything on file, but the live data is old.
        ctx.getSharedPreferences("metrocast", Context.MODE_PRIVATE).edit().commit()
        t("reopen: whole refresh (files cached)") { runBlocking { r.refresh(forceWeather = true, extras = false) } }
        println("LIVETIME maps: " + r.lineMaps().entries.joinToString { "${it.key}=${it.value.trains.size + it.value.branchTrains.size}" })
    }

    /** How many entries WMATA's position feed has right now (counts only). */
    @Test fun feedSizeNow() {
        assumeTrue(System.getProperty("metrocast.livecheck") != null)
        val raw = com.metrocast.app.data.WmataApi.trainPositionsRaw(com.metrocast.app.BuildConfig.WMATA_KEY)
        val arr = org.json.JSONObject(raw).getJSONArray("TrainPositions")
        val withLine = (0 until arr.length()).count { !arr.getJSONObject(it).isNull("LineCode") }
        println("LIVEFEED entries=${arr.length()} withLine=$withLine bytes=${raw.length}")
    }
}
