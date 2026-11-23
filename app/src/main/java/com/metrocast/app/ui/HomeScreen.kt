package com.metrocast.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.clickable
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metrocast.app.R
import com.metrocast.app.data.Arrival
import com.metrocast.app.data.DailyPoint
import com.metrocast.app.data.HourlyPoint
import com.metrocast.app.data.MetroLine
import com.metrocast.app.data.TripLocation
import com.metrocast.app.data.TripStatus
import com.metrocast.app.data.Weather
import com.metrocast.app.data.WeatherCodes
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: UiState,
    repo: com.metrocast.app.data.Repository,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenMap: (() -> Unit)? = null,
    onOpenTrip: (TripStatus) -> Unit = {},
    onTripsChanged: () -> Unit = {},
    onOpenPlanner: () -> Unit = {},
    onOpenLive: () -> Unit = {},
    onOpenLineMap: (String) -> Unit = {},
    onOpenIssues: () -> Unit = {},
    onOpenSaved: () -> Unit = {},
    onOpenHistory: () -> Unit = {},
    onOpenRestrooms: () -> Unit = {},
    onOpenTrackWork: () -> Unit = {},
    onOpenScenic: () -> Unit = {},
    onOpenAirports: () -> Unit = {},
    onOpenRail: () -> Unit = {},
    onOpenBikes: () -> Unit = {},
    onOpenPolice: () -> Unit = {},
    onOpenSpecial: () -> Unit = {},
    liveTripActive: Boolean = false,
    onOpenLiveTrip: () -> Unit = {},
) {
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    var menuOpen by remember { mutableStateOf(false) }
    var mapsMenuOpen by remember { mutableStateOf(false) }
    val s = state.snapshot
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("MetroCast", fontWeight = FontWeight.Bold)
                        Text(
                            Format.updated(maxOf(s.trainsUpdatedAt, s.weatherUpdatedAt)),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.Menu, contentDescription = "Menu")
                    }
                    androidx.compose.material3.DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        if (liveTripActive) {
                            MenuRow("🔴", "Live trip", "In progress — tap to return") { menuOpen = false; onOpenLiveTrip() }
                        }
                        // In the order chosen in Settings → Menu; hidden ones left out.
                        for ((item, on) in repo.prefs.menuItems) {
                            if (!on) continue
                            MenuRow(item.emoji, item.title, item.subtitle) {
                                menuOpen = false
                                when (item) {
                                    com.metrocast.app.data.MenuItem.PLANNER -> onOpenPlanner()
                                    com.metrocast.app.data.MenuItem.SAVED -> onOpenSaved()
                                    com.metrocast.app.data.MenuItem.LIVE -> onOpenLive()
                                    com.metrocast.app.data.MenuItem.MAPS -> mapsMenuOpen = true
                                    // No browser on the phone? Do nothing rather than crash.
                                    com.metrocast.app.data.MenuItem.OFFICIAL_MAP -> runCatching { uri.openUri("https://www.wmata.com/schedules/maps/") }
                                    com.metrocast.app.data.MenuItem.HISTORY -> onOpenHistory()
                                    com.metrocast.app.data.MenuItem.TRACKWORK -> onOpenTrackWork()
                                    com.metrocast.app.data.MenuItem.BIKES -> onOpenBikes()
                                    com.metrocast.app.data.MenuItem.POLICE -> onOpenPolice()
                                    com.metrocast.app.data.MenuItem.SPECIAL -> onOpenSpecial()
                                    com.metrocast.app.data.MenuItem.SCENIC -> onOpenScenic()
                                    com.metrocast.app.data.MenuItem.AIRPORTS -> onOpenAirports()
                                    com.metrocast.app.data.MenuItem.RAIL -> onOpenRail()
                                    com.metrocast.app.data.MenuItem.RESTROOMS -> onOpenRestrooms()
                                    com.metrocast.app.data.MenuItem.ISSUES -> onOpenIssues()
                                }
                            }
                        }
                        HorizontalDivider()
                        MenuRow("⚙", "Settings", null) { menuOpen = false; onOpenSettings() }
                    }
                    androidx.compose.material3.DropdownMenu(expanded = mapsMenuOpen, onDismissRequest = { mapsMenuOpen = false }) {
                        MetroLine.entries.forEach { l ->
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text("${l.displayName} Line") },
                                leadingIcon = { LinePill(l.code, size = 24) },
                                onClick = { mapsMenuOpen = false; onOpenLineMap(l.code) },
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = onRefresh,
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            val online = rememberOnline()
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                // No connection: say so, and how old the times on screen are.
                if (!online) item(key = "offline") {
                    val at = state.snapshot.trainsUpdatedAt.takeIf { it > 0 }?.let {
                        Format.time(java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).toLocalTime())
                    }
                    OfflineBanner(if (at != null) "Offline — showing times from $at" else "Offline — times will show when you're back online")
                }
                // A newer version is waiting on your PC: install it from Settings.
                state.update?.let { u ->
                    item(key = "update") {
                        Surface(onClick = onOpenSettings, shape = RoundedCornerShape(20.dp),
                            color = MaterialTheme.colorScheme.tertiaryContainer, contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.fillMaxWidth()) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                                Text("⬆", fontSize = 22.sp, fontWeight = FontWeight.Bold); Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("Update ready on your PC", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                    Text("Version ${u.versionName} · tap to install", style = MaterialTheme.typography.bodySmall)
                                }
                                Text("›", style = MaterialTheme.typography.headlineSmall)
                            }
                        }
                    }
                }
                // Sections in the order (and on/off) chosen in Settings → Home screen layout.
                val weatherOn = repo.prefs.weatherEnabled
                for ((section, on) in repo.prefs.homeSections) {
                    if (!on) continue
                    when (section) {
                        com.metrocast.app.data.HomeSection.WEATHER -> if (weatherOn) item(key = "hero") {
                            if (s.weather != null) WeatherHero(s.weather)
                            else PlaceholderCard(state.weatherError ?: "Loading the forecast…")
                        }
                        // Planned work on your trips in the next week (or happening now).
                        com.metrocast.app.data.HomeSection.TRACKWORK -> TrackWorkBanner(state.trackWork)?.let { b ->
                            item(key = "trackwork") {
                                Surface(onClick = onOpenTrackWork, shape = MaterialTheme.shapes.medium, color = Color(0xFFFFE0B2), modifier = Modifier.fillMaxWidth()) {
                                    Text(b, color = Color(0xFF4E2600), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
                                }
                            }
                        }
                        // During a live trip on board: your train's speed (hide it in Settings → Home screen layout).
                        com.metrocast.app.data.HomeSection.SPEED -> state.live?.takeIf { !it.arrived && repo.prefs.showTrainSpeed }?.let { live ->
                            item(key = "speed") { SpeedCard(live, repo.liveSpeed(), repo.prefs.metric, onOpenLiveTrip) }
                        }
                        com.metrocast.app.data.HomeSection.ELEVATORS -> if (state.elevators.isNotEmpty() || repo.prefs.bikeMode) item(key = "elevators") {
                            ElevatorCard(state.elevators, repo.prefs.bikeMode, onOpenBikes)
                        }
                        com.metrocast.app.data.HomeSection.ALERTS -> item(key = "alerts") {
                            AlertBanner(s.incidents, s.trips.map { it.trip.lineCode }.toSet(), onOpenIssues)
                        }
                        com.metrocast.app.data.HomeSection.TRIPS -> {
                            item(key = "trains-header") {
                                SectionHeader("Trains", action = "Edit", onAction = onOpenSettings)
                            }
                            state.trainError?.let { err ->
                                item(key = "train-error") { ErrorBanner(err) }
                            }
                            if (!s.hasApiKey) {
                                item(key = "no-key") { ApiKeyCard(onOpenSettings) }
                            }
                            items(s.trips.filter { it.trip.onHome }, key = { it.trip.id }) { status ->
                                TripCard(
                                    status, hasKey = s.hasApiKey, stale = s.trainsStale,
                                    onOpen = { onOpenTrip(status) },
                                    // Trips are changed in Settings → Home screen trips.
                                    onChangeDestination = null,
                                    cardOptions = CardOptions(repo.prefs.cardShowRestroom, repo.prefs.cardShowFacts, repo.prefs.cardTrains),
                                )
                            }
                            if (s.trips.none { it.trip.onHome }) {
                                item(key = "no-trips") {
                                    PlaceholderCard("No trips yet — tap Edit to add a station.")
                                }
                            }
                        }
                        com.metrocast.app.data.HomeSection.MAP -> if (onOpenMap != null) {
                            item(key = "line-map") {
                                val line = repo.prefs.homeMapLine
                                LineMapCard(line, state.maps[line], onOpenMap)
                            }
                        }
                        com.metrocast.app.data.HomeSection.RESTROOMS -> item(key = "restrooms") {
                            Surface(
                                onClick = onOpenRestrooms,
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = MaterialTheme.shapes.medium,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    "🚻 ${com.metrocast.app.data.Restrooms.all.size} stations have restrooms — see which ›",
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                )
                            }
                        }
                        com.metrocast.app.data.HomeSection.FORECAST -> if (weatherOn) s.weather?.let { w ->
                            if (w.hourly.isNotEmpty()) {
                                item(key = "hourly-header") { SectionHeader("Next 24 hours") }
                                item(key = "hourly") { HourlyRow(w.hourly) }
                            }
                            if (w.daily.isNotEmpty()) {
                                item(key = "daily-header") { SectionHeader("7-day forecast") }
                                item(key = "daily") { DailyCard(w.daily) }
                            }
                        }
                    }
                }
                item(key = "credit") {
                    Text(
                        if (repo.prefs.weatherEnabled) "Train data: WMATA · Weather: Open-Meteo" else "Train data: WMATA",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- weather

@Composable
private fun WeatherHero(w: Weather) {
    val c = w.current
    val gradient = if (c.isDay) {
        Brush.linearGradient(listOf(Color(0xFF4A8FE7), Color(0xFF2E5BBA)))
    } else {
        Brush.linearGradient(listOf(Color(0xFF1B2A6B), Color(0xFF0B1236)))
    }
    val today = w.daily.firstOrNull()
    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.background(gradient).padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(w.locationName, color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.titleSmall)
                    Text(
                        Format.temp(c.temp),
                        color = Color.White,
                        fontSize = 64.sp,
                        fontWeight = FontWeight.Light,
                        lineHeight = 68.sp,
                    )
                    Text(WeatherCodes.describe(c.code), color = Color.White, style = MaterialTheme.typography.titleMedium)
                }
                Image(
                    painter = painterResource(WeatherCodes.icon(c.code, c.isDay)),
                    contentDescription = null,
                    modifier = Modifier.size(96.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HeroStat("Feels", Format.temp(c.feelsLike), Modifier.weight(1f))
                if (today != null) HeroStat("High / Low", "${Format.temp(today.high)} / ${Format.temp(today.low)}", Modifier.weight(1.4f))
                HeroStat("Wind", "${c.wind.toInt()} ${w.windUnit}", Modifier.weight(1.1f))
                HeroStat("Humidity", "${c.humidity}%", Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun HeroStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.14f))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(label, color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.labelSmall, maxLines = 1)
        Text(value, color = Color.White, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun HourlyRow(hours: List<HourlyPoint>) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(hours) { i, h ->
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = if (i == 0) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp).width(44.dp),
                ) {
                    Text(Format.hour(h.time, i == 0), style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(6.dp))
                    Image(painterResource(WeatherCodes.icon(h.code, h.isDay)), null, Modifier.size(28.dp))
                    Spacer(Modifier.height(6.dp))
                    Text(Format.temp(h.temp), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (h.precipChance >= 10) "${h.precipChance}%" else " ",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF2F80ED),
                    )
                }
            }
        }
    }
}

@Composable
private fun DailyCard(days: List<DailyPoint>) {
    val weekMin = days.minOf { it.low }
    val weekMax = days.maxOf { it.high }
    val span = (weekMax - weekMin).takeIf { it > 0 } ?: 1.0
    val today = LocalDate.now()
    ElevatedCard(shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 6.dp)) {
            days.forEachIndexed { i, d ->
                if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Text(Format.day(d.date, today), Modifier.width(80.dp), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    Image(painterResource(WeatherCodes.icon(d.code, true)), null, Modifier.size(26.dp))
                    Text(
                        if (d.precipChance >= 10) "${d.precipChance}%" else "",
                        Modifier.width(44.dp).padding(start = 6.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(0xFF2F80ED),
                    )
                    Text(Format.temp(d.low), Modifier.width(38.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TempRangeBar(
                        start = ((d.low - weekMin) / span).toFloat(),
                        end = ((d.high - weekMin) / span).toFloat(),
                        modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
                    )
                    Text(Format.temp(d.high), Modifier.width(38.dp), fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun TempRangeBar(start: Float, end: Float, modifier: Modifier = Modifier) {
    BoxWithConstraints(
        modifier
            .height(6.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        val s = start.coerceIn(0f, 1f)
        val e = end.coerceIn(s, 1f)
        Box(
            Modifier
                .offset(x = maxWidth * s)
                .width(maxWidth * (e - s).coerceAtLeast(0.04f))
                .height(6.dp)
                .clip(CircleShape)
                .background(Brush.horizontalGradient(listOf(Color(0xFF4FC3F7), Color(0xFFFFB300)))),
        )
    }
}

// ---------------------------------------------------------------- trains

@Composable
fun LinePill(lineCode: String, size: Int = 40) {
    val line = MetroLine.fromCode(lineCode)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(size.dp).clip(CircleShape).background(Color(line.color)),
    ) {
        Text(line.code, color = Color(line.onColor), fontWeight = FontWeight.Bold, fontSize = (size * 0.34f).sp)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TripCard(
    status: TripStatus,
    hasKey: Boolean,
    stale: Boolean,
    onOpen: () -> Unit,
    onChangeDestination: (() -> Unit)?,
    cardOptions: CardOptions = CardOptions(),
) {
    val trip = status.trip
    val line = MetroLine.fromCode(trip.lineCode)
    val lineColor = Color(line.color)
    ElevatedCard(onClick = onOpen, shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
        // A strip of the line's colour along the top.
        Box(Modifier.fillMaxWidth().height(6.dp).background(lineColor))
        Column(Modifier.padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 16.dp)) {
            // Where you board, big and clear; the trip's name above it.
            Text(
                when (trip.label) { com.metrocast.app.data.Trip.GOING -> "➡️ "; com.metrocast.app.data.Trip.BACK -> "⬅️ "; else -> "" } + trip.label.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                letterSpacing = 1.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                LinePill(trip.lineCode, size = 34)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Board at", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(trip.stationName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    if (cardOptions.restroom) RestroomNote(trip.stationCode)
                }
            }
            Spacer(Modifier.height(10.dp))
            DestinationRow(trip.destName, lineColor, onChangeDestination)
            if (cardOptions.facts) status.ride?.let { RideFacts(it, line, trip.via) }
            Spacer(Modifier.height(14.dp))
            when {
                !hasKey -> Muted("Add a WMATA API key to see live times")
                stale -> Muted("Loading live times…")
                status.arrivals.isEmpty() -> Muted("No trains toward ${trip.towardName} predicted right now")
                else -> ArrivalsBoard(status.arrivals.take(cardOptions.trains), line)
            }
            status.location?.let { loc ->
                Spacer(Modifier.height(14.dp))
                TrackTimeline(loc, lineColor)
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "Tap to see these trains on the live map  ›",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** "To Metro Center ▾" — tappable on Silver trips to pick another stop. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DestinationRow(name: String, lineColor: Color, onChange: (() -> Unit)?) {
    val content: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(lineColor))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("GOING TO", style = MaterialTheme.typography.labelSmall, letterSpacing = 1.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (onChange != null) {
                Text("Change", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
    if (onChange != null) {
        Surface(onClick = onChange, shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) { content() }
    } else {
        Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) { content() }
    }
}

/** Ride time, stops, lines needed and fare, as small chips. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RideFacts(ride: com.metrocast.app.data.RideInfo, line: MetroLine, via: String? = null) {
    val facts = listOfNotNull(
        ride.minutes?.let { "⏱ $it min ride" },
        ride.stops?.let { if (it == 1) "1 stop" else "$it stops" },
        via?.let { "🔁 $it" } ?: "${line.displayName} Line · no transfer",
        ride.peakFare?.let { p ->
            val q = com.metrocast.app.data.Fares.quote(p, ride.offPeakFare ?: p, java.time.LocalDateTime.now())
            val off = " now (${q.period.label.lowercase()})"
            "${"$%.2f".format(q.perPerson)}$off"
        },
    )
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(top = 8.dp),
    ) {
        facts.forEach { f ->
            Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                Text(f, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp))
            }
        }
    }
}


/**
 * Incoming trains as a plain list: the next one large, then the following ones, each with where
 * it's going and how many cars — like the sign on the platform.
 */
@Composable
private fun ArrivalsBoard(arrivals: List<Arrival>, line: MetroLine) {
    val first = arrivals.first()
    val atPlatform = first.kind != com.metrocast.app.data.ArrivalKind.MINUTES || first.minutes == 0
    Text("INCOMING TRAINS", style = MaterialTheme.typography.labelSmall, letterSpacing = 1.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(6.dp))
    arrivals.take(4).forEachIndexed { i, a ->
        val big = i == 0
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(if (big) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent)
                .padding(horizontal = 10.dp, vertical = if (big) 10.dp else 6.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    when (i) {
                        0 -> if (atPlatform) "At the platform now" else "Next train"
                        1 -> if (atPlatform) "Missed it? Next one" else "After that"
                        else -> "Then"
                    },
                    style = if (big) MaterialTheme.typography.labelLarge else MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold,
                )
                Text(
                    listOfNotNull(
                        a.destination.takeIf { it.isNotBlank() }?.let { "to $it" },
                        a.cars.takeIf { it.isNotBlank() && it != "-" }?.let { "$it cars" },
                    ).joinToString(" · "),
                    style = if (big) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
                    fontWeight = if (big) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            val whenText = when (a.kind) {
                com.metrocast.app.data.ArrivalKind.BOARDING -> "Boarding"
                com.metrocast.app.data.ArrivalKind.ARRIVING -> "Arriving"
                com.metrocast.app.data.ArrivalKind.MINUTES -> if (a.minutes == 0) "Due" else "${a.minutes} min"
            }
            if (big) {
                Surface(shape = RoundedCornerShape(12.dp), color = Color(line.cardColor)) {
                    Text(whenText, fontSize = 28.sp, fontWeight = FontWeight.Black, color = Color(line.onColor),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                }
            } else {
                Text(whenText, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}


/**
 * Where the next trains are, top to bottom: the last few stations before yours, each on its own line
 * (full names, easy to read), with train markers at or between them and your station at the bottom.
 */
@Composable
private fun TrackTimeline(loc: TripLocation, lineColor: Color) {
    val n = loc.stations.size
    val rowH = 34.dp
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text("WHERE THE TRAINS ARE NOW", style = MaterialTheme.typography.labelSmall, letterSpacing = 1.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(rowH * n)) {
            // The line, top (farther away) to bottom (your station).
            Box(
                Modifier
                    .offset(x = 47.dp, y = rowH / 2)
                    .width(6.dp)
                    .height(rowH * (n - 1))
                    .clip(RoundedCornerShape(3.dp))
                    .background(lineColor.copy(alpha = 0.6f)),
            )
            loc.stations.forEachIndexed { i, st ->
                val mine = i == n - 1
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.offset(y = rowH * i).height(rowH).fillMaxWidth(),
                ) {
                    Spacer(Modifier.width(if (mine) 41.dp else 43.dp))
                    Box(
                        Modifier
                            .size(if (mine) 18.dp else 14.dp)
                            .clip(CircleShape)
                            .background(if (mine) lineColor else MaterialTheme.colorScheme.surface)
                            .border(BorderStroke(3.dp, if (mine) MaterialTheme.colorScheme.onSurface else lineColor), CircleShape),
                    )
                    Spacer(Modifier.width(if (mine) 14.dp else 16.dp))
                    Text(
                        if (mine) "${st.name} (you)" else st.name,
                        style = if (mine) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
                        fontWeight = if (mine) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            // Trains on their way to you, left of the line.
            loc.trains.forEachIndexed { k, t ->
                val pos = (n - 1) - t.stopsAway
                if (pos >= 0f) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .offset(x = 4.dp, y = rowH * pos + rowH / 2 - 11.dp)
                            .size(34.dp, 22.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.primary),
                    ) {
                        Text("▼", color = MaterialTheme.colorScheme.onPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        if (loc.trains.isEmpty()) {
            Text(
                if (com.metrocast.app.data.Repository.lastPositionsDown) "WMATA isn't sharing train locations right now — the times above are still live"
                else "No train on its way yet — the next one hasn't started its run",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        loc.trains.take(2).forEachIndexed { k, t ->
            Text(
                "${t.description} · ${t.stopsLabel}",
                style = if (k == 0) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall,
                fontWeight = if (k == 0) FontWeight.SemiBold else FontWeight.Normal,
                color = if (k == 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}


@Composable
internal fun SectionHeader(title: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        if (action != null) TextButton(onClick = onAction) { Text(action) }
    }
}

@Composable
private fun Muted(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun PlaceholderCard(text: String) {
    Card(shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
        Text(text, modifier = Modifier.padding(24.dp), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun ErrorBanner(text: String) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.width(10.dp))
            Text(text, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ApiKeyCard(onOpenSettings: () -> Unit) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text("Connect live train times", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                "MetroCast uses WMATA's free developer API. Grab a key at developer.wmata.com and paste it in Settings.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onOpenSettings) { Text("Open Settings") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LineMapCard(line: String, map: com.metrocast.app.data.LineMap?, onOpen: () -> Unit) {
    val name = MetroLine.fromCode(line).displayName
    Card(
        onClick = onOpen,
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(18.dp)) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(width = 40.dp, height = 26.dp)
                    .background(Color(MetroLine.fromCode(line).color), RoundedCornerShape(8.dp)),
            ) { Text("🚇") }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("$name Line live map", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                val all = map?.let { it.trains + it.branchTrains }.orEmpty()
                val sub = if (map == null || map.stations.size < 2) "See where every $name Line train is right now"
                // No trains placed yet (still loading, or WMATA's position feed is down): not "0 trains".
                else if (all.isEmpty()) (if (com.metrocast.app.data.Repository.lastPositionsDown) "WMATA isn't sending train positions right now" else "Loading where the trains are…")
                else {
                    // Each direction named by its end of the line, e.g. "5 to Largo, 4 to Ashburn".
                    fun end(s: com.metrocast.app.data.Station) = com.metrocast.app.data.Arrival(0, com.metrocast.app.data.ArrivalKind.MINUTES, s.name, "").shortDestination
                    "${all.size} trains · ${all.count { it.eastbound }} to ${end(map.stations.last())}, ${all.count { !it.eastbound }} to ${end(map.stations.first())}"
                }
                Text(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("›", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun MenuRow(icon: String, title: String, subtitle: String?, onClick: () -> Unit) {
    androidx.compose.material3.DropdownMenuItem(
        text = {
            Column {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        leadingIcon = { Text(icon, fontSize = 20.sp) },
        onClick = onClick,
    )
}

/** "🚧 Sat–Sun, Nov 21 – 22: track work on ➡️ Going there — on your line, expect longer waits ›" (null: nothing soon). */
internal fun TrackWorkBanner(impacts: List<com.metrocast.app.data.TrackWork.Impact>, today: java.time.LocalDate = java.time.LocalDate.now(java.time.ZoneId.of("America/New_York"))): String? {
    val soon = impacts.filter { it.level != com.metrocast.app.data.TrackWork.Level.STATION && it.end >= today && it.start.toEpochDay() - today.toEpochDay() <= 7 }
    val first = soon.minWithOrNull(compareBy({ it.start }, { it.level.ordinal })) ?: return null
    val same = soon.filter { it.start == first.start && it.advisory.id == first.advisory.id }
    val worst = same.minBy { it.level.ordinal }
    val trips = same.map { it.trip }.distinctBy { it.id }.joinToString(" & ") { roleOf(it) }
    val more = soon.map { it.advisory.id to it.start }.distinct().size - 1
    return "🚧 ${com.metrocast.app.data.TrackWork.whenLabel(first.start, first.end, today)} (${com.metrocast.app.data.TrackWork.dates(first.start, first.end)}): track work on $trips — " +
        worst.level.note.replaceFirstChar { it.lowercase() } + (if (more > 0) " · +$more more" else "") + " ›"
}

/** "🚄 Your train · 38 mph · riding to L'Enfant Plaza ›" */
@Composable
private fun SpeedCard(live: com.metrocast.app.data.LiveTrip, sp: com.metrocast.app.data.TrainSpeed.Reading?, metric: Boolean, onOpen: () -> Unit) {
    val line = MetroLine.fromCode(live.rideLine ?: live.legStatus?.leg?.options?.firstOrNull()?.line ?: "SV")
    Surface(onClick = onOpen, shape = MaterialTheme.shapes.large, color = Color(line.cardColor), modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text("🚄", fontSize = 28.sp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("YOUR TRAIN", color = Color(line.onColor), style = MaterialTheme.typography.labelSmall, letterSpacing = 1.sp, fontWeight = FontWeight.Bold)
                Text(
                    when { !live.onBoard -> "Shows once you're on the train"; sp == null -> "Measuring speed…"; sp.stopped -> "Stopped"; else -> sp.text(metric) },
                    color = Color(line.onColor), style = if (live.onBoard) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black,
                )
                live.legStatus?.leg?.to?.name?.let {
                    Text("Riding to $it" + (sp?.let { r -> if (r.fromGps) " · GPS" else " · from WMATA positions" } ?: ""), color = Color(line.onColor), style = MaterialTheme.typography.bodySmall)
                }
            }
            Text("›", color = Color(line.onColor), style = MaterialTheme.typography.headlineMedium)
        }
    }
}

/** Elevators out at your stations ("⚠️ Elevator out at Union Station: …"), or all clear in bike mode. */
@Composable
private fun ElevatorCard(outs: List<com.metrocast.app.data.ElevatorOutage>, bikeMode: Boolean, onOpen: () -> Unit) {
    val ok = outs.isEmpty()
    Surface(
        onClick = onOpen, shape = MaterialTheme.shapes.medium,
        color = if (ok) Color(0xFF1B5E20) else Color(0xFF8E1B1B), contentColor = Color.White,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            if (ok) {
                Text("Elevators on your trips are all working" + if (bikeMode) " — good to go with your bike" else "", fontWeight = FontWeight.SemiBold)
            } else {
                Text("ELEVATOR${if (outs.size > 1) "S" else ""} OUT AT YOUR STATIONS", style = MaterialTheme.typography.labelMedium, letterSpacing = 1.sp, fontWeight = FontWeight.Bold)
                outs.take(4).forEach { o ->
                    Text("${com.metrocast.app.data.StationDirectory.nameOf(o.stationCode) ?: o.stationCode}: ${o.where}" + (o.backBy?.let { " · back $it" } ?: ""),
                        style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                }
                Text("Tap for options ›", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/** What each trip card shows (Settings → Trip cards). */
data class CardOptions(val restroom: Boolean = true, val facts: Boolean = true, val trains: Int = 4)
