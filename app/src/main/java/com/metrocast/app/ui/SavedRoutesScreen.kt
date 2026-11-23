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
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metrocast.app.data.ArrivalKind
import com.metrocast.app.data.PlanStatus
import com.metrocast.app.data.Verdict

/** Routes starred in the trip planner, each with live next train, travel time and transfer check. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavedRoutesScreen(
    routes: List<PlanStatus>,
    onOpen: (PlanStatus) -> Unit,
    onRemove: (PlanStatus) -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Saved routes", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            if (routes.isEmpty()) {
                item {
                    Text(
                        "No saved routes yet. Open ☰ → Trip planner, pick two stations and tap ☆ to save the route here.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
            items(routes, key = { "${it.from?.code}-${it.to?.code}" }) { r -> SavedRouteCard(r, { onOpen(r) }, { onRemove(r) }) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SavedRouteCard(r: PlanStatus, onOpen: () -> Unit, onRemove: () -> Unit) {
    ElevatedCard(onClick = onOpen, shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "${r.from?.name ?: "?"} → ${r.to?.name ?: "?"}",
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                        r.legs.forEachIndexed { i, leg ->
                            if (i > 0) Text("  ›  ", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            LinePill(leg.leg.options.first().line, size = 22)
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            listOfNotNull(
                                r.ride?.minutes?.let { "$it min" },
                                when (r.transfers) { 0 -> "direct"; 1 -> "1 transfer"; else -> "${r.transfers} transfers" },
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                IconButton(onClick = onRemove) { Text("★", fontSize = 24.sp, color = Color(0xFFF2B400)) }
            }
            // Next train on the first leg.
            val next = r.legs.firstOrNull()?.arrivals?.firstOrNull()
            Text(
                when {
                    r.message != null -> r.message
                    next == null -> "No train predicted right now"
                    next.arrival.kind == ArrivalKind.MINUTES && next.arrival.minutes > 0 ->
                        "Next train in ${next.arrival.minutes} min · to ${next.arrival.shortDestination}"
                    else -> "Next train: ${next.arrival.label} · to ${next.arrival.shortDestination}"
                },
                style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 10.dp),
            )
            AlertTag(r.alerts)
            // First transfer's live check.
            r.connections.firstOrNull()?.connections?.firstOrNull()?.let { c ->
                val (label, color) = when (c.verdict) {
                    Verdict.EASY -> "easy connection" to Color(0xFF2E7D32)
                    Verdict.WALK_FAST -> "walk quickly" to Color(0xFFE08A00)
                    Verdict.RUN -> "tight — run" to Color(0xFFD32F2F)
                    Verdict.MISS -> "no connection predicted yet" to Color(0xFF757575)
                }
                Surface(shape = RoundedCornerShape(50), color = color.copy(alpha = 0.15f), modifier = Modifier.padding(top = 6.dp)) {
                    Text(
                        "Transfer at ${r.connections.first().stationName}: $label",
                        color = color, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
            Text("Tap for step-by-step directions ›", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
        }
    }
}
