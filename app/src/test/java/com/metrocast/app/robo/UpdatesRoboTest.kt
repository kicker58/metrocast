package com.metrocast.app.robo

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.metrocast.app.data.Repository
import com.metrocast.app.data.Updater
import com.metrocast.app.ui.MetroCastTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The updates copy: checks a folder on your PC, downloads, verifies, and opens Android's installer. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class UpdatesRoboTest {
    @get:Rule val compose = createComposeRule()
    private val apk = "pretend apk bytes".toByteArray()
    private fun versionJson(code: Int = 2_000_000_000, sha: String = Updater.sha256(apk)) =
        """{"versionCode":$code,"versionName":"1.0.$code","apk":"MetroCast-Updates.apk","sha256":"$sha","notes":"New stuff"}"""

    private fun repo(): Repository {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.getSharedPreferences("metrocast", Context.MODE_PRIVATE).edit().clear().commit()
        Updater.testEnabled = true
        Updater.testPhoneAddresses = listOf("192.168.1.77", "10.0.0.9") // at home, on the same Wi-Fi as the PC
        FakeWmata.install()
        FakeWmata.liveFeeds = mapOf("MetroCast-Updates.apk" to apk)
        return Repository(ctx)
    }
    @After fun done() { Updater.testEnabled = null; Updater.testPhoneAddresses = null; FakeWmata.uninstall() }
    private fun sees(t: String) = compose.onAllNodesWithText(t, substring = true).fetchSemanticsNodes().isNotEmpty()

    @Test fun checkDownloadAndInstall() {
        val r = repo()
        FakeWmata.bodies = mapOf("8765/version.json" to versionJson())
        compose.setContent { MetroCastTheme { com.metrocast.app.ui.SettingsScreen(r, onChanged = {}, onBack = {}) } }
        compose.onAllNodes(hasScrollAction())[0].performScrollToNode(hasText("UPDATES FROM YOUR PC"))
        compose.onNodeWithText("Your PC's address").performTextInput("192.168.1.20")
        assertEquals("192.168.1.20", r.prefs.updateAddress)
        compose.onAllNodes(hasScrollAction())[0].performScrollToNode(hasText("Check for updates"))
        compose.onNodeWithText("Check for updates").performClick()
        compose.waitUntil(5_000) { sees("is ready") }
        assertTrue(FakeWmata.requests.any { it == "http://192.168.1.20:8765/version.json" })
        // Not allowed to install yet: sends you to Android's switch for it.
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        shadowOf(app.packageManager).setCanRequestPackageInstalls(false)
        compose.onNodeWithText("Install").performClick()
        assertTrue(sees("allow MetroCast to install updates"))
        assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, shadowOf(app).nextStartedActivity?.action)
        // Allowed: downloads, checks it, opens the installer.
        shadowOf(app.packageManager).setCanRequestPackageInstalls(true)
        compose.onNodeWithText("Install").performClick()
        compose.waitUntil(5_000) { sees("Opening the installer") || sees("Couldn't open") }
        assertTrue(compose.onAllNodes(hasText("", substring = true)).fetchSemanticsNodes().flatMap { n -> n.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Text) { emptyList() }.map { it.text } }.filter { "installer" in it }.toString(), sees("Opening the installer"))
        val i = shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, i?.action); assertEquals("application/vnd.android.package-archive", i?.type)
        assertTrue(i!!.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }

    @Test fun badDownloadIsRefusedAndUpToDateSaysSo() {
        val r = repo()
        shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>().packageManager).setCanRequestPackageInstalls(true)
        FakeWmata.bodies = mapOf("version.json" to versionJson(sha = "0".repeat(64)))
        compose.setContent { MetroCastTheme { com.metrocast.app.ui.SettingsScreen(r, onChanged = {}, onBack = {}) } }
        compose.onAllNodes(hasScrollAction())[0].performScrollToNode(hasText("UPDATES FROM YOUR PC"))
        compose.onNodeWithText("Your PC's address").performTextInput("10.0.0.5")
        compose.onAllNodes(hasScrollAction())[0].performScrollToNode(hasText("Check for updates"))
        compose.onNodeWithText("Check for updates").performClick()
        compose.waitUntil(5_000) { sees("is ready") }
        compose.onNodeWithText("Install").performClick()
        compose.waitUntil(5_000) { sees("didn't match") }
        // Same version as you have: up to date.
        FakeWmata.bodies = mapOf("version.json" to versionJson(code = 1))
        compose.onNodeWithText("Check for updates").performClick()
        compose.waitUntil(5_000) { sees("up to date") }
        // PC not reachable.
        FakeWmata.bodies = emptyMap()
        compose.onNodeWithText("Check for updates").performClick()
        compose.waitUntil(5_000) { sees("Couldn't reach your PC") }
    }

    @Test fun nightlyCheckAtMostOnceADay() {
        val r = repo()
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val nm = shadowOf(app.getSystemService(android.app.NotificationManager::class.java))
        assertNull(r.dailyUpdateCheck())                       // no address: never asks
        assertTrue(FakeWmata.requests.isEmpty())
        r.prefs.updateAddress = "192.168.1.20"
        FakeWmata.bodies = mapOf("version.json" to versionJson())
        val t0 = 1_800_000_000_000L
        val u = r.dailyUpdateCheck(t0)
        assertNotNull(u)
        // Remembered for the home screen, and announced once (silently).
        assertEquals(u!!.versionCode, r.autoCheckUpdate()?.versionCode)
        val n = nm.allNotifications.single()
        assertEquals("MetroCast ${u.versionName} is ready", n.extras.getString(android.app.Notification.EXTRA_TITLE))
        assertEquals("update", shadowOf(n.contentIntent).savedIntent.getStringExtra(com.metrocast.app.notify.Notifier.OPEN))
        // Again the same night: no asking.
        FakeWmata.requests.clear(); r.dailyUpdateCheck(t0 + 3 * 3600_000L)
        assertTrue(FakeWmata.requests.isEmpty())
        // Next night: asks again, but the same version isn't announced twice.
        app.getSystemService(android.app.NotificationManager::class.java).cancelAll(); r.dailyUpdateCheck(t0 + 24 * 3600_000L)
        assertTrue(FakeWmata.requests.any { "version.json" in it }); assertTrue(nm.allNotifications.isEmpty())
        // Opening the app never asks the PC: it just shows what was found.
        FakeWmata.requests.clear(); r.autoCheckUpdate(); assertTrue(FakeWmata.requests.isEmpty())
        // Once that version is installed (or an older one is listed), it's forgotten.
        r.prefs.updateFound = versionJson(code = 1); assertNull(r.autoCheckUpdate()); assertNull(r.prefs.updateFound)
    }

    @Test fun awayFromHomeItDoesntTry() {
        val r = repo()
        r.prefs.updateAddress = "10.1.1.20"
        FakeWmata.bodies = mapOf("version.json" to versionJson())
        // Someone else's Wi-Fi, then mobile data: never asks.
        for (phone in listOf(listOf("192.168.1.77"), emptyList())) {
            Updater.testPhoneAddresses = phone
            assertNull(r.dailyUpdateCheck())
            assertTrue(FakeWmata.requests.none { "version.json" in it })
        }
        // And it'll try again on the next night at home (the day's turn wasn't used up).
        Updater.testPhoneAddresses = listOf("10.1.1.42")
        assertNotNull(r.dailyUpdateCheck())
        // The button says why when you're away.
        Updater.testPhoneAddresses = listOf("172.16.4.4")
        compose.setContent { MetroCastTheme { com.metrocast.app.ui.SettingsScreen(r, onChanged = {}, onBack = {}) } }
        compose.onAllNodes(hasScrollAction())[0].performScrollToNode(hasText("Check for updates"))
        compose.onNodeWithText("Check for updates").performClick()
        assertTrue(sees("Connect to your home Wi-Fi first")); assertTrue(sees("starting 10.1.1."))
    }

    @Test fun pcOffAtNightTriesOnceMore() {
        val r = repo()
        r.prefs.updateAddress = "192.168.1.20"
        val t0 = 1_800_000_000_000L
        FakeWmata.bodies = emptyMap()                      // server not running
        assertNull(r.dailyUpdateCheck(t0)); assertTrue(r.pcDidntAnswer)
        assertEquals(0L, r.prefs.updateCheckedAt)          // the day isn't used up
        // The retry 30 minutes later: now it answers.
        FakeWmata.bodies = mapOf("version.json" to versionJson())
        assertNotNull(r.dailyUpdateCheck(t0 + 30 * 60_000L, retry = true)); assertTrue(!r.pcDidntAnswer)
        // A retry that also fails does use up the day (no more tries tonight).
        r.prefs.updateCheckedAt = 0; FakeWmata.bodies = emptyMap()
        r.dailyUpdateCheck(t0, retry = true)
        assertEquals(t0, r.prefs.updateCheckedAt)
    }

    @Test fun readyNotificationGoesAwayOnceInstalled() {
        val r = repo()
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val nm = shadowOf(app.getSystemService(android.app.NotificationManager::class.java))
        com.metrocast.app.notify.Notifier.updateReady(app, "1.0")
        assertEquals(1, nm.allNotifications.size)
        // A newer version still waiting: stays.
        r.prefs.updateFound = versionJson()
        com.metrocast.app.notify.Notifier.clearUpdateIfInstalled(app)
        assertEquals(1, nm.allNotifications.size)
        // That version (or older) is what's installed now: gone, and forgotten.
        r.prefs.updateFound = versionJson(code = com.metrocast.app.BuildConfig.VERSION_CODE)
        com.metrocast.app.notify.Notifier.clearUpdateIfInstalled(app)
        assertTrue(nm.allNotifications.isEmpty()); assertNull(r.prefs.updateFound)
    }

    @Test fun scheduledFor1amOnWifiOnly() {
        val r = repo()
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(app)
        val wm = androidx.work.WorkManager.getInstance(app)
        com.metrocast.app.notify.UpdateCheckWorker.sync(app)
        assertTrue(wm.getWorkInfosForUniqueWork(com.metrocast.app.notify.UpdateCheckWorker.NAME).get().none { !it.state.isFinished })
        r.prefs.updateAddress = "192.168.1.20"
        com.metrocast.app.notify.UpdateCheckWorker.sync(app)
        val info = wm.getWorkInfosForUniqueWork(com.metrocast.app.notify.UpdateCheckWorker.NAME).get().single()
        assertEquals(androidx.work.NetworkType.UNMETERED, info.constraints.requiredNetworkType)
        assertEquals(24 * 3600_000L, info.periodicityInfo?.repeatIntervalMillis)
        // The first run is the next 1 AM.
        val now = java.time.LocalDateTime.of(2026, 11, 22, 15, 30)
        assertEquals(java.time.Duration.ofMinutes(9 * 60 + 30), com.metrocast.app.data.Updater.untilOneAm(now))
        assertEquals(java.time.Duration.ofMinutes(30), com.metrocast.app.data.Updater.untilOneAm(now.withHour(0)))
        assertEquals(java.time.Duration.ofHours(24), com.metrocast.app.data.Updater.untilOneAm(now.withHour(1).withMinute(0)))
        // Address cleared: stops checking.
        r.prefs.updateAddress = ""; com.metrocast.app.notify.UpdateCheckWorker.sync(app)
        assertTrue(wm.getWorkInfosForUniqueWork(com.metrocast.app.notify.UpdateCheckWorker.NAME).get().all { it.state.isFinished })
    }

    @Test fun homeShowsWhatTheNightlyCheckFound() {
        val r = repo()
        r.prefs.updateFound = versionJson()
        val u = r.autoCheckUpdate()
        assertNotNull(u)
        var settings = false
        compose.setContent { MetroCastTheme { com.metrocast.app.ui.HomeScreen(com.metrocast.app.ui.UiState(snapshot = r.snapshot(), update = u), r, onRefresh = {}, onOpenSettings = { settings = true }) } }
        compose.onNodeWithText("Update ready on your PC").performClick()
        assertTrue(settings)
    }

    @Test fun notificationOpensSettingsAtUpdates() {
        val r = repo()
        compose.setContent { MetroCastTheme { com.metrocast.app.ui.SettingsScreen(r, onChanged = {}, onBack = {}, scrollTo = "update") } }
        compose.waitUntil(10_000) { sees("UPDATES FROM YOUR PC") }
        assertTrue(sees("once a day, around 1 AM"))
    }

    @Test fun offInOtherCopies() {
        val r = repo(); Updater.testEnabled = false
        r.prefs.updateAddress = "192.168.1.20"
        assertNull(r.dailyUpdateCheck()); assertNull(r.autoCheckUpdate())
        compose.setContent { MetroCastTheme { com.metrocast.app.ui.SettingsScreen(r, onChanged = {}, onBack = {}) } }
        assertTrue(!sees("UPDATES FROM YOUR PC"))
        // The version is at the bottom of Settings.
        compose.onAllNodes(hasScrollAction())[0].performScrollToNode(hasText("MetroCast version", substring = true))
        assertTrue(sees("MetroCast version ${com.metrocast.app.BuildConfig.VERSION_NAME}")); assertTrue(sees("Your copy"))
    }
}
