package com.metrocast.app.robo

import com.metrocast.app.data.Http
import com.metrocast.app.data.HttpException
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/** Answers the app's requests from recorded WMATA data (and a made-up forecast). */
object FakeWmata {
    private val live = File("src/test/resources/live")
    private val live2 = File("src/test/resources/live2")
    private fun read(dir: File, n: String) = File(dir, n).readText()

    /** Requests seen, for checks like "the widget didn't call WMATA again". */
    val requests = mutableListOf<String>()
    /** Set to make a kind of request fail, e.g. "GetPrediction" to 500. */
    var failing: Map<String, Exception> = emptyMap()
    var snapshot = 1
    /** Replace the body for requests containing a key, e.g. "GetPrediction" → "{" (garbage). */
    var bodies: Map<String, String> = emptyMap()

    init { TestDefaults.use() }

    fun install() {
        TestDefaults.use()
        requests.clear(); failing = emptyMap(); bodies = emptyMap(); snapshot = 1
        Http.testResponder = { url -> respond(url) }
        Http.testBytes = { url -> respondBytes(url) }
        com.metrocast.app.ui.Net.testOnline = true // the fake network is "online" (tests of offline set false)
        liveFeeds = emptyMap()
    }

    fun uninstall() { Http.testResponder = null; Http.testBytes = null; com.metrocast.app.ui.Net.testOnline = null }

    /** MARC / VRE live feeds to answer with (by URL part, e.g. "marc-tu" or "TripUpdateFeed"); empty feeds otherwise. */
    var liveFeeds: Map<String, ByteArray> = emptyMap()

    private fun respondBytes(url: String): ByteArray {
        requests += url
        failing.entries.firstOrNull { url.contains(it.key) }?.let { throw it.value }
        liveFeeds.entries.firstOrNull { url.contains(it.key) }?.let { return it.value }
        return when {
            "content.amtrak.com" in url -> File(live, "rail_amtrak.zip").readBytes()
            "mdotmta_gtfs_marc" in url -> File(live, "rail_marc.zip").readBytes()
            "gtfs.vre.org" in url && url.endsWith(".zip") -> File(live, "rail_vre.zip").readBytes()
            "marc-tu" in url || "TripUpdateFeed" in url -> File(live, "marc_tu_sunday.pb").readBytes()
            else -> throw HttpException(404)
        }
    }

    private fun respond(url: String): String {
        requests += url
        failing.entries.firstOrNull { url.contains(it.key) }?.let { throw it.value }
        bodies.entries.firstOrNull { url.contains(it.key) }?.let { return it.value }
        return when {
            "alerts_advisories_li" in url -> read(live, "advisories.json")
            url.endsWith("/weekend.html") -> read(live, "weekend.html")
            url.endsWith("/weekday.html") -> read(live, "weekday.html")
            url.endsWith("/restrooms.html") -> read(live, "restrooms.html")
            url.endsWith("/bike-and-ride.html") -> read(live, "b_bike-and-ride.html")
            "station_information.json" in url -> read(live, "cabi_info.json")
            "station_status.json" in url -> read(live, "cabi_status.json")
            "news_listing" in url -> read(live, "news.json")
            "amtraker.com" in url -> "{}"
            // ZIP 22201 (Arlington, VA) — only when the search is limited to the US, as the app does for ZIP codes.
            "open-meteo.com" in url && "geocoding" in url && "name=22201" in url && "countryCode=US" in url ->
                """{"results":[{"name":"Arlington","admin1":"Virginia","country":"United States","latitude":38.887,"longitude":-77.093}]}"""
            "open-meteo.com" in url && "geocoding" in url -> """{"results":[]}"""
            "open-meteo.com" in url -> forecast()
            "jStations" in url -> read(live, "stations.json")
            "StandardRoutes" in url -> read(live, "routes.json")
            "TrainPositions" in url -> read(live2, "pos_$snapshot.json")
            "GetPrediction" in url -> read(live2, "pred_$snapshot.json")
            "ElevatorIncidents" in url -> read(live, "elevators.json")
            "Incidents" in url -> read(live, "incidents.json")
            "jSrcStationToDstStationInfo" in url -> read(live, "ride.json")
            "jStationEntrances" in url -> read(live, "entrances.json")
            "jLines" in url || "jPath" in url -> throw HttpException(404)
            else -> throw HttpException(404)
        }
    }

    private fun forecast(): String {
        val now = LocalDateTime.now().truncatedTo(ChronoUnit.HOURS)
        val hours = (0 until 30).map { now.plusHours(it.toLong()) }
        val days = (0 until 7).map { LocalDate.now().plusDays(it.toLong()) }
        fun arr(xs: List<Any>) = xs.joinToString(",", "[", "]") { if (it is String) "\"$it\"" else it.toString() }
        return """{"current":{"time":"$now","temperature_2m":54.2,"apparent_temperature":51.0,"relative_humidity_2m":60,"is_day":1,"weather_code":2,"wind_speed_10m":7.5},
            "current_units":{"wind_speed_10m":"mp/h"},
            "hourly":{"time":${arr(hours.map { it.toString() })},"temperature_2m":${arr(hours.map { 50 })},"weather_code":${arr(hours.map { 3 })},
              "precipitation_probability":${arr(hours.map { 10 })},"is_day":${arr(hours.map { 1 })}},
            "daily":{"time":${arr(days.map { it.toString() })},"weather_code":${arr(days.map { 61 })},"temperature_2m_max":${arr(days.map { 60 })},
              "temperature_2m_min":${arr(days.map { 41 })},"precipitation_probability_max":${arr(days.map { 40 })}}}"""
    }
}

/**
 * Android keeps one view-model factory per process, tied to the first app object. Each Robolectric
 * test makes a new app object, so without this a later test's screens would read an earlier test's data.
 */
fun resetViewModelFactory() {
    val cls = androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory::class.java
    (cls.declaredFields + (runCatching { Class.forName(cls.name + "\$Companion").declaredFields }.getOrNull() ?: emptyArray()))
        .filter { java.lang.reflect.Modifier.isStatic(it.modifiers) && cls.isAssignableFrom(it.type) }
        .forEach { f -> runCatching { f.isAccessible = true; f.set(null, null) } }
}

/**
 * The tests were written against a Silver Line pair of trips and a Virginia forecast spot, so they
 * keep those as their starting data; the app itself ships neutral defaults (Metro Center ⇄ Union Station).
 */
object TestDefaults {
    val TRIPS = listOf(
        com.metrocast.app.data.Trip(id = "default-going", label = com.metrocast.app.data.Trip.GOING, lineCode = "SV",
            stationCode = "N06", stationName = "Wiehle-Reston East", towardCode = "C04", towardName = "Foggy Bottom-GWU"),
        com.metrocast.app.data.Trip(id = "default-back", label = com.metrocast.app.data.Trip.BACK, lineCode = "SV",
            stationCode = "C04", stationName = "Foggy Bottom-GWU", towardCode = "N06", towardName = "Wiehle-Reston East"),
    )
    fun use() {
        com.metrocast.app.data.Stations.testDefaults = TRIPS
        com.metrocast.app.data.Prefs.DEFAULT_LOCATION = Triple("Arlington, VA", 38.8870, -77.0930)
    }
    fun shipped() {
        com.metrocast.app.data.Stations.testDefaults = null
        com.metrocast.app.data.Prefs.DEFAULT_LOCATION = com.metrocast.app.data.Stations.START.let { Triple(it[5], it[6].toDouble(), it[7].toDouble()) }
    }
}
