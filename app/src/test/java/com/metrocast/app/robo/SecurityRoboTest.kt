package com.metrocast.app.robo

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.metrocast.app.data.CrashLog
import com.metrocast.app.data.Prefs
import com.metrocast.app.data.Updater
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/** Security checks: what other apps can reach, what's kept, what's written down, what's accepted. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SecurityRoboTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun crashLogNeverHoldsYourKeys() {
        val p = Prefs(ctx)
        val wmata = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6"; val flight = "fa-key-0123456789abcdef"
        p.apiKey = wmata; p.aviationStackKey = flight
        CrashLog.clear(ctx)
        val boom = java.io.IOException("GET https://api.aviationstack.com/v1/flights?access_key=$flight&flight_iata=UA1 failed; header api_key=$wmata",
            IllegalStateException("token=sekret123 key=$wmata"))
        CrashLog.record(ctx, boom)
        val log = CrashLog.text(ctx)
        assertTrue("the crash itself is kept", "IOException" in log && "aviationstack" in log)
        for (secret in listOf(wmata, flight, "sekret123")) assertTrue("leaked: $secret", secret !in log)
        assertTrue(CrashLog.redact("x?access_key=abc&y=1", emptyList()) == "x?access_key=•••&y=1")
        CrashLog.clear(ctx)
    }

    @Test fun onlyTheLauncherAndWidgetsAreOpenToOtherApps() {
        val flags = PackageManager.GET_ACTIVITIES or PackageManager.GET_RECEIVERS or PackageManager.GET_SERVICES or PackageManager.GET_PROVIDERS
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, flags)
        val open = (info.activities.orEmpty().toList() + info.receivers.orEmpty() + info.services.orEmpty() + info.providers.orEmpty())
            .filter { it.exported && (it as? android.content.pm.ComponentInfo)?.let { c -> (c as? android.content.pm.ActivityInfo)?.permission ?: (c as? android.content.pm.ServiceInfo)?.permission } == null }
            .map { it.name.substringAfterLast('.') }.toSet()
        val allowed = setOf("MainActivity", "WidgetConfigActivity", "MetroWidgetReceiver", "PylonWidgetReceiver", "NextTrainWidgetReceiver", "TrackWidgetReceiver") +
            setOf("PreviewActivity", "ComponentActivity") // test builds only (Compose's preview and test tools); build_package.sh checks the real APKs
        assertTrue("open to other apps without a permission: ${open - allowed}", (open - allowed).isEmpty())
        // The update receivers and file sharing are MetroCast's alone.
        val mine = (info.receivers.orEmpty().toList() + info.providers.orEmpty()).filter { it.name.contains("Update") || it.name.contains("FileProvider") }
        assertTrue(mine.isNotEmpty() && mine.none { it.exported })
    }

    @Test fun widgetSetupOnlyForMetroCastWidgets() {
        // Another app opening it with a made-up widget number: it closes without changing anything.
        val i = Intent(ctx, com.metrocast.app.widget.WidgetConfigActivity::class.java).putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID, 4242)
        val sc = androidx.test.core.app.ActivityScenario.launchActivityForResult<com.metrocast.app.widget.WidgetConfigActivity>(i)
        assertEquals(android.app.Activity.RESULT_CANCELED, sc.result.resultCode)
        assertNull(Prefs(ctx).widgetTrips(4242))
    }

    @Test fun nothingIsBackedUpOrCopiedOff() {
        val app = ctx.packageManager.getApplicationInfo(ctx.packageName, 0)
        assertTrue("no Android backup", app.flags and android.content.pm.ApplicationInfo.FLAG_ALLOW_BACKUP == 0)
        val rules = File("src/main/res/xml/data_extraction_rules.xml").readText()
        for (part in listOf("cloud-backup", "device-transfer")) assertTrue(rules.substringAfter("<$part>").substringBefore("</$part>").contains("""domain="sharedpref" path="."""))
    }

    @Test fun plainHttpOnlyInTheUpdatesCopy() {
        // The usual copy: https only (except AviationStack's free plan, which has no https).
        val normal = File("src/main/res/xml/network_security_config.xml").readText()
        assertTrue(normal.contains("""base-config cleartextTrafficPermitted="false""""))
        assertTrue(Regex("<domain[^>]*>([^<]+)</domain>").findAll(normal).map { it.groupValues[1] }.toList() == listOf("api.aviationstack.com"))
        // Every MetroCast service address is https.
        val src = File("src/main/java").walkTopDown().filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
        val plain = Regex(""""http://([a-z0-9.-]+)""").findAll(src).map { it.groupValues[1] }.toSet()
        // (Only your PC on the home network, by its number, like the 192.168.1.20 example: never a named service.)
        assertTrue("plain http to: $plain", plain.all { it.isEmpty() || it == "\$h" || Regex("""\d+\.\d+\.\d+\.\d+""").matches(it) })
    }

    @Test fun updatesOnlyAcceptTheirOwnFile() {
        fun json(apk: String) = """{"versionCode":2,"versionName":"9","apk":"$apk","sha256":"${"0".repeat(64)}","notes":""}"""
        for (bad in listOf("../MetroCast.apk", "sub/MetroCast.apk", "..\\\\x.apk", "MetroCast.exe", "http://evil.example/x.apk", "/sdcard/x.apk", "", "x.apk/"))
            assertNull("accepted \"$bad\"", Updater.parse(json(bad)))
        assertTrue(Updater.parse(json("MetroCast-Updates.apk")) != null)
        for (bad in listOf("", "{", "[]", "null", """{"versionCode":"x"}""", "<html>")) assertNull(Updater.parse(bad))
        // Only home-network addresses are ever searched for your PC.
        assertTrue(listOf("8.8.8.8", "1.1.1.1", "100.64.0.1", "172.15.0.1", "172.32.0.1", "192.169.0.1", "localhost", "").none { Updater.isPrivate(it) })
    }

    @Test fun downloadsAreCapped() {
        // A response larger than allowed is refused, not stored.
        com.metrocast.app.data.Http.testBytes = { ByteArray(0) }
        try { assertEquals(0, com.metrocast.app.data.Http.bytes("https://example.invalid/x").size) } finally { com.metrocast.app.data.Http.testBytes = null }
        val flood = "{\"Trains\":[" + List(30_000) { """{"Car":"8","Destination":"X","DestinationCode":"A01","Group":"1","Line":"RD","LocationCode":"A01","Min":"3"}""" }.joinToString(",") + "]}"
        assertTrue(flood.length > 2_000_000)
        com.metrocast.app.data.Http.testResponder = { flood }
        try {
            val r = runCatching { com.metrocast.app.data.WmataApi.predictionsRaw(listOf("A01"), "k") }
            assertTrue("an absurd answer is refused", r.isFailure)
        } finally { com.metrocast.app.data.Http.testResponder = null }
    }
}
