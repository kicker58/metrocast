package com.metrocast.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    /** A screen a notification asked for ("update"); read once by the app. */
    private val openRequest = androidx.compose.runtime.mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) openRequest.value = intent?.getStringExtra(com.metrocast.app.notify.Notifier.OPEN)
        setContent {
            MetroCastTheme { MetroCastRoot(openRequest = openRequest.value, onOpened = { openRequest.value = null }) }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(com.metrocast.app.notify.Notifier.OPEN)?.let { openRequest.value = it }
    }
}

@Composable
private fun MetroCastRoot(vm: MainViewModel = viewModel(), openRequest: String? = null, onOpened: () -> Unit = {}) {
    // First run: the walkthrough before anything else.
    var onboarding by rememberSaveable { mutableStateOf(vm.repo.needsOnboarding()) }
    // Re-running the walkthrough from Settings: Back cancels it and returns to Settings, nothing changed.
    var rerunSetup by rememberSaveable { mutableStateOf(false) }
    var backToSettings by rememberSaveable { mutableStateOf(false) }
    if (onboarding) {
        BackHandler(enabled = rerunSetup) { onboarding = false; rerunSetup = false; backToSettings = true }
        OnboardingScreen(vm.repo, onDone = { onboarding = false; rerunSetup = false; vm.reloadFromCache(); vm.refresh() })
        return
    }
    var showSettings by rememberSaveable { mutableStateOf(backToSettings) }
    LaunchedEffect(Unit) { backToSettings = false }
    // Just updated: "What's new", once.
    var whatsNew by androidx.compose.runtime.remember { mutableStateOf(runCatching { vm.repo.whatsNewToShow() }.getOrNull()) }
    whatsNew?.let { lines ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { whatsNew = null },
            title = { androidx.compose.material3.Text("What's new in ${com.metrocast.app.BuildConfig.VERSION_NAME}") },
            text = {
                androidx.compose.foundation.layout.Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(androidx.compose.ui.unit.Dp(8f))) {
                    lines.forEach { androidx.compose.material3.Text("•  $it", style = androidx.compose.material3.MaterialTheme.typography.bodyMedium) }
                }
            },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { whatsNew = null }) { androidx.compose.material3.Text("Got it") } },
        )
    }
    // Live map: which line, and optionally the trip it was opened from.
    var mapLine by rememberSaveable { mutableStateOf<String?>(null) }
    var mapTripId by rememberSaveable { mutableStateOf<String?>(null) }
    var showPlanner by rememberSaveable { mutableStateOf(false) }
    var showLive by rememberSaveable { mutableStateOf(false) }
    var showIssues by rememberSaveable { mutableStateOf(false) }
    var showSaved by rememberSaveable { mutableStateOf(false) }
    var showLiveTrip by rememberSaveable { mutableStateOf(false) }
    var showHistory by rememberSaveable { mutableStateOf(false) }
    var showRestrooms by rememberSaveable { mutableStateOf(false) }
    var showTrackWork by rememberSaveable { mutableStateOf(false) }
    var showScenic by rememberSaveable { mutableStateOf(false) }
    var showRail by rememberSaveable { mutableStateOf(false) }
    var showAirports by rememberSaveable { mutableStateOf(false) }
    var settingsFocus by rememberSaveable { mutableStateOf<String?>(null) }
    // From the "update ready" notification: Settings, at Check for updates.
    LaunchedEffect(openRequest) {
        if (openRequest == "update") { settingsFocus = "update"; showSettings = true; onOpened() }
    }
    var showBikes by rememberSaveable { mutableStateOf(false) }
    var showPolice by rememberSaveable { mutableStateOf(false) }
    var showSpecial by rememberSaveable { mutableStateOf(false) }
    var locTick by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    // Live-trip notifications need permission on Android 13+; asked when a live trip starts.
    val askNotify = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { }
    val state by vm.state.collectAsStateWithLifecycle()

    // Live refresh every 30 seconds while the app is on screen.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                vm.refresh()
                // Faster while following a trip live; otherwise as chosen in Settings (0 = only on pull-down).
                val every = vm.repo.prefs.refreshSeconds
                if (showLiveTrip || state.live != null) delay(15_000)
                else if (every == 0) { kotlinx.coroutines.awaitCancellation() }
                else delay(every * 1_000L)
            }
        }
    }

    // Back online (e.g. the phone's connection woke up after the app opened): load trains right away.
    val online = rememberOnline()
    var wasOffline by remember { mutableStateOf(false) }
    LaunchedEffect(online) {
        if (!online) wasOffline = true
        else if (wasOffline) { wasOffline = false; vm.refresh() }
    }

    fun closeMap() { mapLine = null; mapTripId = null }

    // The trip ended by itself (7 min after arriving): back to the home screen.
    var hadLiveTrip by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    LaunchedEffect(state.live) {
        if (state.live != null) hadLiveTrip = true
        else if (hadLiveTrip) {
            hadLiveTrip = false
            showLiveTrip = false
            com.metrocast.app.notify.LiveTripTicker.cancel(context)
        }
    }

    // Live trip: check the "2 stops from the transfer" notification on every update.
    LaunchedEffect(state.live) {
        if (state.live != null) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                runCatching { vm.repo.checkLiveNotification(context) }
            }
        }
    }

    BackHandler(enabled = mapLine != null && !showSettings) { closeMap() }
    BackHandler(enabled = showLiveTrip && mapLine == null && !showSettings) { showLiveTrip = false }
    // Planner opened from Saved routes goes back there.
    BackHandler(enabled = showPlanner && showSaved && mapLine == null && !showSettings) { showPlanner = false }
    BackHandler(enabled = !(showPlanner && showSaved) && (showPlanner || showLive || showIssues || showSaved || showHistory || showRestrooms || showTrackWork || showScenic || showBikes || showPolice || showSpecial) && mapLine == null && !showSettings) {
        showPlanner = false; showLive = false; showIssues = false; showSaved = false; showHistory = false; showRestrooms = false; showTrackWork = false; showScenic = false; showBikes = false; showPolice = false; showSpecial = false
    }
    BackHandler(enabled = showRail && mapLine == null && !showSettings) { showRail = false }
    BackHandler(enabled = showAirports && !showRail && mapLine == null && !showSettings) { showAirports = false }
    BackHandler(enabled = showSettings) {
        settingsFocus = null
        showSettings = false
        vm.refresh()
    }

    AnimatedContent(
        targetState = when {
            showSettings -> "settings"
            mapLine != null -> "map"
            showLiveTrip -> "livetrip"
            showLive -> "live"
            showRail -> "rail"
            showAirports -> "airports"
            showPlanner -> "planner"
            showSaved -> "saved"
            showHistory -> "history"
            showRestrooms -> "restrooms"
            showTrackWork -> "trackwork"
            showScenic -> "scenic"
            showBikes -> "bikes"
            showPolice -> "police"
            showSpecial -> "special"
            showIssues -> "issues"
            else -> "home"
        },
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "screen",
    ) { screen ->
        when (screen) {
            "map" -> {
                val line = mapLine ?: "SV"
                val focus = state.snapshot.trips.firstOrNull { it.trip.id == mapTripId }
                LineMapScreen(
                    lineCode = line,
                    map = state.maps[line],
                    focus = focus,
                    highlight = focus?.let { setOf(it.trip.stationCode, it.trip.towardCode) }
                        ?: state.snapshot.trips.filter { it.trip.lineCode == line }.map { it.trip.stationCode }.toSet(),
                    onBack = { closeMap() },
                )
            }
            "saved" -> SavedRoutesScreen(
                routes = state.snapshot.savedRoutes,
                onOpen = { r -> vm.repo.setPlan(r.from?.code, r.to?.code); vm.reloadFromCache(); vm.refresh(); showPlanner = true },
                onRemove = { r ->
                    val f = r.from?.code; val t = r.to?.code
                    if (f != null && t != null) vm.repo.toggleSavedRoute(f, t)
                    vm.reloadFromCache()
                },
                onBack = { showSaved = false },
            )
            "police" -> PoliceScreen(onBack = { showPolice = false })
            "special" -> SpecialScreen(vm.repo, onBack = { showSpecial = false })
            "bikes" -> BikesScreen(
                repo = vm.repo,
                onBack = { showBikes = false },
                onPlan = { from, to ->
                    vm.repo.setPlan(from ?: vm.repo.prefs.planFrom, to)
                    showBikes = false; showPlanner = true
                    vm.reloadFromCache(); vm.refresh()
                },
            )
            "scenic" -> ScenicScreen(
                onBack = { showScenic = false },
                onPlan = { from, to ->
                    vm.repo.setPlan(from ?: vm.repo.prefs.planFrom, to)
                    showScenic = false; showPlanner = true
                    vm.reloadFromCache(); vm.refresh()
                },
            )
            "trackwork" -> TrackWorkScreen(vm.repo, onBack = { showTrackWork = false; vm.reloadFromCache() })
            "restrooms" -> RestroomsScreen(
                onBack = { showRestrooms = false },
                onPlan = { from, to ->
                    vm.repo.setPlan(from ?: vm.repo.prefs.planFrom, to ?: vm.repo.prefs.planTo?.takeIf { it != from })
                    showRestrooms = false; showPlanner = true
                    vm.reloadFromCache(); vm.refresh()
                },
            )
            "history" -> HistoryScreen(vm.repo.prefs, onBack = { showHistory = false })
            "issues" -> IssuesScreen(repo = vm.repo, onBack = { showIssues = false; vm.reloadFromCache() })
            "livetrip" -> LiveTripScreen(
                live = state.live,
                updatedAt = state.snapshot.trainsUpdatedAt,
                onBoard = {
                    vm.repo.boardLiveTrain()
                    com.metrocast.app.notify.LiveTripTicker.schedule(context)
                    vm.reloadFromCache(); vm.refresh()
                },
                onOff = { vm.repo.nextLiveLeg(); vm.reloadFromCache(); vm.refresh() },
                onEnd = {
                    vm.repo.endLiveTrip()
                    com.metrocast.app.notify.LiveTripTicker.cancel(context)
                    showLiveTrip = false
                    vm.reloadFromCache()
                },
                onBack = { showLiveTrip = false },
                repo = vm.repo,
                onPreviousStep = { vm.repo.previousLiveLeg(); vm.reloadFromCache(); vm.refresh() },
                useLocation = vm.repo.prefs.liveUseLocation.also { locTick },
                autoBoarded = vm.repo.prefs.liveAutoBoarded,
                onLocationChoice = { yes -> vm.repo.prefs.liveUseLocation = yes; locTick++ },
                onLocation = { lat, lon, speed ->
                    scope.launch {
                        val boarded = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                            runCatching { vm.repo.onLiveLocation(lat, lon, speed) }.getOrDefault(false)
                        }
                        if (boarded) {
                            com.metrocast.app.notify.LiveTripTicker.schedule(context)
                            vm.reloadFromCache(); vm.refresh()
                        }
                    }
                },
            )
            "live" -> LiveTrainsScreen(maps = state.maps, onBack = { showLive = false })
            "airports" -> if (com.metrocast.app.BuildConfig.AIRPORTS) AirportScreen(
                repo = vm.repo,
                onBack = { showAirports = false },
                onPlan = { from, to -> vm.repo.setPlan(from, to); vm.reloadFromCache(); vm.refresh(); showAirports = false; showPlanner = true },
                onOpenSettings = { settingsFocus = "flights"; showSettings = true },
            )
            "rail" -> if (com.metrocast.app.BuildConfig.RAIL) RailScreen(
                repo = vm.repo,
                onBack = { showRail = false },
                onPlan = { from, to -> vm.repo.setPlan(from, to); vm.reloadFromCache(); vm.refresh(); showRail = false; showPlanner = true },
            )
            "planner" -> PlannerScreen(
                // Hidden in Settings → Menu = no button in the planner either.
                onOpenRail = if (com.metrocast.app.BuildConfig.RAIL && vm.repo.prefs.menuItems.any { it.first == com.metrocast.app.data.MenuItem.RAIL && it.second }) ({ showRail = true }) else null,
                plan = state.snapshot.plan,
                repo = vm.repo,
                onChange = { from, to -> vm.repo.setPlan(from, to); vm.reloadFromCache(); vm.refresh() },
                onBack = { showPlanner = false },
                onSavedChanged = { vm.reloadFromCache() },
                onStartLive = {
                    val f = vm.repo.prefs.planFrom; val t = vm.repo.prefs.planTo
                    if (f != null && t != null) {
                        vm.repo.startLiveTrip(f, t)
                        if (!com.metrocast.app.notify.Notifier.canPost(context) && android.os.Build.VERSION.SDK_INT >= 33) {
                            askNotify.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                        }
                        showPlanner = false
                        showLiveTrip = true
                        vm.reloadFromCache(); vm.refresh()
                    }
                },
            )
            "settings" -> SettingsScreen(
                repo = vm.repo,
                scrollTo = settingsFocus,
                onChanged = vm::reloadFromCache,
                onRunSetup = { showSettings = false; rerunSetup = true; onboarding = true },
                onBack = {
                    showSettings = false
                    settingsFocus = null
                    vm.refresh()
                },
            )
            else -> HomeScreen(
                state = state,
                repo = vm.repo,
                onRefresh = { vm.refresh(userInitiated = true) },
                onOpenSettings = { showSettings = true },
                onOpenMap = if (vm.repo.prefs.showSilverMap) ({ mapLine = vm.repo.prefs.homeMapLine; mapTripId = null; vm.refresh() }) else null,
                onOpenTrip = { status -> mapLine = status.trip.lineCode; mapTripId = status.trip.id; vm.refresh() },
                onTripsChanged = { vm.reloadFromCache(); vm.refresh() },
                onOpenPlanner = { showPlanner = true },
                onOpenLive = { showLive = true; vm.refresh() },
                onOpenIssues = { showIssues = true },
                onOpenSaved = { showSaved = true; vm.refresh() },
                onOpenHistory = { showHistory = true },
                onOpenRestrooms = { showRestrooms = true },
                onOpenTrackWork = { showTrackWork = true },
                onOpenScenic = { showScenic = true },
                onOpenAirports = { showAirports = true },
                onOpenRail = { showRail = true },
                onOpenBikes = { showBikes = true },
                onOpenPolice = { showPolice = true },
                onOpenSpecial = { showSpecial = true },
                liveTripActive = state.live != null,
                onOpenLiveTrip = { showLiveTrip = true; vm.refresh() },
                onOpenLineMap = { line -> mapLine = line; mapTripId = null; vm.refresh() },
            )
        }
    }
}
