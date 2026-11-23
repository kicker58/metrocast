package com.metrocast.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.metrocast.app.data.Branches
import com.metrocast.app.data.MetroLine
import com.metrocast.app.data.Repository
import com.metrocast.app.data.Station
import com.metrocast.app.data.Trip
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TripEditorDialog(initial: Trip?, repo: Repository, onDismiss: () -> Unit, onSave: (Trip) -> Unit) {
    var label by remember { mutableStateOf(initial?.label ?: "") }
    var lineCode by remember { mutableStateOf(initial?.lineCode ?: "SV") }
    var stationCode by remember { mutableStateOf(initial?.stationCode) }
    var towardCode by remember { mutableStateOf(initial?.towardCode) }
    var showOnWidget by remember { mutableStateOf(initial?.showOnWidget ?: true) }

    var stations by remember { mutableStateOf<List<Station>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var pickerOpen by remember { mutableStateOf(false) }
    var destPickerOpen by remember { mutableStateOf(false) }
    var firstLoad by remember { mutableStateOf(true) }

    LaunchedEffect(lineCode) {
        loading = true
        loadError = null
        stations = emptyList()
        try {
            stations = repo.loadLineStations(lineCode)
        } catch (e: Exception) {
            loadError = e.message ?: "Couldn't load stations"
        }
        loading = false
        // Keep the saved selection on first open; reset it when the line changes.
        if (!firstLoad) {
            if (stations.none { it.code == stationCode }) stationCode = null
            if (Branches.destinations(lineCode, stations).none { it.code == towardCode }) towardCode = null
        }
        firstLoad = false
    }

    val station = stations.firstOrNull { it.code == stationCode }
    // Any stop on the line can be the destination (plus the New Carrollton branch on Silver).
    val destinations = Branches.destinations(lineCode, stations)
    val toward = destinations.firstOrNull { it.code == towardCode }
    val canSave = station != null && toward != null && station.code != toward.code

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(if (initial == null) "Add a station" else "Edit trip", fontWeight = FontWeight.Bold) },
                    navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close") } },
                    actions = {
                        TextButton(
                            enabled = canSave,
                            onClick = {
                                val s = station ?: return@TextButton
                                val t = toward ?: return@TextButton
                                onSave(
                                    Trip(
                                        id = initial?.id ?: UUID.randomUUID().toString(),
                                        label = label.trim().ifBlank { "To ${t.name}" },
                                        lineCode = lineCode,
                                        stationCode = s.code,
                                        stationName = s.name,
                                        towardCode = t.code,
                                        towardName = Branches.terminalName(lineCode, t),
                                        showOnWidget = showOnWidget,
                                    )
                                )
                            },
                        ) { Text("Save", fontWeight = FontWeight.Bold) }
                    },
                )
            },
        ) { padding ->
            Column(
                Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                StepTitle("1. Line")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MetroLine.entries.forEach { line ->
                        FilterChip(
                            selected = line.code == lineCode,
                            onClick = { lineCode = line.code },
                            label = { Text(line.displayName) },
                            leadingIcon = {
                                Box(Modifier.size(12.dp).clip(CircleShape).background(Color(line.color)))
                            },
                        )
                    }
                }

                StepTitle("2. Station you board at")
                OutlinedCard(
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().clickable(enabled = stations.isNotEmpty()) { pickerOpen = true },
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        when {
                            loading -> {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(12.dp))
                                Text("Loading stations…")
                            }
                            loadError != null -> Text(loadError ?: "", color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                            else -> {
                                Text(
                                    station?.name ?: "Choose a station",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = if (station == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f),
                                )
                                Icon(Icons.Default.ArrowDropDown, null)
                            }
                        }
                    }
                }

                StepTitle("3. Where you're going")
                OutlinedCard(
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().clickable(enabled = destinations.isNotEmpty()) { destPickerOpen = true },
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            toward?.let { Branches.terminalName(lineCode, it) } ?: "Choose any stop on the line",
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (toward == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(Icons.Default.ArrowDropDown, null)
                    }
                }
                if (destinations.size >= 2) {
                    Text("Quick pick", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOfNotNull(stations.firstOrNull(), stations.lastOrNull()).distinctBy { it.code }.forEach { term ->
                            FilterChip(
                                selected = term.code == towardCode,
                                enabled = term.code != stationCode,
                                onClick = { towardCode = term.code },
                                label = { Text("End of line: ${Branches.terminalName(lineCode, term)}") },
                            )
                        }
                    }
                }

                StepTitle("4. Name (optional)")
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    placeholder = { Text(toward?.let { "To ${it.name}" } ?: "e.g. To work") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("Show on widget", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "The widget shows as many trips as fit its size",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = showOnWidget, onCheckedChange = { showOnWidget = it })
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (destPickerOpen) {
        AlertDialog(
            onDismissRequest = { destPickerOpen = false },
            confirmButton = { TextButton(onClick = { destPickerOpen = false }) { Text("Cancel") } },
            title = { Text("Where are you going?") },
            text = {
                LazyColumn(Modifier.heightIn(max = 460.dp)) {
                    items(destinations.filter { it.code != stationCode }, key = { it.code }) { st ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    towardCode = st.code
                                    destPickerOpen = false
                                }
                                .padding(horizontal = 8.dp, vertical = 12.dp),
                        ) {
                            Box(Modifier.size(10.dp).clip(CircleShape).background(Color(MetroLine.fromCode(lineCode).color)))
                            Spacer(Modifier.width(12.dp))
                            Text(st.name, fontWeight = if (st.code == towardCode) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                }
            },
        )
    }

    if (pickerOpen) {
        AlertDialog(
            onDismissRequest = { pickerOpen = false },
            confirmButton = { TextButton(onClick = { pickerOpen = false }) { Text("Cancel") } },
            title = { Text("${MetroLine.fromCode(lineCode).displayName} Line stations") },
            text = {
                LazyColumn(Modifier.heightIn(max = 460.dp)) {
                    items(stations, key = { it.code }) { st ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    stationCode = st.code
                                    if (towardCode == st.code) towardCode = null
                                    pickerOpen = false
                                }
                                .padding(horizontal = 8.dp, vertical = 12.dp),
                        ) {
                            Box(
                                Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(Color(MetroLine.fromCode(lineCode).color)),
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                st.name,
                                fontWeight = if (st.code == stationCode) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                    }
                }
            },
        )
    }
}

@Composable
private fun StepTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 10.dp),
    )
}
