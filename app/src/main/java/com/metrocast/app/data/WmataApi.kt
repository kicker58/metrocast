package com.metrocast.app.data

import org.json.JSONObject

/**
 * WMATA developer API (https://developer.wmata.com). Every call needs a free API key.
 */
object WmataApi {
    private const val BASE = "https://api.wmata.com"

    data class RawTrain(
        val cars: String,
        val destinationName: String,
        val destinationCode: String?,
        val group: String,
        val line: String,
        val locationCode: String,
        val min: String,
    )

    private fun get(path: String, apiKey: String): String =
        Http.get(BASE + path, mapOf("api_key" to apiKey))

    // ---- Real-time arrival predictions ----

    fun predictionsRaw(stationCodes: List<String>, apiKey: String): String =
        get("/StationPrediction.svc/json/GetPrediction/${stationCodes.joinToString(",")}", apiKey)

    fun parsePredictions(raw: String): List<RawTrain> {
        val arr = JSONObject(raw).getJSONArray("Trains")
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val line = o.str("Line") ?: ""
            val signName = o.str("DestinationName") ?: o.str("Destination") ?: ""
            // Some predictions come without a code, only the sign abbreviation ("Fed Ctr SW"): work it out.
            val code = o.str("DestinationCode") ?: DestinationCodes.resolve(signName, line)
            val fullName = if (o.str("DestinationCode") == null) code?.let { StationDirectory.nameOf(it) } else null
            RawTrain(
                cars = o.str("Car") ?: "",
                destinationName = fullName ?: signName,
                destinationCode = code,
                group = o.str("Group") ?: "",
                line = o.str("Line") ?: "",
                locationCode = o.str("LocationCode") ?: "",
                min = o.str("Min") ?: "",
            )
        }
    }

    /** Scheduled ride time (minutes) and fares between two stations. */
    fun rideInfoRaw(from: String, to: String, apiKey: String): String =
        get("/Rail.svc/json/jSrcStationToDstStationInfo?FromStationCode=$from&ToStationCode=$to", apiKey)

    fun parseRideInfo(raw: String): Triple<Int?, Double?, Double?>? {
        val arr = JSONObject(raw).optJSONArray("StationToStationInfos") ?: return null
        if (arr.length() == 0) return null
        val o = arr.getJSONObject(0)
        val fare = o.optJSONObject("RailFare")
        return Triple(
            o.optInt("RailTime", -1).takeIf { it > 0 },
            fare?.optDouble("PeakTime")?.takeIf { !it.isNaN() },
            fare?.optDouble("OffPeakTime")?.takeIf { !it.isNaN() },
        )
    }

    // ---- Service alerts ----

    fun incidentsRaw(apiKey: String): String = get("/Incidents.svc/json/Incidents", apiKey)

    /** Elevators (and escalators) out of service right now. */
    fun elevatorsRaw(apiKey: String): String = get("/Incidents.svc/json/ElevatorIncidents", apiKey)

    /** Elevator outages only (escalators aren't what you need with a bike). */
    fun parseElevatorOutages(raw: String): List<ElevatorOutage> {
        val arr = JSONObject(raw).optJSONArray("ElevatorIncidents") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            if (o.str("UnitType")?.equals("ELEVATOR", true) != true) return@mapNotNull null
            ElevatorOutage(o.str("StationCode") ?: return@mapNotNull null, o.str("LocationDescription") ?: "Elevator",
                o.str("EstimatedReturnToService")?.take(10))
        }
    }

    fun parseIncidents(raw: String): List<Incident> {
        val arr = JSONObject(raw).optJSONArray("Incidents") ?: return emptyList()
        return (0 until arr.length()).map { arr.getJSONObject(it) }.map { o ->
            Incident(
                description = o.str("Description")?.trim().orEmpty(),
                type = o.str("IncidentType") ?: "Alert",
                lines = (o.str("LinesAffected") ?: "").split(";").map { it.trim() }.filter { it.length == 2 },
                updated = o.str("DateUpdated").orEmpty(),
                id = o.str("IncidentID") ?: o.str("Description").orEmpty().hashCode().toString(),
            )
        }.filter { it.description.isNotBlank() }
    }

    // ---- Station entrances ----

    fun entrancesRaw(lat: Double, lon: Double, apiKey: String): String =
        get("/Rail.svc/json/jStationEntrances?Lat=$lat&Lon=$lon&Radius=800", apiKey)

    fun parseEntrances(raw: String, codes: Collection<String>): List<StationExit> {
        val arr = JSONObject(raw).optJSONArray("Entrances") ?: return emptyList()
        return (0 until arr.length()).map { arr.getJSONObject(it) }
            .filter { o -> o.str("StationCode1") in codes || o.str("StationCode2") in codes }
            .map { o ->
                val name = o.str("Name").orEmpty()
                StationExit(
                    name = name.replace("(ELEVATOR)", "").trim().split(" ").joinToString(" ") { w ->
                        // "23RD ST NW" → "23rd St NW" (keep compass points upper case)
                        if (w in setOf("NW", "NE", "SW", "SE", "N", "S", "E", "W")) w else w.lowercase().replaceFirstChar { it.uppercase() }
                    },
                    description = o.str("Description").orEmpty().replace("form street", "from street"),
                    lat = o.optDouble("Lat"), lon = o.optDouble("Lon"),
                    elevator = name.contains("ELEVATOR", ignoreCase = true),
                )
            }
            .sortedBy { it.elevator }
    }

    // ---- Line / station data ----

    /** Every station, with its lines and the code of its other platform at transfer stations. */
    fun allStationsRaw(apiKey: String): String = get("/Rail.svc/json/jStations", apiKey)

    /** Ordered list of stations from one end of [lineCode] to the other. */
    fun lineStations(lineCode: String, apiKey: String): List<Station> {
        val lines = JSONObject(get("/Rail.svc/json/jLines", apiKey)).getJSONArray("Lines")
        var start: String? = null
        var end: String? = null
        for (i in 0 until lines.length()) {
            val l = lines.getJSONObject(i)
            if (l.str("LineCode") == lineCode) {
                start = l.str("StartStationCode")
                end = l.str("EndStationCode")
            }
        }
        requireNotNull(start); requireNotNull(end)
        val path = JSONObject(
            get("/Rail.svc/json/jPath?FromStationCode=$start&ToStationCode=$end", apiKey)
        ).getJSONArray("Path")
        return (0 until path.length())
            .map { path.getJSONObject(it) }
            .sortedBy { it.optInt("SeqNum") }
            .map { Station(it.getString("StationCode"), it.getString("StationName")) }
    }

    // ---- Live train positions ----

    fun trainPositionsRaw(apiKey: String): String =
        get("/TrainPositions/TrainPositions?contentType=json", apiKey)

    fun standardRoutesRaw(apiKey: String): String =
        get("/TrainPositions/StandardRoutes?contentType=json", apiKey)
}
