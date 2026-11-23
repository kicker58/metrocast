package com.metrocast.app.ui

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metrocast.app.data.Incident
import com.metrocast.app.data.MetroLine
import com.metrocast.app.data.Repository
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** What WMATA is reporting: delays, single tracking, lines ending early, and other train alerts. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IssuesScreen(repo: Repository, onBack: () -> Unit) {
    var incidents by remember { mutableStateOf<List<Incident>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var tick by remember { mutableIntStateOf(0) }

    LaunchedEffect(tick) {
        loading = true
        runCatching { repo.loadIssues() }
            .onSuccess { incidents = it; error = null }
            .onFailure { error = "Couldn't reach WMATA — pull down to try again" }
        loading = false
    }

    val myLines = remember(repo) { repo.prefs.trips.map { it.lineCode }.toSet() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Train issues", fontWeight = FontWeight.Bold)
                        Text("From WMATA", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        PullToRefreshBox(isRefreshing = loading && incidents != null, onRefresh = { tick++ }, modifier = Modifier.padding(padding).fillMaxSize()) {
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                error?.let { e -> item { Text(e, color = MaterialTheme.colorScheme.error) } }
                item { Header("Service alerts") }
                val list = incidents
                when {
                    list == null -> item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(12.dp))
                            Text("Checking with WMATA…")
                        }
                    }
                    list.isEmpty() -> item {
                        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("✅", fontSize = 22.sp)
                                Spacer(Modifier.width(12.dp))
                                Text("No train issues reported right now. Good service on all lines.", style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                    // Alerts on your lines first.
                    else -> items(list.sortedByDescending { i -> i.lines.any { it in myLines } }) { IncidentCard(it, myLines) }
                }

            }
        }
    }
}

@Composable
private fun Header(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
}

@Composable
fun IncidentCard(i: Incident, myLines: Set<String>) {
    val mine = i.lines.any { it in myLines }
    ElevatedCard(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (i.type.equals("Delay", true)) "⏱" else "⚠️", fontSize = 18.sp)
                Spacer(Modifier.width(8.dp))
                i.lines.forEach { l ->
                    LinePill(l, size = 24)
                    Spacer(Modifier.width(4.dp))
                }
                Spacer(Modifier.weight(1f))
                if (mine) {
                    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.errorContainer) {
                        Text(
                            "Your line",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(i.description, style = MaterialTheme.typography.bodyMedium)
            formatWhen(i.updated)?.let {
                Text(
                    "${i.type} · updated $it",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

/** "2026-11-21T06:25:46" → "Sat 6:25 AM". */
private fun formatWhen(iso: String?): String? = iso?.takeIf { it.isNotBlank() }?.let {
    runCatching { LocalDateTime.parse(it).format(DateTimeFormatter.ofPattern("EEE h:mm a")) }.getOrNull()
}

/** Home-screen banner when WMATA reports an issue on one of your lines. */
@Composable
fun AlertBanner(incidents: List<Incident>, myLines: Set<String>, onOpen: () -> Unit) {
    val mine = incidents.filter { i -> i.lines.any { it in myLines } }
    if (mine.isEmpty()) return
    val lines = mine.flatMap { it.lines }.filter { it in myLines }.distinct()
    Surface(
        onClick = onOpen,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("⚠️", fontSize = 20.sp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "Service alert: " + lines.joinToString(", ") { MetroLine.fromCode(it).displayName } + " Line",
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    mine.first().description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
            Text("›", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onErrorContainer)
        }
    }
}
