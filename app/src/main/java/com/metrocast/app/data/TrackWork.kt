package com.metrocast.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.Month
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/**
 * WMATA's planned track work and service changes: the advisories list on wmata.com (dates, lines,
 * stations) plus the details from the weekend and weekday service pages ("Silver Line: trains run
 * Ashburn to Federal Center SW only · Expect 24 minutes between trains"). Checked against the route
 * of each of your trips, so the app can say what it means for you.
 */
object TrackWork {
    const val LIST_URL =
        "https://www.wmata.com/ride/alerts-and-advisories/_jcr_content/root/container/row-container/main-container/main-editable/alerts_advisories_li.results.json"
    private val DC: ZoneId = ZoneId.of("America/New_York")

    enum class Kind(val label: String, val service: Boolean) {
        WEEKEND("Weekend track work", true),
        WEEKDAY("Weekday service changes", true),
        MAJOR("Major construction", true),
        STATION("Station notice", false),
    }

    /** One line's change on some days, e.g. Silver, Sat–Sun Nov 21–22: "trains run Ashburn to Federal Center SW only". */
    data class Change(
        val days: String,
        val start: LocalDate?,
        val end: LocalDate?,
        val line: String,
        val text: String,
        /** The stations the change names ("Eastern Market" to "Minnesota Av"), as station codes when known. */
        val from: String? = null,
        val to: String? = null,
        /** "trains run X to Y only": the rest of the line has no trains. */
        val onlyBetween: Boolean = false,
    )

    data class Advisory(
        val id: String,
        val title: String,
        val summary: String,
        val url: String,
        val kind: Kind,
        val start: LocalDate,
        val end: LocalDate,
        val lines: List<String>,
        val stations: List<String>,
        val changes: List<Change> = emptyList(),
    ) {
        val needsDetails: Boolean get() = kind == Kind.WEEKEND || kind == Kind.WEEKDAY
    }

    enum class Level(val note: String) {
        BLOCKED("Trains won't run the whole way — plan another route"),
        IN_ZONE("Work is on your stretch of the line — expect delays"),
        LINE("On your line — expect longer waits"),
        STATION("At one of your stations"),
    }

    /** What one advisory (or one change in it) means for one of your trips. */
    data class Impact(val advisory: Advisory, val change: Change?, val trip: Trip, val level: Level) {
        val start: LocalDate get() = change?.start ?: advisory.start
        val end: LocalDate get() = change?.end ?: advisory.end
        /** Stable key, so each heads-up is sent once. */
        val key: String get() = "${advisory.id}|${change?.line}|${change?.days}|$start|${trip.id}"
    }

    // ------------------------------------------------------------------ the advisories list

    private val lineCodes = mapOf("red" to "RD", "orange" to "OR", "silver" to "SV", "blue" to "BL", "yellow" to "YL", "green" to "GR")

    fun lineCode(name: String): String? = lineCodes[name.trim().lowercase().removeSuffix(" line").trim()]

    /** Rail advisories from wmata.com's list. Bad or partial data gives fewer results, never a crash. */
    fun parseList(json: String): List<Advisory> {
        val results = runCatching { JSONObject(json).optJSONArray("advisoryResults") }.getOrNull() ?: return emptyList()
        return (0 until results.length()).mapNotNull { i -> runCatching { advisory(results.getJSONObject(i)) }.getOrNull() }
            .distinctBy { it.id }
    }

    private fun advisory(o: JSONObject): Advisory? {
        val p = o.optJSONObject("properties") ?: return null
        val lines = p.optJSONArray("relatedRailLines")?.let { a ->
            (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let { l -> lineCode(l.optString("longName")) } }
        }.orEmpty().distinct()
        if (lines.isEmpty()) return null // bus-only
        val start = date(p.optString("effectiveDateStartEpoch")) ?: return null
        val end = date(p.optString("effectiveDateEndEpoch")) ?: start
        val stations = p.optJSONArray("relatedStations")?.let { a ->
            (0 until a.length()).flatMap { a.optJSONObject(it)?.optString("gtfsId")?.substringAfter("STN_", "")?.split("_").orEmpty() }
        }.orEmpty().filter { it.matches(Regex("[A-Z]\\d\\d")) }.distinct()
        val tags = o.optJSONArray("tags")?.let { a -> (0 until a.length()).map { a.optJSONObject(it)?.optString("value").orEmpty() } }.orEmpty()
        val kind = when {
            tags.any { it.endsWith("weekend-trackwork") } -> Kind.WEEKEND
            tags.any { it.endsWith("weekday-trackwork") } -> Kind.WEEKDAY
            tags.any { it.endsWith("major-construction") } -> Kind.MAJOR
            else -> Kind.STATION
        }
        val url = o.optString("url").takeIf { it.startsWith("https://www.wmata.com/") } ?: "https://www.wmata.com/ride/alerts-and-advisories.html"
        return Advisory(
            id = url, title = o.optString("title").ifBlank { kind.label }, summary = o.optString("description").trim(),
            url = url, kind = kind, start = start, end = if (end.isBefore(start)) start else end, lines = lines, stations = stations,
        )
    }

    private fun date(epochMs: String): LocalDate? =
        epochMs.toLongOrNull()?.takeIf { it > 0 }?.let { Instant.ofEpochMilli(it).atZone(DC).toLocalDate() }

    // ------------------------------------------------------------------ weekend / weekday pages

    private val block = Regex("""class="cmp-text">(.*?)</div>""", RegexOption.DOT_MATCHES_ALL)
    private val heading = Regex("""<h[23][^>]*>(.*?)</h[23]>""", RegexOption.DOT_MATCHES_ALL)
    private val para = Regex("""<p>(.*?)</p>""", RegexOption.DOT_MATCHES_ALL)
    private val stationLink = Regex("""<a href="/ridertools/station/[^"]*">(.*?)</a>""")

    /** The "Impacted service" parts of a weekend or weekday page, one [Change] per line and day range. */
    fun parsePage(html: String, year: Int): List<Change> {
        val out = ArrayList<Change>()
        var impacted = false
        var days = ""
        var range: Pair<LocalDate?, LocalDate?> = null to null
        for (m in block.findAll(html)) {
            val b = m.groupValues[1]
            val heads = heading.findAll(b).map { text(it.groupValues[1]) }.toList()
            if (heads.isNotEmpty()) {
                val h = heads.first()
                when {
                    h.contains("Impacted service", ignoreCase = true) -> {
                        impacted = true
                        val dates = heads.drop(1).firstOrNull().orEmpty()
                        range = dateRange(dates, year)
                        days = listOf(h.substringBefore(":").trim(), dates).filter { it.isNotBlank() }.joinToString(", ")
                    }
                    else -> impacted = false // "Normal service", "What track work…", "Weekend service" links…
                }
            }
            if (!impacted || heads.isNotEmpty()) continue
            // <p>Orange Line:</p><ul><li>Single tracking: A to B<ul><li>Expect 24 minutes…</li></ul></li></ul>
            val ps = para.findAll(b).toList()
            ps.forEachIndexed { i, pm ->
                val line = lineCode(text(pm.groupValues[1]).removeSuffix(":").trim()) ?: return@forEachIndexed
                val body = b.substring(pm.range.last + 1, ps.getOrNull(i + 1)?.range?.first ?: b.length)
                // Each top-level bullet is one change; its sub-bullets are details.
                val items = splitTopLevel(body)
                for (item in items) {
                    val names = stationLink.findAll(item).map { text(it.groupValues[1]) }.toList()
                    val t = item.replace(Regex("<li>"), "\n• ").let { text(it) }
                        .split("•").map { it.trim().trimEnd('.') }.filter { it.isNotBlank() }.joinToString(" · ")
                    if (t.isBlank()) continue
                    out += Change(
                        days = days, start = range.first, end = range.second, line = line, text = t,
                        from = names.getOrNull(0)?.let { DestinationCodes.resolve(it, line) },
                        to = names.getOrNull(1)?.let { DestinationCodes.resolve(it, line) },
                        onlyBetween = Regex("""\bonly\b""", RegexOption.IGNORE_CASE).containsMatchIn(text(item.substringBefore("<ul>"))),
                    )
                }
            }
        }
        return out
    }

    /** Top-level <li> items of the first list in [html] (nested lists stay inside their item). */
    private fun splitTopLevel(html: String): List<String> {
        val items = ArrayList<String>()
        var depth = 0
        var start = -1
        var i = 0
        while (i < html.length) {
            when {
                html.startsWith("<ul", i) -> depth++
                html.startsWith("</ul>", i) -> depth--
                html.startsWith("<li", i) && depth == 1 -> start = html.indexOf('>', i) + 1
                html.startsWith("</li>", i) && depth == 1 && start > 0 -> { items += html.substring(start, i); start = -1 }
            }
            i++
        }
        return items
    }

    private fun text(html: String): String =
        html.replace(Regex("<[^>]+>"), "").replace("&nbsp;", " ").replace("&amp;", "&").replace("&#39;", "'")
            .replace("&quot;", "\"").replace(' ', ' ').replace(Regex("[ \\t]+"), " ").trim()

    private val monthNames = Month.entries.associateBy { it.getDisplayName(TextStyle.FULL, Locale.US).lowercase() } +
        Month.entries.associateBy { it.getDisplayName(TextStyle.SHORT, Locale.US).lowercase() } + mapOf("sept" to Month.SEPTEMBER)

    /** "November 21 - 22", "November 27", "Nov. 30 - Dec. 2", "November 30 – December 2". */
    fun dateRange(s: String, year: Int): Pair<LocalDate?, LocalDate?> {
        val m = Regex("""([A-Za-z]+)\.?\s+(\d{1,2})(?:\s*[-–]\s*(?:([A-Za-z]+)\.?\s+)?(\d{1,2}))?""").find(s) ?: return null to null
        val m1 = monthNames[m.groupValues[1].lowercase()] ?: return null to null
        val d1 = m.groupValues[2].toInt()
        val m2 = m.groupValues[3].takeIf { it.isNotBlank() }?.let { monthNames[it.lowercase()] } ?: m1
        val d2 = m.groupValues[4].toIntOrNull() ?: d1
        val a = runCatching { LocalDate.of(year, m1, d1) }.getOrNull() ?: return null to null
        var b = runCatching { LocalDate.of(year, m2, d2) }.getOrNull() ?: a
        if (b.isBefore(a)) b = b.plusYears(1) // Dec 30 – Jan 2
        return a to b
    }

    // ------------------------------------------------------------------ what it means for your trips

    /** A trip's route: for each leg, the lines that run it and the stations it passes (in order). */
    data class RouteLeg(val lines: Set<String>, val stations: List<String>)

    fun route(trip: Trip, paths: List<LinePath>): List<RouteLeg>? {
        val a = StationDirectory.find(trip.stationCode) ?: return null
        val b = StationDirectory.find(trip.destCode) ?: return null
        val plan = Planner.plan(a, b, paths) ?: return null
        return plan.legs.map { leg ->
            val stations = leg.options.flatMap { o ->
                val i = o.path.codes.indexOf(o.boardCode); val j = o.path.codes.indexOf(o.alightCode)
                if (i < 0 || j < 0) emptyList() else o.path.codes.subList(minOf(i, j), maxOf(i, j) + 1)
            }.distinct()
            RouteLeg(leg.options.map { it.line }.toSet(), stations)
        }
    }

    /** Your trips × upcoming advisories → what each means for each trip. Ended work is left out. */
    fun impacts(advisories: List<Advisory>, trips: List<Pair<Trip, List<RouteLeg>>>, paths: List<LinePath>, today: LocalDate): List<Impact> {
        val out = ArrayList<Impact>()
        for (adv in advisories) for ((trip, legs) in trips) {
            val ends = legs.flatMap { listOf(it.stations.first(), it.stations.last()) }.flatMap { codesOf(it) }.toSet()
            val all = legs.flatMap { it.stations }.flatMap { codesOf(it) }.toSet()
            val used = legs.flatMap { it.lines }.toSet()
            if (!adv.kind.service) {
                if (adv.end >= today && adv.stations.any { it in ends }) out += Impact(adv, null, trip, Level.STATION)
                continue
            }
            if (adv.changes.isNotEmpty()) {
                for (c in adv.changes) {
                    if ((c.end ?: adv.end) < today) continue
                    level(c, legs, paths)?.let { out += Impact(adv, c, trip, it) }
                }
            } else if (adv.end >= today && adv.lines.any { it in used }) {
                val inZone = adv.stations.any { it in all }
                out += Impact(adv, null, trip, if (inZone) Level.IN_ZONE else Level.LINE)
            }
        }
        return out.sortedWith(compareBy({ it.start }, { it.level.ordinal }))
    }

    private fun codesOf(code: String): List<String> = StationDirectory.find(code)?.codes ?: listOf(code)

    /** How one change affects a trip's legs (null: not at all). */
    private fun level(c: Change, legs: List<RouteLeg>, paths: List<LinePath>): Level? {
        var worst: Level? = null
        fun note(l: Level) { if (worst == null || l.ordinal < worst!!.ordinal) worst = l }
        for (leg in legs) {
            if (c.line !in leg.lines) continue
            val segment = segment(c, paths)
            val legCodes = leg.stations.flatMap { codesOf(it) }.toSet()
            val others = leg.lines - c.line
            when {
                segment == null -> note(Level.LINE)
                c.onlyBetween -> {
                    // Trains only run inside the segment: a leg reaching outside it is cut off — unless another line covers it.
                    val outside = leg.stations.any { s -> codesOf(s).none { it in segment } }
                    note(if (outside && others.isEmpty()) Level.BLOCKED else Level.LINE)
                }
                legCodes.any { it in segment } -> note(if (others.isEmpty()) Level.IN_ZONE else Level.LINE)
                else -> note(Level.LINE)
            }
        }
        return worst
    }

    /** Station codes from the change's first station to its second, along that line. */
    private fun segment(c: Change, paths: List<LinePath>): Set<String>? {
        val a = c.from?.let { codesOf(it) } ?: return null
        val b = c.to?.let { codesOf(it) } ?: return null
        for (p in paths.filter { it.line == c.line }) {
            val i = p.codes.indexOfFirst { it in a }; val j = p.codes.indexOfFirst { it in b }
            if (i >= 0 && j >= 0) return p.codes.subList(minOf(i, j), maxOf(i, j) + 1).flatMap { codesOf(it) }.toSet()
        }
        return null
    }

    /** "Sat, Nov 21" / "Nov 21 – 22" / "Dec 18 – 30". */
    fun dates(a: LocalDate, b: LocalDate): String {
        fun md(d: LocalDate) = "${d.month.getDisplayName(TextStyle.SHORT, Locale.US)} ${d.dayOfMonth}"
        fun dow(d: LocalDate) = d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.US)
        return when {
            a == b -> "${dow(a)}, ${md(a)}"
            a.month == b.month && b.toEpochDay() - a.toEpochDay() <= 6 -> "${dow(a)}–${dow(b)}, ${md(a)} – ${b.dayOfMonth}"
            a.month == b.month -> "${md(a)} – ${b.dayOfMonth}"
            else -> "${md(a)} – ${md(b)}"
        }
    }

    /** "Today", "Tomorrow", "In 3 days", "Now" (already started), "In 2 weeks". */
    fun whenLabel(start: LocalDate, end: LocalDate, today: LocalDate): String {
        val d = start.toEpochDay() - today.toEpochDay()
        return when {
            d <= 0 && end >= today -> if (d == 0L) "Today" else "Happening now"
            d == 1L -> "Tomorrow"
            d < 14 -> "In $d days"
            else -> "In ${d / 7} weeks"
        }
    }

    // ------------------------------------------------------------------ cache (no personal data)

    fun toJson(list: List<Advisory>): String = JSONArray().apply {
        list.forEach { a ->
            put(JSONObject().put("id", a.id).put("title", a.title).put("summary", a.summary).put("url", a.url).put("kind", a.kind.name)
                .put("start", a.start.toString()).put("end", a.end.toString()).put("lines", JSONArray(a.lines)).put("stations", JSONArray(a.stations))
                .put("changes", JSONArray().apply {
                    a.changes.forEach { c ->
                        put(JSONObject().put("days", c.days).put("start", c.start?.toString() ?: "").put("end", c.end?.toString() ?: "")
                            .put("line", c.line).put("text", c.text).put("from", c.from ?: "").put("to", c.to ?: "").put("only", c.onlyBetween))
                    }
                }))
        }
    }.toString()

    fun fromJson(s: String): List<Advisory> = runCatching {
        val arr = JSONArray(s)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            fun list(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
            val ch = o.optJSONArray("changes")
            Advisory(
                o.getString("id"), o.getString("title"), o.optString("summary"), o.getString("url"), Kind.valueOf(o.getString("kind")),
                LocalDate.parse(o.getString("start")), LocalDate.parse(o.getString("end")), list("lines"), list("stations"),
                (0 until (ch?.length() ?: 0)).map { j ->
                    val c = ch!!.getJSONObject(j)
                    fun d(k: String) = c.optString(k).takeIf { it.isNotBlank() }?.let { LocalDate.parse(it) }
                    Change(c.optString("days"), d("start"), d("end"), c.getString("line"), c.getString("text"),
                        c.optString("from").ifBlank { null }, c.optString("to").ifBlank { null }, c.optBoolean("only"))
                },
            )
        }
    }.getOrDefault(emptyList())
}
