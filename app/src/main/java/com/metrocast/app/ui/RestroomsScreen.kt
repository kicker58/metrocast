package com.metrocast.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.metrocast.app.data.Restrooms
import com.metrocast.app.data.StationDirectory

/** Menu → Restrooms: every Metro station with a customer restroom. Tap one to plan a trip there. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RestroomsScreen(onBack: () -> Unit, onPlan: (from: String?, to: String?) -> Unit = { _, _ -> }) {
    var filter by rememberSaveable { mutableStateOf("all") }   // all / open / ask
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    val list = Restrooms.all.filter {
        when (filter) {
            "open" -> Restrooms.isWalkIn(it.restroom)
            "ask" -> it.restroom.askManager
            else -> true
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Restrooms", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            item(key = "note") {
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("🚻 ${Restrooms.all.size} stations have restrooms", fontWeight = FontWeight.Bold)
                        Text(
                            "${Restrooms.all.count { Restrooms.isWalkIn(it.restroom) }} are open to walk in — all in Virginia, mostly " +
                                "Silver Line. At the others, ask the station manager at the kiosk by the faregates. " +
                                "Restrooms can close for cleaning or repairs.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text("Source: ${Restrooms.SOURCE}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item(key = "filters") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(filter == "all", { filter = "all" }, { Text("All") })
                    FilterChip(filter == "open", { filter = "open" }, { Text("Walk in") })
                    FilterChip(filter == "ask", { filter = "ask" }, { Text("Ask manager") })
                }
            }
            for (region in Restrooms.REGIONS) {
                val rows = list.filter { it.region == region }.sortedWith(compareBy({ !Restrooms.isWalkIn(it.restroom) }, { it.name }))
                if (rows.isEmpty()) continue
                item(key = "h-$region") { SectionHeader("$region · ${rows.size}") }
                items(rows, key = { it.code }) { l ->
                    val open = expanded == l.code
                    val lines = StationDirectory.find(l.code)?.lines.orEmpty()
                    ElevatedCard(Modifier.fillMaxWidth().clickable { expanded = if (open) null else l.code }) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                lines.forEach { LinePill(it, size = 22) }
                                Text(StationDirectory.nameOf(l.code) ?: l.name, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            }
                            RestroomNote(l.code)
                            if (open) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FilledTonalButton(onClick = { onPlan(null, l.code) }) { Text("Plan a trip here") }
                                    OutlinedButton(onClick = { onPlan(l.code, null) }) { Text("Start here") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
