package com.metrocast.app.robo

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.metrocast.app.data.Repository
import com.metrocast.app.data.TrainTracker
import com.metrocast.app.ui.MetroCastTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/** Opening fast after a while away, and saying so when WMATA's train-position feed is down. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class LoadingAndOutageRoboTest {
    @get:Rule val compose = createComposeRule()
    private fun repo(): Repository {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.getSharedPreferences("metrocast", Context.MODE_PRIVATE).edit().clear().commit()
        FakeWmata.install()
        return Repository(ctx)
    }
    @After fun done() { FakeWmata.uninstall(); Repository.lastPositionsDown = false }
    private fun sees(t: String) = compose.onAllNodesWithText(t, substring = true).fetchSemanticsNodes().isNotEmpty()

    @Test fun trainsFirstThenTheSlowChecks() {
        val r = repo()
        // What the screen needs: trains (and weather) only — none of the monthly lists or schedules.
        runBlocking { r.refresh(extras = false) }
        val slow = listOf("restrooms.html", "bike-and-ride", "mdotmta_gtfs_marc", "gtfs.vre.org", "content.amtrak.com", "weekend.html", "advisories")
        assertTrue(FakeWmata.seen().toString(), FakeWmata.seen().none { u -> slow.any { it in u } })
        assertTrue(FakeWmata.seen().any { "GetPrediction" in it }); assertTrue(FakeWmata.seen().any { "TrainPositions" in it })
        assertTrue(r.snapshot().trips.any { it.arrivals.isNotEmpty() })
        // Then the slower checks run on their own.
        runBlocking { r.refreshExtras() }
        assertTrue(FakeWmata.seen().any { "restrooms" in it })
    }

    @Test fun slowBackgroundChecksNeverHoldUpLiveTrains() {
        repo()
        // The monthly schedule download and the lists are very slow today.
        FakeWmata.slow = mapOf("restrooms.html" to 5_000L, "content.amtrak.com" to 5_000L, "mdotmta_gtfs_marc" to 5_000L)
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        resetViewModelFactory()
        val store = androidx.lifecycle.ViewModelStore()
        val vm = androidx.lifecycle.ViewModelProvider(store, androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.getInstance(app))[com.metrocast.app.ui.MainViewModel::class.java]
        vm.refresh()
        val start = System.currentTimeMillis()
        // Trains show quickly…
        while (vm.state.value.snapshot.trips.none { it.arrivals.isNotEmpty() } && System.currentTimeMillis() - start < 10_000) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper(); Thread.sleep(50)
        }
        assertTrue("trains took ${System.currentTimeMillis() - start} ms", vm.state.value.snapshot.trips.any { it.arrivals.isNotEmpty() })
        // …and while the slow checks are still running, the next live refresh goes ahead right away.
        val before = FakeWmata.seen().count { "GetPrediction" in it }
        Thread.sleep(300); org.robolectric.shadows.ShadowLooper.idleMainLooper()
        vm.refresh()
        val t2 = System.currentTimeMillis()
        while (FakeWmata.seen().count { "GetPrediction" in it } == before && System.currentTimeMillis() - t2 < 4_000) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper(); Thread.sleep(50)
        }
        assertTrue("the next refresh waited on the slow checks", FakeWmata.seen().count { "GetPrediction" in it } > before)
        assertTrue("that refresh took ${System.currentTimeMillis() - t2} ms", System.currentTimeMillis() - t2 < 4_000)
        // Tidy up: stop the view model's work and let the slow fake requests finish before the next test.
        store.clear(); FakeWmata.slow = emptyMap(); Thread.sleep(16_000)
    }

    @Test fun emptyPositionFeedIsAnOutageNotEmptyTracks() {
        val r = repo()
        runBlocking { r.refresh() }
        assertTrue(!r.positionsDown)                                     // normal: trains placed
        FakeWmata.bodies = mapOf("TrainPositions?" to """{"TrainPositions":[]}""")
        runBlocking { r.refresh() }
        assertTrue(r.positionsDown)                                      // arrivals, but no positions: WMATA's feed is down
        val snap = r.snapshot()
        compose.setContent { MetroCastTheme { com.metrocast.app.ui.LiveTrainsScreen(r.lineMaps(), onBack = {}) } }
        assertTrue(sees("WMATA isn't sharing train locations right now"))
        // Feed back: the note goes away.
        FakeWmata.bodies = emptyMap(); runBlocking { r.refresh() }
        assertTrue(!r.positionsDown)
        assertTrue(snap.trips.isNotEmpty())
    }

    @Test fun missingLineWorkedOutFromTheTrack() {
        val routes = TrainTracker.parseRoutes(File("src/test/resources/live/routes.json").readText())
        val pos = TrainTracker.parsePositions(File("src/test/resources/live2/pos_1.json").readText())
        // WMATA leaves the line out: put it back where the track belongs to one line only.
        val stripped = pos.map { it.copy(line = null) }
        val filled = TrainTracker.fillLines(stripped, routes)
        val back = filled.count { it.line != null }
        assertTrue("worked out $back of ${pos.size}", back > pos.size / 3)
        // Nearly always the right line (a Yellow train running on Green-only track reads as Green).
        val compared = filled.zip(pos).filter { (f, o) -> f.line != null && o.line != null }
        val right = compared.count { (f, o) -> f.line == o.line }
        assertTrue("$right of ${compared.size} right", right >= compared.size * 0.9)
        // Nothing to do / no routes: unchanged.
        val withLines = pos.filter { it.line != null }
        assertEquals(withLines, TrainTracker.fillLines(withLines, routes)); assertEquals(stripped, TrainTracker.fillLines(stripped, null))
        assertNull(TrainTracker.fillLines(listOf(stripped.first().copy(circuit = -5)), routes).single().line)
    }

    private fun newVm(): com.metrocast.app.ui.MainViewModel {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        resetViewModelFactory()
        val store = androidx.lifecycle.ViewModelStore()
        stores += store
        return androidx.lifecycle.ViewModelProvider(store, androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.getInstance(app))[com.metrocast.app.ui.MainViewModel::class.java]
    }
    private val stores = mutableListOf<androidx.lifecycle.ViewModelStore>()
    @After fun clearStores() { stores.forEach { it.clear() } }
    private fun waitFor(ms: Long, ok: () -> Boolean): Long {
        val start = System.currentTimeMillis()
        // The test clock only moves when told: move it along with real time, so the app's waits run out.
        while (!ok() && System.currentTimeMillis() - start < ms) { org.robolectric.shadows.ShadowLooper.idleMainLooper(25, java.util.concurrent.TimeUnit.MILLISECONDS); Thread.sleep(25) }
        return System.currentTimeMillis() - start
    }

    @Test fun slowWeatherNeverHoldsUpLiveTrains() {
        repo()
        // The weather service is very slow today (and the track layout is due its weekly download).
        FakeWmata.slow = mapOf("open-meteo" to 8_000L)
        val vm = newVm()
        vm.refresh()
        val took = waitFor(6_000) { vm.state.value.snapshot.trips.any { it.arrivals.isNotEmpty() } && vm.state.value.maps.isNotEmpty() }
        assertTrue("trains and the live map took $took ms (weather is slow)", took < 2_500)
        assertTrue(vm.state.value.maps.values.sumOf { it.trains.size + it.branchTrains.size } > 0)
        // The weather fills in by itself when it finally answers, without losing the trains.
        waitFor(12_000) { vm.state.value.snapshot.weather != null }
        assertTrue(vm.state.value.snapshot.weather != null)
        assertTrue(vm.state.value.snapshot.trips.any { it.arrivals.isNotEmpty() })
    }

    @Test fun trainsRetrySoonAfterAFailedFirstTry() {
        repo()
        // The phone just woke up: the first tries can't reach WMATA.
        FakeWmata.failing = mapOf("GetPrediction" to java.net.UnknownHostException("api.wmata.com"))
        val vm = newVm()
        vm.refresh()
        waitFor(3_000) { vm.state.value.trainError != null }
        assertTrue(vm.state.value.trainError != null)
        // The connection comes back: trains show within seconds, without waiting a whole refresh interval.
        FakeWmata.failing = emptyMap()
        val took = waitFor(10_000) { vm.state.value.snapshot.trips.any { it.arrivals.isNotEmpty() } }
        assertTrue("retry took $took ms", vm.state.value.snapshot.trips.any { it.arrivals.isNotEmpty() } && took < 8_000)
        assertNull(vm.state.value.trainError)
    }

    @Test fun firstOpenAsksForTrainsBeforeTheTrackLayoutArrives() {
        val r = repo()
        // Nothing on file yet and the (big) track layout is slow: arrivals don't wait for it.
        File(ApplicationProvider.getApplicationContext<Context>().filesDir, "standard_routes.json").delete()
        FakeWmata.slow = mapOf("StandardRoutes" to 1_500L)
        val t = Thread { runBlocking { r.refreshTrainsNow() } }.also { it.start() }
        val took = waitFor(1_200) { FakeWmata.seen().any { "GetPrediction" in it } && FakeWmata.seen().any { "TrainPositions" in it } }
        assertTrue("arrivals asked for after $took ms", took < 1_000)
        t.join()
        assertTrue(r.lineMaps().values.sumOf { it.trains.size + it.branchTrains.size } > 0)
    }
}
