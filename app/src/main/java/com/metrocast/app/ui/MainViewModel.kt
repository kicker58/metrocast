package com.metrocast.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.metrocast.app.data.Repository
import com.metrocast.app.data.LineMap
import com.metrocast.app.data.Snapshot
import com.metrocast.app.widget.MetroWidget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class UiState(
    val snapshot: Snapshot,
    val refreshing: Boolean = false,
    val weatherError: String? = null,
    val trainError: String? = null,
    val maps: Map<String, LineMap> = emptyMap(),
    /** The trip being followed live, if any. */
    val live: com.metrocast.app.data.LiveTrip? = null,
    /** Planned track work on your trips (from the last download; no network here). */
    val trackWork: List<com.metrocast.app.data.TrackWork.Impact> = emptyList(),
    /** Elevators out at your stations (from the last download). */
    val elevators: List<com.metrocast.app.data.ElevatorOutage> = emptyList(),
    /** A newer version waiting on your PC (updates copy only). */
    val update: com.metrocast.app.data.Updater.Info? = null,
) {
    val silverMap: LineMap? get() = maps["SV"]
    fun mapFor(line: String): LineMap? = maps[line]
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    val repo = Repository(app)

    private val _state = MutableStateFlow(UiState(snapshot = repo.snapshot()))
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var job: Job? = null
    /** Tests only: what the refresh machinery is doing right now. */
    internal fun debugState() = "refresh running=${job?.isActive == true}, again=$again, retries=$retries, retry waiting=${retryJob?.isActive == true}, extras running=${extrasJob?.isActive == true}"
    // A refresh asked for while one is running (e.g. a new destination) runs right after it.
    private var again = false

    init {
        reloadFromCache()
    }

    /** @param userInitiated shows the pull-to-refresh spinner and forces a weather refresh. */
    fun refresh(userInitiated: Boolean = false) {
        if (job?.isActive == true) {
            again = true
            return
        }
        job = viewModelScope.launch {
            if (userInitiated) _state.update { it.copy(refreshing = true) }
            // Weather on its own: trains never wait for it.
            startWeather(force = userInitiated)
            // Live trains first: the screen updates the moment they're in…
            val trainError = repo.refreshTrainsNow()
            val (snapshot, maps, live) = build()
            val work = trackWork()
            val elev = runCatching { repo.myElevatorOutages() }.getOrDefault(emptyList())
            val upd = runCatching { repo.autoCheckUpdate() }.getOrNull() // what the nightly check found
            _state.update {
                it.copy(
                    snapshot = snapshot,
                    maps = maps,
                    live = live,
                    trackWork = work,
                    elevators = elev,
                    update = upd,
                    refreshing = false,
                    trainError = trainError,
                )
            }
            // Trains didn't load (often the phone's connection still waking up): try again soon,
            // not a whole refresh interval later. 3, 6, 12, 24 seconds, then the normal rhythm.
            if (trainError != null && retries < 4) {
                val wait = 3_000L shl retries
                retries++
                retryJob?.cancel()
                retryJob = viewModelScope.launch { kotlinx.coroutines.delay(wait); refresh() }
            } else if (trainError == null) retries = 0
            runCatching { MetroWidget.notifyChanged(getApplication()) }
            // …then the slower checks (track work, monthly lists, schedules) in their own job, so they
            // never hold up the next live refresh (a monthly schedule download can take minutes).
            startExtras()
            if (again) {
                again = false
                job = null
                refresh()
            }
        }
    }

    private var extrasJob: Job? = null
    private var weatherJob: Job? = null
    private var retryJob: Job? = null
    private var retries = 0

    /** Weather, alongside the trains: when it's in, only the weather part of the screen changes. */
    private fun startWeather(force: Boolean) {
        if (weatherJob?.isActive == true) return
        weatherJob = viewModelScope.launch {
            val err = repo.refreshWeatherNow(force)
            val fresh = withContext(Dispatchers.Default) { repo.snapshot() }
            _state.update {
                it.copy(
                    snapshot = it.snapshot.copy(weather = fresh.weather, weatherUpdatedAt = fresh.weatherUpdatedAt),
                    weatherError = err,
                )
            }
        }
    }


    /** The slower background checks, one at a time, alongside (never in the way of) live refreshes. */
    private fun startExtras() {
        if (extrasJob?.isActive == true) return
        extrasJob = viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                repo.refreshExtras()
                val work = trackWork()
                val elev = runCatching { repo.myElevatorOutages() }.getOrDefault(emptyList())
                _state.update { it.copy(trackWork = work, elevators = elev) }
            }
        }
    }

    /** Updates copy: on opening the app at home (at most every 6 hours), get a new version ready. */
    fun updateCheckOnOpen() {
        if (!com.metrocast.app.data.Updater.enabled) return
        viewModelScope.launch(Dispatchers.IO) {
            val found = runCatching { repo.dailyUpdateCheck(opened = true) }.getOrNull()
            if (found != null) _state.update { it.copy(update = found) }
        }
    }

    /** The update banner: installs the downloaded update now. False if it isn't downloaded (or allowed) yet. */
    fun installReadyUpdate(): Boolean {
        val (info, file) = repo.readyUpdate() ?: return false
        val ctx = getApplication<Application>()
        if (!com.metrocast.app.data.Updater.canInstall(ctx)) return false // Settings asks for the one-time permission
        return com.metrocast.app.data.UpdateInstaller.install(ctx, file, info.versionName)
    }

    /** Call after settings change so the UI reflects them right away. */
    fun reloadFromCache() {
        viewModelScope.launch {
            val (snapshot, maps, live) = build()
            val work = trackWork()
            val elev = runCatching { repo.myElevatorOutages() }.getOrDefault(emptyList())
            val upd = runCatching { repo.autoCheckUpdate() }.getOrNull() // what the nightly check found
            _state.update { it.copy(snapshot = snapshot, maps = maps, live = live, trackWork = work, elevators = elev, update = upd) }
        }
    }

    private suspend fun trackWork() = withContext(Dispatchers.Default) { runCatching { repo.trackWorkImpacts() }.getOrDefault(emptyList()) }

    // Parsing and route-building happen off the main thread so scrolling stays smooth.
    private suspend fun build() = withContext(Dispatchers.Default) {
        runCatching { repo.autoAdvanceLive() }
        Triple(repo.snapshot(), repo.lineMaps(), runCatching { repo.liveTrip() }.getOrNull())
    }
}
