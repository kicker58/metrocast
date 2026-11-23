package com.metrocast.app.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Refreshes the widget about every 30 seconds while the phone is awake.
 *
 * Uses a non-waking alarm: it never wakes a sleeping phone (no battery drain overnight), and the
 * next tick runs as soon as the screen comes back on. The 15-minute WorkManager job stays as a
 * backstop that restarts this chain if Android ever drops it.
 */
class WidgetTicker : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        if (!hasWidgets(app)) return
        val pending = goAsync()
        scope.launch {
            try {
                withTimeoutOrNull(20_000) { MetroWidget.refreshQuietly(app) }
            } finally {
                schedule(app)
                pending.finish()
            }
        }
    }

    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        private fun hasWidgets(context: Context): Boolean = Widgets.anyInstalled(context)

        private fun pendingIntent(context: Context): PendingIntent =
            PendingIntent.getBroadcast(
                context, 0, Intent(context, WidgetTicker::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        /** Queues the next tick in ~30 s (replacing any queued one). */
        fun schedule(context: Context) {
            val every = com.metrocast.app.data.Prefs(context).refreshSeconds
            if (every == 0) { cancel(context); return }
            val am = context.getSystemService(AlarmManager::class.java) ?: return
            // A 10-second window lets Android batch it with other work; no exact-alarm permission needed.
            am.setWindow(
                AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + maxOf(15, every) * 1_000L,
                10_000L,
                pendingIntent(context),
            )
        }

        fun cancel(context: Context) {
            context.getSystemService(AlarmManager::class.java)?.cancel(pendingIntent(context))
        }
    }
}
