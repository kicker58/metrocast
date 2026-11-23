package com.metrocast.app.data

/**
 * The live-trip logic as plain functions of WMATA data (no phone storage), so the app and the
 * tests run exactly the same code.
 */
object LiveEngine {

    /** A line's stations in order, from the track-circuit routes (longest track of the line). */
    fun lineOrder(routes: List<TrainTracker.Route>, lineCode: String, nameOf: (String) -> String?): List<Station>? {
        if (lineCode == "SV") return Stations.SILVER
        val route = routes.filter { it.line == lineCode }.maxByOrNull { it.stationAt.size } ?: return null
        val codes = ArrayList<String>()
        route.stationAt.toSortedMap().values.forEach { c -> if (codes.lastOrNull() != c) codes.add(c) }
        // Yellow Line trains have ended at Mt Vernon Sq since 2023; the track data still runs to Fort Totten.
        if (lineCode == "YL") codes.indexOf("E01").takeIf { it >= 0 }?.let { end -> codes.subList(end + 1, codes.size).clear() }
        // Potomac Yard (opened 2023, Blue and Yellow) isn't in WMATA's track data: it sits between
        // Braddock Road (C12) and Reagan National Airport (C10).
        if ((lineCode == "BL" || lineCode == "YL") && "C11" !in codes) {
            val a = codes.indexOf("C12")
            val b = codes.indexOf("C10")
            if (a >= 0 && b >= 0 && kotlin.math.abs(a - b) == 1) codes.add(maxOf(a, b), "C11")
        }
        if (codes.size < 2) return null
        return codes.map { Station(it, nameOf(it) ?: Branches.stationName(it, emptyMap())) }
    }

    /** Next trains for a leg, across every line that works for it, soonest first. */
    fun legArrivals(leg: Leg, trains: List<WmataApi.RawTrain>, ageMin: Int, limit: Int = 4): List<LegArrival> =
        leg.options.flatMap { opt ->
            val pseudo = Trip("plan", "", opt.line, opt.boardCode, "", opt.alightCode, "")
            Arrivals.filter(pseudo, trains, opt.path.codes, ageMin).map { LegArrival(opt.line, it) }
        }.sortedBy { it.arrival.minutes }.take(limit)

    /** The train at (or closest to) the boarding platform on one line option, with its distance in stops. */
    fun boardingTrain(opt: LegOption, routes: List<TrainTracker.Route>, positions: List<TrainTracker.Position>): Pair<String, Float>? {
        // A train sitting at the platform (this matters at the end of a line, where the waiting train
        // isn't "approaching" from anywhere).
        val platform = routes.filter { it.line == opt.line }.flatMap { r ->
            r.stationAt.filterValues { it == opt.boardCode }.keys.map { r.circuits[it] }
        }.toSet()
        val atEnd = opt.boardCode == opt.path.codes.firstOrNull() || opt.boardCode == opt.path.codes.lastOrNull()
        // Only a train headed your way: not one that just arrived here and ends here.
        val codes = opt.path.codes
        val b = codes.indexOf(opt.boardCode)
        val a = codes.indexOf(opt.alightCode)
        fun headedOurWay(p: TrainTracker.Position): Boolean {
            val d = p.destination ?: return false
            if (d == opt.boardCode) return false
            val di = Branches.indexOf(opt.line, codes, d)
            return di < 0 || (di - b) * (a - b) > 0
        }
        val waiting = positions.firstOrNull { it.line == opt.line && it.circuit in platform && headedOurWay(it) }
        if (atEnd && waiting != null) return waiting.trainId to 0f
        val pseudo = Trip("live", "", opt.line, opt.boardCode, "", opt.alightCode, "")
        return TrainTracker.locate(pseudo, routes, positions, opt.path.stations, maxStops = 60f)
            ?.trains?.firstOrNull()?.takeIf { it.trainId.isNotBlank() }?.let { it.trainId to it.stopsAway }
            ?: waiting?.let { it.trainId to 0f }
    }

    /**
     * After boarding without a known train (e.g. it was waiting where the app can't see it): the train
     * that has most recently left your boarding station toward where you get off is yours.
     */
    fun reacquire(leg: Leg, routes: List<TrainTracker.Route>, positions: List<TrainTracker.Position>): String? =
        leg.options.flatMap { opt ->
            positions.filter { it.line == null || it.line == opt.line }.mapNotNull { q ->
                follow(opt, q.trainId, routes, positions)?.takeIf { it.stopsAway <= leg.stops + 0.5f && it.stopsAway >= leg.stops - 2.5f }
            }
        }.maxByOrNull { it.stopsAway }?.trainId

    /** The train at the platform across every line that works for the leg. */
    fun boardingTrainAny(options: List<LegOption>, routes: List<TrainTracker.Route>, positions: List<TrainTracker.Position>): String? =
        options.mapNotNull { boardingTrain(it, routes, positions) }.minByOrNull { it.second }?.first

    /**
     * Where a specific train is relative to the stop where you get off, read straight from its spot
     * on the track (works at the ends of lines and for trains without a listed destination).
     * Null if it isn't on this stretch, is going the other way, or has passed your stop.
     */
    fun follow(opt: LegOption, trainId: String, routes: List<TrainTracker.Route>, positions: List<TrainTracker.Position>): TrainTrack? {
        val p = positions.firstOrNull { it.trainId == trainId } ?: return null
        if (p.line != null && p.line != opt.line) return null
        val names = opt.path.stations.associate { it.code to it.name }
        fun name(c: String) = names[c] ?: StationDirectory.nameOf(c) ?: c
        for (r in routes.filter { it.line == opt.line }) {
            val ci = r.indexOfCircuit[p.circuit] ?: continue
            val a = r.stationIndices[opt.boardCode] ?: continue
            val b = r.stationIndices[opt.alightCode] ?: continue
            val step = if (b.first() > a.first()) 1 else -1
            val stop = if (step == 1) b.first() else b.last()       // first circuit of your stop you reach
            val stopEnd = if (step == 1) b.last() else b.first()
            if ((ci - stopEnd) * step > 0) return null                // already past your stop
            val atStation = r.stationAt[ci]
            if ((ci - stop) * step >= 0 || atStation == opt.alightCode) return TrainTrack(trainId, 0f, "At ${name(opt.alightCode)}", null)
            val between = ArrayList<String>()
            var i = ci + step
            while (i in r.circuits.indices && (i - stop) * step < 0) {
                r.stationAt[i]?.let { c -> if (c != atStation && c != opt.alightCode && between.lastOrNull() != c) between.add(c) }
                i += step
            }
            val stops = between.size + if (atStation != null) 1f else 0.5f
            val where = when {
                atStation != null -> "At ${name(atStation)}"
                else -> {
                    var j = ci - step
                    var prev: String? = null
                    while (j in r.circuits.indices && prev == null) { prev = r.stationAt[j]; j -= step }
                    val next = name(between.firstOrNull() ?: opt.alightCode)
                    if (prev != null) "Between ${name(prev)} and $next" else "Approaching $next"
                }
            }
            return TrainTrack(trainId, stops, where, null)
        }
        return null
    }

    /**
     * Where your train is relative to the stop where you get off, and its predicted arrival there.
     * Tries each line that works for the leg. Null once it has passed that stop or can't be found.
     */
    fun track(
        options: List<LegOption>,
        trainId: String,
        routes: List<TrainTracker.Route>,
        positions: List<TrainTracker.Position>,
        trains: List<WmataApi.RawTrain>,
        ageMin: Int,
    ): Pair<LegOption, TrainTrack>? {
        for (opt in options) {
            val t = follow(opt, trainId, routes, positions) ?: continue
            // Predicted arrivals at your stop for this line and direction (any destination), soonest first.
            val pseudo = Trip("live", "", opt.line, opt.alightCode, "", opt.towardCode, "")
            val predicted = Arrivals.filter(pseudo, trains, opt.path.codes, ageMin, limit = 12, requireReach = false).map { it.minutes }
            // Trains between you and your stop, to line up with the predictions.
            val ahead = positions.count { q ->
                q.trainId != trainId && follow(opt, q.trainId, routes, positions)?.let { it.stopsAway < t.stopsAway } == true
            }
            return opt to t.copy(etaMinutes = matchPrediction(t.stopsAway, ahead, predicted))
        }
        return null
    }

    /**
     * Which predicted arrival at your stop belongs to your train. Pairing "n-th train approaching"
     * with "n-th prediction" breaks when the lists differ by one train (e.g. one waiting at a
     * platform), which would give you the next train's time. So a prediction is only used if it
     * fits how far away your train actually is; otherwise the stop-based estimate is used.
     */
    fun matchPrediction(stopsAway: Float, index: Int, predicted: List<Int>): Int? {
        if (predicted.isEmpty()) return null
        val est = stopsAway * MINUTES_PER_STOP_ESTIMATE
        val tolerance = maxOf(3f, est * 0.45f)
        fun fits(m: Int) = kotlin.math.abs(m - est) <= tolerance
        predicted.getOrNull(index)?.let { if (fits(it)) return it }
        return predicted.filter(::fits).minByOrNull { kotlin.math.abs(it - est) }
    }

    /** Typical minutes per stop (same as LiveTripEngine.MINUTES_PER_STOP). */
    const val MINUTES_PER_STOP_ESTIMATE = 2.2f

    /** True if [trainId] is seen on the track beyond the station where you get off (it went through). */
    fun isPast(options: List<LegOption>, trainId: String, routes: List<TrainTracker.Route>, positions: List<TrainTracker.Position>): Boolean {
        val p = positions.firstOrNull { it.trainId == trainId } ?: return false
        for (opt in options) {
            if (p.line != null && p.line != opt.line) continue
            for (r in routes.filter { it.line == opt.line }) {
                val ci = r.indexOfCircuit[p.circuit] ?: continue
                val a = r.stationIndices[opt.boardCode] ?: continue
                val b = r.stationIndices[opt.alightCode] ?: continue
                return if (b.first() > a.first()) ci > b.last() else ci < b.first()
            }
        }
        return false
    }

    data class ConnectionPick(val outMinutes: Int?, val outLine: String?, val slack: Int?, val verdict: Verdict, val backup: Int?)

    /** Which connecting train you'd catch if you arrive in [eta] minutes, and how much time you'd have. */
    fun connection(eta: Int, next: List<LegArrival>): ConnectionPick {
        val catchable = next.filter { it.arrival.minutes >= eta + 1 }
        val c = catchable.firstOrNull()
        val slack = c?.let { it.arrival.minutes - eta }
        return ConnectionPick(c?.arrival?.minutes, c?.line, slack, Verdict.of(slack), catchable.getOrNull(1)?.arrival?.minutes)
    }
}

/** What the app remembers about a live trip (kept in settings; this is just the data). */
data class LiveState(
    val from: String,
    val to: String,
    val leg: Int,
    val trainId: String?,
    /** When you boarded this leg (0 = not yet). */
    val boardedAt: Long,
    /** Your train's last distance in stops from where you get off (99 = unknown). */
    val lastStops: Float,
    /** Lowest distance seen this leg (to be sure the train really reached your stop). */
    val minStopsSeen: Float = 99f,
    /** Last good arrival time (minutes) and when it was worked out — carried over feed dropouts. */
    val lastEta: Int? = null,
    val lastEtaAt: Long = 0L,
)

/** Everything a live update needs, as data. */
class LiveInputs(
    val paths: List<LinePath>,
    val routes: List<TrainTracker.Route>?,
    val positions: List<TrainTracker.Position>?,
    val trains: List<WmataApi.RawTrain>,
    val ageMin: Int,
    /** Scheduled ride (minutes, peak fare, off-peak fare) between two station codes, if known. */
    val ride: (String, String) -> Triple<Int?, Double?, Double?>?,
    val incidents: List<Incident>,
    val now: Long,
)

object LiveTripEngine {

    /** A planned route with its live state: next trains per leg, transfers, alerts. */
    fun buildPlan(fromCode: String?, toCode: String?, inp: LiveInputs): PlanStatus? {
        toCode ?: return null
        val from = fromCode?.let { StationDirectory.find(it) }
        val to = StationDirectory.find(toCode)
        if (from == null || to == null) return PlanStatus(from, to, emptyList(), null, "Loading stations…")
        if (from.code == to.code) return PlanStatus(from, to, emptyList(), null, "Pick two different stations")
        val route = Planner.plan(from, to, inp.paths) ?: return PlanStatus(from, to, emptyList(), null, "Loading the Metro lines…")
        val legs = route.legs.map { leg ->
            val first = leg.options.first()
            LegStatus(leg, LiveEngine.legArrivals(leg, inp.trains, inp.ageMin), inp.ride(first.boardCode, first.alightCode)?.first)
        }
        val connections = legs.zipWithNext { a, b -> transferStatus(a, b, inp.trains, inp.ageMin) }
        val routeLines = legs.flatMap { l -> l.leg.options.map { it.line } }.toSet()
        val alerts = inp.incidents.filter { i -> i.lines.any { it in routeLines } }
        val info = inp.ride(from.code, to.code)
        return PlanStatus(from, to, legs, RideInfo(info?.first, route.stops, info?.second, info?.third), null, connections, alerts)
    }

    private fun transferStatus(inLeg: LegStatus, outLeg: LegStatus, trains: List<WmataApi.RawTrain>, ageMin: Int): TransferStatus {
        val inOpt = inLeg.leg.options.first()
        val outOpt = outLeg.leg.options.first()
        val changeLevels = inOpt.alightCode != outOpt.boardCode
        val walk = if (changeLevels) 4 else 1
        val incoming = inLeg.leg.options.flatMap { opt ->
            val pseudo = Trip("in", "", opt.line, opt.alightCode, "", opt.towardCode, "")
            Arrivals.filter(pseudo, trains, opt.path.codes, ageMin, limit = 6, requireReach = false)
        }.map { it.minutes }.distinct().sorted().take(3)
        val conns = incoming.map { inMin ->
            val pick = LiveEngine.connection(inMin, outLeg.arrivals)
            Connection(inMin, pick.outMinutes, pick.outLine, pick.verdict, pick.backup)
        }
        return TransferStatus(outLeg.leg.from.name, changeLevels, walk, inOpt.line, outOpt.line, outOpt.towardName, conns)
    }

    /** Average minutes per stop on Metro, for estimates when nothing better is known. */
    const val MINUTES_PER_STOP = 2.2f

    data class Result(val live: LiveTrip?, val state: LiveState)

    /**
     * One live update: follows your train, works out the ETA to where you get off (live prediction,
     * else from the stops left, else from the schedule — never blank once you've boarded), and rates
     * the connection. Returns the updated state (e.g. a train picked up after boarding).
     */
    fun update(state: LiveState, inp: LiveInputs): Result {
        val plan = buildPlan(state.from, state.to, inp) ?: return Result(null, state)
        val ls = plan.legs.getOrNull(state.leg)
            ?: return Result(LiveTrip(plan, state.leg, false, null, null, null, null, null, null, null, emptyList()), state)
        var st = state
        val onBoard = st.boardedAt > 0L
        val routes = inp.routes
        val positions = inp.positions

        // On board but no train known yet: pick it up once it leaves your station.
        if (onBoard && st.trainId == null && inp.now - st.boardedAt < 15 * 60_000L && routes != null && positions != null) {
            LiveEngine.reacquire(ls.leg, routes, positions)?.let { st = st.copy(trainId = it) }
        }
        var followed = st.trainId?.let { id ->
            if (routes != null && positions != null) LiveEngine.track(ls.leg.options, id, routes, positions, inp.trains, inp.ageMin) else null
        }
        // The followed train is still on its way TO your boarding station well after you said you
        // were on board: you're on the one ahead of it (e.g. you tapped after the doors closed).
        if (onBoard && followed != null && routes != null && positions != null &&
            followed.second.stopsAway > ls.leg.stops + 0.4f && inp.now - st.boardedAt > 90_000L
        ) {
            LiveEngine.reacquire(ls.leg, routes, positions)?.takeIf { it != st.trainId }?.let { ahead ->
                LiveEngine.track(ls.leg.options, ahead, routes, positions, inp.trains, inp.ageMin)?.let {
                    st = st.copy(trainId = ahead, lastStops = 99f, minStopsSeen = 99f)
                    followed = it
                }
            }
        }
        val opt = followed?.first ?: ls.leg.options.first()
        val track = followed?.second
        if (track != null) st = st.copy(lastStops = track.stopsAway, minStopsSeen = minOf(st.minStopsSeen, track.stopsAway))

        val passed = onBoard && track == null && st.trainId != null && routes != null && positions != null &&
            LiveEngine.isPast(ls.leg.options, st.trainId!!, routes, positions)
        // Is your train in the position feed at all right now? (It sometimes drops out briefly.)
        val visible = st.trainId != null && positions?.any { it.trainId == st.trainId } == true
        // Train briefly missing: keep counting down from the last good time rather than guessing anew.
        val carried = if (onBoard && track == null && !passed && st.lastEta != null && inp.now - st.lastEtaAt < 3 * 60_000L) {
            (st.lastEta!! - ((inp.now - st.lastEtaAt) / 60_000L).toInt()).coerceAtLeast(0)
        } else null
        val minutesPerStop = ls.minutes?.let { m -> m.toFloat() / ls.leg.stops.coerceAtLeast(1) } ?: MINUTES_PER_STOP
        val legMinutes = ls.minutes ?: kotlin.math.ceil(ls.leg.stops * MINUTES_PER_STOP).toInt()
        val eta: Int? = when {
            passed -> 0
            carried != null -> carried
            track?.etaMinutes != null -> track.etaMinutes
            track != null -> kotlin.math.ceil(track.stopsAway * minutesPerStop).toInt()
            onBoard -> (legMinutes - ((inp.now - st.boardedAt) / 60_000L).toInt()).coerceAtLeast(0)
            else -> ls.arrivals.firstOrNull()?.arrival?.minutes?.let { it + legMinutes }
        }
        val etaIsLive = track?.etaMinutes != null
        if (eta != null && (track != null || passed)) st = st.copy(lastEta = eta, lastEtaAt = inp.now)

        val next = plan.legs.getOrNull(state.leg + 1)
        val pick = if (next != null && eta != null) LiveEngine.connection(eta, next.arrivals) else null

        val codes = opt.path.codes
        val i = codes.indexOf(opt.boardCode)
        val j = codes.indexOf(opt.alightCode)
        val stretch = if (i >= 0 && j >= 0) {
            (if (j >= i) (i..j).toList() else (i downTo j).toList()).takeLast(5).map { opt.path.stations[it] }
        } else emptyList()

        val live = LiveTrip(
            plan, state.leg, onBoard, track, eta, pick?.outMinutes, pick?.outLine, pick?.slack,
            pick?.verdict, pick?.backup, stretch, followed?.first?.line, etaIsLive, passed, visible,
        )
        return Result(live, st)
    }

    /**
     * True when it's safe to move on by itself: your train was seen at (or right before) your stop,
     * it's no longer approaching it, and at least half the scheduled ride time has passed.
     */
    fun shouldAdvance(before: LiveState, after: LiveState, live: LiveTrip, now: Long): Boolean {
        if (!live.onBoard || live.arrived || after.trainId == null || live.track != null) return false
        if (live.trainPassedStop) return true
        // Missing from the feed tells us nothing: never move on just because the train vanished.
        if (!live.trainVisible) return false
        val ls = live.legStatus ?: return false
        val rode = (now - after.boardedAt) / 60_000.0
        val legMinutes = ls.minutes ?: (ls.leg.stops * MINUTES_PER_STOP).toInt()
        return after.minStopsSeen <= 0.5f && before.lastStops <= 1.0f && rode >= legMinutes * 0.5
    }

    /**
     * The transfer heads-up text: title + body with how far the transfer is, when you arrive, when
     * the connecting train leaves, and whether to run, hurry or take your time.
     */
    fun notificationText(live: LiveTrip): Pair<String, String>? {
        val at = live.legStatus?.leg?.to?.name ?: return null
        val nextOpt = live.plan.legs.getOrNull(live.leg + 1)?.leg?.options?.firstOrNull() ?: return null
        val line = MetroLine.fromCode(live.connectionLine ?: nextOpt.line).displayName
        val stops = live.track?.stopsAway?.let { kotlin.math.ceil(it.toDouble()).toInt() }
        val where = when {
            stops == null -> "almost there"
            stops <= 0 -> "here now"
            stops == 1 -> "the next stop"
            else -> "$stops stops away"
        }
        val arrive = live.etaMinutes?.let { if (it <= 0) "arriving now" else "you arrive in ~$it min" + if (live.etaIsLive) "" else " (est.)" }
        val leaves = live.connectionOut?.let { "$line train toward ${nextOpt.towardName} leaves in $it min" }
        val then = live.backupMinutes?.let { " Next one: $it min." } ?: ""
        val facts = listOfNotNull(arrive, leaves).joinToString(" · ").replaceFirstChar { it.uppercase() }
        return when (live.verdict) {
            Verdict.RUN -> "🏃 Run for the $line Line — ${live.slackMinutes} min" to "Transfer at $at is $where. $facts.$then"
            Verdict.WALK_FAST -> "Hurry for the $line Line — ${live.slackMinutes} min" to "Transfer at $at is $where. $facts.$then"
            Verdict.EASY -> "Take your time at $at — ${live.slackMinutes} min" to "Transfer is $where. $facts.$then"
            else -> "Transfer at $at is $where" to
                listOfNotNull(
                    arrive?.replaceFirstChar { it.uppercase() },
                    if (live.tooEarlyForConnection) "the $line connection shows once WMATA predicts trains that far ahead"
                    else "no $line train predicted after that yet",
                ).joinToString(" · ") + "."
        }
    }

    /** After your final train reaches your destination, the trip ends by itself this much later. */
    const val AUTO_END_MS = 7 * 60_000L

    /** Minutes left before an arrived trip ends by itself (0 = end now), or null if not arrived yet. */
    fun minutesUntilAutoEnd(arrivedAt: Long, now: Long): Int? {
        if (arrivedAt <= 0L) return null
        val left = AUTO_END_MS - (now - arrivedAt)
        return if (left <= 0) 0 else ((left + 59_999) / 60_000).toInt()
    }

    /** Whether the one "2 stops from your transfer" heads-up is due. */
    fun notificationDue(live: LiveTrip): Boolean {
        if (live.isLastLeg || !live.onBoard || live.arrived) return false
        val t = live.track
        return if (t != null) t.stopsAway <= 2.0f else (live.etaMinutes ?: Int.MAX_VALUE) <= 5
    }
}
