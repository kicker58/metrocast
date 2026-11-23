package com.metrocast.app.data

import org.json.JSONObject
import kotlin.math.abs

/** A station as riders know it: one entry for Metro Center even though it has two platforms (A01/C01). */
data class StationInfo(
    /** The code used to identify the station in the planner (the first platform's code). */
    val code: String,
    val name: String,
    val lines: List<String>,
    /** All platform codes of this station, e.g. ["A01", "C01"]. */
    val codes: List<String>,
    val lat: Double = 0.0,
    val lon: Double = 0.0,
)

/** Every Metrorail station, from WMATA's station list (cached). */
object StationDirectory {
    @Volatile var stations: List<StationInfo> = emptyList()
        private set
    @Volatile private var byCode: Map<String, StationInfo> = emptyMap()

    fun nameOf(code: String): String? = byCode[code]?.name

    fun find(code: String): StationInfo? = byCode[code]

    /** Parses WMATA's jStations response and merges stations that share a platform pair. */
    fun load(raw: String) {
        val arr = JSONObject(raw).getJSONArray("Stations")
        val rows = (0 until arr.length()).map { arr.getJSONObject(it) }
        val merged = LinkedHashMap<String, StationInfo>()
        for (o in rows) {
            val code = o.getString("Code")
            val together = o.optString("StationTogether1").takeIf { it.isNotBlank() && it != "null" }
            val key = listOfNotNull(code, together).min()
            val lines = (1..4).mapNotNull { i -> o.optString("LineCode$i").takeIf { it.isNotBlank() && it != "null" } }
            val prev = merged[key]
            merged[key] = if (prev == null) {
                StationInfo(
                    key, o.getString("Name"), lines, listOfNotNull(code, together).distinct().sorted(),
                    o.optDouble("Lat", 0.0), o.optDouble("Lon", 0.0),
                )
            } else {
                prev.copy(lines = (prev.lines + lines).distinct())
            }
        }
        // WMATA's list doesn't always tag the New Carrollton branch as Silver; it is.
        val nc = setOf("D09", "D10", "D11", "D12", "D13")
        val fixed = merged.values.map { s ->
            if (s.code in nc && "SV" !in s.lines) s.copy(lines = s.lines + "SV") else s
        }
        val lineOrder = listOf("RD", "OR", "SV", "BL", "YL", "GR")
        stations = fixed
            .map { it.copy(lines = it.lines.sortedBy { l -> lineOrder.indexOf(l) }) }
            .sortedBy { it.name }
        byCode = buildMap { stations.forEach { s -> s.codes.forEach { put(it, s) } } }
    }
}

/** One way along a line: its stations in order (Silver has two: to Largo, and to New Carrollton). */
data class LinePath(val line: String, val stations: List<Station>) {
    val codes: List<String> = stations.map { it.code }
}

/** One way to ride a leg, e.g. "Silver toward Downtown Largo". */
data class LegOption(val line: String, val path: LinePath, val boardCode: String, val alightCode: String, val towardCode: String, val towardName: String)

data class Leg(
    val from: StationInfo,
    val to: StationInfo,
    val stops: Int,
    /** Every line that runs this leg directly (e.g. Orange, Silver and Blue share track downtown). */
    val options: List<LegOption>,
)

data class PlannedRoute(val legs: List<Leg>) {
    val transfers: Int get() = legs.size - 1
    val stops: Int get() = legs.sumOf { it.stops }
}

/** Finds the route with the fewest transfers (then fewest stops) between two stations. */
object Planner {

    /** Line paths from line station lists; Silver gets a second path for the New Carrollton branch. */
    fun paths(lines: Map<String, List<Station>>): List<LinePath> = buildList {
        lines.forEach { (line, stations) -> if (stations.size >= 2) add(LinePath(line, stations)) }
        val sv = lines["SV"]
        if (sv != null) {
            val split = sv.indexOfFirst { it.code == "D08" }
            if (split >= 0) add(LinePath("SV", sv.subList(0, split + 1) + LineMapBuilder.BRANCH.drop(1)))
        }
    }

    fun plan(from: StationInfo, to: StationInfo, paths: List<LinePath>): PlannedRoute? {
        if (from.code == to.code) return null
        val (index, stationsOnPath) = tables(paths)
        fun idx(p: LinePath, s: StationInfo) = index.getValue(p)[s.code] ?: -1

        data class Candidate(val stations: List<StationInfo>, val stops: Int)
        val candidates = ArrayList<Candidate>()
        val starts = paths.filter { idx(it, from) >= 0 }
        val ends = paths.filter { idx(it, to) >= 0 }

        // Direct.
        for (p in starts) {
            val i = idx(p, to)
            if (i >= 0) candidates += Candidate(listOf(from, to), abs(i - idx(p, from)))
        }
        // One transfer.
        if (candidates.isEmpty()) {
            for (p in starts) for (q in ends) {
                if (p.line == q.line) continue
                for (x in stationsOnPath.getValue(p)) {
                    if (x.code == from.code || x.code == to.code || idx(q, x) < 0) continue
                    candidates += Candidate(listOf(from, x, to), abs(idx(p, x) - idx(p, from)) + abs(idx(q, to) - idx(q, x)))
                }
            }
        }
        // Two transfers.
        if (candidates.isEmpty()) {
            for (p in starts) for (r in paths) for (q in ends) {
                if (r.line == p.line || r.line == q.line) continue
                for (x in stationsOnPath.getValue(p)) {
                    if (idx(r, x) < 0 || x.code == from.code) continue
                    for (y in stationsOnPath.getValue(r)) {
                        if (idx(q, y) < 0 || y.code == x.code || y.code == to.code) continue
                        val stops = abs(idx(p, x) - idx(p, from)) + abs(idx(r, y) - idx(r, x)) + abs(idx(q, to) - idx(q, y))
                        candidates += Candidate(listOf(from, x, y, to), stops)
                    }
                }
            }
        }
        val best = candidates.minByOrNull { it.stops } ?: return null
        val legs = best.stations.zipWithNext { a, b -> leg(a, b, paths) ?: return null }
        return PlannedRoute(legs)
    }

    // Station → position on each path, and the stations on each path. Built once per set of line
    // data (it changes at most weekly) instead of on every plan.
    private class Tables(val index: Map<LinePath, Map<String, Int>>, val onPath: Map<LinePath, List<StationInfo>>)
    @Volatile private var cached: Triple<List<LinePath>, Int, Tables>? = null

    private fun tables(paths: List<LinePath>): Pair<Map<LinePath, Map<String, Int>>, Map<LinePath, List<StationInfo>>> {
        val dirSize = StationDirectory.stations.size
        cached?.let { (p, n, t) -> if (p === paths && n == dirSize) return t.index to t.onPath }
        val index = paths.associateWith { p ->
            buildMap { p.codes.forEachIndexed { i, c -> StationDirectory.find(c)?.let { putIfAbsent(it.code, i) } } }
        }
        val onPath = paths.associateWith { p -> index.getValue(p).keys.mapNotNull { StationDirectory.find(it) } }
        cached = Triple(paths, dirSize, Tables(index, onPath))
        return index to onPath
    }

    private fun leg(a: StationInfo, b: StationInfo, paths: List<LinePath>): Leg? {
        val options = paths.mapNotNull { p ->
            val i = p.codes.indexOfFirst { it in a.codes }
            val j = p.codes.indexOfFirst { it in b.codes }
            if (i < 0 || j < 0 || i == j) return@mapNotNull null
            val end = if (j > i) p.stations.last() else p.stations.first()
            LegOption(
                line = p.line, path = p,
                boardCode = p.codes[i], alightCode = p.codes[j],
                towardCode = end.code,
                towardName = StationDirectory.nameOf(end.code) ?: end.name,
            ) to abs(j - i)
        }
        if (options.isEmpty()) return null
        val stops = options.minOf { it.second }
        // One option per line (Silver's two paths give the same ride downtown).
        val unique = options.filter { it.second == stops }.map { it.first }.distinctBy { it.line }
        return Leg(a, b, stops, unique)
    }
}
