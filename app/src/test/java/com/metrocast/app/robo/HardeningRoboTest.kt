package com.metrocast.app.robo

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.metrocast.app.data.Repository
import com.metrocast.app.data.StationDirectory
import com.metrocast.app.notify.Background
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDateTime

/** Bad data, background features, being killed mid-trip, and endurance. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class HardeningRoboTest {
    private lateinit var ctx: Context
    private lateinit var repo: Repository
    private val nm get() = ctx.getSystemService(NotificationManager::class.java)

    @Before fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        ctx.getSharedPreferences("metrocast", Context.MODE_PRIVATE).edit().clear().commit()
        ctx.filesDir.listFiles()?.forEach { it.delete() }
        shadowOf(ApplicationProvider.getApplicationContext<Application>()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        nm.cancelAll()
        FakeWmata.install()
        repo = Repository(ctx)
    }

    @After fun tearDown() = FakeWmata.uninstall()

    // ---------------------------------------------------------------- garbage from WMATA

    @Test fun garbageResponsesNeverCrashOrReplaceGoodData(): Unit = runBlocking {
        repo.refresh()
        repo.setPlan("C02", "F05")
        repo.startLiveTrip("C02", "F05")
        repo.refresh()
        val goodTrips = repo.snapshot().trips.map { it.arrivals.size }
        val goodStations = StationDirectory.stations.size
        val garbage = listOf(
            "", "{", "null", "[]", "{}", "<html><body>503 Service Unavailable</body></html>",
            """{"Trains":[{"Min":5}]}""", """{"Trains":"oops"}""", """{"TrainPositions":[{"CircuitId":"x"}]}""",
            """{"StandardRoutes":[{"LineCode":"SV","TrackCircuits":[]}]}""", """{"Stations":[]}""",
            """{"Incidents":[{"Description":null}]}""", """{"StationToStationInfos":[]}""",
            """{"current":{}}""",
        )
        val endpoints = listOf("GetPrediction", "TrainPositions", "Incidents", "jStations", "StandardRoutes", "jSrcStationToDstStationInfo", "open-meteo", "jStationEntrances")
        var runs = 0
        for (ep in endpoints) for (g in garbage) {
            FakeWmata.bodies = mapOf(ep to g)
            // Force re-fetch of things that are normally cached.
            ctx.getSharedPreferences("metrocast", Context.MODE_PRIVATE).edit()
                .putLong("cache_ts_incidents", 0L).putLong("cache_ts_weather", 0L).commit()
            try {
                repo.refresh(forceWeather = true)
                repo.snapshot(); repo.lineMaps(); repo.liveTrip(); repo.autoAdvanceLive(); repo.checkLiveNotification(ctx)
                runCatching { repo.loadIssues() }
                runCatching { repo.loadExits(StationDirectory.find("C04")!!) }
            } catch (e: Throwable) {
                throw AssertionError("crashed on $ep = '${g.take(40)}': $e", e)
            }
            runs++
        }
        FakeWmata.bodies = emptyMap()
        // The good data survived: station list intact, and a normal refresh brings everything back.
        assertEquals(goodStations, StationDirectory.stations.size)
        repo.refresh(forceWeather = true)
        assertEquals(goodTrips, repo.snapshot().trips.map { it.arrivals.size })
        assertNotNull(repo.snapshot().weather)
        println("Garbage combinations survived: $runs")
    }

    // ---------------------------------------------------------------- leave-now reminders (removed)

    @Test fun oldLeaveRemindersAreCleanedUp() {
        val am = ctx.getSystemService(AlarmManager::class.java)
        // What an older version left behind: its settings and its next alarm.
        ctx.getSharedPreferences("metrocast", Context.MODE_PRIVATE).edit()
            .putBoolean("leave_alerts", true).putInt("walk_min", 8).putInt("leave_start", 420).putString("leave_last", "x").commit()
        val old = android.app.PendingIntent.getBroadcast(ctx, 1, android.content.Intent().setClassName(ctx, "com.metrocast.app.notify.LeaveTicker"),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
        am.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 60_000, old)
        assertNotNull(shadowOf(am).nextScheduledAlarm)
        Background.sync(ctx)
        assertNull("old reminder alarm still set", shadowOf(am).scheduledAlarms.firstOrNull { it.operation == old })
        val sp = ctx.getSharedPreferences("metrocast", Context.MODE_PRIVATE)
        assertTrue(listOf("leave_alerts", "walk_min", "leave_start", "leave_last").none { sp.contains(it) })
        // No leave-now channel.
        com.metrocast.app.notify.Notifier.createChannels(ctx)
        assertNull(nm.getNotificationChannel("leave"))
    }

    // ---------------------------------------------------------------- train alerts

    @Test fun alertNotificationsOnlyForNewAlertsOnYourLines(): Unit = runBlocking {
        repo.prefs.alertNotifications = true
        repo.refresh()                                   // recorded alert is on Blue/Orange/Silver: a Silver trip
        val first = shadowOf(nm).allNotifications.size
        assertTrue("expected a train-alert notification", first >= 1)
        ctx.getSharedPreferences("metrocast", Context.MODE_PRIVATE).edit().putLong("cache_ts_incidents", 0L).commit()
        repo.refresh()                                   // same alerts again
        assertEquals("repeated an alert", first, shadowOf(nm).allNotifications.size)
        // Off: no notifications at all.
        nm.cancelAll(); repo.prefs.alertNotifications = false; repo.prefs.seenAlertIds = emptySet()
        ctx.getSharedPreferences("metrocast", Context.MODE_PRIVATE).edit().putLong("cache_ts_incidents", 0L).commit()
        repo.refresh()
        assertEquals(0, shadowOf(nm).allNotifications.size)
    }

    // ---------------------------------------------------------------- GPS boarding

    @Test fun gpsNoticesBoarding(): Unit = runBlocking {
        repo.refresh()
        repo.startLiveTrip("N06", "B03")
        repo.refresh()
        val wiehle = StationDirectory.find("N06")!!
        assertFalse(repo.onLiveLocation(wiehle.lat, wiehle.lon, 0f))              // on the platform
        assertTrue(repo.prefs.liveWasNear)
        assertFalse("not yet: walking nearby", repo.onLiveLocation(wiehle.lat + 0.002, wiehle.lon, 1.2f))
        // ~1.5 km east along the line at train speed.
        assertTrue(repo.onLiveLocation(wiehle.lat - 0.003, wiehle.lon + 0.017, 18f))
        val live = repo.liveTrip()!!
        assertTrue(live.onBoard)
        assertTrue(repo.prefs.liveAutoBoarded)
        // Never near the station: driving past doesn't count as boarding.
        repo.endLiveTrip(); repo.startLiveTrip("N06", "B03")
        assertFalse(repo.onLiveLocation(wiehle.lat + 0.02, wiehle.lon, 25f))
        assertFalse(repo.liveTrip()!!.onBoard)
    }

    // ---------------------------------------------------------------- killed mid-trip

    @Test fun tripSurvivesTheAppBeingKilled(): Unit = runBlocking {
        repo.refresh()
        repo.startLiveTrip("C02", "F05")
        repo.boardLiveTrain(); repo.nextLiveLeg(); repo.boardLiveTrain()
        val before = repo.liveTrip()!!
        // Android kills the app; it starts again with a brand-new process state.
        val again = Repository(ctx)
        val after = again.liveTrip()!!
        assertEquals(before.leg, after.leg)
        assertEquals(before.onBoard, after.onBoard)
        assertEquals(before.plan.legs.map { it.leg.to.code }, after.plan.legs.map { it.leg.to.code })
    }

    // ---------------------------------------------------------------- endurance

    @Test fun twoHundredRefreshesStayFastAndDontLeak(): Unit = runBlocking {
        repo.refresh()
        repo.setPlan("N06", "B03"); repo.toggleSavedRoute("C02", "F05"); repo.startLiveTrip("C02", "F05")
        val rt = Runtime.getRuntime()
        fun used(): Long { repeat(3) { System.gc(); Thread.sleep(20) }; return rt.totalMemory() - rt.freeMemory() }
        repeat(20) { i -> FakeWmata.snapshot = i % 8 + 1; repo.refresh(); repo.snapshot(); repo.lineMaps(); repo.liveTrip() }
        val base = used()
        val t = System.nanoTime()
        repeat(200) { i ->
            FakeWmata.snapshot = i % 8 + 1
            repo.refresh(); repo.snapshot(); repo.lineMaps(); repo.liveTrip(); repo.autoAdvanceLive()
        }
        val ms = (System.nanoTime() - t) / 1e6 / 200
        val grew = (used() - base) / (1024 * 1024)
        println("Refresh cycle: %.1f ms average; memory change %d MB".format(ms, grew))
        assertTrue("refresh cycle too slow: $ms ms", ms < 250)
        assertTrue("memory kept growing: $grew MB", grew < 25)
    }
}
