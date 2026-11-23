package com.metrocast.app.robo

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.metrocast.app.data.Repository
import com.metrocast.app.ui.HomeScreen
import com.metrocast.app.ui.LiveTrainsScreen
import com.metrocast.app.ui.MetroCastTheme
import com.metrocast.app.ui.PlannerScreen
import com.metrocast.app.ui.SettingsScreen
import com.metrocast.app.ui.UiState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Small phone, dark mode and 1.6× text: the main screens must still draw (no crashes, text present). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-night-xhdpi")
class AccessibilityRoboTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var repo: Repository
    private lateinit var state: UiState

    @Before fun setUp() = runBlocking {
        RuntimeEnvironment.setFontScale(1.6f)
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.getSharedPreferences("metrocast", Context.MODE_PRIVATE).edit().clear().commit()
        FakeWmata.install()
        repo = Repository(ctx)
        repo.refresh(); repo.setPlan("C02", "F05"); repo.refresh()
        state = UiState(snapshot = repo.snapshot(), maps = repo.lineMaps())
    }

    @After fun tearDown() { FakeWmata.uninstall(); RuntimeEnvironment.setFontScale(1f) }

    private fun sees(t: String) = compose.onAllNodesWithText(t, substring = true).fetchSemanticsNodes().isNotEmpty()

    @Test fun homeLargeTextDark() {
        compose.setContent { MetroCastTheme { HomeScreen(state, repo, onRefresh = {}, onOpenSettings = {}) } }
        assert(sees("MetroCast"))
    }

    @Test fun plannerLargeTextDark() {
        compose.setContent { MetroCastTheme { PlannerScreen(state.snapshot.plan, repo, onChange = { _, _ -> }, onBack = {}, onStartLive = {}) } }
        assert(sees("Plan a trip")); assert(sees("COST"))
    }

    @Test fun settingsBikesAndMapLargeTextDark() {
        val screen = androidx.compose.runtime.mutableStateOf(0)
        compose.setContent {
            MetroCastTheme {
                when (screen.value) {
                    0 -> SettingsScreen(repo, onChanged = {}, onBack = {})
                    1 -> com.metrocast.app.ui.BikesScreen(repo, onBack = {})
                    else -> LiveTrainsScreen(state.maps, onBack = {})
                }
            }
        }
        assert(sees("Settings"))
        compose.runOnIdle { screen.value = 1 }; compose.waitForIdle(); assert(sees("Bikes on Metro"))
        compose.runOnIdle { screen.value = 2 }; compose.waitForIdle(); assert(sees("Live trains"))
    }
}
