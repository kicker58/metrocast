package com.metrocast.app.data

import org.json.JSONObject

/**
 * Capital Bikeshare's public live feed (the standard GBFS format): docks near a Metro station, with
 * bikes, e-bikes and open docks right now. No account or key needed.
 */
object Bikeshare {
    const val INDEX_URL = "https://gbfs.capitalbikeshare.com/gbfs/gbfs.json"
    const val INFO_URL = "https://gbfs.lyft.com/gbfs/1.1/dca-cabi/en/station_information.json"
    const val STATUS_URL = "https://gbfs.lyft.com/gbfs/1.1/dca-cabi/en/station_status.json"
    /** How far from the station's middle a dock still counts as "at" the station. */
    const val NEAR_METERS = 500.0

    data class DockInfo(val id: String, val name: String, val lat: Double, val lon: Double, val capacity: Int)
    data class DockStatus(val bikes: Int, val ebikes: Int, val docks: Int, val renting: Boolean, val returning: Boolean)
    data class Dock(val info: DockInfo, val status: DockStatus?, val meters: Int) {
        /** "6 bikes (2 e-bikes) · 9 open docks" or "Not in service". */
        val summary: String get() {
            val s = status ?: return "Live numbers unavailable"
            if (!s.renting && !s.returning) return "Not in service right now"
            val bikes = "${s.bikes} bike${if (s.bikes == 1) "" else "s"}" + if (s.ebikes > 0) " (${s.ebikes} e-bike${if (s.ebikes == 1) "" else "s"})" else ""
            return "$bikes · ${s.docks} open dock${if (s.docks == 1) "" else "s"}"
        }
    }

    /** The feed's URLs from its index (so a move by the operator is followed); the known ones otherwise. */
    fun feedUrls(indexJson: String): Pair<String, String>? = runCatching {
        val feeds = JSONObject(indexJson).getJSONObject("data").let { d -> (d.optJSONObject("en") ?: d.getJSONObject(d.keys().next())).getJSONArray("feeds") }
        var info: String? = null; var status: String? = null
        for (i in 0 until feeds.length()) {
            val f = feeds.getJSONObject(i)
            when (f.optString("name")) { "station_information" -> info = f.optString("url"); "station_status" -> status = f.optString("url") }
        }
        if (info != null && status != null && info!!.startsWith("https://") && status!!.startsWith("https://")) info!! to status!! else null
    }.getOrNull()

    fun parseInfo(json: String): List<DockInfo> = runCatching {
        val arr = JSONObject(json).getJSONObject("data").getJSONArray("stations")
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val lat = o.optDouble("lat", Double.NaN); val lon = o.optDouble("lon", Double.NaN)
            if (lat.isNaN() || lon.isNaN()) return@mapNotNull null
            DockInfo(o.optString("station_id").ifBlank { return@mapNotNull null }, o.optString("name"), lat, lon, o.optInt("capacity"))
        }
    }.getOrDefault(emptyList())

    fun parseStatus(json: String): Map<String, DockStatus> = runCatching {
        val arr = JSONObject(json).getJSONObject("data").getJSONArray("stations")
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("station_id").ifBlank { return@mapNotNull null }
            id to DockStatus(
                bikes = o.optInt("num_bikes_available"), ebikes = o.optInt("num_ebikes_available"),
                docks = o.optInt("num_docks_available"),
                renting = o.optInt("is_renting", 1) == 1, returning = o.optInt("is_returning", 1) == 1,
            )
        }.toMap()
    }.getOrDefault(emptyMap())

    /** The nearest docks to a Metro station (closest first). */
    fun near(station: StationInfo, docks: List<DockInfo>, status: Map<String, DockStatus>, max: Int = 3): List<Dock> {
        if (station.lat == 0.0) return emptyList()
        return docks.asSequence()
            .map { it to TrainSpeed.haversine(station.lat, station.lon, it.lat, it.lon) }
            .filter { it.second <= NEAR_METERS }
            .sortedBy { it.second }
            .take(max)
            .map { (d, m) -> Dock(d, status[d.id], m.toInt()) }
            .toList()
    }
}
