package com.metrocast.app.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.metrocast.app.data.Repository
import com.metrocast.app.data.StationInfo
import com.metrocast.app.data.Trip
import com.metrocast.app.ui.LinePill
import com.metrocast.app.ui.MetroCastTheme
import com.metrocast.app.ui.StationPickerDialog
import kotlinx.coroutines.launch

/**
 * Opens when a MetroCast widget is added (and from the widget's "Reconfigure"): choose which trips
 * this widget shows, in which order, or add any other stations just for it.
 */
class WidgetConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val widgetId = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        // Backing out cancels adding the widget.
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        val repo = Repository(this)
        val style = runCatching { AppWidgetManager.getInstance(this).getAppWidgetInfo(widgetId)?.loadLabel(packageManager) }.getOrNull()
        setContent {
            MetroCastTheme {
                WidgetConfigScreen(repo, widgetId, style ?: "MetroCast widget", onDone = {
                    lifecycleScope.launch {
                        runCatching { MetroWidget.notifyChanged(applicationContext) }
                        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
                        finish()
                    }
                }, onCancel = { finish() })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WidgetConfigScreen(repo: Repository, widgetId: Int, style: String, onDone: () -> Unit, onCancel: () -> Unit) {
    var trips by remember { mutableStateOf(repo.prefs.trips) }
    // Chosen trip ids, in the order the widget shows them.
    val chosen = remember {
        mutableStateListOf<String>().apply {
            addAll(repo.prefs.widgetTrips(widgetId) ?: trips.filter { it.showOnWidget && it.onHome }.map { it.id })
        }
    }
    var picking by remember { mutableStateOf<String?>(null) }   // "from" / "to"
    var newFrom by remember { mutableStateOf<StationInfo?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    fun save() {
        // Widget-only trips that were un-ticked aren't needed any more.
        val keep = trips.filter { it.onHome || it.id in chosen }
        if (keep.size != trips.size) repo.prefs.trips = keep
        repo.prefs.setWidgetTrips(widgetId, chosen.toList())
        onDone()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Choose your stops", fontWeight = FontWeight.Bold)
                        Text(style, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                // Save sits up here too, always reachable whatever the phone's navigation bar covers.
                actions = {
                    TextButton(onClick = onCancel) { Text("Cancel") }
                    TextButton(onClick = { save() }, enabled = chosen.isNotEmpty()) { Text("Save", fontWeight = FontWeight.Bold) }
                },
            )
        },
        bottomBar = {
            Button(
                onClick = { save() },
                enabled = chosen.isNotEmpty(),
                // Kept above the phone's back/home buttons or gesture bar.
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
            ) { Text(if (chosen.isEmpty()) "Pick at least one trip" else "Save — show ${chosen.size} on this widget") }
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            item {
                Text(
                    "Tick the trips this widget shows. The order you tick them is the order on the widget; " +
                        "it shows as many as fit its size. Each widget can show different stops.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            itemsIndexed(trips, key = { _, t -> t.id }) { _, t ->
                val n = chosen.indexOf(t.id)
                OutlinedCard(
                    onClick = { if (n >= 0) chosen.remove(t.id) else chosen.add(t.id) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = n >= 0, onCheckedChange = { if (it) chosen.add(t.id) else chosen.remove(t.id) })
                        LinePill(t.lineCode, size = 26)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                when (t.label) { Trip.GOING -> "➡️ Going there"; Trip.BACK -> "⬅️ Coming back"; else -> t.label } +
                                    if (!t.onHome) "  · widget only" else "",
                                style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
                            )
                            Text("${t.stationName} → ${t.destName}", style = MaterialTheme.typography.bodyMedium)
                            t.via?.let { Text("🔁 $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                        if (n >= 0) Text("#${n + 1}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            item {
                OutlinedButton(onClick = { error = null; newFrom = null; picking = "from" }, modifier = Modifier.fillMaxWidth()) {
                    Text("+ Add other stops for this widget")
                }
                Text(
                    "Any station to any station. Added stops show only on widgets, not the home screen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }

    when (picking) {
        "from" -> StationPickerDialog(
            title = "Widget: start at", repo = repo, selected = null,
            onPick = { newFrom = it; picking = "to" }, onDismiss = { picking = null },
        )
        "to" -> StationPickerDialog(
            title = "Widget: going to", repo = repo, selected = null,
            onPick = { to ->
                picking = null
                val from = newFrom ?: return@StationPickerDialog
                val t = repo.buildTrip(from.code, to.code, "To ${to.name}")?.copy(onHome = false)
                if (t == null) { error = "Pick two different stations"; return@StationPickerDialog }
                trips = trips + t
                repo.prefs.trips = trips
                chosen.add(t.id)
            },
            onDismiss = { picking = null },
        )
    }
}
