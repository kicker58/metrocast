package com.metrocast.app.ui

import androidx.core.net.toUri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metrocast.app.data.Incident
import com.metrocast.app.data.MetroLine
import com.metrocast.app.data.Repository
import com.metrocast.app.data.StationExit
import com.metrocast.app.data.StationInfo

/** Service changes on the lines a route uses, right where you plan or ride it. */
@Composable
fun RouteAlerts(alerts: List<Incident>) {
    if (alerts.isEmpty()) return
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("⚠️", fontSize = 18.sp)
                Spacer(Modifier.width(8.dp))
                Text(
                    "Service change on your route",
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            alerts.forEach { i ->
                Row(Modifier.padding(top = 8.dp)) {
                    i.lines.forEach { l -> LinePill(l, size = 20); Spacer(Modifier.width(3.dp)) }
                    Spacer(Modifier.width(5.dp))
                    Text(i.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
            Text(
                "Times shown already reflect this — trains ending early are left out.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f),
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/** The exits at a station with WMATA's descriptions; "Map" opens the exit in your maps app. */
@Composable
fun StationExits(station: StationInfo, repo: Repository) {
    val context = LocalContext.current
    var exits by remember(station.code) { mutableStateOf<List<StationExit>?>(null) }
    var failed by remember(station.code) { mutableStateOf(false) }
    LaunchedEffect(station.code) {
        runCatching { repo.loadExits(station) }
            .onSuccess { exits = it }
            .onFailure { failed = true }
    }
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("EXITS AT ${station.name.uppercase()}", style = MaterialTheme.typography.labelSmall, letterSpacing = 1.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val list = exits
            when {
                failed -> Text("Couldn't load the exits right now.", style = MaterialTheme.typography.bodySmall)
                list == null -> Text("Loading exits…", style = MaterialTheme.typography.bodySmall)
                list.isEmpty() -> Text("No exit details from WMATA for this station.", style = MaterialTheme.typography.bodySmall)
                else -> list.forEach { e ->
                    Row(verticalAlignment = Alignment.Top) {
                        Text(if (e.elevator) "" else "🚶", fontSize = 18.sp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                e.name + if (e.elevator) " · elevator" else "",
                                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                            )
                            if (e.description.isNotBlank()) {
                                Text(e.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        TextButton(onClick = {
                            val label = android.net.Uri.encode("${station.name} exit: ${e.name}")
                            val uri = "geo:${e.lat},${e.lon}?q=${e.lat},${e.lon}($label)".toUri()
                            runCatching {
                                context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, uri).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                            }
                        }) { Text("Map") }
                    }
                }
            }
        }
    }
}

/** Small "⚠ service change" tag, e.g. on a saved route. */
@Composable
fun AlertTag(alerts: List<Incident>) {
    if (alerts.isEmpty()) return
    val lines = alerts.flatMap { it.lines }.distinct().joinToString(", ") { MetroLine.fromCode(it).displayName }
    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.padding(top = 6.dp)) {
        Text(
            "⚠ Service change: $lines",
            color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/** "🚻 Restroom — open, no need to ask" (or ask the station manager / closed), if the station has one. */
@Composable
fun RestroomNote(code: String, modifier: Modifier = Modifier, light: Boolean = false) {
    val r = com.metrocast.app.data.Restrooms.at(code) ?: return
    val open = !r.askManager && r.status != com.metrocast.app.data.Restrooms.Status.CLOSED_FOR_RENOVATION
    Text(
        com.metrocast.app.data.Restrooms.label(r),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = if (open) FontWeight.SemiBold else FontWeight.Normal,
        color = when {
            light -> androidx.compose.ui.graphics.Color.White
            open -> androidx.compose.ui.graphics.Color(0xFF2E7D32)
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = modifier,
    )
}
