package com.metrocast.app.robo

import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.metrocast.app.BuildConfig
import com.metrocast.app.data.Repository
import com.metrocast.app.ui.MainActivity
import com.metrocast.app.ui.MetroCastTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
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
 * The public build (gradle -PpublicBuild): no WMATA key anywhere, and the app helps each person add
 * their own. Skipped in your own build (which has the key).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class PublicBuildRoboTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var ctx: Context
    private lateinit var repo: Repository

    @Before fun setUp() {
        assumeTrue("public build only", BuildConfig.WMATA_KEY.isEmpty())
        ctx = ApplicationProvider.getApplicationContext()
        ctx.getSharedPreferences("metrocast", Context.MODE_PRIVATE).edit().clear().commit()
        ctx.filesDir.listFiles()?.forEach { it.delete() }
        FakeWmata.install()
        repo = Repository(ctx)
    }
    @After fun tearDown() = FakeWmata.uninstall()

    private fun sees(t: String) = compose.onAllNodesWithText(t, substring = true).fetchSemanticsNodes().isNotEmpty()

    @Test fun noKeyAnywhere() {
        assertEquals("", repo.prefs.apiKey)
        assertTrue(!repo.snapshot().hasApiKey)
        // Without a key: no calls to WMATA's API, a friendly message, no crash.
        FakeWmata.requests.clear()
        val r = runBlocking { repo.refresh() }
        assertTrue(FakeWmata.seen().none { "api.wmata.com" in it && "GetPrediction" in it })
        assertTrue("message: ${r.trainError}", r.trainError == null || r.trainError!!.isNotBlank())
        repo.snapshot(); repo.lineMaps(); repo.liveTrip(); repo.trackWorkImpacts()
    }

    @Test fun walkthroughAsksForAKey() {
        compose.setContent { MetroCastTheme { com.metrocast.app.ui.OnboardingScreen(repo, onDone = {}) } }
        assertTrue(sees("Your WMATA key")); assertTrue(sees("developer.wmata.com"))
        // A wrong key is caught; the button lets you carry on without one.
        compose.onNode(hasSetTextAction()).performTextInput("not-a-key")
        assertTrue(sees("A key is 32 letters and numbers"))
        assertTrue(compose.onAllNodesWithText("Get started (add a key later)").fetchSemanticsNodes().single().config.contains(SemanticsProperties.Disabled))
        // A real-looking key: saved on the phone.
        compose.onNode(hasSetTextAction()).performTextClearance()
        compose.onNode(hasSetTextAction()).performTextInput("0123456789abcdef0123456789ABCDEF")
        compose.onAllNodesWithText("Get started").onFirst().performClick()
        compose.waitForIdle()
        assertEquals("0123456789abcdef0123456789ABCDEF", repo.prefs.apiKey)
        assertTrue(sees("Your usual trip"))
    }

    @Test fun settingsKeyCardIsOpenWhenThereIsNoKey() {
        compose.setContent { MetroCastTheme { com.metrocast.app.ui.SettingsScreen(repo, onChanged = {}, onBack = {}) } }
        compose.onAllNodes(androidx.compose.ui.test.hasScrollAction()).onFirst()
            .performScrollToNode(androidx.compose.ui.test.hasText("WMATA API key"))
        assertTrue(sees("Unlocked")); assertTrue(!sees("Tap 15 times"))
    }

    @Test fun homeAndWidgetsWithoutAKey() {
        runBlocking { repo.refresh() }
        compose.setContent { MetroCastTheme { com.metrocast.app.ui.HomeScreen(com.metrocast.app.ui.UiState(snapshot = repo.snapshot()), repo, onRefresh = {}, onOpenSettings = {}) } }
        assertTrue(sees("WMATA") || sees("key"))
    }
}

/** Random taps through the whole public app with no key (walkthrough skipped). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class PublicBuildMonkeyRoboTest {
    private val rule = object : TestRule {
        override fun apply(base: Statement, d: Description) = object : Statement() {
            override fun evaluate() {
                assumeTrue("public build only", BuildConfig.WMATA_KEY.isEmpty())
                ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("metrocast", Context.MODE_PRIVATE).edit().clear().putBoolean("onboarded", true).commit()
                FakeWmata.install(); resetViewModelFactory()
                base.evaluate()
            }
        }
    }
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(rule).around(compose)
    @After fun tearDown() = FakeWmata.uninstall()

    @Test fun randomTapsWithoutAKey() {
        val rnd = Random(77)
        compose.waitUntil(20_000) { compose.onAllNodesWithText("MetroCast", substring = true).fetchSemanticsNodes().isNotEmpty() }
        val log = ArrayList<String>()
        repeat(250) { step ->
            if (rnd.nextInt(8) == 0) { compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }; log += "back" }
            else {
                val nodes = compose.onAllNodes(hasClickAction() and SemanticsMatcher("enabled") { !it.config.contains(SemanticsProperties.Disabled) }).fetchSemanticsNodes()
                if (nodes.isNotEmpty()) {
                    val n = nodes[rnd.nextInt(nodes.size)]
                    val label = n.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") ?: "?"
                    log += label
                    // Don't leave the app for a browser, dialer or messages.
                    if (label.contains("911") || label.contains("Call") || label.contains("Text MYMTPD")) return@repeat
                    try { compose.runOnUiThread { n.config.getOrNull(SemanticsActions.OnClick)?.action?.invoke() } }
                    catch (e: Throwable) { throw AssertionError("crash on tap #$step \"$label\" after ${log.takeLast(10)}", e) }
                }
            }
            compose.waitForIdle()
        }
        println("Public-build monkey: ${log.size} actions, ${log.toSet().size} different")
        assertTrue(log.toSet().size > 20)
    }
}
