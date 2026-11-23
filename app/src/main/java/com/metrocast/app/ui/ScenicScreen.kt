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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metrocast.app.data.Scenic
import com.metrocast.app.data.StationDirectory

/** Menu → Scenic rides & stations: where Metro has a view, and stations worth seeing. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScenicScreen(onBack: () -> Unit, onPlan: (from: String?, to: String?) -> Unit = { _, _ -> }) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Scenic rides & stations", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            item { Header("🌅 RIDES WITH A VIEW") }
            items(Scenic.rides, key = { "r-" + it.title }) { r ->
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            r.lines.forEach { LinePill(it, size = 24); Spacer(Modifier.width(4.dp)) }
                            Spacer(Modifier.width(4.dp))
                            Text(r.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }
                        Text("${name(r.from)} ↔ ${name(r.to)}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(r.what, style = MaterialTheme.typography.bodyMedium)
                        Text("💡 ${r.tip}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                        FilledTonalButton(onClick = { onPlan(r.from, r.to) }) { Text("Plan this ride") }
                    }
                }
            }
            item { Header("🚉 STATIONS WORTH A LOOK") }
            items(Scenic.spots, key = { "s-" + it.code + it.title }) { s ->
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StationDirectory.find(s.code)?.lines?.forEach { LinePill(it, size = 20); Spacer(Modifier.width(3.dp)) }
                            Spacer(Modifier.width(4.dp))
                            Text(s.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }
                        Text(s.what, style = MaterialTheme.typography.bodyMedium)
                        s.tip?.let { Text("💡 $it", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold) }
                        FilledTonalButton(onClick = { onPlan(null, s.code) }) { Text("Plan a trip here") }
                    }
                }
            }
            item {
                Text("MetroCast's picks. Escalator lengths and depths are WMATA's published figures (approximate).",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Header(t: String) = Text(t, style = MaterialTheme.typography.labelLarge, letterSpacing = 1.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))

private fun name(code: String) = StationDirectory.nameOf(code) ?: code
