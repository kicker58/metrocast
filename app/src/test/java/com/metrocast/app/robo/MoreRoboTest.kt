package com.metrocast.app.robo

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.hasText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.metrocast.app.data.Repository
import com.metrocast.app.notify.BootReceiver
import com.metrocast.app.notify.LiveTripTicker
import com.metrocast.app.ui.MetroCastTheme
import com.metrocast.app.ui.PlannerScreen
import com.metrocast.app.widget.WidgetBody
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/** Widget drawing, background receivers as Android delivers them, and restroom notes on screen. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class MoreRoboTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var ctx: Context
    private lateinit var repo: Repository

    @Before fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        ctx.getSharedPreferences("metrocast", Context.MODE_PRIVATE).edit().clear().commit()
        FakeWmata.install()
        repo = Repository(ctx)
    }
    @After fun tearDown() = FakeWmata.uninstall()

    // ---------------------------------------------------------------- the home-screen widget

    @Test fun widgetBoardShowsTripsAndTimes() = runGlanceAppWidgetUnitTest {
        runBlocking { repo.refresh() }
        val snap = repo.snapshot()
        setAppWidgetSize(DpSize(320.dp, 320.dp))
        provideComposable { androidx.glance.GlanceTheme { WidgetBody(snap, refreshing = false) } }
        onAllNodes(hasText("WIEHLE-RESTON EAST")).assertCountEquals(2)   // board header + the trip back's destination
        onAllNodes(hasText("LN")).assertCountEquals(2)    // one departure board per home trip
        onAllNodes(hasText("DEST")).assertCountEquals(2)
    }

    @Test fun widgetWithoutTripsOrTimes() = runGlanceAppWidgetUnitTest {
        repo.prefs.trips = emptyList()
        val snap = repo.snapshot()
        setAppWidgetSize(DpSize(250.dp, 120.dp))
        provideComposable { androidx.glance.GlanceTheme { WidgetBody(snap, refreshing = true) } }
        onNode(hasText("NO TRIPS SELECTED")).assertExists()
        onNode(hasText("UPDATING")).assertExists()
    }

    // ---------------------------------------------------------------- background receivers

    private fun deliver(intent: Intent) {
        ctx.sendBroadcast(intent)
        shadowOf(Looper.getMainLooper()).idle()
        Thread.sleep(1500) // the receivers finish their work on a background thread
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun liveTripCheckKeepsGoingDuringATripAndStopsAfter(): Unit = runBlocking {
        repo.refresh()
        val am = ctx.getSystemService(AlarmManager::class.java)
        repo.startLiveTrip("C02", "F05"); repo.boardLiveTrain()
        deliver(Intent(ctx, LiveTripTicker::class.java))
        assertTrue("should schedule the next check during a trip",
            shadowOf(am).scheduledAlarms.any { it.operation?.let { op -> shadowOf(op).savedIntent.component?.className?.endsWith("LiveTripTicker") } == true })
        repo.endLiveTrip()
        shadowOf(am).scheduledAlarms.toList().forEach { a -> a.operation?.let { am.cancel(it) } }
        deliver(Intent(ctx, LiveTripTicker::class.java))
        assertTrue("should stop checking after the trip",
            shadowOf(am).scheduledAlarms.none { it.operation?.let { op -> shadowOf(op).savedIntent.component?.className?.endsWith("LiveTripTicker") } == true })
    }

    @Test fun rebootSetsUpBackgroundWorkWithoutLeaveReminders() {
        ctx.getSharedPreferences("metrocast", Context.MODE_PRIVATE).edit().putBoolean("leave_alerts", true).commit()
        BootReceiver().onReceive(ctx, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertTrue(!ctx.getSharedPreferences("metrocast", Context.MODE_PRIVATE).contains("leave_alerts"))
        val am = ctx.getSystemService(AlarmManager::class.java)
        assertTrue(shadowOf(am).scheduledAlarms.none { it.operation?.let { op -> shadowOf(op).savedIntent.component?.className?.endsWith("LeaveTicker") } == true })
    }

    // ---------------------------------------------------------------- restrooms on screen

    @Test fun plannerShowsRestrooms(): Unit = runBlocking {
        repo.refresh(); repo.setPlan("N06", "C04"); repo.refresh()
        compose.setContent { MetroCastTheme { PlannerScreen(repo.snapshot().plan, repo, onChange = { _, _ -> }, onBack = {}, onStartLive = {}) } }
        fun sees(t: String) = compose.onAllNodesWithText(t, substring = true).fetchSemanticsNodes().isNotEmpty()
        assertTrue("Wiehle: open restroom", sees("🚻 Restroom — open, no need to ask"))
        assertTrue("Foggy Bottom: ask the manager", sees("ask the station manager"))
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class RestroomsScreenRoboTest {
    @get:Rule val compose = createComposeRule()

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.getSharedPreferences("metrocast", Context.MODE_PRIVATE).edit().clear().commit()
        FakeWmata.install()
        runBlocking { Repository(ctx).refresh() }
    }
    @After fun tearDown() = FakeWmata.uninstall()

    private fun sees(t: String) = compose.onAllNodesWithText(t, substring = true).fetchSemanticsNodes().isNotEmpty()

    @Test fun listFiltersAndPlansATrip() {
        var planned: Pair<String?, String?>? = null
        compose.setContent { MetroCastTheme { com.metrocast.app.ui.RestroomsScreen(onBack = {}, onPlan = { f, t -> planned = f to t }) } }
        assertTrue(sees("36 stations have restrooms")); assertTrue(sees("12 are open to walk in"))
        assertTrue(sees("DC · 9")); assertTrue(sees("Cleveland Park"))
        // Walk-in only: DC and Maryland disappear.
        compose.onNodeWithText("Walk in").performClick()
        assertTrue(sees("Virginia · 12")); assertTrue(!sees("DC ·")); assertTrue(!sees("Maryland ·"))
        compose.onNode(androidx.compose.ui.test.hasScrollAction()).performScrollToNode(androidx.compose.ui.test.hasText("Wiehle-Reston East"))
        // Tap a station, plan a trip there.
        compose.onNodeWithText("Wiehle-Reston East").performClick()
        compose.onNodeWithText("Plan a trip here").performClick()
        assertTrue(planned == (null to "N06"))
        // Ask-manager only.
        compose.onNode(androidx.compose.ui.test.hasScrollAction()).performScrollToIndex(0)
        compose.onNodeWithText("Ask manager").performClick()
        assertTrue(sees("DC · 9"))
        for (h in listOf("Maryland · 9", "Virginia · 6")) compose.onNode(androidx.compose.ui.test.hasScrollAction()).performScrollToNode(androidx.compose.ui.test.hasText(h))
    }

    @Test fun menuOpensRestroomsFromHome() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val repo = Repository(ctx)
        var opened = false
        compose.setContent {
            MetroCastTheme {
                com.metrocast.app.ui.HomeScreen(com.metrocast.app.ui.UiState(snapshot = repo.snapshot(), maps = repo.lineMaps()), repo,
                    onRefresh = {}, onOpenSettings = {}, onOpenRestrooms = { opened = true })
            }
        }
        compose.onNodeWithContentDescription("Menu").performClick()
        compose.onNodeWithText("Restrooms").performClick()
        assertTrue(opened)
    }
}

/** Home-screen trips between any two stations, there and back, with or without a transfer. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class AnyStationTripsRoboTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var ctx: Context
    private lateinit var repo: Repository

    @Before fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        ctx.getSharedPreferences("metrocast", Context.MODE_PRIVATE).edit().clear().commit()
        FakeWmata.install()
        repo = Repository(ctx)
        runBlocking { repo.refresh() }
    }
    @After fun tearDown() = FakeWmata.uninstall()

    @Test fun everyPairBuildsASensibleTrip(): Unit = runBlocking {
        val all = com.metrocast.app.data.StationDirectory.stations
        val rnd = kotlin.random.Random(5)
        var transfers = 0; var checked = 0
        repeat(400) {
            val a = all.random(rnd); val b = all.random(rnd)
            val t = repo.buildTrip(a.code, b.code, com.metrocast.app.data.Trip.GOING)
            if (a.code == b.code) { org.junit.Assert.assertNull(t); return@repeat }
            org.junit.Assert.assertNotNull("${a.name}→${b.name}", t); t!!
            checked++
            assertTrue("${a.name}: boards at the right station", t.stationCode in a.codes)
            assertTrue("${b.name}: ends at the right station", com.metrocast.app.data.StationDirectory.find(t.destCode)!!.code == b.code)
            val line = repo.loadLineStations(t.lineCode).map { it.code } + com.metrocast.app.data.LineMapBuilder.BRANCH.map { it.code }
            assertTrue("${a.name}→${b.name}: boards on the ${t.lineCode} line", t.stationCode in line)
            assertTrue("${a.name}→${b.name}: first train goes to ${t.towardCode} on ${t.lineCode}", t.towardCode in line)
            if (t.finalCode != null) { transfers++; assertTrue(t.via!!.startsWith("Change at")) } else org.junit.Assert.assertNull(t.via)
            // Saving and loading keeps everything.
            repo.prefs.trips = listOf(t)
            org.junit.Assert.assertEquals(t, repo.prefs.trips.single())
        }
        println("Any-station trips checked: $checked, with a transfer: $transfers")
        assertTrue(transfers > 100)
    }

    @Test fun transferTripOnTheHomeScreenAndWidget(): Unit = runBlocking {
        val going = repo.buildTrip("N06", "F05", com.metrocast.app.data.Trip.GOING)!!
        val back = repo.buildTrip("F05", "N06", com.metrocast.app.data.Trip.BACK)!!
        repo.prefs.trips = listOf(going, back)
        repo.refresh()
        val snap = repo.snapshot()
        val g = snap.trips[0]
        // Whole-trip stops (Wiehle → L'Enfant → Navy Yard), not just the first train's.
        assertTrue("stops ${g.ride?.stops}", (g.ride?.stops ?: 0) > 15)
        compose.setContent {
            MetroCastTheme {
                com.metrocast.app.ui.HomeScreen(com.metrocast.app.ui.UiState(snapshot = snap, maps = repo.lineMaps()), repo, onRefresh = {}, onOpenSettings = {})
            }
        }
        compose.onNode(androidx.compose.ui.test.hasScrollAction()).performScrollToNode(androidx.compose.ui.test.hasText("GOING THERE", substring = true))
        fun sees(t: String) = compose.onAllNodesWithText(t, substring = true).fetchSemanticsNodes().isNotEmpty()
        assertTrue(sees("Navy Yard-Ballpark")); assertTrue(sees("🔁 Change at L'Enfant Plaza to Green"))
        compose.onNode(androidx.compose.ui.test.hasScrollAction()).performScrollToNode(androidx.compose.ui.test.hasText("COMING BACK", substring = true))
    }

    @Test fun widgetShowsRealDestination() = runGlanceAppWidgetUnitTest {
        repo.prefs.trips = listOf(repo.buildTrip("N06", "F05", com.metrocast.app.data.Trip.GOING)!!)
        runBlocking { repo.refresh() }
        val snap = repo.snapshot()
        setAppWidgetSize(DpSize(320.dp, 200.dp))
        provideComposable { androidx.glance.GlanceTheme { WidgetBody(snap, refreshing = false) } }
        onNode(hasText("→ NAVY YARD-BALLPARK")).assertExists()
    }
}

/** The three Metro-themed widgets, at their sizes, with live data, transfers and every empty state. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class ThemedWidgetsRoboTest {
    private lateinit var ctx: Context
    private lateinit var repo: Repository

    @Before fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        ctx.getSharedPreferences("metrocast", Context.MODE_PRIVATE).edit().clear().commit()
        FakeWmata.install()
        repo = Repository(ctx)
        runBlocking { repo.refresh() }
    }
    @After fun tearDown() = FakeWmata.uninstall()

    private fun snap() = runBlocking { repo.refresh(); repo.snapshot() }

    @Test fun pylonShowsStationLinesAndNextTrain() = runGlanceAppWidgetUnitTest {
        val s = snap()
        setAppWidgetSize(DpSize(160.dp, 160.dp))
        provideComposable { com.metrocast.app.widget.PylonBody(s, refreshing = false) }
        onNode(androidx.glance.testing.unit.hasTextEqualTo("M")).assertExists()
        onNode(hasText("Wiehle-Reston East")).assertExists()
        onNode(hasText("➡ GOING · TO FOGGY BOTTOM-GWU")).assertExists()
    }

    @Test fun nextTrainShowsBothTrips() = runGlanceAppWidgetUnitTest {
        val s = snap()
        setAppWidgetSize(DpSize(320.dp, 90.dp))
        provideComposable { com.metrocast.app.widget.NextTrainBody(s, refreshing = false) }
        onNode(hasText("➡ GOING")).assertExists()
        onNode(hasText("⬅ BACK")).assertExists()
        onNode(hasText("to Wiehle-Reston East")).assertExists()
    }

    @Test fun nextTrainNarrowShowsOneTrip() = runGlanceAppWidgetUnitTest {
        val s = snap()
        setAppWidgetSize(DpSize(150.dp, 80.dp))
        provideComposable { com.metrocast.app.widget.NextTrainBody(s, refreshing = true) }
        onNode(hasText("➡ GOING")).assertExists()
        onAllNodes(hasText("⬅ BACK")).assertCountEquals(0)
    }

    @Test fun trackShowsYourStationAndTrains() = runGlanceAppWidgetUnitTest {
        repo.prefs.showTrainLocation = true
        val s = snap()
        setAppWidgetSize(DpSize(320.dp, 200.dp))
        provideComposable { com.metrocast.app.widget.TrackBody(s, refreshing = false) }
        onNode(hasText("🚇 LIVE TRACK")).assertExists()
        onNode(hasText("YOU: Wiehle-Reston East")).assertExists()
        onNode(hasText("Wiehle-Reston East → Foggy Bottom-GWU")).assertExists()
    }

    @Test fun transferTripOnEveryStyle() {
        repo.prefs.trips = listOf(repo.buildTrip("N06", "F05", com.metrocast.app.data.Trip.GOING)!!)
        val s = snap()
        for (body in listOf<@androidx.compose.runtime.Composable () -> Unit>(
            { com.metrocast.app.widget.PylonBody(s, false) }, { com.metrocast.app.widget.NextTrainBody(s, false) }, { com.metrocast.app.widget.TrackBody(s, false) },
        )) runGlanceAppWidgetUnitTest {
            setAppWidgetSize(DpSize(320.dp, 200.dp))
            provideComposable { body() }
            onAllNodes(hasText("Navy Yard-Ballpark", ignoreCase = true)).assertAny(hasText("Navy Yard-Ballpark", ignoreCase = true))
        }
    }

    @Test fun emptyStatesNeverCrash() {
        val cases = listOf<() -> Unit>(
            { repo.prefs.trips = emptyList() },
            { repo.prefs.trips = repo.prefs.trips.map { it.copy(showOnWidget = false) } },
            { repo.prefs.apiKey = "" },
            { FakeWmata.failing = mapOf("GetPrediction" to java.io.IOException("down"), "TrainPositions" to java.io.IOException("down")) },
        )
        for (setup in cases) {
            ctx.getSharedPreferences("metrocast", Context.MODE_PRIVATE).edit().clear().commit(); FakeWmata.install()
            repo = Repository(ctx); setup()
            val s = runCatching { snap() }.getOrElse { repo.snapshot() }
            for (sz in listOf(DpSize(110.dp, 57.dp), DpSize(200.dp, 110.dp), DpSize(400.dp, 300.dp)))
                for (body in listOf<@androidx.compose.runtime.Composable () -> Unit>(
                    { com.metrocast.app.widget.PylonBody(s, true) }, { com.metrocast.app.widget.NextTrainBody(s, false) }, { com.metrocast.app.widget.TrackBody(s, false) },
                )) runGlanceAppWidgetUnitTest { setAppWidgetSize(sz); provideComposable { body() }; onAllNodes(hasText("")).assertAll(hasText("")) }
        }
    }
}

/** Choosing which stops each widget shows. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class WidgetChoiceRoboTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var ctx: Context
    private lateinit var repo: Repository

    @Before fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        ctx.getSharedPreferences("metrocast", Context.MODE_PRIVATE).edit().clear().commit()
        FakeWmata.install()
        repo = Repository(ctx)
        runBlocking { repo.refresh() }
    }
    @After fun tearDown() = FakeWmata.uninstall()

    private fun sees(t: String) = compose.onAllNodesWithText(t, substring = true).fetchSemanticsNodes().isNotEmpty()

    @Test fun pickStopsOrderAndAddOtherStations() {
        var done = false
        compose.setContent { MetroCastTheme { com.metrocast.app.widget.WidgetConfigScreen(repo, 42, "MetroCast · Next train", onDone = { done = true }, onCancel = {}) } }
        assertTrue(sees("Choose your stops")); assertTrue(sees("➡️ Going there")); assertTrue(sees("⬅️ Coming back"))
        // Both home trips start ticked; un-tick "Going there", so "Coming back" is first.
        compose.onNodeWithText("➡️ Going there").performClick()
        assertTrue(sees("Save — show 1 on this widget"))
        // Add Ballston → Navy Yard just for this widget.
        compose.onNodeWithText("+ Add other stops for this widget").performClick()
        compose.onNode(androidx.compose.ui.test.hasSetTextAction()).performTextInput("Ballston")
        compose.onNodeWithText("Ballston-MU").performClick()
        compose.onNode(androidx.compose.ui.test.hasSetTextAction()).performTextInput("Navy")
        compose.onNodeWithText("Navy Yard-Ballpark").performClick()
        compose.waitForIdle()
        assertTrue(sees("Ballston-MU → Navy Yard-Ballpark")); assertTrue(sees("widget only")); assertTrue(sees("#2"))
        compose.onNodeWithText("Save — show 2 on this widget").performClick()
        assertTrue(done)
        val ids = repo.prefs.widgetTrips(42)!!
        val trips = repo.prefs.trips
        org.junit.Assert.assertEquals(listOf("default-back", trips.last().id), ids)
        assertTrue(!trips.last().onHome)
        // The widget sees exactly those, in that order; the home screen doesn't get the widget-only trip.
        val snap = repo.snapshot()
        org.junit.Assert.assertEquals(ids, com.metrocast.app.widget.Widgets.forWidget(snap, ids).trips.map { it.trip.id })
        org.junit.Assert.assertEquals(listOf("default-going", "default-back"), com.metrocast.app.widget.Widgets.forWidget(snap, null).trips.map { it.trip.id })
        // Removing the widget deletes its choices and its widget-only trip.
        com.metrocast.app.widget.Widgets.forget(ctx, intArrayOf(42))
        org.junit.Assert.assertNull(repo.prefs.widgetTrips(42))
        org.junit.Assert.assertEquals(2, repo.prefs.trips.size)
    }

    @Test fun choicesSurviveTripsBeingDeleted() {
        repo.prefs.setWidgetTrips(7, listOf("gone", "default-going"))
        val s = com.metrocast.app.widget.Widgets.forWidget(repo.snapshot(), repo.prefs.widgetTrips(7))
        org.junit.Assert.assertEquals(listOf("default-going"), s.trips.map { it.trip.id })
        repo.prefs.setWidgetTrips(7, listOf("gone"))
        // Nothing left that exists: falls back to the usual widget trips instead of a blank widget.
        org.junit.Assert.assertEquals(2, com.metrocast.app.widget.Widgets.forWidget(repo.snapshot(), repo.prefs.widgetTrips(7)).trips.size)
    }

    @Test fun configActivityCancelsWithoutAWidgetId() {
        val sc = androidx.test.core.app.ActivityScenario.launchActivityForResult(com.metrocast.app.widget.WidgetConfigActivity::class.java)
        org.junit.Assert.assertEquals(android.app.Activity.RESULT_CANCELED, sc.result.resultCode)
    }
}

/** The track work calendar in the app: download, heads-ups, the screen, the home banner. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class TrackWorkRoboTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var ctx: Context
    private lateinit var repo: Repository
    private val nm get() = ctx.getSystemService(android.app.NotificationManager::class.java)
    private val thu = java.time.LocalDate.of(2026, 11, 19)

    @Before fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        ctx.getSharedPreferences("metrocast", Context.MODE_PRIVATE).edit().clear().commit()
        ctx.filesDir.listFiles()?.forEach { it.delete() }
        org.robolectric.Shadows.shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>()).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        nm.cancelAll()
        FakeWmata.install()
        repo = Repository(ctx)
        runBlocking { repo.refresh() }
    }
    @After fun tearDown() = FakeWmata.uninstall()

    private fun posted() = org.robolectric.Shadows.shadowOf(nm).allNotifications

    @Test fun downloadsOncePerSixHoursAndKeepsGoodData() {
        val first = repo.loadTrackWork()
        assertTrue(first.size >= 10)
        assertTrue(first.single { it.kind == com.metrocast.app.data.TrackWork.Kind.WEEKEND }.changes.size == 3)
        FakeWmata.requests.clear()
        repo.loadTrackWork(); assertTrue("re-downloaded too soon", FakeWmata.seen().none { "wmata.com/ride" in it })
        // A broken answer never wipes what we had.
        FakeWmata.bodies = mapOf("alerts_advisories_li" to "<html>503</html>")
        org.junit.Assert.assertEquals(first, repo.loadTrackWork(force = true))
        FakeWmata.failing = mapOf("alerts_advisories_li" to java.io.IOException("offline"))
        runCatching { repo.loadTrackWork(force = true) }
        org.junit.Assert.assertEquals(first, repo.trackWorkCached())
        // Only public wmata.com pages, no API key or personal data.
        FakeWmata.bodies = emptyMap(); FakeWmata.failing = emptyMap(); FakeWmata.requests.clear()
        repo.loadTrackWork(force = true)
        assertTrue(FakeWmata.seen().toString(), FakeWmata.requests.all { it.startsWith("https://www.wmata.com/") && "api_key" !in it })
    }

    @Test fun headsUpThreeDaysAheadOnceForBothTrips() {
        org.junit.Assert.assertEquals(0, run { repo.loadTrackWork(); repo.checkTrackWorkAlerts(thu) })   // off by default
        repo.prefs.trackWorkAlerts = true
        // Eleven days before: nothing yet.
        org.junit.Assert.assertEquals(0, repo.checkTrackWorkAlerts(java.time.LocalDate.of(2026, 11, 10)))
        // Thursday before the weekend: one heads-up naming both trips (Silver runs only to Federal Center SW).
        org.junit.Assert.assertEquals(1, repo.checkTrackWorkAlerts(thu))
        val n = org.robolectric.Shadows.shadowOf(posted().single())
        assertTrue(n.contentTitle.toString(), n.contentTitle.toString().contains("In 2 days") && n.contentTitle.contains("Going there") && n.contentTitle.contains("Coming back"))
        assertTrue(n.contentText.toString(), n.contentText.toString().contains("Silver Line: Modified service"))
        // Not again.
        org.junit.Assert.assertEquals(0, repo.checkTrackWorkAlerts(thu))
        org.junit.Assert.assertEquals(0, repo.checkTrackWorkAlerts(thu.plusDays(1)))
        // Turned off: nothing.
        nm.cancelAll(); repo.prefs.trackWorkNotified = emptySet(); repo.prefs.trackWorkAlerts = false
        org.junit.Assert.assertEquals(0, repo.checkTrackWorkAlerts(thu)); org.junit.Assert.assertEquals(0, posted().size)
        // Winter construction: 3 days before Dec 18.
        repo.prefs.trackWorkAlerts = true
        org.junit.Assert.assertEquals(1, repo.checkTrackWorkAlerts(java.time.LocalDate.of(2026, 12, 15)))
    }

    @Test fun transferTripGetsTheGreenLineFridayWork() {
        repo.prefs.trackWorkAlerts = true
        repo.prefs.trips = listOf(repo.buildTrip("E10", "F05", com.metrocast.app.data.Trip.GOING)!!)
        repo.loadTrackWork()
        org.junit.Assert.assertEquals(1, repo.checkTrackWorkAlerts(java.time.LocalDate.of(2026, 11, 25)))
        assertTrue(org.robolectric.Shadows.shadowOf(posted().single()).contentText.toString().contains("Green Line: After 10 pm, single tracking"))
    }

    @Test fun calendarScreenShowsYourTripsFirst() {
        repo.loadTrackWork()
        compose.setContent { MetroCastTheme { com.metrocast.app.ui.TrackWorkScreen(repo, onBack = {}, today = thu) } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("YOUR TRIPS").fetchSemanticsNodes().isNotEmpty() && compose.onAllNodesWithText("Checking wmata.com", substring = true).fetchSemanticsNodes().isEmpty() }
        fun sees(t: String) = compose.onAllNodesWithText(t, substring = true).fetchSemanticsNodes().isNotEmpty()
        fun scrollTo(t: String) = compose.onNode(androidx.compose.ui.test.hasScrollAction()).performScrollToNode(androidx.compose.ui.test.hasText(t, substring = true))
        scrollTo("Modified service: All trains run from Ashburn")
        assertTrue(sees("IN 2 DAYS · WEEKEND TRACK WORK")); assertTrue(sees("On your line — expect longer waits"))
        assertTrue(sees("➡️ Going there")); assertTrue(sees("⬅️ Coming back"))
        scrollTo("ELSEWHERE ON METRO"); scrollTo("Single tracking: Eastern Market to Minnesota Av")
        scrollTo("AT YOUR STATIONS"); scrollTo("Foggy Bottom Escalator Replacement")
        // The switch turns the heads-up on (off by default).
        compose.onNode(androidx.compose.ui.test.hasScrollAction()).performScrollToIndex(0)
        assertTrue(!repo.prefs.trackWorkAlerts)
        compose.onNode(androidx.compose.ui.test.isToggleable()).performClick()
        assertTrue(repo.prefs.trackWorkAlerts)
    }

    @Test fun calendarScreenOfflineAndClearTrips() {
        // Never downloaded, and offline: says so. (With an earlier download it shows that instead.)
        java.io.File(ctx.filesDir, "trackwork.json").delete()
        FakeWmata.failing = mapOf("alerts_advisories_li" to java.io.IOException("offline"))
        repo.prefs.trips = listOf(repo.buildTrip("A15", "B11", com.metrocast.app.data.Trip.GOING)!!)
        compose.setContent { MetroCastTheme { com.metrocast.app.ui.TrackWorkScreen(repo, onBack = {}, today = thu) } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Couldn't reach wmata.com", substring = true).fetchSemanticsNodes().isNotEmpty() }
        FakeWmata.failing = emptyMap()
    }

    @Test fun clearTripSaysSo() {
        repo.prefs.trips = listOf(repo.buildTrip("A15", "B11", com.metrocast.app.data.Trip.GOING)!!)
        repo.loadTrackWork()
        compose.setContent { MetroCastTheme { com.metrocast.app.ui.TrackWorkScreen(repo, onBack = {}, today = thu) } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("No planned work on your trips", substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun homeBannerAndMenu() {
        repo.loadTrackWork()
        val b = com.metrocast.app.ui.TrackWorkBanner(repo.trackWorkImpacts(today = thu), thu)!!
        assertTrue(b, b.startsWith("🚧 In 2 days (Sat–Sun, Nov 21 – 22): track work on ➡️ Going there & ⬅️ Coming back — on your line"))
        org.junit.Assert.assertNull(com.metrocast.app.ui.TrackWorkBanner(repo.trackWorkImpacts(today = java.time.LocalDate.of(2026, 11, 1)), java.time.LocalDate.of(2026, 11, 1)))
        var opened = false
        val state = com.metrocast.app.ui.UiState(snapshot = repo.snapshot(), trackWork = repo.trackWorkImpacts(today = thu))
        compose.setContent { MetroCastTheme { com.metrocast.app.ui.HomeScreen(state, repo, onRefresh = {}, onOpenSettings = {}, onOpenTrackWork = { opened = true }) } }
        compose.onNodeWithContentDescription("Menu").performClick()
        compose.onNodeWithText("Track work calendar").performClick()
        assertTrue(opened)
    }
}

/** The home screen's live line map: any line, picked in Settings. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class HomeMapLineRoboTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var repo: Repository

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.getSharedPreferences("metrocast", Context.MODE_PRIVATE).edit().clear().commit()
        FakeWmata.install()
        repo = Repository(ctx)
        runBlocking { repo.refresh() }
    }
    @After fun tearDown() = FakeWmata.uninstall()

    private fun sees(t: String) = compose.onAllNodesWithText(t, substring = true).fetchSemanticsNodes().isNotEmpty()
    /** The page's own (vertical) list, not the sideways hourly-weather row. */
    private fun page() = compose.onAllNodes(androidx.compose.ui.test.hasScrollAction() and androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange)).onFirst()

    @Test fun pickALineInSettingsAndHomeShowsIt() {
        org.junit.Assert.assertEquals("SV", repo.prefs.homeMapLine)   // Silver until changed
        val screen = androidx.compose.runtime.mutableStateOf("settings")
        var opened: String? = null
        compose.setContent {
            MetroCastTheme {
                if (screen.value == "settings") com.metrocast.app.ui.SettingsScreen(repo, onChanged = {}, onBack = {})
                else com.metrocast.app.ui.HomeScreen(
                    com.metrocast.app.ui.UiState(snapshot = repo.snapshot(), maps = repo.lineMaps()), repo,
                    onRefresh = {}, onOpenSettings = {}, onOpenMap = { opened = repo.prefs.homeMapLine },
                )
            }
        }
        compose.onNode(androidx.compose.ui.test.hasScrollAction()).performScrollToNode(androidx.compose.ui.test.hasText("HOME SCREEN MAP LINE"))
        compose.onNode(androidx.compose.ui.test.hasScrollAction()).performScrollToNode(androidx.compose.ui.test.hasText("Green"))
        compose.onNodeWithText("Green").performClick()
        org.junit.Assert.assertEquals("GR", repo.prefs.homeMapLine)
        compose.runOnIdle { screen.value = "home" }
        page().performScrollToNode(androidx.compose.ui.test.hasText("Green Line live map"))
        assertTrue(sees("Green Line live map")); assertTrue(!sees("Silver Line live map"))
        assertTrue("directions named by line ends", sees(" to Greenbelt") && sees(" to Branch Ave"))
        compose.onNodeWithText("Green Line live map").performClick()
        org.junit.Assert.assertEquals("GR", opened)
    }

    @Test fun everyLineDrawsAndBadValuesFallBackToSilver() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.getSharedPreferences("metrocast", Context.MODE_PRIVATE).edit().putString("home_map_line", "PURPLE").commit()
        org.junit.Assert.assertEquals("SV", repo.prefs.homeMapLine)
        val line = androidx.compose.runtime.mutableStateOf("RD")
        val maps = repo.lineMaps()
        compose.setContent {
            MetroCastTheme {
                repo.prefs.homeMapLine = line.value
                com.metrocast.app.ui.HomeScreen(com.metrocast.app.ui.UiState(snapshot = repo.snapshot(), maps = maps), repo, onRefresh = {}, onOpenSettings = {}, onOpenMap = {})
            }
        }
        for (l in com.metrocast.app.data.MetroLine.entries) {
            compose.runOnIdle { line.value = l.code }
            page().performScrollToNode(androidx.compose.ui.test.hasText("${l.displayName} Line live map"))
            assertTrue(l.displayName, sees("${l.displayName} Line live map") && sees(" trains · "))
        }
    }
}

/** The restroom list checks WMATA about once a month. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RestroomUpdateRoboTest {
    private lateinit var ctx: Context

    @Before fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        ctx.getSharedPreferences("metrocast", Context.MODE_PRIVATE).edit().clear().commit()
        ctx.filesDir.listFiles()?.forEach { it.delete() }
        com.metrocast.app.data.Restrooms.useLive("", null)
        FakeWmata.install()
    }
    @After fun tearDown() { FakeWmata.uninstall(); com.metrocast.app.data.Restrooms.useLive("", null) }

    private fun fetched() = FakeWmata.seen().count { it.endsWith("/restrooms.html") }

    @Test fun monthlyCheckRetryAndOffline() {
        val repo = Repository(ctx)
        runBlocking { repo.refresh() }
        org.junit.Assert.assertEquals(1, fetched())
        assertTrue(com.metrocast.app.data.Restrooms.isLive)
        // Many refreshes later: not fetched again within the month.
        runBlocking { repeat(5) { repo.refresh() } }
        org.junit.Assert.assertEquals(1, fetched())
        // A new app start uses the saved copy without going online.
        com.metrocast.app.data.Restrooms.useLive("", null); FakeWmata.requests.clear()
        Repository(ctx)
        assertTrue(com.metrocast.app.data.Restrooms.isLive); org.junit.Assert.assertEquals(0, fetched())
        // A month on: checked again.
        java.io.File(ctx.filesDir, "restrooms.html").setLastModified(System.currentTimeMillis() - 31L * 24 * 3600_000)
        repo.prefs.restroomsTriedAt = 0
        runBlocking { repo.refresh() }
        org.junit.Assert.assertEquals(1, fetched())
    }

    @Test fun brokenPageOrOfflineKeepsTheList() {
        FakeWmata.bodies = mapOf("restrooms.html" to "<html>We moved this page</html>")
        val repo = Repository(ctx)
        runBlocking { repo.refresh() }
        assertTrue(!com.metrocast.app.data.Restrooms.isLive)
        org.junit.Assert.assertEquals(36, com.metrocast.app.data.Restrooms.all.size)   // built-in list still there
        // Not hammering WMATA after a failure: next try tomorrow.
        FakeWmata.requests.clear(); runBlocking { repo.refresh() }
        org.junit.Assert.assertEquals(0, fetched())
        FakeWmata.bodies = emptyMap(); FakeWmata.failing = mapOf("restrooms.html" to java.io.IOException("offline"))
        runCatching { repo.refreshRestrooms(force = true) }
        org.junit.Assert.assertEquals(36, com.metrocast.app.data.Restrooms.all.size)
    }
}

/** Settings → layout, theme, refresh, weather and track-work options all take effect. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class CustomizeRoboTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var ctx: Context
    private lateinit var repo: Repository

    @Before fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        ctx.getSharedPreferences("metrocast", Context.MODE_PRIVATE).edit().clear().commit()
        ctx.filesDir.listFiles()?.forEach { it.delete() }
        FakeWmata.install()
        repo = Repository(ctx)
        com.metrocast.app.ui.AppLook.load(repo.prefs)
        runBlocking { repo.refresh() }
    }
    @After fun tearDown() { FakeWmata.uninstall(); com.metrocast.app.ui.AppLook.load(com.metrocast.app.data.Prefs(ctx)) }

    private fun sees(t: String) = compose.onAllNodesWithText(t, substring = true).fetchSemanticsNodes().isNotEmpty()
    private fun page() = compose.onAllNodes(androidx.compose.ui.test.hasScrollAction() and androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange)).onFirst()

    private fun home() = compose.setContent {
        MetroCastTheme { com.metrocast.app.ui.HomeScreen(com.metrocast.app.ui.UiState(snapshot = repo.snapshot(), maps = repo.lineMaps()), repo, onRefresh = {}, onOpenSettings = {}, onOpenMap = {}) }
    }

    /** Keys of the home screen's list, top to bottom, as laid out. */
    private fun order(): List<String> = listOf("Board at", "Silver Line live map", "stations have restrooms", "Next 24 hours")

    @Test fun homeLayoutOrderAndHiding() {
        val p = repo.prefs
        val default = p.homeSections.map { it.first }
        org.junit.Assert.assertEquals(com.metrocast.app.data.HomeSection.entries.toList(), default)
        // Forecast first, restroom note hidden, map off.
        p.homeSections = listOf(com.metrocast.app.data.HomeSection.FORECAST to true) +
            p.homeSections.filter { it.first != com.metrocast.app.data.HomeSection.FORECAST }
                .map { (s, on) -> s to (on && s != com.metrocast.app.data.HomeSection.RESTROOMS && s != com.metrocast.app.data.HomeSection.MAP) }
        assertTrue(!p.showSilverMap)
        home()
        // The first thing on the page is now the forecast.
        assertTrue(sees("Next 24 hours"))
        page().performScrollToNode(androidx.compose.ui.test.hasText("Board at"))
        assertTrue(!sees("stations have restrooms")); assertTrue(!sees("Silver Line live map"))
        // Saved and read back; garbage in storage falls back to the defaults.
        org.junit.Assert.assertEquals(com.metrocast.app.data.HomeSection.FORECAST, p.homeSections.first().first)
        ctx.getSharedPreferences("metrocast", Context.MODE_PRIVATE).edit().putString("home_sections", "NOPE:1,,TRIPS:0:9,").commit()
        org.junit.Assert.assertEquals(com.metrocast.app.data.HomeSection.entries.size, p.homeSections.size)
        assertTrue(!p.homeSections.single { it.first == com.metrocast.app.data.HomeSection.TRIPS }.second)
    }

    @Test fun settingsLayoutCardMovesAndHides() {
        compose.setContent { MetroCastTheme { com.metrocast.app.ui.SettingsScreen(repo, onChanged = {}, onBack = {}) } }
        page().performScrollToNode(androidx.compose.ui.test.hasText("Home screen layout"))
        compose.onNodeWithContentDescription("Move Forecast up").performClick()
        val order = repo.prefs.homeSections.map { it.first }
        org.junit.Assert.assertEquals(com.metrocast.app.data.HomeSection.FORECAST, order[order.size - 2])
        compose.onNodeWithText("Reset layout").performClick()
        org.junit.Assert.assertEquals(com.metrocast.app.data.HomeSection.entries.toList(), repo.prefs.homeSections.map { it.first })
    }

    @Test fun themeContrastAndTextSize() {
        val probe = androidx.compose.runtime.mutableStateOf(false)
        var bg = androidx.compose.ui.graphics.Color.Unspecified; var scale = 0f
        compose.setContent {
            MetroCastTheme {
                if (!probe.value) com.metrocast.app.ui.SettingsScreen(repo, onChanged = {}, onBack = {})
                else { bg = androidx.compose.material3.MaterialTheme.colorScheme.surface; scale = androidx.compose.ui.platform.LocalDensity.current.fontScale }
            }
        }
        page().performScrollToNode(androidx.compose.ui.test.hasText("Appearance"))
        page().performScrollToNode(androidx.compose.ui.test.hasText("Larger text"))
        compose.onNodeWithText("Dark").performClick()
        org.junit.Assert.assertEquals("dark", repo.prefs.themeMode); org.junit.Assert.assertEquals("dark", com.metrocast.app.ui.AppLook.mode)
        // Tapping the words flips the switch too.
        compose.onNodeWithText("High contrast").performClick()
        compose.onNodeWithText("Larger text").performClick()
        compose.waitForIdle()
        assertTrue(repo.prefs.highContrast && repo.prefs.largeText && com.metrocast.app.ui.AppLook.largeText)
        // The theme really is dark, black-and-white, and bigger now.
        compose.runOnIdle { probe.value = true }
        compose.waitForIdle()
        org.junit.Assert.assertEquals(androidx.compose.ui.graphics.Color.Black, bg)
        assertTrue("font scale $scale", scale >= 1.19f)
    }

    @Test fun weatherOffIsNeverDownloaded() {
        repo.prefs.weatherEnabled = false
        FakeWmata.requests.clear()
        runBlocking { repo.refresh(forceWeather = true) }
        assertTrue(FakeWmata.seen().none { "open-meteo" in it })
        org.junit.Assert.assertNull(repo.snapshot().weather)
        assertTrue(!repo.needsRefresh(maxAgeMs = Long.MAX_VALUE))
        home()
        assertTrue(!sees("Loading the forecast")); assertTrue(sees("Board at") || sees("Trains"))
        page().performScrollToNode(androidx.compose.ui.test.hasText("Train data: WMATA"))
        assertTrue(!sees("Open-Meteo")); assertTrue(!sees("Next 24 hours"))
    }

    @Test fun refreshSpeedChoices() {
        compose.setContent { MetroCastTheme { com.metrocast.app.ui.SettingsScreen(repo, onChanged = {}, onBack = {}) } }
        page().performScrollToNode(androidx.compose.ui.test.hasText("Only when I pull down"))
        compose.onNodeWithText("Only when I pull down").performClick()
        org.junit.Assert.assertEquals(0, repo.prefs.refreshSeconds); assertTrue(sees("Saves the most battery"))
        compose.onNodeWithText("Every minute").performClick()
        org.junit.Assert.assertEquals(60, repo.prefs.refreshSeconds)
        ctx.getSharedPreferences("metrocast", Context.MODE_PRIVATE).edit().putInt("refresh_seconds", 7).commit()
        org.junit.Assert.assertEquals(30, repo.prefs.refreshSeconds)   // bad value → default
    }

    @Test fun trackWorkLeadTimeAndKind() {
        val p = repo.prefs
        p.trackWorkAlerts = true
        repo.loadTrackWork()
        val sat = java.time.LocalDate.of(2026, 11, 21)
        // 1 day ahead: Thursday is too early for Saturday's work.
        p.trackWorkDays = 1
        org.junit.Assert.assertEquals(0, repo.checkTrackWorkAlerts(sat.minusDays(2)))
        org.junit.Assert.assertEquals(1, repo.checkTrackWorkAlerts(sat.minusDays(1)))
        // "Only when my trip is disrupted": longer waits on Silver don't count.
        p.trackWorkNotified = emptySet(); p.trackWorkMajorOnly = true; p.trackWorkDays = 7
        org.junit.Assert.assertEquals(0, repo.checkTrackWorkAlerts(sat.minusDays(2)))
        // A trip that is disrupted (Silver doesn't reach Eastern Market) still warns.
        p.trips = listOf(repo.buildTrip("N06", "D06", com.metrocast.app.data.Trip.GOING)!!)
        org.junit.Assert.assertEquals(1, repo.checkTrackWorkAlerts(sat.minusDays(2)))
        // A week ahead catches the Winter construction early.
        p.trackWorkMajorOnly = false; p.trips = com.metrocast.app.data.Stations.DEFAULT_TRIPS
        org.junit.Assert.assertEquals(1, repo.checkTrackWorkAlerts(java.time.LocalDate.of(2026, 12, 11)))
    }

    @Test fun widgetSaveIsInTheTopBar() {
        var done = false
        compose.setContent { MetroCastTheme { com.metrocast.app.widget.WidgetConfigScreen(repo, 5, "MetroCast", onDone = { done = true }, onCancel = {}) } }
        // Two Save buttons: the top bar one is always reachable.
        val saves = compose.onAllNodesWithText("Save", substring = false).fetchSemanticsNodes()
        assertTrue(saves.isNotEmpty())
        compose.onAllNodesWithText("Save", substring = false).onFirst().performClick()
        assertTrue(done); assertNotNull(repo.prefs.widgetTrips(5))
    }
}

/** The redesigned Live trains screen: lines, the list, tapping trains, zoom buttons. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class LiveTrainsRoboTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var repo: Repository

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.getSharedPreferences("metrocast", Context.MODE_PRIVATE).edit().clear().commit()
        FakeWmata.install()
        repo = Repository(ctx)
        runBlocking { repo.refresh(); repo.refresh() }
    }
    @After fun tearDown() = FakeWmata.uninstall()

    private fun sees(t: String) = compose.onAllNodesWithText(t, substring = true).fetchSemanticsNodes().isNotEmpty()
    private fun list() = compose.onAllNodes(androidx.compose.ui.test.hasScrollAction() and androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange)).onFirst()

    @Test fun everyTrainIsListedAndCanBeFound() {
        val maps = repo.lineMaps()
        val total = maps.values.sumOf { it.trains.size + it.branchTrains.size }
        compose.setContent { MetroCastTheme { com.metrocast.app.ui.LiveTrainsScreen(maps, onBack = {}) } }
        assertTrue(sees("All lines · $total")); assertTrue(sees("Tap a train"))
        compose.onNodeWithContentDescription("Zoom in").performClick()
        assertTrue(compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("Show the whole map")).fetchSemanticsNodes().isNotEmpty())
        // One line only: the list shows just that line.
        compose.onNodeWithText("Green · ", substring = true).performClick()
        list().performScrollToNode(androidx.compose.ui.test.hasText("Green Line · ", substring = true))
        assertTrue(!sees("Red Line · "))
        // Tap a train in the list: the map card describes it.
        val g = maps.getValue("GR"); val t = g.trains.first()
        list().performScrollToNode(androidx.compose.ui.test.hasText(com.metrocast.app.ui.whereIs(g.stations, t)))
        compose.onAllNodesWithText(com.metrocast.app.ui.whereIs(g.stations, t)).onFirst().performClick()
        compose.waitForIdle()
        list().performScrollToIndex(1)
        assertTrue(sees("Green to "))
        // Back to all lines.
        list().performScrollToIndex(0)
        compose.onNodeWithText("All lines", substring = true).performClick()
        list().performScrollToNode(androidx.compose.ui.test.hasText("Red Line · ", substring = true))
    }

    @Test fun tappingTheMapPicksTheNearestTrain() {
        val maps = repo.lineMaps()
        compose.setContent { MetroCastTheme { com.metrocast.app.ui.LiveTrainsScreen(maps, onBack = {}) } }
        val canvas = compose.onNode(androidx.compose.ui.test.hasContentDescription("Map of", substring = true))
        val b = canvas.fetchSemanticsNode().size
        val g = com.metrocast.app.ui.MapGeometry(androidx.compose.ui.geometry.Size(b.width.toFloat(), b.height.toFloat()), 1f, androidx.compose.ui.geometry.Offset.Zero,
            androidx.compose.ui.unit.Density(ApplicationProvider.getApplicationContext<Context>().resources.displayMetrics.density))
        // A Silver train out west, with no other train near it.
        val all = com.metrocast.app.ui.placeTrains(g, maps, maps.keys)
        val target = all.filter { it.line == "SV" }.maxBy { t -> all.filter { it !== t }.minOf { kotlin.math.hypot(it.at.x - t.at.x, it.at.y - t.at.y) } }
        canvas.performTouchInput { click(target.at) }
        compose.waitForIdle()
        val texts = compose.onAllNodes(androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.Text)).fetchSemanticsNodes().map { it.config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString() }
        assertTrue("size=$b at=${target.at} line=${target.line} texts=${texts.take(30)}", sees("Silver to "))
        // The card's ✕ clears it.
        compose.onNodeWithText("✕").performClick()
        compose.waitForIdle()
        assertTrue(!sees("Silver to "))
    }

    @Test fun wordsForEveryTrainOnEveryLine() {
        var n = 0
        for ((_, m) in repo.lineMaps()) for ((st, t) in m.trains.map { m.stations to it } + m.branchTrains.map { m.branchStations to it }) {
            val w = com.metrocast.app.ui.whereIs(st, t); val to = com.metrocast.app.ui.towardName(st, t)
            assertTrue(w, w.startsWith("At ") || w.startsWith("Next stop: "))
            assertTrue("blank destination", to.isNotBlank()); n++
        }
        assertTrue(n > 40)
        // Edge cases: first and last station, empty lists.
        val st = listOf(com.metrocast.app.data.Station("A", "One"), com.metrocast.app.data.Station("B", "Two"))
        org.junit.Assert.assertEquals("At Two", com.metrocast.app.ui.whereIs(st, com.metrocast.app.data.MapTrain("x", 1f, true, "", 6)))
        org.junit.Assert.assertEquals("Next stop: One", com.metrocast.app.ui.whereIs(st, com.metrocast.app.data.MapTrain("x", 0.5f, false, "", 6)))
        org.junit.Assert.assertEquals("Next stop: Two", com.metrocast.app.ui.whereIs(st, com.metrocast.app.data.MapTrain("x", 0.5f, true, "", 6)))
        org.junit.Assert.assertEquals("", com.metrocast.app.ui.whereIs(emptyList(), com.metrocast.app.data.MapTrain("x", 0.5f, true, "", 6)))
        org.junit.Assert.assertEquals("Two", com.metrocast.app.ui.towardName(st, com.metrocast.app.data.MapTrain("x", 0.5f, true, "", 6)))
    }
}

/** Door sides on the planner and the live trip. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class DoorSideRoboTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var repo: Repository

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.getSharedPreferences("metrocast", Context.MODE_PRIVATE).edit().clear().commit()
        FakeWmata.install()
        repo = Repository(ctx)
        runBlocking { repo.refresh(); repo.setPlan("C02", "F05"); repo.refresh() }
    }
    @After fun tearDown() = FakeWmata.uninstall()

    private fun sees(t: String) = compose.onAllNodesWithText(t, substring = true).fetchSemanticsNodes().isNotEmpty()

    @Test fun plannerSaysWhichSide() {
        compose.setContent { MetroCastTheme { com.metrocast.app.ui.PlannerScreen(repo.snapshot().plan, repo, onChange = { _, _ -> }, onBack = {}, onStartLive = {}) } }
        val page = compose.onAllNodes(androidx.compose.ui.test.hasScrollAction() and androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange)).onFirst()
        // McPherson Sq → L'Enfant (center platform for Silver/Orange/Blue) → Navy Yard (center for Green).
        page.performScrollToNode(androidx.compose.ui.test.hasText("doors open on your left", substring = true))
        page.performScrollToNode(androidx.compose.ui.test.hasText("🚪 Doors open on your left"))
    }

    @Test fun liveTripRidingCardSaysWhichSide() {
        repo.startLiveTrip("C02", "F05"); repo.boardLiveTrain()
        val live = repo.liveTrip()!!
        compose.setContent { MetroCastTheme { com.metrocast.app.ui.LiveTripScreen(live, 0L, {}, {}, {}, {}, useLocation = false, repo = repo) } }
        val texts = compose.onAllNodes(androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.Text)).fetchSemanticsNodes().map { it.config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString() }
        assertTrue("onBoard=${live.onBoard} texts=$texts", sees("🚪 Doors open on your left at L'Enfant Plaza"))
        assertTrue("texts=$texts", sees("doors open on your left."))
        // The Green Line level at L'Enfant Plaza has side platforms: no "center platform" tip there.
        assertTrue("texts=$texts", !sees("It's a center platform"))
    }
}

/** The first-run walkthrough. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class OnboardingRoboTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var ctx: Context
    private lateinit var repo: Repository

    @Before fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        ctx.getSharedPreferences("metrocast", Context.MODE_PRIVATE).edit().clear().commit()
        ctx.filesDir.listFiles()?.forEach { it.delete() }
        FakeWmata.install()
        repo = Repository(ctx)
    }
    @After fun tearDown() = FakeWmata.uninstall()

    private fun sees(t: String) = compose.onAllNodesWithText(t, substring = true).fetchSemanticsNodes().isNotEmpty()
    private fun screen() = compose.onAllNodes(androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.Text)).fetchSemanticsNodes().map { it.config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString("|") }
    private fun tap(t: String) { compose.waitUntil(10_000) { sees(t) }; compose.onAllNodesWithText(t, substring = true).onFirst().performClick(); compose.waitForIdle() }
    private fun pick(q: String, name: String) {
        compose.waitUntil(10_000) { compose.onAllNodes(androidx.compose.ui.test.hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(androidx.compose.ui.test.hasSetTextAction()).performTextInput(q)
        compose.waitUntil(10_000) { sees(name) }
        compose.onAllNodesWithText(name).onFirst().performClick(); compose.waitForIdle()
    }

    @Test fun freshInstallWalksThroughEverything() {
        assertTrue(repo.needsOnboarding())
        var done = false
        compose.setContent { MetroCastTheme { com.metrocast.app.ui.OnboardingScreen(repo, onDone = { done = true }) } }
        // Privacy first.
        assertTrue(sees("Welcome to MetroCast")); assertTrue(sees("Your data stays on this phone")); assertTrue(sees("never uploaded or shared"))
        tap("Get started")
        // Usual trip: Ballston → Navy Yard (a transfer), before anything has been downloaded.
        assertTrue(sees("Your usual trip"))
        tap("I usually get on at".uppercase()); pick("Ballston", "Ballston-MU")
        tap("I usually get off at".uppercase()); pick("Navy", "Navy Yard-Ballpark")
        tap("Next")
        compose.waitUntil(20_000) { sees("The trip back?") }
        assertTrue(sees("Ballston-MU → Navy Yard-Ballpark")); assertTrue(sees("Navy Yard-Ballpark → Ballston-MU")); assertTrue(sees("🔁 Change at"))
        tap("Next")
        // Weather by ZIP code.
        compose.waitUntil(10_000) { sees("Weather where you are") }
        compose.onNode(androidx.compose.ui.test.hasSetTextAction()).performTextInput("22201")
        tap("Search")
        tap("Virginia, United States")   // the search result (the app's default is already Arlington, VA)
        assertTrue("${screen()}", sees("Weather for: Arlington, Virginia"))
        tap("Next")
        // Location: no thanks. Notifications: not now.
        assertTrue(sees("Notice when you board?")); assertTrue(sees("never saved or sent anywhere"))
        tap("No thanks")
        assertTrue(sees("Notifications for live trips")); assertTrue(sees("Settings → Notifications"))
        tap("Not now")
        // Summary.
        assertTrue(sees("You're all set")); assertTrue(sees("Weather for Arlington, Virginia")); assertTrue(sees("Notifications are off"))
        tap("Start using MetroCast")
        assertTrue(done); assertTrue(!repo.needsOnboarding())
        val trips = repo.prefs.trips
        org.junit.Assert.assertEquals(listOf("Going there", "Coming back"), trips.map { it.label })
        org.junit.Assert.assertEquals("F05", trips[0].destCode); org.junit.Assert.assertEquals("F05", trips[1].stationCode.let { com.metrocast.app.data.StationDirectory.find(it)!!.code })
        assertTrue(repo.prefs.locationName.startsWith("Arlington"))
        org.junit.Assert.assertEquals(false, repo.prefs.liveUseLocation)
        assertTrue(!repo.prefs.alertNotifications && !repo.prefs.trackWorkAlerts && !repo.prefs.bikeElevatorAlerts && !repo.prefs.specialNewsAlerts)
    }

    @Test fun oneWayNoWeatherAndAllow() {
        compose.setContent { MetroCastTheme { com.metrocast.app.ui.OnboardingScreen(repo, onDone = {}) } }
        tap("Get started")
        tap("I usually get on at".uppercase()); pick("Wiehle", "Wiehle-Reston East")
        tap("I usually get off at".uppercase()); pick("McPherson", "McPherson Square")
        tap("Next")
        compose.waitUntil(20_000) { sees("The trip back?") }
        tap("⬅️ Coming back")   // turn the way back off
        tap("Next")
        compose.waitUntil(10_000) { sees("Weather where you are") }
        tap("trains only")
        assertTrue(!repo.prefs.weatherEnabled)
        tap("Next"); tap("No thanks")
        // As if the person already tapped "Allow" on Android's own prompt.
        org.robolectric.Shadows.shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>()).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        tap("Allow")
        assertTrue("${screen()}", sees("You're all set") && sees("No weather."))
        org.junit.Assert.assertEquals(1, repo.prefs.trips.size)
        // "Allow" is for live trips only: everything else stays off until turned on in Settings.
        assertTrue(!repo.prefs.alertNotifications && !repo.prefs.trackWorkAlerts)
    }

    @Test fun skipKeepsTheDefaults() {
        var done = false
        compose.setContent { MetroCastTheme { com.metrocast.app.ui.OnboardingScreen(repo, onDone = { done = true }) } }
        tap("Skip setup")
        assertTrue(done); assertTrue(repo.prefs.onboarded)
        org.junit.Assert.assertEquals(listOf("N06", "C04"), repo.prefs.trips.map { it.stationCode })
    }
}

/** Train speed on the live trip, the home screen, and its Settings. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class TrainSpeedRoboTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var repo: Repository

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.getSharedPreferences("metrocast", Context.MODE_PRIVATE).edit().clear().commit()
        FakeWmata.install()
        repo = Repository(ctx)
        runBlocking { repo.refresh() }
        repo.startLiveTrip("C02", "F05"); repo.boardLiveTrain()
    }
    @After fun tearDown() { repo.endLiveTrip(); FakeWmata.uninstall() }

    private fun sees(t: String) = compose.onAllNodesWithText(t, substring = true).fetchSemanticsNodes().isNotEmpty()

    @Test fun gpsSpeedOnTheTripAndHome() {
        repo.onLiveLocation(38.9, -77.03, 15f)            // 15 m/s
        org.junit.Assert.assertEquals(33.5, repo.liveSpeed()!!.mph, 0.5)
        val live = repo.liveTrip()!!
        val mode = androidx.compose.runtime.mutableStateOf("trip")
        compose.setContent {
            MetroCastTheme {
                if (mode.value == "trip") com.metrocast.app.ui.LiveTripScreen(live, 0L, {}, {}, {}, {}, useLocation = false, repo = repo)
                else com.metrocast.app.ui.HomeScreen(com.metrocast.app.ui.UiState(snapshot = repo.snapshot(), live = live), repo, onRefresh = {}, onOpenSettings = {})
            }
        }
        assertTrue(sees("🚄 33 mph · GPS"))
        compose.runOnIdle { mode.value = "home" }
        compose.waitForIdle()
        assertTrue(sees("YOUR TRAIN")); assertTrue(sees("33 mph")); assertTrue(sees("Riding to L'Enfant Plaza"))
        // Hidden from the home screen (Settings → Train speed → Also show it on the home screen).
        repo.prefs.homeSections = repo.prefs.homeSections.map { (s, on) -> s to (on && s != com.metrocast.app.data.HomeSection.SPEED) }
        compose.runOnIdle { mode.value = "trip" }; compose.waitForIdle()
        compose.runOnIdle { mode.value = "home" }; compose.waitForIdle()
        assertTrue(!sees("YOUR TRAIN"))
    }

    @Test fun measuringThenOffAndForgotten() {
        assertTrue(repo.liveSpeed() == null)     // no GPS, no positions over time yet
        val live = repo.liveTrip()!!
        compose.setContent { MetroCastTheme { com.metrocast.app.ui.LiveTripScreen(live, 0L, {}, {}, {}, {}, useLocation = false, repo = repo) } }
        assertTrue(sees("Measuring your speed"))
        // Off in Settings: no speed at all.
        repo.prefs.showTrainSpeed = false
        repo.onLiveLocation(38.9, -77.03, 15f)
        assertTrue(repo.liveSpeed() == null)
        // A trip's readings never outlive it.
        repo.prefs.showTrainSpeed = true
        assertTrue(repo.liveSpeed() != null)
        repo.endLiveTrip()
        assertTrue(repo.liveSpeed() == null)
    }

    @Test fun settingsCard() {
        compose.setContent { MetroCastTheme { com.metrocast.app.ui.SettingsScreen(repo, onChanged = {}, onBack = {}) } }
        val page = compose.onAllNodes(androidx.compose.ui.test.hasScrollAction() and androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange)).onFirst()
        page.performScrollToNode(androidx.compose.ui.test.hasText("Also show it on the home screen"))
        compose.onNodeWithText("Also show it on the home screen").performClick()
        assertTrue(!repo.prefs.homeSections.single { it.first == com.metrocast.app.data.HomeSection.SPEED }.second)
        compose.onNodeWithText("Show train speed on live trips").performClick()
        assertTrue(!repo.prefs.showTrainSpeed); assertTrue(!sees("Also show it on the home screen"))
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class ScenicRoboTest {
    @get:Rule val compose = createComposeRule()

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.getSharedPreferences("metrocast", Context.MODE_PRIVATE).edit().clear().commit()
        FakeWmata.install()
        runBlocking { Repository(ctx).refresh() }
    }
    @After fun tearDown() = FakeWmata.uninstall()

    @Test fun listAndPlan() {
        var planned: Pair<String?, String?>? = null
        compose.setContent { MetroCastTheme { com.metrocast.app.ui.ScenicScreen(onBack = {}, onPlan = { f, t -> planned = f to t }) } }
        fun sees(t: String) = compose.onAllNodesWithText(t, substring = true).fetchSemanticsNodes().isNotEmpty()
        assertTrue(sees("Over the Potomac")); assertTrue(sees("Pentagon ↔ L'Enfant Plaza"))
        compose.onAllNodesWithText("Plan this ride").onFirst().performClick()
        assertTrue(planned == ("C07" to "F03"))
        val list = compose.onAllNodes(androidx.compose.ui.test.hasScrollAction()).onFirst()
        list.performScrollToNode(androidx.compose.ui.test.hasText("Forest Glen", substring = true))
        list.performScrollToNode(androidx.compose.ui.test.hasText("Arlington Cemetery", substring = true))
    }
}

/** Bikes screen, Metro Police screen, and their menu entries. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class BikesPoliceRoboTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var ctx: Context
    private lateinit var repo: Repository

    @Before fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        ctx.getSharedPreferences("metrocast", Context.MODE_PRIVATE).edit().clear().commit()
        ctx.filesDir.listFiles()?.forEach { it.delete() }
        com.metrocast.app.data.Bikes.useLive("", null)
        FakeWmata.install()
        repo = Repository(ctx)
        runBlocking { repo.refresh() }
    }
    @After fun tearDown() { FakeWmata.uninstall(); com.metrocast.app.data.Bikes.useLive("", null) }

    private fun sees(t: String) = compose.onAllNodesWithText(t, substring = true).fetchSemanticsNodes().isNotEmpty()
    private fun list() = compose.onAllNodes(androidx.compose.ui.test.hasScrollAction() and androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange)).onFirst()

    @Test fun myStationsParkingAndElevators() {
        var planned: Pair<String?, String?>? = null
        compose.setContent { MetroCastTheme { com.metrocast.app.ui.BikesScreen(repo, onBack = {}, onPlan = { f, t -> planned = f to t }) } }
        compose.waitUntil(10_000) { sees("Elevators working") || sees("Elevator out") }
        // Wiehle: Fairfax County's room, center platform, doors on the left.
        assertTrue(sees("Wiehle-Reston East")); assertTrue(sees("Bike & Ride (Fairfax County) · opens with a key fob from Fairfax County"))
        assertTrue(sees("Silver: center platform — one elevator for both directions, doors open on your left"))
        // Bike rooms tab: Metro's rooms and how to get in, Fairfax's with its link, Maryland note.
        compose.onNodeWithText("🔒 Bike rooms").performClick()
        assertTrue(sees("BIKE & RIDE (METRO) · 5")); assertTrue(sees("bike@wmata.com"))
        list().performScrollToNode(androidx.compose.ui.test.hasText("How to get access — Fairfax County", substring = true))
        list().performScrollToIndex(0)
        assertTrue(sees("MARYLAND — NOT RUN BY METRO"))
        // Elevator outages tab: Grosvenor's garage elevator is out in the recorded data.
        compose.onNodeWithText("Elevator outages", substring = true).performClick()
        list().performScrollToNode(androidx.compose.ui.test.hasText("Garage elevator", substring = true))
        // Rules (back to the top, where the tabs are).
        list().performScrollToIndex(0)
        compose.onNodeWithText("📋 Rules").performClick()
        assertTrue(sees("first and last cars")); assertTrue(sees("4 bikes per car"))
    }

    @Test fun bikeListChecksWmataMonthly() {
        org.junit.Assert.assertTrue(com.metrocast.app.data.Bikes.isLive)          // the first refresh read WMATA's page
        val fetched = { FakeWmata.seen().count { it.endsWith("/bike-and-ride.html") } }
        FakeWmata.requests.clear(); runBlocking { repeat(3) { repo.refresh() } }
        org.junit.Assert.assertEquals(0, fetched())
        java.io.File(ctx.filesDir, "bike_and_ride.html").setLastModified(System.currentTimeMillis() - 31L * 24 * 3600_000)
        repo.prefs.bikesTriedAt = 0
        runBlocking { repo.refresh() }
        org.junit.Assert.assertEquals(1, fetched())
        // A redesigned page keeps the list.
        FakeWmata.bodies = mapOf("bike-and-ride.html" to "<html>moved</html>")
        com.metrocast.app.data.Bikes.useLive("", null)
        org.junit.Assert.assertTrue(!repo.refreshBikes(force = true))
        org.junit.Assert.assertEquals(8, com.metrocast.app.data.Bikes.all.size)
    }

    @Test fun policeScreenAndSettings() {
        val screen = androidx.compose.runtime.mutableStateOf("police")
        compose.setContent {
            MetroCastTheme {
                if (screen.value == "police") com.metrocast.app.ui.PoliceScreen(onBack = {})
                else com.metrocast.app.ui.SettingsScreen(repo, onChanged = {}, onBack = {})
            }
        }
        assertTrue(sees("Emergency: call 911")); assertTrue(sees("Call Metro Transit Police · 202-962-2121")); assertTrue(sees("Text MYMTPD (696873)"))
        // The buttons hand off to the phone's dialer / messages (no crash without them).
        compose.onNodeWithText("Text MYMTPD (696873)", substring = true).performClick()
        compose.onNodeWithText("Call Metro Transit Police · 202-962-2121", substring = true).performClick()
        compose.runOnIdle { screen.value = "settings" }; compose.waitForIdle()
        list().performScrollToNode(androidx.compose.ui.test.hasText("Safety & help"))
        list().performScrollToNode(androidx.compose.ui.test.hasText("Text MYMTPD (696873)", substring = true))
    }

    @Test fun menuHasBikesAndPolice() {
        var bikes = false; var police = false
        compose.setContent { MetroCastTheme { com.metrocast.app.ui.HomeScreen(com.metrocast.app.ui.UiState(snapshot = repo.snapshot()), repo, onRefresh = {}, onOpenSettings = {}, onOpenBikes = { bikes = true }, onOpenPolice = { police = true }) } }
        compose.onNodeWithContentDescription("Menu").performClick(); compose.onNodeWithText("Bikes").performClick()
        compose.onNodeWithContentDescription("Menu").performClick(); compose.onNodeWithText("Metro Police").performClick()
        assertTrue(bikes && police)
    }
}

/** US units by default; metric when chosen — weather requests and train speed follow it. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class UnitsRoboTest {
    @get:Rule val compose = createComposeRule()

    @Test fun usByDefaultMetricOnRequest() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.getSharedPreferences("metrocast", Context.MODE_PRIVATE).edit().clear().commit()
        FakeWmata.install()
        val repo = Repository(ctx)
        assertTrue(repo.prefs.useFahrenheit && !repo.prefs.metric)
        compose.setContent { MetroCastTheme { com.metrocast.app.ui.SettingsScreen(repo, onChanged = {}, onBack = {}) } }
        val page = compose.onAllNodes(androidx.compose.ui.test.hasScrollAction() and androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange)).onFirst()
        page.performScrollToNode(androidx.compose.ui.test.hasText("°C, km/h, meters and kilometers"))
        assertTrue(compose.onAllNodesWithText("For the weather, train speed and walking distances").fetchSemanticsNodes().isNotEmpty())
        compose.onNodeWithText("Metric").performClick()
        assertTrue(repo.prefs.metric)
        compose.onNodeWithText("US").performClick()
        assertTrue(!repo.prefs.metric)
        compose.onNodeWithText("Metric").performClick()
        // The forecast is fetched in metric now; train speed reads in km/h.
        FakeWmata.requests.clear(); runBlocking { repo.refresh(forceWeather = true) }
        assertTrue(FakeWmata.seen().filter { "open-meteo" in it && "forecast" in it }.none { "fahrenheit" in it })
        org.junit.Assert.assertEquals("48 km/h", com.metrocast.app.data.TrainSpeed.Reading(30.0, false).text(repo.prefs.metric))
        FakeWmata.uninstall()
    }
}

/** Bike mode, elevator outages, Capital Bikeshare on/off, special trains, speed card, layout, SmarTrip. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class BikeModeEtcRoboTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var ctx: Context
    private lateinit var repo: Repository
    private val nm get() = ctx.getSystemService(android.app.NotificationManager::class.java)

    @Before fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        ctx.getSharedPreferences("metrocast", Context.MODE_PRIVATE).edit().clear().commit()
        ctx.filesDir.listFiles()?.forEach { it.delete() }
        org.robolectric.Shadows.shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>()).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        nm.cancelAll()
        FakeWmata.install()
        repo = Repository(ctx)
    }
    @After fun tearDown() = FakeWmata.uninstall()
    private fun sees(t: String) = compose.onAllNodesWithText(t, substring = true).fetchSemanticsNodes().isNotEmpty()
    private fun list() = compose.onAllNodes(androidx.compose.ui.test.hasScrollAction() and androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange)).onFirst()

    /** A trip from Grosvenor-Strathmore, whose garage elevator is out in the recorded data. */
    private fun grosvenorTrip() { repo.prefs.trips = listOf(repo.buildTrip("A11", "A01", com.metrocast.app.data.Trip.GOING)!!) }

    @Test fun elevatorOutagesAtYourStationsAndAlertsOnce() {
        runBlocking { repo.refresh() }
        grosvenorTrip()
        org.junit.Assert.assertEquals(0, repo.checkElevatorAlerts())           // off by default: nothing
        repo.prefs.bikeMode = true; repo.prefs.bikeElevatorAlerts = true
        runBlocking { repo.refresh() }                                            // fetches elevator status, notifies
        val mine = repo.myElevatorOutages()
        assertTrue(mine.any { it.stationCode == "A11" && it.where == "Garage elevator" })
        val first = org.robolectric.Shadows.shadowOf(nm).allNotifications.size
        assertTrue(first >= 1)
        org.junit.Assert.assertEquals(0, repo.checkElevatorAlerts())            // same outage: not again
        // Home section shows it; hidden from the layout, it's gone.
        val state = com.metrocast.app.ui.UiState(snapshot = repo.snapshot(), elevators = mine)
        val layoutOn = androidx.compose.runtime.mutableStateOf(true)
        compose.setContent { MetroCastTheme {
            if (layoutOn.value) com.metrocast.app.ui.HomeScreen(state, repo, onRefresh = {}, onOpenSettings = {}) else androidx.compose.material3.Text("off")
        } }
        assertTrue(sees("ELEVATOR OUT AT YOUR STATIONS")); assertTrue(sees("Grosvenor-Strathmore: Garage elevator"))
    }

    @Test fun bikesScreenMyTripsAndBikeshareSwitch() {
        runBlocking { repo.refresh() }
        grosvenorTrip()
        compose.setContent { MetroCastTheme { com.metrocast.app.ui.BikesScreen(repo, onBack = {}) } }
        compose.waitUntil(15_000) { sees("Grosvenor-Strathmore") && (sees("Elevator out") || sees("Elevators working")) }
        assertTrue(sees("Elevator out: Garage elevator"))
        compose.waitUntil(15_000) { sees("🚲 ") && !sees("Checking Capital Bikeshare") }
        // Real dock lines ("🚲 <dock> (<distance>): … open docks"), besides the switch's own description.
        fun dockLines() = compose.onAllNodesWithText("open dock", substring = true).fetchSemanticsNodes().size
        assertTrue(dockLines() >= 2)
        assertTrue(sees("Red: center platform"))
        // Bikeshare off: no more requests to it.
        compose.onNodeWithText("Show Capital Bikeshare").performClick()
        compose.waitForIdle()
        assertTrue(!repo.prefs.bikeshareOn); org.junit.Assert.assertEquals(1, dockLines())
        FakeWmata.requests.clear()
        runBlocking { org.junit.Assert.assertTrue(repo.loadBikeshare(repo.myStations()).isEmpty()) }
        assertTrue(FakeWmata.seen().none { "lyft" in it || "capitalbikeshare" in it })
        // Bike mode switch.
        compose.onNodeWithText("I ride with a bike (bike mode)").performClick()
        assertTrue(repo.prefs.bikeMode); assertTrue(sees("Tell me when an elevator on my trips goes out"))
        // Outages tab: yours first.
        compose.onNodeWithText("Elevator outages", substring = true).performClick()
        assertTrue(sees("AT YOUR STATIONS · 1")); assertTrue(sees("ELSEWHERE ON METRO"))
    }

    @Test fun specialTrainsMonthlyAndScreen() {
        val items = repo.loadSpecialNews()
        assertTrue(items.any { it.title.contains("Marine Corps Marathon") })
        FakeWmata.requests.clear(); repo.loadSpecialNews()
        assertTrue("re-fetched within the month", FakeWmata.seen().none { "news_listing" in it })
        repo.loadSpecialNews(force = true)
        assertTrue(FakeWmata.seen().any { "news_listing" in it })
        // Notifications: off by default; on → nothing for already-known news.
        org.junit.Assert.assertEquals(0, repo.checkSpecialNews())
        repo.prefs.specialNewsAlerts = true
        org.junit.Assert.assertEquals(0, repo.checkSpecialNews())
        compose.setContent { MetroCastTheme { com.metrocast.app.ui.SpecialScreen(repo, onBack = {}) } }
        compose.waitUntil(10_000) { sees("Marine Corps Marathon") }
        assertTrue(sees("EVENT SERVICE")); assertTrue(sees("Check now")); assertTrue(sees("can't be shown on the map"))
    }

    @Test fun speedCardBeforeBoardingAndLayoutPlacement() {
        runBlocking { repo.refresh() }
        repo.startLiveTrip("C02", "F05")
        val live = repo.liveTrip()!!
        compose.setContent { MetroCastTheme { com.metrocast.app.ui.HomeScreen(com.metrocast.app.ui.UiState(snapshot = repo.snapshot(), live = live), repo, onRefresh = {}, onOpenSettings = {}) } }
        assertTrue(sees("YOUR TRAIN")); assertTrue(sees("Shows once you're on the train"))
        // An older saved layout without Train speed / Elevator outages: they go in their usual place, not the bottom.
        ctx.getSharedPreferences("metrocast", Context.MODE_PRIVATE).edit().putString("home_sections", "WEATHER:1,TRACKWORK:1,ALERTS:1,TRIPS:1,MAP:1,RESTROOMS:1,FORECAST:1").commit()
        val order = repo.prefs.homeSections.map { it.first }
        assertTrue(order.indexOf(com.metrocast.app.data.HomeSection.SPEED) < order.indexOf(com.metrocast.app.data.HomeSection.TRIPS))
        org.junit.Assert.assertEquals(order.indexOf(com.metrocast.app.data.HomeSection.ALERTS) + 1, order.indexOf(com.metrocast.app.data.HomeSection.ELEVATORS))
        assertTrue(order.last() == com.metrocast.app.data.HomeSection.FORECAST)
    }

    @Test fun smarTripScreenIsGone() {
        compose.setContent { MetroCastTheme { com.metrocast.app.ui.HomeScreen(com.metrocast.app.ui.UiState(snapshot = repo.snapshot()), repo, onRefresh = {}, onOpenSettings = {}) } }
        compose.onNodeWithContentDescription("Menu").performClick()
        assertTrue(!sees("SmarTrip fares")); assertTrue(sees("Trip planner"))
        // An old saved balance is forgotten.
        ctx.getSharedPreferences("metrocast", Context.MODE_PRIVATE).edit().putString("card_balance", "12.50").commit()
        com.metrocast.app.notify.Background.sync(ctx)
        assertTrue(!ctx.getSharedPreferences("metrocast", Context.MODE_PRIVATE).contains("card_balance"))
    }
}

/** Notifications (live trips only by default), quiet hours, clock, menu, planner start, trip cards. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class MoreSettingsRoboTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var ctx: Context
    private lateinit var repo: Repository
    private val nm get() = ctx.getSystemService(android.app.NotificationManager::class.java)

    @Before fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        ctx.getSharedPreferences("metrocast", Context.MODE_PRIVATE).edit().clear().commit()
        ctx.filesDir.listFiles()?.forEach { it.delete() }
        org.robolectric.Shadows.shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>()).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        nm.cancelAll()
        FakeWmata.install()
        repo = Repository(ctx)
        com.metrocast.app.ui.AppLook.load(repo.prefs)
    }
    @After fun tearDown() { FakeWmata.uninstall(); com.metrocast.app.ui.Format.use24h = false }
    private fun sees(t: String) = compose.onAllNodesWithText(t, substring = true).fetchSemanticsNodes().isNotEmpty()
    private fun page() = compose.onAllNodes(androidx.compose.ui.test.hasScrollAction() and androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange)).onFirst()

    @Test fun onlyLiveTripNotificationsByDefault() {
        val p = repo.prefs
        assertTrue(!p.trackWorkAlerts && !p.alertNotifications && !p.bikeElevatorAlerts && !p.specialNewsAlerts)
        runBlocking { repo.refresh() }                 // recorded data has a Silver alert and work on your trips
        repo.loadTrackWork(); repo.checkTrackWorkAlerts(java.time.LocalDate.of(2026, 11, 19))
        org.junit.Assert.assertEquals(0, org.robolectric.Shadows.shadowOf(nm).allNotifications.size)
        // Older versions' setup had switched some on by itself: reset once, then your choices stick.
        ctx.getSharedPreferences("metrocast", Context.MODE_PRIVATE).edit().clear().putBoolean("trackwork_alerts", true).putBoolean("alert_notifs", true).commit()
        val r2 = Repository(ctx)
        assertTrue(!r2.prefs.trackWorkAlerts && !r2.prefs.alertNotifications)
        r2.prefs.trackWorkAlerts = true
        assertTrue(Repository(ctx).prefs.trackWorkAlerts)
    }

    @Test fun notificationsCardTurnsThingsOn() {
        compose.setContent { MetroCastTheme { com.metrocast.app.ui.SettingsScreen(repo, onChanged = {}, onBack = {}) } }
        page().performScrollToNode(androidx.compose.ui.test.hasText("Live trips — always on", substring = true))
        page().performScrollToNode(androidx.compose.ui.test.hasText("Special trains & events"))
        for ((t, check) in listOf<Pair<String, () -> Boolean>>("Track work on my trips" to { repo.prefs.trackWorkAlerts },
            "Delays and alerts on my lines" to { repo.prefs.alertNotifications }, "Elevator out at my stations" to { repo.prefs.bikeElevatorAlerts },
            "Special trains & events" to { repo.prefs.specialNewsAlerts })) {
            page().performScrollToNode(androidx.compose.ui.test.hasText(t))
            compose.onNodeWithText(t).performClick()
            assertTrue("$t on", check())
        }
        page().performScrollToNode(androidx.compose.ui.test.hasText("Privacy & data"))
    }

    @Test fun quietHoursHoldBackAllButLiveTrips() {
        val p = repo.prefs
        p.quietOn = true; p.quietStart = 22 * 60; p.quietEnd = 7 * 60
        fun at(d: Int, h: Int, m: Int = 0) = java.time.LocalDateTime.of(2026, 11, d, h, m)
        assertTrue(p.inQuietHours(at(18, 23))); assertTrue(p.inQuietHours(at(18, 2))); assertTrue(!p.inQuietHours(at(18, 7)))
        assertTrue(!p.inQuietHours(at(18, 12))); assertTrue(!p.inQuietHours(at(21, 12)))
        p.quietWeekends = true; assertTrue(p.inQuietHours(at(21, 12)))   // Saturday
        p.quietStart = 9 * 60; p.quietEnd = 17 * 60; p.quietWeekends = false
        assertTrue(p.inQuietHours(at(18, 9))); assertTrue(!p.inQuietHours(at(18, 17)))
        p.quietOn = false; assertTrue(!p.inQuietHours(at(18, 12)))
        // Right now inside quiet hours (all day): alerts are held back, the live-trip heads-up isn't.
        p.quietOn = true; p.quietStart = 0; p.quietEnd = 23 * 60 + 59
        com.metrocast.app.notify.Notifier.alert(ctx, 1, "Alert", "x")
        com.metrocast.app.notify.Notifier.trackWork(ctx, 2, "Work", "x")
        org.junit.Assert.assertEquals(0, org.robolectric.Shadows.shadowOf(nm).allNotifications.size)
        com.metrocast.app.notify.Notifier.trip(ctx, "Transfer in 2 stops", "Run!")
        org.junit.Assert.assertEquals(1, org.robolectric.Shadows.shadowOf(nm).allNotifications.size)
    }

    @Test fun clock24AndMenuAndPlannerStartAndTripCards() {
        // Clock.
        com.metrocast.app.ui.Format.use24h = true
        org.junit.Assert.assertEquals("13:45", com.metrocast.app.ui.Format.time(java.time.LocalTime.of(13, 45)))
        com.metrocast.app.ui.Format.use24h = false
        assertTrue(com.metrocast.app.ui.Format.time(java.time.LocalTime.of(13, 45)).startsWith("1:45"))
        // Planner start: your Going there station, or one you pick.
        repo.prefs.trips = listOf(repo.buildTrip("K05", "C04", com.metrocast.app.data.Trip.GOING) ?: run { runBlocking { repo.refresh() }; repo.buildTrip("K05", "C04", com.metrocast.app.data.Trip.GOING)!! })
        org.junit.Assert.assertEquals("K05", repo.prefs.planFrom)
        repo.prefs.plannerStart = "A01"; org.junit.Assert.assertEquals("A01", repo.prefs.planFrom)
        repo.endLiveTrip(); org.junit.Assert.assertEquals("A01", repo.prefs.planFrom)   // after a trip, back to your start
        // Menu: hide Scenic, move Bikes to the top.
        val items = repo.prefs.menuItems.toMutableList()
        val bikes = items.first { it.first == com.metrocast.app.data.MenuItem.BIKES }
        items.remove(bikes); items.add(0, bikes)
        repo.prefs.menuItems = items.map { (m, on) -> m to (on && m != com.metrocast.app.data.MenuItem.SCENIC) }
        // Trip cards: no restroom note, no ride facts, 1 train.
        repo.prefs.cardShowRestroom = false; repo.prefs.cardShowFacts = false; repo.prefs.cardTrains = 1
        runBlocking { repo.refresh() }
        compose.setContent { MetroCastTheme { com.metrocast.app.ui.HomeScreen(com.metrocast.app.ui.UiState(snapshot = repo.snapshot()), repo, onRefresh = {}, onOpenSettings = {}) } }
        page().performScrollToNode(androidx.compose.ui.test.hasText("Board at"))
        assertTrue(!sees("Restroom —")); assertTrue(!sees("min ride")); assertTrue(!sees("After that"))
        compose.onNodeWithContentDescription("Menu").performClick()
        assertTrue(!sees("Scenic rides")); assertTrue(sees("Bikes"))
        val tops = compose.onAllNodes(androidx.compose.ui.test.hasClickAction()).fetchSemanticsNodes()
            .mapNotNull { n -> if (n.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Text)) n.config[androidx.compose.ui.semantics.SemanticsProperties.Text].firstOrNull()?.text else null }
        // Rows start with their icon: 🚲 Bikes now comes before 🧭 Trip planner; 🌅 Scenic is hidden.
        assertTrue("menu order: $tops", tops.indexOf("🚲") in 0 until tops.indexOf("🧭")); assertTrue("🌅" !in tops)
    }

    @Test fun settingsCardsExist() {
        compose.setContent { MetroCastTheme { com.metrocast.app.ui.SettingsScreen(repo, onChanged = {}, onBack = {}) } }
        for (t in listOf("Trip cards", "Menu", "Trip planner starts at", "Units", "Clock", "Quiet hours", "Notifications", "Privacy & data")) {
            page().performScrollToNode(androidx.compose.ui.test.hasText(t))
        }
        page().performScrollToNode(androidx.compose.ui.test.hasText("24-hour (13:45)"))
        compose.onNodeWithText("24-hour (13:45)").performClick()
        assertTrue(repo.prefs.clock24 && com.metrocast.app.ui.Format.use24h)
    }
}

/** The speed-ups never serve stale settings: anything you change shows straight away. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class CachingRoboTest {
    @Test fun cachedButAlwaysCurrent() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.getSharedPreferences("metrocast", Context.MODE_PRIVATE).edit().clear().commit()
        FakeWmata.install()
        val repo = Repository(ctx)
        runBlocking { repo.refresh() }
        val p = repo.prefs
        // Same answer object while nothing changes (no re-reading)…
        assertTrue(p.trips === p.trips); assertTrue(p.homeSections === p.homeSections); assertTrue(p.menuItems === p.menuItems)
        assertTrue(repo.myStations() === repo.myStations())
        // …and the new answer as soon as something does.
        p.trips = listOf(repo.buildTrip("K05", "C04", com.metrocast.app.data.Trip.GOING)!!)
        org.junit.Assert.assertEquals("K05", p.trips.single().stationCode.let { com.metrocast.app.data.StationDirectory.find(it)!!.code })
        assertTrue(repo.myStations().any { it.code == "K05" })
        p.homeSections = p.homeSections.map { (s, _) -> s to false }
        assertTrue(p.homeSections.none { it.second })
        p.showSilverMap = true
        assertTrue(p.homeSections.single { it.first == com.metrocast.app.data.HomeSection.MAP }.second)
        p.menuItems = p.menuItems.reversed()
        org.junit.Assert.assertEquals(com.metrocast.app.data.MenuItem.ISSUES, p.menuItems.first().first)
        // Capital Bikeshare's station list: read once, reused.
        runBlocking {
            repo.loadBikeshare(repo.myStations())
            FakeWmata.requests.clear()
            repo.loadBikeshare(repo.myStations())
        }
        assertTrue(FakeWmata.seen().none { "station_information" in it })
        FakeWmata.uninstall()
    }
}


@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class TrainsCountHelpRoboTest {
    @get:Rule val compose = createComposeRule()

    @Test fun incomingTrainsSettingExplainsItself() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.getSharedPreferences("metrocast", Context.MODE_PRIVATE).edit().clear().commit()
        FakeWmata.install()
        val repo = Repository(ctx)
        compose.setContent { MetroCastTheme { com.metrocast.app.ui.SettingsScreen(repo, onChanged = {}, onBack = {}) } }
        val page = compose.onAllNodes(androidx.compose.ui.test.hasScrollAction())[0]
        page.performScrollToNode(androidx.compose.ui.test.hasText("How many of the next trains", substring = true))
        compose.onNodeWithText("How many of the next trains", substring = true).assertExists()
        page.performScrollToNode(androidx.compose.ui.test.hasText("Right now:", substring = true))
        compose.onNodeWithText("Right now: the next 4 trains.").assertExists()
        FakeWmata.uninstall()
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class NoTrainNumbersRoboTest {
    @get:Rule val compose = createComposeRule()

    /** Trains aren't numbered "1", "2" — it read like part of the time ("1 3m"). */
    @Test fun noNumbersBeforeTimes() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.getSharedPreferences("metrocast", Context.MODE_PRIVATE).edit().clear().commit()
        FakeWmata.install()
        val repo = Repository(ctx); runBlocking { repo.refresh() }
        val snap = repo.snapshot()
        val trip = snap.trips.firstOrNull { it.location?.trains?.isNotEmpty() == true }
        val map = repo.lineMaps()[trip?.trip?.lineCode ?: "SV"]
        val onMap = androidx.compose.runtime.mutableStateOf(false)
        compose.setContent { MetroCastTheme {
            if (!onMap.value) com.metrocast.app.ui.HomeScreen(com.metrocast.app.ui.UiState(snapshot = snap, maps = repo.lineMaps()), repo, onRefresh = {}, onOpenSettings = {})
            else com.metrocast.app.ui.LineMapScreen(trip?.trip?.lineCode ?: "SV", map, trip, emptySet(), onBack = {})
        } }
        compose.waitForIdle()
        val texts = compose.onAllNodes(androidx.compose.ui.test.hasText("", substring = true)).fetchSemanticsNodes()
            .flatMap { n -> n.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Text) { emptyList() }.map { it.text } }
        assertTrue(texts.toString(), texts.none { Regex("^\\d+\\s*▼").containsMatchIn(it) || Regex("^\\d+  ").containsMatchIn(it) })
        // The full line map, focused on a trip.
        assertTrue("a trip with trains on the way", trip != null)
        onMap.value = true
        compose.waitForIdle()
        val mapTexts = compose.onAllNodes(androidx.compose.ui.test.hasText("", substring = true)).fetchSemanticsNodes()
            .flatMap { n -> n.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Text) { emptyList() }.map { it.text } }
        assertTrue(mapTexts.toString(), mapTexts.none { Regex("^\\d+ \\d+m?[▲▼]").containsMatchIn(it) || Regex("^\\d [▲▼]").containsMatchIn(it) })
        FakeWmata.uninstall()
    }
}
