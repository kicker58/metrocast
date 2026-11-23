package com.metrocast.app.data

/**
 * Customer restrooms at Metrorail stations, from wmata.com/ride/guides/restrooms.html
 * (updated 10/30/2026, status as of 10/31/2026). 36 stations have restrooms for customers; at some
 * you can walk in, at others you ask the station manager (usually at the kiosk by the faregates).
 * Restrooms can be temporarily closed for cleaning, repairs, etc.
 */
object Restrooms {
    enum class Status { ACCESSIBLE, RENOVATION_PLANNED, CLOSED_FOR_RENOVATION }

    data class Restroom(val askManager: Boolean, val status: Status, val note: String? = null)

    const val PAGE_URL = "https://www.wmata.com/ride/guides/restrooms.html"
    private const val BUILT_IN_SOURCE = "wmata.com, Oct 2026"

    /** WMATA's latest list (checked about once a month); null until downloaded — then the built-in list is used. */
    @Volatile private var live: Pair<String, List<Listing>>? = null
    /** Lookup for [live], built once when it's set (not on every call). */
    @Volatile private var liveByCode: Map<String, Restroom>? = null

    /** Where the list shown came from, e.g. "wmata.com, updated 10/30/2026". */
    val SOURCE: String get() = live?.first ?: BUILT_IN_SOURCE
    val isLive: Boolean get() = live != null

    fun useLive(source: String, list: List<Listing>?) {
        liveByCode = list?.associate { it.code to it.restroom }
        live = list?.let { source to it }
    }

    private fun open(s: Status = Status.ACCESSIBLE) = Restroom(askManager = false, status = s)
    private fun ask(s: Status = Status.ACCESSIBLE, note: String? = null) = Restroom(askManager = true, status = s, note = note)

    /** By station code (both platform codes at two-level stations). */
    private val builtInByCode: Map<String, Restroom> = mapOf(
        // DC
        "A05" to ask(),                                                       // Cleveland Park
        "E04" to ask(Status.CLOSED_FOR_RENOVATION, "closed for renovation through April 2027"), // Columbia Heights
        "D10" to ask(),                                                       // Deanwood
        "A03" to ask(Status.RENOVATION_PLANNED),                              // Dupont Circle
        "D06" to ask(),                                                       // Eastern Market
        "C04" to ask(Status.RENOVATION_PLANNED),                              // Foggy Bottom-GWU
        "B01" to ask(), "F01" to ask(),                                       // Gallery Place
        "D03" to ask(Status.RENOVATION_PLANNED), "F03" to ask(Status.RENOVATION_PLANNED), // L'Enfant Plaza
        "A01" to ask(Status.RENOVATION_PLANNED), "C01" to ask(Status.RENOVATION_PLANNED), // Metro Center
        // Maryland
        "B11" to ask(),                                                       // Glenmont
        "A14" to ask(),                                                       // Rockville
        "F11" to ask(),                                                       // Branch Ave
        "G05" to ask(),                                                       // Downtown Largo
        "E10" to ask(Status.RENOVATION_PLANNED),                              // Greenbelt
        "A11" to ask(),                                                       // Grosvenor-Strathmore
        "D13" to ask(Status.RENOVATION_PLANNED),                              // New Carrollton
        "A15" to ask(),                                                       // Shady Grove
        "F08" to ask(),                                                       // Southern Ave
        // Virginia
        "N12" to open(),                                                      // Ashburn
        "N10" to open(),                                                      // Washington Dulles International Airport
        "N03" to open(),                                                      // Greensboro
        "N09" to open(),                                                      // Innovation Center
        "N11" to open(),                                                      // Loudoun Gateway
        "N01" to open(),                                                      // McLean
        "C11" to open(),                                                      // Potomac Yard
        "N04" to open(),                                                      // Spring Hill
        "N07" to open(),                                                      // Reston Town Center
        "K08" to ask(),                                                       // Vienna/Fairfax-GMU
        "N06" to open(),                                                      // Wiehle-Reston East
        "C05" to ask(),                                                       // Rosslyn
        "K04" to ask(),                                                       // Ballston-MU
        "J03" to ask(),                                                       // Franconia-Springfield
        "N08" to open(),                                                      // Herndon
        "C15" to ask(),                                                       // Huntington
        "C13" to ask(),                                                       // King St-Old Town
        "N02" to open(),                                                      // Tysons
    )

    /** The station's restroom, by any of its platform codes. */
    fun at(code: String): Restroom? {
        val map = byCode
        return map[code] ?: StationDirectory.find(code)?.codes?.firstNotNullOfOrNull { map[it] }
    }

    private val byCode: Map<String, Restroom>
        get() = liveByCode ?: builtInByCode

    /** All codes in the list (for checking). */
    val codes: Set<String> get() = byCode.keys

    /** "🚻 Restroom — open, no need to ask" / "… ask the station manager" / "… closed for renovation". */
    fun label(r: Restroom): String = when {
        r.status == Status.CLOSED_FOR_RENOVATION -> "🚻 Restroom ${r.note ?: "closed for renovation"}"
        !r.askManager -> "🚻 Restroom — open, no need to ask"
        else -> "🚻 Restroom — ask the station manager (kiosk by the faregates)"
    } + if (r.status == Status.RENOVATION_PLANNED) " · not yet accessible" else ""

    /** One row of the restroom list: a station (both platforms at two-level stations). */
    data class Listing(val code: String, val name: String, val region: String, val restroom: Restroom)

    // Name and region for each station, as on wmata.com (works before WMATA's station list has loaded).
    private val places = listOf(
        Triple("A05", "Cleveland Park", "DC"), Triple("E04", "Columbia Heights", "DC"), Triple("D10", "Deanwood", "DC"),
        Triple("A03", "Dupont Circle", "DC"), Triple("D06", "Eastern Market", "DC"), Triple("C04", "Foggy Bottom-GWU", "DC"),
        Triple("B01", "Gallery Pl-Chinatown", "DC"), Triple("D03", "L'Enfant Plaza", "DC"), Triple("A01", "Metro Center", "DC"),
        Triple("B11", "Glenmont", "Maryland"), Triple("A14", "Rockville", "Maryland"), Triple("F11", "Branch Ave", "Maryland"),
        Triple("G05", "Downtown Largo", "Maryland"), Triple("E10", "Greenbelt", "Maryland"), Triple("A11", "Grosvenor-Strathmore", "Maryland"),
        Triple("D13", "New Carrollton", "Maryland"), Triple("A15", "Shady Grove", "Maryland"), Triple("F08", "Southern Ave", "Maryland"),
        Triple("N12", "Ashburn", "Virginia"), Triple("N10", "Washington Dulles International Airport", "Virginia"),
        Triple("N03", "Greensboro", "Virginia"), Triple("N09", "Innovation Center", "Virginia"), Triple("N11", "Loudoun Gateway", "Virginia"),
        Triple("N01", "McLean", "Virginia"), Triple("C11", "Potomac Yard", "Virginia"), Triple("N04", "Spring Hill", "Virginia"),
        Triple("N07", "Reston Town Center", "Virginia"), Triple("K08", "Vienna/Fairfax-GMU", "Virginia"), Triple("N06", "Wiehle-Reston East", "Virginia"),
        Triple("C05", "Rosslyn", "Virginia"), Triple("K04", "Ballston-MU", "Virginia"), Triple("J03", "Franconia-Springfield", "Virginia"),
        Triple("N08", "Herndon", "Virginia"), Triple("C15", "Huntington", "Virginia"), Triple("C13", "King St-Old Town", "Virginia"),
        Triple("N02", "Tysons", "Virginia"),
    )

    /** Every station with a customer restroom: walk-in ones first, then by name, grouped DC / Maryland / Virginia. */
    val all: List<Listing> get() = live?.second ?: builtIn

    private val builtIn: List<Listing> by lazy {
        places.map { (c, n, r) -> Listing(c, n, r, builtInByCode.getValue(c)) }
    }

    // ------------------------------------------------------------------ reading WMATA's page

    private fun norm(s: String) = s.lowercase().replace("&#39;", "'").filter { it.isLetterOrDigit() }

    /** A station name as WMATA writes it on that page ("Gallery Place", "Tysons Corner", "King St") → its code. */
    fun codeFor(name: String): String? {
        val q = norm(name).takeIf { it.length >= 3 } ?: return null
        places.firstOrNull { norm(it.second) == q }?.let { return it.first }
        val stations = StationDirectory.stations.map { it.code to it.name } + places.map { it.first to it.second }
        val hits = stations.filter { (_, n) -> val k = norm(n); k == q || k.startsWith(q) || q.startsWith(k) || k.contains(q) || q.contains(k) }
            .map { it.first }.distinct()
        if (hits.size == 1) return hits[0]
        // "Gallery Place" ↔ "Gallery Pl-Chinatown": same first word, only one such station.
        val first = norm(name.split(' ', '-', '/').first())
        val byWord = stations.filter { (_, n) -> norm(n.split(' ', '-', '/').first()) == first }.map { it.first }.distinct()
        return byWord.singleOrNull() ?: hits.minByOrNull { c -> stations.first { it.first == c }.second.length }
    }

    /**
     * WMATA's restroom tables (DC, Maryland, Virginia): station, "Station Manager Assistance Required?",
     * current status. Returns null when the page doesn't look right, so a redesign never empties the list.
     */
    fun parsePage(html: String): Pair<String, List<Listing>>? {
        val tables = Regex("""<table[^>]*>(.*?)</table>""", RegexOption.DOT_MATCHES_ALL).findAll(html).toList()
        val out = ArrayList<Listing>()
        for (t in tables) {
            val before = html.substring(maxOf(0, t.range.first - 800), t.range.first)
            val region = Regex("""<h[2-4][^>]*>(.*?)</h[2-4]>""").findAll(before).lastOrNull()?.groupValues?.get(1)
                ?.replace(Regex("<[^>]+>"), "")?.trim()?.let { h -> REGIONS.firstOrNull { h.contains(it, ignoreCase = true) } } ?: continue
            for (row in Regex("""<tr>(.*?)</tr>""", RegexOption.DOT_MATCHES_ALL).findAll(t.groupValues[1])) {
                val cells = Regex("""<td[^>]*>(.*?)</td>""", RegexOption.DOT_MATCHES_ALL).findAll(row.groupValues[1])
                    .map { it.groupValues[1].replace(Regex("<[^>]+>"), " ").replace("&#39;", "'").replace("&amp;", "&").replace("&nbsp;", " ").replace(Regex("\\s+"), " ").trim() }.toList()
                if (cells.size < 3 || cells[0].equals("Station", true)) continue
                val code = codeFor(cells[0]) ?: continue
                val ask = !cells[1].trim().startsWith("No", ignoreCase = true)
                val st = cells[2]
                val r = when {
                    st.contains("unavailable", true) || st.contains("closed", true) ->
                        Restroom(ask, Status.CLOSED_FOR_RENOVATION,
                            "closed" + (if (st.contains("renovation", true)) " for renovation" else "") +
                                (Regex("""\(([^)]*)\)""").find(st)?.groupValues?.get(1)?.trimEnd('.')?.let { " " + it.replaceFirstChar { c -> c.lowercase() } } ?: ""))
                    st.contains("planned", true) -> Restroom(ask, Status.RENOVATION_PLANNED)
                    else -> Restroom(ask, Status.ACCESSIBLE)
                }
                val name = StationDirectory.nameOf(code) ?: places.firstOrNull { it.first == code }?.second ?: cells[0]
                out += Listing(code, name, region, r)
            }
        }
        val list = out.distinctBy { it.code }
        if (list.size < 10 || list.map { it.region }.distinct().size < 2) return null
        val updated = Regex("""Updated:\s*(?:<[^>]+>\s*)*(\d{1,2}/\d{1,2}/\d{4})""").find(html)?.groupValues?.get(1)
        return (if (updated != null) "wmata.com, updated $updated" else "wmata.com") to list
    }

    /** True when you can just walk in (not closed, no need to ask). */
    fun isWalkIn(r: Restroom) = !r.askManager && r.status != Status.CLOSED_FOR_RENOVATION

    val REGIONS = listOf("DC", "Maryland", "Virginia")
}
