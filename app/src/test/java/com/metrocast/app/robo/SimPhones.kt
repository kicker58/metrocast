package com.metrocast.app.robo

import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.metrocast.app.data.CrashLog
import com.metrocast.app.ui.MainActivity
import com.metrocast.app.ui.Net
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.annotation.Config
import java.io.File
import kotlin.random.Random

/**
 * Simulated phones (only with -Psim=<seconds>): the real app, for minutes at a time — random taps,
 * whole rides (start a trip, trains move stop by stop, board, transfer, arrive), WMATA trouble
 * (position feed down, errors, garbage, offline), the Trains/Airports screens and settings.
 * Fails on any crash; writes everything odd it sees to a findings file.
 */
abstract class SimPhone(private val name: String, private val seed: Int) {
    private val fake = object : TestRule {
        override fun apply(base: Statement, d: Description) = object : Statement() {
            override fun evaluate() {
                ApplicationProvider.getApplicationContext<Context>()
                    .getSharedPreferences("metrocast", Context.MODE_PRIVATE).edit().clear().putBoolean("onboarded", true).commit()
                FakeWmata.install(); resetViewModelFactory()
                base.evaluate()
            }
        }
    }
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(fake).around(compose)
    @After fun tearDown() { FakeWmata.uninstall(); Net.testOnline = null }

    private val rnd = Random(seed)
    private val log = ArrayList<String>()
    private val findings = LinkedHashMap<String, String>() // what → where first seen
    private val counts = HashMap<String, Int>()

    private fun texts(): List<String> = compose.onAllNodes(SemanticsMatcher("any") { true }, useUnmergedTree = true).fetchSemanticsNodes()
        .flatMap { n -> (n.config.getOrNull(SemanticsProperties.Text)?.map { it.text } ?: emptyList()) + (n.config.getOrNull(SemanticsProperties.EditableText)?.let { listOf(it.text) } ?: emptyList()) }
    private fun sees(t: String) = compose.onAllNodesWithText(t, substring = true).fetchSemanticsNodes().isNotEmpty()
    private fun idle() { compose.waitForIdle(); org.robolectric.shadows.ShadowLooper.idleMainLooper() }
    private fun note(what: String) { counts.merge(what, 1, Int::plus); findings.putIfAbsent(what, log.takeLast(6).joinToString(" → ")) }

    private fun tap(n: androidx.compose.ui.semantics.SemanticsNode, label: String) {
        log += label
        try { compose.runOnUiThread { n.config.getOrNull(SemanticsActions.OnClick)?.action?.invoke() } }
        catch (e: Throwable) { throw AssertionError("[$name] CRASH tapping \"$label\" after: ${log.takeLast(15)}", e) }
        idle(); check()
    }
    private fun tapText(t: String): Boolean {
        val n = compose.onAllNodes(hasText(t, substring = true) and hasClickAction()).fetchSemanticsNodes().firstOrNull() ?: return false
        tap(n, t); return true
    }
    private fun back() { log += "back"; compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }; idle(); check() }
    private fun home() { repeat(10) { if (!sees("Updated") || compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("Menu")).fetchSemanticsNodes().isEmpty()) back() } }
    private fun menu(item: String): Boolean {
        home()
        val m = compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("Menu")).fetchSemanticsNodes().firstOrNull() ?: return false
        tap(m, "☰"); return tapText(item)
    }
    private fun label(n: androidx.compose.ui.semantics.SemanticsNode) =
        n.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ")?.take(40) ?: n.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ") ?: "?"

    /** Anything on screen that a person shouldn't see. */
    private fun check() {
        val all = texts()
        for (t in all) {
            if (Regex("\\bnull\\b").containsMatchIn(t)) note("shows \"null\": \"${t.take(80)}\"")
            if ("NaN" in t || "Infinity" in t) note("shows NaN/Infinity: \"${t.take(80)}\"")
            if (Regex("(^|[^\\w])-\\d+ ?(min|m)\\b").containsMatchIn(t)) note("negative minutes: \"${t.take(80)}\"")
            if (Regex("Exception|java\\.|kotlin\\.|at com\\.metrocast").containsMatchIn(t)) note("shows an error trace: \"${t.take(80)}\"")
            if (Regex("\\{\\s*\"|\\[\\s*\\{").containsMatchIn(t)) note("shows raw JSON: \"${t.take(80)}\"")
            if (t.length > 600) note("very long text (${t.length} chars): \"${t.take(60)}…\"")
        }
        val crashes = CrashLog.entries(ApplicationProvider.getApplicationContext())
        if (crashes.isNotEmpty()) throw AssertionError("[$name] crash noted: ${crashes.first().take(400)} after ${log.takeLast(15)}")
    }

    // ---- scenarios
    private fun randomTaps(k: Int) = repeat(k) {
        val nodes = compose.onAllNodes(hasClickAction() and SemanticsMatcher("enabled") { !it.config.contains(SemanticsProperties.Disabled) }).fetchSemanticsNodes()
        if (rnd.nextInt(9) == 0) back() else if (nodes.isNotEmpty()) nodes[rnd.nextInt(nodes.size)].let { tap(it, label(it)) }
    }

    private val stations = listOf("Navy Yard", "Metro Center", "Union Station", "Rosslyn", "Pentagon", "Gallery Pl", "L'Enfant", "Shady Grove", "Ashburn", "Largo", "Huntington", "Vienna", "Dupont", "Anacostia", "Greenbelt", "Tysons", "Crystal City", "Fort Totten")

    private fun ride() {
        if (!menu("Trip planner")) return
        idle()
        if (!tapText("Choose where you're going") && !tapText("TO")) return
        compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().firstOrNull() ?: return
        val dest = stations[rnd.nextInt(stations.size)]
        compose.onAllNodes(hasSetTextAction())[0].performTextInput(dest.take(5)); idle()
        val hit = compose.onAllNodes(hasText(dest.take(5), substring = true) and hasClickAction()).fetchSemanticsNodes()
        if (hit.isEmpty()) { note("station search found nothing for \"${dest.take(5)}\""); back(); return }
        tap(hit.first(), "pick $dest")
        if (!sees("Start live trip")) { log += "no route to $dest"; return }
        tapText("Start live trip")
        if (sees("No, I'll tap the button")) tapText("No, I'll tap the button")
        // The ride: trains move (recorded snapshots 1→5), board, maybe transfer, then end.
        for (snap in 1..5) {
            FakeWmata.snapshot = snap; compose.activity.runOnUiThread { }
            Thread.sleep(200); idle(); check()
            if (sees("I'm on the train") || sees("BOARD")) { tapText("I'm on the train") || tapText("BOARD") }
            if (rnd.nextInt(3) == 0 && sees("I'm off")) tapText("I'm off")
            if (rnd.nextInt(4) == 0) randomTaps(2)
        }
        FakeWmata.snapshot = 1
        if (!tapText("End trip")) home()
    }

    private fun trouble() {
        when (rnd.nextInt(5)) {
            0 -> FakeWmata.bodies = mapOf("TrainPositions?" to """{"TrainPositions":[]}""")          // WMATA's feed down
            1 -> FakeWmata.failing = mapOf("GetPrediction" to com.metrocast.app.data.HttpException(500))
            2 -> FakeWmata.bodies = mapOf("GetPrediction" to "{", "Incidents" to "<html>oops</html>")   // garbage
            3 -> Net.testOnline = false                                                                 // offline
            else -> FakeWmata.failing = mapOf("api.wmata.com" to java.io.IOException("timeout"))
        }
        log += "trouble"
        home(); compose.onAllNodes(hasClickAction()).fetchSemanticsNodes() // refreshes happen on their own
        repeat(3) { if (rnd.nextBoolean()) randomTaps(3) else menu(listOf("Live trains", "Train maps", "Trip planner", "Train issues")[rnd.nextInt(4)]) }
        FakeWmata.bodies = emptyMap(); FakeWmata.failing = emptyMap(); Net.testOnline = true
    }

    private fun trains() {
        if (!menu("Amtrak, MARC & VRE")) return
        repeat(4) {
            when (rnd.nextInt(5)) {
                0 -> tapText(listOf("Amtrak", "MARC", "VRE", "All")[rnd.nextInt(4)])
                1 -> tapText(if (rnd.nextBoolean()) "🚉  Stations" else "🚆  Trains")
                2 -> compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().firstOrNull()?.let {
                    compose.onAllNodes(hasSetTextAction())[0].performTextInput(listOf("New York", "171", "Fred", "x", "Bal")[rnd.nextInt(5)]); idle(); check()
                }
                else -> randomTaps(2)
            }
        }
    }

    private fun airports() {
        if (!menu("Airports")) return
        tapText(if (rnd.nextBoolean()) "Dulles (IAD)" else "Reagan National (DCA)")
        compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().lastOrNull()?.let {
            val box = compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size - 1
            compose.onAllNodes(hasSetTextAction())[box].performTextInput(listOf("1:30", "930", "12", "7", "23:59", "abc", "0")[rnd.nextInt(7)]); idle(); check()
        }
        if (rnd.nextBoolean()) tapText(if (rnd.nextBoolean()) "AM" else "PM")
        randomTaps(3)
    }

    private fun settings() {
        if (!menu("Settings")) return
        randomTaps(6)
    }

    fun run(seconds: Long) {
        compose.waitUntil(10_000) { sees("MetroCast") }
        Net.testOnline = true
        val end = System.currentTimeMillis() + seconds * 1000
        var rounds = 0
        while (System.currentTimeMillis() < end) {
            when (rnd.nextInt(10)) {
                0, 1 -> ride()
                2 -> trouble()
                3 -> trains()
                4 -> airports()
                5 -> settings()
                else -> { if (rnd.nextInt(3) == 0) menu(listOf("Live trains", "Train maps", "Saved routes", "Trip history", "Restrooms", "Track work calendar", "Bikes", "Metro Police", "Special trains", "Scenic rides", "Train issues")[rnd.nextInt(11)]); randomTaps(8) }
            }
            rounds++
            // Robolectric keeps every dialog ever shown (a real phone frees them on close): forget them, or a long run runs out of memory.
            compose.runOnUiThread { org.robolectric.shadows.ShadowDialog.reset() }
        }
        home()
        check(sees("MetroCast")) { "[$name] couldn't get back home" }
        val report = buildString {
            appendLine("[$name] $rounds scenarios, ${log.size} actions, ${log.count { it == "trouble" }} rounds of WMATA trouble")
            appendLine("  rides: ${log.count { it.startsWith("pick ") }} planned, ${log.count { it == "Start live trip" }} started, ${log.count { it.startsWith("I'm on the train") || it == "BOARD" }} boarded, ${log.count { it == "End trip" }} ended")
            val screens = listOf("Trip planner", "Live trains", "Train maps", "Saved routes", "Trip history", "Restrooms", "Track work calendar", "Bikes", "Metro Police", "Special trains", "Scenic rides", "Train issues", "Airports", "Amtrak, MARC & VRE", "Settings").filter { s -> log.any { it == s } }
            appendLine("  screens opened: ${screens.size} of 15 · different buttons tapped: ${log.filter { it != "back" && it != "?" }.map { it.take(30) }.toSet().size}")
            if (findings.isEmpty()) appendLine("  no problems seen")
            findings.forEach { (what, where) -> appendLine("  FINDING x${counts[what]}: $what\n      after: $where") }
        }
        println(report)
        System.getProperty("metrocast.simout")?.let { File(it).apply { parentFile?.mkdirs() }.appendText(report) }
    }

    protected fun go() { val s = System.getProperty("metrocast.sim")?.toLongOrNull(); assumeTrue(s != null); run(s!!) }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class SimPhone1PixelLight : SimPhone("Phone 1 · Pixel-size, light", 101) { @Test fun session() = go() }

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-night-xhdpi", fontScale = 1.3f)
class SimPhone2SmallDarkBigText : SimPhone("Phone 2 · small, dark, large text", 202) { @Test fun session() = go() }

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-land-xhdpi")
class SimPhone3TabletLandscape : SimPhone("Phone 3 · tablet, landscape", 303) { @Test fun session() = go() }
