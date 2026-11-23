package com.metrocast.app.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.metrocast.app.R
import com.metrocast.app.data.Arrival
import com.metrocast.app.data.ArrivalKind
import com.metrocast.app.data.MetroLine
import com.metrocast.app.data.Repository
import com.metrocast.app.data.Snapshot
import com.metrocast.app.data.StationDirectory
import com.metrocast.app.data.Trip
import com.metrocast.app.data.TripStatus
import com.metrocast.app.ui.Format
import com.metrocast.app.ui.MainActivity

/** Every MetroCast widget style: the departure board plus the three Metro-themed ones. */
object Widgets {
    val receivers: List<Class<out GlanceAppWidgetReceiver>> = listOf(
        MetroWidgetReceiver::class.java, PylonWidgetReceiver::class.java,
        NextTrainWidgetReceiver::class.java, TrackWidgetReceiver::class.java,
    )

    fun anyInstalled(context: Context): Boolean = runCatching {
        val m = AppWidgetManager.getInstance(context)
        receivers.any { m.getAppWidgetIds(ComponentName(context, it)).isNotEmpty() }
    }.getOrDefault(false)

    /**
     * The snapshot as one placed widget should show it: the trips chosen for that widget (in the
     * chosen order), or, when none were chosen, the trips marked "Show on widget".
     */
    fun forWidget(s: Snapshot, chosen: List<String>?): Snapshot {
        val ids = chosen?.filter { id -> s.trips.any { it.trip.id == id } }.orEmpty()
        if (ids.isEmpty()) return s.copy(trips = s.trips.filter { it.trip.showOnWidget && it.trip.onHome })
        return s.copy(trips = ids.mapNotNull { id -> s.trips.firstOrNull { it.trip.id == id }?.let { st -> st.copy(trip = st.trip.copy(showOnWidget = true)) } })
    }

    suspend fun appWidgetId(context: Context, id: GlanceId): Int =
        runCatching { androidx.glance.appwidget.GlanceAppWidgetManager(context).getAppWidgetId(id) }.getOrDefault(-1)

    /** A removed widget's choices are deleted; trips added only for it go too, if no other widget uses them. */
    fun forget(context: Context, appWidgetIds: IntArray) {
        val prefs = com.metrocast.app.data.Prefs(context)
        appWidgetIds.forEach { prefs.setWidgetTrips(it, null) }
        val m = runCatching { AppWidgetManager.getInstance(context) }.getOrNull()
        val stillUsed = receivers.flatMap { r -> m?.getAppWidgetIds(ComponentName(context, r))?.toList().orEmpty() }
            .flatMap { prefs.widgetTrips(it).orEmpty() }.toSet()
        prefs.trips = prefs.trips.filter { it.onHome || it.id in stillUsed }
    }

    @Volatile private var lastSnapshot: Pair<Long, Snapshot>? = null

    /** The data for one widget update, built once and shared by every placed widget. */
    fun snapshot(repo: Repository, stamp: Long): Snapshot {
        lastSnapshot?.let { (s, snap) -> if (s == stamp && stamp != 0L) return snap }
        return repo.snapshot().also { lastSnapshot = stamp to it }
    }

    /** One of each, for redrawing them all. */
    fun all(): List<GlanceAppWidget> = listOf(MetroWidget(), PylonWidget(), NextTrainWidget(), TrackWidget())
}

/** Shared loading and refresh state for the Metro-themed widgets; each draws its own [Body]. */
abstract class ThemedWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    @Composable abstract fun Body(s: Snapshot, refreshing: Boolean)

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repo = Repository(context)
        if (repo.needsRefresh()) repo.refresh()
        val widgetId = Widgets.appWidgetId(context, id)
        provideContent {
            val state = currentState<Preferences>()
            val stamp = state[MetroWidget.KEY_STAMP] ?: 0L
            val refreshing = state[MetroWidget.KEY_REFRESHING] ?: false
            val snapshot = remember(stamp) { Widgets.forWidget(Widgets.snapshot(repo, stamp), repo.prefs.widgetTrips(widgetId)) }
            GlanceTheme { Body(snapshot, refreshing) }
        }
    }
}

/** Starts and stops the 30-second refresh for any widget style. */
abstract class ThemedReceiver : GlanceAppWidgetReceiver() {
    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        WidgetRefreshWorker.schedule(context)
        WidgetTicker.schedule(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        if (!Widgets.anyInstalled(context)) WidgetTicker.cancel(context)
        com.metrocast.app.notify.Background.sync(context)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        Widgets.forget(context, appWidgetIds)
    }
}

class PylonWidget : ThemedWidget() { @Composable override fun Body(s: Snapshot, refreshing: Boolean) = PylonBody(s, refreshing) }
class NextTrainWidget : ThemedWidget() { @Composable override fun Body(s: Snapshot, refreshing: Boolean) = NextTrainBody(s, refreshing) }
class TrackWidget : ThemedWidget() { @Composable override fun Body(s: Snapshot, refreshing: Boolean) = TrackBody(s, refreshing) }

class PylonWidgetReceiver : ThemedReceiver() { override val glanceAppWidget: GlanceAppWidget = PylonWidget() }
class NextTrainWidgetReceiver : ThemedReceiver() { override val glanceAppWidget: GlanceAppWidget = NextTrainWidget() }
class TrackWidgetReceiver : ThemedReceiver() { override val glanceAppWidget: GlanceAppWidget = TrackWidget() }

// ------------------------------------------------------------------ shared bits

private val White = ColorProvider(Color.White)
private val Ink = ColorProvider(Color(0xFF111111))

private fun txt(size: Int, color: ColorProvider = White, bold: Boolean = false, align: TextAlign = TextAlign.Start) =
    TextStyle(color = color, fontSize = size.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal, textAlign = align)

private fun lineColor(code: String) = Color(MetroLine.fromCode(code).color)
private fun onLine(code: String) = ColorProvider(Color(MetroLine.fromCode(code).onColor))

/** "BRD", "ARR" or the minutes, the way station boards say it. */
internal fun minutesText(a: Arrival): String = when {
    a.kind == ArrivalKind.BOARDING -> "BRD"
    a.kind == ArrivalKind.ARRIVING || a.minutes == 0 -> "ARR"
    else -> "${a.minutes}"
}

private fun widgetTrips(s: Snapshot) = s.trips.filter { it.trip.showOnWidget }

/** A message instead of times: no key, no trips, or times not loaded yet. */
internal fun problem(s: Snapshot): String? = when {
    !s.hasApiKey -> "Open MetroCast to add a WMATA key"
    widgetTrips(s).isEmpty() -> "No trips selected for the widget"
    else -> null
}

private fun roleTag(t: Trip) = when (t.label) { Trip.GOING -> "➡ GOING"; Trip.BACK -> "⬅ BACK"; else -> t.label.uppercase() }

@Composable
private fun RefreshButton(tint: ColorProvider, bg: ColorProvider) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = GlanceModifier.size(26.dp).cornerRadius(13.dp).background(bg).clickable(actionRunCallback<RefreshAction>()),
    ) {
        Image(ImageProvider(R.drawable.ic_refresh), "Refresh", colorFilter = ColorFilter.tint(tint), modifier = GlanceModifier.size(15.dp))
    }
}

// ------------------------------------------------------------------ 1. Metro pylon
// Like the bronze pylons outside every station: the white "M", the station name, a stripe for each
// line that stops there, then your next trains.

private val Bronze = ColorProvider(Color(0xFF3D3128))
private val BronzeLight = ColorProvider(Color(0xFF574638))
private val BronzeText = ColorProvider(Color(0xFFD9C8B4))

@Composable
internal fun PylonBody(s: Snapshot, refreshing: Boolean) {
    val t = widgetTrips(s).firstOrNull()
    val lines = t?.let { StationDirectory.find(it.trip.stationCode)?.lines }?.takeIf { it.isNotEmpty() } ?: listOfNotNull(t?.trip?.lineCode)
    Column(
        GlanceModifier.fillMaxSize().background(Bronze).cornerRadius(18.dp).padding(12.dp).clickable(actionStartActivity<MainActivity>()),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = GlanceModifier.fillMaxWidth()) {
            Box(contentAlignment = Alignment.Center, modifier = GlanceModifier.size(34.dp).cornerRadius(6.dp).background(White)) {
                Text("M", style = txt(24, Ink, bold = true, align = TextAlign.Center))
            }
            Spacer(GlanceModifier.width(8.dp))
            Column(GlanceModifier.defaultWeight()) {
                Text(t?.trip?.stationName ?: "MetroCast", style = txt(14, bold = true), maxLines = 2)
                Text(if (refreshing) "Updating…" else "Updated ${Format.clock(s.trainsUpdatedAt.takeIf { it > 0 } ?: System.currentTimeMillis())}", style = txt(10, BronzeText))
            }
            RefreshButton(White, BronzeLight)
        }
        Spacer(GlanceModifier.height(8.dp))
        // One colored stripe per line, like the pylon's bands.
        Row(GlanceModifier.fillMaxWidth()) {
            lines.forEachIndexed { i, l ->
                if (i > 0) Spacer(GlanceModifier.width(3.dp))
                Box(GlanceModifier.defaultWeight().height(7.dp).cornerRadius(3.dp).background(lineColor(l))) {}
            }
        }
        Spacer(GlanceModifier.height(8.dp))
        val msg = problem(s)
        if (msg != null || t == null) { Text(msg ?: "", style = txt(12, BronzeText)); return@Column }
        Text("${roleTag(t.trip)} · TO ${t.trip.destName.uppercase()}", style = txt(10, BronzeText, bold = true), maxLines = 1)
        val next = if (s.trainsStale) emptyList() else t.arrivals.take(3)
        if (next.isEmpty()) {
            Text(if (s.trainsStale) "Tap ↻ for times" else "No trains predicted", style = txt(13, BronzeText))
        } else {
            Row(verticalAlignment = Alignment.Bottom, modifier = GlanceModifier.fillMaxWidth()) {
                Text(minutesText(next[0]), style = txt(34, bold = true))
                if (next[0].kind == ArrivalKind.MINUTES && next[0].minutes > 0) Text(" min", style = txt(14, BronzeText), modifier = GlanceModifier.padding(bottom = 6.dp))
                Spacer(GlanceModifier.defaultWeight())
                Text(next.drop(1).joinToString("  ") { minutesText(it) }.let { if (it.isBlank()) "" else "then $it" }, style = txt(12, BronzeText), modifier = GlanceModifier.padding(bottom = 6.dp))
            }
        }
    }
}

// ------------------------------------------------------------------ 2. Next train
// A big line-colored bullet with the minutes, like the Metro line signs; one per everyday trip.

private val Night = ColorProvider(Color(0xFF14181C))
private val NightPanel = ColorProvider(Color(0xFF20262C))
private val Grey = ColorProvider(Color(0xFFA9B3BC))

@Composable
internal fun NextTrainBody(s: Snapshot, refreshing: Boolean) {
    val size = LocalSize.current
    val trips = widgetTrips(s).take(if (size.width >= 250.dp) 2 else 1)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = GlanceModifier.fillMaxSize().background(Night).cornerRadius(18.dp).padding(8.dp).clickable(actionStartActivity<MainActivity>()),
    ) {
        val msg = problem(s)
        if (msg != null) { Text(msg, style = txt(12, Grey), modifier = GlanceModifier.defaultWeight()); RefreshButton(White, NightPanel); return@Row }
        trips.forEachIndexed { i, t ->
            if (i > 0) Spacer(GlanceModifier.width(6.dp))
            NextTrainCell(t, s.trainsStale, GlanceModifier.defaultWeight())
        }
        Spacer(GlanceModifier.width(4.dp))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            RefreshButton(White, NightPanel)
            if (refreshing) Text("…", style = txt(10, Grey))
        }
    }
}

@Composable
private fun NextTrainCell(t: TripStatus, stale: Boolean, modifier: GlanceModifier) {
    val next = if (stale) emptyList() else t.arrivals.take(3)
    val code = t.trip.lineCode
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier.fillMaxHeight().background(NightPanel).cornerRadius(14.dp).padding(8.dp)) {
        Box(contentAlignment = Alignment.Center, modifier = GlanceModifier.size(52.dp).cornerRadius(26.dp).background(lineColor(code))) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(next.firstOrNull()?.let { minutesText(it) } ?: "–", style = txt(20, onLine(code), bold = true, align = TextAlign.Center))
                if (next.firstOrNull()?.let { it.kind == ArrivalKind.MINUTES && it.minutes > 0 } == true) Text("min", style = txt(9, onLine(code), align = TextAlign.Center))
            }
        }
        Spacer(GlanceModifier.width(8.dp))
        Column(GlanceModifier.defaultWeight()) {
            Text(roleTag(t.trip), style = txt(9, Grey, bold = true), maxLines = 1)
            Text(t.trip.stationName, style = txt(12, bold = true), maxLines = 1)
            Text("to ${t.trip.destName}", style = txt(11, Grey), maxLines = 1)
            Text(
                when {
                    stale -> "tap ↻ for times"
                    next.size > 1 -> "then " + next.drop(1).joinToString(", ") { minutesText(it) } + " min"
                    next.isEmpty() -> "no trains predicted"
                    else -> "${MetroLine.fromCode(code).displayName} Line"
                },
                style = txt(10, Grey), maxLines = 1,
            )
        }
    }
}

// ------------------------------------------------------------------ 3. Live track
// Your line drawn as a track: the stations before yours, the trains on them, and your stop at the end.

private val TrackBg = ColorProvider(Color(0xFFF4F1EA))
private val TrackInk = ColorProvider(Color(0xFF1B1B1B))
private val TrackMuted = ColorProvider(Color(0xFF6B665E))

@Composable
internal fun TrackBody(s: Snapshot, refreshing: Boolean) {
    val size = LocalSize.current
    val trips = widgetTrips(s).take(if (size.height >= 170.dp) 2 else 1)
    Column(GlanceModifier.fillMaxSize().background(TrackBg).cornerRadius(18.dp).padding(10.dp).clickable(actionStartActivity<MainActivity>())) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = GlanceModifier.fillMaxWidth()) {
            Text("🚇 LIVE TRACK", style = txt(11, TrackInk, bold = true), modifier = GlanceModifier.defaultWeight())
            Text(if (refreshing) "updating…" else Format.clock(s.trainsUpdatedAt.takeIf { it > 0 } ?: System.currentTimeMillis()), style = txt(10, TrackMuted))
            Spacer(GlanceModifier.width(6.dp))
            RefreshButton(TrackInk, ColorProvider(Color(0xFFE2DDD2)))
        }
        val msg = problem(s)
        if (msg != null) { Text(msg, style = txt(12, TrackMuted)); return@Column }
        trips.forEachIndexed { i, t ->
            Spacer(GlanceModifier.height(if (i == 0) 4.dp else 8.dp))
            TrackStrip(t, s.trainsStale)
        }
    }
}

@Composable
private fun TrackStrip(t: TripStatus, stale: Boolean) {
    val code = t.trip.lineCode
    val color = lineColor(code)
    val loc = t.location
    val stations = loc?.stations?.takeLast(6).orEmpty()
    val next = if (stale) null else t.arrivals.firstOrNull()
    Row(verticalAlignment = Alignment.CenterVertically, modifier = GlanceModifier.fillMaxWidth()) {
        Text("${roleTag(t.trip)} ", style = txt(9, TrackMuted, bold = true))
        Text("${t.trip.stationName} → ${t.trip.destName}", style = txt(11, TrackInk, bold = true), maxLines = 1, modifier = GlanceModifier.defaultWeight())
        Text(next?.let { minutesText(it) + if (it.kind == ArrivalKind.MINUTES && it.minutes > 0) " min" else "" } ?: "–", style = txt(13, TrackInk, bold = true))
    }
    Spacer(GlanceModifier.height(4.dp))
    if (stations.isEmpty()) {
        Text(if (stale) "Tap ↻ to load trains" else "Train positions show up here (Settings → Live train location)", style = txt(10, TrackMuted), maxLines = 2)
        return
    }
    // Stops-away for each station slot: the last slot is your station (0).
    val n = stations.size
    val trains = loc!!.trains
    Row(verticalAlignment = Alignment.CenterVertically, modifier = GlanceModifier.fillMaxWidth()) {
        stations.forEachIndexed { i, st ->
            val stopsAway = (n - 1 - i).toFloat()
            val here = trains.any { it.stopsAway == stopsAway }
            val between = trains.any { it.stopsAway > stopsAway && it.stopsAway < stopsAway + 1f }
            if (i > 0) {
                // Track between the previous station and this one; a train on it when one's in between.
                Box(contentAlignment = Alignment.Center, modifier = GlanceModifier.defaultWeight().height(16.dp)) {
                    Box(GlanceModifier.fillMaxWidth().height(5.dp).background(color)) {}
                    if (between) Text("🚆", style = txt(12, align = TextAlign.Center))
                }
            }
            val mine = i == n - 1
            Box(
                contentAlignment = Alignment.Center,
                modifier = GlanceModifier.size(if (mine) 20.dp else 14.dp).cornerRadius(if (mine) 10.dp else 7.dp)
                    .background(if (here) ColorProvider(Color(0xFF111111)) else if (mine) ColorProvider(color) else White),
            ) {
                if (here) Text("■", style = txt(8, White, align = TextAlign.Center))
            }
        }
    }
    Row(GlanceModifier.fillMaxWidth()) {
        Text(stations.first().name, style = txt(9, TrackMuted), maxLines = 1, modifier = GlanceModifier.defaultWeight())
        Text("YOU: ${stations.last().name}", style = txt(9, TrackInk, bold = true), maxLines = 1)
    }
    trains.firstOrNull()?.let { tr ->
        Text("Next: ${tr.stopsLabel.lowercase()} · ${tr.description}", style = txt(9, TrackMuted), maxLines = 1)
    }
}
