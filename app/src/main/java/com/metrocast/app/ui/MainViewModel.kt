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
            val result = repo.refresh(forceWeather = userInitiated)
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
                    weatherError = result.weatherError,
                    trainError = result.trainError,
                )
            }
            runCatching { MetroWidget.notifyChanged(getApplication()) }
            if (again) {
                again = false
                job = null
                refresh()
            }
        }
    }

    /** Call after settings change so the UI reflects them right away. */
    fun reloadFromCache() {
        viewModelScope.launch {
            val (snapshot, maps, live) = build()
            val work = trackWork()
            val elev = runCatching { repo.myElevatorOutages() }.getOrDefault(emptyList())
            val upd = runCatching { repo.autoCheckUpdate() }.getOrNull() // what the nightly check found
            _state.update { it.copy(snapshot = snapshot, maps = maps, live = live, trackWork = work, elevators = elev) }
        }
    }

    private suspend fun trackWork() = withContext(Dispatchers.Default) { runCatching { repo.trackWorkImpacts() }.getOrDefault(emptyList()) }

    // Parsing and route-building happen off the main thread so scrolling stays smooth.
    private suspend fun build() = withContext(Dispatchers.Default) {
        runCatching { repo.autoAdvanceLive() }
        Triple(repo.snapshot(), repo.lineMaps(), runCatching { repo.liveTrip() }.getOrNull())
    }
}
