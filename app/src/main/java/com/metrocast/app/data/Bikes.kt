package com.metrocast.app.data

/**
 * Bikes on Metro: secure parking you open with a SmarTrip card or key fob — Bike & Ride rooms run
 * by Metro or by Fairfax County — from WMATA's Bike & Ride page — checked about once a month, with the list
 * below built in for when that can't be read.
 */
object Bikes {
    const val BIKE_AND_RIDE_URL = "https://www.wmata.com/ride/guides/bikes/bike-and-ride.html"
    const val RULES_URL = "https://www.wmata.com/ride/guides/bikes/bikes-on-rail.html"
    /** Fairfax County's bike pages, for its own Bike & Ride rooms. */
    const val FAIRFAX_URL = "https://www.fairfaxcounty.gov/transportation/bike-walk"
    const val FAIRFAX_PHONE = "703-877-5600"
    /** Maryland: no county-run secure bike rooms at Metro stations could be confirmed — these are where to check. */
    const val MONTGOMERY_URL = "https://www.montgomerycountymd.gov/DOT-DTE/BikeWays/index.html"
    const val METRO_BIKE_PHONE = "202-962-1066"

    /** Secure parking you open with a SmarTrip card or a key fob (app-booked lockers aren't included). */
    enum class Kind(val label: String, val access: String) {
        BIKE_AND_RIDE_METRO("Bike & Ride (Metro)", "SmarTrip card"),
        BIKE_AND_RIDE_FAIRFAX("Bike & Ride (Fairfax County)", "Key fob from Fairfax County"),
    }

    data class Facility(val code: String, val name: String, val kind: Kind, val detail: String, val ready: Boolean = true)

    /** WMATA's guidance for bikes on trains (from its Bikes on Metro Rail page). */
    val RULES = listOf(
        "Bikes and e-scooters ride free, any time. Up to 80\" long, 48\" high, 22\" wide; nothing gas-powered.",
        "Use the elevators, and let other customers get on and off first.",
        "Use the extra-wide fare gates.",
        "Board through the front or rear doors of a car — at most 4 bikes per car, 2 at each end.",
        "The first and last cars are usually the least crowded.",
        "Don't ride inside stations or on platforms; keep both wheels down and stay with your bike.",
        "Under 16 with a bike? An adult must come along (one child with a bike per adult).",
        "Metro can limit bikes at very busy times or special events.",
    )

    private val builtIn: List<Facility> = listOf(
        Facility("E09", "College Park-U of Md", Kind.BIKE_AND_RIDE_METRO, "Free, card-controlled, sheltered, 24/7, video-monitored; repair stand and pump"),
        Facility("K05", "East Falls Church", Kind.BIKE_AND_RIDE_METRO, "Free, card-controlled, sheltered, 24/7, video-monitored; repair stand and pump"),
        Facility("A11", "Grosvenor-Strathmore", Kind.BIKE_AND_RIDE_METRO, "Free, card-controlled, sheltered, 24/7, video-monitored; repair stand and pump"),
        Facility("C11", "Potomac Yard", Kind.BIKE_AND_RIDE_METRO, "Free, card-controlled, sheltered, 24/7, video-monitored; repair stand and pump"),
        Facility("K08", "Vienna/Fairfax-GMU", Kind.BIKE_AND_RIDE_METRO, "Free, card-controlled, sheltered, 24/7, video-monitored; repair stand and pump"),
        Facility("N08", "Herndon", Kind.BIKE_AND_RIDE_FAIRFAX, "Run by Fairfax County"),
        Facility("N09", "Innovation Center", Kind.BIKE_AND_RIDE_FAIRFAX, "Run by Fairfax County"),
        Facility("N06", "Wiehle-Reston East", Kind.BIKE_AND_RIDE_FAIRFAX, "Run by Fairfax County"),
    )

    private const val BUILT_IN_SOURCE = "wmata.com, Nov 2026"
    @Volatile private var live: Pair<String, List<Facility>>? = null

    val all: List<Facility> get() = live?.second ?: builtIn
    val SOURCE: String get() = live?.first ?: BUILT_IN_SOURCE
    val isLive: Boolean get() = live != null
    fun useLive(source: String, list: List<Facility>?) { live = list?.let { source to it } }

    /** Secure parking at a station (any of its platform codes). */
    fun at(code: String): List<Facility> {
        val codes = StationDirectory.find(code)?.codes ?: listOf(code)
        return all.filter { it.code in codes || StationDirectory.find(it.code)?.codes?.any { c -> c in codes } == true }
    }

    // ------------------------------------------------------------------ reading WMATA's pages

    private fun clean(s: String) = s.replace(Regex("<[^>]+>"), " ").replace("&nbsp;", " ").replace("&amp;", "&").replace("&#39;", "'")
        .replace(' ', ' ').replace(Regex("\\s+"), " ").trim()

    /** The page as plain HTML: some of WMATA's text only appears escaped inside page data. */
    private fun unescaped(html: String) = html.replace("&lt;", "<").replace("&gt;", ">").replace("&#34;", "\"").replace("\\\"", "\"")
        .replace("\\r\\n", "\n").replace("&amp;", "&")

    /** "Locations operated by Metro / by Fairfax County" lists. Null if the page doesn't look right. */
    fun parseBikeAndRide(html: String): List<Facility>? {
        val h = if (html.contains("<h2>Locations operated by Metro</h2>")) html else unescaped(html)
        fun list(heading: String): List<String> {
            // The heading itself (not the same words inside the page's data).
            val i = h.indexOf("<h2>$heading</h2>").takeIf { it >= 0 } ?: return emptyList()
            val ul = h.indexOf("<ul>", i).takeIf { it >= 0 } ?: return emptyList()
            val end = h.indexOf("</ul>", ul).takeIf { it >= 0 } ?: return emptyList()
            return Regex("<li>(.*?)</li>", RegexOption.DOT_MATCHES_ALL).findAll(h.substring(ul, end)).map { clean(it.groupValues[1]) }.filter { it.isNotBlank() }.toList()
        }
        val metro = list("Locations operated by Metro").mapNotNull { n -> Restrooms.codeFor(n)?.let { Facility(it, StationDirectory.nameOf(it) ?: n, Kind.BIKE_AND_RIDE_METRO, "Free, card-controlled, sheltered, 24/7, video-monitored; repair stand and pump") } }
        val fairfax = list("Locations operated by Fairfax County").mapNotNull { n -> Restrooms.codeFor(n)?.let { Facility(it, StationDirectory.nameOf(it) ?: n, Kind.BIKE_AND_RIDE_FAIRFAX, "Run by Fairfax County") } }
        return (metro + fairfax).takeIf { metro.size >= 3 }
    }
}
