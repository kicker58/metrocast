package com.metrocast.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.metrocast.app.data.ArrivalKind
import com.metrocast.app.data.LegStatus
import com.metrocast.app.data.MetroLine
import com.metrocast.app.data.PlanStatus
import com.metrocast.app.data.Repository
import com.metrocast.app.data.StationDirectory
import com.metrocast.app.data.StationInfo
import com.metrocast.app.data.TransferStatus
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * "Plan a trip": pick any two stations; shows total ride time, when to leave, which trains to take
 * and where to transfer, with live next trains for every leg.
 */
@Composable
fun PlannerCard(
    plan: PlanStatus?,
    repo: Repository,
    onChange: (from: String?, to: String?) -> Unit,
    onSavedChanged: () -> Unit = {},
) {
    var picking by remember { mutableStateOf<String?>(null) } // "from" or "to"
    var people by remember { androidx.compose.runtime.mutableIntStateOf(repo.prefs.riders) }
    // null = leaving now. Kept only on screen (not saved).
    var leaveAt by remember { mutableStateOf<java.time.LocalDateTime?>(null) }
    var returnAt by remember { mutableStateOf<java.time.LocalDateTime?>(null) }
    val from = plan?.from ?: repo.prefs.planFrom?.let { StationDirectory.find(it) }
    val to = plan?.to

    ElevatedCard(shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Plan a trip", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "Any two stations — travel time, trains and transfers",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // ★ saves this route to "Saved routes" in the menu.
                if (from != null && to != null) {
                    var saved by remember(from.code, to.code) { mutableStateOf(repo.isSaved(from.code, to.code)) }
                    IconButton(onClick = {
                        repo.toggleSavedRoute(from.code, to.code)
                        saved = !saved
                        onSavedChanged()
                    }) {
                        Text(if (saved) "★" else "☆", fontSize = 24.sp, color = if (saved) Color(0xFFF2B400) else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            // 1. How many people.
            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("PEOPLE", style = MaterialTheme.typography.labelSmall, letterSpacing = 1.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(if (people == 1) "Just me" else "$people people", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                    }
                    IconButton(onClick = { people = (people - 1).coerceAtLeast(1); repo.prefs.riders = people }, enabled = people > 1) {
                        Text("−", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    }
                    IconButton(onClick = { people = (people + 1).coerceAtMost(6); repo.prefs.riders = people }, enabled = people < 6) {
                        Text("+", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            // 2. When (fares depend on the day and time you tap in).
            WhenPicker("LEAVING", leaveAt) { leaveAt = it }
            Spacer(Modifier.height(8.dp))
            // 3. Start and destination.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    StationField("FROM", from, "Choose a start station") { picking = "from" }
                    StationField("TO", to, "Choose where you're going") { picking = "to" }
                }
                Spacer(Modifier.width(8.dp))
                // Swap.
                Surface(
                    onClick = { onChange(to?.code, from?.code) },
                    enabled = from != null && to != null,
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.size(40.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) { Text("⇅", fontSize = 20.sp, fontWeight = FontWeight.Bold) }
                }
            }

            when {
                to == null -> {}
                plan?.message != null -> {
                    Spacer(Modifier.height(14.dp))
                    Text(plan.message, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                plan != null && plan.legs.isNotEmpty() -> {
                    if (plan.alerts.isNotEmpty()) {
                        Spacer(Modifier.height(14.dp))
                        RouteAlerts(plan.alerts)
                    }
                    Spacer(Modifier.height(16.dp))
                    PlanSummary(plan)
                    Spacer(Modifier.height(14.dp))
                    plan.legs.forEachIndexed { i, leg ->
                        if (i > 0) TransferRow(plan.legs[i - 1], leg, plan.connections.getOrNull(i - 1))
                        LegRow(leg)
                    }
                    ArriveRow(plan)
                    plan.ride?.let {
                        GroupFare(it, repo, people, leaveAt, returnAt) { r -> returnAt = r }
                    }
                    plan.to?.let { dest ->
                        Spacer(Modifier.height(12.dp))
                        StationExits(dest, repo)
                    }
                }
            }
        }
    }

    picking?.let { which ->
        StationPickerDialog(
            title = if (which == "from") "Start station" else "Destination",
            repo = repo,
            selected = if (which == "from") from?.code else to?.code,
            onPick = { st ->
                picking = null
                if (which == "from") onChange(st.code, to?.code) else onChange(from?.code ?: com.metrocast.app.data.Stations.DEFAULT_TRIPS.first().stationCode, st.code)
            },
            onDismiss = { picking = null },
        )
    }
}

@Composable
private fun StationField(label: String, station: StationInfo?, placeholder: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelSmall, letterSpacing = 1.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    station?.name ?: placeholder,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (station != null) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (station != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            station?.let { LineDots(it.lines) }
            Text("  ▾", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun LineDots(lines: List<String>) {
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        lines.forEach { l -> Box(Modifier.size(10.dp).clip(CircleShape).background(Color(MetroLine.fromCode(l).color))) }
    }
}

/** Big numbers: total ride time, and when the next train leaves. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlanSummary(plan: PlanStatus) {
    val ride = plan.ride
    val first = plan.legs.first().arrivals.firstOrNull()?.arrival
    Row(Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text("TRAVEL TIME", style = MaterialTheme.typography.labelSmall, letterSpacing = 1.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(ride?.minutes?.toString() ?: "–", fontSize = 40.sp, fontWeight = FontWeight.Bold, lineHeight = 42.sp)
                Text(" min", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 5.dp))
            }
        }
        Column(Modifier.weight(1f)) {
            Text("NEXT TRAIN", style = MaterialTheme.typography.labelSmall, letterSpacing = 1.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (first == null) {
                Text("No prediction", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
            } else if (first.kind == ArrivalKind.MINUTES && first.minutes > 0) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("${first.minutes}", fontSize = 40.sp, fontWeight = FontWeight.Bold, lineHeight = 42.sp)
                    Text(" min", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 5.dp))
                }
            } else {
                Text(first.label, fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
        listOfNotNull(
            when (plan.transfers) { 0 -> "No transfers"; 1 -> "1 transfer"; else -> "${plan.transfers} transfers" },
            ride?.stops?.let { "$it stops" },
            ride?.peakFare?.let { p ->
                val q = com.metrocast.app.data.Fares.quote(p, ride.offPeakFare ?: p, java.time.LocalDateTime.now())
                "${"$%.2f".format(q.perPerson)} now (${q.period.label.lowercase()})"
            },
        ).forEach { f ->
            Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                Text(f, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp))
            }
        }
    }
}

/** One train to take: line(s), direction, where to board and get off, and its next trains. */
@Composable
private fun LegRow(ls: LegStatus) {
    val leg = ls.leg
    // Colour the step by the train you'd take next (Silver if the next train is Silver, etc.).
    val nextLine = ls.arrivals.firstOrNull()?.line
    val main = MetroLine.fromCode(nextLine ?: leg.options.first().line)
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        // Coloured rail down the side, like a route map.
        Box(Modifier.width(28.dp).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
            Box(Modifier.padding(top = 6.dp).width(6.dp).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(Color(main.color)))
        }
        Column(Modifier.weight(1f).padding(start = 6.dp, bottom = 12.dp)) {
            Text(leg.from.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            RestroomNote(leg.options.first().boardCode)
            ElevatorNote(leg.options.first().boardCode, Modifier.padding(top = 3.dp))
            Spacer(Modifier.height(4.dp))
            leg.options.sortedByDescending { it.line == nextLine }.forEach { opt ->
                val line = MetroLine.fromCode(opt.line)
                val isNext = leg.options.size > 1 && opt.line == nextLine
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                    LinePill(opt.line, size = 22)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${line.displayName} Line toward ${opt.towardName}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (isNext) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (isNext) {
                        Spacer(Modifier.width(6.dp))
                        Surface(shape = RoundedCornerShape(50), color = Color(line.cardColor)) {
                            Text(
                                "Next",
                                color = Color(line.onColor), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
            }
            if (leg.options.size > 1) {
                Text("Any of these works", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                listOfNotNull(
                    if (leg.stops == 1) "1 stop" else "${leg.stops} stops",
                    ls.minutes?.let { "about $it min" },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
            if (ls.arrivals.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 6.dp).horizontalScroll(rememberScrollState()),
                ) {
                    ls.arrivals.forEach { la ->
                        val c = Color(MetroLine.fromCode(la.line).color)
                        Surface(shape = RoundedCornerShape(10.dp), color = c.copy(alpha = 0.18f)) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)) {
                                Box(Modifier.size(8.dp).clip(CircleShape).background(c))
                                Spacer(Modifier.width(6.dp))
                                Text(la.arrival.label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                                Text(" ${la.arrival.shortDestination}", style = MaterialTheme.typography.labelSmall, maxLines = 1)
                            }
                        }
                    }
                }
            } else {
                Text(
                    "No live times yet",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/**
 * What to do at a transfer: step-by-step directions using what the station signs show (line colour
 * and name, and the end station of your direction), plus a live check of whether you'll make the
 * connection.
 */
@Composable
private fun TransferRow(prev: LegStatus, next: LegStatus, live: TransferStatus?) {
    val outOpt = next.leg.options.first()
    val nextLine = MetroLine.fromCode(outOpt.line)
    val prevLine = MetroLine.fromCode(prev.leg.options.first().line)
    val changeLevels = live?.changeLevels ?: (prev.leg.options.first().alightCode != outOpt.boardCode)
    val otherLines = next.leg.options.drop(1).map { MetroLine.fromCode(it.line).displayName }
    // Train signs that work for this leg: live destinations of the connecting trains (short trips that
    // end too early are already left out), else the end of the line.
    val okSigns = next.arrivals.map { it.arrival.shortDestination }.filter { it.isNotBlank() }.distinct()
        .ifEmpty { listOf(outOpt.towardName) }
    val onColor = MaterialTheme.colorScheme.onTertiaryContainer

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("⇄", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = onColor)
                Spacer(Modifier.width(8.dp))
                Text(
                    "Transfer at ${next.leg.from.name}",
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = onColor,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    if (changeLevels) "~${live?.walkMinutes ?: 4} min walk" else "same platform",
                    style = MaterialTheme.typography.labelSmall, color = onColor,
                )
            }

            // Directions, in the words of the signs.
            val steps = buildList {
                val inOpt = prev.leg.options.first()
                add("Get off the ${prevLine.displayName} Line train — doors open on your ${com.metrocast.app.data.Platforms.doorSide(inOpt.line, inOpt.alightCode).word}.")
                if (changeLevels) {
                    add("Look for overhead signs with the ${nextLine.displayName.lowercase()} line colour that say “${nextLine.displayName} Line” and “${outOpt.towardName}”.")
                    add("Follow them up or down the escalator or stairs to the ${nextLine.displayName} Line level.")
                    add("On that level, go to the track whose sign says “${outOpt.towardName}” — the other track goes the opposite way.")
                } else {
                    add("Stay on this platform: the ${nextLine.displayName} Line stops here too. Check the platform sign says “${outOpt.towardName}”.")
                }
                add(
                    "Board a train showing " + okSigns.joinToString(" or ") { "“$it”" } +
                        (if (otherLines.isNotEmpty()) " (${(listOf(nextLine.displayName) + otherLines).joinToString(", ")} all work)." else ".")
                )
            }
            steps.forEachIndexed { i, step ->
                Row(Modifier.padding(top = 6.dp)) {
                    Text("${i + 1}.", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = onColor, modifier = Modifier.width(18.dp))
                    Text(step, style = MaterialTheme.typography.bodySmall, color = onColor)
                }
            }

            // Live: will you make it?
            if (live != null && live.connections.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text("LIVE CONNECTION", style = MaterialTheme.typography.labelSmall, letterSpacing = 1.sp, color = onColor)
                live.connections.forEachIndexed { i, c -> ConnectionRow(c, prevLine, nextLine, first = i == 0) }
            } else if (live != null) {
                Text(
                    "Live connection times show once trains are predicted at ${next.leg.from.name}.",
                    style = MaterialTheme.typography.labelSmall, color = onColor, modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun ConnectionRow(c: com.metrocast.app.data.Connection, inLine: MetroLine, outLine: MetroLine, first: Boolean) {
    val (label, color) = when (c.verdict) {
        com.metrocast.app.data.Verdict.EASY -> "Plenty of time" to Color(0xFF2E7D32)
        com.metrocast.app.data.Verdict.WALK_FAST -> "Walk quickly" to Color(0xFFB85C00)
        com.metrocast.app.data.Verdict.RUN -> "Run!" to Color(0xFFD32F2F)
        com.metrocast.app.data.Verdict.MISS -> "No connection predicted yet" to Color(0xFF505050)
    }
    // Solid background and full-strength text: easy to read on any colour behind it.
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = if (first) androidx.compose.foundation.BorderStroke(2.dp, color) else null,
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
            Column(Modifier.weight(1f)) {
                Text(
                    "${inLine.displayName} train gets here in ${minutesText(c.inMinutes)}" +
                        (c.outMinutes?.let { " → ${outLine.displayName} leaves in ${minutesText(it)}" } ?: ""),
                    style = MaterialTheme.typography.bodySmall, fontWeight = if (first) FontWeight.SemiBold else FontWeight.Normal,
                )
                c.backupMinutes?.let {
                    Text("Miss it? Next one in ${minutesText(it)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)
                }
            }
            Spacer(Modifier.width(8.dp))
            Surface(shape = RoundedCornerShape(50), color = color) {
                Text(label, color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
            }
        }
    }
}

private fun minutesText(m: Int) = if (m <= 0) "now" else "$m min"

@Composable
private fun ArriveRow(plan: PlanStatus) {
    val to = plan.to ?: return
    val wait = plan.legs.first().arrivals.firstOrNull()?.arrival?.minutes ?: 0
    val eta = plan.ride?.minutes?.let { LocalTime.now().plusMinutes((it + wait).toLong()) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(28.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(14.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurface))
        }
        Spacer(Modifier.width(6.dp))
        Column {
            Text(to.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            plan.legs.lastOrNull()?.leg?.options?.firstOrNull()?.let { o ->
                Text(com.metrocast.app.data.Platforms.doorText(o.line, o.alightCode), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            }
            RestroomNote(to.code)
            ElevatorNote(to.code, Modifier.padding(top = 3.dp))
            eta?.let {
                Text(
                    "Arrive around ${Format.time(it)} if you catch the next train",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Full-screen station list: search by name, or filter by line. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StationPickerDialog(
    title: String,
    repo: Repository,
    selected: String?,
    onPick: (StationInfo) -> Unit,
    onDismiss: () -> Unit,
) {
    var all by remember { mutableStateOf(StationDirectory.stations) }
    var loading by remember { mutableStateOf(all.isEmpty()) }
    var query by remember { mutableStateOf("") }
    var line by remember { mutableStateOf<String?>(null) }
    var restroomOnly by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (all.isEmpty()) {
            repo.ensureStationDirectory()
            all = StationDirectory.stations
            loading = false
        }
    }
    val shown = all.filter { s ->
        (line == null || line in s.lines) && (query.isBlank() || s.name.contains(query.trim(), ignoreCase = true)) &&
            (!restroomOnly || com.metrocast.app.data.Restrooms.at(s.code) != null)
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(title, fontWeight = FontWeight.Bold) },
                    navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close") } },
                )
            },
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    placeholder = { Text("Search stations") },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    FilterChip(selected = line == null, onClick = { line = null }, label = { Text("All lines") })
                    FilterChip(selected = restroomOnly, onClick = { restroomOnly = !restroomOnly }, label = { Text("🚻 Restroom") })
                    MetroLine.entries.forEach { l ->
                        FilterChip(
                            selected = line == l.code,
                            onClick = { line = if (line == l.code) null else l.code },
                            label = { Text(l.displayName) },
                            leadingIcon = { Box(Modifier.size(12.dp).clip(CircleShape).background(Color(l.color))) },
                        )
                    }
                }
                when {
                    loading -> Row(Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("Loading stations…")
                    }
                    all.isEmpty() -> Text("Couldn't load the station list. Check your connection.", Modifier.padding(24.dp))
                    else -> LazyColumn(Modifier.fillMaxSize()) {
                        items(shown, key = { it.code }) { st ->
                            val isSel = st.code == selected
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onPick(st) }
                                    .background(if (isSel) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                                    .padding(horizontal = 20.dp, vertical = 14.dp),
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        st.name,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                                    )
                                    com.metrocast.app.data.Restrooms.at(st.code)?.let { r ->
                                        Text(
                                            if (r.status == com.metrocast.app.data.Restrooms.Status.CLOSED_FOR_RENOVATION) "🚻 restroom closed for renovation"
                                            else if (r.askManager) "🚻 restroom · ask the station manager" else "🚻 restroom · open",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                LineDots(st.lines)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The trip planner on its own screen (from the menu). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlannerScreen(
    plan: PlanStatus?,
    repo: Repository,
    onChange: (String?, String?) -> Unit,
    onBack: () -> Unit,
    onSavedChanged: () -> Unit = {},
    onStartLive: (() -> Unit)? = null,
    onOpenRail: (() -> Unit)? = null,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Trip planner", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
        ) {
            PlannerCard(plan, repo, onChange, onSavedChanged)
            // Catching a train: Amtrak, MARC or VRE from a Metro station.
            if (com.metrocast.app.BuildConfig.RAIL && onOpenRail != null) {
                Surface(
                    onClick = onOpenRail, shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                        Text("🚆", fontSize = 24.sp); Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Catching Amtrak, MARC or VRE?", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Text("Pick the train — see when to leave and if it's on time", style = MaterialTheme.typography.bodySmall)
                        }
                        Text("›", style = MaterialTheme.typography.headlineSmall)
                    }
                }
            }
            // Follow this trip live: your train, the transfer (run / hurry / take your time), notifications.
            if (onStartLive != null && plan != null && plan.legs.isNotEmpty() && plan.message == null) {
                androidx.compose.material3.Button(
                    onClick = onStartLive,
                    modifier = Modifier.fillMaxWidth().padding(top = 14.dp).height(56.dp),
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Text("▶  Start live trip", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                }
                Text(
                    "Follows your train, shows each transfer live, and notifies you 2 stops before a transfer.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp, start = 4.dp),
                )
            }
            Text(
                "Times update every 30 seconds. Travel time is WMATA's scheduled time between the two stations.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp, start = 4.dp, end = 4.dp),
            )
        }
    }
}

/**
 * The exact cost: peak or off-peak (from the day and time you leave) × the fare for the distance
 * between your stations, per person and for everyone, plus the trip back at its own time.
 */
@Composable
private fun GroupFare(
    ride: com.metrocast.app.data.RideInfo,
    repo: Repository,
    people: Int,
    leaveAt: java.time.LocalDateTime?,
    returnAt: java.time.LocalDateTime?,
    onReturn: (java.time.LocalDateTime?) -> Unit,
) {
    val peak = ride.peakFare ?: return
    val off = ride.offPeakFare ?: peak
    val F = com.metrocast.app.data.Fares
    val out = F.quote(peak, off, leaveAt ?: java.time.LocalDateTime.now(), people)
    val back = returnAt?.let { F.quote(peak, off, it, people) }
    fun money(v: Double) = "$%.2f".format(v)
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text("COST", style = MaterialTheme.typography.labelSmall, letterSpacing = 1.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 2.dp)) {
                Text(money(out.total), fontSize = 30.sp, fontWeight = FontWeight.Bold)
                Text(
                    if (people == 1) " one way" else " one way for $people",
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 5.dp),
                )
            }
            // Peak / off-peak badge.
            Surface(
                shape = RoundedCornerShape(50),
                color = if (out.period == com.metrocast.app.data.Fares.Period.PEAK) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.padding(top = 2.dp),
            ) {
                Text(
                    "${out.period.label} · ${out.period.hours}",
                    style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                )
            }
            if (people > 1) {
                Text("${money(out.perPerson)} per person", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
            }
            // Both prices for this distance.
            Text(
                "This trip: ${money(peak)} peak · ${money(off)} off-peak per person",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            Spacer(Modifier.height(8.dp))
            // The trip back, priced for its own time.
            WhenPicker("COMING BACK", returnAt, allowNone = true, noneLabel = "Add the trip back", onChange = onReturn)
            if (back != null) {
                Text(
                    "Back: ${money(back.total)} (${back.period.label.lowercase()}) · Round trip ${money(out.total + back.total)}",
                    style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (people > 1) {
                Text(
                    "Each person needs their own card or phone to tap through the gates.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/** "Leaving now" / a chosen day and time (next 7 days, 15-minute steps). */
@Composable
private fun WhenPicker(
    label: String,
    value: java.time.LocalDateTime?,
    allowNone: Boolean = false,
    noneLabel: String = "Now",
    onChange: (java.time.LocalDateTime?) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val fmt = java.time.format.DateTimeFormatter.ofPattern("EEE MMM d, ") // then the time, on your 12/24-hour clock
    Surface(onClick = { open = !open }, shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelSmall, letterSpacing = 1.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    value?.let { it.format(fmt) + Format.time(it.toLocalTime()) } ?: if (allowNone) noneLabel else "Leaving now",
                    style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold,
                    color = if (value == null && allowNone) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(if (open) "Close  ▴" else "Change  ▾", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
        }
    }
    if (open) {
        val now = java.time.LocalDateTime.now()
        val start = value ?: now.plusMinutes(15 - now.minute % 15L).withSecond(0).withNano(0)
        var day by remember { mutableStateOf(start.toLocalDate()) }
        val h24 = Format.use24h
        // The time, typed (like the flight time on Airports): "5:45", "545", "17:45"… AM / PM is guessed from the
        // hour (the next time it comes round) until you tap one. −/+ still nudge it by 15 minutes.
        fun typed(t: java.time.LocalTime) = if (h24) "%d:%02d".format(t.hour, t.minute) else "%d:%02d".format((t.hour + 11) % 12 + 1, t.minute)
        var text by remember { mutableStateOf(typed(start.toLocalTime())) }
        var pmPicked by remember { mutableStateOf<Boolean?>(if (h24) null else start.hour >= 12) }
        val hour12 = com.metrocast.app.data.Flights.typedHour(text)
        val pm = pmPicked ?: hour12?.takeIf { it in 1..12 }?.let { com.metrocast.app.data.Flights.guessPm(it, day, now) } ?: true
        val time = com.metrocast.app.data.Flights.parseTime(text, if (h24) null else pm)
        fun nudge(by: Int) {
            val base = time ?: start.toLocalTime()
            val t = base.plusMinutes(by.toLong()).let { it.withMinute(it.minute - it.minute % 15).withSecond(0).withNano(0) }
            text = typed(t); if (!h24) pmPicked = t.hour >= 12
        }
        // Opens right here in the card (like the flight time on Airports), not in a pop-up.
        Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(if (allowNone) "When are you coming back?" else "When are you leaving?", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        (0L..6L).forEach { d ->
                            val date = now.toLocalDate().plusDays(d)
                            FilterChip(
                                selected = day == date,
                                onClick = { day = date },
                                label = { Text(when (d) { 0L -> "Today"; 1L -> "Tomorrow"; else -> date.format(java.time.format.DateTimeFormatter.ofPattern("EEE d")) }) },
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        IconButton(onClick = { nudge(-15) }) { Text("−", fontSize = 24.sp, fontWeight = FontWeight.Bold) }
                        androidx.compose.material3.OutlinedTextField(
                            value = text,
                            onValueChange = { v -> text = v.filter { it.isDigit() || it == ':' || it == '.' }.take(5); pmPicked = null },
                            singleLine = true,
                            label = { Text("Time") },
                            placeholder = { Text(if (h24) "17:45" else "5:45") },
                            isError = text.isNotBlank() && time == null,
                            textStyle = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { nudge(15) }) { Text("+", fontSize = 24.sp, fontWeight = FontWeight.Bold) }
                    }
                    if (!h24) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !pm, onClick = { pmPicked = false }, label = { Text("AM", fontWeight = FontWeight.Bold) })
                        FilterChip(selected = pm, onClick = { pmPicked = true }, label = { Text("PM", fontWeight = FontWeight.Bold) })
                    }
                    val pick = time?.let { day.atTime(it) }
                    Text(
                        if (pick == null) (if (h24) "Type the time, like 17:45" else "Type the time, like 5:45, then pick AM or PM")
                        else (if (pick.isBefore(now.withSecond(0).withNano(0))) "That's earlier today · " else "") +
                            com.metrocast.app.data.Fares.period(pick).let { "${it.label} fare · ${it.hours}" },
                        style = MaterialTheme.typography.labelMedium,
                        color = if (pick == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        androidx.compose.material3.TextButton(onClick = { onChange(null); open = false }) { Text(if (allowNone) "No trip back" else "Leaving now") }
                        androidx.compose.material3.TextButton(enabled = time != null, onClick = { time?.let { onChange(day.atTime(it)) }; open = false }) { Text("Done") }
                    }
                }
        }
    }
}
