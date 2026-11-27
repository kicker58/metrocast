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

/** Trains on the live maps move when WMATA's positions change (every refresh, not just the first). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MapMovesRoboTest {
    @After fun done() = FakeWmata.uninstall()

    private fun spots(r: Repository) = r.lineMaps().values.flatMap { m -> (m.trains + m.branchTrains).map { it.id to it.pos } }.toMap()

    @Test fun repositoryMapsFollowEachNewSnapshot() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.getSharedPreferences("metrocast", Context.MODE_PRIVATE).edit().clear().putBoolean("onboarded", true).commit()
        FakeWmata.install()
        val r = Repository(ctx)
        var last: Map<String, Float>? = null
        for (snap in 1..5) {
            FakeWmata.snapshot = snap
            Thread.sleep(5) // a new cache time
            runBlocking { r.refresh(extras = false) }
            val now = spots(r)
            println("MAPMOVE snap $snap: ${now.size} trains, moved since last: ${last?.let { l -> now.count { (id, p) -> l[id] != null && l[id] != p } }}")
            if (last != null) assertTrue("snapshot $snap: no train moved", now.count { (id, p) -> last!![id] != null && last!![id] != p } > 0)
            last = now
        }
    }

    @Test fun viewModelMapsFollowEachNewSnapshot() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.getSharedPreferences("metrocast", Context.MODE_PRIVATE).edit().clear().putBoolean("onboarded", true).commit()
        FakeWmata.install(); resetViewModelFactory()
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val store = androidx.lifecycle.ViewModelStore()
        val vm = androidx.lifecycle.ViewModelProvider(store, androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.getInstance(app))[com.metrocast.app.ui.MainViewModel::class.java]
        fun waitFor(ok: () -> Boolean) { val t = System.currentTimeMillis(); while (!ok() && System.currentTimeMillis() - t < 8_000) { org.robolectric.shadows.ShadowLooper.idleMainLooper(25, java.util.concurrent.TimeUnit.MILLISECONDS); Thread.sleep(25) } }
        var last: Map<String, Float>? = null
        try {
            for (snap in 1..5) {
                FakeWmata.snapshot = snap
                val before = vm.state.value.maps
                vm.refresh()
                waitFor { vm.state.value.maps !== before && vm.state.value.maps.isNotEmpty() }
                val now = vm.state.value.maps.values.flatMap { m -> (m.trains + m.branchTrains).map { it.id to it.pos } }.toMap()
                val moved = last?.let { l -> now.count { (id, p) -> l[id] != null && l[id] != p } }
                println("MAPMOVE vm snap $snap: ${now.size} trains, moved: $moved")
                if (last != null) assertTrue("vm snapshot $snap: no train moved", (moved ?: 0) > 0)
                last = now
            }
        } finally { store.clear() }
    }

}

/** The real app on a test phone: Live trains left open, its own refresh timer running. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class LiveTrainsScreenMovesRoboTest {
    private val fake = object : org.junit.rules.TestRule {
        override fun apply(base: org.junit.runners.model.Statement, d: org.junit.runner.Description) = object : org.junit.runners.model.Statement() {
            override fun evaluate() {
                ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("metrocast", Context.MODE_PRIVATE).edit().clear().putBoolean("onboarded", true).commit()
                FakeWmata.install(); resetViewModelFactory(); com.metrocast.app.ui.Net.testOnline = true
                base.evaluate()
            }
        }
    }
    private val compose = androidx.compose.ui.test.junit4.createAndroidComposeRule<com.metrocast.app.ui.MainActivity>()
    @get:org.junit.Rule val rules: org.junit.rules.RuleChain = org.junit.rules.RuleChain.outerRule(fake).around(compose)
    @After fun done() { FakeWmata.uninstall(); com.metrocast.app.ui.Net.testOnline = null }

    private fun tap(t: String) {
        val n = compose.onAllNodes(androidx.compose.ui.test.hasText(t, substring = true) and androidx.compose.ui.test.hasClickAction()).fetchSemanticsNodes().first()
        compose.runOnUiThread { n.config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action?.invoke() }; compose.waitForIdle()
    }
    private fun vm() = androidx.lifecycle.ViewModelProvider(compose.activity)[com.metrocast.app.ui.MainViewModel::class.java]
    /** Real time passes (and the test clock with it), so the app's refresh timer fires by itself. */
    private fun live(ms: Long) { val end = System.currentTimeMillis() + ms; while (System.currentTimeMillis() < end) { org.robolectric.shadows.ShadowLooper.idleMainLooper(100, java.util.concurrent.TimeUnit.MILLISECONDS); compose.mainClock.advanceTimeBy(100); Thread.sleep(20) } ; compose.waitForIdle() }

    @Test fun trainsKeepMovingWhileTheScreenIsOpen() {
        compose.waitUntil(10_000) { compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("Menu")).fetchSemanticsNodes().isNotEmpty() }
        val every = vm().repo.prefs.refreshSeconds
        println("LIVEMOVE refresh every $every s")
        compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("Menu")).fetchSemanticsNodes().first().let { n -> compose.runOnUiThread { n.config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action?.invoke() } }
        compose.waitForIdle(); tap("Live trains"); live(1_500)
        var before = vm().state.value.maps
        for (snap in 2..4) {
            FakeWmata.snapshot = snap
            live(2_400) // about 12 s of the app's time: with a map open it refreshes every 10 s, whatever the setting
            val now = vm().state.value.maps
            val moved = now.values.flatMap { it.trains }.count { t -> before.values.flatMap { it.trains }.firstOrNull { it.id == t.id }?.let { it.pos != t.pos } == true }
            println("LIVEMOVE snap $snap: maps replaced=${now !== before}, trains moved=$moved; ${vm().debugState()}")
            assertTrue("snapshot $snap: the open Live trains screen got no new positions (${vm().debugState()})", moved > 0)
            before = now
        }
    }

    @Test fun trainsGlideInsteadOfJumping() {
        val st = listOf(com.metrocast.app.data.Station("A01", "One"), com.metrocast.app.data.Station("A02", "Two"))
        fun map(vararg t: com.metrocast.app.data.MapTrain) = mapOf("RD" to com.metrocast.app.data.LineMap("RD", st, t.toList(), emptyList(), emptyList(), 0L))
        fun tr(id: String, pos: Float, east: Boolean = true) = com.metrocast.app.data.MapTrain(id, pos, east, "x", 6)
        val a = map(tr("1", 2f), tr("2", 5f), tr("3", 1f))
        val b = map(tr("1", 3f), tr("2", 5f, east = false), tr("3", 9f), tr("4", 4f))
        val half = com.metrocast.app.ui.glideBetween(a, b, 0.5f)["RD"]!!.trains.associate { it.id to it.pos }
        assertTrue("moving on: halfway", half["1"] == 2.5f)
        assertTrue("turned around: no backwards slide", half["2"] == 5f)
        assertTrue("a big jump: no slide across the line", half["3"] == 9f)
        assertTrue("new train: just appears", half["4"] == 4f)
        assertTrue(com.metrocast.app.ui.glideBetween(a, b, 1f) === b)
    }
}
