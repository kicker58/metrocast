package com.metrocast.app.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.metrocast.app.data.Arrival
import com.metrocast.app.data.ArrivalKind
import com.metrocast.app.data.Prefs
import com.metrocast.app.data.Repository
import com.metrocast.app.widget.MetroWidget
import com.metrocast.app.widget.MetroWidgetReceiver
import com.metrocast.app.widget.WidgetRefreshWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** Turns background work on or off to match the settings: widget refresh, alerts, track work, bike elevators. */
object Background {
    /**
     * Leave-now reminders were removed: cancel an alarm an older version may have set, and forget
     * their settings. Safe to run every time.
     */
    private fun removeOldLeaveReminders(context: Context, prefs: Prefs) {
        runCatching {
            val pi = PendingIntent.getBroadcast(
                context, 1, Intent().setClassName(context, "com.metrocast.app.notify.LeaveTicker"),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
            )
            if (pi != null) { context.getSystemService(AlarmManager::class.java)?.cancel(pi); pi.cancel() }
        }
        prefs.forgetLeaveReminders()
    }

    fun sync(context: Context) {
        val prefs = Prefs(context)
        val widgets = com.metrocast.app.widget.Widgets.anyInstalled(context)
        // 15-minute background refresh: keeps the widget fresh and checks for new train alerts.
        if (widgets || prefs.alertNotifications || prefs.trackWorkAlerts || prefs.specialNewsAlerts ||
            prefs.bikeElevatorAlerts) WidgetRefreshWorker.schedule(context)
        else WidgetRefreshWorker.cancel(context)
        removeOldLeaveReminders(context, prefs)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            Background.sync(context.applicationContext)
        }
    }
}

/**
 * During a live trip (after "I'm on the train"), checks about once a minute even with the phone
 * locked, so the "2 stops from your transfer" heads-up arrives. Stops by itself when the trip ends.
 */
class LiveTripTicker : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val pending = goAsync()
        scope.launch {
            var keepGoing = false
            try {
                withTimeoutOrNull(25_000) {
                    val repo = Repository(app)
                    if (repo.needsRefresh(40_000)) repo.refresh()
                    repo.autoAdvanceLive()
                    val live = repo.liveTrip()
                    keepGoing = live != null
                    if (live != null && live.onBoard) repo.checkLiveNotification(app)
                }
            } finally {
                if (keepGoing) schedule(app) else cancel(app)
                pending.finish()
            }
        }
    }

    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        private fun pendingIntent(context: Context) = PendingIntent.getBroadcast(
            context, 2, Intent(context, LiveTripTicker::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        fun schedule(context: Context) {
            val am = context.getSystemService(AlarmManager::class.java) ?: return
            // About a minute; Android may stretch this while the phone is idle.
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, android.os.SystemClock.elapsedRealtime() + 60_000L, pendingIntent(context))
        }

        fun cancel(context: Context) {
            context.getSystemService(AlarmManager::class.java)?.cancel(pendingIntent(context))
        }
    }
}
