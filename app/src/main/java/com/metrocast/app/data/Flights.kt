package com.metrocast.app.data

import org.json.JSONObject
import java.net.URLEncoder
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId

/** The two airports Metro goes to. */
enum class Airport(val code: String, val title: String, val metroCode: String, val walkMin: Int, val note: String) {
    DCA("DCA", "Reagan National", "C10", 6,
        "Covered walkways from the Metro platform: Terminal 2 (gates 10–59) about 5 minutes, Terminal 1 (gates 1–9) about 10."),
    IAD("IAD", "Dulles", "N10", 10,
        "Covered walkway from the Metro station to the main terminal, about 8–10 minutes. Then security and the AeroTrain to your gates."),
}

/** Live flight tracking services (each needs its own key, typed into Settings). */
enum class FlightProvider(val id: String, val title: String, val about: String, val signUp: String) {
    AVIATIONSTACK("aviationstack", "AviationStack",
        "Free plan: about 100 lookups a month, so the flight is checked every 15 minutes while this screen is open.",
        "https://aviationstack.com/signup/free"),
    FLIGHTAWARE("flightaware", "FlightAware AeroAPI",
        "Paid per lookup (a fraction of a cent each); checked every 5 minutes while this screen is open.",
        "https://www.flightaware.com/aeroapi/portal/"),
    ;

    val refreshMinutes: Int get() = if (this == AVIATIONSTACK) 15 else 5

    companion object {
        /** The ones this copy of the app includes (set when it's built). */
        val available: List<FlightProvider>
            get() = com.metrocast.app.BuildConfig.FLIGHT_PROVIDERS.split(',').mapNotNull { p -> entries.firstOrNull { it.id == p.trim() } }
    }
}

/** A flight's departure, as the tracking service reports it. */
data class FlightStatus(
    /** "AA 1234". */
    val flight: String,
    val airline: String,
    /** Where it leaves from ("DCA", "IAD", or somewhere else if the number is wrong). */
    val from: String,
    /** Where it's going, e.g. "Chicago (ORD)". */
    val to: String,
    val scheduled: LocalDateTime,
    /** The latest estimate (or the actual time once it's left). */
    val estimated: LocalDateTime?,
    val terminal: String?,
    val gate: String?,
    val status: String,
    val canceled: Boolean,
) {
    val delayMin: Int get() = estimated?.let { java.time.Duration.between(scheduled, it).toMinutes().toInt() } ?: 0
    val statusText: String get() = when {
        canceled -> "Canceled"
        delayMin >= 5 -> "Delayed $delayMin min"
        else -> status.ifBlank { "On time" }
    }
}

/** Departure plan: when to leave your Metro station for your flight. */
data class FlightPlan(
    val airport: Airport,
    val departs: LocalDateTime,
    val metroMin: Int,
    val estimated: Boolean,
    val waitMin: Int,
    val walkMin: Int,
    /** Being at the airport before the flight (security, bags, boarding). */
    val earlyMin: Int,
) {
    val totalMin: Int get() = metroMin + waitMin + walkMin + earlyMin
    val leaveBy: LocalDateTime get() = departs.minusMinutes(totalMin.toLong())
}

object Flights {
    val ZONE: ZoneId = ZoneId.of("America/New_York")

    /** Airlines at Reagan and Dulles (name → code), for picking by name. */
    val AIRLINES: List<Pair<String, String>> = listOf(
        "American" to "AA", "United" to "UA", "Delta" to "DL", "Southwest" to "WN", "JetBlue" to "B6",
        "Alaska" to "AS", "Spirit" to "NK", "Frontier" to "F9", "Sun Country" to "SY", "Breeze" to "MX",
        "Air Canada" to "AC", "WestJet" to "WS", "British Airways" to "BA", "Virgin Atlantic" to "VS",
        "Lufthansa" to "LH", "Air France" to "AF", "KLM" to "KL", "SWISS" to "LX", "Austrian" to "OS",
        "Brussels Airlines" to "SN", "Iberia" to "IB", "Aer Lingus" to "EI", "Icelandair" to "FI", "SAS" to "SK",
        "TAP Air Portugal" to "TP", "Turkish Airlines" to "TK", "Emirates" to "EK", "Qatar Airways" to "QR",
        "Etihad" to "EY", "Saudia" to "SV", "Ethiopian" to "ET", "EgyptAir" to "MS", "Royal Air Maroc" to "AT",
        "ANA" to "NH", "Korean Air" to "KE", "Avianca" to "AV", "Copa" to "CM", "Aeromexico" to "AM",
        "Volaris" to "Y4", "Air India" to "AI", "Porter" to "PD",
    )

    /** "American", "aa", "AA1234" → "AA". Null when it isn't a known airline or a 2-character code. */
    fun airlineCode(text: String): String? {
        val t = text.trim()
        if (t.isEmpty()) return null
        AIRLINES.firstOrNull { it.first.equals(t, ignoreCase = true) || it.second.equals(t, ignoreCase = true) }?.let { return it.second }
        AIRLINES.firstOrNull { it.first.startsWith(t, ignoreCase = true) }?.let { return it.second }
        return t.uppercase().takeIf { it.length == 2 && it.all { c -> c.isLetterOrDigit() } }
    }

    fun airlineName(code: String): String = AIRLINES.firstOrNull { it.second == code }?.first ?: code

    /** Airline + number, tidied: ("American", " 1234") → "AA1234". A number typed as "AA 1234" works too. */
    fun designator(airline: String, number: String): String? {
        val n = number.replace(" ", "").uppercase()
        val inline = Regex("^([A-Z0-9]{2})(\\d{1,4})$").find(n)
        if (inline != null && n.first().isLetter()) return inline.groupValues[1] + inline.groupValues[2].trimStart('0').ifEmpty { "0" }
        val code = airlineCode(airline) ?: return null
        val digits = n.filter { it.isDigit() }.takeIf { it.isNotEmpty() && it.length <= 4 } ?: return null
        return code + digits.trimStart('0').ifEmpty { "0" }
    }

    /**
     * A typed time: "1:05", "105", "1.05", "13:05", "1305", "1" → with AM/PM ([pm] true/false) on a
     * 12-hour clock, or as it is on a 24-hour clock ([pm] null). Null when it isn't a real time.
     */
    private val TIME = Regex("^(\\d{1,2})(?::?(\\d{2}))?$")

    /** The hour part of a typed time ("105" → 1, "1045" → 10, "7" → 7), or null. */
    fun typedHour(text: String): Int? = TIME.find(text.trim().replace('.', ':').replace(" ", ""))?.groupValues?.get(1)?.toInt()

    fun parseTime(text: String, pm: Boolean?): java.time.LocalTime? {
        val t = text.trim().replace('.', ':').replace(" ", "")
        val m = TIME.find(t) ?: return null
        val h = m.groupValues[1].toInt(); val min = m.groupValues[2].ifEmpty { "0" }.toInt()
        if (min > 59) return null
        val hour = if (pm == null) h.takeIf { it in 0..23 } ?: return null
        else {
            if (h !in 1..12) return null
            (h % 12) + if (pm) 12 else 0
        }
        return java.time.LocalTime.of(hour, min)
    }

    /**
     * AM or PM for a typed hour (1–12), until you pick one: today, the next time that hour comes round;
     * another day, morning for 5–11 and afternoon/evening otherwise. True = PM.
     */
    fun guessPm(hour12: Int, day: java.time.LocalDate, now: LocalDateTime): Boolean {
        val h = hour12 % 12
        if (day == now.toLocalDate()) {
            val am = day.atTime(h, 0); val pm = day.atTime(h + 12, 0)
            return when { now.isBefore(am.plusMinutes(59)) -> false; now.isBefore(pm.plusMinutes(59)) -> true; else -> true }
        }
        return hour12 !in 5..11
    }

    /** When to leave [startCode] to make a flight at [departs]. */
    fun plan(
        airport: Airport, departs: LocalDateTime, startCode: String, earlyMin: Int,
        metroMinutes: (from: String, to: String) -> Pair<Int, Boolean>?,
    ): FlightPlan? {
        val here = StationDirectory.find(startCode)?.codes?.contains(airport.metroCode) == true || startCode == airport.metroCode
        val (ride, est) = if (here) 0 to false else metroMinutes(startCode, airport.metroCode) ?: return null
        return FlightPlan(airport, departs, ride, est, if (here) 0 else 5, airport.walkMin, earlyMin)
    }

    // ------------------------------------------------------------------ AviationStack

    fun aviationStackUrl(key: String, designator: String, https: Boolean = true): String =
        (if (https) "https" else "http") + "://api.aviationstack.com/v1/flights?access_key=" +
            URLEncoder.encode(key, "UTF-8") + "&flight_iata=" + URLEncoder.encode(designator, "UTF-8")

    /** The free plan only answers plain http; it says so with this error. */
    fun needsHttp(json: String): Boolean = json.contains("https_access_restricted")

    /** AviationStack's error, in words, or null if the answer is fine. */
    fun aviationStackError(json: String): String? = runCatching {
        val e = JSONObject(json).optJSONObject("error") ?: return@runCatching null
        when (e.optString("code")) {
            "invalid_access_key", "missing_access_key" -> "AviationStack didn't accept the key — check it in Settings."
            "usage_limit_reached" -> "This month's AviationStack lookups are used up."
            else -> e.optString("message").ifBlank { "AviationStack couldn't look that up." }
        }
    }.getOrNull()

    /**
     * The flight's next departure from Reagan or Dulles (or its next departure from anywhere if it doesn't
     * fly from either). AviationStack gives airport-local times with a "+00:00" ending, so the clock time is
     * used as it is.
     */
    fun parseAviationStack(json: String, now: LocalDateTime): FlightStatus? = runCatching {
        val arr = JSONObject(json).optJSONArray("data") ?: return@runCatching null
        val all = (0 until arr.length()).mapNotNull { i ->
            runCatching {
                val o = arr.getJSONObject(i)
                val dep = o.getJSONObject("departure"); val arrv = o.optJSONObject("arrival")
                fun local(s: String?) = s?.takeIf { it.isNotBlank() && it != "null" }?.let { LocalDateTime.parse(it.take(19)) }
                val sched = local(dep.str("scheduled")) ?: return@runCatching null
                val est = local(dep.str("actual")) ?: local(dep.str("estimated"))
                    ?: dep.optInt("delay", 0).takeIf { it > 0 }?.let { sched.plusMinutes(it.toLong()) }
                val st = o.optString("flight_status")
                FlightStatus(
                    flight = o.optJSONObject("flight")?.str("iata")?.let { pretty(it) } ?: "",
                    airline = o.optJSONObject("airline")?.str("name") ?: "",
                    from = dep.str("iata") ?: "",
                    to = arrv?.let { a -> listOfNotNull(a.str("airport"), a.str("iata")?.let { "($it)" }).joinToString(" ") }.orEmpty(),
                    scheduled = sched, estimated = est?.takeIf { it != sched },
                    terminal = dep.str("terminal"), gate = dep.str("gate"),
                    status = when (st) { "scheduled" -> "Scheduled"; "active" -> "In the air"; "landed" -> "Landed"; "diverted" -> "Diverted"; "incident" -> "Incident"; else -> st.replaceFirstChar { it.uppercase() } },
                    canceled = st == "cancelled",
                )
            }.getOrNull()
        }
        pick(all, now)
    }.getOrNull()

    // ------------------------------------------------------------------ FlightAware AeroAPI

    fun flightAwareUrl(designator: String): String =
        "https://aeroapi.flightaware.com/aeroapi/flights/" + URLEncoder.encode(designator, "UTF-8") + "?ident_type=designator"

    fun parseFlightAware(json: String, now: LocalDateTime): FlightStatus? = runCatching {
        val arr = JSONObject(json).optJSONArray("flights") ?: return@runCatching null
        fun local(s: String?) = s?.takeIf { it.isNotBlank() && it != "null" }?.let { OffsetDateTime.parse(it).atZoneSameInstant(ZONE).toLocalDateTime() }
        val all = (0 until arr.length()).mapNotNull { i ->
            runCatching {
                val o = arr.getJSONObject(i)
                val origin = o.optJSONObject("origin"); val dest = o.optJSONObject("destination")
                val sched = local(o.str("scheduled_out")) ?: return@runCatching null
                val est = local(o.str("actual_out")) ?: local(o.str("estimated_out"))
                FlightStatus(
                    flight = o.str("ident_iata")?.let { pretty(it) } ?: o.optString("ident"),
                    airline = o.str("operator_iata")?.let { airlineName(it) } ?: "",
                    from = origin?.str("code_iata") ?: "",
                    to = dest?.let { d -> listOfNotNull(d.str("city") ?: d.str("name"), d.str("code_iata")?.let { "($it)" }).joinToString(" ") }.orEmpty(),
                    scheduled = sched, estimated = est?.takeIf { it != sched },
                    terminal = o.str("terminal_origin"), gate = o.str("gate_origin"),
                    status = o.optString("status"),
                    canceled = o.optBoolean("cancelled"),
                )
            }.getOrNull()
        }
        pick(all, now)
    }.getOrNull()

    /** The run of the flight that matters: next from DCA/IAD (from a few hours ago on), else the next one at all. */
    private fun pick(all: List<FlightStatus>, now: LocalDateTime): FlightStatus? {
        val recent = all.filter { (it.estimated ?: it.scheduled).isAfter(now.minusHours(3)) }.sortedBy { it.scheduled }
        return recent.firstOrNull { it.from in setOf("DCA", "IAD") } ?: recent.firstOrNull() ?: all.maxByOrNull { it.scheduled }
    }

    /** "AA1234" → "AA 1234". */
    fun pretty(d: String): String = Regex("^([A-Z0-9]{2})(\\d+)$").find(d)?.let { "${it.groupValues[1]} ${it.groupValues[2]}" } ?: d

    fun airportOf(code: String): Airport? = Airport.entries.firstOrNull { it.code == code }
}
