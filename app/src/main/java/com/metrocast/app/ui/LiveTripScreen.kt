package com.metrocast.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metrocast.app.data.ArrivalKind
import com.metrocast.app.data.LiveTrip
import com.metrocast.app.data.MetroLine
import com.metrocast.app.data.Verdict
import kotlin.math.ceil

/**
 * A trip, live: board → ride (your train followed on the map) → transfer (run / hurry / take your
 * time) → arrive. The screen stays on; a notification also comes 2 stops before each transfer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveTripScreen(
    live: LiveTrip?,
    updatedAt: Long,
    onBoard: () -> Unit,
    onOff: () -> Unit,
    onEnd: () -> Unit,
    onBack: () -> Unit,
    useLocation: Boolean? = null,
    autoBoarded: Boolean = false,
    onLocationChoice: (Boolean) -> Unit = {},
    onLocation: (lat: Double, lon: Double, speed: Float?) -> Unit = { _, _, _ -> },
    repo: com.metrocast.app.data.Repository? = null,
    onPreviousStep: () -> Unit = {},
) {
    // Keep the screen on while following a trip.
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    // Ask once whether to use location to notice boarding.
    val context = androidx.compose.ui.platform.LocalContext.current
    var hasPermission by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(LocationWatch.granted(context)) }
    val askLocation = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        hasPermission = result.values.any { it }
        onLocationChoice(hasPermission)
    }
    if (useLocation == null && live != null && !live.arrived) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { onLocationChoice(false) },
            icon = { Text("📍", fontSize = 28.sp) },
            title = { Text("Notice when you board?") },
            text = {
                Text(
                    "MetroCast can use your location while this screen is open to tell when your train has left " +
                        "the station with you on it, so you don't have to tap anything.\n\n" +
                        "Your location stays on your phone: it's never saved or sent anywhere. " +
                        "Underground GPS can be weak, so the \"I'm on the train\" button stays available."
                )
            },
            confirmButton = {
                Button(onClick = {
                    if (LocationWatch.granted(context)) { hasPermission = true; onLocationChoice(true) }
                    else askLocation.launch(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION))
                }) { Text("Use location") }
            },
            dismissButton = { TextButton(onClick = { onLocationChoice(false) }) { Text("No, I'll tap the button") } },
        )
    }
    // Watch location only while waiting to board, with this screen open.
    val watching = useLocation == true && hasPermission && live != null && !live.onBoard && !live.arrived
    // On the train, location is only used for its speed (when that's turned on).
    val speedOn = repo?.prefs?.showTrainSpeed == true
    val listening = watching || (useLocation == true && hasPermission && live != null && live.onBoard && !live.arrived && speedOn)
    if (listening) LocationWatch(onLocation)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Live trip", fontWeight = FontWeight.Bold)
                        Text(
                            "Updates every 15 s · " + Format.updated(updatedAt),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = { TextButton(onClick = onEnd) { Text("End trip") } },
            )
        },
    ) { padding ->
        Column(
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
        ) {
            if (live == null) {
                Text("Loading your trip…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                return@Column
            }
            val plan = live.plan
            Text(
                "${plan.from?.name ?: "Your station"} → ${plan.to?.name ?: "your stop"}",
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
            )
            Progress(live)
            if (live.leg > 0 && !live.arrived) {
                TextButton(onClick = onPreviousStep) { Text("← Back a step (if it moved on too early)") }
            }
            RouteAlerts(plan.alerts)

            if (live.arrived) {
                ArrivedCard(plan.to?.name ?: "your stop", live.autoEndMinutes, onEnd, plan.to?.code)
                if (repo != null) plan.to?.let { StationExits(it, repo) }
                return@Column
            }
            val ls = live.legStatus ?: return@Column
            // The card takes the colour of the train you should board next (e.g. Silver if the next
            // train here is Silver, Orange once an Orange train is next) — or the one you're on.
            val cardLine = if (live.onBoard) live.rideLine ?: ls.leg.options.first().line
            else ls.arrivals.firstOrNull()?.line ?: ls.leg.options.first().line
            val opt = ls.leg.options.firstOrNull { it.line == cardLine } ?: ls.leg.options.first()
            val line = MetroLine.fromCode(cardLine)
            val color by androidx.compose.animation.animateColorAsState(Color(line.cardColor), label = "card-colour")

            // ---- The big card: what to do right now.
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(28.dp))
                    .background(Brush.verticalGradient(listOf(color, androidx.compose.ui.graphics.lerp(color, Color.Black, 0.06f))))
                    .padding(20.dp),
            ) {
                val ink = Color(line.onColor)
                Column {
                    if (!live.onBoard) {
                        Text("BOARD", color = ink, style = MaterialTheme.typography.labelLarge, letterSpacing = 2.sp)
                        // Every line that works for this ride (e.g. Orange or Silver from East Falls Church).
                        val opts = ls.leg.options.sortedByDescending { it.line == cardLine }
                        Text(
                            if (opts.size == 1) "${line.displayName} Line toward ${opt.towardName}"
                            else opts.joinToString(" or ") { MetroLine.fromCode(it.line).displayName } + " Line",
                            color = ink, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                        )
                        if (opts.size > 1) {
                            Text(
                                "Any of these gets you to ${ls.leg.to.name}",
                                color = ink, style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        Text("at ${ls.leg.from.name}", color = ink, style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.height(14.dp))
                        val nextLA = ls.arrivals.firstOrNull()
                        val next = nextLA?.arrival
                        Text(
                            "NEXT TRAIN" + (nextLA?.let { " · ${MetroLine.fromCode(it.line).displayName.uppercase()} to ${it.arrival.shortDestination.uppercase()}" } ?: ""),
                            color = ink, style = MaterialTheme.typography.labelMedium, letterSpacing = 1.sp,
                        )
                        Text(
                            when {
                                next == null -> "Not predicted yet"
                                next.kind == ArrivalKind.BOARDING -> "Boarding now"
                                next.kind == ArrivalKind.ARRIVING || next.minutes == 0 -> "Arriving"
                                else -> "${next.minutes} min"
                            },
                            color = ink, fontSize = 52.sp, fontWeight = FontWeight.Black, lineHeight = 54.sp,
                        )
                        // The next trains as chips in their line's colour, so you can see which comes when.
                        val rest = ls.arrivals.drop(1).take(4)
                        if (rest.isNotEmpty()) {
                            Text("THEN", color = ink, style = MaterialTheme.typography.labelSmall, letterSpacing = 1.sp, modifier = Modifier.padding(top = 6.dp))
                            TrainChips(rest)
                        }
                        if (next == null) {
                            Text(
                                "WMATA hasn't predicted a train for this ride yet — check the platform sign.",
                                color = ink, style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        if (useLocation == true && !hasPermission) {
                            TextButton(onClick = {
                                askLocation.launch(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION))
                            }) { Text("📍 Allow location so it can notice you boarding", color = ink) }
                        }
                        if (watching) {
                            Surface(shape = RoundedCornerShape(50), color = ink.copy(alpha = 0.18f), modifier = Modifier.padding(top = 12.dp)) {
                                Text(
                                    "📍 Watching for your train to leave — no need to tap",
                                    color = ink, style = MaterialTheme.typography.labelLarge,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                )
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        Button(
                            onClick = onBoard,
                            colors = ButtonDefaults.buttonColors(containerColor = ink, contentColor = color),
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                        ) { Text(if (watching) "I'm on the train (tap if it doesn't notice)" else "I'm on the train", fontWeight = FontWeight.Bold, fontSize = if (watching) 14.sp else 17.sp) }
                    } else {
                        val stops = live.track?.stopsAway?.let { ceil(it.toDouble()).toInt() }
                            ?: live.etaMinutes?.let { ceil(it / com.metrocast.app.data.LiveTripEngine.MINUTES_PER_STOP.toDouble()).toInt() }
                        val target = ls.leg.to.name
                        Text(if (live.isLastLeg) "RIDING TO YOUR STOP" else "RIDING TO YOUR TRANSFER", color = ink, style = MaterialTheme.typography.labelLarge, letterSpacing = 2.sp)
                        Text(target, color = ink, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                when (stops) { null -> "–"; else -> "$stops" },
                                color = ink, fontSize = 52.sp, fontWeight = FontWeight.Black, lineHeight = 54.sp,
                            )
                            Text(if (stops == 1) " stop" else " stops", color = ink, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 8.dp))
                            Spacer(Modifier.width(18.dp))
                            live.etaMinutes?.let {
                                Text("~$it", color = ink, fontSize = 36.sp, fontWeight = FontWeight.Bold, lineHeight = 40.sp)
                                Text(if (live.etaIsLive) " min" else " min (est.)", color = ink, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 6.dp))
                            }
                        }
                        Text(
                            live.track?.where?.let { "Your train: $it" } ?: "Following your train by the schedule (live position not available)",
                            color = ink, style = MaterialTheme.typography.bodyMedium,
                        )
                        if (autoBoarded) {
                            Text("📍 Noticed you boarded", color = ink, style = MaterialTheme.typography.labelMedium)
                        }
                        // How fast you're going.
                        if (speedOn) {
                            val sp = repo?.liveSpeed()
                            Text(
                                when {
                                    sp == null -> "🚄 Measuring your speed…"
                                    sp.stopped -> "🚄 Stopped"
                                    else -> "🚄 ${sp.text(repo?.prefs?.metric == true)}"
                                } + (sp?.let { if (it.fromGps) " · GPS" else " · from WMATA positions" } ?: ""),
                                color = ink, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                        ElevatorNote(ls.leg.to.code, Modifier.padding(top = 6.dp))
                        // Which side to stand by: from WMATA's list of center-platform stations.
                        val alightLine = live.rideLine ?: ls.leg.options.first().line
                        val alightCode = (ls.leg.options.firstOrNull { it.line == alightLine } ?: ls.leg.options.first()).alightCode
                        Surface(shape = RoundedCornerShape(50), color = ink.copy(alpha = 0.14f), modifier = Modifier.padding(top = 8.dp)) {
                            Text(
                                "${com.metrocast.app.data.Platforms.doorText(alightLine, alightCode)} at $target",
                                color = ink, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            )
                        }
                        if (stops != null && stops <= 1) {
                            Surface(shape = RoundedCornerShape(12.dp), color = ink, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                                Text(
                                    if (stops == 0) "You're at $target — get off here" else "Get off at the next stop: $target",
                                    color = color, fontWeight = FontWeight.Bold, modifier = Modifier.padding(12.dp),
                                )
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        Button(
                            onClick = onOff,
                            colors = ButtonDefaults.buttonColors(containerColor = ink, contentColor = color),
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                        ) { Text(if (live.isLastLeg) "I've arrived" else "I'm off at $target", fontWeight = FontWeight.Bold, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                }
            }

            // ---- The transfer, live (right under the main card so it's always in view).
            if (!live.isLastLeg) TransferLive(live)

            // ---- Where your train is.
            if (live.stretch.size >= 2) WhereIsMyTrain(live, color)

            // ---- Last train: which exit to use.
            if (live.isLastLeg && repo != null) plan.to?.let { StationExits(it, repo) }
        }
    }
}

/** Legs as coloured segments; done ones full, the current one highlighted. */
@Composable
private fun Progress(live: LiveTrip) {
    val legs = live.plan.legs
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        legs.forEachIndexed { i, ls ->
            val c = Color(MetroLine.fromCode(ls.leg.options.first().line).color)
            val alpha = when {
                i < live.leg -> 1f
                i == live.leg -> if (live.onBoard) 0.85f else 0.55f
                else -> 0.22f
            }
            Box(
                Modifier
                    .weight(ls.leg.stops.coerceAtLeast(1).toFloat())
                    .height(10.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(c.copy(alpha = alpha))
                    .then(if (i == live.leg) Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, RoundedCornerShape(5.dp)) else Modifier),
            )
        }
    }
    Text(
        if (live.arrived) "Trip complete" else "Step ${live.leg + 1} of ${legs.size}" +
            if (legs.size > 1) " · ${legs.size - 1} transfer${if (legs.size > 2) "s" else ""}" else "",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** The last stations before where you get off, with your train on them. */
@Composable
private fun WhereIsMyTrain(live: LiveTrip, color: Color) {
    val st = live.stretch
    val n = st.size
    val rowH = 36.dp
    ElevatedCard(shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("WHERE YOUR TRAIN IS", style = MaterialTheme.typography.labelSmall, letterSpacing = 1.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().height(rowH * n)) {
                Box(
                    Modifier.offset(x = 49.dp, y = rowH / 2).width(6.dp).height(rowH * (n - 1))
                        .clip(RoundedCornerShape(3.dp)).background(color.copy(alpha = 0.6f)),
                )
                st.forEachIndexed { i, s ->
                    val end = i == n - 1
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.offset(y = rowH * i).height(rowH).fillMaxWidth()) {
                        Spacer(Modifier.width(if (end) 43.dp else 45.dp))
                        Box(
                            Modifier.size(if (end) 18.dp else 14.dp).clip(CircleShape)
                                .background(if (end) color else MaterialTheme.colorScheme.surface)
                                .border(3.dp, if (end) MaterialTheme.colorScheme.onSurface else color, CircleShape),
                        )
                        Spacer(Modifier.width(14.dp))
                        Text(
                            if (end) "${s.name} — get off here" else s.name,
                            style = if (end) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
                            fontWeight = if (end) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                // Your train, when it's on this stretch (or waiting at the platform before you board).
                val pos: Float? = when {
                    live.track != null -> (n - 1) - live.track.stopsAway
                    !live.onBoard -> 0f
                    else -> null
                }
                if (pos != null && pos >= -0.5f) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .offset(x = 2.dp, y = rowH * pos.coerceAtLeast(0f) + rowH / 2 - 12.dp)
                            .size(40.dp, 24.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .background(if (live.onBoard) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
                    ) {
                        Text(if (live.onBoard) "YOU" else "▼", color = MaterialTheme.colorScheme.onPrimary, fontSize = 11.sp, fontWeight = FontWeight.Black)
                    }
                }
            }
            if (live.onBoard && live.track == null) {
                Text(
                    "Live position not available right now — using the scheduled ride time.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** The transfer: a big run / hurry / take-your-time banner, timings, and sign-by-sign directions. */
@Composable
private fun TransferLive(live: LiveTrip) {
    val inLeg = live.legStatus ?: return
    val outLeg = live.plan.legs.getOrNull(live.leg + 1) ?: return
    val outOpt = outLeg.leg.options.first()
    val outLine = MetroLine.fromCode(live.connectionLine ?: outOpt.line)
    val inLine = MetroLine.fromCode(inLeg.leg.options.first().line)
    val station = outLeg.leg.from.name
    val changeLevels = inLeg.leg.options.first().alightCode != outOpt.boardCode

    val (headline, sub, bg) = when (live.verdict) {
        Verdict.RUN -> Triple("RUN 🏃", "Only ${live.slackMinutes} min to make the ${outLine.displayName} train", Color(0xFFD32F2F))
        Verdict.WALK_FAST -> Triple("HURRY", "${live.slackMinutes} min to reach the ${outLine.displayName} train", Color(0xFFE08A00))
        Verdict.EASY -> Triple("TAKE YOUR TIME", "${live.slackMinutes} min to spare for the ${outLine.displayName} train", Color(0xFF2E7D32))
        Verdict.MISS -> if (live.tooEarlyForConnection) {
            Triple("TOO EARLY TO TELL", "WMATA predicts ~20–30 min ahead — the run / hurry check starts as you get closer", Color(0xFF455A64))
        } else {
            Triple("NO CONNECTION YET", "No ${outLine.displayName} train predicted after you arrive", Color(0xFF616161))
        }
        null -> Triple("TRANSFER", "Connection times appear when trains are predicted", Color(0xFF616161))
    }

    ElevatedCard(shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
        Column {
            Column(Modifier.fillMaxWidth().background(bg).padding(18.dp)) {
                Text("TRANSFER AT ${station.uppercase()}", color = Color.White, style = MaterialTheme.typography.labelMedium, letterSpacing = 1.sp)
                Text(headline, color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Black)
                Text(sub, color = Color.White, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                RestroomNote(outOpt.boardCode, Modifier.padding(top = 6.dp), light = true)
                ElevatorNote(outOpt.boardCode, Modifier.padding(top = 6.dp))
            }
            Column(Modifier.padding(16.dp)) {
                // Timings.
                Row(Modifier.fillMaxWidth()) {
                    TimeBox(if (live.etaIsLive) "You arrive" else "You arrive (est.)", live.etaMinutes, Modifier.weight(1f))
                    TimeBox("${outLine.displayName} leaves", live.connectionOut, Modifier.weight(1f))
                    TimeBox("Next after", live.backupMinutes, Modifier.weight(1f))
                }
                if (outLeg.arrivals.isNotEmpty()) {
                    Text(
                        "CONNECTING TRAINS AT ${station.uppercase()}",
                        style = MaterialTheme.typography.labelSmall, letterSpacing = 1.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp),
                    )
                    TrainChips(outLeg.arrivals.take(5), onCard = false)
                }
                Spacer(Modifier.height(14.dp))
                Text("HOW TO GET THERE", style = MaterialTheme.typography.labelSmall, letterSpacing = 1.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val steps = buildList {
                    val inOpt = inLeg.leg.options.first()
                    add("Get off the ${inLine.displayName} train at $station — doors open on your ${com.metrocast.app.data.Platforms.doorSide(inOpt.line, inOpt.alightCode).word}.")
                    if (changeLevels) {
                        add("Look for signs with the ${outLine.displayName.lowercase()} line colour saying “${outLine.displayName} Line” and “${outOpt.towardName}”.")
                        add("Take the escalator or stairs to the ${outLine.displayName} Line level.")
                        add("Go to the track signed “${outOpt.towardName}”.")
                    } else {
                        add("Stay on this platform — the ${outLine.displayName} Line stops here. Check the sign says “${outOpt.towardName}”.")
                    }
                    val signs = outLeg.arrivals.map { it.arrival.shortDestination }.filter { it.isNotBlank() }.distinct().ifEmpty { listOf(outOpt.towardName) }
                    add("Board a train showing " + signs.joinToString(" or ") { "“$it”" } + ".")
                    if (com.metrocast.app.data.Platforms.isCenter(outOpt.line, outOpt.boardCode))
                        add("It's a center platform: trains both ways stop on either side of you, so check the sign over your track.")
                }
                steps.forEachIndexed { i, s ->
                    Row(Modifier.padding(top = 6.dp)) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.size(22.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
                        ) { Text("${i + 1}", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold) }
                        Spacer(Modifier.width(10.dp))
                        Text(s, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Text(
                    "You'll get a notification 2 stops before $station.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun TimeBox(label: String, minutes: Int?, modifier: Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            when (minutes) { null -> "–"; 0 -> "now"; else -> "$minutes min" },
            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun ArrivedCard(name: String, autoEndMinutes: Int?, onEnd: () -> Unit, code: String? = null) {
    ElevatedCard(shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("🎉", fontSize = 48.sp)
            Text("You've arrived at $name", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            if (code != null) RestroomNote(code, Modifier.padding(top = 4.dp))
            if (code != null) ElevatorNote(code, Modifier.padding(top = 4.dp))
            autoEndMinutes?.let { m ->
                Text(
                    if (m <= 1) "This trip ends automatically in about a minute" else "This trip ends automatically in $m min",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = onEnd) { Text("End trip") }
        }
    }
}

/** Location updates while composed (GPS and network); nothing is stored — each fix is handed on and dropped. */
@Composable
private fun LocationWatch(onLocation: (Double, Double, Float?) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val latest = androidx.compose.runtime.rememberUpdatedState(onLocation)
    DisposableEffect(Unit) {
        val lm = context.getSystemService(android.location.LocationManager::class.java)
        val listener = android.location.LocationListener { loc ->
            latest.value(loc.latitude, loc.longitude, if (loc.hasSpeed()) loc.speed else null)
        }
        if (lm != null && LocationWatch.granted(context)) {
            try {
                for (p in listOf(android.location.LocationManager.GPS_PROVIDER, android.location.LocationManager.NETWORK_PROVIDER)) {
                    if (lm.isProviderEnabled(p)) lm.requestLocationUpdates(p, 5_000L, 10f, listener, android.os.Looper.getMainLooper())
                }
            } catch (_: SecurityException) {
                // Permission withdrawn: the button still works.
            }
        }
        onDispose { runCatching { lm?.removeUpdates(listener) } }
    }
}

object LocationWatch {
    fun granted(context: android.content.Context): Boolean =
        listOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION).any {
            androidx.core.content.ContextCompat.checkSelfPermission(context, it) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
}

/** Upcoming trains as chips in their line's colour: "SV  4 min · Fed Ctr SW". */
@Composable
private fun TrainChips(trains: List<com.metrocast.app.data.LegArrival>, onCard: Boolean = true) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(top = 4.dp).horizontalScroll(androidx.compose.foundation.rememberScrollState()),
    ) {
        trains.forEach { la ->
            val l = MetroLine.fromCode(la.line)
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color(l.cardColor),
                border = if (onCard) androidx.compose.foundation.BorderStroke(1.5.dp, Color.White.copy(alpha = 0.85f)) else null,
            ) {
                Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                    Text(
                        "${l.displayName} · ${la.arrival.label}",
                        color = Color(l.onColor), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
                    )
                    Text(
                        la.arrival.shortDestination,
                        color = Color(l.onColor), style = MaterialTheme.typography.labelSmall, maxLines = 1,
                    )
                }
            }
        }
    }
}
