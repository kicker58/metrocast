package com.metrocast.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.zip.ZipInputStream

/** The trains Metro connects to. */
enum class RailService(val title: String, val color: Long, val scheduleUrl: String) {
    AMTRAK("Amtrak", 0xFF1A5A96, "https://content.amtrak.com/content/gtfs/GTFS.zip"),
    MARC("MARC", 0xFF8F3A00, "https://mdotmta-gtfs.s3.amazonaws.com/mdotmta_gtfs_marc.zip"),
    VRE("VRE", 0xFF8E1B2E, "https://gtfs.vre.org/containercdngtfsupload/google_transit.zip"),
}

/** A Metro station you can catch one of those trains at. */
data class RailStation(
    val metroCode: String,
    val name: String,
    val services: Set<RailService>,
    /** Metro fare gates to the train platform, on foot. */
    val walkMin: Int,
    val note: String,
)

/** Where one train picks up at a Metro-connected station: seconds after midnight of its service day. */
data class RailStop(val metroCode: String, val dep: Int, val stopId: String)

/** One scheduled train (one run of it: a train number can have a weekday and a weekend run). */
data class RailTrain(
    val service: RailService,
    val number: String,
    val tripId: String,
    /** "Northeast Regional", "Penn Line", "Fredericksburg Line". */
    val route: String,
    /** Where it ends, e.g. "Boston". */
    val headsign: String,
    /** Monday..Sunday, "1111100" = weekdays. */
    val days: String,
    val start: Int,
    val end: Int,
    val added: Set<Int>,
    val removed: Set<Int>,
    /** Metro-area pickups, in train order. */
    val stops: List<RailStop>,
    /** Stops after the DC area, lower case, for searching ("new york penn station · ..."). */
    val via: String,
) {
    val title: String get() = "${service.title} $number"

    fun runsOn(date: LocalDate): Boolean {
        val d = date.year * 10000 + date.monthValue * 100 + date.dayOfMonth
        if (d in removed) return false
        if (d in added) return true
        return d in start..end && days.getOrNull(date.dayOfWeek.value - 1) == '1'
    }

    fun matches(q: String): Boolean {
        val s = q.trim().lowercase()
        if (s.isEmpty()) return true
        return number == s || number.startsWith(s) || route.lowercase().contains(s) ||
            headsign.lowercase().contains(s) || via.contains(s) || service.title.lowercase() == s
    }
}

/** A train on a given day, with its first Metro-area pickup. */
data class RailRun(val train: RailTrain, val serviceDate: LocalDate) {
    fun timeAt(stop: RailStop): LocalDateTime = serviceDate.atStartOfDay().plusSeconds(stop.dep.toLong())
    val first: LocalDateTime get() = timeAt(train.stops.first())
}

/** One way to catch the train: which Metro station to go to, and when to leave yours. */
data class RailOption(
    val station: RailStation,
    val stop: RailStop,
    val trainLeaves: LocalDateTime,
    /** Riding Metro from your station (0 when you start there). */
    val metroMin: Int,
    /** True when WMATA's ride time wasn't available and it's worked out from the number of stops. */
    val estimated: Boolean,
    /** Allowance for waiting on the platform for a Metro train. */
    val waitMin: Int,
    val walkMin: Int,
    /** Being there before it leaves (Amtrak boarding, MARC/VRE platform). */
    val earlyMin: Int,
) {
    val totalMin: Int get() = metroMin + waitMin + walkMin + earlyMin
    val leaveBy: LocalDateTime get() = trainLeaves.minusMinutes(totalMin.toLong())
}

/** Live: is the train on time? */
data class RailLive(val delayMin: Int?, val canceled: Boolean = false, val note: String? = null) {
    val text: String get() = when {
        canceled -> "Canceled"
        delayMin == null -> note ?: "No live update yet"
        delayMin >= 2 -> "$delayMin min late"
        delayMin <= -2 -> "${-delayMin} min early"
        else -> "On time"
    }
    val late: Boolean get() = canceled || (delayMin ?: 0) >= 5
}

object Rail {
    val ZONE: ZoneId = ZoneId.of("America/New_York")

    val STATIONS = listOf(
        RailStation("B03", "Union Station", setOf(RailService.AMTRAK, RailService.MARC, RailService.VRE), 6,
            "Trains leave from the main hall, upstairs from Metro (First St / Union Station exit)."),
        RailStation("D13", "New Carrollton", setOf(RailService.AMTRAK, RailService.MARC), 3,
            "Amtrak and MARC (Penn Line) platform is right next to Metro."),
        RailStation("E09", "College Park-U of Md", setOf(RailService.MARC), 4,
            "MARC Camden Line platform, a short walk from the Metro entrance."),
        RailStation("E10", "Greenbelt", setOf(RailService.MARC), 3,
            "MARC Camden Line platform beside the Metro."),
        RailStation("B08", "Silver Spring", setOf(RailService.MARC), 3,
            "MARC Brunswick Line platform beside the transit center."),
        RailStation("A14", "Rockville", setOf(RailService.MARC, RailService.AMTRAK), 2,
            "MARC and Amtrak (Capitol Limited) platform next to Metro."),
        RailStation("D03", "L'Enfant Plaza", setOf(RailService.VRE), 6,
            "VRE platform: take the 7th St & Maryland Ave exit, then Virginia Ave SW."),
        RailStation("C09", "Crystal City", setOf(RailService.VRE), 7,
            "VRE station is a few blocks south, on Crystal Dr."),
        RailStation("C13", "King St-Old Town", setOf(RailService.AMTRAK, RailService.VRE), 3,
            "Alexandria Union Station (Amtrak and VRE) is across the street."),
        RailStation("J03", "Franconia-Springfield", setOf(RailService.VRE), 3,
            "VRE Fredericksburg Line platform beside Metro."),
    )

    fun station(code: String): RailStation? = STATIONS.firstOrNull { it.metroCode == code }

    /** Amtrak's own station codes, for its live status. */
    private val AMTRAK_CODES = mapOf("WAS" to "B03", "NCR" to "D13", "ALX" to "C13", "RKV" to "A14")

    /** Which Metro station a schedule stop belongs to (null = not one Metro reaches). */
    fun metroFor(service: RailService, stopId: String, name: String): String? {
        val n = name.uppercase()
        return when (service) {
            RailService.AMTRAK -> AMTRAK_CODES[stopId]
            RailService.MARC -> when {
                n.startsWith("UNION STATION") -> "B03"
                n.startsWith("NEW CARROLLTON") -> "D13"
                n.startsWith("COLLEGE PARK") -> "E09"
                n.startsWith("GREENBELT") -> "E10"
                n.startsWith("SILVER SPRING") -> "B08"
                n.startsWith("ROCKVILLE") -> "A14"
                else -> null
            }
            RailService.VRE -> when (n) {
                "UNION STATION" -> "B03"
                "L ENFANT", "L'ENFANT" -> "D03"
                "CRYSTAL CITY" -> "C09"
                "ALEXANDRIA" -> "C13"
                "FRANCONIA / SPRINGFIELD", "FRANCONIA-SPRINGFIELD" -> "J03"
                else -> null
            }
        }
    }

    // ------------------------------------------------------------------ trains today

    /** Trains with a Metro-area pickup between [now] and [hours] from now, soonest first. */
    fun upcoming(trains: List<RailTrain>, now: LocalDateTime, hours: Long = 24): List<RailRun> {
        val until = now.plusHours(hours)
        val days = listOf(now.toLocalDate().minusDays(1), now.toLocalDate(), now.toLocalDate().plusDays(1))
        return trains.flatMap { t -> days.filter { t.runsOn(it) }.map { RailRun(t, it) } }
            .filter { r -> r.train.stops.any { s -> r.timeAt(s).let { !it.isBefore(now) && it.isBefore(until) } } }
            // One run per train and day (feeds sometimes list the same train twice).
            .distinctBy { "${it.train.service}|${it.train.number}|${it.first}" }
            .sortedBy { r -> r.train.stops.map { r.timeAt(it) }.filter { !it.isBefore(now) }.minOrNull() ?: r.first }
    }

    /**
     * Every Metro station where you can catch [run], from [startCode], latest leave-by first (that's
     * the best one: the most time before you have to go). [metroMinutes] is the ride time between two
     * stations (and whether it's an estimate), or null when there's no Metro route.
     */
    fun options(
        run: RailRun,
        startCode: String,
        now: LocalDateTime,
        amtrakEarlyMin: Int,
        metroMinutes: (from: String, to: String) -> Pair<Int, Boolean>?,
    ): List<RailOption> {
        val start = StationDirectory.find(startCode)?.codes ?: listOf(startCode)
        return run.train.stops.mapNotNull { stop ->
            val station = station(stop.metroCode) ?: return@mapNotNull null
            val leaves = run.timeAt(stop)
            if (leaves.isBefore(now)) return@mapNotNull null
            val here = stop.metroCode in start || StationDirectory.find(stop.metroCode)?.codes?.any { it in start } == true
            val (ride, est) = if (here) 0 to false else metroMinutes(startCode, stop.metroCode) ?: return@mapNotNull null
            RailOption(
                station = station, stop = stop, trainLeaves = leaves,
                metroMin = ride, estimated = est,
                waitMin = if (here) 0 else 5,
                walkMin = station.walkMin,
                earlyMin = if (run.train.service == RailService.AMTRAK) amtrakEarlyMin else 5,
            )
        }.sortedByDescending { it.leaveBy }
    }

    // ------------------------------------------------------------------ saving

    fun toJson(trains: List<RailTrain>): String = JSONArray().apply {
        trains.forEach { t ->
            put(JSONObject().apply {
                put("s", t.service.name); put("n", t.number); put("t", t.tripId); put("r", t.route); put("h", t.headsign)
                put("d", t.days); put("a", t.start); put("e", t.end)
                put("add", JSONArray(t.added.sorted())); put("rm", JSONArray(t.removed.sorted()))
                put("st", JSONArray().apply { t.stops.forEach { put(JSONArray().put(it.metroCode).put(it.dep).put(it.stopId)) } })
                put("v", t.via)
            })
        }
    }.toString()

    fun fromJson(raw: String): List<RailTrain> = runCatching {
        val arr = JSONArray(raw)
        (0 until arr.length()).mapNotNull { i ->
            runCatching {
                val o = arr.getJSONObject(i)
                fun ints(name: String) = o.optJSONArray(name)?.let { a -> (0 until a.length()).map { a.getInt(it) }.toSet() }.orEmpty()
                val st = o.getJSONArray("st")
                RailTrain(
                    service = RailService.valueOf(o.getString("s")), number = o.getString("n"), tripId = o.getString("t"),
                    route = o.optString("r"), headsign = o.optString("h"), days = o.optString("d", "0000000"),
                    start = o.optInt("a"), end = o.optInt("e"), added = ints("add"), removed = ints("rm"),
                    stops = (0 until st.length()).map { j -> st.getJSONArray(j).let { RailStop(it.getString(0), it.getInt(1), it.getString(2)) } },
                    via = o.optString("v"),
                ).takeIf { it.stops.isNotEmpty() }
            }.getOrNull()
        }
    }.getOrDefault(emptyList())

    // ------------------------------------------------------------------ live status

    const val MARC_LIVE = "https://mdotmta-gtfs-rt.s3.amazonaws.com/MARC%20RT/marc-tu.pb"
    const val VRE_LIVE = "https://gtfs.vre.org/containercdngtfsupload/TripUpdateFeed"
    /** How often to ask for live status: Amtrak's (via a free community service) once a minute, the others at your refresh speed. */
    fun liveRefreshSeconds(service: RailService, refreshSeconds: Int): Int =
        if (service == RailService.AMTRAK) 60 else refreshSeconds.coerceAtLeast(30)

    /** Amtraker: a free community service that relays Amtrak's own live train status. */
    const val AMTRAKER = "https://api-v3.amtraker.com/v3/trains/"

    /** MARC / VRE live feed (GTFS-realtime): this train's delay at [stop]. */
    fun liveFromFeed(bytes: ByteArray, run: RailRun, stop: RailStop): RailLive {
        val updates = GtfsRealtime.tripUpdates(bytes)
        val u = updates.firstOrNull { it.tripId == run.train.tripId }
            ?: updates.firstOrNull { it.tripId.substringBefore('-') == run.train.number || it.tripId == "Train${run.train.number}" }
            ?: return RailLive(null, note = "No live update yet — it usually starts once the train is running")
        if (u.canceled) return RailLive(null, canceled = true)
        val scheduled = run.timeAt(stop).atZone(ZONE).toEpochSecond()
        val atStop = u.stops.firstOrNull { it.stopId == stop.stopId }
        val delaySec = atStop?.let { s -> s.delay ?: s.time?.let { it - scheduled }?.toInt() }
            ?: u.delay ?: u.stops.firstOrNull()?.delay
        if (atStop?.skipped == true) return RailLive(null, canceled = true, note = "Skipping this stop")
        return RailLive(delaySec?.let { Math.round(it / 60.0).toInt() })
    }

    /** Amtraker's answer for one train number: the delay at [stop] for the run on [run]'s day. */
    fun liveFromAmtraker(json: String, run: RailRun, stop: RailStop): RailLive = runCatching {
        val root = JSONObject(json)
        val list = root.optJSONArray(run.train.number) ?: return@runCatching RailLive(null, note = "Not running yet — live status starts when it leaves its first station")
        val want = run.timeAt(stop)
        for (i in 0 until list.length()) {
            val t = list.getJSONObject(i)
            val st = t.optJSONArray("stations") ?: continue
            for (j in 0 until st.length()) {
                val s = st.getJSONObject(j)
                if (s.optString("code") != stop.stopId) continue
                val sch = s.optString("schDep").ifBlank { s.optString("schArr") }.takeIf { it.isNotBlank() } ?: continue
                val schT = OffsetDateTime.parse(sch).atZoneSameInstant(ZONE).toLocalDateTime()
                if (Duration.between(schT, want).abs().toMinutes() > 90) continue // another day's run
                val est = s.optString("dep").ifBlank { s.optString("arr") }.takeIf { it.isNotBlank() }
                    ?.let { OffsetDateTime.parse(it).atZoneSameInstant(ZONE).toLocalDateTime() }
                val delay = est?.let { Duration.between(schT, it).toMinutes().toInt() }
                val status = s.optString("status")
                val note = if (status == "Departed") "Already left ${stop.metroCode.let { station(it)?.name ?: it }}" else null
                return@runCatching RailLive(delay, note = note)
            }
        }
        RailLive(null, note = "Not running yet — live status starts when it leaves its first station")
    }.getOrDefault(RailLive(null))

    // ------------------------------------------------------------------ reading a schedule (GTFS)

    /** The Metro-area trains in one operator's published schedule (a GTFS zip). */
    fun parseGtfs(service: RailService, zip: ByteArray): List<RailTrain> =
        runCatching { parseGtfsOrThrow(service, zip) }.getOrDefault(emptyList()) // a cut-off or broken download: nothing

    private fun parseGtfsOrThrow(service: RailService, zip: ByteArray): List<RailTrain> {
        val files = HashMap<String, String>()
        val wanted = setOf("stops.txt", "routes.txt", "trips.txt", "stop_times.txt", "calendar.txt", "calendar_dates.txt")
        ZipInputStream(zip.inputStream()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                val name = e.name.substringAfterLast('/')
                if (name in wanted) files[name] = z.readBytes().toString(Charsets.UTF_8).removePrefix("\uFEFF")
            }
        }
        val stops = csv(files["stops.txt"].orEmpty()).associateBy { it["stop_id"].orEmpty() }
        fun stopName(id: String): String {
            val s = stops[id] ?: return ""
            val parent = s["parent_station"].orEmpty()
            val n = s["stop_name"].orEmpty()
            // VRE names its platforms ("1", "22", "Lead"); the station's name is on the parent.
            return stops[parent]?.get("stop_name")?.takeIf { parent.isNotBlank() && it.isNotBlank() } ?: n
        }
        val metro = HashMap<String, String?>()
        fun metroOf(id: String) = metro.getOrPut(id) { metroFor(service, id, stopName(id)) }

        val routes = csv(files["routes.txt"].orEmpty()).associateBy { it["route_id"].orEmpty() }
        val trips = csv(files["trips.txt"].orEmpty()).associateBy { it["trip_id"].orEmpty() }
        val cal = csv(files["calendar.txt"].orEmpty()).associateBy { it["service_id"].orEmpty() }
        val added = HashMap<String, MutableSet<Int>>(); val removed = HashMap<String, MutableSet<Int>>()
        csv(files["calendar_dates.txt"].orEmpty()).forEach { r ->
            val d = r["date"]?.toIntOrNull() ?: return@forEach
            val sid = r["service_id"].orEmpty()
            if (r["exception_type"] == "1") added.getOrPut(sid) { HashSet() }.add(d) else removed.getOrPut(sid) { HashSet() }.add(d)
        }

        data class Row(val stopId: String, val seq: Int, val dep: Int, val pickup: Boolean)
        val byTrip = HashMap<String, MutableList<Row>>()
        csv(files["stop_times.txt"].orEmpty()).forEach { r ->
            val trip = r["trip_id"] ?: return@forEach
            val dep = secs(r["departure_time"].orEmpty().ifBlank { r["arrival_time"].orEmpty() }) ?: return@forEach
            byTrip.getOrPut(trip) { ArrayList() } += Row(r["stop_id"].orEmpty(), r["stop_sequence"]?.toIntOrNull() ?: 0, dep, r["pickup_type"] != "1")
        }

        return byTrip.mapNotNull { (tripId, rows0) ->
            val trip = trips[tripId] ?: return@mapNotNull null
            val route = routes[trip["route_id"].orEmpty()]
            if (route?.get("route_type") == "3") return@mapNotNull null // Amtrak Thruway buses
            // Amtrak's file also lists MARC trains its crews run ("Commuter Rail"): they're in MARC's own schedule.
            if (service == RailService.AMTRAK && route?.get("route_long_name").orEmpty().contains("Commuter", ignoreCase = true)) return@mapNotNull null
            val rows = rows0.sortedBy { it.seq }
            val last = rows.lastOrNull() ?: return@mapNotNull null
            val dc = rows.filter { it !== last && it.pickup && metroOf(it.stopId) != null }
                .distinctBy { metroOf(it.stopId) }
                .map { RailStop(metroOf(it.stopId)!!, it.dep, it.stopId) }
            if (dc.isEmpty()) return@mapNotNull null
            val firstDc = rows.indexOfFirst { it.stopId == dc.first().stopId }
            val via = rows.drop(firstDc + 1).map { clean(stopName(it.stopId)) }.filter { it.isNotBlank() }.distinct()
            val sid = trip["service_id"].orEmpty()
            val c = cal[sid]
            val days = if (c == null) "0000000" else listOf("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday").joinToString("") { if (c[it] == "1") "1" else "0" }
            RailTrain(
                service = service,
                number = trip["trip_short_name"].orEmpty().removePrefix("Train").trim().ifBlank { tripId.removePrefix("Train").substringBefore('-') },
                tripId = tripId,
                route = routeName(service, route),
                headsign = trip["trip_headsign"].orEmpty().takeIf { it.isNotBlank() }?.let { titleCase(clean(it)) } ?: clean(stopName(last.stopId)),
                days = days,
                start = c?.get("start_date")?.toIntOrNull() ?: 0,
                end = c?.get("end_date")?.toIntOrNull() ?: 0,
                added = added[sid].orEmpty(), removed = removed[sid].orEmpty(),
                stops = dc,
                via = via.joinToString(" · ").lowercase(),
            )
        }.sortedWith(compareBy({ it.service }, { it.stops.first().dep }))
    }

    private fun routeName(service: RailService, r: Map<String, String>?): String {
        val long = r?.get("route_long_name").orEmpty(); val short = r?.get("route_short_name").orEmpty()
        return when (service) {
            // "PENN - WASHINGTON" → "Penn Line"
            RailService.MARC -> long.substringBefore(" -").trim().let { if (it.isBlank()) "MARC" else titleCase(it) + " Line" }
            else -> long.ifBlank { short }.ifBlank { service.title }
        }
    }

    /** "PENN STATION MARC nb" → "Penn Station". */
    private fun clean(name: String) = titleCase(name.replace(Regex("\\s+MARC.*$"), "").trim())

    private fun titleCase(s: String) = if (s.any { it.isLowerCase() }) s else
        s.lowercase().split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }

    /** "13:05:00" (or "25:10:00" after midnight) → seconds. */
    fun secs(t: String): Int? {
        val p = t.trim().split(':'); if (p.size < 2) return null
        val h = p[0].toIntOrNull() ?: return null; val m = p[1].toIntOrNull() ?: return null
        return h * 3600 + m * 60 + (p.getOrNull(2)?.toIntOrNull() ?: 0)
    }

    /** A small CSV reader (quoted fields, commas inside quotes). */
    fun csv(text: String): List<Map<String, String>> {
        val lines = text.lineSequence().filter { it.isNotBlank() }.iterator()
        if (!lines.hasNext()) return emptyList()
        val head = split(lines.next()).map { it.trim() }
        val out = ArrayList<Map<String, String>>()
        while (lines.hasNext()) {
            val f = split(lines.next())
            out += HashMap<String, String>(head.size * 2).apply { head.forEachIndexed { i, h -> put(h, f.getOrElse(i) { "" }.trim()) } }
        }
        return out
    }

    private fun split(line: String): List<String> {
        if ('"' !in line) return line.trimEnd('\r').split(',')
        val out = ArrayList<String>(); val sb = StringBuilder(); var q = false; var i = 0
        val l = line.trimEnd('\r')
        while (i < l.length) {
            val c = l[i]
            when {
                q && c == '"' && l.getOrNull(i + 1) == '"' -> { sb.append('"'); i++ }
                c == '"' -> q = !q
                c == ',' && !q -> { out += sb.toString(); sb.setLength(0) }
                else -> sb.append(c)
            }
            i++
        }
        out += sb.toString()
        return out
    }
}

/**
 * Just enough of the GTFS-realtime format (protocol buffers) to read MARC's and VRE's live trip
 * updates: which trains are late, by how much, and which are canceled.
 */
object GtfsRealtime {
    data class StopUpdate(val stopId: String, val delay: Int?, val time: Long?, val skipped: Boolean)
    data class TripUpdate(val tripId: String, val canceled: Boolean, val delay: Int?, val stops: List<StopUpdate>)

    fun tripUpdates(bytes: ByteArray): List<TripUpdate> = runCatching<List<TripUpdate>> {
        val out = ArrayList<TripUpdate>()
        Reader(bytes, 0, bytes.size).fields { f, r -> if (f == 2) r.message()?.fields { ef, er -> if (ef == 3) er.message()?.let { out += tripUpdate(it) } ?: er.skip() else er.skip() } ?: r.skip() else r.skip() }
        out
    }.getOrDefault(emptyList())

    private fun tripUpdate(m: Reader): TripUpdate {
        var tripId = ""; var canceled = false; var delay: Int? = null; val stops = ArrayList<StopUpdate>()
        m.fields { f, r ->
            when (f) {
                1 -> r.message()?.fields { tf, tr ->
                    when (tf) { 1 -> tripId = tr.string(); 4 -> if (tr.varint() == 3L) canceled = true; else -> tr.skip() }
                } ?: r.skip()
                2 -> r.message()?.let { stops += stopUpdate(it) } ?: r.skip()
                5 -> delay = r.varint().toInt()
                else -> r.skip()
            }
        }
        return TripUpdate(tripId, canceled, delay, stops)
    }

    private fun stopUpdate(m: Reader): StopUpdate {
        var stopId = ""; var arrDelay: Int? = null; var arrTime: Long? = null; var depDelay: Int? = null; var depTime: Long? = null; var skipped = false
        m.fields { f, r ->
            when (f) {
                4 -> stopId = r.string()
                2 -> r.message()?.fields { ef, er -> when (ef) { 1 -> arrDelay = er.varint().toInt(); 2 -> arrTime = er.varint(); else -> er.skip() } } ?: r.skip()
                3 -> r.message()?.fields { ef, er -> when (ef) { 1 -> depDelay = er.varint().toInt(); 2 -> depTime = er.varint(); else -> er.skip() } } ?: r.skip()
                5 -> if (r.varint() == 1L) skipped = true
                else -> r.skip()
            }
        }
        return StopUpdate(stopId, depDelay ?: arrDelay, depTime ?: arrTime, skipped)
    }

    /** Protocol-buffer wire format, read-only. */
    private class Reader(val b: ByteArray, var pos: Int, val end: Int) {
        private var wire = 0
        fun fields(each: (field: Int, r: Reader) -> Unit) {
            while (pos < end) {
                val key = varint(); wire = (key and 7).toInt()
                val before = pos
                each((key ushr 3).toInt(), this)
                if (pos == before && wire != 0) skip() // nothing read: move on
            }
        }
        fun varint(): Long {
            var shift = 0; var v = 0L
            while (pos < end) {
                val x = b[pos++].toInt()
                v = v or ((x and 0x7f).toLong() shl shift)
                if (x and 0x80 == 0) return v
                shift += 7; if (shift > 63) break
            }
            return v
        }
        private fun len(): Int = varint().toInt().also { require(it >= 0 && pos + it <= end) }
        fun string(): String { val n = len(); return String(b, pos, n, Charsets.UTF_8).also { pos += n } }
        fun message(): Reader? { if (wire != 2) return null; val n = len(); return Reader(b, pos, pos + n).also { pos += n } }
        fun skip() {
            when (wire) {
                0 -> varint()
                1 -> pos += 8
                2 -> { val n = len(); pos += n }
                5 -> pos += 4
                else -> pos = end
            }
        }
    }
}
