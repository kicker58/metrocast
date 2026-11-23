package com.metrocast.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metrocast.app.data.Bikes
import com.metrocast.app.data.Bikeshare
import com.metrocast.app.data.ElevatorOutage
import com.metrocast.app.data.MetroLine
import com.metrocast.app.data.Platforms
import com.metrocast.app.data.Repository
import com.metrocast.app.data.StationDirectory
import com.metrocast.app.data.StationExit
import com.metrocast.app.data.StationInfo

private val OkGreen = Color(0xFF1B5E20)
private val OutRed = Color(0xFFB71C1C)

/**
 * Menu → Bikes. "My trips" first: every station on your trips with a bike — elevators working now,
 * which street entrance has the elevator, platform and door side, bike rooms and Capital Bikeshare —
 * then all elevator outages, bike rooms (card or fob), and WMATA's rules.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BikesScreen(repo: Repository, onBack: () -> Unit, onPlan: (from: String?, to: String?) -> Unit = { _, _ -> }) {
    val p = repo.prefs
    var tab by rememberSaveable { mutableStateOf("mine") }   // mine / outages / rooms / rules
    var outages by remember { mutableStateOf<List<ElevatorOutage>?>(null) }
    var outageError by remember { mutableStateOf(false) }
    var bikeMode by remember { mutableStateOf(p.bikeMode) }
    var alerts by remember { mutableStateOf(p.bikeElevatorAlerts) }
    var share by remember { mutableStateOf(p.bikeshareOn) }
    var exits by remember { mutableStateOf<Map<String, List<StationExit>>>(emptyMap()) }
    var docks by remember { mutableStateOf<Map<String, List<Bikeshare.Dock>>?>(null) }
    var shareError by remember { mutableStateOf(false) }
    val mine = remember { repo.myStations() }
    val metric = p.metric
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    val context = androidx.compose.ui.platform.LocalContext.current

    LaunchedEffect(Unit) {
        outages = runCatching { repo.loadElevatorOutages() }.onFailure { outageError = true }.getOrNull()
        exits = mine.associate { st -> st.code to runCatching { repo.loadExits(st) }.getOrDefault(emptyList()) }
    }
    LaunchedEffect(share) {
        docks = null; shareError = false
        if (share) docks = runCatching { repo.loadBikeshare(mine) }.onFailure { shareError = true }.getOrNull()
    }
    fun outagesAt(st: StationInfo) = outages?.filter { it.stationCode in st.codes }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Bikes on Metro", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            item(key = "tabs") {
                val outCount = outages?.let { o -> mine.count { st -> o.any { it.stationCode in st.codes } } } ?: 0
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    for ((k, label) in listOf("mine" to "🚲 My trips", "outages" to "Elevator outages" + if (outCount > 0) " ($outCount)" else "",
                        "rooms" to "🔒 Bike rooms", "rules" to "📋 Rules")) {
                        FilterChip(selected = tab == k, onClick = { tab = k }, label = { Text(label) })
                    }
                }
            }
            when (tab) {
                // ---------------------------------------------------------------- my trips with a bike
                "mine" -> {
                    item(key = "switches") {
                        ElevatedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                SwitchRow("I ride with a bike (bike mode)", "Elevator status shows on your trips, in the planner and on live trips", bikeMode) {
                                    bikeMode = it; p.bikeMode = it; runCatching { com.metrocast.app.notify.Background.sync(context) }
                                }
                                SwitchRow("Tell me when an elevator on my trips goes out", "A notification, checked every few minutes (also in Settings → Notifications)", alerts) {
                                    alerts = it; p.bikeElevatorAlerts = it; runCatching { com.metrocast.app.notify.Background.sync(context) }
                                }
                                SwitchRow("Show Capital Bikeshare", "Live bikes and open docks near your stations. Off: the app never contacts Capital Bikeshare", share) {
                                    share = it; p.bikeshareOn = it
                                }
                            }
                        }
                    }
                    item(key = "car") {
                        Note("🚃 Board the first or last car, through the end doors — up to 4 bikes per car, 2 at each end. Use the elevators and the wide faregates.")
                    }
                    if (outageError) item(key = "err") { Text("Couldn't load elevator status — check your connection.", color = MaterialTheme.colorScheme.error) }
                    if (mine.isEmpty()) item(key = "none") { Text("Set up your trips in Settings → Home screen trips to see them here.") }
                    items(mine, key = { "m-" + it.code }) { st ->
                        StationWithBike(st, outagesAt(st), exits[st.code], Bikes.at(st.code), if (share) docks?.get(st.code) else null,
                            shareLoading = share && docks == null && !shareError, metric = metric, open = { runCatching { uri.openUri(it) } })
                    }
                    if (share && shareError) item(key = "cabi-err") { Text("Couldn't reach Capital Bikeshare right now.", color = MaterialTheme.colorScheme.error) }
                }
                // ---------------------------------------------------------------- elevator outages
                "outages" -> {
                    val all = outages
                    if (all == null) item(key = "o-wait") { Text(if (outageError) "Couldn't load elevator status — check your connection." else "Checking with WMATA…") }
                    else {
                        val myCodes = mine.flatMap { it.codes }.toSet()
                        val (yours, others) = all.partition { it.stationCode in myCodes }
                        item(key = "o-yours") { Header("AT YOUR STATIONS · ${yours.size}") }
                        if (yours.isEmpty()) item(key = "o-none") {
                            Surface(shape = MaterialTheme.shapes.medium, color = OkGreen, contentColor = Color.White, modifier = Modifier.fillMaxWidth()) {
                                Text("✅ Every elevator at your stations is working", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(12.dp))
                            }
                        }
                        items(yours, key = { "y-" + it.stationCode + it.where }) { OutageRow(it, mine = true) }
                        item(key = "o-all") { Header("ELSEWHERE ON METRO · ${others.size}") }
                        items(others.sortedBy { StationDirectory.nameOf(it.stationCode) ?: it.stationCode }, key = { "a-" + it.stationCode + it.where }) { OutageRow(it, mine = false) }
                        item(key = "o-src") { Text("Live from WMATA (elevators only; escalators left out).", style = MaterialTheme.typography.labelSmall) }
                    }
                }
                // ---------------------------------------------------------------- bike rooms
                "rooms" -> {
                    item(key = "p-note") { Note("Only parking you open with a SmarTrip card or a key fob: free Bike & Ride rooms — sheltered, 24/7, cameras, repair stand. Source: ${Bikes.SOURCE} — checked about once a month.") }
                    item(key = "md") {
                        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Text("MARYLAND — NOT RUN BY METRO", style = MaterialTheme.typography.labelLarge, letterSpacing = 1.2.sp)
                                Text("We couldn't confirm any county-run, card- or fob-access bike rooms at Metro stations in Maryland. Metro's own (College Park, Grosvenor-Strathmore) are below.",
                                    style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = { runCatching { uri.openUri(Bikes.MONTGOMERY_URL) } }, contentPadding = PaddingValues(0.dp)) { Text("Montgomery County Bikeways ›") }
                            }
                        }
                    }
                    for (kind in Bikes.Kind.entries) {
                        val list = Bikes.all.filter { it.kind == kind }.sortedBy { it.name }
                        if (list.isEmpty()) continue
                        item(key = "h-$kind") { Header(kind.label.uppercase() + " · ${list.size}") }
                        item(key = "how-$kind") { HowToGetIn(kind) { runCatching { uri.openUri(it) } } }
                        items(list, key = { "f-$kind-" + it.code }) { f ->
                            ElevatedCard(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        StationDirectory.find(f.code)?.lines?.forEach { LinePill(it, size = 18); Spacer(Modifier.width(3.dp)) }
                                        Spacer(Modifier.width(4.dp))
                                        Text(f.name, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                        TextButton(onClick = { onPlan(null, f.code) }) { Text("Go") }
                                    }
                                    Text("Opens with a ${f.kind.access.replaceFirstChar { it.lowercase() }} · ${f.detail}", style = MaterialTheme.typography.bodySmall)
                                    StationDirectory.find(f.code)?.let { st -> ElevatorLine(outagesAt(st)) }
                                }
                            }
                        }
                    }
                }
                // ---------------------------------------------------------------- rules
                else -> {
                    item(key = "r-h") { Header("BIKES ON THE TRAIN · WMATA'S RULES") }
                    items(Bikes.RULES, key = { "r-" + it.hashCode() }) { r ->
                        Row { Text("•  ", fontWeight = FontWeight.Bold); Text(r, style = MaterialTheme.typography.bodyMedium) }
                    }
                    item(key = "r-link") { TextButton(onClick = { runCatching { uri.openUri(Bikes.RULES_URL) } }) { Text("WMATA's bike page ›") } }
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Text(sub, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun Header(t: String) = Text(t, style = MaterialTheme.typography.labelLarge, letterSpacing = 1.2.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = 8.dp))

@Composable
private fun Note(t: String) = Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
    Text(t, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
}

/** "✅ Elevators working" / "⚠️ Elevator out: …" — solid colours with white text, readable in light and dark. */
@Composable
internal fun ElevatorLine(outs: List<ElevatorOutage>?) {
    when {
        outs == null -> Text("Elevator status unavailable", style = MaterialTheme.typography.labelMedium)
        outs.isEmpty() -> Surface(shape = MaterialTheme.shapes.small, color = OkGreen, contentColor = Color.White) {
            Text("✅ Elevators working", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
        }
        else -> Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            outs.forEach { o ->
                Surface(shape = MaterialTheme.shapes.small, color = OutRed, contentColor = Color.White) {
                    Text("⚠️ Elevator out: ${o.where}" + (o.backBy?.let { " · back $it" } ?: ""), style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                }
            }
        }
    }
}

/** In bike mode: a small elevator note for a station (planner and live trip). Nothing otherwise. */
@Composable
internal fun ElevatorNote(code: String, modifier: Modifier = Modifier) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    if (!com.metrocast.app.data.Prefs(ctx).bikeMode) return
    val st = StationDirectory.find(code) ?: return
    val outs = Repository.lastElevatorOutages?.filter { it.stationCode in st.codes }
    Column(modifier) { ElevatorLine(outs) }
}

@Composable
private fun OutageRow(o: ElevatorOutage, mine: Boolean) {
    val st = StationDirectory.find(o.stationCode)
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                st?.lines?.forEach { LinePill(it, size = 18); Spacer(Modifier.width(3.dp)) }
                Spacer(Modifier.width(4.dp))
                Text(st?.name ?: o.stationCode, fontWeight = FontWeight.Bold)
                if (mine) Text("  · your station", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            }
            Text(o.where, style = MaterialTheme.typography.bodyMedium)
            Text(o.backBy?.let { "WMATA expects it back $it" } ?: "No return date given", style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** How to get into each kind of bike room. */
@Composable
private fun HowToGetIn(kind: Bikes.Kind, open: (String) -> Unit) {
    val (text, link, label) = when (kind) {
        Bikes.Kind.BIKE_AND_RIDE_METRO -> Triple(
            "Free. Register a physical SmarTrip card, then email a government ID to bike@wmata.com (about 3 days). Tap the card at the door — Mobile SmarTrip doesn't open it. Questions: ${Bikes.METRO_BIKE_PHONE}.",
            Bikes.BIKE_AND_RIDE_URL, "Sign up on wmata.com ›")
        Bikes.Kind.BIKE_AND_RIDE_FAIRFAX -> Triple(
            "Run by Fairfax County (FCDOT), not Metro — opened with a key fob from the county. Sign up with Fairfax County; for information call ${Bikes.FAIRFAX_PHONE}.",
            Bikes.FAIRFAX_URL, "How to get access — Fairfax County ›")
    }
    Column {
        Text(text, style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { open(link) }, contentPadding = PaddingValues(0.dp)) { Text(label) }
    }
}

/** One station on your trips, from a bike rider's point of view. */
@Composable
private fun StationWithBike(
    st: StationInfo, outs: List<ElevatorOutage>?, exits: List<StationExit>?, rooms: List<Bikes.Facility>,
    docks: List<Bikeshare.Dock>?, shareLoading: Boolean, metric: Boolean, open: (String) -> Unit,
) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                st.lines.forEach { LinePill(it, size = 20); Spacer(Modifier.width(3.dp)) }
                Spacer(Modifier.width(6.dp))
                Text(st.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            ElevatorLine(outs)
            // Which street entrance has the elevator (WMATA's entrances list).
            val lifts = exits?.filter { it.elevator }.orEmpty()
            when {
                exits == null -> {}
                lifts.isEmpty() -> Text("🚪 Street elevator: not listed by WMATA — ask the station manager", style = MaterialTheme.typography.bodySmall)
                else -> lifts.forEach { e ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("🚪 Street elevator: ${e.description.ifBlank { e.name }}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = { open("geo:${e.lat},${e.lon}?q=${e.lat},${e.lon}(${android.net.Uri.encode(st.name + " elevator")})") }) { Text("Map") }
                    }
                }
            }
            // Platform and doors, for each line here.
            st.lines.forEach { l ->
                val center = Platforms.isCenter(l, st.code)
                Text("${MetroLine.fromCode(l).displayName}: " + if (center) "center platform — one elevator for both directions, doors open on your left"
                    else "side platforms — each direction has its own elevator, doors open on your right", style = MaterialTheme.typography.bodySmall)
            }
            rooms.forEach { f -> Text("🔒 ${f.kind.label} · opens with a ${f.kind.access.replaceFirstChar { it.lowercase() }}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold) }
            // Capital Bikeshare nearby.
            when {
                shareLoading -> Text("🚲 Checking Capital Bikeshare…", style = MaterialTheme.typography.bodySmall)
                docks == null -> {}
                docks.isEmpty() -> Text("🚲 No Capital Bikeshare dock within ${Format.distance(Bikeshare.NEAR_METERS, metric)}", style = MaterialTheme.typography.bodySmall)
                else -> docks.forEach { d ->
                    Text("🚲 ${d.info.name} (${Format.distance(d.meters.toDouble(), metric)}): ${d.summary}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
