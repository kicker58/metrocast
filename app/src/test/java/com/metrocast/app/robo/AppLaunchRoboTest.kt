package com.metrocast.app.robo

import android.content.Context
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
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

/** The real app, launched: app start, the refresh loop against a fake WMATA, and navigation. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class AppLaunchRoboTest {
    // The fake WMATA must be in place before the activity starts.
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

    private fun sees(t: String) = compose.onAllNodesWithText(t, substring = true).fetchSemanticsNodes().isNotEmpty()
    private fun waitFor(t: String) = compose.waitUntil(8_000) { sees(t) }
    private fun menu(item: String) {
        compose.onNodeWithContentDescription("Menu").performClick()
        compose.onNodeWithText(item).performClick()
        compose.waitForIdle()
    }
    private fun back() = compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }.also { compose.waitForIdle() }

    @Test fun launchesLoadsAndNavigates() {
        waitFor("MetroCast")
        // Live data arrives from the (fake) WMATA.
        compose.waitUntil(20_000) { runCatching { compose.onNode(hasScrollAction()).performScrollToNode(hasText("INCOMING TRAINS", substring = true)) }.isSuccess }
        // Every menu screen opens and Back returns home.
        for ((item, title) in listOf("Live trains" to "Live trains", "Train issues" to "Train issues", "Saved routes" to "No saved routes", "Settings" to "Settings")) {
            menu(item); waitFor(title); back(); waitFor("MetroCast")
        }
        // Planner → live trip → end it.
        menu("Trip planner"); waitFor("Plan a trip")
        compose.onNodeWithText("Choose where you're going").performClick()
        waitFor("Search stations")
        compose.onNode(androidx.compose.ui.test.hasSetTextAction()).performTextInput("Navy")
        waitFor("Navy Yard-Ballpark")
        compose.onNodeWithText("Navy Yard-Ballpark").performClick()
        waitFor("Transfer at")
        compose.onAllNodes(hasScrollAction() and androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange))
            .onFirst().performScrollToNode(hasText("Start live trip", substring = true))
        compose.onNodeWithText("Start live trip", substring = true).performClick()
        waitFor("Live trip")
        // Decline location, then end the trip.
        if (sees("No, I'll tap the button")) compose.onNodeWithText("No, I'll tap the button").performClick()
        waitFor("BOARD")
        compose.onNodeWithText("End trip").performClick()
        waitFor("MetroCast")
    }
}
