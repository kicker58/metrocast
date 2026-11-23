package com.metrocast.app.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metrocast.app.data.Arrival
import com.metrocast.app.data.ArrivalKind
import com.metrocast.app.data.LineMap
import com.metrocast.app.data.MapTrain
import com.metrocast.app.data.MetroLine
import com.metrocast.app.data.Station
import com.metrocast.app.data.StationDirectory
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot

// Lines that share track are drawn side by side, nudged by these amounts (in line widths).
private val LineNudge = mapOf("OR" to -1f, "SV" to 0f, "BL" to 1f, "YL" to -0.5f, "GR" to 0.5f, "RD" to 0f)
private val DrawOrder = listOf("RD", "GR", "YL", "BL", "OR", "SV")

/** When to write station names on the map. */
enum class Labels { WHEN_ZOOMED, MAJOR_THEN_ALL }

data class MapColors(val ink: Color, val paper: Color, val label: Color)

/** A train placed on the map: where it is on screen and which way it's heading. */
data class PlacedTrain(val line: String, val train: MapTrain, val stations: List<Station>, val at: Offset, val dir: Offset)

/** Turns station coordinates into screen positions for a canvas of [size] at [zoom] and [pan]. */
class MapGeometry(size: Size, val zoom: Float, val pan: Offset, density: Density, padding: Float = with(density) { 20.dp.toPx() }) {
    /** The network's extent, worked out once per station list (not on every frame of a pinch). */
    private class Bounds(val minLat: Double, val maxLat: Double, val minLon: Double, val maxLon: Double, val ok: Boolean)
    private companion object {
        @Volatile var cache: Pair<List<*>, Bounds>? = null
        fun bounds(): Bounds {
            val all = StationDirectory.stations
            cache?.let { (k, b) -> if (k === all) return b }
            val geo = all.filter { it.lat != 0.0 && it.lon != 0.0 }
            val b = Bounds(geo.minOfOrNull { it.lat } ?: 0.0, geo.maxOfOrNull { it.lat } ?: 0.0,
                geo.minOfOrNull { it.lon } ?: 0.0, geo.maxOfOrNull { it.lon } ?: 0.0, geo.isNotEmpty())
            cache = all to b
            return b
        }
    }
    private val b = bounds()
    val ok = b.ok && size.width > 0f && size.height > 0f
    private val minLat = b.minLat
    private val maxLat = b.maxLat
    private val minLon = b.minLon
    private val kx = cos(Math.toRadians((minLat + maxLat) / 2)).toFloat()
    private val w = (b.maxLon - minLon).toFloat() * kx
    private val h = (maxLat - minLat).toFloat()
    private val base = if (!ok || w <= 0f || h <= 0f) 1f else minOf((size.width - padding * 2) / w, (size.height - padding * 2) / h)
    private val ox = (size.width - w * base) / 2
    private val oy = (size.height - h * base) / 2

    /** Lines get a little thicker as you zoom in. */
    val lineW: Float = with(density) { (3.dp.toPx() * (0.85f + zoom * 0.25f)).coerceAtMost(8.dp.toPx()) }
    /** Train dots stay big enough to tap at any zoom. */
    val trainR: Float = with(density) { (7.dp.toPx() + zoom * 0.6f.dp.toPx()).coerceAtMost(11.dp.toPx()) }

    fun project(lat: Double, lon: Double) = Offset(
        (ox + (lon - minLon).toFloat() * kx * base) * zoom + pan.x,
        (oy + (maxLat - lat).toFloat() * base) * zoom + pan.y,
    )

    fun at(code: String): Offset? = StationDirectory.find(code)?.let { project(it.lat, it.lon) }

    fun nudge(a: Offset, b: Offset, k: Float): Offset {
        val dx = b.x - a.x; val dy = b.y - a.y
        val len = hypot(dx, dy).takeIf { it > 0f } ?: return Offset.Zero
        return Offset(-dy / len, dx / len) * (k * lineW)
    }

    /** Where a train is and which way it faces. */
    fun place(line: String, stations: List<Station>, t: MapTrain): PlacedTrain? {
        if (stations.isEmpty()) return null
        val k = LineNudge[line] ?: 0f
        val i = floor(t.pos).toInt().coerceIn(0, stations.size - 1)
        val j = (i + 1).coerceAtMost(stations.size - 1)
        val a = at(stations[i].code) ?: return null
        val b = at(stations[j].code) ?: a
        val p = (a + (b - a) * (t.pos - i)).let { if (i == j) it else it + nudge(a, b, k) }
        val (fromIdx, toIdx) = if (t.eastbound) i to j
        else (if (t.pos > i) j else i) to (if (t.pos > i) i else (i - 1).coerceAtLeast(0))
        val f = at(stations[fromIdx].code); val to = at(stations[toIdx].code)
        var dir = if (f != null && to != null && f != to) to - f else Offset.Zero
        val len = hypot(dir.x, dir.y)
        dir = if (len > 0f) dir / len else Offset.Zero
        return PlacedTrain(line, t, stations, p, dir)
    }
}

/** Every visible train with its screen position (used for drawing and for taps). */
fun placeTrains(g: MapGeometry, maps: Map<String, LineMap>, visible: Set<String>): List<PlacedTrain> =
    DrawOrder.filter { it in visible }.flatMap { line ->
        val m = maps[line] ?: return@flatMap emptyList()
        m.trains.mapNotNull { g.place(line, m.stations, it) } + m.branchTrains.mapNotNull { g.place(line, m.branchStations, it) }
    }

/** "Largo", "Ashburn": where a train is going, short. */
fun towardName(stations: List<Station>, t: MapTrain): String {
    val d = t.destination.takeIf { it.isNotBlank() } ?: (if (t.eastbound) stations.lastOrNull() else stations.firstOrNull())?.name.orEmpty()
    return Arrival(0, ArrivalKind.MINUTES, d, "").shortDestination
}

/** "At McLean" or "Next stop: McLean". */
fun whereIs(stations: List<Station>, t: MapTrain): String {
    if (stations.isEmpty()) return ""
    val i = floor(t.pos).toInt().coerceIn(0, stations.size - 1)
    val frac = t.pos - i
    if (i == stations.size - 1 || frac < 0.08f) return "At ${stations[i].name}"
    if (frac > 0.92f) return "At ${stations[i + 1].name}"
    val next = if (t.eastbound) stations[i + 1] else stations[i]
    return "Next stop: ${next.name}"
}

/** Ink that reads on a line colour (dark on Yellow and Silver, white on the rest). */
fun onLineColor(line: String): Color = if (line == "YL" || line == "SV") Color(0xFF1A1A1A) else Color.White

/**
 * Draws Metrorail from WMATA's station coordinates: each line's stations joined in order (lines
 * sharing track side by side), station dots, names with a halo, and live trains with a direction chevron.
 */
fun DrawScope.drawMetro(
    g: MapGeometry,
    lines: Map<String, List<List<Station>>>,
    placed: List<PlacedTrain>,
    visible: Set<String>,
    measurer: TextMeasurer,
    colors: MapColors,
    labels: Labels,
    selected: String? = null,
) {
    if (!g.ok) return
    val lineW = g.lineW
    val order = DrawOrder.filter { it in visible && lines.containsKey(it) }
    // Hidden lines stay as faint context so the map keeps its shape.
    for (line in DrawOrder.filter { it !in visible && lines.containsKey(it) }) for (list in lines.getValue(line)) {
        list.zipWithNext { s1, s2 ->
            val a = g.at(s1.code); val b = g.at(s2.code)
            if (a != null && b != null) drawLine(colors.label.copy(alpha = 0.18f), a, b, strokeWidth = lineW * 0.6f, cap = StrokeCap.Round)
        }
    }
    for (line in order) {
        val color = Color(MetroLine.fromCode(line).color)
        val k = LineNudge[line] ?: 0f
        for (list in lines.getValue(line)) {
            list.zipWithNext { s1, s2 ->
                val a = g.at(s1.code); val b = g.at(s2.code)
                if (a != null && b != null) {
                    val n = g.nudge(a, b, k)
                    drawLine(color, a + n, b + n, strokeWidth = lineW, cap = StrokeCap.Round)
                }
            }
        }
    }

    // Stations on the visible lines (transfer stations bigger).
    val onLines = order.flatMap { l -> lines.getValue(l).flatten().mapNotNull { StationDirectory.find(it.code) } }.distinctBy { it.code }
    for (s in onLines) {
        val p = g.project(s.lat, s.lon)
        val r = if (s.lines.size > 1) lineW * 1.3f else lineW * 0.8f
        drawCircle(colors.paper, r, p)
        drawCircle(colors.ink, r, p, style = Stroke(width = lineW * 0.45f))
    }

    // Trains: a line-coloured dot with a white rim and a chevron pointing the way it's going.
    val r = g.trainR
    for (pt in placed.sortedBy { it.train.id == selected }) {
        val color = Color(MetroLine.fromCode(pt.line).color)
        val sel = pt.train.id == selected
        val rr = if (sel) r * 1.35f else r
        if (sel) drawCircle(colors.ink.copy(alpha = 0.18f), rr * 2.1f, pt.at)
        drawCircle(Color(0x44000000), rr + 2.5f, pt.at + Offset(0f, 2f))
        drawCircle(Color.White, rr + 2.5f, pt.at)
        drawCircle(color, rr, pt.at)
        if (sel) drawCircle(colors.ink, rr + 2.5f, pt.at, style = Stroke(width = 3f))
        if (pt.dir != Offset.Zero) {
            val d = pt.dir; val side = Offset(-d.y, d.x)
            val tip = pt.at + d * (rr * 0.6f)
            val back = pt.at - d * (rr * 0.35f)
            val chevron = Path().apply {
                moveTo(back.x + side.x * rr * 0.5f, back.y + side.y * rr * 0.5f)
                lineTo(tip.x, tip.y)
                lineTo(back.x - side.x * rr * 0.5f, back.y - side.y * rr * 0.5f)
            }
            drawPath(chevron, onLineColor(pt.line), style = Stroke(width = rr * 0.32f, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
        }
    }

    // Names on top, with a halo, so trains never hide them. Line ends and transfer stations always; every station once you zoom in. Never on top of each other.
    val ends = order.flatMap { l -> lines.getValue(l).flatMap { listOfNotNull(it.firstOrNull()?.code, it.lastOrNull()?.code) } }
        .mapNotNull { StationDirectory.find(it)?.code }.toSet()
    val named = when (labels) {
        Labels.WHEN_ZOOMED -> if (g.zoom >= 2.4f) onLines else emptyList()
        Labels.MAJOR_THEN_ALL -> if (g.zoom >= 2.6f) onLines else onLines.filter { it.code in ends || it.lines.size > 1 }
    }.sortedByDescending { it.code in ends || it.lines.size > 1 }
    if (named.isNotEmpty()) {
        val fs = (10 + g.zoom * 0.7f).coerceAtMost(15f)
        val style = TextStyle(
            fontSize = fs.sp, color = colors.ink, fontWeight = FontWeight.SemiBold,
            shadow = Shadow(colors.paper, Offset.Zero, blurRadius = 6f),
        )
        val taken = ArrayList<Rect>()
        for (s in named) {
            val p = g.project(s.lat, s.lon)
            val layout = measurer.measure(s.name, style)
            val tl = p + Offset(lineW * 2f, -layout.size.height / 2f)
            val r = Rect(tl, Size(layout.size.width.toFloat(), layout.size.height.toFloat()))
            if (r.right < 0 || r.bottom < 0 || r.left > size.width - 8f || r.top > size.height - 8f) continue
            if (r.left < 0 || r.top < 0 || r.right > size.width || r.bottom > size.height) continue // drawText can't clip
            if (taken.any { it.overlaps(r) }) continue
            taken += r
            drawText(layout, topLeft = tl)
        }
    }
}
