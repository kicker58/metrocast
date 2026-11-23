package com.metrocast.app.widget

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Refreshes the widget every 15 minutes (Android's minimum for background work). */
class WidgetRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val repo = com.metrocast.app.data.Repository(applicationContext)
        if (Widgets.anyInstalled(applicationContext)) {
            // A widget is showing: keep it fresh (trains, weather…), and its 30-second ticks going.
            MetroWidget.refreshAll(applicationContext, extras = true)
            WidgetTicker.schedule(applicationContext)
        } else {
            // No widget: just what your turned-on alerts need. Saves battery and data.
            runCatching { repo.alertsOnlyCheck() }
        }
        // Planned track work on your trips: a heads-up a few days ahead (the list is re-fetched every 6 hours).
        runCatching { repo.checkTrackWorkAlerts() }
        runCatching { repo.checkSpecialNews() }            // special trains & events, if turned on
        runCatching { repo.checkElevatorAlerts() }         // bike mode: elevator outages at your stations
        return Result.success()
    }

    companion object {
        private const val NAME = "metrocast-widget-refresh"

        // Background refresh must never take the app down: if WorkManager isn't available yet,
        // skip it (the next app start or widget update schedules it again).
        fun schedule(context: Context) {
            runCatching {
                val request = PeriodicWorkRequestBuilder<WidgetRefreshWorker>(15, TimeUnit.MINUTES)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build()
                WorkManager.getInstance(context)
                    .enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
            }
        }

        fun cancel(context: Context) {
            runCatching { WorkManager.getInstance(context).cancelUniqueWork(NAME) }
        }
    }
}
