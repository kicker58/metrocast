package com.metrocast.app.robo

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.ComponentName
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.metrocast.app.ui.MainActivity
import com.metrocast.app.widget.MetroWidget
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Opening the app from the widget is quick, and the widget's own refreshes stay light. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class WidgetTapRoboTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()
    @After fun done() = FakeWmata.uninstall()

    @Test fun tappingTheWidgetReusesTheOpenApp() {
        // One copy of the app: a widget tap brings back the one already open (with its live data),
        // instead of starting a second one from scratch on top.
        val info = ctx.packageManager.getActivityInfo(ComponentName(ctx, MainActivity::class.java), 0)
        assertEquals(ActivityInfo.LAUNCH_SINGLE_TASK, info.launchMode)
    }

    @Test fun widgetRefreshesSkipTheSlowChecks() {
        ctx.getSharedPreferences("metrocast", Context.MODE_PRIVATE).edit().clear().commit()
        FakeWmata.install()
        val slow = listOf("restrooms.html", "bike-and-ride", "mdotmta_gtfs_marc", "gtfs.vre.org", "content.amtrak.com", "weekend.html")
        // The 30-second tick and the ↻ button: trains and weather only.
        runBlocking { MetroWidget.refreshQuietly(ctx) }
        runBlocking { MetroWidget.refreshAll(ctx, forceWeather = true) }
        assertTrue(FakeWmata.seen().toString(), FakeWmata.seen().none { u -> slow.any { it in u } })
        assertTrue(FakeWmata.seen().any { "GetPrediction" in it })
        // The 15-minute background job does the slow checks.
        runBlocking { MetroWidget.refreshAll(ctx, extras = true) }
        assertTrue(FakeWmata.seen().any { "restrooms.html" in it })
    }
}
