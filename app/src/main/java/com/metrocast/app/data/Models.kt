package com.metrocast.app.data

import java.time.LocalDate
import java.time.LocalDateTime

/** WMATA rail lines with their official colours. */
enum class MetroLine(val code: String, val displayName: String, val color: Long, val onColor: Long) {
    RED("RD", "Red", 0xFFBF0D3E, 0xFFFFFFFF),
    ORANGE("OR", "Orange", 0xFFED8B00, 0xFF1A1A1A),
    SILVER("SV", "Silver", 0xFF919D9D, 0xFF1A1A1A),
    BLUE("BL", "Blue", 0xFF009CDE, 0xFFFFFFFF),
    YELLOW("YL", "Yellow", 0xFFFFD100, 0xFF1A1A1A),
    GREEN("GR", "Green", 0xFF00B140, 0xFFFFFFFF);

    companion object {
        fun fromCode(code: String): MetroLine = entries.firstOrNull { it.code == code } ?: SILVER
    }

    /**
     * The line's colour for a card or chip with text on it: Blue and Green a shade deeper so white
     * text is easy to read, Silver a shade lighter for its dark text (all at least 4.5:1 contrast).
     */
    val cardColor: Long get() = when (this) {
        BLUE -> 0xFF006BA6
        GREEN -> 0xFF007A33
        SILVER -> 0xFFB4BCBC
        else -> color
    }
}

data class Station(val code: String, val name: String)

/** A saved commute: "trains on [line] at [station] heading toward [terminal]". */
data class Trip(
    val id: String,
    val label: String,
    val lineCode: String,
    val stationCode: String,
    val stationName: String,
    val towardCode: String,
    val towardName: String,
    val showOnWidget: Boolean = true,
    /** Where the trip really ends when it needs a transfer ([towardCode] is then where you change). */
    val finalCode: String? = null,
    val finalName: String? = null,
    /** e.g. "Change at L'Enfant Plaza to Green" (null when there's no transfer). */
    val via: String? = null,
    /** False for trips added only for a widget: they stay off the home screen. */
    val onHome: Boolean = true,
) {
    /** The station you're going to, transfer or not. */
    val destCode: String get() = finalCode ?: towardCode
    val destName: String get() = finalName ?: towardName

    companion object {
        const val GOING = "Going there"
        const val BACK = "Coming back"
    }
}

enum class ArrivalKind { MINUTES, ARRIVING, BOARDING }

data class Arrival(
    val minutes: Int,
    val kind: ArrivalKind,
    val destination: String,
    val cars: String,
) {
    /** "Boarding", "Arriving", "Due", "4 min" */
    val label: String
        get() = when (kind) {
            ArrivalKind.BOARDING -> "Boarding"
            ArrivalKind.ARRIVING -> "Arriving"
            ArrivalKind.MINUTES -> if (minutes == 0) "Due" else "$minutes min"
        }

    /** "Largo", "New Carrollton", "Stadium-Armory" */
    val shortDestination: String
        get() = destination.removePrefix("Downtown ").trim()

    /** Compact form for widgets: "Brd", "Arr", "4". */
    val short: String
        get() = when (kind) {
            ArrivalKind.BOARDING -> "Brd"
            ArrivalKind.ARRIVING -> "Arr"
            ArrivalKind.MINUTES -> if (minutes == 0) "Due" else "$minutes"
        }
}

/** A train that is on its way to the trip's station. */
data class ApproachingTrain(
    /** 0 = at your station, 1 = at the previous station, 0.5 = between them, etc. */
    val stopsAway: Float,
    val description: String,
    val cars: Int,
    val destinationName: String,
    /** WMATA's train id, to find the same train on the live map. */
    val trainId: String = "",
) {
    val stopsLabel: String
        get() {
            val n = kotlin.math.ceil(stopsAway.toDouble()).toInt()
            return when {
                stopsAway == 0f -> "At your station"
                n == 1 -> "1 stop away"
                else -> "$n stops away"
            }
        }
}

/** Live position info for a trip: the last few stations before yours and the trains on them. */
data class TripLocation(
    /** Farthest station first, the trip's own station last. */
    val stations: List<Station>,
    val trains: List<ApproachingTrain>,
)

/** Ride facts for a trip, from WMATA's station-to-station info. */
data class RideInfo(
    val minutes: Int?,
    val stops: Int?,
    val peakFare: Double?,
    val offPeakFare: Double?,
)

/** A WMATA service alert, e.g. single tracking or a line ending early. */
/** An elevator out of service: where it is, and when WMATA expects it back (yyyy-mm-dd). */
data class ElevatorOutage(val stationCode: String, val where: String, val backBy: String?)

data class Incident(val description: String, val type: String, val lines: List<String>, val updated: String, val id: String = "")

/** One train followed during a live trip: how far it is from where you get off, and when it gets there. */
data class TrainTrack(
    val trainId: String,
    /** Stops until your stop (0 = at it). */
    val stopsAway: Float,
    /** "At Spring Hill", "Between Tysons and McLean"… */
    val where: String,
    /** Minutes until it reaches your stop, from WMATA's predictions there (null if not predicted yet). */
    val etaMinutes: Int?,
)

/** Everything the live-trip screen and its notification need, recomputed every refresh. */
data class LiveTrip(
    val plan: PlanStatus,
    /** Current leg (0-based). */
    val leg: Int,
    /** True once you've said you're on the train for this leg. */
    val onBoard: Boolean,
    /** Your train, when it can be followed. */
    val track: TrainTrack?,
    /** Minutes until your train reaches where you get off (live, else estimated). */
    val etaMinutes: Int?,
    /** At a transfer: the connecting train you'd catch, the time to spare, the rating and the next one. */
    val connectionOut: Int?,
    val connectionLine: String?,
    val slackMinutes: Int?,
    val verdict: Verdict?,
    val backupMinutes: Int?,
    /** The last few stations before where you get off (for the little live track). */
    val stretch: List<Station>,
    /** The line of the train you're on, once the app is following it. */
    val rideLine: String? = null,
    /** True when [etaMinutes] is WMATA's live prediction (false = estimated from stops/schedule). */
    val etaIsLive: Boolean = false,
    /** Your train has been seen beyond the stop where you get off (so you're there). */
    val trainPassedStop: Boolean = false,
    /** Your train is in WMATA's position feed right now. */
    val trainVisible: Boolean = false,
    /** Once arrived: minutes until the trip ends by itself. */
    val autoEndMinutes: Int? = null,
) {
    /**
     * WMATA predicts only ~20–30 min ahead: if you arrive after the last connecting train it has
     * predicted so far, the connection can't be judged yet (it isn't "missed").
     */
    val tooEarlyForConnection: Boolean get() {
        if (connectionOut != null) return false
        val eta = etaMinutes ?: return true
        val lastPredicted = plan.legs.getOrNull(leg + 1)?.arrivals?.maxOfOrNull { it.arrival.minutes } ?: return true
        return eta >= lastPredicted || eta > 22
    }
    val isLastLeg: Boolean get() = leg >= plan.legs.size - 1
    val arrived: Boolean get() = leg >= plan.legs.size
    val legStatus: LegStatus? get() = plan.legs.getOrNull(leg)
}

/** A way in/out of a station, e.g. "23rd St NW & I St NW" (elevator or escalator). */
data class StationExit(val name: String, val description: String, val lat: Double, val lon: Double, val elevator: Boolean)

/** A planned journey's live state: route, next trains on each leg, and ride time. */
data class LegArrival(val line: String, val arrival: Arrival)

data class LegStatus(val leg: Leg, val arrivals: List<LegArrival>, val minutes: Int?)

/**
 * Time between arriving at a transfer station and the connecting train leaving:
 * more than 5 min = take your time, 3–5 = hurry, under 3 = run, none catchable = miss.
 */
enum class Verdict {
    EASY, WALK_FAST, RUN, MISS;

    companion object {
        fun of(slackMinutes: Int?): Verdict = when {
            slackMinutes == null -> MISS
            slackMinutes > 5 -> EASY
            slackMinutes >= 3 -> WALK_FAST
            else -> RUN
        }
    }
}

data class Connection(
    /** Minutes until your (incoming) train reaches the transfer station. */
    val inMinutes: Int,
    /** Minutes until the connecting train leaves, or null if none is predicted yet. */
    val outMinutes: Int?,
    val outLine: String?,
    val verdict: Verdict,
    /** The next connecting train after that one, as a fallback. */
    val backupMinutes: Int?,
)

data class TransferStatus(
    val stationName: String,
    /** Different platform codes = a different level (e.g. Metro Center A01 / C01). */
    val changeLevels: Boolean,
    val walkMinutes: Int,
    val fromLine: String,
    val toLine: String,
    val toToward: String,
    val connections: List<Connection>,
)

data class PlanStatus(
    val from: StationInfo?,
    val to: StationInfo?,
    val legs: List<LegStatus>,
    val ride: RideInfo?,
    /** Shown instead of a route, e.g. while station data is loading. */
    val message: String?,
    /** Live connection info for each transfer, in order. */
    val connections: List<TransferStatus> = emptyList(),
    /** WMATA alerts (track work, delays, lines ending early) on the lines this route uses. */
    val alerts: List<Incident> = emptyList(),
) {
    val transfers: Int get() = (legs.size - 1).coerceAtLeast(0)
}

data class TripStatus(
    val trip: Trip,
    val arrivals: List<Arrival>,
    val location: TripLocation?,
    val ride: RideInfo? = null,
)

data class CurrentWeather(
    val temp: Double,
    val feelsLike: Double,
    val code: Int,
    val isDay: Boolean,
    val wind: Double,
    val humidity: Int,
)

data class HourlyPoint(
    val time: LocalDateTime,
    val temp: Double,
    val code: Int,
    val precipChance: Int,
    val isDay: Boolean,
)

data class DailyPoint(
    val date: LocalDate,
    val code: Int,
    val high: Double,
    val low: Double,
    val precipChance: Int,
)

data class Weather(
    val locationName: String,
    val current: CurrentWeather,
    val hourly: List<HourlyPoint>,
    val daily: List<DailyPoint>,
    val windUnit: String,
)

data class Place(val name: String, val detail: String, val lat: Double, val lon: Double)

/** Everything the UI or widget needs, built from cached API responses. */
data class Snapshot(
    val weather: Weather?,
    val weatherUpdatedAt: Long,
    val trips: List<TripStatus>,
    val trainsUpdatedAt: Long,
    val trainsStale: Boolean,
    val hasApiKey: Boolean,
    val showTrainLocation: Boolean,
    val plan: PlanStatus? = null,
    /** Routes saved from the planner (★). */
    val savedRoutes: List<PlanStatus> = emptyList(),
    /** Current WMATA service alerts (refreshed every few minutes). */
    val incidents: List<Incident> = emptyList(),
)
