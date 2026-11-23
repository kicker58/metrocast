package com.metrocast.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.UnknownHostException

class Repository(context: Context) {
    val appContext: android.content.Context = context.applicationContext
    val prefs = Prefs(appContext)

    init {
        runCatching { prefs.notificationDefaultsV2() }
        // Old one-time trip migrations (long done everywhere): just mark them done.
        prefs.defaultsV2 = true; prefs.defaultsV3 = true
        if (StationDirectory.stations.isEmpty()) {
            stationsFile().takeIf { it.exists() }?.let { f -> runCatching { StationDirectory.load(f.readText()) } }
        }
        runCatching { loadRestroomsFromFile() }
        runCatching { loadBikesFromFiles() }
    }

    data class RefreshResult(val weatherError: String?, val trainError: String?)

    /** Fetches fresh data into the cache. Never throws. */
    /**
     * Trains and weather at the same time (what the screen needs), then — unless [extras] is false —
     * the slower background checks. The app shows trains as soon as the first part is done and runs
     * [refreshExtras] after, so opening it after a while never waits on monthly downloads.
     */
    suspend fun refresh(forceWeather: Boolean = false, extras: Boolean = true): RefreshResult = withContext(Dispatchers.IO) {
        val result = kotlinx.coroutines.coroutineScope {
            val weather = async { refreshWeather(forceWeather) }
            val trains = async { refreshTrains() }
            RefreshResult(weather.await(), trains.await())
        }
        if (extras) refreshExtras()
        result
    }

    /** Live trains only (what the screen needs first). Never throws; returns the error to show, if any. */
    suspend fun refreshTrainsNow(): String? = withContext(Dispatchers.IO) { refreshTrains() }

    /** Weather only: never in the way of trains. Never throws; returns the error to show, if any. */
    suspend fun refreshWeatherNow(force: Boolean = false): String? = withContext(Dispatchers.IO) { refreshWeather(force) }

    /** Track work, restrooms, bike parking, Amtrak/MARC/VRE schedules, elevators: the slower checks. */
    suspend fun refreshExtras() = withContext(Dispatchers.IO) {
        runCatching { loadTrackWork() } // WMATA's planned work; fetched at most every 6 hours
        runCatching { refreshRestrooms() } // WMATA's restroom list; checked about once a month
        runCatching { refreshBikes() }
        if (com.metrocast.app.BuildConfig.RAIL) runCatching { refreshRail() } // Amtrak, MARC & VRE schedules: about once a month     // WMATA's bike parking lists; also about once a month
        if (wantsElevators()) runCatching { loadElevatorOutages(); checkElevatorAlerts() }
        Unit
    }

    private fun refreshWeather(force: Boolean): String? = if (!prefs.weatherEnabled) null else try {
        val age = System.currentTimeMillis() - prefs.cacheTime(KEY_WEATHER)
        val paramsChanged = prefs.cacheTag(KEY_WEATHER) != prefs.weatherParams
        if (force || paramsChanged || age > WEATHER_TTL_MS || prefs.cached(KEY_WEATHER) == null) {
            val raw = WeatherApi.forecastRaw(prefs.lat, prefs.lon, prefs.useFahrenheit)
            WeatherApi.parse(raw, prefs.locationName) // validate before caching
            prefs.putCache(KEY_WEATHER, raw, prefs.weatherParams)
        }
        null
    } catch (e: Exception) {
        friendly(e, "weather")
    }

    private suspend fun refreshTrains(): String? = kotlinx.coroutines.coroutineScope {
        val key = prefs.apiKey
        if (key.isBlank()) return@coroutineScope null
        val trips = prefs.trips
        try {
            // Live data first: arrivals and train positions are asked for straight away, while the
            // slower things (station list, weekly track layout, ride times) load next to them.
            // Station list (names, transfer stations, map coordinates): fetched once, refreshed monthly.
            val stationsJob = if (StationDirectory.stations.isEmpty() || !stationsFile().exists()) async(Dispatchers.IO) {
                runCatching {
                    val raw = WmataApi.allStationsRaw(key)
                    StationDirectory.load(raw)
                    stationsFile().writeText(raw)
                }
            } else null
            // Track layout (line station orders + where trains are): weekly. Needed to place trains.
            val routesJob = async(Dispatchers.IO) {
                runCatching {
                    val routesFile = routesFile()
                    if (!routesFile.exists() || System.currentTimeMillis() - routesFile.lastModified() > ROUTES_TTL_MS) {
                        val routes = WmataApi.standardRoutesRaw(key)
                        TrainTracker.parseRoutes(routes)
                        routesFile.writeText(routes)
                        cachedRoutes = null
                        cachedPositions = null // placed with the old layout
                    }
                }
            }

            // Ride time and fare per trip: fixed data, fetched once per station pair.
            trips.forEach { t -> ridesWanted += t.stationCode to t.destCode }

            // Trip planner: ride times, and the boarding stations that need live predictions (no network).
            val planBoarding = runCatching { preparePlan(key) }.getOrDefault(emptyList())

            val codes = (trips.map { it.stationCode } + planBoarding).distinct()
            // Arrivals, alerts and train positions are fetched at the same time (faster to show).
            // Each one catches its own failure (so a bad answer becomes a friendly message, not a crash,
            // and one failing doesn't cancel the others).
            val predictions = async(Dispatchers.IO) {
                runCatching {
                    if (codes.isNotEmpty()) {
                        val raw = retryOnce { WmataApi.predictionsRaw(codes, key) }
                        WmataApi.parsePredictions(raw).size.also { prefs.putCache(KEY_PREDICTIONS, raw) }
                    } else -1
                }
            }
            // Service alerts: every 5 minutes.
            val alerts = async(Dispatchers.IO) {
                if (System.currentTimeMillis() - prefs.cacheTime(KEY_INCIDENTS) > INCIDENTS_TTL_MS) runCatching {
                    val raw = WmataApi.incidentsRaw(key)
                    val incidents = WmataApi.parseIncidents(raw)
                    prefs.putCache(KEY_INCIDENTS, raw)
                    notifyNewAlerts(incidents)
                }
            }
            // Live positions for every train (trip cards, line maps, Live trains): downloaded now,
            // placed on the track once the layout is in.
            val positions = async(Dispatchers.IO) {
                runCatching {
                    val pos = retryOnce { WmataApi.trainPositionsRaw(key) }
                    routesJob.await()
                    // Trains that can go on a map (with a line, given or worked out from the track).
                    val n = TrainTracker.fillLines(TrainTracker.parsePositions(pos), loadRoutes()).count { it.line != null }
                    prefs.putCache(KEY_POSITIONS, pos)
                    n
                }.getOrNull()
            }

            // Ride times and fares not known yet (first run, a new trip): all at once, next to the live data.
            val missing = synchronized(ridesWanted) { ridesWanted.toList().also { ridesWanted.clear() } }
                .filter { (f, t) -> prefs.cached(rideKey(f, t)) == null }
            val rides = missing.map { (f, t) -> async(Dispatchers.IO) { runCatching { ensureRide(f, t, key) } } }

            alerts.await(); val placed = positions.await()
            rides.forEach { it.await() }; stationsJob?.await(); routesJob.await()
            val arrivals = predictions.await().getOrThrow() // arrivals failing is the error to show
            // WMATA sometimes sends no usable train positions while trains are clearly running (their
            // feed is down): remember it, so the maps can say so instead of just looking empty.
            lastPositionsDown = arrivals > 0 && placed == 0
            null
        } catch (e: Exception) {
            friendly(e, "trains")
        }
    }

    /** One quick second try for a dropped connection (common right after the phone wakes up). */
    private fun <T> retryOnce(f: () -> T): T = try { f() } catch (e: java.io.IOException) {
        if (e is HttpException) throw e
        Thread.sleep(700); f()
    }

    /** WMATA's train-position feed is missing trains right now, while arrivals show them running. */
    val positionsDown: Boolean get() = lastPositionsDown

    /** True if the widget should fetch before drawing. */
    fun needsRefresh(maxAgeMs: Long = 60_000): Boolean {
        val now = System.currentTimeMillis()
        val trainsOld = prefs.apiKey.isNotBlank() && now - prefs.cacheTime(KEY_PREDICTIONS) > maxAgeMs
        val weatherOld = prefs.weatherEnabled && now - prefs.cacheTime(KEY_WEATHER) > WEATHER_TTL_MS
        return trainsOld || weatherOld
    }

    /** Builds a view of everything from the cache (fast, no network). */
    fun snapshot(now: Long = System.currentTimeMillis()): Snapshot {
        val weatherTs = prefs.cacheTime(KEY_WEATHER)
        val weather = prefs.cached(KEY_WEATHER)?.takeIf { prefs.weatherEnabled }?.let {
            runCatching { WeatherApi.parse(it, prefs.locationName, now - weatherTs) }.getOrNull()
        }

        val predTs = prefs.cacheTime(KEY_PREDICTIONS)
        val stale = predTs == 0L || now - predTs > TRAINS_STALE_MS
        val (trains, ageMin) = predictions(now)

        val showLocation = prefs.showTrainLocation
        val posTs = prefs.cacheTime(KEY_POSITIONS)
        val positions = if (showLocation && posTs > 0 && now - posTs <= POSITIONS_STALE_MS) {
            positions(posTs)
        } else null
        val routes = if (positions != null) loadRoutes() else null

        val statuses = prefs.trips.map { trip ->
            val stations = stationsFor(trip.lineCode)
            val location = if (positions != null && routes != null && stations != null) {
                runCatching { TrainTracker.locate(trip, routes, positions, stations) }.getOrNull()
            } else null
            TripStatus(
                trip = trip,
                arrivals = Arrivals.filter(trip, trains, stations?.map { it.code }, ageMin),
                location = location,
                ride = rideInfo(trip, stations),
            )
        }

        return Snapshot(
            weather = weather,
            weatherUpdatedAt = weatherTs,
            trips = statuses,
            trainsUpdatedAt = predTs,
            trainsStale = stale,
            hasApiKey = prefs.apiKey.isNotBlank(),
            showTrainLocation = showLocation,
            plan = runCatching { planStatus(prefs.planFrom, prefs.planTo, trains, ageMin) }.getOrNull(),
            savedRoutes = prefs.savedRoutes.mapNotNull { (f, t) -> runCatching { planStatus(f, t, trains, ageMin) }.getOrNull() },
            incidents = prefs.cached(KEY_INCIDENTS)
                ?.let { runCatching { WmataApi.parseIncidents(it) }.getOrNull() }.orEmpty(),
        )
    }

    private fun rideKey(t: Trip) = rideKey(t.stationCode, t.destCode)
    private fun rideKey(from: String, to: String) = "ride_${from}_$to"

    /** Station pairs whose ride time and fare are needed (filled while planning; fetched together). */
    private val ridesWanted = java.util.Collections.synchronizedSet(LinkedHashSet<Pair<String, String>>())

    private fun ensureRide(from: String, to: String, key: String) {
        val k = rideKey(from, to)
        if (prefs.cached(k) != null) return
        runCatching { WmataApi.rideInfoRaw(from, to, key) }
            .getOrNull()?.let { raw -> if (WmataApi.parseRideInfo(raw) != null) prefs.putCache(k, raw) }
    }

    private fun stationsFile() = File(appContext.filesDir, "stations.json")

    /** Posts a notification for each new WMATA alert on one of your lines (when that's turned on). */
    /**
     * Background check with no widget on screen: only what turned-on alerts need (no trains, no weather,
     * no maps). Delay alerts ask WMATA for service alerts only, at most every 5 minutes.
     */
    fun alertsOnlyCheck(): Int {
        var calls = 0
        if (prefs.alertNotifications && prefs.apiKey.isNotBlank() &&
            System.currentTimeMillis() - prefs.cacheTime(KEY_INCIDENTS) > INCIDENTS_TTL_MS) runCatching {
            val raw = WmataApi.incidentsRaw(prefs.apiKey); calls++
            prefs.putCache(KEY_INCIDENTS, raw)
            notifyNewAlerts(WmataApi.parseIncidents(raw))
        }
        return calls
    }

    private fun notifyNewAlerts(incidents: List<Incident>) {
        val seen = prefs.seenAlertIds
        val myLines = prefs.trips.map { it.lineCode }.toSet() +
            followedRoutes().flatMap { (f, _) -> StationDirectory.find(f)?.lines.orEmpty() }
        if (prefs.alertNotifications) {
            incidents.filter { it.id !in seen && it.lines.any { l -> l in myLines } }.forEach { i ->
                val lines = i.lines.filter { it in myLines }.joinToString(", ") { MetroLine.fromCode(it).displayName }
                com.metrocast.app.notify.Notifier.alert(appContext, i.id.hashCode(), "$lines Line: ${i.type.lowercase()}", i.description)
            }
        }
        // Remember current ones only, so an alert that comes back later is announced again.
        prefs.seenAlertIds = incidents.map { it.id }.toSet()
    }

    /** A station's exits (fixed data, cached per station). */
    suspend fun loadExits(station: StationInfo): List<StationExit> = withContext(Dispatchers.IO) {
        val k = "exits_${station.code}"
        val raw = prefs.cached(k) ?: WmataApi.entrancesRaw(station.lat, station.lon, prefs.apiKey).also { prefs.putCache(k, it) }
        WmataApi.parseEntrances(raw, station.codes)
    }

    /** Fresh train alerts for the Train issues screen. */
    suspend fun loadIssues(): List<Incident> = withContext(Dispatchers.IO) {
        val raw = WmataApi.incidentsRaw(prefs.apiKey)
        prefs.putCache(KEY_INCIDENTS, raw)
        WmataApi.parseIncidents(raw)
    }

    // ------------------------------------------------------------------ your stations

    /** Where your home trips start, change trains and end (each station once). */
    fun myStations(): List<StationInfo> {
        val paths = runCatching { linePaths() }.getOrNull()
        val trips = prefs.trips
        myStationsCache?.let { (t, p, l) -> if (t === trips && p === paths) return l }
        return trips.filter { it.onHome }.flatMap { t ->
            val a = StationDirectory.find(t.stationCode); val b = StationDirectory.find(t.destCode)
            val route = if (a != null && b != null && paths != null) runCatching { Planner.plan(a, b, paths) }.getOrNull() else null
            route?.legs?.let { legs -> legs.map { it.from } + legs.last().to } ?: listOfNotNull(a, b)
        }.distinctBy { it.code }.also { myStationsCache = Triple(trips, paths, it) }
    }

    @Volatile private var myStationsCache: Triple<List<Trip>, List<LinePath>?, List<StationInfo>>? = null

    /** The last elevator status download (no network), if it's recent. */
    fun elevatorOutagesCached(maxAgeMs: Long = 30 * 60_000L): List<ElevatorOutage>? {
        if (System.currentTimeMillis() - prefs.cacheTime(KEY_ELEVATORS) > maxAgeMs) return null
        return prefs.cached(KEY_ELEVATORS)?.let { runCatching { WmataApi.parseElevatorOutages(it) }.getOrNull() }
    }

    /** Elevators out at your stations right now (from the last download). */
    fun myElevatorOutages(): List<ElevatorOutage> {
        val all = elevatorOutagesCached() ?: return emptyList()
        val codes = myStations().flatMap { it.codes }.toSet()
        return all.filter { it.stationCode in codes }
    }

    /** Elevator status is fetched while bike mode or the home "Elevator outages" section wants it. */
    private fun wantsElevators() = prefs.bikeMode || prefs.bikeElevatorAlerts || prefs.homeSections.any { it.first == HomeSection.ELEVATORS && it.second }

    /** Bike mode: one notification per new elevator outage at your stations. */
    fun checkElevatorAlerts(): Int {
        if (!prefs.bikeElevatorAlerts) return 0
        val outs = myElevatorOutages()
        val keys = outs.associateBy { "${it.stationCode}|${it.where}|${it.backBy}" }
        val sent = prefs.notifiedElevators
        val fresh = keys.filterKeys { it !in sent }
        fresh.forEach { (k, o) ->
            val name = StationDirectory.nameOf(o.stationCode) ?: o.stationCode
            com.metrocast.app.notify.Notifier.alert(appContext, k.hashCode(), "Elevator out at $name",
                o.where + (o.backBy?.let { " · WMATA expects it back $it" } ?: "") + ". Riding with a bike? Check the Bikes screen for options.")
        }
        prefs.notifiedElevators = keys.keys   // forget fixed ones, so a repeat outage is announced again
        return fresh.size
    }

    // ------------------------------------------------------------------ Capital Bikeshare

    private fun bikeshareInfoFile() = File(appContext.filesDir, "bikeshare_info.json")

    /** Docks near each station (live bikes and open docks). Empty when turned off. */
    suspend fun loadBikeshare(stations: List<StationInfo>): Map<String, List<Bikeshare.Dock>> = withContext(Dispatchers.IO) {
        if (!prefs.bikeshareOn || stations.isEmpty()) return@withContext emptyMap()
        val f = bikeshareInfoFile()
        val cachedDocks = bikeshareDocks?.takeIf { f.exists() && it.first == f.lastModified() && System.currentTimeMillis() - f.lastModified() < 24 * 60 * 60_000L }
        val infoRaw = if (cachedDocks != null) "" else if (f.exists() && System.currentTimeMillis() - f.lastModified() < 24 * 60 * 60_000L) f.readText()
            else Http.get(Bikeshare.INFO_URL).also { raw -> if (Bikeshare.parseInfo(raw).isNotEmpty()) f.writeText(raw) }
        val stamp = f.lastModified()
        val docks = bikeshareDocks?.takeIf { it.first == stamp && stamp > 0 }?.second
            ?: Bikeshare.parseInfo(infoRaw).also { if (stamp > 0) bikeshareDocks = stamp to it }
        val status = bikeshareStatus?.takeIf { System.currentTimeMillis() - it.first < 60_000L }?.second
            ?: Bikeshare.parseStatus(Http.get(Bikeshare.STATUS_URL)).also { bikeshareStatus = System.currentTimeMillis() to it }
        stations.associate { it.code to Bikeshare.near(it, docks, status) }
    }

    // ------------------------------------------------------------------ special trains & events

    private fun newsFile() = File(appContext.filesDir, "special_news.json")

    // ------------------------------------------------------------------ Amtrak, MARC & VRE

    private fun railFile(s: RailService) = File(appContext.filesDir, "rail_${s.name}.json")
    @Volatile private var railCache: Pair<Long, List<RailTrain>>? = null

    /** Every Metro-area Amtrak, MARC and VRE train: the latest schedules, or the built-in ones. */
    fun railTrains(): List<RailTrain> {
        val stamp = RailService.entries.sumOf { railFile(it).let { f -> if (f.exists()) f.lastModified() else 0L } }
        railCache?.let { if (it.first == stamp) return it.second }
        val all = RailService.entries.flatMap { s ->
            val f = railFile(s)
            val saved = if (f.exists()) runCatching { Rail.fromJson(f.readText()) }.getOrNull().orEmpty() else emptyList()
            saved.ifEmpty { builtInRail(s) }
        }
        railCache = stamp to all
        return all
    }

    private fun builtInRail(s: RailService): List<RailTrain> = runCatching {
        appContext.assets.open("rail_${s.name}.json").bufferedReader().use { Rail.fromJson(it.readText()) }
    }.getOrDefault(emptyList())

    /** When each schedule was last updated (0 = still the built-in one). */
    fun railCheckedAt(s: RailService): Long = railFile(s).takeIf { it.exists() }?.lastModified() ?: 0L

    /**
     * Checks each operator's published schedule about once a month (a failed check waits a day).
     * Amtrak's is a big download (about 19 MB), so it's only fetched on Wi-Fi.
     */
    fun refreshRail(force: Boolean = false): Int {
        var updated = 0
        val now = System.currentTimeMillis()
        for (s in RailService.entries) {
            val f = railFile(s)
            val due = force || !f.exists() || now - f.lastModified() > RESTROOMS_TTL_MS
            if (!due || (!force && now - prefs.railTriedAt(s) < RESTROOMS_RETRY_MS)) continue
            if (s == RailService.AMTRAK && !force && metered()) continue
            prefs.setRailTriedAt(s, now)
            runCatching {
                val trains = Rail.parseGtfs(s, Http.bytes(s.scheduleUrl))
                // A schedule with hardly any trains is a bad download, not a real change.
                if (trains.size >= 5) { f.writeText(Rail.toJson(trains)); updated++ }
            }
        }
        if (updated > 0) railCache = null
        return updated
    }

    private fun metered(): Boolean = runCatching {
        val cm = appContext.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        cm.isActiveNetworkMetered
    }.getOrDefault(true)

    /** Live: is this train on time at [stop]? Asks only that train's operator. */
    suspend fun railLive(run: RailRun, stop: RailStop): RailLive = withContext(Dispatchers.IO) {
        runCatching {
            when (run.train.service) {
                RailService.MARC -> Rail.liveFromFeed(Http.bytes(Rail.MARC_LIVE, 5_000_000), run, stop)
                RailService.VRE -> Rail.liveFromFeed(Http.bytes(Rail.VRE_LIVE, 5_000_000), run, stop)
                RailService.AMTRAK -> Rail.liveFromAmtraker(Http.get(Rail.AMTRAKER + run.train.number), run, stop)
            }
        }.getOrElse { RailLive(null, note = "Live status unavailable right now") }
    }

    // ------------------------------------------------------------------ updates from your PC

    /**
     * Lines of "What's new" to show once, right after an update (not on a fresh install). Null when
     * there's nothing to show; marks the version as seen either way.
     */
    fun whatsNewToShow(): List<String>? {
        val now = com.metrocast.app.BuildConfig.VERSION_NAME
        val seen = prefs.seenVersion
        if (seen == now) return null
        prefs.seenVersion = now
        // Never seen a version: a fresh install (walkthrough instead) — unless setup was already done, which
        // means an update from a version before 1.19 (those didn't remember the version).
        if (seen == null && !prefs.onboarded) return null
        val lines = runCatching { appContext.assets.open("metrocast-whatsnew.txt").bufferedReader().readLines() }.getOrDefault(emptyList())
            .map { it.trim() }.filter { it.isNotEmpty() }
        return lines.ifEmpty { null }
    }

    /** Set by the nightly check when your PC didn't answer (so it tries once more a bit later). */
    @Volatile var pcDidntAnswer = false
        private set

    /** What the nightly check found, if it's newer than this app (no network: just reads it). */
    fun autoCheckUpdate(): Updater.Info? {
        if (!Updater.enabled) return null
        val found = prefs.updateFound?.let { Updater.parse(it) } ?: return null
        return found.takeIf { it.versionCode > com.metrocast.app.BuildConfig.VERSION_CODE }
            ?: null.also { prefs.updateFound = null } // installed since: forget it
    }

    /**
     * The nightly check (around 1 AM, Wi-Fi): asks your PC at most once a day. A newer version is
     * remembered for the home screen, and announced once with a silent notification.
     */
    fun dailyUpdateCheck(now: Long = System.currentTimeMillis(), retry: Boolean = false): Updater.Info? {
        pcDidntAnswer = false
        if (!Updater.enabled || prefs.updateAddress.isBlank()) return null
        if (now - prefs.updateCheckedAt < 20 * 60 * 60_000L) return autoCheckUpdate() // once a day at most
        // Only on your home Wi-Fi (the same network as your PC); anywhere else, don't try.
        if (!Updater.onHomeNetwork(prefs.updateAddress, Updater.phoneWifiAddresses(appContext))) return autoCheckUpdate()
        val result = Updater.check(prefs.updateAddress, com.metrocast.app.BuildConfig.VERSION_CODE)
        // PC off or server not running: the first try of the night doesn't use up the day (one retry later).
        if (result is Updater.Result.Problem && !retry) { pcDidntAnswer = true; return autoCheckUpdate() }
        prefs.updateCheckedAt = now
        val info = (result as? Updater.Result.Available)?.info ?: return autoCheckUpdate()
        val announced = prefs.updateFound?.let { Updater.parse(it) }?.versionCode
        prefs.updateFound = Updater.toJson(info)
        if (announced != info.versionCode) com.metrocast.app.notify.Notifier.updateReady(appContext, info.versionName)
        return info
    }

    // ------------------------------------------------------------------ flights

    /**
     * Looks up a flight with the tracking service chosen in Settings. Only the flight number goes to the
     * service (with your key); nothing is saved. Returns the flight, or a message saying what went wrong.
     */
    suspend fun lookupFlight(designator: String, now: java.time.LocalDateTime): Pair<FlightStatus?, String?> = withContext(Dispatchers.IO) {
        val p = prefs.flightProvider ?: return@withContext null to "Add a flight tracking key in Settings to follow a flight live."
        val key = prefs.flightKey(p)
        runCatching {
            when (p) {
                FlightProvider.AVIATIONSTACK -> {
                    var body = Http.get(Flights.aviationStackUrl(key, designator))
                    if (Flights.needsHttp(body)) body = Http.get(Flights.aviationStackUrl(key, designator, https = false))
                    Flights.aviationStackError(body)?.let { return@runCatching null to it }
                    Flights.parseAviationStack(body, now)?.let { it to null } ?: (null to "No flight ${Flights.pretty(designator)} found.")
                }
                FlightProvider.FLIGHTAWARE -> {
                    val body = Http.get(Flights.flightAwareUrl(designator), mapOf("x-apikey" to key))
                    Flights.parseFlightAware(body, now)?.let { it to null } ?: (null to "No flight ${Flights.pretty(designator)} found.")
                }
            }
        }.getOrElse { e ->
            null to when ((e as? HttpException)?.code) {
                401, 403 -> "${p.title} didn't accept the key — check it in Settings."
                404 -> "No flight ${Flights.pretty(designator)} found."
                429 -> "Too many lookups right now — try again in a bit."
                else -> "Couldn't reach ${p.title} right now."
            }
        }
    }

    /** Gets WMATA's ride times from [from] to each station (cached; quick when already known). */
    suspend fun prepareRailRides(from: String, to: Collection<String>) = withContext(Dispatchers.IO) {
        if (prefs.apiKey.isBlank()) return@withContext
        if (StationDirectory.stations.isEmpty()) runCatching {
            val raw = WmataApi.allStationsRaw(prefs.apiKey); StationDirectory.load(raw); stationsFile().writeText(raw)
        }
        to.filter { it != from }.forEach { runCatching { ensureRide(from, it, prefs.apiKey) } }
        railMinutesMemo.clear()
    }

    /**
     * Minutes riding Metro between two stations: WMATA's time when known, otherwise worked out from
     * the route (about 2 minutes a stop, plus 6 per transfer). Null when there's no route.
     */
    fun railMetroMinutes(from: String, to: String): Pair<Int, Boolean>? =
        railMinutesMemo.getOrPut("$from|$to") { Optional(railMetroMinutesNow(from, to)) }.v

    /** Remembered ride times (cleared when WMATA's times arrive). */
    private val railMinutesMemo = java.util.concurrent.ConcurrentHashMap<String, Optional<Pair<Int, Boolean>>>()
    private class Optional<T>(val v: T?)

    private fun railMetroMinutesNow(from: String, to: String): Pair<Int, Boolean>? {
        val raw: String? = prefs.cached(rideKey(from, to))
        val known: Int? = raw?.let { r -> runCatching { WmataApi.parseRideInfo(r) }.getOrNull() }?.first
        val f = StationDirectory.find(from) ?: return null
        val t = StationDirectory.find(to) ?: return null
        val route = Planner.plan(f, t, linePaths()) ?: return null
        if (known != null) return (known + route.transfers * 6) to false
        return (route.stops * 2 + route.transfers * 6 + 1) to true
    }

    /** WMATA's announcements of special trains and event service: checked about once a month, or on "Check now". */
    fun loadSpecialNews(force: Boolean = false): List<SpecialNews.Item> {
        val f = newsFile()
        val fresh = f.exists() && System.currentTimeMillis() - f.lastModified() < RESTROOMS_TTL_MS
        if (!fresh || force) runCatching {
            val raw = Http.get(SpecialNews.LIST_URL)
            if (runCatching { org.json.JSONObject(raw).getJSONArray("results") }.isSuccess) f.writeText(raw)
        }
        return if (f.exists()) SpecialNews.parse(f.readText()) else emptyList()
    }

    fun specialNewsCheckedAt(): Long = newsFile().takeIf { it.exists() }?.lastModified() ?: 0L

    /** A notification for each newly announced special train or event (when turned on). */
    fun checkSpecialNews(): Int {
        if (!prefs.specialNewsAlerts) return 0
        val items = loadSpecialNews()
        val seen = prefs.seenSpecialNews
        val fresh = if (seen.isEmpty()) emptyList() else items.filter { it.id !in seen } // first look: no flood of old news
        fresh.forEach { com.metrocast.app.notify.Notifier.alert(appContext, it.id.hashCode(), "${it.kind.emoji} ${it.kind.label}", it.title) }
        prefs.seenSpecialNews = seen + items.map { it.id }
        return fresh.size
    }

    // ------------------------------------------------------------------ bikes

    /** Elevators out of service now (kept for 5 minutes). */
    suspend fun loadElevatorOutages(force: Boolean = false): List<ElevatorOutage> = withContext(Dispatchers.IO) {
        val fresh = System.currentTimeMillis() - prefs.cacheTime(KEY_ELEVATORS) < 5 * 60_000L
        val raw = if (fresh && !force) prefs.cached(KEY_ELEVATORS) else null
        val body = raw ?: WmataApi.elevatorsRaw(prefs.apiKey).also { prefs.putCache(KEY_ELEVATORS, it) }
        WmataApi.parseElevatorOutages(body).also { lastElevatorOutages = it }
    }

    private fun bikeFile(name: String) = File(appContext.filesDir, name)

    /** WMATA's Bike & Ride page, checked about once a month (built-in list otherwise). */
    fun refreshBikes(force: Boolean = false): Boolean {
        val f = bikeFile("bike_and_ride.html")
        val now = System.currentTimeMillis()
        val due = force || !f.exists() || now - f.lastModified() > RESTROOMS_TTL_MS
        if (!due || (!force && now - prefs.bikesTriedAt < RESTROOMS_RETRY_MS)) { loadBikesFromFiles(); return false }
        prefs.bikesTriedAt = now
        val html = Http.get(Bikes.BIKE_AND_RIDE_URL, mapOf("Accept" to "text/html"))
        val rooms = Bikes.parseBikeAndRide(html) ?: run { loadBikesFromFiles(); return false }
        f.writeText(html)
        Bikes.useLive("wmata.com, checked ${java.time.LocalDate.now()}", rooms)
        return true
    }

    private fun loadBikesFromFiles() {
        if (Bikes.isLive) return
        val f = bikeFile("bike_and_ride.html").takeIf { it.exists() } ?: return
        val rooms = Bikes.parseBikeAndRide(f.readText()) ?: return
        Bikes.useLive("wmata.com, checked ${java.time.Instant.ofEpochMilli(f.lastModified()).atZone(java.time.ZoneId.systemDefault()).toLocalDate()}", rooms)
    }

    // ------------------------------------------------------------------ restrooms

    private fun restroomsFile() = File(appContext.filesDir, "restrooms.html")

    /**
     * Checks WMATA's restroom page about once a month (sooner if the last try failed) and uses it
     * when it reads correctly; otherwise the list built into the app stays.
     */
    fun refreshRestrooms(force: Boolean = false): Boolean {
        val f = restroomsFile()
        val now = System.currentTimeMillis()
        val due = force || !f.exists() || now - f.lastModified() > RESTROOMS_TTL_MS
        if (!due || (!force && now - prefs.restroomsTriedAt < RESTROOMS_RETRY_MS)) { loadRestroomsFromFile(); return false }
        prefs.restroomsTriedAt = now
        val html = Http.get(Restrooms.PAGE_URL, mapOf("Accept" to "text/html"))
        val parsed = Restrooms.parsePage(html) ?: run { loadRestroomsFromFile(); return false }
        f.writeText(html)
        Restrooms.useLive(parsed.first, parsed.second)
        return true
    }

    private fun loadRestroomsFromFile() {
        if (Restrooms.isLive) return
        val f = restroomsFile().takeIf { it.exists() } ?: return
        Restrooms.parsePage(f.readText())?.let { Restrooms.useLive(it.first, it.second) }
    }

    // ------------------------------------------------------------------ planned track work

    private fun trackWorkFile() = File(appContext.filesDir, "trackwork.json")

    /** Planned work from wmata.com, kept for 6 hours. Nothing about you is sent: these are public pages. */
    fun loadTrackWork(force: Boolean = false): List<TrackWork.Advisory> {
        val f = trackWorkFile()
        val fresh = f.exists() && System.currentTimeMillis() - f.lastModified() < TRACKWORK_TTL_MS
        if (fresh && !force) return trackWorkCached()
        val list = TrackWork.parseList(Http.get(TrackWork.LIST_URL)).map { a ->
            if (!a.needsDetails) a
            else a.copy(changes = runCatching { TrackWork.parsePage(Http.get(a.url, mapOf("Accept" to "text/html")), a.start.year) }.getOrDefault(emptyList()))
        }
        // An empty or broken answer never replaces good data.
        if (list.isEmpty() && f.exists()) return trackWorkCached()
        f.writeText(TrackWork.toJson(list))
        cachedTrackWork = null
        return list
    }

    @Volatile private var cachedTrackWork: Pair<Long, List<TrackWork.Advisory>>? = null

    /** The last download, without going online. */
    fun trackWorkCached(): List<TrackWork.Advisory> {
        val f = trackWorkFile()
        if (!f.exists()) return emptyList()
        val ts = f.lastModified()
        cachedTrackWork?.let { (t, l) -> if (t == ts) return l }
        return TrackWork.fromJson(f.readText()).also { cachedTrackWork = ts to it }
    }

    fun trackWorkUpdatedAt(): Long = trackWorkFile().takeIf { it.exists() }?.lastModified() ?: 0L

    /** What the planned work means for your home trips (Going there, Coming back, and any others). */
    fun trackWorkImpacts(list: List<TrackWork.Advisory> = trackWorkCached(), today: java.time.LocalDate = java.time.LocalDate.now(java.time.ZoneId.of("America/New_York"))): List<TrackWork.Impact> {
        if (list.isEmpty()) return emptyList()
        val paths = runCatching { linePaths() }.getOrNull() ?: return emptyList()
        val trips = prefs.trips.filter { it.onHome }.mapNotNull { t -> runCatching { TrackWork.route(t, paths) }.getOrNull()?.let { t to it } }
        return TrackWork.impacts(list, trips, paths, today)
    }

    /**
     * Heads-up for work on your trips starting within [TRACKWORK_NOTICE_DAYS] days — once per piece of
     * work and trip. Station notices (parking, escalators…) only show in the calendar.
     */
    fun checkTrackWorkAlerts(now: java.time.LocalDate = java.time.LocalDate.now(java.time.ZoneId.of("America/New_York"))): Int {
        if (!prefs.trackWorkAlerts) return 0
        runCatching { loadTrackWork() }
        val levels = if (prefs.trackWorkMajorOnly) setOf(TrackWork.Level.BLOCKED, TrackWork.Level.IN_ZONE)
            else setOf(TrackWork.Level.BLOCKED, TrackWork.Level.IN_ZONE, TrackWork.Level.LINE)
        val due = trackWorkImpacts(today = now).filter { it.level in levels }
            .filter { it.end >= now && it.start.toEpochDay() - now.toEpochDay() <= prefs.trackWorkDays }
        val sent = prefs.trackWorkNotified
        var posted = 0
        // One notification per piece of work, naming every trip it affects.
        due.filter { it.key !in sent }.groupBy { it.advisory.id + "|" + it.change?.line + "|" + it.start }.values.forEach { group ->
            val i = group.minBy { it.level.ordinal }
            val trips = group.map { it.trip }.distinctBy { it.id }.joinToString(" and ") { tripName(it) }
            val line = i.change?.line?.let { MetroLine.fromCode(it).displayName + " Line: " }.orEmpty()
            val title = "🚧 ${TrackWork.whenLabel(i.start, i.end, now)}: track work on $trips"
            val text = "${TrackWork.dates(i.start, i.end)} · $line${i.change?.text ?: i.advisory.title}\n${i.level.note}"
            com.metrocast.app.notify.Notifier.trackWork(appContext, i.key.hashCode(), title, text)
            posted++
        }
        // Remember what was sent; forget work that has ended.
        val live = trackWorkImpacts(today = now).map { it.key }.toSet()
        prefs.trackWorkNotified = (sent.filter { it in live } + due.map { it.key }).toSet()
        return posted
    }

    private fun tripName(t: Trip) = when (t.label) {
        Trip.GOING -> "your Going there trip"
        Trip.BACK -> "your Coming back trip"
        else -> "${t.stationName} → ${t.destName}"
    }

    /** Live positions, parsed once per download (snapshot and maps both use them every refresh). */
    private fun positions(ts: Long): List<TrainTracker.Position>? {
        cachedPositions?.let { (t, list) -> if (t == ts) return list }
        return prefs.cached(KEY_POSITIONS)
            ?.let { runCatching { TrainTracker.fillLines(TrainTracker.parsePositions(it), loadRoutes()) }.getOrNull() }
            ?.also { cachedPositions = ts to it }
    }

    /** Downloads the station list (monthly) so pickers and names work. Never throws. */
    suspend fun ensureStationDirectory(): Boolean = withContext(Dispatchers.IO) {
        val f = stationsFile()
        val fresh = f.exists() && System.currentTimeMillis() - f.lastModified() < 30L * 24 * 60 * 60_000
        if (!fresh || StationDirectory.stations.isEmpty()) {
            runCatching {
                val raw = WmataApi.allStationsRaw(prefs.apiKey)
                StationDirectory.load(raw)
                f.writeText(raw)
            }
        }
        StationDirectory.stations.isNotEmpty()
    }

    private val allLines = listOf("RD", "OR", "SV", "BL", "YL", "GR")

    private fun linePaths(): List<LinePath> {
        val stamp = (cachedRoutes?.first ?: routesFile().lastModified()) xor StationDirectory.stations.size.toLong()
        cachedPaths?.let { (s, p) -> if (s == stamp && p.isNotEmpty()) return p }
        val p = Planner.paths(allLines.mapNotNull { l -> stationsFor(l)?.let { l to it } }.toMap())
        cachedPaths = stamp to p
        return p
    }

    /** All routes the app follows: the planner's current one and the saved ones. */
    private fun followedRoutes(): List<Pair<String, String>> =
        (listOfNotNull(
            prefs.liveFrom?.let { f -> prefs.liveTo?.let { t -> f to t } },
            prefs.planFrom?.let { f -> prefs.planTo?.let { t -> f to t } },
        ) + prefs.savedRoutes).distinct()

    /** Makes sure the planner has what it needs; returns the station codes to get predictions for. */
    private fun preparePlan(key: String): List<String> {
        val routes = followedRoutes()
        if (routes.isEmpty() || StationDirectory.stations.isEmpty()) return emptyList() // station list still loading
        val paths = linePaths()
        return routes.flatMap { (fromCode, toCode) ->
            val from = StationDirectory.find(fromCode) ?: return@flatMap emptyList()
            val to = StationDirectory.find(toCode) ?: return@flatMap emptyList()
            val route = Planner.plan(from, to, paths) ?: return@flatMap emptyList()
            ridesWanted += from.code to to.code
            route.legs.forEach { leg -> leg.options.firstOrNull()?.let { ridesWanted += it.boardCode to it.alightCode } }
            // Boarding stations, plus transfer stations (to see when your train gets there).
            route.legs.flatMap { leg -> leg.options.map { it.boardCode } } +
                route.legs.dropLast(1).flatMap { leg -> leg.options.map { it.alightCode } }
        }
    }

    /** Live inputs from the cached data (positions may be missing if stale). */
    private fun inputs(trains: List<WmataApi.RawTrain>, ageMin: Int, now: Long = System.currentTimeMillis()): LiveInputs {
        val posTs = prefs.cacheTime(KEY_POSITIONS)
        val positions = if (posTs > 0 && now - posTs <= POSITIONS_STALE_MS) positions(posTs) else null
        return LiveInputs(
            paths = linePaths(),
            routes = loadRoutes(),
            positions = positions,
            trains = trains,
            ageMin = ageMin,
            ride = { f, t -> prefs.cached(rideKey(f, t))?.let { runCatching { WmataApi.parseRideInfo(it) }.getOrNull() } },
            incidents = prefs.cached(KEY_INCIDENTS)?.let { runCatching { WmataApi.parseIncidents(it) }.getOrNull() }.orEmpty(),
            now = now,
        )
    }

    private fun planStatus(fromCode: String?, toCode: String?, trains: List<WmataApi.RawTrain>, ageMin: Int): PlanStatus? =
        LiveTripEngine.buildPlan(fromCode, toCode, inputs(trains, ageMin))


    // ---- Live trip: following your train ----

    private fun liveInputs(): Triple<List<TrainTracker.Route>, List<TrainTracker.Position>, Pair<List<WmataApi.RawTrain>, Int>>? {
        val now = System.currentTimeMillis()
        val posTs = prefs.cacheTime(KEY_POSITIONS)
        if (posTs == 0L || now - posTs > POSITIONS_STALE_MS) return null
        val positions = positions(posTs) ?: return null
        val routes = loadRoutes() ?: return null
        return Triple(routes, positions, predictions(now))
    }

    /** The train you'd board on this leg: the one closest to (or at) the boarding platform. */
    fun trainAtBoarding(opt: LegOption): String? {
        val (routes, positions, _) = liveInputs() ?: return null
        return LiveEngine.boardingTrain(opt, routes, positions)?.first
    }

    /** The train at the platform, across every line that works for this leg. */
    private fun trainAtBoardingAny(options: List<LegOption>): String? {
        val (routes, positions, _) = liveInputs() ?: return null
        return LiveEngine.boardingTrainAny(options, routes, positions)
    }

    /** Starts following a trip live (from the planner's current route). */
    fun startLiveTrip(from: String, to: String) {
        prefs.liveFrom = from
        prefs.liveTo = to
        prefs.liveLeg = 0
        prefs.liveTrainId = null
        prefs.liveBoardedAt = 0L
        prefs.liveNotifiedLeg = -1
        prefs.liveStartedAt = System.currentTimeMillis()
        prefs.liveWasNear = false
        prefs.liveCandidate = null
        prefs.liveAutoBoarded = false
        prefs.liveLastStops = 99f
        prefs.liveMinStops = 99f
        prefs.liveLastEta = -1
    }

    /** Ends the live trip and erases everything about it (train followed, boarding notes). */
    fun endLiveTrip() {
        prefs.clearLiveTrip()
        speedTracker.clear(); gpsSpeed = null
        runCatching { com.metrocast.app.notify.Notifier.cancelTrip(appContext) }
        resetPlanner()
    }

    /** Trip over: the planner starts fresh (default start, no destination, just you). */
    fun resetPlanner() {
        prefs.planFrom = null   // reads back as the default start
        prefs.planTo = null
        prefs.riders = 1
    }

    /** "I'm on the train": locks onto the train at (or nearest) your platform. */
    fun boardLiveTrain(trainId: String? = null, auto: Boolean = false) {
        val live = liveTrip() ?: return
        val options = live.legStatus?.leg?.options ?: return
        prefs.liveTrainId = trainId ?: trainAtBoardingAny(options)
        prefs.liveBoardedAt = System.currentTimeMillis()
        if (prefs.liveFirstBoardedAt == 0L) prefs.liveFirstBoardedAt = prefs.liveBoardedAt
        prefs.liveAutoBoarded = auto
        prefs.liveLastStops = 99f
        prefs.liveMinStops = 99f
        prefs.liveLastEta = -1
    }

    /** "I'm off": moves on to the next leg (or finishes). */
    fun nextLiveLeg() {
        prefs.liveLeg = prefs.liveLeg + 1
        prefs.liveTrainId = null
        prefs.liveBoardedAt = 0L
        prefs.liveWasNear = false
        prefs.liveCandidate = null
        prefs.liveAutoBoarded = false
        prefs.liveLastStops = 99f
        prefs.liveMinStops = 99f
        prefs.liveLastEta = -1
    }

    /**
     * Location during a live trip (only while the live screen is open). Notices boarding: you were at
     * the boarding station, then you're moving away fast — so you're on the train that was at the
     * platform. Returns true when it just marked you as on board.
     */
    /** Your train's position along the track right now, for the speed reading. */
    private fun recordSpeed(live: LiveTrip) {
        if (!prefs.showTrainSpeed) return
        val id = live.track?.trainId?.takeIf { it.isNotBlank() } ?: return
        val posTs = prefs.cacheTime(KEY_POSITIONS).takeIf { it > 0 } ?: return
        val p = positions(posTs)?.firstOrNull { it.trainId == id } ?: return
        val r = loadRoutes()?.firstOrNull { (p.line == null || it.line == p.line) && it.indexOfCircuit.containsKey(p.circuit) } ?: return
        val m = TrainSpeed.metersAlong(r, r.indexOfCircuit.getValue(p.circuit)) ?: return
        speedTracker.add(id, posTs, m)
    }

    /**
     * How fast your train is going (null when off, not on a train, or not known yet). The phone's GPS
     * when it has a recent reading (above ground), otherwise from WMATA's positions.
     */
    fun liveSpeed(now: Long = System.currentTimeMillis()): TrainSpeed.Reading? {
        if (!prefs.showTrainSpeed) return null
        gpsSpeed?.let { (t, ms) -> if (now - t < 20_000L) return TrainSpeed.Reading(ms * TrainSpeed.MS_TO_MPH, fromGps = true) }
        return speedTracker.mph()?.let { TrainSpeed.Reading(it, fromGps = false) }
    }

    fun onLiveLocation(lat: Double, lon: Double, speedMs: Float?): Boolean {
        // Speed only (never where you are) is kept, in memory, for the speed reading.
        if (speedMs != null && speedMs >= 0f) gpsSpeed = System.currentTimeMillis() to speedMs
        val live = liveTrip() ?: return false
        if (live.onBoard || live.arrived) return false
        val ls = live.legStatus ?: return false
        val station = ls.leg.from
        if (station.lat == 0.0) return false
        val d = distanceMeters(lat, lon, station.lat, station.lon)
        if (d <= 300) {
            prefs.liveWasNear = true
            // Remember the train at (or closest to) the platform while you're here.
            trainAtBoardingAny(ls.leg.options)?.let { prefs.liveCandidate = it; prefs.liveCandidateAt = System.currentTimeMillis() }
            return false
        }
        val movingFast = (speedMs ?: 0f) > 6f // ~13 mph: faster than walking or a bus in traffic
        if (prefs.liveWasNear && d > 500 && (movingFast || d > 1200)) {
            val recent = prefs.liveCandidate?.takeIf { System.currentTimeMillis() - prefs.liveCandidateAt < 4 * 60_000L }
            boardLiveTrain(recent, auto = true)
            return true
        }
        return false
    }

    /**
     * Moves on by itself once your train has reached the stop where you get off and then left it
     * (from WMATA's live positions, so it works underground) — only when that's certain: the train
     * was seen at the stop and at least half the ride time has passed. Returns true if it moved on.
     */
    fun autoAdvanceLive(): Boolean {
        val before = liveState() ?: return false
        val r = liveUpdate() ?: return false
        val live = r.live ?: return false
        if (LiveTripEngine.shouldAdvance(before, r.state, live, System.currentTimeMillis())) {
            nextLiveLeg()
            return true
        }
        return false
    }

    /** Saves the finished trip to the history (on this phone only), if history is on. */
    private fun recordTrip(live: LiveTrip, arrivedAt: Long) {
        if (!prefs.historyEnabled || prefs.liveRecorded) return
        val from = live.plan.from ?: return
        val to = live.plan.to ?: return
        val boarded = prefs.liveFirstBoardedAt.takeIf { it > 0 } ?: prefs.liveStartedAt
        val zone = java.time.ZoneId.systemDefault()
        val boardedTime = java.time.Instant.ofEpochMilli(boarded).atZone(zone).toLocalDateTime()
        val people = prefs.riders
        val peakFare = live.plan.ride?.peakFare
        val quote = peakFare?.let { Fares.quote(it, live.plan.ride?.offPeakFare ?: it, boardedTime, people) }
        val record = TripRecord(
            at = boarded, from = from.name, to = to.name,
            minutes = ((arrivedAt - boarded) / 60_000L).toInt().coerceAtLeast(1),
            people = people, transfers = live.plan.transfers,
            fare = quote?.total ?: -1.0, peak = quote?.period != Fares.Period.OFF_PEAK, // -1 = fare not known
        )
        prefs.history = History.add(prefs.history, record)
        prefs.liveRecorded = true
    }

    /** Undo a step (e.g. it moved on too early): back to the previous leg, still on board. */
    fun previousLiveLeg() {
        if (prefs.liveLeg <= 0) return
        prefs.liveLeg = prefs.liveLeg - 1
        prefs.liveTrainId = null
        prefs.liveBoardedAt = System.currentTimeMillis() - 60_000L
        prefs.liveLastStops = 99f
        prefs.liveMinStops = 99f
        prefs.liveLastEta = -1
        prefs.liveMinStops = 99f
        prefs.liveNotifiedLeg = -1
    }

    private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = kotlin.math.sin(dLat / 2).let { it * it } +
            kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) * kotlin.math.sin(dLon / 2).let { it * it }
        return 2 * r * kotlin.math.asin(kotlin.math.sqrt(a))
    }

    /** The live trip's current state, from cached data. Null when no live trip is running. */
    private fun liveState(): LiveState? {
        val from = prefs.liveFrom ?: return null
        val to = prefs.liveTo ?: return null
        // A damaged saved trip (stations Metro doesn't have): drop it rather than follow nothing.
        if (StationDirectory.stations.isNotEmpty() && (StationDirectory.find(from) == null || StationDirectory.find(to) == null)) {
            endLiveTrip(); return null
        }
        return LiveState(
            from, to, prefs.liveLeg.coerceAtLeast(0), prefs.liveTrainId, prefs.liveBoardedAt, prefs.liveLastStops, prefs.liveMinStops,
            prefs.liveLastEta.takeIf { it >= 0 }, prefs.liveLastEtaAt,
        )
    }

    private fun saveLiveState(s: LiveState) {
        prefs.liveTrainId = s.trainId
        prefs.liveLastStops = s.lastStops
        prefs.liveMinStops = s.minStopsSeen
        prefs.liveLastEta = s.lastEta ?: -1
        prefs.liveLastEtaAt = s.lastEtaAt
    }

    fun liveTrip(): LiveTrip? = liveUpdate()?.live

    private fun liveUpdate(): LiveTripEngine.Result? {
        val state = liveState() ?: return null
        // Give up on trips started more than 4 hours ago.
        if (System.currentTimeMillis() - prefs.liveStartedAt > 4 * 60 * 60_000L) { endLiveTrip(); return null }
        val (trains, ageMin) = predictions(System.currentTimeMillis())
        val result = LiveTripEngine.update(state, inputs(trains, ageMin))
        if (result.state != state) saveLiveState(result.state)
        val live = result.live ?: return result
        if (live.onBoard) runCatching { recordSpeed(live) }
        if (!live.arrived) {
            if (prefs.liveArrivedAt != 0L) prefs.liveArrivedAt = 0L // e.g. "Back a step" after arriving
            return result
        }
        // Arrived at the destination: end the trip by itself 7 minutes later.
        val now = System.currentTimeMillis()
        if (prefs.liveArrivedAt == 0L) {
            prefs.liveArrivedAt = now
            recordTrip(live, now)
        }
        val left = LiveTripEngine.minutesUntilAutoEnd(prefs.liveArrivedAt, now)
        if (left == 0) {
            endLiveTrip()
            return null
        }
        return result.copy(live = live.copy(autoEndMinutes = left))
    }

    /**
     * Sends the one heads-up per transfer when your train is about 2 stops from it, saying whether to
     * run, hurry or take your time. Called by the live screen and by the background check.
     */
    fun checkLiveNotification(context: android.content.Context) {
        val live = liveTrip() ?: return
        // Moved on past the transfer: the heads-up has done its job.
        if (prefs.liveNotifiedLeg >= 0 && prefs.liveNotifiedLeg != live.leg) {
            com.metrocast.app.notify.Notifier.cancelTrip(context)
            prefs.liveNotifiedLeg = -1
        }
        if (!LiveTripEngine.notificationDue(live)) return
        val (title, body) = LiveTripEngine.notificationText(live) ?: return
        val first = prefs.liveNotifiedLeg != live.leg
        val text = "$title|$body"
        if (!first && text == prefs.liveNotifiedText) return
        prefs.liveNotifiedLeg = live.leg
        prefs.liveNotifiedText = text
        // First time: sound/vibrate. After that: quietly update the same notification.
        com.metrocast.app.notify.Notifier.trip(context, title, body, silent = !first)
    }

    /** Adds or removes the planner's current route from the saved routes. */
    fun toggleSavedRoute(from: String, to: String) {
        val list = prefs.savedRoutes
        prefs.savedRoutes = if ((from to to) in list) list - (from to to) else list + (from to to)
    }

    fun isSaved(from: String?, to: String?): Boolean =
        from != null && to != null && (from to to) in prefs.savedRoutes

    /** Saves the planner's stations (null clears the destination). */
    fun setPlan(from: String?, to: String?) {
        prefs.planFrom = from
        prefs.planTo = to
    }

    private fun rideInfo(trip: Trip, stations: List<Station>?): RideInfo? {
        val stops = if (trip.finalCode != null) transferStops(trip.stationCode, trip.finalCode) else stations?.let { Branches.stopsBetween(trip.lineCode, it.map { s -> s.code }, trip.stationCode, trip.towardCode) }
        val info = prefs.cached(rideKey(trip))?.let { runCatching { WmataApi.parseRideInfo(it) }.getOrNull() }
        if (stops == null && info == null) return null
        return RideInfo(info?.first, stops, info?.second, info?.third)
    }

    /**
     * A home-screen trip between any two stations. With a transfer, the home card follows the first
     * train (to where you change) and still shows the whole trip's time, stops and fare.
     */
    fun buildTrip(fromCode: String, toCode: String, label: String, id: String = java.util.UUID.randomUUID().toString(), showOnWidget: Boolean = true): Trip? {
        val from = StationDirectory.find(fromCode) ?: return null
        val to = StationDirectory.find(toCode) ?: return null
        if (from.code == to.code) return null
        val route = Planner.plan(from, to, linePaths()) ?: return null
        val first = route.legs.first()
        val opt = first.options.first()
        val multi = route.legs.size > 1
        val via = if (!multi) null else route.legs.drop(1).joinToString(", then ") { l ->
            "change at ${l.from.name} to ${l.options.joinToString("/") { MetroLine.fromCode(it.line).displayName }}"
        }.replaceFirstChar { it.uppercase() }
        return Trip(
            id = id, label = label, lineCode = opt.line,
            stationCode = opt.boardCode, stationName = from.name,
            towardCode = opt.alightCode, towardName = first.to.name,
            showOnWidget = showOnWidget,
            finalCode = if (multi) to.code else null, finalName = if (multi) to.name else null, via = via,
        )
    }

    /** Whole-trip stops for a home trip with a transfer; worked out once per station pair and track layout. */
    private fun transferStops(from: String, to: String): Int? {
        val paths = runCatching { linePaths() }.getOrNull() ?: return null
        val key = "$from>$to"
        stopsMemo.takeIf { it.first === paths }?.second?.get(key)?.let { return it.takeIf { n -> n >= 0 } }
        val n = runCatching {
            val a = StationDirectory.find(from); val b = StationDirectory.find(to)
            if (a != null && b != null) Planner.plan(a, b, paths)?.stops else null
        }.getOrNull()
        val memo = stopsMemo.takeIf { it.first === paths }?.second ?: java.util.concurrent.ConcurrentHashMap<String, Int>().also { stopsMemo = paths to it }
        memo[key] = n ?: -1
        return n
    }

    /** [buildTrip], loading the station and line lists first if this is a fresh install. */
    suspend fun buildTripLoading(fromCode: String, toCode: String, label: String, id: String): Trip? {
        buildTrip(fromCode, toCode, label, id)?.let { return it }
        withContext(Dispatchers.IO) { runCatching { ensureStationDirectory() }; runCatching { refresh() } }
        return buildTrip(fromCode, toCode, label, id)
    }

    /** True the first time the app runs after a fresh install (not after an update). */
    fun needsOnboarding(): Boolean {
        if (prefs.onboarded) return false
        val info = runCatching { appContext.packageManager.getPackageInfo(appContext.packageName, 0) }.getOrNull() ?: return true
        return info.firstInstallTime == info.lastUpdateTime
    }

    /** Changes a trip's destination (from the home screen) and returns the updated trip. */
    fun setTripDestination(tripId: String, dest: Station, lineCode: String): Trip? {
        var updated: Trip? = null
        prefs.trips = prefs.trips.map { t ->
            if (t.id != tripId) t else {
                val keepLabel = t.label.isNotBlank() && !t.label.startsWith("To ")
                t.copy(
                    towardCode = dest.code,
                    towardName = Branches.terminalName(lineCode, dest),
                    label = if (keepLabel) t.label else "To ${dest.name}",
                ).also { updated = it }
            }
        }
        return updated
    }

    /** Live maps for every line, from the cached positions (parsed once). */
    fun lineMaps(now: Long = System.currentTimeMillis()): Map<String, LineMap> {
        val posTs = prefs.cacheTime(KEY_POSITIONS)
        if (posTs == 0L || now - posTs > POSITIONS_STALE_MS) return emptyMap()
        val positions = positions(posTs) ?: return emptyMap()
        val routes = loadRoutes() ?: return emptyMap()
        val (trains, ageMin) = predictions(now)
        return allLines.mapNotNull { line ->
            // Silver uses the fixed Ashburn → Downtown Largo order, so "eastbound" always means toward DC.
            val stations = stationsFor(line) ?: return@mapNotNull null
            val map = runCatching { LineMapBuilder.build(line, routes, positions, stations, posTs) }.getOrNull()
                ?: return@mapNotNull null
            line to runCatching { withEtas(map, routes, positions, trains, ageMin) }.getOrDefault(map)
        }.toMap()
    }

    /** Cached predictions (empty when stale) and their age in minutes. */
    private fun predictions(now: Long): Pair<List<WmataApi.RawTrain>, Int> {
        val predTs = prefs.cacheTime(KEY_PREDICTIONS)
        if (predTs == 0L || now - predTs > TRAINS_STALE_MS) return emptyList<WmataApi.RawTrain>() to 0
        val trains = cachedPredictions?.takeIf { it.first == predTs }?.second
            ?: prefs.cached(KEY_PREDICTIONS)?.let { runCatching { WmataApi.parsePredictions(it) }.getOrNull() }.orEmpty()
                .also { cachedPredictions = predTs to it }
        return trains to ((now - predTs) / 60_000L).toInt()
    }

    /**
     * For each trip on this line, labels the trains heading to the trip's station with their time to
     * it (e.g. DC-bound trains before your station → minutes to it). Live predictions at the station are
     * matched to the approaching trains in arrival order; extra trains get an estimate from the stops.
     */
    private fun withEtas(
        map: LineMap,
        routes: List<TrainTracker.Route>,
        positions: List<TrainTracker.Position>,
        trains: List<WmataApi.RawTrain>,
        ageMin: Int,
    ): LineMap {
        val order = map.stations.map { it.code }
        val etas = LinkedHashMap<String, TrainEta>()
        val targets = ArrayList<EtaTarget>()
        val seenDirections = HashSet<Boolean>()
        for (trip in prefs.trips.filter { it.lineCode == map.lineCode }) {
            val here = order.indexOf(trip.stationCode)
            val toward = Branches.indexOf(trip.lineCode, order, trip.towardCode)
            if (here < 0 || toward < 0 || here == toward) continue
            val eastbound = toward > here
            if (!seenDirections.add(eastbound)) continue // one station per direction keeps the map readable
            val stationName = map.stations[here].name
            targets += EtaTarget(stationName, trip.towardName, eastbound)
            val approaching = TrainTracker.locate(trip, routes, positions, map.stations, maxStops = 60f)?.trains.orEmpty()
            val arrivals = Arrivals.filter(trip, trains, order, ageMin, limit = 12)
            approaching.forEachIndexed { i, t ->
                if (t.trainId.isBlank() || etas.containsKey(t.trainId)) return@forEachIndexed
                val a = arrivals.getOrNull(i)
                val short = when {
                    t.stopsAway == 0f -> "here"
                    a == null -> "~${(kotlin.math.ceil(t.stopsAway.toDouble()) * 2.5).toInt()}m"
                    a.kind == ArrivalKind.BOARDING -> "BRD"
                    a.kind == ArrivalKind.ARRIVING || a.minutes == 0 -> "ARR"
                    else -> "${a.minutes}m"
                }
                etas[t.trainId] = TrainEta(short, stationName)
            }
        }
        return map.copy(etas = etas, etaTargets = targets)
    }

    // WMATA's line list still starts Silver at Wiehle-Reston East (pre-extension), so Silver
    // always uses the built-in Ashburn → Downtown Largo order.
    //
    // Other lines come from the track-circuit routes (WMATA's jPath skips stations on some lines,
    // e.g. Orange came back as Vienna → Minnesota Ave → Deanwood → New Carrollton).
    fun stationsFor(lineCode: String): List<Station>? =
        if (lineCode == "SV") Stations.SILVER else routeOrder(lineCode) ?: prefs.lineStations(lineCode)

    /** A line's stations in order, read from the cached StandardRoutes (longest track of the line). */
    private fun routeOrder(lineCode: String): List<Station>? {
        val routes = loadRoutes() ?: return null
        val fallbackNames = prefs.lineStations(lineCode)?.associate { it.code to it.name }.orEmpty()
        return LiveEngine.lineOrder(routes, lineCode) { StationDirectory.nameOf(it) ?: fallbackNames[it] }
    }

    /** Ordered stations for the trip editor; downloads them if needed. */
    suspend fun loadLineStations(lineCode: String): List<Station> = withContext(Dispatchers.IO) {
        if (lineCode == "SV") return@withContext Stations.SILVER
        ensureStationDirectory()
        routeOrder(lineCode)?.let { return@withContext it }
        // No track data yet: download it, then try again.
        runCatching {
            val raw = WmataApi.standardRoutesRaw(prefs.apiKey)
            TrainTracker.parseRoutes(raw)
            routesFile().writeText(raw)
            cachedRoutes = null
        }
        routeOrder(lineCode)?.let { return@withContext it }
        prefs.lineStations(lineCode)?.let { return@withContext it }
        val key = prefs.apiKey
        if (key.isBlank()) {
            if (lineCode == "SV") return@withContext Stations.SILVER
            throw IOException("Add your WMATA API key first to load stations for this line.")
        }
        try {
            WmataApi.lineStations(lineCode, key).also { prefs.setLineStations(lineCode, it) }
        } catch (e: Exception) {
            if (lineCode == "SV") Stations.SILVER else throw IOException(friendly(e, "stations"))
        }
    }

    suspend fun searchPlaces(query: String): List<Place> = withContext(Dispatchers.IO) {
        WeatherApi.searchPlaces(query)
    }

    private fun routesFile() = File(appContext.filesDir, "standard_routes.json")

    private fun loadRoutes(): List<TrainTracker.Route>? {
        val f = routesFile()
        if (!f.exists()) return null
        val stamp = f.lastModified()
        cachedRoutes?.let { (ts, routes) -> if (ts == stamp) return routes }
        return runCatching { TrainTracker.parseRoutes(f.readText()) }.getOrNull()?.also {
            cachedRoutes = stamp to it
        }
    }

    private fun friendly(e: Exception, what: String): String = when (e) {
        is UnknownHostException -> "No internet connection"
        is HttpException -> when (e.code) {
            401, 403 -> "WMATA didn't accept the API key — check it in Settings"
            429 -> "Too many requests — try again in a moment"
            else -> "Couldn't load $what (error ${e.code})"
        }
        else -> e.message ?: "Couldn't load $what"
    }

    companion object {
        const val KEY_WEATHER = "weather"
        const val KEY_PREDICTIONS = "predictions"
        const val KEY_POSITIONS = "positions"
        const val KEY_INCIDENTS = "incidents"
        const val KEY_ELEVATORS = "elevators"
        const val INCIDENTS_TTL_MS = 5 * 60_000L
        const val WEATHER_TTL_MS = 5 * 60_000L
        const val TRAINS_STALE_MS = 30 * 60_000L
        const val POSITIONS_STALE_MS = 5 * 60_000L
        const val ROUTES_TTL_MS = 7 * 24 * 60 * 60_000L
        const val TRACKWORK_TTL_MS = 6 * 60 * 60_000L
        const val RESTROOMS_TTL_MS = 30L * 24 * 60 * 60_000L
        /** Shared with the screens: WMATA's train positions are missing right now. */
        @Volatile var lastPositionsDown = false
        const val RESTROOMS_RETRY_MS = 24 * 60 * 60_000L
        const val TRACKWORK_NOTICE_DAYS = 3

        @Volatile
        private var cachedPredictions: Pair<Long, List<WmataApi.RawTrain>>? = null

        @Volatile
        private var stopsMemo: Pair<List<LinePath>?, java.util.concurrent.ConcurrentHashMap<String, Int>> = null to java.util.concurrent.ConcurrentHashMap()

        /** The latest elevator outages, for the small notes in the planner and live trip (bike mode). */
        @Volatile var lastElevatorOutages: List<ElevatorOutage>? = null

        @Volatile private var bikeshareDocks: Pair<Long, List<Bikeshare.DockInfo>>? = null
        @Volatile private var bikeshareStatus: Pair<Long, Map<String, Bikeshare.DockStatus>>? = null

        // The current trip's speed readings: memory only, cleared when the trip ends.
        private val speedTracker = TrainSpeed.Tracker()
        @Volatile private var gpsSpeed: Pair<Long, Float>? = null

        @Volatile
        private var cachedPaths: Pair<Long, List<LinePath>>? = null

        @Volatile
        private var cachedPositions: Pair<Long, List<TrainTracker.Position>>? = null

        @Volatile
        private var cachedRoutes: Pair<Long, List<TrainTracker.Route>>? = null
    }
}
