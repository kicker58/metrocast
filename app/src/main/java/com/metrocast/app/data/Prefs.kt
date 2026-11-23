package com.metrocast.app.data

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

/** Settings and cached API responses, stored in SharedPreferences. */
class Prefs(context: Context) {
    companion object {
        private val LINES = setOf("RD", "OR", "SV", "BL", "YL", "GR")
        /** Weather until you enter a ZIP (set per build). Tests can swap it. */
        @JvmField var DEFAULT_LOCATION = Stations.START.let { Triple(it[5], it[6].toDouble(), it[7].toDouble()) }
    }

    // Parsed once per change (they're read many times per refresh and on every redraw).
    @Volatile private var tripsCache: Pair<String, List<Trip>>? = null
    @Volatile private var sectionsCache: Pair<String, List<Pair<HomeSection, Boolean>>>? = null
    @Volatile private var menuCache: Pair<String, List<Pair<MenuItem, Boolean>>>? = null

    private val sp = context.applicationContext.getSharedPreferences("metrocast", Context.MODE_PRIVATE)
    // Downloaded data lives in its own files, not in the settings file: a refresh every few seconds then
    // never rewrites your settings, and a tap that saves a setting never waits on a big download being saved.
    private val cacheDir = java.io.File(context.applicationContext.filesDir, "cache")

    /** WMATA key: the one typed in Settings, or the built-in one when that's empty. */
    var apiKey: String
        get() = sp.getString("api_key", "")?.takeIf { it.isNotBlank() } ?: BUILT_IN_WMATA_KEY
        set(v) = sp.edit { putString("api_key", v.trim()) }

    var locationName: String
        get() = sp.getString("loc_name", null) ?: DEFAULT_LOCATION.first
        private set(v) = sp.edit { putString("loc_name", v) }

    // A damaged value (not a number, or off the globe) falls back to the default spot.
    val lat: Double get() = sp.getString("lat", null)?.toDoubleOrNull()?.takeIf { it.isFinite() && it in -90.0..90.0 } ?: DEFAULT_LOCATION.second
    val lon: Double get() = sp.getString("lon", null)?.toDoubleOrNull()?.takeIf { it.isFinite() && it in -180.0..180.0 } ?: DEFAULT_LOCATION.third

    fun setLocation(name: String, lat: Double, lon: Double) = sp.edit {
        putString("loc_name", name)
        putString("lat", lat.toString())
        putString("lon", lon.toString())
    }

    /** US units (°F, mph, feet) unless metric is chosen in Settings (°C, km/h, metres). */
    var useFahrenheit: Boolean
        get() = sp.getBoolean("fahrenheit", true)
        set(v) = sp.edit { putBoolean("fahrenheit", v) }

    val metric: Boolean get() = !useFahrenheit

    var showTrainLocation: Boolean
        get() = sp.getBoolean("train_location", true)
        set(v) = sp.edit { putBoolean("train_location", v) }

    /** Trip planner: the stations picked last (planner codes), or null. */
    var planFrom: String?
        // A new plan starts at the station chosen in Settings, else your "Going there" station.
        get() = sp.getString("plan_from", null) ?: plannerStart ?: trips.firstOrNull { it.onHome }?.stationCode ?: Stations.DEFAULT_TRIPS.first().stationCode
        set(v) = sp.edit { if (v == null) remove("plan_from") else putString("plan_from", v) }

    /** Trips one placed widget shows (by trip id, in order); null = the trips marked "Show on widget". */
    fun widgetTrips(appWidgetId: Int): List<String>? =
        sp.getString("widget_$appWidgetId", null)?.split(",")?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() }

    fun setWidgetTrips(appWidgetId: Int, ids: List<String>?) =
        sp.edit { if (ids.isNullOrEmpty()) remove("widget_$appWidgetId") else putString("widget_$appWidgetId", ids.joinToString(",")) }

    var planTo: String?
        get() = sp.getString("plan_to", null)
        set(v) = sp.edit { putString("plan_to", v) }

    /** Planner routes saved with ★, as (from, to) station codes. */
    var savedRoutes: List<Pair<String, String>>
        get() = (sp.getString("saved_routes", "") ?: "").split(";").mapNotNull { r ->
            r.split(">").takeIf { it.size == 2 && it.all { c -> c.isNotBlank() } }?.let { it[0] to it[1] }
        }
        set(v) = sp.edit { putString("saved_routes", v.joinToString(";") { "${it.first}>${it.second}" }) }

    /**
     * Only live-trip notifications are on unless you turn others on in Settings. Earlier versions'
     * setup switched track work and delay alerts on by itself; this resets those once.
     */
    fun notificationDefaultsV2() {
        if (sp.getBoolean("notif_defaults_v2", false)) return
        sp.edit {
            putBoolean("trackwork_alerts", false); putBoolean("alert_notifs", false)
            putBoolean("bike_elevator_alerts", false); putBoolean("special_news_alerts", false)
            putBoolean("notif_defaults_v2", true)
        }
    }

    /** Leave-now reminders and the SmarTrip balance were removed; their old settings are deleted. */
    fun forgetLeaveReminders() {
        if (sp.contains("card_balance") || sp.contains("card_balance_at")) sp.edit { remove("card_balance"); remove("card_balance_at") }
        if (listOf("leave_alerts", "leave_trip", "walk_min", "leave_start", "leave_end", "leave_weekdays", "leave_last").none { sp.contains(it) }) return
        sp.edit { listOf("leave_alerts", "leave_trip", "walk_min", "leave_start", "leave_end", "leave_weekdays", "leave_last").forEach { remove(it) } }
    }

    /** Push a notification when WMATA posts an alert for one of your lines. */
    var alertNotifications: Boolean
        get() = sp.getBoolean("alert_notifs", false)
        set(v) = sp.edit { putBoolean("alert_notifs", v) }

    /** Alert ids already notified. */
    /** Heads-up a few days before planned track work on your trips (on unless turned off). */
    var trackWorkAlerts: Boolean
        get() = sp.getBoolean("trackwork_alerts", false)
        set(v) = sp.edit { putBoolean("trackwork_alerts", v) }

    /** Track work already warned about ("advisory|line|days|start|trip"), so each is sent once. */
    var trackWorkNotified: Set<String>
        get() = sp.getStringSet("trackwork_notified", emptySet()) ?: emptySet()
        set(v) = sp.edit { putStringSet("trackwork_notified", v) }

    var seenAlertIds: Set<String>
        get() = sp.getStringSet("seen_alerts", emptySet()) ?: emptySet()
        set(v) = sp.edit { putStringSet("seen_alerts", v) }

    /** How many people ride on a planned trip (for the group fare). */
    var riders: Int
        get() = sp.getInt("riders", 1)
        set(v) = sp.edit { putInt("riders", v.coerceIn(1, 6)) }

    // ---- Live trip (survives the app being closed, so notifications keep working) ----
    var liveFrom: String?
        get() = sp.getString("live_from", null)
        set(v) = sp.edit { putString("live_from", v) }
    var liveTo: String?
        get() = sp.getString("live_to", null)
        set(v) = sp.edit { putString("live_to", v) }
    /** Which leg you're on (0 = first train). */
    var liveLeg: Int
        get() = sp.getInt("live_leg", 0)
        set(v) = sp.edit { putInt("live_leg", v) }
    /** The train you're riding (WMATA train id), once you tap "I'm on the train". */
    var liveTrainId: String?
        get() = sp.getString("live_train", null)
        set(v) = sp.edit { putString("live_train", v) }
    var liveBoardedAt: Long
        get() = sp.getLong("live_boarded_at", 0L)
        set(v) = sp.edit { putLong("live_boarded_at", v) }
    var liveStartedAt: Long
        get() = sp.getLong("live_started", 0L)
        set(v) = sp.edit { putLong("live_started", v) }
    /** Leg whose "2 stops from the transfer" notification was already sent (-1 = none). */
    var liveNotifiedLeg: Int
        get() = sp.getInt("live_notified_leg", -1)
        set(v) = sp.edit { putInt("live_notified_leg", v) }

    /** Live trip: use location to notice boarding? null = not asked yet. */
    var liveUseLocation: Boolean?
        get() = if (sp.contains("live_use_loc")) sp.getBoolean("live_use_loc", false) else null
        set(v) = sp.edit { if (v == null) remove("live_use_loc") else putBoolean("live_use_loc", v) }
    /** Seen at the boarding station during this leg (location). */
    var liveWasNear: Boolean
        get() = sp.getBoolean("live_was_near", false)
        set(v) = sp.edit { putBoolean("live_was_near", v) }
    /** Train last seen at the platform while you were there, and when. */
    var liveCandidate: String?
        get() = sp.getString("live_candidate", null)
        set(v) = sp.edit { putString("live_candidate", v) }
    var liveCandidateAt: Long
        get() = sp.getLong("live_candidate_at", 0L)
        set(v) = sp.edit { putLong("live_candidate_at", v) }
    /** Your train's last distance (stops) from where you get off; used to notice you've arrived. */
    var liveLastStops: Float
        get() = sp.getFloat("live_last_stops", 99f)
        set(v) = sp.edit { putFloat("live_last_stops", v) }
    /** Last text shown in the transfer heads-up (so it's only refreshed when something changes). */
    var liveNotifiedText: String
        get() = sp.getString("live_notified_text", "") ?: ""
        set(v) = sp.edit { putString("live_notified_text", v) }

    /** Last good arrival time (minutes, -1 = none) and when — carried over feed dropouts. */
    var liveLastEta: Int
        get() = sp.getInt("live_last_eta", -1)
        set(v) = sp.edit { putInt("live_last_eta", v) }
    var liveLastEtaAt: Long
        get() = sp.getLong("live_last_eta_at", 0L)
        set(v) = sp.edit { putLong("live_last_eta_at", v) }

    /** When your final train reached your destination (0 = not yet); the trip ends 7 min later. */
    var liveArrivedAt: Long
        get() = sp.getLong("live_arrived_at", 0L)
        set(v) = sp.edit { putLong("live_arrived_at", v) }

    /** Closest your train got to where you get off this leg (stops). */
    var liveMinStops: Float
        get() = sp.getFloat("live_min_stops", 99f)
        set(v) = sp.edit { putFloat("live_min_stops", v) }

    /** True when the app (not you) noticed the boarding. */
    var liveAutoBoarded: Boolean
        get() = sp.getBoolean("live_auto_boarded", false)
        set(v) = sp.edit { putBoolean("live_auto_boarded", v) }

    /** Erases all live-trip data (location itself is never stored). */
    fun clearLiveTrip() = sp.edit {
        listOf(
            "live_from", "live_to", "live_leg", "live_train", "live_boarded_at", "live_started",
            "live_notified_leg", "live_was_near", "live_candidate", "live_candidate_at",
            "live_last_stops", "live_auto_boarded", "live_min_stops", "live_notified_text", "live_last_eta", "live_last_eta_at", "live_arrived_at", "live_first_boarded", "live_recorded",
        ).forEach { remove(it) }
    }

    /** Trip history (on this phone only). On by default; turning it off deletes it. */
    var historyEnabled: Boolean
        get() = sp.getBoolean("history_on", true)
        set(v) = sp.edit { putBoolean("history_on", v); if (!v) remove("history") }

    var history: List<TripRecord>
        get() = History.decode(sp.getString("history", null))
        set(v) = sp.edit { putString("history", History.encode(v)) }

    fun clearHistory() = sp.edit { remove("history") }

    /** This live trip has already been saved to the history (so arriving twice doesn't save it twice). */
    var liveRecorded: Boolean
        get() = sp.getBoolean("live_recorded", false)
        set(v) = sp.edit { putBoolean("live_recorded", v) }

    /** When you boarded the first train of the live trip (for the trip's time and fare). */
    var liveFirstBoardedAt: Long
        get() = sp.getLong("live_first_boarded", 0L)
        set(v) = sp.edit { putLong("live_first_boarded", v) }

    /** The two built-in trips were renamed "Going there" / "Coming back". */
    var defaultsV3: Boolean
        get() = sp.getBoolean("defaults_v3", false)
        set(v) = sp.edit { putBoolean("defaults_v3", v) }

    var defaultsV2: Boolean
        get() = sp.getBoolean("defaults_v2", false)
        set(v) = sp.edit { putBoolean("defaults_v2", v) }

    /** The live Silver Line map (all trains, in real time). */
    // ------------------------------------------------------------------ look and feel

    /** "system", "light" or "dark". */
    var themeMode: String
        get() = sp.getString("theme_mode", null)?.takeIf { it in setOf("system", "light", "dark") } ?: "system"
        set(v) = sp.edit { putString("theme_mode", v) }

    /** Stronger colours and outlines, easier to read in sunlight or with low vision. */
    var highContrast: Boolean
        get() = sp.getBoolean("high_contrast", false)
        set(v) = sp.edit { putBoolean("high_contrast", v) }

    /** Everything 20% bigger, on top of the phone's own font size. */
    var largeText: Boolean
        get() = sp.getBoolean("large_text", false)
        set(v) = sp.edit { putBoolean("large_text", v) }

    /** How often times update while the app is open: 15, 30 or 60 s; 0 = only when you pull down. */
    var refreshSeconds: Int
        get() = sp.getInt("refresh_seconds", 30).takeIf { it in setOf(0, 15, 30, 60) } ?: 30
        set(v) = sp.edit { putInt("refresh_seconds", v) }

    /** Weather on the home screen and widget; off = never fetched. */
    var weatherEnabled: Boolean
        get() = sp.getBoolean("weather_enabled", true)
        set(v) = sp.edit { putBoolean("weather_enabled", v) }

    /** Last attempt to download WMATA's bike parking lists. */
    var bikesTriedAt: Long
        get() = sp.getLong("bikes_tried", 0L)
        set(v) = sp.edit { putLong("bikes_tried", v) }

    /** Last attempt to download WMATA's restroom list (successful or not), so a failure isn't retried all day. */
    /** Amtrak, MARC & VRE schedules: when each was last tried (so a failed check waits a day). */
    fun railTriedAt(service: RailService): Long = sp.getLong("rail_tried_${service.name}", 0L)
    fun setRailTriedAt(service: RailService, t: Long) = sp.edit { putLong("rail_tried_${service.name}", t) }

    /** The version whose "What's new" you've seen (null until the first run). */
    var seenVersion: String?
        get() = sp.getString("seen_version", null)
        set(v) = sp.edit { putString("seen_version", v) }

    /** Updates from your PC: its address on your home Wi-Fi (e.g. 192.168.1.20), and the last automatic check. */
    var updateAddress: String
        get() = sp.getString("update_address", "").orEmpty()
        set(v) = sp.edit { putString("update_address", v.trim()) }
    /** The newer version the nightly check found (its version.json), until it's installed. */
    var updateFound: String?
        get() = sp.getString("update_found", null)
        set(v) = sp.edit { if (v == null) remove("update_found") else putString("update_found", v) }
    var updateCheckedAt: Long
        get() = sp.getLong("update_checked", 0L)
        set(v) = sp.edit { putLong("update_checked", v) }

    /** Flight tracking keys (typed in Settings; each service's own). */
    var aviationStackKey: String
        get() = sp.getString("aviationstack_key", "").orEmpty()
        set(v) = sp.edit { putString("aviationstack_key", v.trim()) }
    var flightAwareKey: String
        get() = sp.getString("flightaware_key", "").orEmpty()
        set(v) = sp.edit { putString("flightaware_key", v.trim()) }

    fun flightKey(p: FlightProvider) = if (p == FlightProvider.AVIATIONSTACK) aviationStackKey else flightAwareKey

    /** Which tracking service to use (when this copy has both): the chosen one, else the first with a key. */
    var flightProvider: FlightProvider?
        get() {
            val avail = FlightProvider.available
            val chosen = sp.getString("flight_provider", null)?.let { id -> avail.firstOrNull { it.id == id } }
            return chosen?.takeIf { flightKey(it).isNotBlank() } ?: avail.firstOrNull { flightKey(it).isNotBlank() }
        }
        set(v) = sp.edit { putString("flight_provider", v?.id) }

    /** How early to be at the airport (domestic; international adds an hour). */
    var airportEarlyMin: Int
        get() = sp.getInt("airport_early", 120).coerceIn(30, 240)
        set(v) = sp.edit { putInt("airport_early", v.coerceIn(30, 240)) }

    /** How early to be at the station for Amtrak (Amtrak suggests 30 minutes). */
    var amtrakEarlyMin: Int
        get() = sp.getInt("amtrak_early", 30).coerceIn(5, 90)
        set(v) = sp.edit { putInt("amtrak_early", v.coerceIn(5, 90)) }

    var restroomsTriedAt: Long
        get() = sp.getLong("restrooms_tried", 0L)
        set(v) = sp.edit { putLong("restrooms_tried", v) }

    // ------------------------------------------------------------------ bikes, elevators, news

    /** Riding with a bike: elevator status shows along your trips, in the planner and on live trips. */
    var bikeMode: Boolean
        get() = sp.getBoolean("bike_mode", false)
        set(v) = sp.edit { putBoolean("bike_mode", v) }

    /** A notification when an elevator at one of your stations goes out (off until turned on). */
    var bikeElevatorAlerts: Boolean
        get() = sp.getBoolean("bike_elevator_alerts", false)
        set(v) = sp.edit { putBoolean("bike_elevator_alerts", v) }

    /** Elevator outages already notified (station|place|back-by), so each is sent once. */
    var notifiedElevators: Set<String>
        get() = sp.getStringSet("notified_elevators", emptySet()) ?: emptySet()
        set(v) = sp.edit { putStringSet("notified_elevators", v) }

    /** Capital Bikeshare docks near your stations (off = the app never contacts Capital Bikeshare). */
    var bikeshareOn: Boolean
        get() = sp.getBoolean("bikeshare_on", true)
        set(v) = sp.edit { putBoolean("bikeshare_on", v) }

    /** A notification when WMATA announces a special train or event service. */
    var specialNewsAlerts: Boolean
        get() = sp.getBoolean("special_news_alerts", false)
        set(v) = sp.edit { putBoolean("special_news_alerts", v) }

    var seenSpecialNews: Set<String>
        get() = sp.getStringSet("seen_special_news", emptySet()) ?: emptySet()
        set(v) = sp.edit { putStringSet("seen_special_news", v) }

    /** Show how fast your train is going during a live trip. */
    var showTrainSpeed: Boolean
        get() = sp.getBoolean("train_speed", true)
        set(v) = sp.edit { putBoolean("train_speed", v) }

    /** Where a new trip plan starts (null = your "Going there" station). */
    var plannerStart: String?
        get() = sp.getString("planner_start", null)
        set(v) = sp.edit { if (v == null) remove("planner_start") else putString("planner_start", v) }

    // ------------------------------------------------------------------ trip cards on the home screen

    var cardShowRestroom: Boolean
        get() = sp.getBoolean("card_restroom", true)
        set(v) = sp.edit { putBoolean("card_restroom", v) }

    /** Ride time, stops, transfer and fare chips. */
    var cardShowFacts: Boolean
        get() = sp.getBoolean("card_facts", true)
        set(v) = sp.edit { putBoolean("card_facts", v) }

    /** How many incoming trains each card lists (1–4). */
    var cardTrains: Int
        get() = sp.getInt("card_trains", 4).coerceIn(1, 4)
        set(v) = sp.edit { putInt("card_trains", v.coerceIn(1, 4)) }

    // ------------------------------------------------------------------ quiet hours, clock

    /** No notifications in these hours (the live-trip transfer heads-up still comes through). */
    var quietOn: Boolean
        get() = sp.getBoolean("quiet_on", false)
        set(v) = sp.edit { putBoolean("quiet_on", v) }

    /** Minutes after midnight. Overnight ranges (e.g. 10 pm – 7 am) are fine. */
    var quietStart: Int
        get() = sp.getInt("quiet_start", 22 * 60)
        set(v) = sp.edit { putInt("quiet_start", ((v % 1440) + 1440) % 1440) }

    var quietEnd: Int
        get() = sp.getInt("quiet_end", 7 * 60)
        set(v) = sp.edit { putInt("quiet_end", ((v % 1440) + 1440) % 1440) }

    /** Quiet all weekend too. */
    var quietWeekends: Boolean
        get() = sp.getBoolean("quiet_weekends", false)
        set(v) = sp.edit { putBoolean("quiet_weekends", v) }

    fun inQuietHours(now: java.time.LocalDateTime): Boolean {
        if (!quietOn) return false
        if (quietWeekends && now.dayOfWeek in setOf(java.time.DayOfWeek.SATURDAY, java.time.DayOfWeek.SUNDAY)) return true
        val m = now.hour * 60 + now.minute
        val a = quietStart; val b = quietEnd
        return when {
            a == b -> false
            a < b -> m in a until b
            else -> m >= a || m < b   // overnight
        }
    }

    /** 24-hour times (13:45) instead of 1:45 PM. */
    var clock24: Boolean
        get() = sp.getBoolean("clock_24", false)
        set(v) = sp.edit { putBoolean("clock_24", v) }

    /** The ☰ menu: items in order, each shown or hidden. Settings is always there. */
    var menuItems: List<Pair<MenuItem, Boolean>>
        get() {
            val rawMenu = sp.getString("menu_items", null).orEmpty()
            menuCache?.let { (r, l) -> if (r == rawMenu) return l }
            val saved = sp.getString("menu_items", null).orEmpty().split(",").mapNotNull { e ->
                val (id, on) = e.split(":").let { it.getOrNull(0) to it.getOrNull(1) }
                MenuItem.entries.firstOrNull { it.name == id }?.let { it to (on != "0") }
            }.distinctBy { it.first }.toMutableList()
            for (m in MenuItem.entries) if (saved.none { it.first == m }) {
                val before = MenuItem.entries.take(m.ordinal).lastOrNull { b -> saved.any { it.first == b } }
                saved.add(before?.let { b -> saved.indexOfFirst { it.first == b } + 1 } ?: 0, m to true)
            }
            // Sections left out of this copy of the app aren't in the menu.
            if (!com.metrocast.app.BuildConfig.AIRPORTS) saved.removeAll { it.first == MenuItem.AIRPORTS }
            if (!com.metrocast.app.BuildConfig.RAIL) saved.removeAll { it.first == MenuItem.RAIL }
            return saved.also { menuCache = rawMenu to it }
        }
        set(v) = sp.edit { putString("menu_items", v.joinToString(",") { "${it.first.name}:${if (it.second) 1 else 0}" }) }

    /** The first-run walkthrough was finished (or skipped). */
    var onboarded: Boolean
        get() = sp.getBoolean("onboarded", false)
        set(v) = sp.edit { putBoolean("onboarded", v) }

    /** Track work heads-up: how many days ahead (1, 3 or 7). */
    var trackWorkDays: Int
        get() = sp.getInt("trackwork_days", 3).takeIf { it in setOf(1, 3, 7) } ?: 3
        set(v) = sp.edit { putInt("trackwork_days", v) }

    /** Track work heads-up only when your trip is disrupted (not for "longer waits"). */
    var trackWorkMajorOnly: Boolean
        get() = sp.getBoolean("trackwork_major_only", false)
        set(v) = sp.edit { putBoolean("trackwork_major_only", v) }

    /** Home screen sections in order, each on or off. Unknown or missing ones are added at their default place. */
    var homeSections: List<Pair<HomeSection, Boolean>>
        get() {
            val rawSections = sp.getString("home_sections", null).orEmpty() + "|" + showSilverMap
            sectionsCache?.let { (r, l) -> if (r == rawSections) return l }
            val saved = sp.getString("home_sections", null).orEmpty().split(",").mapNotNull { e ->
                val (id, on) = e.split(":").let { it.getOrNull(0) to it.getOrNull(1) }
                HomeSection.entries.firstOrNull { it.name == id }?.let { it to (on != "0") }
            }.distinctBy { it.first }
            val list0 = saved.toMutableList()
            for (s in HomeSection.entries) if (list0.none { it.first == s }) {
                // After the section that comes before it by default (or at the top).
                val before = HomeSection.entries.take(s.ordinal).lastOrNull { b -> list0.any { it.first == b } }
                val at = before?.let { b -> list0.indexOfFirst { it.first == b } + 1 } ?: 0
                list0.add(at, s to true)
            }
            return list0.let { list ->
                // The live map's on/off is the "Live line map" switch.
                list.map { (s, on) -> s to if (s == HomeSection.MAP) showSilverMap else on }
            }.also { sectionsCache = rawSections to it }
        }
        set(v) {
            v.firstOrNull { it.first == HomeSection.MAP }?.let { showSilverMap = it.second }
            sp.edit { putString("home_sections", v.joinToString(",") { "${it.first.name}:${if (it.second) 1 else 0}" }) }
        }

    /** Which line the home screen's live map shows (Silver unless changed in Settings). */
    var homeMapLine: String
        get() = sp.getString("home_map_line", null)?.takeIf { it in LINES }
            ?: trips.firstOrNull()?.lineCode?.takeIf { it in LINES } ?: "RD"
        set(v) = sp.edit { putString("home_map_line", v) }

    var showSilverMap: Boolean
        get() = sp.getBoolean("silver_map", true)
        set(v) = sp.edit { putBoolean("silver_map", v) }

    /** Identifies which location/units the cached forecast was fetched for. */
    val weatherParams: String get() = "$lat,$lon,$useFahrenheit"

    var trips: List<Trip>
        get() {
            val raw = sp.getString("trips", null) ?: return Stations.DEFAULT_TRIPS
            tripsCache?.let { (r, list) -> if (r == raw) return list }
            return runCatching {
                val arr = JSONArray(raw)
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    Trip(
                        id = o.getString("id"),
                        label = o.optString("label"),
                        lineCode = o.getString("line"),
                        stationCode = o.getString("station"),
                        stationName = o.getString("stationName"),
                        towardCode = o.getString("toward"),
                        towardName = o.getString("towardName"),
                        showOnWidget = o.optBoolean("widget", true),
                        finalCode = o.optString("final").takeIf { it.isNotBlank() },
                        finalName = o.optString("finalName").takeIf { it.isNotBlank() },
                        via = o.optString("via").takeIf { it.isNotBlank() },
                        onHome = o.optBoolean("home", true),
                    )
                }
            }.getOrDefault(Stations.DEFAULT_TRIPS).also { tripsCache = raw to it }
        }
        set(list) {
            val arr = JSONArray()
            list.forEach { t ->
                arr.put(
                    JSONObject()
                        .put("id", t.id).put("label", t.label).put("line", t.lineCode)
                        .put("station", t.stationCode).put("stationName", t.stationName)
                        .put("toward", t.towardCode).put("towardName", t.towardName)
                        .put("widget", t.showOnWidget)
                        .apply { t.finalCode?.let { put("final", it) }; t.finalName?.let { put("finalName", it) }; t.via?.let { put("via", it) }; if (!t.onHome) put("home", false) }
                )
            }
            sp.edit { putString("trips", arr.toString()) }
        }

    fun lineStations(lineCode: String): List<Station>? {
        val raw = sp.getString("line_$lineCode", null) ?: return null
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Station(o.getString("c"), o.getString("n"))
            }
        }.getOrNull()
    }

    fun setLineStations(lineCode: String, stations: List<Station>) {
        val arr = JSONArray()
        stations.forEach { arr.put(JSONObject().put("c", it.code).put("n", it.name)) }
        sp.edit { putString("line_$lineCode", arr.toString()) }
    }

    // ---- raw response cache ----

    private fun cacheFile(key: String) = java.io.File(cacheDir, key.replace(Regex("[^A-Za-z0-9_.-]"), "_") + ".txt")

    fun putCache(key: String, value: String, tag: String = "") {
        val f = cacheFile(key)
        runCatching {
            cacheDir.mkdirs()
            // Written whole, then swapped in: a reader never sees half a file.
            val tmp = java.io.File(cacheDir, f.name + "." + Thread.currentThread().id + ".tmp")
            tmp.writeText(value); if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }
        memo.remove(key)
        sp.edit {
            remove("cache_$key") // an older version kept the data here
            putLong("cache_ts_$key", System.currentTimeMillis())
            putString("cache_tag_$key", tag)
        }
    }

    /** Read once per update, not on every screen build. */
    private val memo = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, String>>()

    fun cached(key: String): String? {
        val ts = cacheTime(key)
        memo[key]?.let { (t, v) -> if (t == ts) return v }
        val f = cacheFile(key)
        val v = if (f.exists()) runCatching { f.readText() }.getOrNull()
            // Moving over from an older version, which kept the data in the settings file.
            else sp.getString("cache_$key", null)?.also { old ->
                runCatching { cacheDir.mkdirs(); f.writeText(old) }.onSuccess { sp.edit { remove("cache_$key") } }
            }
        if (v != null) memo[key] = ts to v
        return v
    }
    fun cacheTime(key: String): Long = sp.getLong("cache_ts_$key", 0L)
    fun cacheTag(key: String): String = sp.getString("cache_tag_$key", "") ?: ""
    fun clearCache(key: String) { memo.remove(key); runCatching { cacheFile(key).delete() }; sp.edit { remove("cache_$key"); remove("cache_ts_$key") } }
}

/** WMATA API key built into the app (Default Tier). A key typed in Settings takes its place. */
/** Built in for your own copy; empty in a public build (people add their own free key). */
val BUILT_IN_WMATA_KEY: String get() = com.metrocast.app.BuildConfig.WMATA_KEY

/** The parts of the home screen, in their default order. */
enum class HomeSection(val title: String, val subtitle: String) {
    WEATHER("Weather now", "Temperature and conditions at the top"),
    TRACKWORK("Track work banner", "When planned work hits your trips this week"),
    ALERTS("Train alerts", "WMATA alerts on your lines"),
    ELEVATORS("Elevator outages", "Elevators out at your stations, live from WMATA"),
    SPEED("Train speed", "How fast your train is going, during a live trip"),
    TRIPS("Your trips", "Going there, Coming back, and next trains"),
    MAP("Live line map", "Every train on one line"),
    RESTROOMS("Restroom note", "Link to stations with restrooms"),
    FORECAST("Forecast", "Next 24 hours and 7 days"),
}

/** The ☰ menu's items (Settings and an active live trip are always shown). */
enum class MenuItem(val emoji: String, val title: String, val subtitle: String) {
    PLANNER("🧭", "Trip planner", "Any station to any station, or follow a trip live"),
    AIRPORTS("✈️", "Airports", "Reagan & Dulles: when to leave for your flight"),
    RAIL("🚄", "Amtrak, MARC & VRE", "When to leave to catch your train"),
    SAVED("★", "Saved routes", "Your starred trips, live"),
    LIVE("🚆", "Live trains", "Every train on one map"),
    MAPS("🚉", "Train maps", "One line at a time, live"),
    OFFICIAL_MAP("🗺", "Official Metro map", "WMATA's map, opens in your browser"),
    HISTORY("📊", "Trip history", "Your time and money on Metro"),
    TRACKWORK("🚧", "Track work calendar", "Planned work on your trips"),
    BIKES("🚲", "Bikes", "Your trips with a bike, elevators, bike rooms"),
    POLICE("🚓", "Metro Police", "Call or text for help"),
    SPECIAL("🎄", "Special trains & events", "Holiday trains, extra service"),
    SCENIC("🌅", "Scenic rides & stations", "Where Metro has a view"),
    RESTROOMS("🚻", "Restrooms", "Stations with a bathroom"),
    ISSUES("⚠️", "Train issues", "Delays and alerts from WMATA"),
}
