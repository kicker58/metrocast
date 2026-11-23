package com.metrocast.app.robo

import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.metrocast.app.data.Repository
import com.metrocast.app.ui.MainActivity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.annotation.Config
import kotlin.random.Random

/** The whole live trip in the real app, McPherson Square → Navy Yard with a transfer, tapped through like a rider. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class LiveTripAppRoboTest {
    private val ctx: Context get() = compose.activity.applicationContext
    private val seed = object : TestRule {
        override fun apply(base: Statement, d: Description) = object : Statement() {
            override fun evaluate() {
                ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("metrocast", Context.MODE_PRIVATE).edit().clear().putBoolean("onboarded", true).commit()
                FakeWmata.install(); resetViewModelFactory()
                base.evaluate()
            }
        }
    }
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(seed).around(compose)
    @After fun tearDown() = FakeWmata.uninstall()

    private fun sees(t: String) = compose.onAllNodesWithText(t, substring = true).fetchSemanticsNodes().isNotEmpty()
    private fun waitFor(t: String) = try { compose.waitUntil(20_000) { sees(t) } } catch (e: Throwable) {
        val texts = compose.onAllNodes(androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(SemanticsProperties.Text)).fetchSemanticsNodes()
            .map { it.config[SemanticsProperties.Text].joinToString(" ") }
        throw AssertionError("never saw \"$t\"; requests: ${FakeWmata.requests.map { it.substringBefore("?").substringAfterLast("/") }}; plan_to=${ctx.getSharedPreferences("metrocast", Context.MODE_PRIVATE).getString("plan_to", null)}; on screen: ${texts.take(40)}", e)
    }
    private fun tap(t: String) { waitFor(t); compose.onNodeWithText(t, substring = true).performClick(); compose.waitForIdle() }

    @Test fun rideWithATransferFromStartToFinish() {
        waitFor("MetroCast")
        // Set the planner's stations in the running app, then restart the screen to pick them up.
        ctx.getSharedPreferences("metrocast", Context.MODE_PRIVATE).edit().putString("plan_from", "C02").putString("plan_to", "F05").putBoolean("live_use_loc", false).commit()
        compose.activityRule.scenario.recreate()
        waitFor("MetroCast")
        compose.onNodeWithContentDescription("Menu").performClick()
        tap("Trip planner")
        waitFor("Transfer at L'Enfant Plaza")
        compose.onAllNodes(hasScrollAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .onFirst().performScrollToNode(hasText("Start live trip", substring = true))
        tap("Start live trip")
        waitFor("Live trip")
        if (sees("No, I'll tap the button")) tap("No, I'll tap the button")
        // Leg 1: Silver/Blue/Orange to L'Enfant Plaza.
        waitFor("BOARD"); assertTrue(sees("TRANSFER AT L'ENFANT PLAZA"))
        tap("I'm on the train")
        waitFor("RIDING TO YOUR TRANSFER")
        tap("I'm off at")
        // Leg 2: Green to Navy Yard.
        tap("I'm on the train")
        waitFor("RIDING TO YOUR STOP")
        tap("I've arrived")
        waitFor("You've arrived")
        assertTrue(sees("This trip ends automatically in 7 min"))
        // Trip history got exactly one entry.
        val repo = Repository(ctx)
        assertEquals(1, repo.prefs.history.size)
        assertEquals("Navy Yard-Ballpark", repo.prefs.history[0].to)
        compose.onAllNodesWithText("End trip").onFirst().performClick(); compose.waitForIdle()
        waitFor("MetroCast")
        // Live trip gone, planner reset for the next trip.
        assertNull(Repository(ctx).liveTrip())
        assertNull(ctx.getSharedPreferences("metrocast", Context.MODE_PRIVATE).getString("plan_to", null))
        assertTrue(!sees("In progress"))
    }
}

/** Random taps while a live trip is running (with Back, the menu, and the live trip screen in the mix). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class MonkeyDuringTripRoboTest {
    private val ctx: Context get() = compose.activity.applicationContext
    private val seed = object : TestRule {
        override fun apply(base: Statement, d: Description) = object : Statement() {
            override fun evaluate() {
                ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("metrocast", Context.MODE_PRIVATE).edit().clear().putBoolean("onboarded", true).commit()
                FakeWmata.install(); resetViewModelFactory()
                base.evaluate()
            }
        }
    }
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(seed).around(compose)
    @After fun tearDown() = FakeWmata.uninstall()

    private fun label(n: androidx.compose.ui.semantics.SemanticsNode) =
        n.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") ?: n.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ") ?: "?"

    private fun monkey(seedN: Int, taps: Int) {
        val rnd = Random(seedN)
        compose.waitUntil(20_000) { compose.onAllNodesWithText("MetroCast", substring = true).fetchSemanticsNodes().isNotEmpty() }
        // Start a trip in the running app, then restart the screen so it shows it.
        Repository(ctx).apply { prefs.liveUseLocation = false; runBlocking { refresh() }; startLiveTrip("C02", "F05") }
        val log = ArrayList<String>()
        fun openTrip() {
            val menu = compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("Menu")).fetchSemanticsNodes()
            if (menu.isEmpty()) return
            compose.runOnUiThread { menu[0].config.getOrNull(SemanticsActions.OnClick)?.action?.invoke() }
            compose.waitForIdle()
            compose.onAllNodes(hasText("Live trip") and hasClickAction()).fetchSemanticsNodes().firstOrNull()?.let { n ->
                log += "Live trip (menu)"; compose.runOnUiThread { n.config.getOrNull(SemanticsActions.OnClick)?.action?.invoke() }
            }
            compose.waitForIdle()
        }
        // Wait until the app has picked up the running trip (opening Live trains refreshes it), then open it.
        compose.waitUntil(25_000) {
            openTrip()
            if (log.lastOrNull() == "Live trip (menu)") return@waitUntil true
            compose.onAllNodes(hasText("Live trains") and hasClickAction()).fetchSemanticsNodes().firstOrNull()?.let { n ->
                compose.runOnUiThread { n.config.getOrNull(SemanticsActions.OnClick)?.action?.invoke() }
                compose.waitForIdle(); Thread.sleep(300)
                compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
                compose.waitForIdle()
            }
            false
        }
        repeat(taps) { step ->
            when {
                rnd.nextInt(15) == 0 -> openTrip()
                rnd.nextInt(8) == 0 -> { log += "back"; compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() } }
                else -> {
                    val nodes = compose.onAllNodes(hasClickAction() and SemanticsMatcher("enabled") { !it.config.contains(SemanticsProperties.Disabled) })
                        .fetchSemanticsNodes()
                    // "End trip" only rarely, so most of the run happens mid-trip.
                    val pick = nodes.filter { label(it) != "End trip" || rnd.nextInt(20) == 0 }
                    if (pick.isNotEmpty()) {
                        val n = pick[rnd.nextInt(pick.size)]
                        log += label(n)
                        try { compose.runOnUiThread { n.config.getOrNull(SemanticsActions.OnClick)?.action?.invoke() } }
                        catch (e: Throwable) { throw AssertionError("crash on tap #$step \"${label(n)}\" after: ${log.takeLast(12)}", e) }
                    }
                }
            }
            compose.waitForIdle()
        }
        repeat(8) { compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }; compose.waitForIdle() }
        val touchedTrip = log.any { it.startsWith("I'm on the train") || it.startsWith("I'm off") || it.startsWith("Live trip") || it.startsWith("I've arrived") }
        println("Monkey-during-trip seed $seedN: ${log.size} actions, ${log.toSet().size} different; live-trip buttons used: $touchedTrip")
        assertTrue("never touched the live trip: ${log.toSet()}", touchedTrip)
        // Whatever happened, the stored trip is consistent: either still running and readable, or fully cleared.
        val live = Repository(ctx).liveTrip()
        if (live == null) assertNull(ctx.getSharedPreferences("metrocast", Context.MODE_PRIVATE).getString("live_from", null))
    }

    @Test fun seed11() = monkey(11, 250)
    @Test fun seed12() = monkey(12, 250)
}

/** A brand-new install opens the walkthrough; finishing it lands on a working home screen. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class FirstLaunchRoboTest {
    private val fresh = object : TestRule {
        override fun apply(base: Statement, d: Description) = object : Statement() {
            override fun evaluate() {
                ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("metrocast", Context.MODE_PRIVATE).edit().clear().commit()
                ApplicationProvider.getApplicationContext<Context>().filesDir.listFiles()?.forEach { it.delete() }
                FakeWmata.install(); resetViewModelFactory()
                base.evaluate()
            }
        }
    }
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(fresh).around(compose)
    @After fun tearDown() = FakeWmata.uninstall()

    private fun sees(t: String) = compose.onAllNodesWithText(t, substring = true).fetchSemanticsNodes().isNotEmpty()

    @Test fun walkthroughThenHome() {
        compose.waitUntil(10_000) { sees("Welcome to MetroCast") }
        assertTrue(sees("Your data stays on this phone"))
        compose.onNodeWithText("Skip setup").performClick()
        compose.waitUntil(20_000) { sees("Board at") || sees("Trains") }
        assertTrue(!sees("Welcome to MetroCast"))
        // Settings can run it again.
        compose.onNodeWithContentDescription("Menu").performClick()
        compose.onNodeWithText("Settings").performClick()
        compose.waitUntil(10_000) { sees("Home screen layout") }
        compose.onAllNodes(androidx.compose.ui.test.hasScrollAction() and androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange)).onFirst()
            .performScrollToNode(hasText("Run the setup walkthrough again"))
        compose.onNodeWithText("Run the setup walkthrough again").performClick()
        compose.waitUntil(10_000) { sees("Welcome to MetroCast") }
    }
}
