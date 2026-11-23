package com.metrocast.app.robo

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.metrocast.app.data.HttpException
import com.metrocast.app.data.Repository
import com.metrocast.app.data.StationDirectory
import com.metrocast.app.notify.Notifier
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
import java.net.UnknownHostException

/** The whole data side of the app (settings, caching, planner, live trip, notifications) against a fake WMATA. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RepositoryRoboTest {
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

    @Test fun firstRefreshLoadsEverything(): Unit = runBlocking {
        val r = repo.refresh()
        assertNull("weather error: ${r.weatherError}", r.weatherError)
        assertNull("train error: ${r.trainError}", r.trainError)
        val s = repo.snapshot()
        assertNotNull(s.weather)
        assertEquals(2, s.trips.size)                      // the two default trips
        assertTrue(StationDirectory.stations.size > 90)
        assertEquals(6, repo.lineMaps().size)               // all six lines on the live map
        s.trips.forEach { t -> assertNotNull("ride info for ${t.trip.label}", t.ride) }
    }

    @Test fun plannerSavedRoutesAndDestination(): Unit = runBlocking {
        repo.refresh()
        repo.setPlan("N06", "B03")
        repo.refresh()
        val plan = repo.snapshot().plan!!
        assertEquals(2, plan.legs.size)
        assertEquals("Metro Center", plan.legs[0].leg.to.name)
        repo.toggleSavedRoute("N06", "B03")
        assertTrue(repo.isSaved("N06", "B03"))
        assertEquals(1, repo.snapshot().savedRoutes.size)
        repo.toggleSavedRoute("N06", "B03")
        assertFalse(repo.isSaved("N06", "B03"))
        // Change the first trip's destination to Metro Center.
        val t = repo.prefs.trips.first()
        // Metro Center's Silver/Orange/Blue platform is C01 (A01 is the Red Line level).
        repo.setTripDestination(t.id, com.metrocast.app.data.Station("C01", "Metro Center"), "SV")
        assertEquals("C01", repo.prefs.trips.first().towardCode)
    }

    @Test fun liveTripLifecycleAndPrivacy(): Unit = runBlocking {
        repo.refresh()
        repo.startLiveTrip("C02", "F05")
        repo.refresh()
        val live = repo.liveTrip()
        assertNotNull(live)
        assertEquals(2, live!!.plan.legs.size)
        repo.boardLiveTrain()
        assertTrue(repo.liveTrip()!!.onBoard)
        repo.autoAdvanceLive()
        repo.checkLiveNotification(ctx)                     // must not crash
        repo.nextLiveLeg()
        assertEquals(1, repo.liveTrip()!!.leg)
        repo.previousLiveLeg()
        assertEquals(0, repo.liveTrip()!!.leg)
        // Ending the trip erases everything about it.
        repo.endLiveTrip()
        assertNull(repo.liveTrip())
        val keys = ctx.getSharedPreferences("metrocast", Context.MODE_PRIVATE).all.keys
        val leftover = keys.filter { it.startsWith("live_") && it != "live_use_loc" }
        assertTrue("live-trip data left after ending: $leftover", leftover.isEmpty())
    }

    @Test fun tripEndsByItselfSevenMinutesAfterArriving(): Unit = runBlocking {
        repo.refresh()
        repo.startLiveTrip("C02", "F05")       // 2 legs: to L'Enfant, then Green to Navy Yard
        repo.boardLiveTrain(); repo.nextLiveLeg()
        repo.boardLiveTrain(); repo.nextLiveLeg() // final train reached Navy Yard
        val arrived = repo.liveTrip()!!
        assertTrue(arrived.arrived)
        assertEquals(7, arrived.autoEndMinutes)
        // 3 minutes later: still there, 4 minutes to go.
        repo.prefs.liveArrivedAt = System.currentTimeMillis() - 3 * 60_000L
        assertEquals(4, repo.liveTrip()!!.autoEndMinutes)
        // "Back a step" after arriving cancels the countdown.
        repo.previousLiveLeg()
        assertFalse(repo.liveTrip()!!.arrived)
        assertEquals(0L, repo.prefs.liveArrivedAt)
        repo.nextLiveLeg()
        assertEquals(7, repo.liveTrip()!!.autoEndMinutes)
        // 7 minutes after arriving: the trip is gone, with nothing left behind.
        repo.prefs.liveArrivedAt = System.currentTimeMillis() - 7 * 60_000L - 1_000L
        assertNull(repo.liveTrip())
        val keys = ctx.getSharedPreferences("metrocast", Context.MODE_PRIVATE).all.keys
        assertTrue("left after auto-end: $keys", keys.none { it.startsWith("live_") && it != "live_use_loc" })
    }

    @Test fun finishedTripsGoToHistoryOnceAndCanBeTurnedOff(): Unit = runBlocking {
        repo.refresh()
        repo.prefs.riders = 2
        repo.startLiveTrip("C02", "F05")
        repo.refresh() // the app refreshes every 15–30 s; this fetches the trip's fare
        repo.boardLiveTrain(); repo.nextLiveLeg(); repo.boardLiveTrain(); repo.nextLiveLeg()
        repo.liveTrip() // arrival: recorded
        var h = repo.prefs.history
        assertEquals(1, h.size)
        assertEquals("McPherson Square", h[0].from); assertEquals("Navy Yard-Ballpark", h[0].to)
        assertEquals(2, h[0].people); assertEquals(1, h[0].transfers)
        assertTrue("fare for 2: ${h[0].fare}", h[0].fare >= 2 * 2.25)
        // Back a step and arrive again: still one record.
        repo.previousLiveLeg(); repo.nextLiveLeg(); repo.liveTrip()
        assertEquals(1, repo.prefs.history.size)
        // A second trip, arriving before its fare was downloaded: saved as "fare unknown", not $0.
        repo.endLiveTrip(); repo.startLiveTrip("N06", "K05")
        repo.boardLiveTrain(); repo.nextLiveLeg(); repo.liveTrip()
        assertEquals(2, repo.prefs.history.size)
        assertTrue("unknown fare should be negative, was ${repo.prefs.history[0].fare}", repo.prefs.history[0].fare < 0)
        assertEquals("unknown fares stay out of the money total", h[0].fare,
            com.metrocast.app.data.History.totals(repo.prefs.history).money, 1e-9)
        // Off: deletes everything and records nothing.
        repo.prefs.historyEnabled = false
        assertTrue(repo.prefs.history.isEmpty())
        repo.endLiveTrip(); repo.startLiveTrip("N06", "C04"); repo.boardLiveTrain(); repo.nextLiveLeg(); repo.liveTrip()
        assertTrue(repo.prefs.history.isEmpty())
        h = repo.prefs.history
        assertTrue(h.isEmpty())
    }

    @Test fun upgradeKeepsSavedTrips() {
        val prefs = ctx.getSharedPreferences("metrocast", Context.MODE_PRIVATE)
        // An older version's saved trips are left exactly as they were.
        prefs.edit().clear().putString("trips", """[
            {"id":"default-going","label":"Going there","line":"SV","station":"N06","stationName":"Wiehle-Reston East","toward":"C04","towardName":"Foggy Bottom-GWU","widget":true},
            {"id":"mine","label":"Work","line":"OR","station":"K05","stationName":"East Falls Church","toward":"D13","towardName":"New Carrollton","widget":true}
        ]""").commit()
        val trips = Repository(ctx).prefs.trips
        assertEquals(listOf("N06→C04", "K05→D13"), trips.map { "${it.stationCode}→${it.towardCode}" })
        assertEquals(listOf("Going there", "Work"), trips.map { it.label })
    }

    @Test fun freshInstallHasTheTestTrips() {
        val trips = repo.prefs.trips
        assertEquals(listOf("N06→C04", "C04→N06"), trips.map { "${it.stationCode}→${it.towardCode}" })
    }

    @Test fun shippedDefaultsMatchTheBuild() {
        TestDefaults.shipped()
        try {
            ctx.getSharedPreferences("metrocast", Context.MODE_PRIVATE).edit().clear().commit()
            val p = Repository(ctx).prefs
            if (com.metrocast.app.BuildConfig.WMATA_KEY.isEmpty()) {
                // Public build: nothing of yours, just downtown DC.
                assertEquals(listOf("A01→B03", "B03→A01"), p.trips.map { "${it.stationCode}→${it.towardCode}" })
                assertEquals("Washington, DC", p.locationName); assertEquals("A01", p.planFrom); assertEquals("RD", p.homeMapLine)
            } else {
                // Your build: the starting trips and weather it was built with (private.properties, or neutral).
                val d = com.metrocast.app.BuildConfig.START_DEFAULTS.split("|")
                assertEquals(listOf("${d[1]}→${d[3]}", "${d[3]}→${d[1]}"), p.trips.map { "${it.stationCode}→${it.towardCode}" })
                assertEquals(d[5], p.locationName); assertEquals(d[1], p.planFrom); assertEquals(d[0], p.homeMapLine)
            }
        } finally { TestDefaults.use() }
    }

    @Test fun plannerResetsWhenATripIsOver(): Unit = runBlocking {
        repo.refresh()
        // Ended by hand.
        repo.prefs.riders = 3
        repo.setPlan("C02", "F05")
        repo.startLiveTrip("C02", "F05")
        repo.endLiveTrip()
        assertEquals("N06", repo.prefs.planFrom)
        assertNull(repo.prefs.planTo)
        assertEquals(1, repo.prefs.riders)
        assertNull(repo.snapshot().plan)
        // Ended by itself 7 minutes after arriving.
        repo.prefs.riders = 2
        repo.setPlan("N06", "C04")
        repo.startLiveTrip("N06", "C04")
        repo.boardLiveTrain(); repo.nextLiveLeg(); repo.liveTrip()
        repo.prefs.liveArrivedAt = System.currentTimeMillis() - 8 * 60_000L
        assertNull(repo.liveTrip())
        assertNull(repo.prefs.planTo)
        assertEquals(1, repo.prefs.riders)
        // Saved routes and home trips are untouched.
        repo.toggleSavedRoute("A15", "F05"); repo.startLiveTrip("A15", "F05"); repo.endLiveTrip()
        assertTrue(repo.isSaved("A15", "F05"))
        assertEquals(2, repo.prefs.trips.size)
    }

    @Test fun errorsAreFriendlyAndNothingCrashes(): Unit = runBlocking {
        FakeWmata.failing = mapOf("GetPrediction" to HttpException(500))
        assertTrue(repo.refresh().trainError!!.contains("500"))
        FakeWmata.failing = mapOf("GetPrediction" to HttpException(401))
        assertTrue(repo.refresh().trainError!!.contains("API key"))
        FakeWmata.failing = mapOf("api.wmata.com" to UnknownHostException("x"), "open-meteo" to UnknownHostException("x"))
        val r = repo.refresh(forceWeather = true) // weather is cached for 5 min; force a fetch to see the error
        assertEquals("No internet connection", r.trainError)
        assertEquals("No internet connection", r.weatherError)
        repo.snapshot() // still builds from whatever is cached
        repo.lineMaps()
        Unit
    }

    @Test fun issuesAndExits(): Unit = runBlocking {
        repo.refresh()
        assertTrue(repo.loadIssues().isNotEmpty())
        val exits = repo.loadExits(StationDirectory.find("C04")!!)
        assertEquals(2, exits.size)
        // Exits are cached: no second request.
        val before = FakeWmata.seen().count { "Entrances" in it }
        repo.loadExits(StationDirectory.find("C04")!!)
        assertEquals(before, FakeWmata.seen().count { "Entrances" in it })
    }

    @Test fun widgetTickReusesFreshData(): Unit = runBlocking {
        repo.refresh()
        val n = FakeWmata.seen().size
        assertFalse("data just loaded shouldn't need refreshing", repo.needsRefresh(25_000))
        assertEquals(n, FakeWmata.seen().size)
    }

    @Test fun transferNotificationPostsUpdatesAndClears() {
        shadowOf(ApplicationProvider.getApplicationContext<Application>()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val nm = ctx.getSystemService(NotificationManager::class.java)
        Notifier.trip(ctx, "Hurry for the Green Line — 3 min", "Transfer at L'Enfant Plaza is 2 stops away.")
        assertEquals(1, shadowOf(nm).allNotifications.size)
        Notifier.trip(ctx, "Run for the Green Line — 2 min", "…", silent = true)   // updates the same one
        assertEquals(1, shadowOf(nm).allNotifications.size)
        Notifier.cancelTrip(ctx)
        assertEquals(0, shadowOf(nm).allNotifications.size)
    }

    @Test fun noNotificationWithoutPermission() {
        shadowOf(ApplicationProvider.getApplicationContext<Application>()).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val nm = ctx.getSystemService(NotificationManager::class.java)
        Notifier.trip(ctx, "x", "y")
        assertEquals(0, shadowOf(nm).allNotifications.size)
    }
}
