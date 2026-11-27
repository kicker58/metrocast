package com.metrocast.app.robo

import android.content.Context
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.onFirst
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.metrocast.app.LiveScenarioTest
import com.metrocast.app.data.LiveInputs
import com.metrocast.app.data.LiveState
import com.metrocast.app.data.LiveTrip
import com.metrocast.app.data.LiveTripEngine
import com.metrocast.app.data.Repository
import com.metrocast.app.data.StationDirectory
import com.metrocast.app.data.TrainTracker
import com.metrocast.app.data.WmataApi
import com.metrocast.app.ui.HomeScreen
import com.metrocast.app.ui.IssuesScreen
import com.metrocast.app.ui.LineMapScreen
import com.metrocast.app.ui.LiveTrainsScreen
import com.metrocast.app.ui.LiveTripScreen
import com.metrocast.app.ui.MetroCastTheme
import com.metrocast.app.ui.PlannerScreen
import com.metrocast.app.ui.SavedRoutesScreen
import com.metrocast.app.ui.SettingsScreen
import com.metrocast.app.ui.UiState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Every screen drawn with real data in its main states, plus taps through the menu. */
@RunWith(AndroidJUnit4::class)
// A typical modern phone screen (411 × 891 dp).
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class ScreensRoboTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var repo: Repository
    private lateinit var state: UiState

    @Before fun setUp() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.getSharedPreferences("metrocast", Context.MODE_PRIVATE).edit().clear().commit()
        FakeWmata.install()
        repo = Repository(ctx)
        repo.refresh()
        repo.setPlan("C02", "F05")
        repo.toggleSavedRoute("N06", "B03")
        repo.refresh()
        state = UiState(snapshot = repo.snapshot(), maps = repo.lineMaps())
    }

    @After fun tearDown() = FakeWmata.uninstall()

    private fun show(content: @androidx.compose.runtime.Composable () -> Unit) = compose.setContent { MetroCastTheme { content() } }
    private fun sees(text: String) = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
    private fun mustSee(vararg texts: String) = texts.forEach { t -> assert(sees(t)) { "expected to see \"$t\"" } }
    /** Scrolls the screen's list until [text] is on screen (like a person would). */
    private fun scrollTo(text: String) = compose.onNode(hasScrollAction()).performScrollToNode(hasText(text, substring = true))

    // ---------------------------------------------------------------- home and menu

    @Test fun homeShowsTripsAndMenuOpensEverything() {
        var opened = ""
        show {
            HomeScreen(
                state, repo, onRefresh = {}, onOpenSettings = { opened = "settings" },
                onOpenPlanner = { opened = "planner" }, onOpenLive = { opened = "live" },
                onOpenIssues = { opened = "issues" }, onOpenSaved = { opened = "saved" },
            )
        }
        mustSee("MetroCast")
        scrollTo("Board at")
        mustSee("Board at", "Wiehle-Reston East", "GOING THERE", "GOING TO", "Foggy Bottom-GWU")
        assert(!sees("Change")) { "the home page shouldn't offer to change trips any more" }
        scrollTo("INCOMING TRAINS")
        for ((item, expect) in listOf("Trip planner" to "planner", "Saved routes" to "saved", "Live trains" to "live", "Train issues" to "issues", "Settings" to "settings")) {
            compose.onNodeWithContentDescription("Menu").performClick()
            compose.onNodeWithText(item).performClick()
            assert(opened == expect) { "menu \"$item\" opened \"$opened\"" }
        }
        // Train maps submenu lists every line.
        compose.onNodeWithContentDescription("Menu").performClick()
        compose.onNodeWithText("Train maps").performClick()
        mustSee("Red Line", "Silver Line", "Green Line")
    }

    // ---------------------------------------------------------------- planner, saved, SmarTrip

    @Test fun plannerShowsRouteTransferAndCost() {
        show { PlannerScreen(state.snapshot.plan, repo, onChange = { _, _ -> }, onBack = {}, onStartLive = {}) }
        mustSee("Plan a trip", "McPherson Square", "Navy Yard", "Transfer at L'Enfant Plaza", "COST", "Start live trip")
        assert(sees("Peak") || sees("Off-peak"))
        // People stepper.
        compose.onNodeWithText("+").performClick()
        mustSee("2 people", "one way for 2")
    }

    /** Opens "When are you leaving?" and types [text] into its Time box (replacing what's there). */
    private fun typeLeavingTime(text: String) {
        compose.onAllNodesWithText("Change", substring = true).onFirst().performClick()
        mustSee("When are you leaving?", "Time")
        compose.onNode(androidx.compose.ui.test.hasSetTextAction()).performTextReplacement(text)
    }

    @Test fun plannerTimeDoneRightAwayKeepsTheSuggestedTime() {
        com.metrocast.app.ui.Format.use24h = false
        show { PlannerScreen(state.snapshot.plan, repo, onChange = { _, _ -> }, onBack = {}, onStartLive = {}) }
        compose.onAllNodesWithText("Change", substring = true).onFirst().performClick()
        compose.onNodeWithText("Done").performClick()
        compose.waitForIdle()
        assert(!sees("Leaving now") && (sees(" AM") || sees(" PM"))) { "a time is set" }
    }

    @Test fun plannerTimeCanBeTyped() {
        com.metrocast.app.ui.Format.use24h = false
        show { PlannerScreen(state.snapshot.plan, repo, onChange = { _, _ -> }, onBack = {}, onStartLive = {}) }
        // Typed with a colon, then PM.
        typeLeavingTime("5:30")
        compose.onNodeWithText("PM").performClick()
        compose.onNodeWithText("Done").performClick()
        mustSee("5:30 PM")
        // Without a colon ("845" = 8:45), AM picked.
        typeLeavingTime("845")
        compose.onNodeWithText("AM").performClick()
        compose.onNodeWithText("Done").performClick()
        mustSee("8:45 AM")
        // −/+ still nudge it, and the box follows.
        compose.onAllNodesWithText("Change", substring = true).onFirst().performClick()
        compose.onAllNodesWithText("+").let { it[it.fetchSemanticsNodes().size - 1] }.performClick()
        compose.onNodeWithText("Done").performClick()
        mustSee("9:00 AM")
    }

    @Test fun plannerTimeRejectsNonsense() {
        com.metrocast.app.ui.Format.use24h = false
        show { PlannerScreen(state.snapshot.plan, repo, onChange = { _, _ -> }, onBack = {}, onStartLive = {}) }
        for (bad in listOf("99:99", "13:75", "0", "25", "::", "1.2.3")) {
            typeLeavingTime(bad)
            mustSee("Type the time")
            compose.onNodeWithText("Done").assertIsNotEnabled()
            // The dialog's "Leaving now" button: closes it, back to "now".
            compose.onAllNodes(hasText("Leaving now") and androidx.compose.ui.test.hasClickAction()).let { it[it.fetchSemanticsNodes().size - 1] }.performClick()
        }
        // Letters and symbols can't even be typed.
        typeLeavingTime("ab😀!7")
        assert(compose.onAllNodes(androidx.compose.ui.test.hasSetTextAction() and hasText("7")).fetchSemanticsNodes().size == 1) { "only the digit stays" }
    }

    @Test fun plannerTimeOn24HourClock() {
        com.metrocast.app.ui.Format.use24h = true
        try {
            show { PlannerScreen(state.snapshot.plan, repo, onChange = { _, _ -> }, onBack = {}, onStartLive = {}) }
            typeLeavingTime("17:45")
            assert(!sees("AM") && !sees("PM")) { "no AM/PM on a 24-hour clock" }
            compose.onNodeWithText("Done").performClick()
            mustSee("17:45")
        } finally { com.metrocast.app.ui.Format.use24h = false }
    }

    @Test fun savedRoutesAndSmarTrip() {
        show { SavedRoutesScreen(state.snapshot.savedRoutes, onOpen = {}, onRemove = {}, onBack = {}) }
        mustSee("Saved routes", "Wiehle-Reston East → Union Station")
    }


    // ---------------------------------------------------------------- maps and issues

    @Test fun mapsDraw() {
        show { LiveTrainsScreen(state.maps, onBack = {}) }
        mustSee("Live trains", "trains")
    }

    @Test fun lineMapDrawsWithTripFocus() {
        val trip = state.snapshot.trips.first()
        show { LineMapScreen("SV", state.maps["SV"], trip, setOf(trip.trip.stationCode), onBack = {}) }
        mustSee("Silver Line · Live", "Wiehle-Reston East")
    }

    @Test fun issuesLoad() {
        show { IssuesScreen(repo, onBack = {}) }
        compose.waitUntil(60_000) { sees("Service alerts") && !sees("Checking with WMATA") }
        mustSee("Train issues")
    }

    @Test fun settingsCustomisesHomeTrips() {
        repo.prefs.trips = repo.prefs.trips.take(1)
        show { SettingsScreen(repo, onChanged = {}, onBack = {}) }
        mustSee("Home screen trips", "Going there", "Coming back", "FROM", "TO", "Wiehle-Reston East", "Foggy Bottom-GWU")
        // Coming back isn't set: one tap fills it in as the reverse.
        compose.onNodeWithText("Use the reverse", substring = true).performClick()
        compose.waitForIdle()
        assert(repo.prefs.trips.map { it.destCode } == listOf("C04", "N06")) { repo.prefs.trips.toString() }
        assert(repo.prefs.trips[1].label == "Coming back")
        // Any station to any station: change "Going there" to end at Navy Yard (a transfer).
        compose.onAllNodesWithText("Foggy Bottom-GWU").onFirst().performClick()
        compose.onNode(androidx.compose.ui.test.hasSetTextAction()).performTextInput("Navy")
        compose.onNodeWithText("Navy Yard-Ballpark").performClick()
        compose.waitForIdle()
        val going = repo.prefs.trips[0]
        assert(going.destCode == "F05" && going.via != null && going.stationCode == "N06") { going.toString() }
        mustSee("Navy Yard-Ballpark", "🔁 Change at")
        // Reset puts the defaults back.
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Reset to the starting trips", substring = true))
        compose.onNodeWithText("Reset to the starting trips", substring = true).performClick()
        assert(repo.prefs.trips.map { it.towardCode } == listOf("C04", "N06"))
    }

    @Test fun settingsKeyIsLocked() {
        show { SettingsScreen(repo, onChanged = {}, onBack = {}) }
        mustSee("Settings")
        scrollTo("Tap 15 times")
        mustSee("Locked", "Tap 15 times")
        // Unlock by tapping 15 times.
        repeat(15) { compose.onNodeWithText("•", substring = true).performClick() }
        mustSee("Unlocked")
    }

    @Test fun historyScreenShowsTotalsAndTurnsOff() {
        val now = System.currentTimeMillis()
        repo.prefs.history = listOf(
            com.metrocast.app.data.TripRecord(now - 3_600_000, "Wiehle-Reston East", "Foggy Bottom-GWU", 37, 1, 0, 6.75, true),
            com.metrocast.app.data.TripRecord(now - 7_200_000, "Foggy Bottom-GWU", "Wiehle-Reston East", 39, 1, 0, 2.50, false),
        )
        show { com.metrocast.app.ui.HistoryScreen(repo.prefs, onBack = {}) }
        mustSee("Trip history", "Kept only on this phone", "THIS WEEK", "LAST 4 WEEKS", "$9.25", "Wiehle-Reston East → Foggy Bottom-GWU")
        // Turning it off asks first, then deletes.
        compose.onNode(androidx.compose.ui.test.isToggleable()).performClick()
        mustSee("Turn off trip history?")
        compose.onNodeWithText("Turn off").performClick()
        mustSee("Trip history is off")
        assert(repo.prefs.history.isEmpty())
    }

    // ---------------------------------------------------------------- live trip states

    private fun liveState(trainAt: String?, greenIn: List<Int>, onBoard: Boolean): LiveTrip {
        LiveScenarioTest.load()
        val routes = LiveScenarioTest.routes
        val r = routes.filter { it.line == "SV" }.maxByOrNull { it.stationAt.size }!!
        val pos = trainAt?.let { listOf(TrainTracker.Position("S1", r.circuits[r.stationIndices[it]!!.first()], "SV", "D04", 8, 1)) } ?: emptyList()
        val preds = greenIn.map { WmataApi.RawTrain("8", "Branch Ave", "F11", "2", "GR", "F03", "$it") } +
            listOf(WmataApi.RawTrain("8", "Federal Center SW", "D04", "1", "SV", "D03", "2"))
        val inp = LiveInputs(LiveScenarioTest.paths, routes, pos, preds, 0, { _, _ -> null }, emptyList(), 1_700_000_000_000L)
        val st = LiveState("C02", "F05", 0, if (onBoard) "S1" else null, if (onBoard) 1_700_000_000_000L - 60_000 else 0L, 99f)
        return LiveTripEngine.update(st, inp).live!!
    }

    @Test fun liveTripWaitingToBoard() {
        show { LiveTripScreen(liveState(null, listOf(9), onBoard = false), 0L, {}, {}, {}, {}, useLocation = false, repo = repo) }
        mustSee("Live trip", "BOARD", "I'm on the train", "TRANSFER AT L'ENFANT PLAZA")
    }

    @Test fun liveTripRunHurryAndTakeYourTime() {
        val cases = listOf(listOf(4) to "RUN", listOf(6) to "HURRY", listOf(12) to "TAKE YOUR TIME")
        val current = androidx.compose.runtime.mutableStateOf(liveState("D02", cases[0].first, onBoard = true))
        show { LiveTripScreen(current.value, 0L, {}, {}, {}, {}, useLocation = false, repo = repo) }
        for ((green, word) in cases) {
            compose.runOnIdle { current.value = liveState("D02", green, onBoard = true) }
            compose.waitForIdle()
            mustSee("RIDING TO YOUR TRANSFER", "L'Enfant Plaza", word, "CONNECTING TRAINS")
        }
    }

    @Test fun liveTripArrived() {
        val arrived = liveState(null, emptyList(), onBoard = false).let { it.copy(leg = 2, autoEndMinutes = 7) }
        show { LiveTripScreen(arrived, 0L, {}, {}, {}, {}, useLocation = false, repo = repo) }
        mustSee("You've arrived", "This trip ends automatically in 7 min", "End trip")
    }

    @Test fun liveTripAsksAboutLocationOnce() {
        show { LiveTripScreen(liveState(null, listOf(9), onBoard = false), 0L, {}, {}, {}, {}, useLocation = null, repo = repo) }
        mustSee("Notice when you board?", "Use location", "No, I'll tap the button")
    }
}
