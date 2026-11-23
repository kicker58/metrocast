package com.metrocast.app.robo

import android.content.Context
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.metrocast.app.data.CrashLog
import com.metrocast.app.data.Repository
import com.metrocast.app.ui.MetroCastTheme
import com.metrocast.app.ui.Net
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 1.19: lighter background work, What's new, offline banner, crash log. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class Version119RoboTest {
    @get:Rule val compose = createComposeRule()
    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()

    private fun repo(): Repository {
        ctx.getSharedPreferences("metrocast", Context.MODE_PRIVATE).edit().clear().commit()
        CrashLog.clear(ctx)
        FakeWmata.install()
        return Repository(ctx)
    }
    @After fun done() { Net.testOnline = null; FakeWmata.uninstall(); CrashLog.clear(ctx) }
    private fun sees(t: String) = compose.onAllNodesWithText(t, substring = true).fetchSemanticsNodes().isNotEmpty()

    @Test fun noWidgetMeansAlertsOnly() {
        val r = repo()
        // Delay alerts on: only WMATA's service alerts are asked for — no trains, weather or maps.
        r.prefs.alertNotifications = true
        assertEquals(1, r.alertsOnlyCheck())
        assertTrue(FakeWmata.requests.all { "Incidents" in it }); assertTrue(FakeWmata.requests.isNotEmpty())
        // Not again within 5 minutes.
        FakeWmata.requests.clear(); assertEquals(0, r.alertsOnlyCheck()); assertTrue(FakeWmata.requests.isEmpty())
        // Delay alerts off: nothing at all.
        r.prefs.alertNotifications = false; r.prefs.putCache("incidents", "{}")
        FakeWmata.requests.clear(); assertEquals(0, r.alertsOnlyCheck()); assertTrue(FakeWmata.requests.isEmpty())
    }

    @Test fun whatsNewOnceAfterAnUpdateNotOnFreshInstall() {
        val r = repo()
        assertNull(r.whatsNewToShow())                       // fresh install: the walkthrough instead
        assertNull(r.whatsNewToShow())                       // and not later either
        r.prefs.seenVersion = "1.18"                         // pretend we just updated from 1.18
        val lines = r.whatsNewToShow()
        // Exactly this version's whatsnew.txt.
        assertEquals(java.io.File("../whatsnew.txt").readLines().map { it.trim() }.filter { it.isNotEmpty() }, lines)
        assertNull(r.whatsNewToShow())                       // once only
        assertEquals(com.metrocast.app.BuildConfig.VERSION_NAME, r.prefs.seenVersion)
    }

    @Test fun whatsNewAfterUpdatingFromBefore119() {
        val r = repo()
        // Someone on 1.18: set up long ago, but no version remembered (1.18 didn't keep one).
        r.prefs.onboarded = true
        assertNull(r.prefs.seenVersion)
        val lines = r.whatsNewToShow()
        assertTrue("an update from 1.18 shows What's new", lines != null && lines.isNotEmpty())
        assertNull(r.whatsNewToShow())                       // and only once
    }

    @Test fun offlineBannerOnHome() {
        val r = repo(); runBlocking { r.refresh() }
        Net.testOnline = false
        compose.setContent { MetroCastTheme { com.metrocast.app.ui.HomeScreen(com.metrocast.app.ui.UiState(snapshot = r.snapshot()), r, onRefresh = {}, onOpenSettings = {}) } }
        assertTrue(sees("Offline — showing times from"))
    }

    @Test fun noBannerWhenOnline() {
        val r = repo(); runBlocking { r.refresh() }
        Net.testOnline = true
        compose.setContent { MetroCastTheme { com.metrocast.app.ui.HomeScreen(com.metrocast.app.ui.UiState(snapshot = r.snapshot()), r, onRefresh = {}, onOpenSettings = {}) } }
        assertTrue(!sees("Offline"))
    }

    @Test fun offlineOnTheTrainAndAirportScreens() {
        val r = repo(); runBlocking { r.refresh() }
        Net.testOnline = false
        val which = androidx.compose.runtime.mutableStateOf(0)
        compose.setContent { MetroCastTheme {
            if (which.value == 0) com.metrocast.app.ui.AirportScreen(r, onBack = {}, onPlan = { _, _ -> }, clock = { java.time.LocalDateTime.of(2026, 11, 23, 8, 0) })
            else com.metrocast.app.ui.RailScreen(r, onBack = {}, onPlan = { _, _ -> }, clock = { java.time.LocalDateTime.of(2026, 11, 23, 8, 0) })
        } }
        assertTrue(sees("Offline — flight status"))
        which.value = 1; compose.waitForIdle()
        compose.onAllNodesWithText("Northeast Regional", substring = true)[0].performClick()
        compose.waitForIdle()
        assertTrue(sees("Offline — live status comes back"))
    }

    @Test fun crashLogKeptOnThePhoneAndShownInSettings() {
        val r = repo()
        assertTrue(CrashLog.entries(ctx).isEmpty())
        // Many crashes, huge ones: only the last 5 kept, and the file stays small.
        repeat(20) { i -> CrashLog.record(ctx, IllegalStateException("boom $i " + "x".repeat(50_000))) }
        val e = CrashLog.entries(ctx)
        assertEquals(5, e.size); assertTrue(e.first().contains("boom 19")); assertTrue(CrashLog.text(ctx).length <= 60_000)
        assertTrue(e.first().contains("MetroCast ${com.metrocast.app.BuildConfig.VERSION_NAME}"))
        compose.setContent { MetroCastTheme { com.metrocast.app.ui.SettingsScreen(r, onChanged = {}, onBack = {}) } }
        compose.onAllNodes(hasScrollAction())[0].performScrollToNode(hasText("Crash log"))
        assertTrue(sees("5 crashes noted · kept on this phone only"))
        compose.onNodeWithText("View").performClick(); assertTrue(sees("boom 19"))
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithText("Clear").performClick()
        assertTrue(CrashLog.entries(ctx).isEmpty()); assertTrue(!sees("Crash log"))
    }

    @Test fun crashHandlerNotesItAndPassesItOn() {
        var passedOn: Throwable? = null
        Thread.setDefaultUncaughtExceptionHandler { _, t -> passedOn = t }
        CrashLog.clear(ctx)
        CrashLog.install(ctx)
        val boom = RuntimeException("test crash")
        Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), boom)
        assertEquals(boom, passedOn)                         // Android still handles it as usual
        assertTrue(CrashLog.entries(ctx).single().contains("test crash"))
    }

    @Test fun settingsStillCompleteAfterTheSplit() {
        val r = repo()
        compose.setContent { MetroCastTheme { com.metrocast.app.ui.SettingsScreen(r, onChanged = {}, onBack = {}) } }
        val page = compose.onAllNodes(hasScrollAction())[0]
        for (t in listOf("Home screen layout", "Trip cards", "Menu", "Units", "Quiet hours", "Notifications", "Weather", "MetroCast version")) {
            page.performScrollToNode(hasText(t, substring = true)); assertTrue(t, sees(t))
        }
    }
}
