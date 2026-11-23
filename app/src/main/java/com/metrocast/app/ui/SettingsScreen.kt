package com.metrocast.app.ui

import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.metrocast.app.data.Place
import com.metrocast.app.data.Repository
import com.metrocast.app.data.Trip
import com.metrocast.app.data.StationDirectory
import com.metrocast.app.data.MetroLine
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(repo: Repository, onChanged: () -> Unit, onBack: () -> Unit, onRunSetup: (() -> Unit)? = null, scrollTo: String? = null) {
    val prefs = repo.prefs
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    // Opened for one setting (e.g. "flights" from Airports): scroll until it's on screen.
    LaunchedEffect(scrollTo) {
        val want = scrollTo ?: return@LaunchedEffect
        // Wait until the list has been laid out.
        androidx.compose.runtime.snapshotFlow { listState.layoutInfo.totalItemsCount }.first { it > 0 }
        var i = 0
        while (i < 200) {
            listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == want }?.let { listState.animateScrollToItem(it.index); return@LaunchedEffect }
            if (i >= listState.layoutInfo.totalItemsCount) return@LaunchedEffect
            listState.scrollToItem(i); i++
        }
    }
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current

    var apiKey by remember { mutableStateOf(prefs.apiKey) }
    var trips by remember { mutableStateOf(prefs.trips) }
    var fahrenheit by remember { mutableStateOf(prefs.useFahrenheit) }
    var showLocation by remember { mutableStateOf(prefs.showTrainLocation) }
    var showMap by remember { mutableStateOf(prefs.showSilverMap) }
    var mapLine by remember { mutableStateOf(prefs.homeMapLine) }
    var weatherOn by remember { mutableStateOf(prefs.weatherEnabled) }
    var locationName by remember { mutableStateOf(prefs.locationName) }

    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Place>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }

    var editorOpen by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Trip?>(null) }

    fun saveTrips(list: List<Trip>) {
        trips = list
        prefs.trips = list
        onChanged()
    }

    fun search() {
        if (query.isBlank()) return
        searching = true
        searchError = null
        scope.launch {
            results = runCatching { repo.searchPlaces(query) }
                .onFailure { searchError = "Search failed — check your connection" }
                .getOrDefault(emptyList())
            if (results.isEmpty() && searchError == null) searchError = "No places found"
            searching = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            // ---- Trips: "Going there" and "Coming back", any station to any station, then any extras
            item {
                SettingsCard(
                    title = "Home screen trips",
                    subtitle = "Your two everyday trips, shown on the home screen and widget. Pick any station to any station — transfers are worked out for you.",
                ) {
                    val going = trips.getOrNull(0)
                    val back = trips.getOrNull(1)
                    fun setSlot(i: Int, t: Trip) {
                        val list = trips.toMutableList()
                        if (i < list.size) list[i] = t else list.add(t)
                        saveTrips(list)
                    }
                    CommuteSlot(
                        role = Trip.GOING, emoji = "➡️", hint = "e.g. home to work", trip = going, repo = repo,
                        onChange = { setSlot(0, it) },
                    )
                    HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    CommuteSlot(
                        role = Trip.BACK, emoji = "⬅️", hint = "e.g. work back home", trip = back, repo = repo,
                        onChange = { if (trips.isNotEmpty()) setSlot(1, it) },
                        reverseOf = going,
                    )
                    if (trips.size > 2) {
                        Spacer(Modifier.height(10.dp))
                        Text("MORE TRIPS", style = MaterialTheme.typography.labelMedium, letterSpacing = 1.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    trips.forEachIndexed { i, t ->
                        if (i < 2) return@forEachIndexed
                        if (i > 2) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        TripRow(
                            trip = t,
                            canMoveUp = i > 2,
                            canMoveDown = i < trips.lastIndex,
                            onMoveUp = { saveTrips(trips.toMutableList().apply { add(i - 1, removeAt(i)) }) },
                            onMoveDown = { saveTrips(trips.toMutableList().apply { add(i + 1, removeAt(i)) }) },
                            onEdit = { editing = t; editorOpen = true },
                            onDelete = { saveTrips(trips.filter { it.id != t.id }) },
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    if (trips.size >= 2) {
                        OutlinedButton(onClick = { editing = null; editorOpen = true }) {
                            Icon(Icons.Default.Add, null)
                            Spacer(Modifier.width(6.dp))
                            Text("Add another trip")
                        }
                    }
                    TextButton(onClick = { saveTrips(com.metrocast.app.data.Stations.DEFAULT_TRIPS) }) {
                        Text("Reset to the starting trips")
                    }
                }
            }

            // ---- Home screen layout, look, refresh, track work heads-up
            item { HomeLayoutCard(repo, onChanged) }
            item { TripCardsCard(repo, onChanged) }
            item { MenuCard(repo) }
            item { PlannerStartCard(repo) }
            item { AppearanceCard(repo) }
            item { UnitsCard(repo, onChanged) }
            item { ClockCard(repo, onChanged) }
            item { QuietHoursCard(repo) }
            item { RefreshCard(repo) }
            item { SpeedSettingsCard(repo, onChanged) }
            // Safety: Metro Transit Police (also in the ☰ menu).
            item {
                SettingsCard(title = "Safety & help", subtitle = "Metro Transit Police and 911 — opens your phone's dialer or messages") {
                    PoliceActions(compact = true)
                }
            }

            // ---- Train location toggle
            item {
                SettingsCard(title = "Live train location") {
                    ToggleRow(
                        title = "Show where trains are",
                        subtitle = "Adds a live track map to each trip, e.g. \"At Spring Hill · 2 stops away\"",
                        checked = showLocation,
                        onChange = {
                            showLocation = it
                            prefs.showTrainLocation = it
                            onChanged()
                        },
                    )
                    Spacer(Modifier.height(12.dp))
                    ToggleRow(
                        title = "Live line map on the home screen",
                        subtitle = "Every train on one line, in real time. Pick the line below",
                        checked = showMap,
                        onChange = {
                            showMap = it
                            prefs.showSilverMap = it
                            onChanged()
                        },
                    )
                    if (showMap) {
                        Spacer(Modifier.height(8.dp))
                        Text("HOME SCREEN MAP LINE", style = MaterialTheme.typography.labelMedium, letterSpacing = 1.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        androidx.compose.foundation.layout.FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(top = 6.dp),
                        ) {
                            MetroLine.entries.forEach { l ->
                                androidx.compose.material3.FilterChip(
                                    selected = mapLine == l.code,
                                    onClick = { mapLine = l.code; prefs.homeMapLine = l.code; onChanged() },
                                    label = { Text(l.displayName) },
                                    leadingIcon = { LinePill(l.code, size = 18) },
                                )
                            }
                        }
                    }
                }
            }

            // ---- Notifications (each one off until you turn it on)
            item { NotificationSettings(repo) }
            item { PrivacyCard(repo) }
            if (com.metrocast.app.BuildConfig.AIRPORTS && com.metrocast.app.data.FlightProvider.available.isNotEmpty()) item(key = "flights") { FlightTrackingCard(repo) }

            // ---- API key (locked: tap the card 15 times within 30 seconds to unlock)
            item {
                ApiKeyCard(
                    apiKey = apiKey,
                    onChange = {
                        apiKey = it
                        prefs.apiKey = it
                        onChanged()
                    },
                    onGetKey = { runCatching { uri.openUri("https://developer.wmata.com/") } },
                )
            }

            // ---- Weather
            item {
                SettingsCard(title = "Weather") {
                    ToggleRow(
                        title = "Show weather",
                        subtitle = if (weatherOn) "On the home screen and widget" else "Off — never downloaded; the app is trains only",
                        checked = weatherOn,
                        onChange = { weatherOn = it; prefs.weatherEnabled = it; onChanged() },
                    )
                    if (weatherOn) {
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.LocationOn, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text(locationName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("Change location (city or ZIP)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { search() }),
                        trailingIcon = {
                            if (searching) CircularProgressIndicator(Modifier.padding(12.dp), strokeWidth = 2.dp)
                            else IconButton(onClick = { search() }) { Icon(Icons.Default.Search, "Search") }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    searchError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                    }
                    results.forEach { p ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val name = if (p.detail.isNotBlank()) "${p.name}, ${p.detail.substringBefore(",")}" else p.name
                                    prefs.setLocation(name, p.lat, p.lon)
                                    locationName = name
                                    results = emptyList()
                                    query = ""
                                    onChanged()
                                }
                                .padding(vertical = 10.dp),
                        ) {
                            Text(p.name, style = MaterialTheme.typography.bodyLarge)
                            Text(p.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    }
                }
            }

            onRunSetup?.let { run ->
                item {
                    OutlinedButton(onClick = run, modifier = Modifier.fillMaxWidth()) { Text("Run the setup walkthrough again") }
                }
            }
            item {
                Text(
                    "Tip: long-press your home screen → Widgets → MetroCast to add the widget.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            // ---- Crash log: only shows up if the app has crashed.
            if (com.metrocast.app.data.CrashLog.entries(repo.appContext).isNotEmpty()) item(key = "crashes") { CrashLogCard(repo) }
            // ---- Which version this is (bottom of Settings)
            item(key = "update") {
                val parts = buildList {
                    if (com.metrocast.app.BuildConfig.WMATA_KEY.isBlank()) add("Public copy") else add("Your copy")
                    if (com.metrocast.app.BuildConfig.RAIL) add("Trains")
                    if (com.metrocast.app.BuildConfig.AIRPORTS) add("Airports")
                    if (com.metrocast.app.data.Updater.enabled) add("Updates from your PC")
                }
                // The updates copy: the version comes with "Check for updates" (from your PC).
                if (com.metrocast.app.data.Updater.enabled) UpdatesCard(repo, parts.joinToString(" · "))
                else Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("MetroCast version ${com.metrocast.app.BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    if (editorOpen) {
        TripEditorDialog(
            initial = editing,
            repo = repo,
            onDismiss = { editorOpen = false },
            onSave = { saved ->
                val existing = trips.indexOfFirst { it.id == saved.id }
                saveTrips(if (existing >= 0) trips.toMutableList().apply { set(existing, saved) } else trips + saved)
                editorOpen = false
            },
        )
    }
}

