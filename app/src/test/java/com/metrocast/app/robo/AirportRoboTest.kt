package com.metrocast.app.robo

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextClearance
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.metrocast.app.data.FlightProvider
import com.metrocast.app.data.Repository
import com.metrocast.app.ui.AirportScreen
import com.metrocast.app.ui.MetroCastTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDateTime

/** Menu → Airports: Reagan & Dulles, with a typed-in time or a flight followed live. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AirportRoboTest {
    @get:Rule val compose = androidx.compose.ui.test.junit4.createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val monday = LocalDateTime.of(2026, 11, 23, 8, 0)
    private val avs = File("src/test/resources/flights/aviationstack.json").readText()
    private val fa = File("src/test/resources/flights/flightaware.json").readText()

    private fun repo(): Repository {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.getSharedPreferences("metrocast", Context.MODE_PRIVATE).edit().clear().commit()
        FakeWmata.install()
        return Repository(ctx).also { runBlocking { it.refresh() } }
    }
    @After fun done() = FakeWmata.uninstall()
    private fun sees(t: String) = compose.onAllNodesWithText(t, substring = true).fetchSemanticsNodes().isNotEmpty()
    private fun page() = compose.onAllNodes(hasScrollAction())[0]
    private fun show(r: Repository, onPlan: (String, String) -> Unit = { _, _ -> }) =
        compose.setContent { MetroCastTheme { AirportScreen(r, onBack = {}, onPlan = onPlan, clock = { monday }) } }

    @Test fun typedFlightTimeGivesLeaveBy() {
        val r = repo()
        var planned: Pair<String, String>? = null
        show(r) { f, t -> planned = f to t }
        // No key yet: says how to follow a flight live.
        assertTrue(sees("Add a flight tracking key in Settings"))
        assertTrue(sees("Set your flight time"))
        // Type it, pick AM/PM, right on the screen.
        compose.onNodeWithText("Time").performTextInput("1275")      // not a time
        assertTrue(sees("Type the time"))
        compose.onNodeWithText("Time").performTextClearance()
        compose.onNodeWithText("Time").performTextInput("12:00")
        compose.onNodeWithText("PM").performClick()
        compose.onNodeWithText("Tomorrow").performClick()
        assertTrue(sees("at 12:00 PM"))
        compose.waitForIdle()
        assertTrue(sees("LEAVE WIEHLE-RESTON EAST BY")); assertTrue(sees("12:00 PM flight from Reagan National"))
        assertTrue(sees("2 hr early")); assertTrue(sees("6 walk to the terminal"))
        // International adds an hour.
        page().performScrollToNode(hasText("International flight (+1 hour)"))
        compose.onNodeWithText("International flight (+1 hour)").performClick() // the row label; tap the switch next
        compose.onAllNodes(androidx.compose.ui.test.isToggleable()).onFirst().performClick()
        compose.waitForIdle()
        assertTrue(sees("3 hr early"))
        // Dulles: longer walk.
        compose.onNodeWithText("Dulles (IAD)").performClick(); compose.waitForIdle()
        assertTrue(sees("10 walk to the terminal")); assertTrue(sees("flight from Dulles"))
        page().performScrollToNode(hasText("Plan the Metro ride", substring = true))
        compose.onNodeWithText("Plan the Metro ride to Dulles").performClick()
        assertEquals("N06" to "N10", planned)
        // How early is remembered.
        page().performScrollToNode(hasText("1½ hr")); compose.onNodeWithText("1½ hr").performClick()
        assertEquals(90, r.prefs.airportEarlyMin)
    }

    @Test fun followFlightWithAviationStack() {
        val r = repo()
        r.prefs.aviationStackKey = "test-key"
        // Free plan: https refused, so it asks again over http.
        FakeWmata.bodies = mapOf(
            "https://api.aviationstack.com" to """{"error":{"code":"https_access_restricted","message":"Access Restricted"}}""",
            "http://api.aviationstack.com" to avs,
        )
        show(r)
        assertTrue(sees("Uses AviationStack"))
        compose.onNodeWithText("Airline").performTextInput("Amer")
        compose.onNodeWithText("American (AA)").performClick()
        compose.onNodeWithText("Flight #").performTextInput("1234")
        compose.onNodeWithText("Find AA 1234").performClick()
        compose.waitUntil(5_000) { sees("Delayed 25 min") }
        assertTrue(sees("Gate 23")); assertTrue(sees("Terminal 2")); assertTrue(sees("ORD"))
        assertTrue(FakeWmata.seen().any { it.startsWith("http://api.aviationstack.com") && "flight_iata=AA1234" in it })
        // The plan uses the flight's own time; the delay is mentioned.
        assertTrue(sees("1:05 PM flight from Reagan National"))
        assertTrue(sees("running 25 min late"))
    }

    @Test fun followFlightWithFlightAwareFillsInDulles() {
        val r = repo()
        r.prefs.flightAwareKey = "fa-key"
        FakeWmata.bodies = mapOf("aeroapi.flightaware.com" to fa)
        show(r)
        assertTrue(sees("Uses FlightAware"))
        compose.onNodeWithText("Flight #").performTextInput("UA 2051")
        compose.onNodeWithText("Find UA 2051").performClick()
        compose.waitUntil(5_000) { sees("San Francisco") }
        assertTrue(sees("Gate C14")); assertTrue(sees("Delayed 40 min"))
        // It leaves from Dulles, so that's the airport now.
        assertTrue(sees("5:10 PM flight from Dulles")); assertTrue(sees("10 walk to the terminal"))
        // Bad key: says so.
        FakeWmata.failing = mapOf("aeroapi" to com.metrocast.app.data.HttpException(401))
        compose.onNodeWithText("Check now").performClick()
        compose.waitForIdle()
        assertTrue(sees("Gate C14")) // keeps what it had
    }

    @Test fun bothKeysPickOneInSettings() {
        val r = repo()
        r.prefs.aviationStackKey = "a"; r.prefs.flightAwareKey = "b"
        assertEquals(FlightProvider.AVIATIONSTACK, r.prefs.flightProvider)
        r.prefs.flightProvider = FlightProvider.FLIGHTAWARE
        assertEquals(FlightProvider.FLIGHTAWARE, r.prefs.flightProvider)
        // Chosen one without a key falls back to the one that has one.
        r.prefs.flightAwareKey = ""
        assertEquals(FlightProvider.AVIATIONSTACK, r.prefs.flightProvider)
        compose.setContent { MetroCastTheme { com.metrocast.app.ui.SettingsScreen(r, onChanged = {}, onBack = {}) } }
        page().performScrollToNode(hasText("Flight tracking"))
        assertTrue(sees("Flight tracking"))
        page().performScrollToNode(hasText("Get a FlightAware AeroAPI key"))
        assertTrue(sees("Get a AviationStack key") || sees("AVIATIONSTACK"))
    }

    @Test fun randomTypingIntoTheTimeBox() {
        val r = repo()
        show(r)
        val rnd = kotlin.random.Random(7)
        repeat(40) {
            compose.onNodeWithText("Time").performTextClearance()
            val t = String(CharArray(rnd.nextInt(0, 7)) { "0123456789:.ap ".random(rnd) })
            compose.onNodeWithText("Time").performTextInput(t)
            if (rnd.nextBoolean()) compose.onNodeWithText(if (rnd.nextBoolean()) "AM" else "PM").performClick()
            // Either a real time with a leave-by, or a hint — never a crash or a wrong time.
            assertTrue(sees("Flight leaves") || sees("Type the time"))
            if (sees("Flight leaves")) assertTrue(sees("LEAVE WIEHLE-RESTON EAST BY"))
        }
    }

    @Test fun noKeyCardOpensFlightTrackingSettings() {
        val r = repo()
        var settings = false
        compose.setContent { MetroCastTheme { AirportScreen(r, onBack = {}, onPlan = { _, _ -> }, onOpenSettings = { settings = true }, clock = { monday }) } }
        compose.onNodeWithText("✈️ Follow your flight live  ›").performClick()
        assertTrue(settings)
    }

    @Test fun settingsOpensAtFlightTracking() {
        val r = repo()
        compose.setContent { MetroCastTheme { com.metrocast.app.ui.SettingsScreen(r, onChanged = {}, onBack = {}, scrollTo = "flights") } }
        compose.waitUntil(10_000) { sees("Flight tracking") }
        compose.onNodeWithText("Flight tracking").assertIsDisplayed()
    }

    @Test fun inTheMenu() {
        val r = repo()
        assertTrue(r.prefs.menuItems.any { it.first == com.metrocast.app.data.MenuItem.AIRPORTS && it.second })
        var opened = false
        compose.setContent { MetroCastTheme { com.metrocast.app.ui.HomeScreen(com.metrocast.app.ui.UiState(snapshot = r.snapshot()), r, onRefresh = {}, onOpenSettings = {}, onOpenAirports = { opened = true }) } }
        compose.onNodeWithContentDescription("Menu").performClick()
        compose.onNodeWithText("Airports").performClick()
        assertTrue(opened)
    }

    private fun contrastOf(name: String): Double {
        val r = repo(); r.prefs.flightAwareKey = "k"
        FakeWmata.bodies = mapOf("aeroapi.flightaware.com" to fa)
        show(r)
        compose.onNodeWithText("Flight #").performTextInput("UA2051"); compose.onNodeWithText("Find UA 2051").performClick()
        compose.waitUntil(5_000) { sees("San Francisco") }
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
    @Test fun readableLight() { val c = contrastOf("airport_light"); assertTrue("contrast $c", c >= 7.0) }
    @Config(sdk = [34], qualifiers = "w411dp-h891dp-night-xxhdpi") @Test fun readableDark() { val c = contrastOf("airport_dark"); assertTrue("contrast $c", c >= 7.0) }
}
