package com.metrocast.app.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontFamily
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.metrocast.app.R
import com.metrocast.app.data.MetroLine
import com.metrocast.app.data.Repository
import com.metrocast.app.data.Snapshot
import com.metrocast.app.data.TripStatus
import com.metrocast.app.data.WeatherCodes
import com.metrocast.app.ui.Format
import com.metrocast.app.ui.MainActivity

class MetroWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repo = Repository(context)
        if (repo.needsRefresh()) repo.refresh()
        val widgetId = Widgets.appWidgetId(context, id)

        provideContent {
            val state = currentState<Preferences>()
            val stamp = state[KEY_STAMP] ?: 0L
            val refreshing = state[KEY_REFRESHING] ?: false
            val snapshot = remember(stamp) { Widgets.forWidget(Widgets.snapshot(repo, stamp), repo.prefs.widgetTrips(widgetId)) }
            GlanceTheme {
                WidgetBody(snapshot, refreshing)
            }
        }
    }

    companion object {
        val KEY_STAMP = longPreferencesKey("stamp")
        val KEY_REFRESHING = booleanPreferencesKey("refreshing")

        /** Redraws every MetroCast widget from the latest cached data. */
        suspend fun notifyChanged(context: Context, refreshing: Boolean = false) {
            if (!Widgets.anyInstalled(context)) return   // nothing on the home screen: skip the work
            val manager = GlanceAppWidgetManager(context)
            // Every style: the departure board, pylon, next train and live track.
            val stamp = System.currentTimeMillis()   // one stamp per update, so all widgets share one data load
            for (w in Widgets.all()) manager.getGlanceIds(w.javaClass).forEach { id ->
                updateAppWidgetState(context, id) { prefs ->
                    prefs[KEY_STAMP] = stamp
                    prefs[KEY_REFRESHING] = refreshing
                }
                w.update(context, id)
            }
        }

        /** Background refresh without the spinner (the 30-second ticks). */
        suspend fun refreshQuietly(context: Context) {
            val repo = Repository(context)
            // While the app is open it refreshes every 30 s; reuse that instead of calling WMATA again.
            if (repo.needsRefresh(maxAgeMs = 25_000)) repo.refresh()
            notifyChanged(context, refreshing = false)
        }

        suspend fun refreshAll(context: Context, forceWeather: Boolean = false) {
            notifyChanged(context, refreshing = true)
            Repository(context).refresh(forceWeather)
            notifyChanged(context, refreshing = false)
        }
    }
}

class MetroWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MetroWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        WidgetRefreshWorker.schedule(context)
        WidgetTicker.schedule(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        if (!Widgets.anyInstalled(context)) WidgetTicker.cancel(context)
        // Keep the 15-minute job if alerts or reminders still need it.
        com.metrocast.app.notify.Background.sync(context)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        Widgets.forget(context, appWidgetIds)
    }
}

class RefreshAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        MetroWidget.refreshAll(context, forceWeather = true)
    }
}

// ------------------------------------------------------------------ UI
// Styled like a Metro station's arrival board: black panel, amber dot-matrix-style text,
// LN / CAR / DEST / MIN columns, the next 3 trains for each trip.

private val BoardBlack = ColorProvider(Color(0xFF0A0A0A))
private val BoardPanel = ColorProvider(Color(0xFF161616))
private val Amber = ColorProvider(Color(0xFFFFB31A))
private val AmberDim = ColorProvider(Color(0xFFB07A10))
private val BoardRed = ColorProvider(Color(0xFFFF5A3C))

private fun board(size: Int, color: ColorProvider = Amber, bold: Boolean = false, align: TextAlign = TextAlign.Start) =
    TextStyle(
        color = color,
        fontSize = size.sp,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium,
        fontFamily = FontFamily.Monospace,
        textAlign = align,
    )

@Composable
internal fun WidgetBody(s: Snapshot, refreshing: Boolean) {
    val size = LocalSize.current
    val maxTrips = when {
        size.height < 190.dp -> 1
        size.height < 300.dp -> 2
        else -> 3
    }
    val trips = s.trips.filter { it.trip.showOnWidget }.take(maxTrips)

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(BoardBlack)
            .cornerRadius(20.dp)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .clickable(actionStartActivity<MainActivity>()),
    ) {
        BoardHeader(s, refreshing)
        Spacer(GlanceModifier.height(8.dp))
        when {
            !s.hasApiKey -> Note("OPEN METROCAST TO ADD A WMATA KEY")
            trips.isEmpty() -> Note("NO TRIPS SELECTED FOR THE WIDGET")
            else -> trips.forEachIndexed { i, t ->
                if (i > 0) Spacer(GlanceModifier.height(6.dp))
                TripBoard(t, s.trainsStale)
            }
        }
    }
}

/** Top line: clock-style "updated" time, weather, and the refresh button. */
@Composable
private fun BoardHeader(s: Snapshot, refreshing: Boolean) {
    val w = s.weather
    Row(verticalAlignment = Alignment.CenterVertically, modifier = GlanceModifier.fillMaxWidth()) {
        if (w != null) {
            Image(
                provider = ImageProvider(WeatherCodes.icon(w.current.code, w.current.isDay)),
                contentDescription = WeatherCodes.describe(w.current.code),
                modifier = GlanceModifier.size(22.dp),
            )
            Spacer(GlanceModifier.width(6.dp))
            val today = w.daily.firstOrNull()
            Text(
                text = listOfNotNull(
                    Format.temp(w.current.temp),
                    WeatherCodes.describe(w.current.code).uppercase(),
                    today?.let { "H${Format.temp(it.high)} L${Format.temp(it.low)}" },
                ).joinToString("  "),
                style = board(12, bold = true),
                maxLines = 1,
                modifier = GlanceModifier.defaultWeight(),
            )
        } else {
            Text("METROCAST", style = board(13, bold = true), modifier = GlanceModifier.defaultWeight())
        }
        Text(
            text = if (refreshing) "UPDATING" else Format.clock(s.trainsUpdatedAt.takeIf { it > 0 } ?: System.currentTimeMillis()),
            style = board(11, AmberDim),
        )
        Spacer(GlanceModifier.width(8.dp))
        Box(
            contentAlignment = Alignment.Center,
            modifier = GlanceModifier
                .size(28.dp)
                .cornerRadius(14.dp)
                .background(BoardPanel)
                .clickable(actionRunCallback<RefreshAction>()),
        ) {
            Image(
                provider = ImageProvider(R.drawable.ic_refresh),
                contentDescription = "Refresh",
                colorFilter = ColorFilter.tint(Amber),
                modifier = GlanceModifier.size(16.dp),
            )
        }
    }
}

/** One trip as a board: station header, then LN / CAR / DEST / MIN rows for the next 3 trains. */
@Composable
private fun TripBoard(t: TripStatus, stale: Boolean) {
    Column(
        modifier = GlanceModifier
            .fillMaxWidth()
            .background(BoardPanel)
            .cornerRadius(12.dp)
            .padding(horizontal = 10.dp, vertical = 7.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = GlanceModifier.fillMaxWidth()) {
            Text(
                text = t.trip.stationName.uppercase(),
                style = board(11, bold = true),
                maxLines = 1,
                modifier = GlanceModifier.defaultWeight(),
            )
            Text(text = "→ " + t.trip.destName.uppercase(), style = board(11, AmberDim), maxLines = 1)
        }
        Spacer(GlanceModifier.height(3.dp))
        BoardRow("LN", "CAR", "DEST", "MIN", header = true, lineColor = null)
        val arrivals = if (stale) emptyList() else t.arrivals.take(3)
        val line = MetroLine.fromCode(t.trip.lineCode)
        if (arrivals.isEmpty()) {
            Text(
                text = if (stale) "TAP ↻ TO LOAD TIMES" else "NO TRAINS PREDICTED",
                style = board(12, AmberDim),
                modifier = GlanceModifier.padding(top = 2.dp),
            )
        } else {
            arrivals.forEach { a ->
                val min = when (a.kind) {
                    com.metrocast.app.data.ArrivalKind.BOARDING -> "BRD"
                    com.metrocast.app.data.ArrivalKind.ARRIVING -> "ARR"
                    com.metrocast.app.data.ArrivalKind.MINUTES -> if (a.minutes == 0) "ARR" else "${a.minutes}"
                }
                BoardRow(line.code, a.cars.takeIf { it.isNotBlank() && it != "-" } ?: "-", a.shortDestination, min, header = false, lineColor = Color(line.color))
            }
        }
        // Where the next one is right now, when known.
        t.location?.trains?.firstOrNull()?.let { loc ->
            Text(
                text = "NEXT: ${loc.description.uppercase()} · ${loc.stopsLabel.uppercase()}",
                style = board(10, AmberDim),
                maxLines = 1,
                modifier = GlanceModifier.padding(top = 3.dp),
            )
        }
    }
}

@Composable
private fun BoardRow(ln: String, car: String, dest: String, min: String, header: Boolean, lineColor: Color?) {
    val size = if (header) 9 else 13
    val color = if (header) AmberDim else Amber
    Row(verticalAlignment = Alignment.CenterVertically, modifier = GlanceModifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Box(modifier = GlanceModifier.width(30.dp), contentAlignment = Alignment.CenterStart) {
            if (lineColor != null) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = GlanceModifier.size(width = 24.dp, height = 15.dp).cornerRadius(4.dp).background(lineColor),
                ) {
                    Text(ln, style = TextStyle(color = ColorProvider(Color(0xFF111111)), fontSize = 9.sp, fontWeight = FontWeight.Bold))
                }
            } else {
                Text(ln, style = board(size, color))
            }
        }
        Text(car, style = board(size, color), modifier = GlanceModifier.width(32.dp))
        Text(dest.uppercase(), style = board(size, color, bold = !header), maxLines = 1, modifier = GlanceModifier.defaultWeight())
        Text(
            min,
            style = board(size, if (!header && (min == "BRD" || min == "ARR")) BoardRed else color, bold = !header, align = TextAlign.End),
            modifier = GlanceModifier.width(40.dp),
        )
    }
}

@Composable
private fun Note(text: String) {
    Text(text, style = board(12, AmberDim))
}
