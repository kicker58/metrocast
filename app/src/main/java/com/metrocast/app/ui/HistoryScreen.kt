package com.metrocast.app.ui

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metrocast.app.data.History
import com.metrocast.app.data.HistoryTotals
import com.metrocast.app.data.Prefs
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Trips you've finished with a live trip: weekly time and money, and the list. Phone only. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(prefs: Prefs, onBack: () -> Unit) {
    var enabled by remember { mutableStateOf(prefs.historyEnabled) }
    var records by remember { mutableStateOf(prefs.history) }
    var confirm by remember { mutableStateOf<String?>(null) } // "off" or "delete"
    val today = LocalDate.now()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Trip history", fontWeight = FontWeight.Bold)
                        Text("Kept only on this phone", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            item {
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(14.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text("Keep trip history", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                            Text(
                                "Saves each finished live trip on this phone. Turning it off deletes it.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Switch(checked = enabled, onCheckedChange = { on ->
                            if (on) { enabled = true; prefs.historyEnabled = true } else confirm = "off"
                        })
                    }
                }
            }
            if (!enabled) {
                item { Text("Trip history is off. Nothing is being saved.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                return@LazyColumn
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    TotalsCard("This week", History.week(records, today, 0), Modifier.weight(1f))
                    TotalsCard("Last week", History.week(records, today, 1), Modifier.weight(1f))
                }
            }
            item { TotalsCard("Last 4 weeks", History.lastWeeks(records, today, 4), Modifier.fillMaxWidth()) }
            if (records.isEmpty()) {
                item {
                    Text(
                        "No trips yet. Finish a live trip (☰ → Trip planner → Start live trip) and it shows up here.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                item { Text("Recent trips", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp)) }
                items(records.take(100)) { r ->
                    val fmt = DateTimeFormatter.ofPattern("EEE MMM d · h:mm a")
                    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(12.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text("${r.from} → ${r.to}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(
                                    Instant.ofEpochMilli(r.at).atZone(ZoneId.systemDefault()).format(fmt) +
                                        " · ${r.minutes} min" + (if (r.transfers > 0) " · ${r.transfers} transfer${if (r.transfers > 1) "s" else ""}" else "") +
                                        (if (r.people > 1) " · ${r.people} people" else ""),
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(if (r.fare >= 0) "$%.2f".format(r.fare) else "–", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                Text(
                                    if (r.fare < 0) "fare unknown" else if (r.peak) "peak" else "off-peak",
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                item {
                    OutlinedButton(onClick = { confirm = "delete" }, modifier = Modifier.padding(top = 8.dp)) { Text("Delete history") }
                }
            }
        }
    }

    confirm?.let { what ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(if (what == "off") "Turn off trip history?" else "Delete trip history?") },
            text = { Text(if (what == "off") "This stops saving trips and deletes the ${records.size} saved." else "This deletes all ${records.size} saved trips from this phone.") },
            confirmButton = {
                TextButton(onClick = {
                    if (what == "off") { prefs.historyEnabled = false; enabled = false } else prefs.clearHistory()
                    records = emptyList(); confirm = null
                }) { Text(if (what == "off") "Turn off" else "Delete") }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun TotalsCard(title: String, t: HistoryTotals, modifier: Modifier) {
    ElevatedCard(shape = RoundedCornerShape(20.dp), modifier = modifier) {
        Column(Modifier.padding(14.dp)) {
            Text(title.uppercase(), style = MaterialTheme.typography.labelSmall, letterSpacing = 1.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("$%.2f".format(t.money), fontSize = 26.sp, fontWeight = FontWeight.Bold)
            val h = t.minutes / 60; val m = t.minutes % 60
            Text(
                "${t.trips} trip${if (t.trips == 1) "" else "s"} · " + (if (h > 0) "${h} h ${m} min" else "$m min") + " riding",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
