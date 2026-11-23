package com.metrocast.app.robo

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.metrocast.app.data.Repository
import com.metrocast.app.data.Trip
import com.metrocast.app.ui.MetroCastTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDateTime
import androidx.compose.ui.unit.dp

/**
 * Screenshots to share (only when asked: -PshareShots=<dir>). Uses the public app's own starting setup
 * (Metro Center ⇄ Union Station, Washington, DC weather) and recorded real Metro data: nothing personal.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ShareShotsRoboTest {
    @get:Rule val compose = androidx.compose.ui.test.junit4.createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val dir = System.getProperty("metrocast.shareshots")
    private val monday = LocalDateTime.of(2026, 11, 23, 8, 0)

    private fun repo(): Repository {
        assumeTrue(dir != null)
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("America/New_York")) // DC time on screen
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.getSharedPreferences("metrocast", Context.MODE_PRIVATE).edit().clear()
            .putBoolean("onboarded", true).putString("home_map_line", "RD").commit()
        FakeWmata.install()
        com.metrocast.app.data.Stations.testDefaults = listOf(
            Trip(id = "default-going", label = Trip.GOING, lineCode = "RD", stationCode = "A01", stationName = "Metro Center", towardCode = "B03", towardName = "Union Station"),
            Trip(id = "default-back", label = Trip.BACK, lineCode = "RD", stationCode = "B03", stationName = "Union Station", towardCode = "A01", towardName = "Metro Center"),
        )
        com.metrocast.app.data.Prefs.DEFAULT_LOCATION = Triple("Washington, DC", 38.8983, -77.0281)
        // Realistic ride times for the stations shown (the recorded data has one trip's time for every pair).
        val rides = mapOf(
            "A01" to "B03" to (6 to 1.6), "B03" to "A01" to (6 to 1.6), "A01" to "C10" to (19 to 5.6), "C01" to "C10" to (19 to 5.6), "C01" to "D13" to (24 to 10.3), "C01" to "C13" to (22 to 7.9), "C01" to "C09" to (14 to 4.2), "C01" to "J03" to (34 to 15.0), "C01" to "D03" to (4 to 0.9),
            "A01" to "D13" to (24 to 10.3), "A01" to "C13" to (22 to 7.9), "A01" to "C09" to (14 to 4.2),
            "A01" to "D03" to (4 to 0.9), "A01" to "J03" to (34 to 15.0), "A01" to "E09" to (20 to 8.6),
            "A01" to "E10" to (23 to 10.2), "A01" to "B08" to (17 to 7.3), "A01" to "A14" to (29 to 14.2),
        )
        FakeWmata.bodies = rides.map { (pair, v) ->
            val (from, to) = pair; val (min, miles) = v
            val peak = minOf(6.75, 2.50 + maxOf(0.0, miles - 3) * 0.35)
            "FromStationCode=$from&ToStationCode=$to" to """{"StationToStationInfos":[{"SourceStation":"$from","DestinationStation":"$to","CompositeMiles":$miles,"RailTime":$min,"RailFare":{"PeakTime":${"%.2f".format(peak)},"OffPeakTime":2.50,"SeniorDisabled":1.25}}]}"""
        }.toMap()
        return Repository(ctx).also { runBlocking { it.refresh() } }
    }
    @After fun done() { FakeWmata.uninstall(); TestDefaults.use() }

    private fun shot(name: String) {
        compose.waitForIdle(); org.robolectric.shadows.ShadowLooper.idleMainLooper()
        val view = compose.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bmp))
        File(dir!!).mkdirs()
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun homeAndMaps(suffix: String) {
        val r = repo()
        val snap = r.snapshot(); val maps = r.lineMaps()
        val screen = mutableStateOf(0)
        compose.setContent { MetroCastTheme {
            when (screen.value) {
                0 -> com.metrocast.app.ui.HomeScreen(com.metrocast.app.ui.UiState(snapshot = snap, maps = maps), r, onRefresh = {}, onOpenSettings = {})
                1 -> com.metrocast.app.ui.LineMapScreen("RD", maps["RD"], snap.trips.firstOrNull(), emptySet(), onBack = {})
                else -> com.metrocast.app.ui.LiveTrainsScreen(maps, onBack = {})
            }
        } }
        shot("1-home-$suffix")
        screen.value = 1; shot("2-red-line-live-$suffix")
        screen.value = 2; shot("3-every-train-live-$suffix")
    }

    @Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi") @Test fun light() = homeAndMaps("light")
    @Config(sdk = [34], qualifiers = "w411dp-h891dp-night-xxhdpi") @Test fun dark() = homeAndMaps("dark")

    @Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi") @Test fun planner() {
        val r = repo()
        r.setPlan("A01", "C10"); runBlocking { r.refresh() } // Metro Center → Reagan National Airport
        compose.setContent { MetroCastTheme { com.metrocast.app.ui.PlannerScreen(plan = r.snapshot().plan, repo = r, onChange = { _, _ -> }, onBack = {}, onStartLive = {}, onOpenRail = {}) } }
        shot("4-trip-planner")
    }

    @Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi") @Test fun trains() {
        val r = repo()
        compose.setContent { MetroCastTheme { com.metrocast.app.ui.RailScreen(r, onBack = {}, onPlan = { _, _ -> }, clock = { monday }) } }
        shot("5-amtrak-marc-vre")
        compose.onNodeWithText("Train number or where it's going").performTextInput("New York")
        compose.waitForIdle()
        compose.onAllNodesWithText("Northeast Regional", substring = true)[0].performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("BEST:", substring = true).fetchSemanticsNodes().isNotEmpty() }
        shot("6-catch-your-train")
    }

    @Config(sdk = [34], qualifiers = "w411dp-h891dp-night-xxhdpi") @Test fun airport() {
        val r = repo()
        compose.setContent { MetroCastTheme { com.metrocast.app.ui.AirportScreen(r, onBack = {}, onPlan = { _, _ -> }, clock = { monday }) } }
        compose.onNodeWithText("Time").performTextInput("1:30")
        compose.onNodeWithText("PM").performClick()
        compose.waitForIdle()
        shot("7-airports")
    }

    // ---- more screens
    @Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi") @Test fun more() {
        val r = repo()
        val screen = mutableStateOf(0)
        compose.setContent { MetroCastTheme {
            when (screen.value) {
                0 -> com.metrocast.app.ui.TrackWorkScreen(r, onBack = {}, today = monday.toLocalDate())
                1 -> com.metrocast.app.ui.RestroomsScreen(onBack = {})
                2 -> com.metrocast.app.ui.BikesScreen(r, onBack = {})
                3 -> com.metrocast.app.ui.ScenicScreen(onBack = {})
                4 -> com.metrocast.app.ui.SpecialScreen(r, onBack = {})
                5 -> com.metrocast.app.ui.IssuesScreen(r, onBack = {})
                else -> com.metrocast.app.ui.SettingsScreen(r, onChanged = {}, onBack = {})
            }
        } }
        val names = listOf("8-track-work-calendar", "9-restrooms", "10-bikes", "11-scenic-rides", "12-special-trains", "13-train-issues", "14-settings")
        names.forEachIndexed { i, n -> screen.value = i; compose.waitForIdle(); Thread.sleep(300); shot(n) }
    }

    @Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi") @Test fun setup() {
        val r = repo(); r.prefs.onboarded = false
        compose.setContent { MetroCastTheme { com.metrocast.app.ui.OnboardingScreen(r, onDone = {}) } }
        shot("15-first-run-setup")
    }

    // ---- home-screen widgets, drawn as Android draws them (RemoteViews) on a wallpaper
    @OptIn(androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi::class)
    private fun widgets(suffix: String) {
        val r = repo()
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val dm = ctx.resources.displayMetrics
        fun px(dp: Int) = (dp * dm.density).toInt()
        val snap = com.metrocast.app.widget.Widgets.forWidget(com.metrocast.app.widget.Widgets.snapshot(r, System.currentTimeMillis()), null)
        // Each widget's own content, exactly as its provideGlance draws it.
        val list: List<Triple<String, androidx.compose.ui.unit.DpSize, @androidx.compose.runtime.Composable () -> Unit>> = listOf(
            Triple("widget-main", androidx.compose.ui.unit.DpSize(340.dp, 330.dp)) { androidx.glance.GlanceTheme { com.metrocast.app.widget.WidgetBody(snap, false) } },
            Triple("widget-next-train", androidx.compose.ui.unit.DpSize(340.dp, 90.dp)) { androidx.glance.GlanceTheme { com.metrocast.app.widget.NextTrainBody(snap, false) } },
            Triple("widget-pylon", androidx.compose.ui.unit.DpSize(170.dp, 170.dp)) { androidx.glance.GlanceTheme { com.metrocast.app.widget.PylonBody(snap, false) } },
            Triple("widget-track", androidx.compose.ui.unit.DpSize(340.dp, 125.dp)) { androidx.glance.GlanceTheme { com.metrocast.app.widget.TrackBody(snap, false) } },
        )
        val views = list.map { (name, size, content) ->
            val rv = runBlocking { androidx.glance.appwidget.GlanceRemoteViews().compose(ctx, size, content = content).remoteViews }
            val host = android.widget.FrameLayout(ctx)
            val v: android.view.View = rv.apply(ctx, host)
            val wpx = px(size.width.value.toInt()); val hpx = px(size.height.value.toInt())
            v.measure(android.view.View.MeasureSpec.makeMeasureSpec(wpx, android.view.View.MeasureSpec.EXACTLY), android.view.View.MeasureSpec.makeMeasureSpec(hpx, android.view.View.MeasureSpec.EXACTLY))
            v.layout(0, 0, wpx, hpx)
            Triple(name, v, wpx to hpx)
        }
        // One "home screen" with all four, plus each on its own.
        val W = px(411); val gap = px(16)
        val H = views.sumOf { it.third.second } + gap * (views.size + 1)
        val home = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(home)
        val dark = suffix == "dark"
        c.drawPaint(android.graphics.Paint().apply {
            shader = android.graphics.LinearGradient(0f, 0f, W.toFloat(), H.toFloat(),
                if (dark) 0xFF101826.toInt() else 0xFF6A8FD8.toInt(), if (dark) 0xFF2A1F3D.toInt() else 0xFFB7C9F0.toInt(), android.graphics.Shader.TileMode.CLAMP)
        })
        var y = gap
        for ((name, v, sz) in views) {
            val single = Bitmap.createBitmap(sz.first, sz.second, Bitmap.Config.ARGB_8888)
            v.draw(android.graphics.Canvas(single))
            File(dir!!).mkdirs()
            File(dir, "$name-$suffix.png").outputStream().use { single.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val x = (W - sz.first) / 2
            c.save(); c.translate(x.toFloat(), y.toFloat()); v.draw(c); c.restore()
            y += sz.second + gap
        }
        File(dir, "16-widgets-$suffix.png").outputStream().use { home.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi") @Test fun widgetsLight() = widgets("light")
    @Config(sdk = [34], qualifiers = "w411dp-h891dp-night-xxhdpi") @Test fun widgetsDark() = widgets("dark")
}
