package com.metrocast.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metrocast.app.data.Repository
import com.metrocast.app.data.TrackWork
import com.metrocast.app.data.Trip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/**
 * Menu → Track work calendar: WMATA's planned work, starting with what hits your Going there and
 * Coming back trips, then everything else on Metro, then notices at your own stations.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackWorkScreen(repo: Repository, onBack: () -> Unit, today: LocalDate = LocalDate.now(ZoneId.of("America/New_York"))) {
    var list by remember { mutableStateOf(repo.trackWorkCached()) }
    var impacts by remember { mutableStateOf(emptyList<TrackWork.Impact>()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var alerts by remember { mutableStateOf(repo.prefs.trackWorkAlerts) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.Default) { impacts = repo.trackWorkImpacts(list, today) }
        val fresh = withContext(Dispatchers.IO) { runCatching { repo.loadTrackWork() } }
        fresh.onSuccess { l -> list = l; impacts = withContext(Dispatchers.Default) { repo.trackWorkImpacts(l, today) } }
            .onFailure { if (list.isEmpty()) error = "Couldn't reach wmata.com — pull up this screen again when you're online." }
        loading = false
    }
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val yours = impacts.filter { it.level != TrackWork.Level.STATION }
        .groupBy { Triple(it.advisory.id, it.change?.line, it.start) }.values.toList()
    val stationNotices = impacts.filter { it.level == TrackWork.Level.STATION }.groupBy { it.advisory.id }.values.toList()
    val yourIds = yours.flatten().map { it.advisory.id to it.change?.line }.toSet()
    val elsewhere = list.filter { it.kind.service && it.end >= today }.sortedBy { it.start }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Track work calendar", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            item(key = "intro") {
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("🚧 Planned work on your trips", fontWeight = FontWeight.Bold)
                        Text(
                            "From WMATA's planned service changes. Weekend details are usually posted a few days before each weekend; big projects weeks ahead.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(if (alerts) "Warn me ${repo.prefs.trackWorkDays} day${if (repo.prefs.trackWorkDays == 1) "" else "s"} ahead" else "Heads-up is off", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                Text(if (repo.prefs.trackWorkMajorOnly) "Only when your trip is disrupted · change in Settings" else "A notification when work on your trips is coming up · options in Settings", style = MaterialTheme.typography.bodySmall)
                            }
                            Switch(checked = alerts, onCheckedChange = {
                                alerts = it; repo.prefs.trackWorkAlerts = it
                                runCatching { com.metrocast.app.notify.Background.sync(context) }
                            })
                        }
                    }
                }
            }
            if (loading && list.isEmpty()) item(key = "loading") {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(10.dp)); Text("Checking wmata.com…")
                }
            }
            error?.let { e -> item(key = "error") { Text(e, color = MaterialTheme.colorScheme.error) } }

            item(key = "h-yours") { Header("YOUR TRIPS") }
            if (!loading || list.isNotEmpty()) {
                if (yours.isEmpty()) item(key = "none") {
                    Surface(shape = MaterialTheme.shapes.medium, color = Color(0xFF2E7D32).copy(alpha = 0.12f), modifier = Modifier.fillMaxWidth()) {
                        Text("✅ No planned work on your trips in WMATA's current schedule.", modifier = Modifier.padding(14.dp), fontWeight = FontWeight.SemiBold)
                    }
                }
                items(yours, key = { g -> "y-" + g.first().key }) { g -> ImpactCard(g, today) { runCatching { uri.openUri(g.first().advisory.url) } } }
            }

            val other = elsewhere.filter { a -> a.changes.isEmpty() && (a.id to null) !in yourIds || a.changes.any { (a.id to it.line) !in yourIds } }
            if (other.isNotEmpty()) {
                item(key = "h-else") { Header("ELSEWHERE ON METRO") }
                items(other, key = { "e-" + it.id }) { a ->
                    AdvisoryCard(a, a.changes.filter { (a.id to it.line) !in yourIds }, today) { runCatching { uri.openUri(a.url) } }
                }
            }
            if (stationNotices.isNotEmpty()) {
                item(key = "h-stations") { Header("AT YOUR STATIONS") }
                items(stationNotices, key = { "s-" + it.first().advisory.id }) { g ->
                    val a = g.first().advisory
                    ElevatedCard(onClick = { runCatching { uri.openUri(a.url) } }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("📍 ${a.title}", fontWeight = FontWeight.SemiBold)
                            Text("Until ${TrackWork.dates(a.end, a.end)} · ${g.joinToString { roleOf(it.trip) }.let { it }}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (a.summary.isNotBlank()) Text(a.summary, style = MaterialTheme.typography.bodySmall, maxLines = 3)
                        }
                    }
                }
            }
            item(key = "foot") {
                Text(
                    "Source: wmata.com · updated ${repo.trackWorkUpdatedAt().takeIf { it > 0 }?.let { Format.clock(it) } ?: "—"}. Plans can change; tap a card for WMATA's page.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Header(t: String) =
    Text(t, style = MaterialTheme.typography.labelLarge, letterSpacing = 1.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))

internal fun roleOf(t: Trip) = when (t.label) { Trip.GOING -> "➡️ Going there"; Trip.BACK -> "⬅️ Coming back"; else -> "${t.stationName} → ${t.destName}" }

private fun levelColor(l: TrackWork.Level) = when (l) {
    TrackWork.Level.BLOCKED -> Color(0xFFC62828)
    TrackWork.Level.IN_ZONE -> Color(0xFFE65100)
    TrackWork.Level.LINE -> Color(0xFF9A6700)
    TrackWork.Level.STATION -> Color(0xFF1565C0)
}

/** A calendar tile: month on top, day number big, weekday(s) below. */
@Composable
private fun DateTile(start: LocalDate, end: LocalDate, color: Color) {
    Surface(color = color.copy(alpha = 0.12f), shape = RoundedCornerShape(12.dp), modifier = Modifier.width(62.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(vertical = 6.dp)) {
            Text(start.month.getDisplayName(TextStyle.SHORT, Locale.US).uppercase(), style = MaterialTheme.typography.labelSmall, color = color, fontWeight = FontWeight.Bold)
            Text(if (end != start && end.month == start.month) "${start.dayOfMonth}–${end.dayOfMonth}" else "${start.dayOfMonth}", fontSize = if (end != start) 16.sp else 22.sp, fontWeight = FontWeight.Black, color = color)
            Text(
                start.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.US) + if (end != start) "–" + end.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.US) else "",
                style = MaterialTheme.typography.labelSmall, color = color,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ImpactCard(group: List<TrackWork.Impact>, today: LocalDate, onOpen: () -> Unit) {
    val i = group.minBy { it.level.ordinal }
    val color = levelColor(i.level)
    ElevatedCard(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().padding(0.dp)) {
            Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DateTile(i.start, i.end, color)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${TrackWork.whenLabel(i.start, i.end, today).uppercase()} · ${i.advisory.kind.label.uppercase()}", style = MaterialTheme.typography.labelSmall, color = color, fontWeight = FontWeight.Bold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        (i.change?.line?.let { listOf(it) } ?: i.advisory.lines).forEach { LinePill(it, size = 20); Spacer(Modifier.width(4.dp)) }
                        Spacer(Modifier.width(2.dp))
                        Text(i.change?.text ?: i.advisory.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    }
                    if (i.change?.days?.isNotBlank() == true) Text(i.change.days, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("⚠ ${i.level.note}", style = MaterialTheme.typography.bodySmall, color = color, fontWeight = FontWeight.SemiBold)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        group.map { it.trip }.distinctBy { it.id }.forEach { t ->
                            Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                                Text(roleOf(t), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                            }
                        }
                    }
                    TextButton(onClick = onOpen, contentPadding = PaddingValues(0.dp)) { Text("Details on wmata.com ›") }
                }
            }
        }
    }
}

@Composable
private fun AdvisoryCard(a: TrackWork.Advisory, changes: List<TrackWork.Change>, today: LocalDate, onOpen: () -> Unit) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    ElevatedCard(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val s = changes.mapNotNull { it.start }.minOrNull() ?: a.start
            val e = changes.mapNotNull { it.end }.maxOrNull() ?: a.end
            DateTile(s, e, color)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("${TrackWork.whenLabel(s, e, today).uppercase()} · ${a.kind.label.uppercase()}", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = color)
                if (changes.isEmpty()) {
                    Text(a.title, fontWeight = FontWeight.SemiBold)
                    Row { a.lines.forEach { LinePill(it, size = 18); Spacer(Modifier.width(4.dp)) } }
                    if (a.summary.isNotBlank()) Text(a.summary, style = MaterialTheme.typography.bodySmall, maxLines = 3)
                } else changes.forEach { c ->
                    Row(verticalAlignment = Alignment.Top) {
                        LinePill(c.line, size = 18); Spacer(Modifier.width(6.dp))
                        Column {
                            Text(c.text, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                            if (c.days.isNotBlank()) Text(c.days, style = MaterialTheme.typography.labelSmall, color = color)
                        }
                    }
                }
            }
        }
    }
}
