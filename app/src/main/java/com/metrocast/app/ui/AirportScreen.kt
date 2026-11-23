package com.metrocast.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metrocast.app.data.Airport
import com.metrocast.app.data.Flights
import com.metrocast.app.data.FlightStatus
import com.metrocast.app.data.Repository
import com.metrocast.app.data.StationDirectory
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Menu → Airports: Reagan National or Dulles. Your flight's time (typed in, or looked up live by flight
 * number with a tracking key) → when to leave your Metro station.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AirportScreen(
    repo: Repository,
    onBack: () -> Unit,
    onPlan: (from: String, to: String) -> Unit,
    onOpenSettings: () -> Unit = {},
    clock: () -> LocalDateTime = { LocalDateTime.now(Flights.ZONE) },
) {
    val p = repo.prefs
    var airport by remember { mutableStateOf(Airport.DCA) }
    var start by remember { mutableStateOf(p.planFrom ?: "A01") }
    var picking by remember { mutableStateOf(false) }
    var international by remember { mutableStateOf(false) }
    var early by remember { mutableIntStateOf(p.airportEarlyMin) }
    var departs by remember { mutableStateOf<LocalDateTime?>(null) }
    // Live flight (kept on screen only; nothing is saved).
    var airline by remember { mutableStateOf("") }
    var number by remember { mutableStateOf("") }
    var tracking by remember { mutableStateOf<String?>(null) }
    var flight by remember { mutableStateOf<FlightStatus?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var checkedAt by remember { mutableStateOf<LocalDateTime?>(null) }
    var refreshTick by remember { mutableIntStateOf(0) }
    var rides by remember { mutableIntStateOf(0) }
    val provider = p.flightProvider
    val canTrack = com.metrocast.app.data.FlightProvider.available.isNotEmpty()

    LaunchedEffect(start) { repo.prepareRailRides(start, Airport.entries.map { it.metroCode }); rides++ }
    // Following a flight: checked now, then every few minutes while you're here.
    LaunchedEffect(tracking, refreshTick) {
        val d = tracking ?: return@LaunchedEffect
        while (true) {
            val (f, err) = repo.lookupFlight(d, clock())
            checkedAt = clock()
            if (f != null) {
                flight = f; message = null
                Flights.airportOf(f.from)?.let { airport = it }   // fills in which airport (and Metro station)
            } else if (flight == null) message = err
            delay((provider?.refreshMinutes ?: 15) * 60_000L)
        }
    }

    val live = flight?.takeIf { Flights.airportOf(it.from) != null }
    val time = live?.scheduled ?: departs
    val plan = remember(time, airport, start, early, international, rides) {
        time?.let { Flights.plan(airport, it, start, early + if (international) 60 else 0) { f, t -> repo.railMetroMinutes(f, t) } }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Airports", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("When to leave for your flight, by Metro.", style = MaterialTheme.typography.bodyMedium)
            if (!rememberOnline()) OfflineBanner("Offline — flight status and Metro times update when you're back online.")
            // 1. Airport.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Airport.entries.forEach { a ->
                    FilterChip(selected = airport == a, onClick = { airport = a }, label = { Text("${a.title} (${a.code})") })
                }
            }
            // 2. Your station.
            Surface(onClick = { picking = true }, shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Text("YOUR METRO STATION", style = MaterialTheme.typography.labelSmall, letterSpacing = 1.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(StationDirectory.nameOf(start) ?: start, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                }
            }
            // 3. The flight: live by number, or its time typed in.
            if (canTrack) {
                // No key yet: the whole card opens Settings → Flight tracking.
                ElevatedCard(onClick = { if (provider == null) onOpenSettings() }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("✈️ Follow your flight live" + if (provider == null) "  ›" else "", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        if (provider == null) {
                            Text("Add a flight tracking key in Settings to look up a flight by number and follow delays and gate changes.", style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = onOpenSettings) { Text("Set up flight tracking") }
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(value = airline, onValueChange = { airline = it.take(30) }, singleLine = true, label = { Text("Airline") }, placeholder = { Text("American") }, modifier = Modifier.weight(1.2f))
                                OutlinedTextField(value = number, onValueChange = { number = it.take(8) }, singleLine = true, label = { Text("Flight #") }, placeholder = { Text("1234") }, modifier = Modifier.weight(1f))
                            }
                            // Matching airlines, to tap.
                            val matches = if (airline.length >= 2 && Flights.AIRLINES.none { it.first.equals(airline, true) }) Flights.AIRLINES.filter { it.first.startsWith(airline, true) || it.second.equals(airline, true) }.take(4) else emptyList()
                            if (matches.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                matches.forEach { (n, c) -> FilterChip(selected = false, onClick = { airline = n }, label = { Text("$n ($c)") }) }
                            }
                            val d = Flights.designator(airline, number)
                            Button(onClick = { flight = null; message = null; tracking = d; refreshTick++ }, enabled = d != null) {
                                Text(if (d != null) "Find ${Flights.pretty(d)}" else "Find flight")
                            }
                            Text("Uses ${provider.title}. ${provider.about} Only the flight number is sent; nothing is saved.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold) }
                        flight?.let { f -> FlightCard(f, checkedAt, onRefresh = { refreshTick++ }) }
                    }
                }
            }
            if (live == null) {
                FlightTimeEntry(
                    label = if (canTrack && provider != null) "OR TYPE YOUR FLIGHT TIME" else "YOUR FLIGHT LEAVES",
                    today = clock().toLocalDate(),
                    now = clock,
                    onChange = { departs = it },
                )
            }
            // 4. How early.
            Column {
                Text("BE AT THE AIRPORT BEFORE YOUR FLIGHT", style = MaterialTheme.typography.labelSmall, letterSpacing = 1.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(60, 90, 120, 150, 180).forEach { m ->
                        FilterChip(selected = early == m, onClick = { early = m; p.airportEarlyMin = m }, label = { Text(if (m % 60 == 0) "${m / 60} hr" else "${m / 60}½ hr") })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("International flight (+1 hour)", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Switch(checked = international, onCheckedChange = { international = it })
                }
            }
            // 5. The answer.
            if (plan != null) PlanCard(plan, start, live, onPlan)
            else if (time == null) Text("Set your flight time (or find your flight) to see when to leave.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("🚶 ${airport.note}", style = MaterialTheme.typography.bodySmall)
        }
    }
    if (picking) StationPickerDialog(title = "Your Metro station", repo = repo, selected = start, onPick = { start = it.code; picking = false }, onDismiss = { picking = false })
}

@Composable
private fun FlightCard(f: FlightStatus, checkedAt: LocalDateTime?, onRefresh: () -> Unit) {
    val (bg, fg) = when {
        f.canceled -> Color(0xFF9A1C16) to Color.White
        f.delayMin >= 15 -> Color(0xFF7A4A00) to Color.White
        else -> Color(0xFF175C2B) to Color.White
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("${f.flight}${if (f.airline.isNotBlank()) " · ${f.airline}" else ""}" + if (f.to.isNotBlank()) " → ${f.to}" else "", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Surface(shape = RoundedCornerShape(12.dp), color = bg, contentColor = fg) {
            Text(f.statusText, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
        }
        val from = Flights.airportOf(f.from)
        if (from == null) Text("⚠️ This flight leaves from ${f.from.ifBlank { "another airport" }}, not Reagan or Dulles. Check the number?", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        Text(
            "Departs ${from?.title ?: f.from} " + f.scheduled.format(DateTimeFormatter.ofPattern("EEE ")) + Format.time(f.scheduled.toLocalTime()) +
                (f.estimated?.takeIf { f.delayMin != 0 }?.let { " · now ${Format.time(it.toLocalTime())}" } ?: ""),
            style = MaterialTheme.typography.bodyMedium,
        )
        val tg = listOfNotNull(f.terminal?.let { "Terminal $it" }, f.gate?.let { "Gate $it" }).joinToString(" · ")
        if (tg.isNotBlank()) Text(tg, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(checkedAt?.let { "Checked ${Format.time(it.toLocalTime())}" } ?: "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            TextButton(onClick = onRefresh) { Text("Check now") }
        }
    }
}

@Composable
private fun PlanCard(plan: com.metrocast.app.data.FlightPlan, start: String, live: FlightStatus?, onPlan: (String, String) -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("LEAVE ${(StationDirectory.nameOf(start) ?: "YOUR STATION").uppercase()} BY", style = MaterialTheme.typography.labelLarge, letterSpacing = 1.sp)
            Text(Format.time(plan.leaveBy.toLocalTime()), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
            if (plan.leaveBy.toLocalDate() != plan.departs.toLocalDate() || plan.leaveBy.toLocalDate() != LocalDateTime.now(Flights.ZONE).toLocalDate()) {
                Text(plan.leaveBy.format(DateTimeFormatter.ofPattern("EEEE, MMM d")), style = MaterialTheme.typography.bodyMedium)
            }
            Text("for a ${Format.time(plan.departs.toLocalTime())} flight from ${plan.airport.title}", style = MaterialTheme.typography.bodyMedium)
            val parts = buildList {
                if (plan.metroMin > 0) add("${plan.metroMin} min Metro" + if (plan.estimated) " (estimate)" else "")
                if (plan.waitMin > 0) add("${plan.waitMin} wait")
                add("${plan.walkMin} walk to the terminal")
                add("${plan.earlyMin / 60.0}".removeSuffix(".0") + " hr early")
            }
            Text(parts.joinToString(" + ") + " = ${plan.totalMin} min", style = MaterialTheme.typography.bodySmall)
            live?.takeIf { it.delayMin >= 5 && !it.canceled }?.let {
                Text("Your flight is running ${it.delayMin} min late. Leave-by stays on the original time: delays can shrink, and check-in and bag cut-offs don't move.", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
            }
            if (plan.metroMin > 0) FilledTonalButton(onClick = { onPlan(start, plan.airport.metroCode) }) { Text("Plan the Metro ride to ${plan.airport.title}") }
        }
    }
}

/** The flight's day and time, typed right here: day buttons, the time, and AM / PM (unless on a 24-hour clock). */
@Composable
private fun FlightTimeEntry(label: String, today: java.time.LocalDate, now: () -> LocalDateTime, onChange: (LocalDateTime?) -> Unit) {
    val h24 = Format.use24h
    var day by remember { mutableStateOf(today) }
    var text by remember { mutableStateOf("") }
    // AM/PM: guessed from the hour you type (the next time it comes round) until you tap one.
    var pmPicked by remember { mutableStateOf<Boolean?>(null) }
    val hour12 = Flights.typedHour(text)
    val pm = pmPicked ?: hour12?.takeIf { it in 1..12 }?.let { Flights.guessPm(it, day, now()) } ?: true
    val time = Flights.parseTime(text, if (h24) null else pm)
    LaunchedEffect(day, time) { onChange(time?.let { day.atTime(it) }) }
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, letterSpacing = 1.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (0L..6L).forEach { d ->
                    val date = today.plusDays(d)
                    FilterChip(selected = day == date, onClick = { day = date }, label = {
                        Text(when (d) { 0L -> "Today"; 1L -> "Tomorrow"; else -> date.format(DateTimeFormatter.ofPattern("EEE d")) })
                    })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { v -> text = v.filter { it.isDigit() || it == ':' || it == '.' }.take(5) },
                    singleLine = true,
                    label = { Text("Time") },
                    placeholder = { Text(if (h24) "13:05" else "1:05") },
                    isError = text.isNotBlank() && time == null,
                    textStyle = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
                if (!h24) {
                    FilterChip(selected = !pm, onClick = { pmPicked = false }, label = { Text("AM", fontWeight = FontWeight.Bold) })
                    FilterChip(selected = pm, onClick = { pmPicked = true }, label = { Text("PM", fontWeight = FontWeight.Bold) })
                }
            }
            Text(
                time?.let { "Flight leaves " + day.format(DateTimeFormatter.ofPattern("EEE MMM d")) + " at " + Format.time(it) }
                    ?: if (h24) "Type the time, like 13:05" else "Type the time, like 1:05, then pick AM or PM",
                style = MaterialTheme.typography.bodyMedium,
                color = if (time == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}
