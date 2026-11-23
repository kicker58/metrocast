package com.metrocast.app.data

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * How fast the train you're on is going. Two sources:
 *  - the phone's GPS speed, when you allowed location and it has a fix (above ground), and
 *  - WMATA's live positions: how far along the track (by track circuit, turned into metres with the
 *    stations' coordinates) your train moved between updates. Works underground; a little rougher.
 * Nothing is saved: readings live in memory for the current trip only.
 */
object TrainSpeed {
    const val MS_TO_MPH = 2.23694
    /** Faster than any Metro train runs (75 mph top speed): a glitch, not a reading. */
    private const val MAX_MPH = 80.0

    data class Reading(val mph: Double, val fromGps: Boolean) {
        val stopped: Boolean get() = mph < 2.0
        /** "38 mph" — or "61 km/h" with metric units. */
        fun text(metric: Boolean = false): String = if (metric) "${(mph * 1.60934).toInt()} km/h" else "${mph.toInt()} mph"
    }

    fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1); val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    /**
     * Metres from the start of [route] to track circuit index [ci]: whole station-to-station gaps,
     * plus the part of the current gap the train has covered (by circuit count).
     */
    fun metersAlong(route: TrainTracker.Route, ci: Int): Double? {
        // One circuit index per station (its first), in track order, with coordinates.
        val stops = route.stationAt.entries.sortedBy { it.key }
            .fold(ArrayList<Pair<Int, StationInfo>>()) { acc, (i, code) ->
                val s = StationDirectory.find(code)
                if (s != null && s.lat != 0.0 && acc.lastOrNull()?.second?.code != s.code) acc.add(i to s)
                acc
            }
        if (stops.size < 2) return null
        val cum = DoubleArray(stops.size)
        for (k in 1 until stops.size) {
            val a = stops[k - 1].second; val b = stops[k].second
            cum[k] = cum[k - 1] + haversine(a.lat, a.lon, b.lat, b.lon)
        }
        if (ci <= stops.first().first) return 0.0
        if (ci >= stops.last().first) return cum.last()
        val k = stops.indexOfLast { it.first <= ci }
        val (i0, _) = stops[k]; val (i1, _) = stops[k + 1]
        val frac = if (i1 > i0) (ci - i0).toDouble() / (i1 - i0) else 0.0
        return cum[k] + frac * (cum[k + 1] - cum[k])
    }

    /** Positions over time for the one train you're on. */
    class Tracker {
        private data class Sample(val t: Long, val m: Double)
        private var trainId: String? = null
        private val samples = ArrayDeque<Sample>()

        @Synchronized fun add(id: String, t: Long, meters: Double) {
            if (id != trainId) { trainId = id; samples.clear() }
            if (samples.lastOrNull()?.t?.let { it >= t } == true) return // same download again
            samples.addLast(Sample(t, meters))
            while (samples.size > 2 && t - samples.first().t > 3 * 60_000L) samples.removeFirst()
        }

        @Synchronized fun clear() { trainId = null; samples.clear() }

        /**
         * Average speed over about the last minute (at least 20 s, so one jumpy update can't spike it).
         * Null until there's enough to go on.
         */
        @Synchronized fun mph(): Double? {
            val n = samples.lastOrNull() ?: return null
            val o = samples.filter { n.t - it.t >= 20_000L }.minByOrNull { abs((n.t - it.t) - 60_000L) } ?: return null
            val v = abs(n.m - o.m) / ((n.t - o.t) / 1000.0) * MS_TO_MPH
            return v.takeIf { it <= MAX_MPH }
        }
    }
}
