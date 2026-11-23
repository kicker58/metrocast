package com.metrocast.app.data

import org.json.JSONObject

/** Picks the predictions that match a trip (right station, line and direction). */
object Arrivals {

    fun filter(
        trip: Trip,
        trains: List<WmataApi.RawTrain>,
        order: List<String>?,
        ageMinutes: Int,
        limit: Int = 4,
        requireReach: Boolean = true,
    ): List<Arrival> {
        val here = order?.indexOf(trip.stationCode) ?: -1
        val toward = order?.let { Branches.indexOf(trip.lineCode, it, trip.towardCode) } ?: -1
        val useOrder = order != null && here >= 0 && toward >= 0 && here != toward

        val atStation = trains.filter { it.locationCode == trip.stationCode && it.line == trip.lineCode }

        // true = our direction, false = other direction, null = destination not on this line's route
        fun direction(t: WmataApi.RawTrain): Boolean? {
            val dest = t.destinationCode ?: return null
            if (!useOrder) return if (dest == trip.towardCode) true else null
            val d = Branches.indexOf(trip.lineCode, order!!, dest)
            if (d < 0) return null
            return if (toward > here) d > here else d < here
        }

        // Trains to unfamiliar destinations (a new branch, a turnback) are matched by platform
        // group: WMATA reports the same Group for every train on the same track at a station.
        val ourGroups = atStation.filter { direction(it) == true }.map { it.group }.toSet()

        return atStation.asSequence()
            .filter { t ->
                when (direction(t)) {
                    // Going our way; for a mid-line destination it must also run at least that far
                    // (a train ending at Federal Center SW doesn't get you to Largo).
                    true -> !useOrder || !requireReach || Branches.reaches(trip.lineCode, order!!, trip.stationCode, trip.towardCode, t.destinationCode)
                    false -> false
                    null -> t.group.isNotBlank() && t.group in ourGroups &&
                        (!useOrder || Branches.reaches(trip.lineCode, order!!, trip.stationCode, trip.towardCode, t.destinationCode))
                }
            }
            .mapNotNull { toArrival(it, ageMinutes) }
            .take(limit)
            .toList()
    }

    private fun toArrival(t: WmataApi.RawTrain, age: Int): Arrival? {
        val n = t.min.trim().toIntOrNull()
        return when {
            n != null -> {
                val adjusted = n - age
                if (adjusted < 0) null
                else Arrival(adjusted, ArrivalKind.MINUTES, t.destinationName, t.cars)
            }
            t.min.equals("BRD", ignoreCase = true) ->
                if (age > 0) null else Arrival(0, ArrivalKind.BOARDING, t.destinationName, t.cars)
            t.min.equals("ARR", ignoreCase = true) ->
                if (age > 0) null else Arrival(0, ArrivalKind.ARRIVING, t.destinationName, t.cars)
            else -> null // "---" or blank: no estimate
        }
    }
}

/**
 * Works out where approaching trains are, using WMATA's live TrainPositions (which report
 * the track circuit each train occupies) and StandardRoutes (the ordered circuits of each
 * line/track, with station circuits marked).
 */
object TrainTracker {

    class Route(
        val line: String,
        val circuits: IntArray,
        /** circuit list index -> station code at that circuit */
        val stationAt: Map<Int, String>,
        val indexOfCircuit: Map<Int, Int>,
    ) {
        val stationIndices: Map<String, List<Int>> =
            stationAt.entries.groupBy({ it.value }, { it.key }).mapValues { e -> e.value.sorted() }
    }

    data class Position(
        val trainId: String,
        val circuit: Int,
        val line: String?,
        val destination: String?,
        val cars: Int,
        val directionNum: Int,
    )

    fun parseRoutes(raw: String): List<Route> {
        val arr = JSONObject(raw).getJSONArray("StandardRoutes")
        return (0 until arr.length()).map { i ->
            val r = arr.getJSONObject(i)
            val tc = r.getJSONArray("TrackCircuits")
            val items = (0 until tc.length()).map { tc.getJSONObject(it) }.sortedBy { it.optInt("SeqNum") }
            val circuits = IntArray(items.size) { items[it].optInt("CircuitId") }
            val stationAt = HashMap<Int, String>()
            val indexOf = HashMap<Int, Int>()
            items.forEachIndexed { idx, o ->
                indexOf[circuits[idx]] = idx
                o.str("StationCode")?.let { stationAt[idx] = it }
            }
            Route(r.str("LineCode") ?: "", circuits, stationAt, indexOf)
        }
    }

    /**
     * WMATA sometimes leaves a train's line out. Where its track circuit is on only one line, that's
     * its line; on shared track (e.g. Orange/Silver/Blue downtown) it stays unknown rather than guessed.
     */
    fun fillLines(positions: List<Position>, routes: List<Route>?): List<Position> {
        if (routes.isNullOrEmpty() || positions.none { it.line == null }) return positions
        val linesAt = HashMap<Int, MutableSet<String>>()
        routes.forEach { r -> if (r.line.isNotBlank()) r.indexOfCircuit.keys.forEach { c -> linesAt.getOrPut(c) { HashSet() }.add(r.line) } }
        return positions.map { p -> if (p.line != null) p else linesAt[p.circuit]?.singleOrNull()?.let { p.copy(line = it) } ?: p }
    }

    const val MAX_POSITIONS = 200

    fun parsePositions(raw: String): List<Position> {
        val arr = JSONObject(raw).getJSONArray("TrainPositions")
        // Metro runs about 150 trains at its busiest: past 200, the rest is a broken answer, not more trains.
        return (0 until minOf(arr.length(), MAX_POSITIONS)).map { i ->
            val o = arr.getJSONObject(i)
            Position(
                trainId = o.str("TrainId") ?: "$i",
                circuit = o.optInt("CircuitId"),
                line = o.str("LineCode"),
                destination = o.str("DestinationStationCode"),
                cars = o.optInt("CarCount"),
                directionNum = o.optInt("DirectionNum"),
            )
        }
    }

    fun locate(
        trip: Trip,
        routes: List<Route>,
        positions: List<Position>,
        lineStations: List<Station>,
        maxStops: Float = 40f,
        requireReach: Boolean = true,
    ): TripLocation? {
        val order = lineStations.map { it.code }
        val names = lineStations.associate { it.code to it.name }
        fun name(code: String) = names[code] ?: StationDirectory.nameOf(code) ?: code

        val here = order.indexOf(trip.stationCode)
        val toward = Branches.indexOf(trip.lineCode, order, trip.towardCode)
        if (here < 0 || toward < 0 || here == toward) return null
        // Track direction: use the line's end on the destination's side.
        val endCode = if (toward > here) order.last() else order.first()

        // Trains on this line whose destination is beyond our station in our direction.
        // Destinations off this line's route (e.g. Silver trains to New Carrollton) are matched
        // via the branch table, or by sharing a direction number with a train we can place.
        fun direction(p: Position): Boolean? {
            val dest = p.destination ?: return null
            val d = Branches.indexOf(trip.lineCode, order, dest)
            if (d < 0) return null
            return if (toward > here) d > here else d < here
        }
        val onLine = positions.filter { it.line == trip.lineCode }
        val ourDirections = onLine.filter { direction(it) == true }.map { it.directionNum }.toSet()
        val candidates = onLine.filter { p ->
            when (direction(p)) {
                true -> !requireReach || Branches.reaches(trip.lineCode, order, trip.stationCode, trip.towardCode, p.destination)
                false -> false
                null -> p.destination != null && p.directionNum != 0 && p.directionNum in ourDirections
            }
        }

        val found = LinkedHashMap<String, ApproachingTrain>()
        var upcoming: List<Station>? = null

        for (route in routes) {
            if (route.line != trip.lineCode) continue
            val sAll = route.stationIndices[trip.stationCode] ?: continue
            val tAll = route.stationIndices[endCode] ?: route.stationIndices[trip.towardCode] ?: continue
            val step = if (tAll.first() > sAll.first()) 1 else -1
            // The circuit where an approaching train first reaches our station.
            val sIdx = if (step == 1) sAll.first() else sAll.last()

            if (upcoming == null) {
                val before = ArrayList<String>()
                var i = sIdx - step
                while (i in route.circuits.indices && before.size < 4) {
                    route.stationAt[i]?.let { c ->
                        if (c != trip.stationCode && before.lastOrNull() != c) before.add(c)
                    }
                    i -= step
                }
                upcoming = (before.reversed() + trip.stationCode).map { Station(it, name(it)) }
            }

            for (p in candidates) {
                if (found.containsKey(p.trainId)) continue
                val c = route.indexOfCircuit[p.circuit] ?: continue
                val atStation = route.stationAt[c]
                if ((c - sIdx) * step > 0 && atStation != trip.stationCode) continue // already passed

                // Distinct stations strictly between the train and our station.
                val between = ArrayList<String>()
                var i = c + step
                while (i in route.circuits.indices && (i - sIdx) * step < 0) {
                    route.stationAt[i]?.let { code ->
                        if (code != trip.stationCode && code != atStation && between.lastOrNull() != code) {
                            between.add(code)
                        }
                    }
                    i += step
                }

                val stops: Float
                val desc: String
                when {
                    atStation == trip.stationCode -> {
                        stops = 0f; desc = "At ${name(trip.stationCode)}"
                    }
                    atStation != null -> {
                        stops = between.size + 1f; desc = "At ${name(atStation)}"
                    }
                    else -> {
                        stops = between.size + 0.5f
                        var j = c - step
                        var prev: String? = null
                        var guard = 0
                        while (j in route.circuits.indices && prev == null && guard < 400) {
                            prev = route.stationAt[j]; j -= step; guard++
                        }
                        val next = name(between.firstOrNull() ?: trip.stationCode)
                        desc = if (prev != null) "Between ${name(prev)} and $next" else "Approaching $next"
                    }
                }
                if (stops <= maxStops) {
                    found[p.trainId] = ApproachingTrain(stops, desc, p.cars, Branches.stationName(p.destination ?: "", names), p.trainId)
                }
            }
        }

        val stations = upcoming ?: return null
        val sorted = found.values.sortedBy { it.stopsAway }
        return TripLocation(stations, if (maxStops > 40f) sorted else sorted.take(3))
    }
}

/**
 * Lines that split into branches. WMATA's line path only follows one branch, so stations
 * on the other branch are mapped to the first station past the split on the main path —
 * enough to tell which direction a train is heading.
 */
object Branches {
    private val NEW_CARROLLTON_BRANCH = mapOf(
        "D09" to "Minnesota Ave",
        "D10" to "Deanwood",
        "D11" to "Cheverly",
        "D12" to "Landover",
        "D13" to "New Carrollton",
    )

    // Since June 2025, every other Silver Line train runs to New Carrollton instead of Downtown Largo.
    private val LARGO_BRANCH = listOf("G01", "G02", "G03", "G04", "G05")

    // A station missing from the station order is placed at the nearest station that is in it:
    // New Carrollton branch → Benning Road (on the Largo order), Largo branch → Stadium-Armory
    // (on the New Carrollton order). Enough to tell which way a train is heading.
    private val ALIASES: Map<String, Map<String, String>> = mapOf(
        "SV" to NEW_CARROLLTON_BRANCH.keys.associateWith { "G01" } + LARGO_BRANCH.associateWith { "D08" },
    )

    fun indexOf(lineCode: String, order: List<String>, code: String): Int {
        val i = order.indexOf(code)
        if (i >= 0) return i
        val alias = ALIASES[lineCode]?.get(code) ?: return -1
        return order.indexOf(alias)
    }

    /** Destinations offered for a line: every station, plus the New Carrollton branch on Silver. */
    fun destinations(lineCode: String, stations: List<Station>): List<Station> =
        if (lineCode == "SV") stations + NEW_CARROLLTON_BRANCH.map { (c, n) -> Station(c, n) } else stations

    /**
     * Whether a train ending at [trainDest] carries you from [from] to [to]. A line terminal as [to]
     * just means "that direction" (so Silver trips "to Largo" also show New Carrollton trains).
     */
    fun reaches(lineCode: String, order: List<String>, from: String, to: String, trainDest: String?): Boolean {
        val dest = trainDest ?: return true
        // Silver "to Downtown Largo" is the classic "to DC" trip: New Carrollton trains go that way too.
        if (lineCode == "SV" && to == "G05" && from !in LARGO_BRANCH) return true
        val nc = NEW_CARROLLTON_BRANCH.keys.toList()
        if (lineCode == "SV" && to in nc) {
            return dest in nc && nc.indexOf(dest) >= nc.indexOf(to)
        }
        val here = order.indexOf(from)
        val target = order.indexOf(to)
        if (here < 0 || target < 0) return true
        // New Carrollton trains share the Largo route only as far as Stadium-Armory.
        val ends = when {
            lineCode == "SV" && dest in nc && dest !in order -> order.indexOf("D08")
            lineCode == "SV" && dest in LARGO_BRANCH && dest !in order -> order.indexOf("D08")
            else -> indexOf(lineCode, order, dest)
        }
        if (ends < 0) return true
        return if (target > here) ends >= target else ends <= target
    }

    /** Stops from [from] to [to] along the line (counting the branch on Silver). */
    fun stopsBetween(lineCode: String, order: List<String>, from: String, to: String): Int? {
        val nc = NEW_CARROLLTON_BRANCH.keys.toList()
        val a = order.indexOf(from)
        if (a < 0) return null
        if (lineCode == "SV" && to in nc) {
            val split = order.indexOf("D08").takeIf { it >= 0 } ?: return null
            return kotlin.math.abs(split - a) + nc.indexOf(to) + 1
        }
        val b = order.indexOf(to)
        return if (b < 0) null else kotlin.math.abs(b - a)
    }

    fun stationName(code: String, names: Map<String, String>): String =
        names[code] ?: NEW_CARROLLTON_BRANCH[code] ?: StationDirectory.nameOf(code) ?: code

    /** How a line's terminal is shown when picking a direction. */
    fun terminalName(lineCode: String, station: Station): String =
        if (lineCode == "SV" && station.code == "G05") "Downtown Largo / New Carrollton" else station.name
}

/** A train placed along a line, for the live map. */
data class MapTrain(
    val id: String,
    /** Position in station units along [LineMap.stations] (or the branch): 2.5 = halfway between stations 2 and 3. */
    val pos: Float,
    /** true = heading toward the last station in [LineMap.stations], false = toward the first. */
    val eastbound: Boolean,
    val destination: String,
    val cars: Int,
)

data class LineMap(
    val lineCode: String,
    val stations: List<Station>,
    val trains: List<MapTrain>,
    /** Silver only: Stadium-Armory → New Carrollton, where every other Silver train goes. */
    val branchStations: List<Station>,
    val branchTrains: List<MapTrain>,
    val updatedAt: Long,
    /** Train id → time to the station of one of your trips it's heading to, e.g. "5m" to your station. */
    val etas: Map<String, TrainEta> = emptyMap(),
    /** The trip stations those times are for, e.g. "Metro Center". */
    val etaTargets: List<EtaTarget> = emptyList(),
)

data class TrainEta(val short: String, val stationName: String)

data class EtaTarget(val stationName: String, val towardName: String, val eastbound: Boolean)

object LineMapBuilder {
    val BRANCH: List<Station> = listOf(
        Station("D08", "Stadium-Armory"),
        Station("D09", "Minnesota Ave"),
        Station("D10", "Deanwood"),
        Station("D11", "Cheverly"),
        Station("D12", "Landover"),
        Station("D13", "New Carrollton"),
    )

    /**
     * Fractional station position of [circuit] on any of [routes], using [order] as the station
     * sequence. Null if the circuit isn't between two of those stations on those routes.
     */
    private fun place(routes: List<TrainTracker.Route>, order: List<String>, circuit: Int): Float? {
        for (route in routes) {
            val c = route.indexOfCircuit[circuit] ?: continue
            // Anchor each station of [order] to the middle of its circuits on this track.
            val anchors = order.mapIndexedNotNull { i, code ->
                route.stationIndices[code]?.let { idx -> i to idx.average().toFloat() }
            }
            if (anchors.size < 2) continue
            val ascending = anchors.last().second > anchors.first().second
            val cf = c.toFloat()
            for (k in 0 until anchors.size - 1) {
                val (i0, a0) = anchors[k]
                val (i1, a1) = anchors[k + 1]
                val lo = minOf(a0, a1)
                val hi = maxOf(a0, a1)
                if (cf in lo..hi) {
                    val f = if (hi == lo) 0f else (cf - a0) / (a1 - a0)
                    return i0 + f * (i1 - i0)
                }
            }
            // Just past either end (pocket tracks, terminal tail tracks): pin to that end.
            val first = anchors.first().second
            val last = anchors.last().second
            val beforeFirst = if (ascending) cf < first else cf > first
            val afterLast = if (ascending) cf > last else cf < last
            if (beforeFirst && kotlin.math.abs(cf - first) <= 6) return anchors.first().first.toFloat()
            if (afterLast && kotlin.math.abs(cf - last) <= 6) return anchors.last().first.toFloat()
        }
        return null
    }

    fun build(
        lineCode: String,
        routes: List<TrainTracker.Route>,
        positions: List<TrainTracker.Position>,
        stations: List<Station>,
        updatedAt: Long,
    ): LineMap {
        val order = stations.map { it.code }
        val branchOrder = BRANCH.map { it.code }
        val names = (stations + BRANCH).associate { it.code to it.name }
        val svRoutes = routes.filter { it.line == lineCode }
        val orRoutes = if (lineCode == "SV") routes.filter { it.line == "OR" } else emptyList()

        val main = ArrayList<MapTrain>()
        val branch = ArrayList<MapTrain>()
        for (p in positions) {
            if (p.line != lineCode) continue
            val destName = p.destination?.let { names[it] ?: Branches.stationName(it, names) } ?: ""
            // Which way: compare where it's going with where it is; fall back to WMATA's direction number.
            fun eastbound(pos: Float, destIndex: Int): Boolean =
                if (destIndex >= 0 && destIndex.toFloat() != pos) destIndex > pos else p.directionNum == 1

            val onMain = place(svRoutes, order, p.circuit)
            if (onMain != null) {
                val d = p.destination?.let { Branches.indexOf(lineCode, order, it) } ?: -1
                main += MapTrain(p.trainId, onMain, eastbound(onMain, d), destName, p.cars)
                continue
            }
            val onBranch = place(orRoutes, branchOrder, p.circuit)
            if (onBranch != null) {
                val d = p.destination?.let { dest ->
                    branchOrder.indexOf(dest).takeIf { it >= 0 } ?: if (dest in order) 0 else -1
                } ?: -1
                branch += MapTrain(p.trainId, onBranch, eastbound(onBranch, d), destName, p.cars)
            }
        }
        val withBranch = lineCode == "SV"
        return LineMap(lineCode, stations, main, if (withBranch) BRANCH else emptyList(), branch, updatedAt)
    }
}
