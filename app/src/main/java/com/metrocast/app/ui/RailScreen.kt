package com.metrocast.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metrocast.app.data.Rail
import com.metrocast.app.data.RailLive
import com.metrocast.app.data.RailOption
import com.metrocast.app.data.RailRun
import com.metrocast.app.data.RailService
import com.metrocast.app.data.Repository
import com.metrocast.app.data.StationDirectory
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Trip planner → Catch Amtrak, MARC or VRE: pick the train, and it tells you which Metro station to
 * go to (and any other one that works) and when to leave yours — with the train's live status.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RailScreen(
    repo: Repository,
    onBack: () -> Unit,
    onPlan: (from: String, to: String) -> Unit,
    clock: () -> LocalDateTime = { LocalDateTime.now(Rail.ZONE) },
) {
    var start by remember { mutableStateOf(repo.prefs.planFrom ?: "A01") }
    var picking by remember { mutableStateOf(false) }
    var service by remember { mutableStateOf<RailService?>(null) }
    var query by remember { mutableStateOf("") }
    var chosen by remember { mutableStateOf<RailRun?>(null) }
    // "trains" (pick a train) or "stations" (which Metro stations have Amtrak / MARC / VRE).
    var view by remember { mutableStateOf("trains") }
    var atStation by remember { mutableStateOf<String?>(null) }
    // Narrow to one line, e.g. MARC Penn Line or Amtrak Northeast Regional.
    var line by remember { mutableStateOf<String?>(null) }
    var early by remember { mutableStateOf(repo.prefs.amtrakEarlyMin) }
    var now by remember { mutableStateOf(clock()) }
    var rides by remember { mutableStateOf(0) } // bumps when WMATA's ride times arrive
    val trains = remember { repo.railTrains() }

    // Ride times from your station to each train station (once per start; cached after).
    LaunchedEffect(start) { repo.prepareRailRides(start, Rail.STATIONS.map { it.metroCode }); rides++ }
    // Keep "now" moving so gone trains drop off.
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = clock() } }

    val minutes = remember(rides) { { f: String, t: String -> repo.railMetroMinutes(f, t) } }
    BackHandler(enabled = chosen != null) { chosen = null }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (chosen == null) "Catch Amtrak, MARC or VRE" else chosen!!.train.title, fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = { if (chosen != null) chosen = null else onBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        val run = chosen
        if (run != null) {
            Column(Modifier.padding(padding)) {
                TrainDetail(repo, run, start, now, early, minutes, onEarly = { early = it; repo.prefs.amtrakEarlyMin = it }, onPlan = onPlan)
            }
            return@Scaffold
        }
        // Each train with the best way to catch it from your station. Ones you can't make any more are
        // left out, unless you searched for them (then they say it's too late).
        // The next 24 hours of trains, worked out once per minute-tick (not on every redraw).
        val upcoming = remember(trains, now) { Rail.upcoming(trains, now) }
        val list = remember(upcoming, service, line, query, now, start, early, rides, atStation) {
            upcoming
                .filter { (service == null || it.train.service == service) && (line == null || it.train.route == line) && it.train.matches(query) }
                .filter { r -> atStation == null || r.train.stops.any { it.metroCode == atStation } }
                .map { r -> r to Rail.options(r, start, now, early, minutes).firstOrNull { !it.leaveBy.isBefore(now) && (atStation == null || it.station.metroCode == atStation) } }
                .filter { (_, best) -> best != null || query.isNotBlank() }
                .sortedBy { (r, best) -> best?.trainLeaves ?: r.first }
                .take(80)
        }
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            item {
                Text(
                    "Pick your train: we'll tell you which Metro station to go to and when to leave yours.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            item {
                Surface(onClick = { picking = true }, shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        Text("YOUR METRO STATION", style = MaterialTheme.typography.labelSmall, letterSpacing = 1.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(StationDirectory.nameOf(start) ?: start, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = view == "trains", onClick = { view = "trains" }, label = { Text("🚆  Trains") })
                    FilterChip(selected = view == "stations", onClick = { view = "stations" }, label = { Text("📍  Stations") })
                }
            }
            // Which service, then which line: narrows the stations and trains.
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = service == null, onClick = { service = null; line = null }, label = { Text("All") })
                    RailService.entries.forEach { s -> FilterChip(selected = service == s, onClick = { service = if (service == s) null else s; line = null }, label = { Text(s.title) }) }
                }
            }
            service?.let { sv ->
                val lines = trains.filter { it.service == sv }.groupingBy { it.route }.eachCount().entries.sortedByDescending { it.value }.map { it.key }
                if (lines.size > 1) item {
                    Column {
                        Text("WHICH LINE?", style = MaterialTheme.typography.labelSmall, letterSpacing = 1.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(selected = line == null, onClick = { line = null }, label = { Text("Any ${sv.title}") })
                            lines.forEach { l -> FilterChip(selected = line == l, onClick = { line = if (line == l) null else l }, label = { Text(l) }) }
                        }
                    }
                }
            }
            if (view == "stations") {
                item {
                    Text(
                        "Metro stations where you can catch " + (line ?: service?.title ?: "Amtrak, MARC or VRE") +
                            ", closest to ${StationDirectory.nameOf(start) ?: "you"} first.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                val served = trains.filter { (service == null || it.service == service) && (line == null || it.route == line) }
                    .flatMap { t -> t.stops.map { it.metroCode } }.toSet()
                val stations = Rail.STATIONS.filter { it.metroCode in served }.map { st ->
                    val here = StationDirectory.find(start)?.codes?.contains(st.metroCode) == true || start == st.metroCode
                    st to (if (here) 0 to false else minutes(start, st.metroCode))
                }.sortedBy { it.second?.first ?: Int.MAX_VALUE }
                items(stations, key = { "st-" + it.first.metroCode }) { (st, ride) ->
                    val next = upcoming.filter { (service == null || it.train.service == service) && (line == null || it.train.route == line) }
                        .mapNotNull { r -> r.train.stops.firstOrNull { it.metroCode == st.metroCode }?.let { s -> r to r.timeAt(s) } }
                        .filter { !it.second.isBefore(now) }.sortedBy { it.second }.take(3)
                    StationCard(st, ride, start, next, service, onTrains = { atStation = st.metroCode; view = "trains" })
                }
                item { Sources(repo) }
                return@LazyColumn
            }
            atStation?.let { code ->
                item {
                    FilterChip(selected = true, onClick = { atStation = null }, label = { Text("From ${Rail.station(code)?.name ?: code}  ✕") })
                }
            }
            item {
                OutlinedTextField(
                    value = query, onValueChange = { query = it.take(40) }, singleLine = true,
                    label = { Text("Train number or where it's going") },
                    placeholder = { Text("e.g. 171, New York, Fredericksburg") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (list.isEmpty()) item {
                Text(
                    if (query.isBlank()) "No trains in the next 24 hours." else "No train matches \"$query\" in the next 24 hours.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(list, key = { (r, _) -> "${r.train.service}-${r.train.tripId}-${r.serviceDate}" }) { (r, best) ->
                TrainRow(r, best, start, now.toLocalDate(), onClick = { chosen = r })
            }
            item { Sources(repo) }
        }
    }
    if (picking) StationPickerDialog(title = "Your Metro station", repo = repo, selected = start,
        onPick = { start = it.code; picking = false }, onDismiss = { picking = false })
}

/** One Metro station with Amtrak / MARC / VRE: what runs there, how far it is from you, what's next. */
@Composable
private fun StationCard(
    st: com.metrocast.app.data.RailStation, ride: Pair<Int, Boolean>?, start: String,
    next: List<Pair<RailRun, LocalDateTime>>, only: RailService?, onTrains: () -> Unit,
) {
    ElevatedCard(onClick = onTrains, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StationDirectory.find(st.metroCode)?.lines?.forEach { LinePill(it, size = 20); Spacer(Modifier.width(3.dp)) }
                Spacer(Modifier.width(5.dp))
                Text(st.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { st.services.filter { only == null || it == only }.sortedBy { it.ordinal }.forEach { ServicePill(it) } }
            Text(
                when {
                    ride == null -> "No Metro route from ${StationDirectory.nameOf(start) ?: "your station"}"
                    ride.first == 0 -> "You start here · ${st.walkMin} min walk to the train platform"
                    else -> "${ride.first} min by Metro from ${StationDirectory.nameOf(start) ?: "your station"}" +
                        (if (ride.second) " (estimate)" else "") + " · then ${st.walkMin} min walk to the platform"
                },
                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary,
            )
            Text("📍 ${st.note}", style = MaterialTheme.typography.bodySmall)
            if (next.isEmpty()) Text("No more trains from here in the next 24 hours.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else Text(
                "Next: " + next.joinToString(" · ") { (r, t) -> "${r.train.title} ${Format.time(t.toLocalTime())}" },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("See trains from here ›", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun TrainRow(r: RailRun, best: RailOption?, start: String, today: java.time.LocalDate, onClick: () -> Unit) {
    ElevatedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ServicePill(r.train.service)
                Spacer(Modifier.width(8.dp))
                Text("${r.train.number} · ${r.train.route}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                // When it leaves the station you'd use.
                Text(Format.time((best?.trainLeaves ?: r.first).toLocalTime()), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            }
            Text("→ ${r.train.headsign}" + dayNote(best?.trainLeaves ?: r.first, today), style = MaterialTheme.typography.bodyMedium)
            Text(
                "From " + r.train.stops.joinToString(", ") { Rail.station(it.metroCode)?.name ?: it.metroCode },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (best != null) {
                Text(
                    "Leave ${StationDirectory.nameOf(start) ?: "your station"} by ${Format.time(best.leaveBy.toLocalTime())} → ${best.station.name}",
                    style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary,
                )
            } else {
                Text(
                    "Too late to catch from ${StationDirectory.nameOf(start) ?: "your station"}",
                    style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun TrainDetail(
    repo: Repository, run: RailRun, start: String, now: LocalDateTime, early: Int,
    minutes: (String, String) -> Pair<Int, Boolean>?, onEarly: (Int) -> Unit, onPlan: (String, String) -> Unit,
) {
    val opts = Rail.options(run, start, now, early, minutes)
    var live by remember(run) { mutableStateOf<RailLive?>(null) }
    // The station you're following live: the best one, or whichever option you tap.
    var picked by remember(run) { mutableStateOf<String?>(null) }
    val followed = opts.firstOrNull { it.station.metroCode == picked } ?: opts.firstOrNull()
    // Live status, refreshed while you're looking: MARC & VRE at your refresh speed (at least 30 s);
    // Amtrak once a minute (its live data updates about that often).
    LaunchedEffect(run, followed?.stop) {
        val stop = followed?.stop ?: run.train.stops.first()
        live = null
        while (true) {
            live = repo.railLive(run, stop)
            delay(Rail.liveRefreshSeconds(run.train.service, repo.prefs.refreshSeconds) * 1000L)
        }
    }
    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ServicePill(run.train.service); Spacer(Modifier.width(8.dp))
                Column {
                    Text("${run.train.route} → ${run.train.headsign}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(run.first.toLocalDate().format(DateTimeFormatter.ofPattern("EEEE, MMM d")), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            if (!rememberOnline()) OfflineBanner("Offline — live status comes back when you're online. Times below are the schedule.")
            else LiveBadge(live, followed?.station?.name)
        }
        if (opts.isEmpty()) item {
            Text("This train has already left every Metro-area station (or there's no Metro route from your station).", style = MaterialTheme.typography.bodyMedium)
        }
        opts.forEachIndexed { i, o ->
            item(key = o.station.metroCode) {
                val isFollowed = o.station.metroCode == followed?.station?.metroCode
                OptionCard(o, i == 0, start, if (isFollowed) live else null, now, isFollowed, onFollow = { picked = o.station.metroCode }, onPlan = onPlan)
            }
        }
        if (run.train.service == RailService.AMTRAK) item {
            Column {
                Text("BE AT THE STATION BEFORE AN AMTRAK TRAIN", style = MaterialTheme.typography.labelSmall, letterSpacing = 1.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(15, 30, 45, 60).forEach { m -> FilterChip(selected = early == m, onClick = { onEarly(m) }, label = { Text("$m min") }) }
                }
                Text("Amtrak suggests 30 minutes; more if you're checking bags.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            Text(
                "Leave-by times include waiting for a Metro train (5 min), walking to the platform, and being there " +
                    (if (run.train.service == RailService.AMTRAK) "$early min" else "5 min") + " before it leaves. " +
                    "They stay on the train's schedule even when it's running late — late trains can make up time.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun OptionCard(
    o: RailOption, best: Boolean, start: String, live: RailLive?, now: LocalDateTime,
    followed: Boolean, onFollow: () -> Unit, onPlan: (String, String) -> Unit,
) {
    ElevatedCard(onClick = onFollow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                if (best) "BEST: ${o.station.name.uppercase()}" else "OR: ${o.station.name.uppercase()}",
                style = MaterialTheme.typography.labelLarge, letterSpacing = 1.sp,
                color = if (best) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Leave ${StationDirectory.nameOf(start) ?: "your station"} by ${Format.time(o.leaveBy.toLocalTime())}",
                style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
            )
            if (o.leaveBy.isBefore(now)) Text("⚠️ That's already passed — you'd likely miss it from here.", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            val delay = live?.delayMin?.takeIf { it >= 2 && !live.canceled }
            Text(
                "Train leaves ${o.station.name} at ${Format.time(o.trainLeaves.toLocalTime())}" +
                    (delay?.let { " (live: about ${Format.time(o.trainLeaves.plusMinutes(it.toLong()).toLocalTime())})" } ?: ""),
                style = MaterialTheme.typography.bodyMedium,
            )
            val parts = buildList {
                if (o.metroMin > 0) add("${o.metroMin} min Metro" + if (o.estimated) " (estimate)" else "")
                if (o.waitMin > 0) add("${o.waitMin} wait")
                add("${o.walkMin} walk")
                add("${o.earlyMin} early")
            }
            Text(parts.joinToString(" + ") + " = ${o.totalMin} min", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("📍 ${o.station.note}", style = MaterialTheme.typography.bodySmall)
            Text(
                if (followed) "● Following this station live" else "Tap to follow this station live",
                style = MaterialTheme.typography.labelLarge,
                color = if (followed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (o.metroMin > 0) FilledTonalButton(onClick = { onPlan(start, o.station.metroCode) }) { Text("Plan the Metro ride to ${o.station.name}") }
        }
    }
}

@Composable
private fun LiveBadge(live: RailLive?, at: String?) {
    val (bg, fg) = when {
        live == null -> MaterialTheme.colorScheme.surfaceContainerHigh to MaterialTheme.colorScheme.onSurface
        // Solid colours with white text, 7:1 contrast or better in light and dark.
        live.canceled -> Color(0xFF9A1C16) to Color.White
        live.late -> Color(0xFF7A4A00) to Color.White
        live.delayMin != null -> Color(0xFF175C2B) to Color.White
        else -> MaterialTheme.colorScheme.surfaceContainerHigh to MaterialTheme.colorScheme.onSurface
    }
    Surface(shape = RoundedCornerShape(14.dp), color = bg, contentColor = fg, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(if (at != null) "LIVE AT ${at.uppercase()}" else "LIVE", style = MaterialTheme.typography.labelSmall, letterSpacing = 1.sp)
            Text(live?.text ?: "Checking…", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            live?.note?.takeIf { live.delayMin != null || live.canceled }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
fun ServicePill(s: RailService) {
    Surface(shape = RoundedCornerShape(8.dp), color = Color(s.color), contentColor = Color.White) {
        Text(s.title, fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
    }
}

@Composable
private fun Sources(repo: Repository) {
    val fmt = DateTimeFormatter.ofPattern("MMM d")
    val checked = RailService.entries.joinToString(" · ") { s ->
        val t = repo.railCheckedAt(s)
        "${s.title}: " + if (t == 0L) "built-in" else java.time.Instant.ofEpochMilli(t).atZone(Rail.ZONE).format(fmt)
    }
    Text(
        "Schedules from Amtrak, MARC (MDOT MTA) and VRE, checked about once a month ($checked; Amtrak's only on Wi-Fi). " +
            "Live status: MARC's and VRE's own feeds; Amtrak's through Amtraker, a free community service.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
}

private fun dayNote(t: LocalDateTime, today: java.time.LocalDate): String {
    return when (t.toLocalDate()) { today -> ""; today.plusDays(1) -> " · tomorrow"; else -> " · " + t.toLocalDate().format(DateTimeFormatter.ofPattern("EEE")) }
}
