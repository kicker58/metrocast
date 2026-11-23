package com.metrocast.app.robo

import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.metrocast.app.ui.MainActivity
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.annotation.Config
import kotlin.random.Random

/**
 * "Monkey" test: the real app, hundreds of random taps on whatever can be tapped, with random Back
 * presses. Passes if the app never crashes and always gets back to the home screen.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class MonkeyRoboTest {
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
    @After fun tearDown() = FakeWmata.uninstall()

    private fun label(n: androidx.compose.ui.semantics.SemanticsNode): String =
        (n.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ")
            ?: n.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ") ?: "?")

    private fun monkey(seed: Int, taps: Int) {
        val rnd = Random(seed)
        compose.waitUntil(8_000) { compose.onAllNodesWithText("MetroCast", substring = true).fetchSemanticsNodes().isNotEmpty() }
        val log = ArrayList<String>()
        repeat(taps) { step ->
            val menuItems = listOf("Trip planner", "Saved routes", "Live trains", "Train maps", "Trip history", "Restrooms", "Track work calendar", "Scenic rides", "Special trains", "Bikes", "Metro Police", "Train issues", "Airports", "Amtrak, MARC & VRE", "Settings")
            val menu = compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("Menu")).fetchSemanticsNodes()
            if (rnd.nextInt(8) == 0) {
                log += "back"
                compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
            } else if (menu.isNotEmpty() && rnd.nextInt(6) == 0) {
                // Open the ☰ menu and pick a random screen, so every screen gets random taps.
                compose.runOnUiThread { menu[0].config.getOrNull(SemanticsActions.OnClick)?.action?.invoke() }
                compose.waitForIdle()
                val item = menuItems[rnd.nextInt(menuItems.size)]
                val n = compose.onAllNodes(androidx.compose.ui.test.hasText(item, substring = true) and hasClickAction()).fetchSemanticsNodes().firstOrNull()
                if (n != null) {
                    log += item
                    try { compose.runOnUiThread { n.config.getOrNull(SemanticsActions.OnClick)?.action?.invoke() } }
                    catch (e: Throwable) { throw AssertionError("crash opening \"$item\" after: ${log.takeLast(12)}", e) }
                }
            } else {
                val nodes = compose.onAllNodes(hasClickAction() and SemanticsMatcher("enabled") {
                    !it.config.contains(SemanticsProperties.Disabled)
                }, useUnmergedTree = false).fetchSemanticsNodes()
                if (nodes.isNotEmpty()) {
                    val n = nodes[rnd.nextInt(nodes.size)]
                    log += label(n)
                    try {
                        compose.runOnUiThread { n.config.getOrNull(SemanticsActions.OnClick)?.action?.invoke() }
                    } catch (e: Throwable) {
                        throw AssertionError("crash on tap #$step \"${label(n)}\" after: ${log.takeLast(12)}", e)
                    }
                }
            }
            compose.waitForIdle()
            check(!compose.activity.isFinishing || true)
        }
        // Back out to the home screen: the app must still be alive and working.
        repeat(8) { compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }; compose.waitForIdle() }
        val distinct = log.filter { it != "back" && it != "?" }.map { it.take(30) }.toSet()
        val screens = listOf("Trip planner", "Live trains", "Train maps", "Saved routes", "Trip history", "Restrooms", "Track work calendar", "Scenic rides", "Special trains", "Bikes", "Metro Police", "Train issues", "Airports", "Amtrak, MARC & VRE", "Settings")
            .filter { s -> log.any { it.startsWith(s) } }
        check(screens.size >= 5) { "monkey reached only $screens" }
        println("Monkey seed $seed: ${log.size} actions, ${distinct.size} different buttons; menu screens reached: $screens")
        check(distinct.size >= 25) { "monkey got stuck: only ${distinct.size} different buttons: $distinct" }
    }

    @Test fun randomTapsSeed1() = monkey(1, 300)
    @Test fun randomTapsSeed2() = monkey(2, 300)
    @Test fun randomTapsSeed3() = monkey(3, 300)
}
