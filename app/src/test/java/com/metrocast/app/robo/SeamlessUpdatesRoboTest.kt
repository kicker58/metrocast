package com.metrocast.app.robo

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.metrocast.app.data.Repository
import com.metrocast.app.data.UpdateInstaller
import com.metrocast.app.data.Updater
import com.metrocast.app.notify.AppUpdatedReceiver
import com.metrocast.app.notify.UpdateStatusReceiver
import com.metrocast.app.ui.MetroCastTheme
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
import java.io.File

/** Updates without fuss: found, downloaded ahead, installed with as few taps as Android allows. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class SeamlessUpdatesRoboTest {
    @get:Rule val compose = createComposeRule()
    private val apk = "pretend apk bytes, version two".toByteArray()
    private val app get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private fun versionJson(code: Int = 2_000_000_000) =
        """{"versionCode":$code,"versionName":"9.$code","apk":"MetroCast-Updates.apk","sha256":"${Updater.sha256(apk)}","notes":""}"""
    private val installed = mutableListOf<File>()

    private fun repo(): Repository {
        app.getSharedPreferences("metrocast", Context.MODE_PRIVATE).edit().clear().commit()
        Updater.testEnabled = true
        Updater.testPhoneAddresses = listOf("10.1.1.77")
        Updater.testFindPc = { null }
        UpdateInstaller.testInstall = { f -> installed += f; true }
        UpdateInstaller.foreground = false
        FakeWmata.install()
        FakeWmata.liveFeeds = mapOf("MetroCast-Updates.apk" to apk)
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(app.packageManager).setCanRequestPackageInstalls(true)
        return Repository(app)
    }
    @After fun done() {
        Updater.testEnabled = null; Updater.testPhoneAddresses = null; Updater.testFindPc = null
        UpdateInstaller.testInstall = null; UpdateInstaller.foreground = false; FakeWmata.uninstall()
    }
    private val nm get() = shadowOf(app.getSystemService(android.app.NotificationManager::class.java))
    private fun sees(t: String) = compose.onAllNodesWithText(t, substring = true).fetchSemanticsNodes().isNotEmpty()

    @Test fun overnightItDownloadsAndInstallsByItself() {
        val r = repo()
        r.prefs.updateAddress = "10.1.1.20"
        FakeWmata.bodies = mapOf("8765/version.json" to versionJson())
        val info = r.dailyUpdateCheck(1_800_000_000_000L)
        assertNotNull(info)
        // Downloaded ahead, checked, kept until installed…
        val ready = UpdateInstaller.ready(app, info!!)
        assertNotNull(ready); assertTrue(ready!!.readBytes().contentEquals(apk))
        // …and handed to Android's installer: no "tap to install" needed.
        assertEquals(listOf(ready), installed)
        assertTrue("no notification while it installs by itself", nm.allNotifications.isEmpty())
    }

    @Test fun notAllowedYetItSaysReadyInstead() {
        val r = repo()
        shadowOf(app.packageManager).setCanRequestPackageInstalls(false)
        r.prefs.updateAddress = "10.1.1.20"
        FakeWmata.bodies = mapOf("8765/version.json" to versionJson())
        r.dailyUpdateCheck(1_800_000_000_000L)
        assertTrue(installed.isEmpty())
        assertEquals("MetroCast 9.2000000000 is ready", nm.allNotifications.single().extras.getString(Notification.EXTRA_TITLE))
    }

    @Test fun openingTheAppGetsItReadyButDoesntRestartYou() {
        val r = repo()
        r.prefs.updateAddress = "10.1.1.20"
        FakeWmata.bodies = mapOf("8765/version.json" to versionJson())
        val t0 = 1_800_000_000_000L
        val info = r.dailyUpdateCheck(t0, opened = true)
        assertNotNull(info)
        assertNotNull("downloaded ahead", UpdateInstaller.ready(app, info!!))
        assertTrue("not installed under you while you use the app", installed.isEmpty())
        assertNotNull(r.readyUpdate())
        // Every 6 hours at most when opening the app.
        FakeWmata.requests.clear(); r.dailyUpdateCheck(t0 + 2 * 3600_000L, opened = true)
        assertTrue(FakeWmata.seen().none { "version.json" in it })
        r.dailyUpdateCheck(t0 + 7 * 3600_000L, opened = true)
        assertTrue(FakeWmata.seen().any { "version.json" in it })
    }

    @Test fun pcWithANewAddressIsFoundAgain() {
        val r = repo()
        r.prefs.updateAddress = "10.1.1.20"
        FakeWmata.failing = mapOf("10.1.1.20:8765" to java.io.IOException("no answer")) // the router gave the PC a new address
        FakeWmata.bodies = mapOf("10.1.1.31:8765/version.json" to versionJson())
        var searchedFrom: List<String>? = null
        Updater.testFindPc = { phone -> searchedFrom = phone; "10.1.1.31" }
        assertNotNull(r.dailyUpdateCheck(1_800_000_000_000L))
        assertEquals(listOf("10.1.1.77"), searchedFrom)
        assertEquals("10.1.1.31", r.prefs.updateAddress)
        assertEquals(1, installed.size)
    }

    @Test fun findMyPcFromSettings() {
        val r = repo()
        FakeWmata.bodies = mapOf("10.1.1.31:8765/version.json" to versionJson())
        Updater.testFindPc = { "10.1.1.31" }
        compose.setContent { MetroCastTheme { com.metrocast.app.ui.SettingsScreen(r, onChanged = {}, onBack = {}) } }
        compose.onAllNodes(hasScrollAction())[0].performScrollToNode(hasText("Find my PC"))
        compose.onNodeWithText("Find my PC").performClick()
        compose.waitUntil(5_000) { sees("is ready") }
        assertTrue(sees("Found your PC at 10.1.1.31"))
        assertEquals("10.1.1.31", r.prefs.updateAddress)
        // Install: straight to Android's installer.
        compose.onNodeWithText("Install").performClick()
        compose.waitUntil(5_000) { sees("Installing") }
        assertEquals(1, installed.size)
    }

    @Test fun homeBannerInstallsInOneTap() {
        val r = repo()
        r.prefs.updateAddress = "10.1.1.20"
        FakeWmata.bodies = mapOf("8765/version.json" to versionJson())
        val info = r.dailyUpdateCheck(1_800_000_000_000L, opened = true)!!
        val store = androidx.lifecycle.ViewModelStore()
        resetViewModelFactory()
        val vm = androidx.lifecycle.ViewModelProvider(store, androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.getInstance(app))[com.metrocast.app.ui.MainViewModel::class.java]
        try {
            assertTrue("one tap installs the downloaded update", vm.installReadyUpdate())
            assertEquals(listOf(UpdateInstaller.ready(app, info)), installed)
            // Nothing downloaded: the banner opens Settings instead.
            File(app.filesDir, "updates").deleteRecursively()
            assertTrue(!vm.installReadyUpdate())
        } finally { store.clear() }
    }

    @Test fun androidAsksForATap() {
        repo()
        val confirm = Intent("android.content.pm.action.CONFIRM_INSTALL")
        fun status(code: Int) = Intent(UpdateInstaller.ACTION_STATUS).putExtra(PackageInstaller.EXTRA_STATUS, code)
            .putExtra(UpdateInstaller.EXTRA_VERSION, "9.9").putExtra(Intent.EXTRA_INTENT, confirm)
        // In the app: Android's Update button opens right away.
        UpdateInstaller.foreground = true
        UpdateStatusReceiver().onReceive(app, status(PackageInstaller.STATUS_PENDING_USER_ACTION))
        assertEquals("android.content.pm.action.CONFIRM_INSTALL", shadowOf(app).nextStartedActivity?.action)
        // Overnight: a silent notification that opens it.
        UpdateInstaller.foreground = false
        UpdateStatusReceiver().onReceive(app, status(PackageInstaller.STATUS_PENDING_USER_ACTION))
        val n = nm.allNotifications.single()
        assertEquals("MetroCast 9.9 is ready", n.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("android.content.pm.action.CONFIRM_INSTALL", shadowOf(n.contentIntent).savedIntent.action)
        // Didn't install (cancelled): says so in Settings, and the usual "ready" stays.
        UpdateStatusReceiver().onReceive(app, status(PackageInstaller.STATUS_FAILURE_ABORTED).putExtra(PackageInstaller.EXTRA_STATUS_MESSAGE, "Cancelled"))
        assertEquals("Cancelled", com.metrocast.app.data.Prefs(app).updateProblem)
    }

    @Test fun afterUpdatingItTidiesUpAndSaysSo() {
        val r = repo()
        val dir = File(app.filesDir, "updates").apply { mkdirs() }
        File(dir, "MetroCast-1.apk").writeText("old")                                  // installed now: removed
        val keep = File(dir, "MetroCast-2000000000.apk").apply { writeText("newer") } // still newer: kept
        r.prefs.updateProblem = "Cancelled"
        AppUpdatedReceiver().onReceive(app, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        assertTrue(!File(dir, "MetroCast-1.apk").exists()); assertTrue(keep.exists())
        assertNull(r.prefs.updateProblem)
        assertEquals("MetroCast updated to ${com.metrocast.app.BuildConfig.VERSION_NAME}", nm.allNotifications.single().extras.getString(Notification.EXTRA_TITLE))
        // Only the copy with updates reacts.
        app.getSystemService(android.app.NotificationManager::class.java).cancelAll()
        Updater.testEnabled = false
        AppUpdatedReceiver().onReceive(app, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        assertTrue(nm.allNotifications.isEmpty())
    }

    @Test fun searchingOnlyLooksAtHomeAddresses() {
        assertTrue(Updater.isPrivate("10.1.1.7") && Updater.isPrivate("10.0.0.2") && Updater.isPrivate("172.20.1.1"))
        assertTrue(!Updater.isPrivate("8.8.8.8") && !Updater.isPrivate("172.32.0.1") && !Updater.isPrivate("100.64.0.1") && !Updater.isPrivate("x"))
        // On mobile data (or only public addresses): nothing is searched.
        assertNull(Updater.findPc(emptyList())); assertNull(Updater.findPc(listOf("100.70.1.2")))
    }

    @Test fun realInstallerSessionAsksForNoPrompt() {
        repo(); UpdateInstaller.testInstall = null
        val f = File(app.filesDir, "updates/MetroCast-2000000000.apk").apply { parentFile!!.mkdirs(); writeBytes(apk) }
        assertTrue(UpdateInstaller.install(app, f, "9.9"))
        val session = app.packageManager.packageInstaller.mySessions.single()
        assertEquals(app.packageName, session.appPackageName)
        // (Robolectric's installer doesn't keep the "no confirmation" request, so that part is checked on a real phone.)
    }
}
