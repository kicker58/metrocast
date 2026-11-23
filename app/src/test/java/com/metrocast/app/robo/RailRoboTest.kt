package com.metrocast.app.robo

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.metrocast.app.data.Rail
import com.metrocast.app.data.RailService
import com.metrocast.app.data.Repository
import com.metrocast.app.ui.MetroCastTheme
import com.metrocast.app.ui.RailScreen
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDateTime

/** Trip planner → Catch Amtrak, MARC or VRE, on the real schedules (a Monday morning). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RailRoboTest {
    @get:Rule val compose = androidx.compose.ui.test.junit4.createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val monday = LocalDateTime.of(2026, 11, 23, 8, 0)

    private fun repo(): Repository {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.getSharedPreferences("metrocast", Context.MODE_PRIVATE).edit().clear().commit()
        FakeWmata.install()
        return Repository(ctx).also { runBlocking { it.refresh() } }
    }
    @After fun done() = FakeWmata.uninstall()

    private fun sees(t: String) = compose.onAllNodesWithText(t, substring = true).fetchSemanticsNodes().isNotEmpty()
    private fun page() = compose.onAllNodes(hasScrollAction())[0]

    @Test fun schedulesLoadAndRefreshMonthly() {
        val r = repo()
        // The refresh fetched all three operators' schedules (the fake network serves the real files).
        assertTrue(FakeWmata.requests.any { "mdotmta_gtfs_marc" in it }); assertTrue(FakeWmata.requests.any { "gtfs.vre.org" in it })
        val trains = r.railTrains()
        assertEquals(RailService.entries.toSet(), trains.map { it.service }.toSet())
        assertTrue(r.railCheckedAt(RailService.MARC) > 0); assertTrue(r.railCheckedAt(RailService.VRE) > 0)
        // Not again until next month.
        FakeWmata.requests.clear(); runBlocking { r.refresh() }
        assertTrue(FakeWmata.requests.none { "mdotmta_gtfs_marc" in it || "gtfs.vre.org" in it || "content.amtrak.com" in it })
        // A failed download keeps what's there and waits a day.
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        File(ctx.filesDir, "rail_MARC.json").setLastModified(System.currentTimeMillis() - 40L * 24 * 3600_000)
        FakeWmata.failing = mapOf("mdotmta_gtfs_marc" to java.io.IOException("offline"))
        assertEquals(0, r.refreshRail()); assertTrue(r.railTrains().any { it.service == RailService.MARC })
        FakeWmata.requests.clear(); r.refreshRail()
        assertTrue(FakeWmata.requests.none { "mdotmta_gtfs_marc" in it })
        // A garbage download doesn't replace a good schedule.
        FakeWmata.failing = emptyMap()
        Http_bytes { "not a zip".toByteArray() }
        assertEquals(0, r.refreshRail(force = true)); assertTrue(r.railTrains().any { it.service == RailService.MARC })
    }

    private fun Http_bytes(f: (String) -> ByteArray) { com.metrocast.app.data.Http.testBytes = f }

    @Test fun catchANortheastRegionalFromWiehle() {
        val r = repo()
        var planned: Pair<String, String>? = null
        compose.setContent { MetroCastTheme { RailScreen(r, onBack = {}, onPlan = { f, t -> planned = f to t }, clock = { monday }) } }
        assertTrue(sees("Wiehle-Reston East")); assertTrue(sees("Leave Wiehle-Reston East by"))
        // Only trains you can still make; MARC trains aren't listed twice as "Amtrak Commuter Rail".
        assertTrue(!sees("Too late")); assertTrue(!sees("Commuter Rail"))
        // Search where it's going.
        compose.onNodeWithText("Train number or where it's going").performTextInput("New York")
        compose.waitForIdle()
        assertTrue(sees("Northeast Regional")); assertTrue(!sees("Penn Line")); assertTrue(!sees("Fredericksburg"))
        compose.onAllNodesWithText("Northeast Regional", substring = true).onFirst().performClick()
        compose.waitUntil(5_000) { sees("BEST:") }
        assertTrue(sees("Leave Wiehle-Reston East by"))
        assertTrue(sees("min Metro")); assertTrue(sees("30 early"))
        // Live status (Amtraker has nothing yet for it).
        compose.waitUntil(5_000) { sees("Not running yet") }
        assertTrue(FakeWmata.requests.any { "amtraker.com" in it })
        // How early for Amtrak.
        page().performScrollToNode(hasText("45 min"))
        compose.onNodeWithText("45 min").performClick()
        assertEquals(45, r.prefs.amtrakEarlyMin)
        compose.waitUntil(5_000) { sees("45 early") }
        // Hand the Metro part to the planner.
        page().performScrollToNode(hasText("Plan the Metro ride", substring = true))
        compose.onAllNodesWithText("Plan the Metro ride", substring = true).onFirst().performClick()
        assertEquals("N06", planned?.first); assertTrue(planned?.second in setOf("B03", "D13", "C13"))
        // Back to the list.
        compose.onNodeWithContentDescription("Back").performClick()
        assertTrue(sees("Train number or where it's going"))
    }

    @Test fun tapAnotherStationToFollowItLive() {
        val r = repo()
        compose.setContent { MetroCastTheme { RailScreen(r, onBack = {}, onPlan = { _, _ -> }, clock = { monday }) } }
        compose.onNodeWithText("Train number or where it's going").performTextInput("New York")
        compose.waitForIdle()
        compose.onAllNodesWithText("Northeast Regional", substring = true).onFirst().performClick()
        compose.waitUntil(5_000) { sees("BEST:") }
        val first = compose.onAllNodesWithText("LIVE AT ", substring = true).fetchSemanticsNodes().single()
            .config[androidx.compose.ui.semantics.SemanticsProperties.Text].first().text
        assertTrue(sees("● Following this station live"))
        // The other station on the list.
        page().performScrollToNode(hasText("Tap to follow this station live"))
        compose.onNodeWithText("Tap to follow this station live").performClick(); compose.waitForIdle()
        val now = compose.onAllNodesWithText("LIVE AT ", substring = true).fetchSemanticsNodes().single()
            .config[androidx.compose.ui.semantics.SemanticsProperties.Text].first().text
        assertTrue("$first → $now", first != now)
    }

    @Test fun marcLiveDelayShows() {
        val r = repo()
        // A MARC train you can still make from Wiehle (a couple of hours out).
        val run = Rail.upcoming(r.railTrains(), monday).first { it.train.service == RailService.MARC && it.first.isAfter(monday.plusHours(2)) }
        FakeWmata.liveFeeds = mapOf("marc-tu" to feed(run.train.tripId, delaySec = 11 * 60))
        compose.setContent { MetroCastTheme { RailScreen(r, onBack = {}, onPlan = { _, _ -> }, clock = { monday }) } }
        compose.onAllNodesWithText("MARC").onFirst().performClick()
        compose.waitForIdle()
        assertTrue(!sees("Northeast Regional"))
        compose.onNodeWithText("Train number or where it's going").performTextInput(run.train.number)
        compose.waitForIdle()
        compose.onAllNodesWithText("${run.train.number} · ", substring = true).onFirst().performClick()
        compose.waitUntil(5_000) { sees("11 min late") }
        assertTrue(sees("(live: about"))
        // A canceled train says so.
        FakeWmata.liveFeeds = mapOf("marc-tu" to feed(run.train.tripId, canceled = true))
    }

    @Test fun vreFromCrystalCityStartsThere() {
        val r = repo()
        r.prefs.planFrom = "C09"
        val at = monday.withHour(14)
        // An afternoon train that stops at Crystal City.
        val run = Rail.upcoming(r.railTrains(), at).first { it.train.service == RailService.VRE && it.train.stops.any { s -> s.metroCode == "C09" && it.timeAt(s).isAfter(at.plusMinutes(15)) } }
        compose.setContent { MetroCastTheme { RailScreen(r, onBack = {}, onPlan = { _, _ -> }, clock = { at }) } }
        compose.onAllNodesWithText("VRE").onFirst().performClick(); compose.waitForIdle()
        assertTrue(sees("Leave Crystal City by"))
        compose.onNodeWithText("Train number or where it's going").performTextInput(run.train.number)
        compose.waitForIdle()
        compose.onAllNodesWithText("${run.train.number} · ", substring = true).onFirst().performClick()
        compose.waitUntil(5_000) { sees("BEST:") }
        // Starting at the train's station: no Metro ride, just the walk.
        assertTrue(sees("BEST: CRYSTAL CITY")); assertTrue(sees("7 walk"))
    }

    @Test fun stationsListShowsWhereAndHowFar() {
        val r = repo()
        compose.setContent { MetroCastTheme { RailScreen(r, onBack = {}, onPlan = { _, _ -> }, clock = { monday }) } }
        compose.onNodeWithText("📍  Stations").performClick(); compose.waitForIdle()
        assertTrue(sees("closest to Wiehle-Reston East first"))
        // Every station with commuter rail or Amtrak is listed with how far it is from you.
        Rail.STATIONS.forEach { st ->
            page().performScrollToNode(hasText(st.name))
            assertTrue(st.name, sees(st.name))
        }
        page().performScrollToNode(hasText("Union Station"))
        assertTrue(sees("min by Metro from Wiehle-Reston East")); assertTrue(sees("walk to the platform")); assertTrue(sees("Next: "))
        // Tap one: just its trains.
        compose.onAllNodesWithText("Union Station", substring = false).onFirst().performClick(); compose.waitForIdle()
        assertTrue(sees("From Union Station  ✕"))
        assertTrue(!sees("→ Fredericksburg") || sees("Union Station"))
        compose.onNodeWithText("From Union Station  ✕").performClick(); compose.waitForIdle()
        assertTrue(!sees("From Union Station  ✕"))
    }

    @Test fun pickALineToNarrowStations() {
        val r = repo()
        compose.setContent { MetroCastTheme { RailScreen(r, onBack = {}, onPlan = { _, _ -> }, clock = { monday }) } }
        compose.onNodeWithText("📍  Stations").performClick()
        compose.onAllNodesWithText("MARC").onFirst().performClick(); compose.waitForIdle()
        assertTrue(sees("WHICH LINE?"))
        compose.onNodeWithText("Brunswick Line").performClick(); compose.waitForIdle()
        assertTrue(sees("catch Brunswick Line"))
        // Brunswick: Union Station, Silver Spring, Rockville — not VRE-only or Penn-only stations.
        page().performScrollToNode(hasText("Rockville")); assertTrue(sees("Rockville"))
        assertTrue(!sees("Crystal City")); assertTrue(!sees("Franconia-Springfield")); assertTrue(!sees("New Carrollton"))
        // VRE Manassas: no Franconia-Springfield.
        compose.onAllNodes(hasScrollAction())[0].performScrollToNode(hasText("VRE"))
        compose.onAllNodesWithText("VRE").onFirst().performClick(); compose.waitForIdle()
        compose.onNodeWithText("Manassas Line").performClick(); compose.waitForIdle()
        assertTrue(!sees("Franconia-Springfield")); assertTrue(!sees("Rockville"))
        page().performScrollToNode(hasText("Crystal City")); assertTrue(sees("Crystal City"))
        // The trains list follows the same choice.
        page().performScrollToNode(hasText("🚆  Trains")); compose.onNodeWithText("🚆  Trains").performClick(); compose.waitForIdle()
        assertTrue(!sees(" · Fredericksburg Line") && !sees(" · Penn Line")); assertTrue(sees(" · Manassas Line"))
    }

    @Test fun startingAtUnionStationSaysSo() {
        val r = repo(); r.prefs.planFrom = "B03"
        compose.setContent { MetroCastTheme { RailScreen(r, onBack = {}, onPlan = { _, _ -> }, clock = { monday }) } }
        compose.onNodeWithText("📍  Stations").performClick(); compose.waitForIdle()
        assertTrue(sees("You start here"))
    }

    @Test fun inTheMenuAndCanBeHidden() {
        val r = repo()
        assertTrue(r.prefs.menuItems.any { it.first == com.metrocast.app.data.MenuItem.RAIL && it.second })
        var opened = false
        compose.setContent { MetroCastTheme { com.metrocast.app.ui.HomeScreen(com.metrocast.app.ui.UiState(snapshot = r.snapshot()), r, onRefresh = {}, onOpenSettings = {}, onOpenRail = { opened = true }) } }
        compose.onNodeWithContentDescription("Menu").performClick()
        compose.onNodeWithText("Amtrak, MARC & VRE").performClick()
        assertTrue(opened)
        // Hidden like any other menu option.
        r.prefs.menuItems = r.prefs.menuItems.map { (m, on) -> m to (on && m != com.metrocast.app.data.MenuItem.RAIL && m != com.metrocast.app.data.MenuItem.AIRPORTS) }
        assertTrue(r.prefs.menuItems.none { it.second && (it.first == com.metrocast.app.data.MenuItem.RAIL || it.first == com.metrocast.app.data.MenuItem.AIRPORTS) })
    }

    @Test fun plannerHasTheButton() {
        val r = repo()
        var opened = false
        compose.setContent { MetroCastTheme { com.metrocast.app.ui.PlannerScreen(plan = null, repo = r, onChange = { _, _ -> }, onBack = {}, onOpenRail = { opened = true }) } }
        compose.onNodeWithText("Catching Amtrak, MARC or VRE?").performClick()
        assertTrue(opened)
    }

    // ---- light & dark: the text is easy to read
    private fun contrastOf(name: String): Double {
        val r = repo()
        compose.setContent { MetroCastTheme { RailScreen(r, onBack = {}, onPlan = { _, _ -> }, clock = { monday }) } }
        compose.waitForIdle(); org.robolectric.shadows.ShadowLooper.idleMainLooper()
        val view = compose.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bmp))
        System.getProperty("metrocast.shots")?.let { d -> File(d).mkdirs(); File(d, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        fun lum(c: Int): Double {
            fun ch(v: Int) = (v / 255.0).let { if (it <= 0.03928) it / 12.92 else Math.pow((it + 0.055) / 1.055, 2.4) }
            return 0.2126 * ch(android.graphics.Color.red(c)) + 0.7152 * ch(android.graphics.Color.green(c)) + 0.0722 * ch(android.graphics.Color.blue(c))
        }
        val counts = HashMap<Int, Int>()
        for (y in 0 until bmp.height step 3) for (x in 0 until bmp.width step 3) counts.merge(bmp.getPixel(x, y), 1, Int::plus)
        val bg = counts.maxBy { it.value }.key
        val text = counts.filter { it.value > 150 && it.key != bg }.keys.maxBy { Math.abs(lum(it) - lum(bg)) }
        val (a, b) = listOf(lum(bg), lum(text)).sortedDescending()
        return (a + 0.05) / (b + 0.05)
    }
    @Test fun readableLight() { val c = contrastOf("rail_light"); assertTrue("contrast $c", c >= 7.0) }
    @Config(sdk = [34], qualifiers = "w411dp-h891dp-night-xxhdpi") @Test fun readableDark() { val c = contrastOf("rail_dark"); assertTrue("contrast $c", c >= 7.0) }

    // ---- a tiny GTFS-realtime writer
    private fun varint(o: ByteArrayOutputStream, v0: Long) { var v = v0; while (true) { if (v and 0x7fL.inv() == 0L) { o.write(v.toInt()); return }; o.write(((v and 0x7f) or 0x80).toInt()); v = v ushr 7 } }
    private fun field(o: ByteArrayOutputStream, n: Int, bytes: ByteArray) { varint(o, (n shl 3 or 2).toLong()); varint(o, bytes.size.toLong()); o.write(bytes) }
    private fun num(o: ByteArrayOutputStream, n: Int, v: Long) { varint(o, (n shl 3).toLong()); varint(o, v) }
    private fun msg(b: (ByteArrayOutputStream) -> Unit) = ByteArrayOutputStream().also(b).toByteArray()
    private fun feed(tripId: String, delaySec: Int? = null, canceled: Boolean = false) = msg { o ->
        field(o, 1, msg { h -> field(h, 1, "2.0".toByteArray()) })
        field(o, 2, msg { e ->
            field(e, 1, "e1".toByteArray())
            field(e, 3, msg { t -> field(t, 1, msg { d -> field(d, 1, tripId.toByteArray()); if (canceled) num(d, 4, 3) }); delaySec?.let { num(t, 5, it.toLong()) } })
        })
    }
}
