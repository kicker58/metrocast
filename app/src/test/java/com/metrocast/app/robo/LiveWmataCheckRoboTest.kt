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
}
