package com.metrocast.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metrocast.app.data.LineMap
import com.metrocast.app.data.MapTrain
import com.metrocast.app.data.MetroLine
import com.metrocast.app.data.Station
import com.metrocast.app.data.TripStatus

private val TrainSilver = Color(0xFFCDD3D5)  // train markers
private val TrainEdge = Color(0xFF5E6668)
private val RowHeight = 42.dp
private val LaneWidth = 80.dp

/**
 * Live schematic of one line with every train on it (refreshed every 30 s by the app).
 * With [focus], it also shows that trip's next trains and numbers them on the map.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LineMapScreen(lineCode: String, map: LineMap?, focus: TripStatus?, highlight: Set<String>, onBack: () -> Unit) {
    val line = MetroLine.fromCode(lineCode)
    val lineColor = Color(line.color)
    val scroll = rememberScrollState()
    val density = LocalDensity.current

    // Numbered trains for the focused trip, in arrival order.
    val numbered: Map<String, Int> = focus?.location?.trains
        ?.mapIndexedNotNull { i, t -> t.trainId.takeIf { it.isNotBlank() }?.let { it to i + 1 } }
        ?.toMap() ?: emptyMap()

    // Scroll so the trip's station is in view the first time the map appears.
    var diagramTop by remember { mutableStateOf<Float?>(null) }
    var scrolled by remember { mutableStateOf(false) }
    val focusIndex = map?.stations?.indexOfFirst { it.code == focus?.trip?.stationCode } ?: -1
    LaunchedEffect(diagramTop, focusIndex) {
        val top = diagramTop ?: return@LaunchedEffect
        if (!scrolled && focusIndex >= 0) {
            val row = with(density) { RowHeight.toPx() }
            scroll.animateScrollTo((top + row * focusIndex - row * 4).toInt().coerceAtLeast(0))
            scrolled = true
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LinePill(lineCode, size = 30)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("${line.displayName} Line · Live", fontWeight = FontWeight.Bold)
                            Text(
                                if (map == null) "Loading train positions…"
                                else "${map.trains.size + map.branchTrains.size} trains · " + Format.updated(map.updatedAt),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        Column(
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
        ) {
            if (com.metrocast.app.data.Repository.lastPositionsDown) {
                OfflineBanner("WMATA isn't sharing train locations right now (their live feed is empty). Arrival times still work.", Modifier.padding(bottom = 10.dp))
            }
            focus?.let { FocusCard(it, lineColor) }

            if (map == null) {
                ElevatedCard(shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "Waiting for live positions from WMATA. They refresh every 30 seconds while the app is open.",
                        modifier = Modifier.padding(24.dp),
                    )
                }
                return@Column
            }

            if (map.etaTargets.isNotEmpty()) EtaNote(map)

            ElevatedCard(
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .onGloballyPositioned { diagramTop = it.positionInParent().y },
            ) {
                Column {
                    LaneHeaders(map, lineCode)
                    LineDiagram(map.stations, map.trains, lineCode, highlight, numbered, map.etas, Modifier.padding(bottom = 14.dp))
                }
            }

            if (map.branchStations.isNotEmpty()) {
                Column(Modifier.padding(top = 6.dp)) {
                    Text("New Carrollton branch", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "Every other Silver train continues here after Stadium-Armory.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                ElevatedCard(shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
                    LineDiagram(map.branchStations, map.branchTrains, lineCode, highlight, numbered, map.etas, Modifier.padding(vertical = 14.dp))
                }
            }
        }
    }
}

/** The trip's next trains, matching the numbers on the map. */
@Composable
private fun FocusCard(status: TripStatus, lineColor: Color) {
    val trip = status.trip
    ElevatedCard(shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp)) {
            Text(trip.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "${trip.stationName} → ${trip.towardName}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(12.dp))
            if (status.arrivals.isEmpty()) {
                Text("No trains predicted right now", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    status.arrivals.take(4).forEach { a ->
                        Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            ) {
                                Text(a.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                Text(a.shortDestination, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                            }
                        }
                    }
                }
            }
            val approaching = status.location?.trains.orEmpty()
            if (approaching.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                HorizontalDivider()
                Spacer(Modifier.height(10.dp))
                Text(
                    "ON THE WAY",
                    style = MaterialTheme.typography.labelSmall,
                    letterSpacing = 1.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                approaching.forEach { t ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(t.description, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Text(
                                listOf(t.stopsLabel, t.destinationName.takeIf { it.isNotBlank() }?.let { "to $it" })
                                    .filterNotNull().joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Above the two lanes: which way each lane's trains go, and how many there are. */
@Composable
private fun LaneHeaders(map: LineMap, lineCode: String) {
    val all = map.trains + map.branchTrains
    val first = map.stations.first().name.let { towardShort(it) }
    val last = if (lineCode == "SV") "Largo / New Carrollton" else towardShort(map.stations.last().name)
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 4.dp, vertical = 8.dp),
    ) {
        Column(Modifier.width(LaneWidth), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("▲ To", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            Text(first, style = MaterialTheme.typography.labelSmall, maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Text("${all.count { !it.eastbound }} trains", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(20.dp))
        Column(Modifier.width(LaneWidth), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("▼ To", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            Text(last, style = MaterialTheme.typography.labelSmall, maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Text("${all.count { it.eastbound }} trains", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(14.dp))
        Text(
            "Each chip is a train, where it is right now, named by where it's going.",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f).padding(top = 2.dp),
        )
    }
}

private fun towardShort(name: String) = com.metrocast.app.data.Arrival(0, com.metrocast.app.data.ArrivalKind.MINUTES, name, "").shortDestination

@Composable
private fun Legend(first: String, last: String, lineCode: String) {
    val lastName = if (lineCode == "SV") "DC / Largo / New Carrollton" else last
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        LegendChip(up = true, text = "To $first", modifier = Modifier.weight(1f))
        LegendChip(up = false, text = "To $lastName", modifier = Modifier.weight(1f))
    }
}

@Composable
private fun LegendChip(up: Boolean, text: String, modifier: Modifier) {
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            TrainChip(up = up, number = null)
            Spacer(Modifier.width(8.dp))
            Text(text, style = MaterialTheme.typography.labelMedium, maxLines = 2)
        }
    }
}

/**
 * Stations top to bottom with the line down the middle; trains heading to the first station ride in
 * the left lane, trains heading to the last station in the right lane, placed between stations.
 */
@Composable
private fun LineDiagram(
    stations: List<Station>,
    trains: List<MapTrain>,
    lineCode: String,
    highlight: Set<String>,
    numbered: Map<String, Int>,
    etas: Map<String, com.metrocast.app.data.TrainEta>,
    modifier: Modifier,
) {
    val lineColor = Color(MetroLine.fromCode(lineCode).color)
    Box(modifier.fillMaxWidth().height(RowHeight * stations.size)) {
        Box(
            Modifier
                .offset(x = LaneWidth + 9.dp, y = RowHeight / 2)
                .width(6.dp)
                .height(RowHeight * (stations.size - 1))
                .background(lineColor, RoundedCornerShape(3.dp))
        )
        stations.forEachIndexed { i, st ->
            val mine = st.code in highlight
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.offset(y = RowHeight * i).height(RowHeight).fillMaxWidth(),
            ) {
                Spacer(Modifier.width(LaneWidth + 4.dp))
                Box(
                    Modifier
                        .size(if (mine) 18.dp else 14.dp)
                        .offset(x = if (mine) (-1).dp else 1.dp)
                        .background(if (mine) lineColor else MaterialTheme.colorScheme.surface, CircleShape)
                        .border(3.dp, if (mine) MaterialTheme.colorScheme.onSurface else lineColor, CircleShape)
                )
                Spacer(Modifier.width(LaneWidth + 8.dp))
                Text(
                    st.name,
                    style = if (mine) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
                    fontWeight = if (mine) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // Plain trains first, numbered (your trip's) ones on top.
        trains.sortedBy { numbered.containsKey(it.id) }.forEach { t -> TrainMarker(t, numbered[t.id], etas[t.id]?.short, lineCode, stations) }
    }
}

@Composable
private fun TrainMarker(t: MapTrain, number: Int?, eta: String?, lineCode: String, stations: List<Station>) {
    // Read the animated value only at layout time, so a gliding train doesn't recompose the map.
    val y = animateFloatAsState(t.pos, animationSpec = tween(1200), label = "train-${t.id}")
    val x: Dp = if (t.eastbound) LaneWidth + 24.dp else 4.dp
    Box(Modifier.offset { IntOffset(x.roundToPx(), (RowHeight * y.value + RowHeight / 2 - 11.dp).roundToPx()) }) {
        TrainChip(up = !t.eastbound, number = number, eta = eta, lineCode = lineCode, toward = towardName(stations, t))
    }
}

/** A silver train marker with its direction, or a highlighted one numbered for your trip. */
@Composable
private fun TrainChip(up: Boolean, number: Int?, eta: String? = null, lineCode: String? = null, toward: String? = null) {
    // Your trip's trains stand out; the rest wear their line's colour.
    val fill = when {
        number != null -> MaterialTheme.colorScheme.primary
        lineCode != null -> Color(MetroLine.fromCode(lineCode).color)
        else -> TrainSilver
    }
    val edge = if (number != null) MaterialTheme.colorScheme.onPrimaryContainer else Color(0x66000000)
    val ink = when {
        number != null -> MaterialTheme.colorScheme.onPrimary
        lineCode != null -> onLineColor(lineCode)
        else -> Color(0xFF263033)
    }
    val arrow = if (up) "▲" else "▼"
    // Your trip's trains are highlighted (no number: it read like part of the time).
    val text = if (number != null || eta != null) listOfNotNull(eta).joinToString(" ") + arrow
        else arrow + (toward?.let { " $it" } ?: "")
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(width = LaneWidth - 6.dp, height = 24.dp)
            .background(fill, RoundedCornerShape(8.dp))
            .border(1.5.dp, edge, RoundedCornerShape(8.dp)),
    ) {
        Text(text, color = ink, fontSize = if (text.length > 11) 10.sp else 11.sp, fontWeight = FontWeight.Bold, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 3.dp))
    }
}

/** Explains the times on the trains, e.g. "▼ times: minutes to Metro Center". */
@Composable
private fun EtaNote(map: LineMap) {
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text("TIMES ON THE TRAINS", style = MaterialTheme.typography.labelSmall, letterSpacing = 1.sp)
            map.etaTargets.forEach { t ->
                Text(
                    "${if (t.eastbound) "▼" else "▲"}  minutes until it reaches ${t.stationName}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
            Text(
                "\"~\" means an estimate (no live prediction yet)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                modifier = Modifier.padding(top = 3.dp),
            )
        }
    }
}
