package com.metrocast.app.ui

// The cards that make up Settings (split out of SettingsScreen.kt to keep each file manageable).

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

@Composable
internal fun SettingsCard(title: String, subtitle: String? = null, content: @Composable () -> Unit) {
    ElevatedCard(shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
internal fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    // The whole row is the switch: tap the words or the switch.
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().toggleable(value = checked, role = androidx.compose.ui.semantics.Role.Switch, onValueChange = onChange),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
internal fun TripRow(
    trip: Trip,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        LinePill(trip.lineCode, size = 34)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(trip.label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Text(
                "${trip.stationName} → ${trip.destName}" + (if (!trip.onHome) " · widget only" else if (trip.showOnWidget) "" else " · hidden on widget"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column {
            IconButton(onClick = onMoveUp, enabled = canMoveUp) { Icon(Icons.Default.KeyboardArrowUp, "Move up") }
            IconButton(onClick = onMoveDown, enabled = canMoveDown) { Icon(Icons.Default.KeyboardArrowDown, "Move down") }
        }
        IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, "Edit") }
        IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Delete") }
    }
}

/** Trip history, location and train-alert notifications, each with an on/off switch. */
@Composable
internal fun NotificationSettings(repo: Repository) {
    val prefs = repo.prefs
    val context = androidx.compose.ui.platform.LocalContext.current
    var alerts by remember { mutableStateOf(prefs.alertNotifications) }
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    // Android 13+ asks before an app can post notifications.
    val ask = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { ok -> if (ok) pending?.invoke(); pending = null }
    fun withPermission(action: () -> Unit) {
        if (com.metrocast.app.notify.Notifier.canPost(context)) action()
        else { pending = action; ask.launch(android.Manifest.permission.POST_NOTIFICATIONS) }
    }
    fun apply() = com.metrocast.app.notify.Background.sync(context)

    var trackwork by remember { mutableStateOf(prefs.trackWorkAlerts) }
    var elevators by remember { mutableStateOf(prefs.bikeElevatorAlerts) }
    var special by remember { mutableStateOf(prefs.specialNewsAlerts) }
    var days by remember { androidx.compose.runtime.mutableIntStateOf(prefs.trackWorkDays) }
    var major by remember { mutableStateOf(prefs.trackWorkMajorOnly) }
    fun toggle(on: Boolean, set: (Boolean) -> Unit) {
        if (on) withPermission { set(true); apply() } else { set(false); apply() }
    }

    SettingsCard(title = "Notifications", subtitle = "Only live trips notify you unless you turn more on here") {
        // Always on: the reason to start a live trip.
        Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(10.dp)) {
                Text("🚆 Live trips — always on", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Text("While you follow a trip live: a heads-up 2 stops before each transfer (run, hurry or take your time)", style = MaterialTheme.typography.bodySmall)
            }
        }
        Label("MORE, IF YOU WANT THEM")
        ToggleRow("Track work on my trips", if (trackwork) "$days day${if (days == 1) "" else "s"} ahead" + if (major) ", only when my trip is disrupted" else "" else "Planned work, a few days ahead", trackwork) {
            toggle(it) { v -> trackwork = v; prefs.trackWorkAlerts = v }
        }
        if (trackwork) {
            Choices(listOf(1 to "1 day ahead", 3 to "3 days", 7 to "A week"), days) { days = it; prefs.trackWorkDays = it }
            Choices(listOf(false to "Any work on my lines", true to "Only when my trip is disrupted"), major) { major = it; prefs.trackWorkMajorOnly = it }
        }
        Spacer(Modifier.height(10.dp))
        ToggleRow("Delays and alerts on my lines", "When WMATA posts a delay or alert on a line you ride", alerts) {
            toggle(it) { v -> alerts = v; prefs.alertNotifications = v }
        }
        Spacer(Modifier.height(10.dp))
        ToggleRow("Elevator out at my stations", "Handy with a bike, stroller or wheelchair", elevators) {
            toggle(it) { v -> elevators = v; prefs.bikeElevatorAlerts = v }
        }
        Spacer(Modifier.height(10.dp))
        ToggleRow("Special trains & events", "Holiday trains and extra service (checked about once a month)", special) {
            toggle(it) { v -> special = v; prefs.specialNewsAlerts = v }
        }
    }
}

/** Trip history and location: what stays on this phone. */
@Composable
internal fun PrivacyCard(repo: Repository) {
    val prefs = repo.prefs
    SettingsCard(title = "Privacy & data", subtitle = "Everything stays on this phone") {
        var history by remember { mutableStateOf(prefs.historyEnabled) }
        ToggleRow(
            title = "Trip history",
            subtitle = "Keeps finished live trips on this phone for weekly time and money. Off deletes it",
            checked = history,
            onChange = { history = it; prefs.historyEnabled = it },
        )
        Spacer(Modifier.height(12.dp))
        var useLoc by remember { mutableStateOf(prefs.liveUseLocation == true) }
        ToggleRow(
            title = "Use location during live trips",
            subtitle = "Notices when your train leaves with you on it. Only while the live screen is open; never saved or sent anywhere",
            checked = useLoc,
            onChange = { useLoc = it; prefs.liveUseLocation = it },
        )
    }
}

@Composable
internal fun Stepper(label: String, value: String, step: Int = 1, onStep: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        IconButton(onClick = { onStep(-step) }) { Text("−", style = MaterialTheme.typography.titleLarge) }
        Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
        IconButton(onClick = { onStep(step) }) { Text("+", style = MaterialTheme.typography.titleLarge) }
    }
}

/**
 * The WMATA key, locked against accidental edits: tap the card 15 times within 30 seconds to unlock.
 * It locks again whenever Settings is closed.
 */
@Composable
internal fun ApiKeyCard(apiKey: String, onChange: (String) -> Unit, onGetKey: () -> Unit) {
    val needed = 15
    val windowMs = 30_000L
    // Nothing to protect yet (a public build with no key entered): open straight away.
    var unlocked by remember { mutableStateOf(apiKey.isBlank()) }
    var taps by remember { mutableStateOf(listOf<Long>()) }
    val recent = taps.size

    SettingsCard(title = "WMATA API key", subtitle = if (unlocked) "Unlocked — locks again when you leave Settings" else "Locked") {
        if (!unlocked) {
            androidx.compose.material3.Surface(
                onClick = {
                    val now = System.currentTimeMillis()
                    taps = (taps + now).filter { now - it <= windowMs }
                    if (taps.size >= needed) { unlocked = true; taps = emptyList() }
                },
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(14.dp)) {
                    Text("🔒", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        // Only the last 4 characters are shown.
                        Text("•••• •••• •••• " + apiKey.takeLast(4), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                        Text(
                            if (recent >= 3) "${needed - recent} more taps to unlock" else "Tap 15 times within 30 seconds to unlock",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        } else {
            OutlinedTextField(
                value = apiKey,
                onValueChange = onChange,
                label = { Text("Primary key") },
                supportingText = { Text("Leave empty to use the key built into the app") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row {
                TextButton(onClick = onGetKey) { Text("Get a free key at developer.wmata.com") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { unlocked = false }) { Text("Lock") }
            }
        }
    }
}

/**
 * One everyday trip ("Going there" or "Coming back"): a big role title, then From and To, each a
 * button that opens the full station list (any line), and the route it works out.
 */
@Composable
internal fun CommuteSlot(role: String, emoji: String, hint: String, trip: Trip?, repo: Repository, onChange: (Trip) -> Unit, reverseOf: Trip? = null) {
    var picking by remember { mutableStateOf<String?>(null) }   // "from" / "to"
    var from by remember(trip?.id, trip?.stationCode) { mutableStateOf(trip?.stationCode) }
    var to by remember(trip?.id, trip?.destCode) { mutableStateOf(trip?.destCode) }
    var error by remember { mutableStateOf<String?>(null) }
    fun save(f: String?, t: String?) {
        from = f; to = t; error = null
        if (f == null || t == null) return
        if (StationDirectory.find(f)?.code == StationDirectory.find(t)?.code) { error = "Start and end are the same station"; return }
        val built = repo.buildTrip(f, t, role, id = trip?.id ?: java.util.UUID.randomUUID().toString(), showOnWidget = trip?.showOnWidget ?: true)
        if (built == null) error = "Couldn't find a route — try again once stations have loaded" else onChange(built)
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(emoji, fontSize = 20.sp)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(role, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (from != null && to != null) {
                IconButton(onClick = { save(to, from) }) { Text("⇅", fontSize = 20.sp) }
            }
        }
        StationButton("FROM", from?.let { StationDirectory.nameOf(it) ?: trip?.stationName }) { picking = "from" }
        StationButton("TO", to?.let { StationDirectory.nameOf(it) ?: trip?.destName }) { picking = "to" }
        if (trip != null && from == trip.stationCode && to == trip.destCode) {
            Text(
                trip.via?.let { "🔁 $it" } ?: "${MetroLine.fromCode(trip.lineCode).displayName} Line · no transfer",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (reverseOf != null) {
            val isReverse = trip != null && trip.stationCode.let { StationDirectory.find(it)?.code } == StationDirectory.find(reverseOf.destCode)?.code &&
                StationDirectory.find(trip.destCode)?.code == StationDirectory.find(reverseOf.stationCode)?.code
            if (!isReverse) {
                TextButton(onClick = { save(reverseOf.destCode, reverseOf.stationCode) }) {
                    Text("Use the reverse of ${Trip.GOING.lowercase()} (${reverseOf.destName} → ${reverseOf.stationName})", maxLines = 2)
                }
            }
        }
    }
    if (picking != null) {
        StationPickerDialog(
            title = if (picking == "from") "$role: start at" else "$role: going to",
            repo = repo,
            selected = if (picking == "from") from else to,
            onPick = { st -> val which = picking; picking = null; if (which == "from") save(st.code, to) else save(from, st.code) },
            onDismiss = { picking = null },
        )
    }
}

@Composable
internal fun StationButton(label: String, name: String?, onClick: () -> Unit) {
    androidx.compose.material3.OutlinedCard(onClick = onClick, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelMedium, letterSpacing = 1.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(52.dp))
            Text(name ?: "Choose a station", style = MaterialTheme.typography.bodyLarge, fontWeight = if (name != null) FontWeight.SemiBold else FontWeight.Normal,
                color = if (name != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text("▾", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}


// ------------------------------------------------------------------ customisation cards

/** Choice chips in a wrapping row. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun <T> Choices(options: List<Pair<T, String>>, selected: T, onPick: (T) -> Unit) {
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
        options.forEach { (v, label) ->
            androidx.compose.material3.FilterChip(selected = v == selected, onClick = { onPick(v) }, label = { Text(label) })
        }
    }
}

@Composable
internal fun Label(t: String) = Text(t, style = MaterialTheme.typography.labelMedium, letterSpacing = 1.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))

/**
 * The list with the matching row moved [delta] places, found where it is *now*: two taps before the screen
 * catches up then move it twice, never a row that isn't there. Moves past either end (or above [lowest]) do nothing.
 */
internal fun <T> List<T>.moved(delta: Int, lowest: Int = 0, match: (T) -> Boolean): List<T> {
    val from = indexOfFirst(match); val to = from + delta
    if (from < 0 || to < lowest || to > lastIndex) return this
    return toMutableList().apply { add(to, removeAt(from)) }
}

/** Show, hide and reorder the parts of the home screen. */
@Composable
internal fun HomeLayoutCard(repo: Repository, onChanged: () -> Unit) {
    var sections by remember { mutableStateOf(repo.prefs.homeSections) }
    fun save(v: List<Pair<com.metrocast.app.data.HomeSection, Boolean>>) { sections = v; repo.prefs.homeSections = v; onChanged() }
    SettingsCard(title = "Home screen layout", subtitle = "Turn parts on or off and move them up or down") {
        sections.forEachIndexed { i, (sec, on) ->
            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(sec.title, style = MaterialTheme.typography.bodyLarge, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                    val off = (sec == com.metrocast.app.data.HomeSection.WEATHER || sec == com.metrocast.app.data.HomeSection.FORECAST) && !repo.prefs.weatherEnabled
                    Text(if (off) "Weather is off (see Weather below)" else sec.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { save(sections.moved(-1) { it.first == sec }) }, enabled = i > 0) { Icon(Icons.Default.KeyboardArrowUp, "Move ${sec.title} up") }
                IconButton(onClick = { save(sections.moved(1) { it.first == sec }) }, enabled = i < sections.lastIndex) { Icon(Icons.Default.KeyboardArrowDown, "Move ${sec.title} down") }
                Switch(checked = on, onCheckedChange = { v -> save(sections.map { if (it.first == sec) sec to v else it }) })
            }
        }
        TextButton(onClick = { save(com.metrocast.app.data.HomeSection.entries.map { it to true }) }) { Text("Reset layout") }
    }
}

/** Light / dark / phone setting, high contrast, larger text. Applies right away. */
@Composable
internal fun AppearanceCard(repo: Repository) {
    val p = repo.prefs
    SettingsCard(title = "Appearance") {
        Label("THEME")
        Choices(listOf("system" to "Same as phone", "light" to "Light", "dark" to "Dark"), AppLook.mode) { p.themeMode = it; AppLook.load(p) }
        Spacer(Modifier.height(10.dp))
        ToggleRow("High contrast", "Black and white backgrounds, darker text — easier in sunlight", AppLook.highContrast) { p.highContrast = it; AppLook.load(p) }
        Spacer(Modifier.height(10.dp))
        ToggleRow("Larger text", "Everything 20% bigger, on top of your phone's text size", AppLook.largeText) { p.largeText = it; AppLook.load(p) }
    }
}

/** How often times update. */
@Composable
internal fun RefreshCard(repo: Repository) {
    var every by remember { mutableStateOf(repo.prefs.refreshSeconds) }
    val context = androidx.compose.ui.platform.LocalContext.current
    SettingsCard(title = "Updates", subtitle = "How often train times refresh while the app or widget is on screen") {
        Choices(listOf(15 to "Every 15 s", 30 to "Every 30 s", 60 to "Every minute", 0 to "Only when I pull down"), every) {
            every = it; repo.prefs.refreshSeconds = it
            runCatching { com.metrocast.app.widget.WidgetTicker.schedule(context) }
        }
        Text(
            when (every) {
                0 -> "Saves the most battery and data. The widget still updates about every 15 minutes."
                15 -> "Freshest times; uses a little more battery."
                else -> "A live trip always updates every 15 seconds."
            },
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/** Track work heads-up: on/off, how far ahead, and for which kinds of work. */
@Composable
internal fun TrackWorkCard(repo: Repository) {
    val p = repo.prefs
    var on by remember { mutableStateOf(p.trackWorkAlerts) }
    var days by remember { mutableStateOf(p.trackWorkDays) }
    var major by remember { mutableStateOf(p.trackWorkMajorOnly) }
    val context = androidx.compose.ui.platform.LocalContext.current
    SettingsCard(title = "Track work heads-up", subtitle = "A notification before planned work on your trips") {
        ToggleRow("Warn me", if (on) "$days day${if (days == 1) "" else "s"} ahead" else "Off — the Track work calendar still shows it", on) {
            on = it; p.trackWorkAlerts = it; runCatching { com.metrocast.app.notify.Background.sync(context) }
        }
        if (on) {
            Label("HOW FAR AHEAD")
            Choices(listOf(1 to "1 day", 3 to "3 days", 7 to "A week"), days) { days = it; p.trackWorkDays = it }
            Label("WARN ME ABOUT")
            Choices(listOf(false to "Any work on my lines", true to "Only when my trip is disrupted"), major) { major = it; p.trackWorkMajorOnly = it }
            Text(
                if (major) "Only when trains won't run the whole way, or the work is on your stretch." else "Includes work elsewhere on your lines that means longer waits.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/** Train speed during live trips, and whether it also shows on the home screen. */
@Composable
internal fun SpeedSettingsCard(repo: Repository, onChanged: () -> Unit) {
    val p = repo.prefs
    var on by remember { mutableStateOf(p.showTrainSpeed) }
    var onHome by remember { mutableStateOf(p.homeSections.firstOrNull { it.first == com.metrocast.app.data.HomeSection.SPEED }?.second ?: true) }
    SettingsCard(title = "Train speed", subtitle = "How fast the train you're on is going, during a live trip") {
        ToggleRow("Show train speed on live trips",
            "From the phone's GPS when it has a signal (and you allowed location), otherwise from WMATA's train positions — works underground too",
            on) { on = it; p.showTrainSpeed = it; onChanged() }
        if (on) {
            Spacer(Modifier.height(10.dp))
            ToggleRow("Also show it on the home screen", "A card while you're on a train; you can also move it in Home screen layout", onHome) { v ->
                onHome = v
                p.homeSections = p.homeSections.map { (s, en) -> s to if (s == com.metrocast.app.data.HomeSection.SPEED) v else en }
                onChanged()
            }
        }
    }
}

/** Units for everything: weather, train speed and distances. Two plain choices. */
@Composable
internal fun UnitsCard(repo: Repository, onChanged: () -> Unit) {
    var us by remember { mutableStateOf(repo.prefs.useFahrenheit) }
    SettingsCard(title = "Units", subtitle = "For the weather, train speed and walking distances") {
        for ((isUs, title, sub) in listOf(
            Triple(true, "US", "°F, mph, feet and miles"),
            Triple(false, "Metric", "°C, km/h, meters and kilometers"),
        )) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .androidxSelectable(selected = us == isUs) { us = isUs; repo.prefs.useFahrenheit = isUs; onChanged() }
                    .padding(vertical = 6.dp),
            ) {
                androidx.compose.material3.RadioButton(selected = us == isUs, onClick = null)
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                    Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

internal fun Modifier.androidxSelectable(selected: Boolean, onClick: () -> Unit): Modifier =
    this.then(Modifier.selectable(selected = selected, role = androidx.compose.ui.semantics.Role.RadioButton, onClick = onClick))

/** Choose and order the ☰ menu's items. */
@Composable
internal fun MenuCard(repo: Repository) {
    var items by remember { mutableStateOf(repo.prefs.menuItems) }
    fun save(v: List<Pair<com.metrocast.app.data.MenuItem, Boolean>>) { items = v; repo.prefs.menuItems = v }
    SettingsCard(title = "Menu", subtitle = "Hide what you don't use and put your favourites first. Settings is always there") {
        items.forEachIndexed { i, (item, on) ->
            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Text(item.emoji, fontSize = 18.sp, modifier = Modifier.width(30.dp))
                Text(item.title, style = MaterialTheme.typography.bodyLarge, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                IconButton(onClick = { save(items.moved(-1) { it.first == item }) }, enabled = i > 0) { Icon(Icons.Default.KeyboardArrowUp, "Move ${item.title} up") }
                IconButton(onClick = { save(items.moved(1) { it.first == item }) }, enabled = i < items.lastIndex) { Icon(Icons.Default.KeyboardArrowDown, "Move ${item.title} down") }
                Switch(checked = on, onCheckedChange = { v -> save(items.map { if (it.first == item) item to v else it }) })
            }
        }
        TextButton(onClick = { save(com.metrocast.app.data.MenuItem.entries.map { it to true }) }) { Text("Reset menu") }
    }
}

/** Where a new trip plan starts. */
@Composable
internal fun PlannerStartCard(repo: Repository) {
    var start by remember { mutableStateOf(repo.prefs.plannerStart) }
    var picking by remember { mutableStateOf(false) }
    val going = repo.prefs.trips.firstOrNull { it.onHome }
    SettingsCard(title = "Trip planner starts at", subtitle = "Where a new plan begins (you can always change it in the planner)") {
        for (opt in listOf<String?>(null, start ?: "pick")) {
            val isGoing = opt == null
            val selected = if (isGoing) start == null else start != null
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .androidxSelectable(selected = selected) {
                        if (isGoing) { start = null; repo.prefs.plannerStart = null; repo.prefs.planFrom = null } else picking = true
                    }.padding(vertical = 6.dp),
            ) {
                androidx.compose.material3.RadioButton(selected = selected, onClick = null)
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(if (isGoing) "My \"Going there\" station" else "A station I choose", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (isGoing) going?.stationName ?: "Set up your trips first"
                        else start?.let { com.metrocast.app.data.StationDirectory.nameOf(it) ?: it } ?: "Choose a station",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (start != null) TextButton(onClick = { picking = true }) { Text("Change station") }
    }
    if (picking) StationPickerDialog(title = "Planner starts at", repo = repo, selected = start,
        onPick = { st -> start = st.code; repo.prefs.plannerStart = st.code; repo.prefs.planFrom = null; picking = false },
        onDismiss = { picking = false })
}

/** What each trip card on the home screen shows. */
@Composable
internal fun TripCardsCard(repo: Repository, onChanged: () -> Unit) {
    val p = repo.prefs
    var restroom by remember { mutableStateOf(p.cardShowRestroom) }
    var facts by remember { mutableStateOf(p.cardShowFacts) }
    var whereNow by remember { mutableStateOf(p.showTrainLocation) }
    var trains by remember { mutableStateOf(p.cardTrains) }
    SettingsCard(title = "Trip cards", subtitle = "What each trip on the home screen shows") {
        ToggleRow("Restroom note", "e.g. \"Restroom — open, no need to ask\"", restroom) { restroom = it; p.cardShowRestroom = it; onChanged() }
        Spacer(Modifier.height(8.dp))
        ToggleRow("Ride facts", "Ride time, stops, transfer and fare", facts) { facts = it; p.cardShowFacts = it; onChanged() }
        Spacer(Modifier.height(8.dp))
        ToggleRow("Where the trains are now", "The live track under each trip", whereNow) { whereNow = it; p.showTrainLocation = it; onChanged() }
        Label("INCOMING TRAINS TO SHOW")
        Text(
            "How many of the next trains each trip card lists. 1 = just the next train; 4 = the next four.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        Choices(listOf(1 to "1", 2 to "2", 3 to "3", 4 to "4"), trains) { trains = it; p.cardTrains = it; onChanged() }
        Text(
            "Right now: " + when (trains) { 1 -> "just the next train"; else -> "the next $trains trains" } + ".",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** No notifications overnight or at weekends (the live-trip transfer heads-up still comes). */
@Composable
internal fun QuietHoursCard(repo: Repository) {
    val p = repo.prefs
    var on by remember { mutableStateOf(p.quietOn) }
    var start by remember { androidx.compose.runtime.mutableIntStateOf(p.quietStart) }
    var end by remember { androidx.compose.runtime.mutableIntStateOf(p.quietEnd) }
    var weekends by remember { mutableStateOf(p.quietWeekends) }
    fun t(m: Int) = Format.time(java.time.LocalTime.of(m / 60, m % 60))
    SettingsCard(title = "Quiet hours", subtitle = "No notifications at these times — except the transfer heads-up while you're on a live trip") {
        ToggleRow("Quiet hours", if (on) "${t(start)} – ${t(end)}" + if (weekends) ", and all weekend" else "" else "Off", on) { on = it; p.quietOn = it }
        if (on) {
            Stepper("From", t(start), step = 30) { d -> start = (start + d).mod(1440); p.quietStart = start }
            Stepper("Until", t(end), step = 30) { d -> end = (end + d).mod(1440); p.quietEnd = end }
            ToggleRow("All weekend too", "Saturday and Sunday stay quiet all day", weekends) { weekends = it; p.quietWeekends = it }
        }
    }
}

/** 12- or 24-hour times. */
@Composable
internal fun ClockCard(repo: Repository, onChanged: () -> Unit) {
    var h24 by remember { mutableStateOf(repo.prefs.clock24) }
    SettingsCard(title = "Clock", subtitle = "How times are shown") {
        Choices(listOf(false to "12-hour (1:45 PM)", true to "24-hour (13:45)"), h24) {
            h24 = it; repo.prefs.clock24 = it; Format.use24h = it; onChanged()
        }
    }
}

/** Airports: keys for following a flight live (each service's own; none is built in). */
@Composable
internal fun FlightTrackingCard(repo: Repository) {
    val p = repo.prefs
    val uri = LocalUriHandler.current
    val avail = com.metrocast.app.data.FlightProvider.available
    var chosen by remember { mutableStateOf(p.flightProvider) }
    SettingsCard(title = "Flight tracking", subtitle = "For Airports: follow your flight's time and gate live") {
        Text(
            "Needs a key from a flight data service. Only the flight number you look up is sent to it; nothing is saved.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        avail.forEach { prov ->
            var key by remember(prov) { mutableStateOf(p.flightKey(prov)) }
            Label(prov.title.uppercase())
            OutlinedTextField(
                value = key,
                onValueChange = { v ->
                    key = v.trim()
                    if (prov == com.metrocast.app.data.FlightProvider.AVIATIONSTACK) p.aviationStackKey = key else p.flightAwareKey = key
                    chosen = p.flightProvider
                },
                label = { Text("${prov.title} key") },
                singleLine = true,
                visualTransformation = if (key.length > 4) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(prov.about, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { runCatching { uri.openUri(prov.signUp) } }) { Text("Get a ${prov.title} key") }
        }
        val withKeys = avail.filter { p.flightKey(it).isNotBlank() }
        if (withKeys.size > 1) {
            Label("USE")
            Choices(withKeys.map { it to it.title }, chosen ?: withKeys.first()) { chosen = it; p.flightProvider = it }
        }
    }
}

/** Updates from a folder on your PC, over home Wi-Fi (the updates copy only). */
@Composable
internal fun UpdatesCard(repo: Repository, edition: String) {
    val p = repo.prefs
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var address by remember { mutableStateOf(p.updateAddress) }
    // Already found (and maybe downloaded) by the nightly or on-open check: Install is there straight away.
    var found by remember { mutableStateOf(repo.autoCheckUpdate()) }
    var status by remember { mutableStateOf(p.updateProblem?.let { "Last try didn't install: $it" } ?: found?.let { f ->
        if (com.metrocast.app.data.UpdateInstaller.ready(ctx, f) != null) "Version ${f.versionName} is downloaded and ready." else "Version ${f.versionName} is ready on your PC." }) }
    var busy by remember { mutableStateOf(false) }
    SettingsCard(title = "MetroCast version ${com.metrocast.app.BuildConfig.VERSION_NAME}", subtitle = edition) {
        Text("UPDATES FROM YOUR PC", style = MaterialTheme.typography.labelMedium, letterSpacing = 1.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            "On your PC, open \"MetroCast Updates\" (it can start with Windows). New versions you download there are shared by themselves. " +
                "MetroCast checks your PC every night around 1 AM and when you open it at home, gets new versions ready, and installs them " +
                "(after the first one, usually without asking). Only over your home Wi-Fi; nothing goes over the internet.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = address, onValueChange = { address = it.take(60); p.updateAddress = address; com.metrocast.app.notify.UpdateCheckWorker.sync(ctx) },
            label = { Text("Your PC's address") }, placeholder = { Text("leave blank and tap Find my PC") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )

        /** Looks for the PC on the home network; fills in the address if it answers. */
        suspend fun find(): String? {
            val phone = com.metrocast.app.data.Updater.phoneWifiAddresses(ctx)
            val pc = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.metrocast.app.data.Updater.findPc(phone) }
            if (pc != null) { address = pc; p.updateAddress = pc; com.metrocast.app.notify.UpdateCheckWorker.sync(ctx) }
            return pc
        }

        fun check() {
            busy = true; status = if (address.isBlank()) "Looking for your PC…" else "Checking…"; found = null
            scope.launch {
                val phone = com.metrocast.app.data.Updater.phoneWifiAddresses(ctx)
                val home = address.isNotBlank() && com.metrocast.app.data.Updater.onHomeNetwork(address, phone)
                var r = if (address.isBlank()) null else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.metrocast.app.data.Updater.check(address, com.metrocast.app.BuildConfig.VERSION_CODE) }
                var moved: String? = null
                // No address yet, or no answer: look for the PC on the home network (its address may have changed).
                if (r == null || r is com.metrocast.app.data.Updater.Result.Problem) {
                    status = "Looking for your PC on this Wi-Fi…"
                    val before = address
                    find()?.let { pc ->
                        moved = pc.takeIf { it != before }
                        r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.metrocast.app.data.Updater.check(pc, com.metrocast.app.BuildConfig.VERSION_CODE) }
                    }
                }
                busy = false
                val prefix = moved?.let { "Found your PC at $it. " } ?: ""
                status = prefix + when (val res = r) {
                    is com.metrocast.app.data.Updater.Result.Available -> { found = res.info; p.updateFound = com.metrocast.app.data.Updater.toJson(res.info); "Version ${res.info.versionName} is ready." + if (res.info.notes.isNotBlank()) " ${res.info.notes}" else "" }
                    is com.metrocast.app.data.Updater.Result.UpToDate -> "You're up to date (version ${res.versionName} is the newest on your PC)."
                    is com.metrocast.app.data.Updater.Result.Problem -> res.message + if (home) "" else
                        " " + com.metrocast.app.data.Updater.whereThePhoneIs(phone) +
                            (com.metrocast.app.data.Updater.homeBlock(address)?.let { " Your PC is on $it.x: connect to that Wi-Fi, and turn off any VPN (Settings › Network › VPN) if one is on." } ?: "")
                    null -> "Couldn't find MetroCast Updates on this Wi-Fi. Is it open on your PC (and allowed on Private networks)? " + com.metrocast.app.data.Updater.whereThePhoneIs(phone)
                }
            }
        }

        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            androidx.compose.material3.Button(enabled = !busy, onClick = { check() }) { Text(if (address.isBlank()) "Find my PC" else "Check for updates") }
            val info = found
            if (info != null) androidx.compose.material3.FilledTonalButton(enabled = !busy, onClick = {
                if (!com.metrocast.app.data.Updater.canInstall(ctx)) {
                    status = "First, allow MetroCast to install updates (one time), then come back and tap Install again."
                    runCatching { ctx.startActivity(com.metrocast.app.data.Updater.allowInstallsIntent(ctx)) }
                    return@FilledTonalButton
                }
                busy = true; status = if (com.metrocast.app.data.UpdateInstaller.ready(ctx, info) != null) "Installing…" else "Downloading…"
                p.updateProblem = null
                scope.launch {
                    val file = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { com.metrocast.app.data.UpdateInstaller.prepare(ctx, address, info) } }
                    busy = false
                    file.onSuccess { f ->
                        status = if (com.metrocast.app.data.UpdateInstaller.install(ctx, f, info.versionName))
                            "Installing ${info.versionName}… If Android asks, tap Update. MetroCast restarts as the new version."
                        else runCatching { ctx.startActivity(com.metrocast.app.data.Updater.installIntent(ctx, f)) } // the older way, if the installer session couldn't start
                            .fold({ "Opening the installer — tap Update there." }, { "Couldn't open Android's installer (${it.javaClass.simpleName}: ${it.message})." })
                    }.onFailure { status = it.message ?: "Download failed — try again." }
                }
            }) { Text("Install") }
        }
        status?.let { Text(it, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp)) }
    }
}

/** The app crashed at some point: see what happened, copy it to share, or clear it. Kept on the phone only. */
@Composable
internal fun CrashLogCard(repo: Repository) {
    val ctx = repo.appContext
    var entries by remember { mutableStateOf(com.metrocast.app.data.CrashLog.entries(ctx)) }
    var showing by remember { mutableStateOf(false) }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    if (entries.isEmpty()) return
    SettingsCard(title = "Crash log", subtitle = "${entries.size} crash${if (entries.size == 1) "" else "es"} noted · kept on this phone only") {
        Text(entries.first().lineSequence().first(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { showing = true }) { Text("View") }
            OutlinedButton(onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(com.metrocast.app.data.CrashLog.text(ctx))) }) { Text("Copy") }
            TextButton(onClick = { com.metrocast.app.data.CrashLog.clear(ctx); entries = emptyList() }) { Text("Clear") }
        }
    }
    if (showing) androidx.compose.material3.AlertDialog(
        onDismissRequest = { showing = false },
        title = { Text("Crash log") },
        text = {
            Text(
                com.metrocast.app.data.CrashLog.text(ctx),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = { TextButton(onClick = { showing = false }) { Text("Close") } },
    )
}
