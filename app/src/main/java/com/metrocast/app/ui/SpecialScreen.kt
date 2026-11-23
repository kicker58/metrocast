package com.metrocast.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.metrocast.app.data.Repository
import com.metrocast.app.data.SpecialNews
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Menu → Special trains & events: WMATA's announcements of special/wrapped trains and event service. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpecialScreen(repo: Repository, onBack: () -> Unit) {
    var items by remember { mutableStateOf<List<SpecialNews.Item>?>(null) }
    var checking by remember { mutableStateOf(false) }
    var notify by remember { mutableStateOf(repo.prefs.specialNewsAlerts) }
    val scope = rememberCoroutineScope()
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    val context = androidx.compose.ui.platform.LocalContext.current
    fun load(force: Boolean) {
        checking = true
        scope.launch { items = withContext(Dispatchers.IO) { runCatching { repo.loadSpecialNews(force) }.getOrDefault(emptyList()) }; checking = false }
    }
    LaunchedEffect(Unit) { load(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Special trains & events", fontWeight = FontWeight.Bold) },
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
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("🎄 Holiday and themed trains, and extra service for big events — from WMATA's announcements.", style = MaterialTheme.typography.bodyMedium)
                        Text("WMATA's live data doesn't say which train is wrapped, so these can't be shown on the map — the announcement has the dates and lines.",
                            style = MaterialTheme.typography.bodySmall)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Notify me of new ones", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                Text("Checked about once a month", style = MaterialTheme.typography.bodySmall)
                            }
                            Switch(checked = notify, onCheckedChange = {
                                notify = it; repo.prefs.specialNewsAlerts = it
                                runCatching { com.metrocast.app.notify.Background.sync(context) }
                            })
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedButton(onClick = { load(true) }, enabled = !checking) { Text("Check now") }
                            Spacer(Modifier.width(10.dp))
                            if (checking) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            else repo.specialNewsCheckedAt().takeIf { it > 0 }?.let {
                                Text("Last checked ${java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).toLocalDate()}", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
            val list = items
            if (list != null && list.isEmpty() && !checking) item(key = "none") {
                Text("Nothing special announced right now. Holiday trains are usually announced in late November or December.", style = MaterialTheme.typography.bodyMedium)
            }
            items(list.orEmpty(), key = { it.id }) { n ->
                ElevatedCard(onClick = { runCatching { uri.openUri(n.url) } }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${n.kind.emoji} ${n.kind.label.uppercase()}" + (n.date?.let { " · ${it.month.name.lowercase().replaceFirstChar { c -> c.uppercase() }.take(3)} ${it.dayOfMonth}" } ?: ""),
                            style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            n.lines.forEach { LinePill(it, size = 18); Spacer(Modifier.width(4.dp)) }
                            Text(n.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                        }
                        Text("Details on wmata.com ›", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}
