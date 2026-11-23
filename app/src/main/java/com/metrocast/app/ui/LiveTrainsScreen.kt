package com.metrocast.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metrocast.app.data.LineMap
import com.metrocast.app.data.MetroLine
import com.metrocast.app.data.StationDirectory
import kotlinx.coroutines.launch
import kotlin.math.hypot

/**
 * Every train on every line: a map you can pinch, drag, zoom with buttons, and tap a train on,
 * then a plain list of every train by line underneath (tap one to find it on the map).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveTrainsScreen(maps: Map<String, LineMap>, onBack: () -> Unit) {
    // null = all lines; otherwise just this one.
    var only by remember { mutableStateOf<String?>(null) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var selected by remember { mutableStateOf<String?>(null) }
    var mapSize by remember { mutableStateOf(Size.Zero) }
    val density = LocalDensity.current
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val total = maps.values.sumOf { it.trains.size + it.branchTrains.size }
    val updated = maps.values.maxOfOrNull { it.updatedAt } ?: 0L
    val visible = only?.let { setOf(it) } ?: MetroLine.entries.map { it.code }.toSet()

    fun zoomTo(z1: Float, focus: Offset = Offset(mapSize.width / 2, mapSize.height / 2)) {
        val z = z1.coerceIn(1f, 12f)
        pan = (pan - focus) * (z / zoom) + focus
        zoom = z
    }
    /** Zooms in on a train and puts it in the middle of the map. */
    fun focusOn(id: String) {
        selected = id
        if (zoom < 2.5f) zoomTo(2.5f)
        val g = MapGeometry(mapSize, zoom, pan, density)
        placeTrains(g, maps, visible).firstOrNull { it.train.id == id }?.let { p ->
            pan += Offset(mapSize.width / 2, mapSize.height / 2) - p.at
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Live trains", fontWeight = FontWeight.Bold)
                        Text(
                            if (maps.isEmpty()) "Loading train positions…" else "$total trains running · ${Format.updated(updated)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(bottom = 32.dp),
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            // Lines: all, or one at a time.
            item(key = "chips") {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp),
                ) {
                    FilterChip(selected = only == null, onClick = { only = null }, label = { Text("All lines · $total") })
                    MetroLine.entries.forEach { l ->
                        val n = maps[l.code]?.let { it.trains.size + it.branchTrains.size } ?: 0
                        FilterChip(
                            selected = only == l.code,
                            onClick = { only = if (only == l.code) null else l.code; selected = null },
                            label = { Text("${l.displayName} · $n") },
                            leadingIcon = { Box(Modifier.size(12.dp).clip(CircleShape).background(Color(l.color))) },
                        )
                    }
                }
            }

            item(key = "map") {
                BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                    // The network is wider than tall: size the map to its shape so it fills the box.
                    val h = (maxWidth * 0.82f).coerceIn(280.dp, 520.dp)
                    Box(
                        Modifier.fillMaxWidth().height(h).clip(RoundedCornerShape(24.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerLow),
                    ) {
                        if (StationDirectory.stations.isEmpty() || maps.isEmpty()) {
                            Text(
                                "Loading the map… it fills in once the station list and live positions arrive.",
                                modifier = Modifier.align(Alignment.Center).padding(32.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            SystemMap(maps, visible, zoom, pan, selected,
                                onSize = { mapSize = it },
                                onTransform = { z, p -> zoom = z; pan = p },
                                onTap = { id -> selected = id },
                            )
                            // Zoom buttons for one-handed use.
                            Column(
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.align(Alignment.TopEnd).padding(10.dp),
                            ) {
                                MapButton("+", "Zoom in") { zoomTo(zoom * 1.6f) }
                                MapButton("−", "Zoom out") { zoomTo(zoom / 1.6f); if (zoom <= 1.01f) pan = Offset.Zero }
                                if (zoom != 1f || pan != Offset.Zero) MapButton("⤢", "Show the whole map") { zoom = 1f; pan = Offset.Zero }
                            }
                            // The tapped train, in words.
                            val sel = selected?.let { id -> maps.values.firstNotNullOfOrNull { m -> trainInfo(m, id) } }
                            if (sel != null) {
                                Surface(
                                    shape = RoundedCornerShape(16.dp), shadowElevation = 4.dp,
                                    color = MaterialTheme.colorScheme.surface,
                                    modifier = Modifier.align(Alignment.BottomCenter).padding(10.dp).fillMaxWidth(),
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp)) {
                                        LinePill(sel.line, size = 26)
                                        Spacer(Modifier.width(10.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text("${MetroLine.fromCode(sel.line).displayName} to ${sel.toward}", fontWeight = FontWeight.Bold)
                                            Text(sel.where + (sel.cars.takeIf { it > 0 }?.let { " · $it cars" } ?: ""), style = MaterialTheme.typography.bodySmall)
                                        }
                                        IconButton(onClick = { selected = null }) { Text("✕") }
                                    }
                                }
                            } else {
                                Text(
                                    "Tap a train · pinch or + to zoom",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
                                )
                            }
                        }
                    }
                }
            }

            // Every train in words, by line.
            for (l in MetroLine.entries.filter { it.code in visible }) {
                val m = maps[l.code] ?: continue
                val rows = (m.trains.map { m.stations to it } + m.branchTrains.map { m.branchStations to it })
                    .sortedWith(compareBy({ !it.second.eastbound }, { it.second.pos }))
                if (rows.isEmpty()) continue
                item(key = "h-${l.code}") {
                    val east = rows.count { it.second.eastbound }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 4.dp)) {
                        LinePill(l.code, size = 24)
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text("${l.displayName} Line · ${rows.size} trains", fontWeight = FontWeight.Bold)
                            Text(
                                "$east to ${endName(m.stations.lastOrNull()?.name)} · ${rows.size - east} to ${endName(m.stations.firstOrNull()?.name)}",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                items(rows, key = { "t-${l.code}-${it.second.id}" }) { (stations, t) ->
                    val isSel = t.id == selected
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                focusOn(t.id)
                                scope.launch { listState.animateScrollToItem(1) }
                            }
                            .background(if (isSel) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                            .padding(horizontal = 16.dp, vertical = 9.dp),
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.size(28.dp).clip(CircleShape).background(Color(l.color)),
                        ) { Text(if (t.eastbound) "▶" else "◀", color = onLineColor(l.code), fontSize = 12.sp) }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("To ${towardName(stations, t)}", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                            Text(whereIs(stations, t), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (t.cars > 0) Text("${t.cars} cars", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

private fun endName(n: String?) =
    com.metrocast.app.data.Arrival(0, com.metrocast.app.data.ArrivalKind.MINUTES, n.orEmpty(), "").shortDestination

private data class TrainInfo(val line: String, val toward: String, val where: String, val cars: Int)

private fun trainInfo(m: LineMap, id: String): TrainInfo? {
    m.trains.firstOrNull { it.id == id }?.let { return TrainInfo(m.lineCode, towardName(m.stations, it), whereIs(m.stations, it), it.cars) }
    m.branchTrains.firstOrNull { it.id == id }?.let { return TrainInfo(m.lineCode, towardName(m.branchStations, it), whereIs(m.branchStations, it), it.cars) }
    return null
}

@Composable
private fun MapButton(label: String, description: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick, shape = CircleShape, shadowElevation = 3.dp,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.size(40.dp).semantics { contentDescription = description },
    ) {
        Box(contentAlignment = Alignment.Center) { Text(label, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun SystemMap(
    maps: Map<String, LineMap>,
    visible: Set<String>,
    zoom: Float,
    pan: Offset,
    selected: String?,
    onSize: (Size) -> Unit,
    onTransform: (Float, Offset) -> Unit,
    onTap: (String?) -> Unit,
) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val colors = MapColors(MaterialTheme.colorScheme.onSurface, MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.onSurfaceVariant)
    val lines = remember(maps) { maps.mapValues { (_, m) -> listOf(m.stations, m.branchStations).filter { it.size >= 2 } } }
    var latestZoom by remember { mutableFloatStateOf(zoom) }
    var latestPan by remember { mutableStateOf(pan) }
    var latestVisible by remember { mutableStateOf(visible) }
    latestZoom = zoom; latestPan = pan; latestVisible = visible
    val count = visible.sumOf { l -> maps[l]?.let { it.trains.size + it.branchTrains.size } ?: 0 }
    Canvas(
        Modifier
            .fillMaxSize()
            .onSizeChanged { onSize(Size(it.width.toFloat(), it.height.toFloat())) }
            .semantics { contentDescription = "Map of $count trains. The list below the map describes each one." }
            .pointerInput(Unit) {
                detectTransformGestures { centroid, panChange, zoomChange, _ ->
                    val z0 = latestZoom
                    val z1 = (z0 * zoomChange).coerceIn(1f, 12f)
                    val p1 = (latestPan - centroid) * (z1 / z0) + centroid + panChange
                    latestZoom = z1; latestPan = p1
                    onTransform(z1, p1)
                }
            }
            .pointerInput(maps) {
                detectTapGestures { tap ->
                    // The nearest train within a finger's width, or nothing.
                    val g = MapGeometry(Size(size.width.toFloat(), size.height.toFloat()), latestZoom, latestPan, density)
                    val hit = placeTrains(g, maps, latestVisible).minByOrNull { hypot(it.at.x - tap.x, it.at.y - tap.y) }
                        ?.takeIf { hypot(it.at.x - tap.x, it.at.y - tap.y) < with(density) { 28.dp.toPx() } }
                    onTap(hit?.train?.id)
                }
            },
    ) {
        val g = MapGeometry(size, zoom, pan, this)
        drawMetro(g, lines, placeTrains(g, maps, visible), visible, measurer, colors, Labels.MAJOR_THEN_ALL, selected)
    }
}
