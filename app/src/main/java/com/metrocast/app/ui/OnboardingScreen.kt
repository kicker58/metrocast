package com.metrocast.app.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metrocast.app.data.Place
import com.metrocast.app.data.Repository
import com.metrocast.app.data.StationDirectory
import com.metrocast.app.data.Trip
import kotlinx.coroutines.launch

/**
 * First run: a short walkthrough — privacy, your usual trip (and the trip back), weather by ZIP
 * code, and whether to use location and notifications. Every step can be skipped; all of it can be
 * changed later in Settings.
 */
@Composable
fun OnboardingScreen(repo: Repository, onDone: () -> Unit) {
    val p = repo.prefs
    val steps = 7
    var step by rememberSaveable { mutableIntStateOf(0) }
    var from by rememberSaveable { mutableStateOf<String?>(null) }
    var to by rememberSaveable { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf<String?>(null) }
    var going by remember { mutableStateOf<Trip?>(null) }
    var addBack by rememberSaveable { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var place by rememberSaveable { mutableStateOf(p.locationName) }
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Place>>(emptyList()) }
    var weatherOn by rememberSaveable { mutableStateOf(p.weatherEnabled) }
    var useLocation by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var notifications by rememberSaveable { mutableStateOf<Boolean?>(null) }
    // A copy without a built-in key: ask for the person's own (free) WMATA key first.
    val needsKey = remember { p.apiKey.isBlank() }
    var key by rememberSaveable { mutableStateOf("") }
    val keyOk = key.trim().matches(Regex("[0-9a-fA-F]{32}"))
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        val ok = r.values.any { it }; useLocation = ok; p.liveUseLocation = ok; step++
    }
    val askNotify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        notifications = ok; step++
    }

    fun finish() {
        p.onboarded = true
        runCatching { com.metrocast.app.notify.Background.sync(context) }
        onDone()
    }

    /** Saves the usual trip (and the way back) as the home screen's two trips. */
    fun saveTrips(next: () -> Unit) {
        val f = from ?: return; val t = to ?: return
        busy = true; error = null
        scope.launch {
            val g = repo.buildTripLoading(f, t, Trip.GOING, "default-going")
            val b = if (addBack) repo.buildTripLoading(t, f, Trip.BACK, "default-back") else null
            busy = false
            if (g == null) { error = "Couldn't load the station list — check your connection and try again, or skip for now."; return@launch }
            going = g
            p.trips = listOfNotNull(g, b)
            next()
        }
    }

    // A Surface sets the text colour to match the background (readable in light and dark mode).
    Surface(color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground, modifier = Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        // Progress dots and Skip.
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
                repeat(steps) { i ->
                    Box(Modifier.size(if (i == step) 10.dp else 8.dp).background(
                        if (i <= step) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, CircleShape,
                    ))
                }
            }
            if (step < steps - 1) TextButton(onClick = { finish() }) { Text("Skip setup") }
        }

        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            when (step) {
                // ---------------------------------------------------------------- welcome + privacy
                0 -> {
                    Text("🚇", fontSize = 56.sp)
                    Title("Welcome to MetroCast")
                    Body("Live DC Metro times for the trips you take every day, plus the weather. A few quick questions and your home screen will be ready.")
                    Privacy(full = true)
                    if (needsKey) {
                        Text("Your WMATA key", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Body("Train times come straight from WMATA, which asks each app user for a free key. It takes a minute: sign up, choose the free \"Default Tier\" product, and copy your Primary key here.")
                        OutlinedButton(onClick = { runCatching { uri.openUri("https://developer.wmata.com/") } }) { Text("Get a free key at developer.wmata.com ›") }
                        OutlinedTextField(
                            value = key, onValueChange = { key = it.trim() }, singleLine = true,
                            label = { Text("Paste your WMATA key") },
                            isError = key.isNotBlank() && !keyOk,
                            supportingText = { Text(if (key.isNotBlank() && !keyOk) "A key is 32 letters and numbers" else "Saved only on this phone") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                // ---------------------------------------------------------------- the usual trip
                1 -> {
                    Title("Your usual trip")
                    Body("Where do you normally get on and off? Any stations — transfers are worked out for you. This becomes your “Going there” trip on the home screen.")
                    StationField("I usually get on at", from?.let { StationDirectory.nameOf(it) }) { picking = "from" }
                    StationField("I usually get off at", to?.let { StationDirectory.nameOf(it) }) { picking = "to" }
                    if (from != null && from == to) Text("Pick two different stations", color = MaterialTheme.colorScheme.error)
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (busy) Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(10.dp)); Text("Working out your route…")
                    }
                    SmallNote("Not sure yet? Skip — you'll start with ${com.metrocast.app.data.Stations.DEFAULT_TRIPS.first().stationName} ⇄ ${com.metrocast.app.data.Stations.DEFAULT_TRIPS.first().towardName} and can change it in Settings.")
                }
                // ---------------------------------------------------------------- the trip back
                2 -> {
                    val back = to?.let { StationDirectory.nameOf(it) }.orEmpty()
                    val start = from?.let { StationDirectory.nameOf(it) }.orEmpty()
                    Title("The trip back?")
                    Body("Add the reverse too, so the home screen shows both directions:")
                    going?.let { g ->
                        TripSummary("➡️ Going there", "${g.stationName} → ${g.destName}", g.via)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { addBack = !addBack }) {
                        Column(Modifier.weight(1f)) {
                            Text("⬅️ Coming back", fontWeight = FontWeight.Bold)
                            Text("$back → $start", style = MaterialTheme.typography.bodyMedium)
                        }
                        Switch(checked = addBack, onCheckedChange = null)
                    }
                }
                // ---------------------------------------------------------------- weather
                3 -> {
                    Title("Weather where you are")
                    Body("Enter your ZIP code (or a city) for the forecast on your home screen.")
                    OutlinedTextField(
                        value = query, onValueChange = { query = it; error = null },
                        label = { Text("ZIP code or city") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = {
                            busy = true
                            scope.launch {
                                results = runCatching { repo.searchPlaces(query) }.getOrElse { error = "Couldn't search — check your connection"; emptyList() }
                                if (results.isEmpty() && error == null) error = "Nothing found for “$query”"
                                busy = false
                            }
                        }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedButton(onClick = {
                        busy = true
                        scope.launch {
                            results = runCatching { repo.searchPlaces(query) }.getOrElse { error = "Couldn't search — check your connection"; emptyList() }
                            if (results.isEmpty() && error == null) error = "Nothing found for “$query”"
                            busy = false
                        }
                    }, enabled = query.isNotBlank() && !busy) { Text("Search") }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    results.forEach { r ->
                        val name = if (r.detail.isNotBlank()) "${r.name}, ${r.detail.substringBefore(",")}" else r.name
                        OutlinedCard(onClick = { p.setLocation(name, r.lat, r.lon); place = name; weatherOn = true; p.weatherEnabled = true; results = emptyList(); query = "" }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Text(r.name, fontWeight = FontWeight.SemiBold); Text(r.detail, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                        Text(if (weatherOn) "📍 Weather for: $place" else "Weather is off", modifier = Modifier.padding(12.dp), fontWeight = FontWeight.SemiBold)
                    }
                    TextButton(onClick = { weatherOn = !weatherOn; p.weatherEnabled = weatherOn }) {
                        Text(if (weatherOn) "I don't want weather — trains only" else "Turn weather back on")
                    }
                }
                // ---------------------------------------------------------------- location
                4 -> {
                    Text("📍", fontSize = 44.sp)
                    Title("Notice when you board?")
                    Body("During a live trip, MetroCast can use your phone's location to tell when you've got on the train and follow it for you — no tapping needed.")
                    Bullets(
                        "Only while a live trip is running, and only on this phone.",
                        "Your location is never saved or sent anywhere.",
                        "Prefer not to? You'll just tap “I'm on the train”.",
                    )
                }
                // ---------------------------------------------------------------- notifications
                5 -> {
                    Text("🔔", fontSize = 44.sp)
                    Title("Notifications for live trips")
                    Body("When you follow a trip live, MetroCast gives you a heads-up 2 stops before each transfer: run, hurry or take your time.")
                    Body("That's all, unless you want more. Track work, delays on your lines, elevator outages and special trains can each be turned on in Settings → Notifications.")
                    SmallNote("Notifications are made on your phone from WMATA's public data — nothing is sent anywhere.")
                }
                // ---------------------------------------------------------------- done
                else -> {
                    Text("✅", fontSize = 56.sp)
                    Title("You're all set")
                    p.trips.filter { it.onHome }.forEach { t ->
                        TripSummary(if (t.label == Trip.BACK) "⬅️ Coming back" else "➡️ Going there", "${t.stationName} → ${t.destName}", t.via)
                    }
                    Body(
                        listOfNotNull(
                            if (p.weatherEnabled) "Weather for ${p.locationName}." else "No weather.",
                            if (p.liveUseLocation == true) "Location helps on live trips." else "You'll tap “I'm on the train” on live trips.",
                            if (notifications == false) "Notifications are off — you can allow them in Settings later." else null,
                        ).joinToString(" "),
                    )
                    Privacy(full = false)
                    SmallNote("You can change any of this in ☰ → Settings, or run this setup again from there.")
                }
            }
        }

        // Back / Next.
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            if (step > 0 && step < steps - 1) OutlinedButton(onClick = { error = null; step-- }, modifier = Modifier.weight(1f).height(52.dp)) { Text("Back") }
            when (step) {
                1 -> {
                    OutlinedButton(onClick = { error = null; step = 3 }, modifier = Modifier.weight(1f).height(52.dp)) { Text("Skip") }
                    Button(
                        onClick = { saveTrips { step = 2 } },
                        enabled = from != null && to != null && from != to && !busy,
                        modifier = Modifier.weight(1f).height(52.dp),
                    ) { Text("Next") }
                }
                2 -> Button(onClick = { saveTrips { step = 3 } }, enabled = !busy, modifier = Modifier.weight(1f).height(52.dp)) { Text("Next") }
                4 -> {
                    OutlinedButton(onClick = { useLocation = false; p.liveUseLocation = false; step++ }, modifier = Modifier.weight(1f).height(52.dp)) { Text("No thanks") }
                    Button(onClick = {
                        runCatching { askLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }
                            .onFailure { p.liveUseLocation = false; step++ }
                    }, modifier = Modifier.weight(1f).height(52.dp)) { Text("Use location") }
                }
                5 -> {
                    OutlinedButton(onClick = {
                        notifications = false; step++
                    }, modifier = Modifier.weight(1f).height(52.dp)) { Text("Not now") }
                    Button(onClick = {
                        if (Build.VERSION.SDK_INT >= 33 && !com.metrocast.app.notify.Notifier.canPost(context)) {
                            runCatching { askNotify.launch(Manifest.permission.POST_NOTIFICATIONS) }.onFailure { notifications = true; step++ }
                        } else { notifications = true; step++ }
                    }, modifier = Modifier.weight(1f).height(52.dp)) { Text("Allow") }
                }
                steps - 1 -> Button(onClick = { finish() }, modifier = Modifier.weight(1f).height(52.dp)) { Text("Start using MetroCast", fontWeight = FontWeight.Bold) }
                0 -> Button(
                    onClick = { if (needsKey && keyOk) { p.apiKey = key.trim(); scope.launch { runCatching { repo.refresh() } } }; error = null; step++ },
                    enabled = !needsKey || keyOk || key.isBlank(),
                    modifier = Modifier.weight(1f).height(52.dp),
                ) { Text(if (needsKey && !keyOk) "Get started (add a key later)" else "Get started") }
                else -> Button(onClick = { error = null; step++ }, modifier = Modifier.weight(1f).height(52.dp)) { Text("Next") }
            }
        }
    }

    }

    picking?.let { which ->
        StationPickerDialog(
            title = if (which == "from") "Where do you get on?" else "Where do you get off?",
            repo = repo,
            selected = if (which == "from") from else to,
            onPick = { s -> if (which == "from") from = s.code else to = s.code; picking = null; error = null },
            onDismiss = { picking = null },
        )
    }
}

@Composable
private fun Title(t: String) = Text(t, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

@Composable
private fun Body(t: String) = Text(t, style = MaterialTheme.typography.bodyLarge)

@Composable
private fun SmallNote(t: String) = Text(t, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun Bullets(vararg items: String) = Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    items.forEach { Row { Text("•  ", fontWeight = FontWeight.Bold); Text(it, style = MaterialTheme.typography.bodyMedium) } }
}

/** What stays private, in plain words. */
@Composable
private fun Privacy(full: Boolean) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("🔒 Your data stays on this phone", fontWeight = FontWeight.Bold)
            if (full) {
                Bullets(
                    "No account, no sign-in, no tracking or ads.",
                    "Your trips, settings and history are saved only on this phone — never uploaded or shared.",
                    "The app only asks WMATA for train times and Open-Meteo for the forecast. Neither is told who you are.",
                    "Location (if you allow it) is used for a moment during a live trip, then thrown away.",
                )
            } else {
                Text("Nothing is shared — everything you set up lives only on this phone.", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun StationField(label: String, name: String?, onClick: () -> Unit) {
    OutlinedCard(onClick = onClick, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(label.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(name ?: "Choose a station ▾", style = MaterialTheme.typography.titleMedium,
                fontWeight = if (name != null) FontWeight.Bold else FontWeight.Normal,
                color = if (name != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun TripSummary(role: String, route: String, via: String?) {
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(role, fontWeight = FontWeight.Bold)
            Text(route, style = MaterialTheme.typography.bodyMedium)
            via?.let { Text("🔁 $it", style = MaterialTheme.typography.bodySmall) }
        }
    }
}
