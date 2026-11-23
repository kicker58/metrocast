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
        assertTrue(FakeWmata.requests.toString(), FakeWmata.requests.none { u -> slow.any { it in u } })
        assertTrue(FakeWmata.requests.any { "GetPrediction" in it }); assertTrue(FakeWmata.requests.any { "TrainPositions" in it })
        assertTrue(r.snapshot().trips.any { it.arrivals.isNotEmpty() })
        // Then the slower checks run on their own.
        runBlocking { r.refreshExtras() }
        assertTrue(FakeWmata.requests.any { "restrooms" in it })
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
        val before = FakeWmata.requests.count { "GetPrediction" in it }
        Thread.sleep(300); org.robolectric.shadows.ShadowLooper.idleMainLooper()
        vm.refresh()
        val t2 = System.currentTimeMillis()
        while (FakeWmata.requests.count { "GetPrediction" in it } == before && System.currentTimeMillis() - t2 < 4_000) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper(); Thread.sleep(50)
        }
        assertTrue("the next refresh waited on the slow checks", FakeWmata.requests.count { "GetPrediction" in it } > before)
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
}
