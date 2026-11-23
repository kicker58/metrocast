package com.metrocast.app.robo

import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.metrocast.app.ui.MainActivity
import com.metrocast.app.ui.Net
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.annotation.Config

/** Every screen survives turning the phone (the screen being rebuilt), and settles afterwards. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class RotateEveryScreenRoboTest {
    private val fake = object : TestRule {
        override fun apply(base: Statement, d: Description) = object : Statement() {
            override fun evaluate() {
                ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("metrocast", Context.MODE_PRIVATE).edit().clear().putBoolean("onboarded", true).commit()
                FakeWmata.install(); resetViewModelFactory(); Net.testOnline = true
                base.evaluate()
            }
        }
    }
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(fake).around(compose)
    @After fun done() { FakeWmata.uninstall(); Net.testOnline = null }

    private fun idle() { compose.mainClock.advanceTimeBy(2_000); compose.waitForIdle() }
    private fun tap(text: String, desc: Boolean = false): Boolean {
        val m = if (desc) hasContentDescription(text) else hasText(text, substring = true)
        val n = compose.onAllNodes(m and hasClickAction()).fetchSemanticsNodes().firstOrNull() ?: return false
        compose.runOnUiThread { n.config.getOrNull(SemanticsActions.OnClick)?.action?.invoke() }; idle(); return true
    }
    private fun rotate(what: String) {
        listOf("+land", "+port").forEach { q ->
            org.robolectric.RuntimeEnvironment.setQualifiers(q)
            try { compose.activityRule.scenario.recreate(); idle() }
            catch (t: Throwable) { throw AssertionError("turning the phone on \"$what\" ($q)", t) }
        }
    }

    private val menuItems = listOf("Trip planner", "Live trains", "Train maps", "Saved routes", "Trip history", "Restrooms", "Track work calendar",
        "Bikes", "Metro Police", "Special trains", "Scenic rides", "Train issues", "Airports", "Amtrak, MARC & VRE", "Settings")

    @Test fun everyMenuScreen() {
        compose.waitUntil(10_000) { compose.onAllNodes(hasContentDescription("Menu")).fetchSemanticsNodes().isNotEmpty() }
        rotate("home")
        for (item in menuItems) {
            tap("Menu", desc = true)
            if (!tap(item)) { compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }; idle(); continue }
            rotate(item)
            // One level deeper: the first thing to tap on the screen, then turn again.
            val inner = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes().drop(1).firstOrNull()
            if (inner != null) { compose.runOnUiThread { inner.config.getOrNull(SemanticsActions.OnClick)?.action?.invoke() }; idle(); rotate("$item, then a tap") }
            repeat(4) { if (compose.onAllNodes(hasContentDescription("Menu")).fetchSemanticsNodes().isEmpty()) { compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }; idle() } }
        }
    }

    @Test fun specialTrainsThenTurn() {
        compose.waitUntil(10_000) { compose.onAllNodes(hasContentDescription("Menu")).fetchSemanticsNodes().isNotEmpty() }
        tap("Menu", desc = true); tap("Special trains")
        tap("Holiday train")
        rotate("Special trains → Holiday train")
    }

    @Test fun trackWorkFromHomeThenTurn() {
        compose.waitUntil(10_000) { compose.onAllNodes(hasContentDescription("Menu")).fetchSemanticsNodes().isNotEmpty() }
        if (!tap("track work")) tap("🚧")
        rotate("track work (from the home card)")
        tap("Menu", desc = true); tap("Train maps"); tap("Green Line"); compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }; idle()
        if (!tap("track work")) tap("🚧")
        rotate("track work after the Green Line map")
    }
}
