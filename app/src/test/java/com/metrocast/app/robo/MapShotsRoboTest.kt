package com.metrocast.app.robo

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.metrocast.app.data.Repository
import com.metrocast.app.ui.LineMapScreen
import com.metrocast.app.ui.LiveTrainsScreen
import com.metrocast.app.ui.MetroCastTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Draws the map screens for real and checks they aren't blank. Set -Dmetrocast.shots=<dir> to also
 * save PNGs (used to look at the design); normally nothing is written.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class MapShotsRoboTest {
    @get:Rule val compose = androidx.compose.ui.test.junit4.createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private lateinit var repo: Repository

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.getSharedPreferences("metrocast", Context.MODE_PRIVATE).edit().clear().commit()
        FakeWmata.install()
        repo = Repository(ctx)
        runBlocking { repo.refresh(); repo.refresh() }
    }
    @After fun tearDown() = FakeWmata.uninstall()

    private fun shot(name: String): Bitmap {
        compose.waitForIdle()
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        // Draw the window straight into a bitmap (Robolectric's capture helper can't wait for frames).
        val view = compose.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bmp))
        System.getProperty("metrocast.shots")?.let { dir ->
            File(dir).mkdirs(); File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        // Not blank: plenty of different colours on screen.
        val colors = HashSet<Int>()
        for (y in 0 until bmp.height step 7) for (x in 0 until bmp.width step 7) colors += bmp.getPixel(x, y)
        assertTrue("$name looks blank (${colors.size} colours)", colors.size > 40)
        return bmp
    }

    @Test fun liveTrains() {
        val maps = repo.lineMaps()
        assertTrue(maps.isNotEmpty())
        compose.setContent { MetroCastTheme { LiveTrainsScreen(maps, onBack = {}) } }
        shot("live_trains")
    }

    @Test fun lineMapWithTrip() {
        val maps = repo.lineMaps()
        val trip = repo.snapshot().trips.first()
        compose.setContent { MetroCastTheme { LineMapScreen("SV", maps["SV"], trip, setOf(trip.trip.stationCode), onBack = {}) } }
        shot("line_sv")
    }

    @Test fun lineMapRed() {
        val maps = repo.lineMaps()
        compose.setContent { MetroCastTheme { LineMapScreen("RD", maps["RD"], null, emptySet(), onBack = {}) } }
        shot("line_rd")
    }
}

/** The setup screens drawn for real in light and dark mode: text must stand out from the background. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SetupContrastRoboTest {
    @get:Rule val compose = androidx.compose.ui.test.junit4.createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private fun render(name: String): Bitmap {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.getSharedPreferences("metrocast", Context.MODE_PRIVATE).edit().clear().commit()
        FakeWmata.install()
        val repo = Repository(ctx)
        compose.setContent { MetroCastTheme { com.metrocast.app.ui.OnboardingScreen(repo, onDone = {}) } }
        compose.waitForIdle(); org.robolectric.shadows.ShadowLooper.idleMainLooper()
        val view = compose.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bmp))
        System.getProperty("metrocast.shots")?.let { dir -> File(dir).mkdirs(); File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        FakeWmata.uninstall()
        return bmp
    }

    private fun lum(c: Int): Double {
        fun ch(v: Int) = (v / 255.0).let { if (it <= 0.03928) it / 12.92 else Math.pow((it + 0.055) / 1.055, 2.4) }
        return 0.2126 * ch(android.graphics.Color.red(c)) + 0.7152 * ch(android.graphics.Color.green(c)) + 0.0722 * ch(android.graphics.Color.blue(c))
    }

    /** The most common colour (the background) against the colour that contrasts most with it (the text). */
    private fun contrast(bmp: Bitmap): Double {
        val counts = HashMap<Int, Int>()
        for (y in bmp.height / 4 until bmp.height * 3 / 4 step 3) for (x in 0 until bmp.width step 3) counts.merge(bmp.getPixel(x, y), 1, Int::plus)
        val bg = counts.maxBy { it.value }.key
        // Text: colours covering at least a few hundred samples, furthest in brightness from the background.
        val text = counts.filter { it.value > 150 && it.key != bg }.keys.maxBy { Math.abs(lum(it) - lum(bg)) }
        val (a, b) = listOf(lum(bg), lum(text)).sortedDescending()
        return (a + 0.05) / (b + 0.05)
    }

    @Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
    @Test fun lightModeIsReadable() { val c = contrast(render("setup_light")); assertTrue("contrast $c", c >= 7.0) }

    @Config(sdk = [34], qualifiers = "w411dp-h891dp-night-xxhdpi")
    @Test fun darkModeIsReadable() { val c = contrast(render("setup_dark")); assertTrue("contrast $c", c >= 7.0) }
}

/** The home screen and Bikes screen drawn for real, light and dark: the main text stands out. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreensContrastRoboTest {
    @get:Rule val compose = androidx.compose.ui.test.junit4.createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private fun repo(): Repository {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.getSharedPreferences("metrocast", Context.MODE_PRIVATE).edit().clear().putBoolean("bike_mode", true).commit()
        FakeWmata.install()
        return Repository(ctx).also { runBlocking { it.refresh() } }
    }

    private fun shot(name: String): Bitmap {
        compose.waitForIdle(); org.robolectric.shadows.ShadowLooper.idleMainLooper()
        val view = compose.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bmp))
        System.getProperty("metrocast.shots")?.let { dir -> File(dir).mkdirs(); File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        return bmp
    }

    private fun lum(c: Int): Double {
        fun ch(v: Int) = (v / 255.0).let { if (it <= 0.03928) it / 12.92 else Math.pow((it + 0.055) / 1.055, 2.4) }
        return 0.2126 * ch(android.graphics.Color.red(c)) + 0.7152 * ch(android.graphics.Color.green(c)) + 0.0722 * ch(android.graphics.Color.blue(c))
    }
    private fun contrast(bmp: Bitmap): Double {
        val counts = HashMap<Int, Int>()
        for (y in 0 until bmp.height step 3) for (x in 0 until bmp.width step 3) counts.merge(bmp.getPixel(x, y), 1, Int::plus)
        val bg = counts.maxBy { it.value }.key
        val text = counts.filter { it.value > 150 && it.key != bg }.keys.maxBy { Math.abs(lum(it) - lum(bg)) }
        val (a, b) = listOf(lum(bg), lum(text)).sortedDescending()
        return (a + 0.05) / (b + 0.05)
    }

    private fun home(name: String) {
        val r = repo()
        val state = com.metrocast.app.ui.UiState(snapshot = r.snapshot(), maps = r.lineMaps(), elevators = r.myElevatorOutages())
        compose.setContent { MetroCastTheme { com.metrocast.app.ui.HomeScreen(state, r, onRefresh = {}, onOpenSettings = {}) } }
        val c = contrast(shot(name)); assertTrue("$name contrast $c", c >= 7.0)
        FakeWmata.uninstall()
    }
    private fun bikes(name: String) {
        val r = repo()
        compose.setContent { MetroCastTheme { com.metrocast.app.ui.BikesScreen(r, onBack = {}) } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Elevators working", substring = true).fetchSemanticsNodes().isNotEmpty() || compose.onAllNodesWithText("Elevator out", substring = true).fetchSemanticsNodes().isNotEmpty() }
        val c = contrast(shot(name)); assertTrue("$name contrast $c", c >= 7.0)
        FakeWmata.uninstall()
    }

    @Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi") @Test fun homeLight() = home("home_light")
    @Config(sdk = [34], qualifiers = "w411dp-h891dp-night-xxhdpi") @Test fun homeDark() = home("home_dark")
    @Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi") @Test fun bikesLight() = bikes("bikes_light")
    @Config(sdk = [34], qualifiers = "w411dp-h891dp-night-xxhdpi") @Test fun bikesDark() = bikes("bikes_dark")
}
