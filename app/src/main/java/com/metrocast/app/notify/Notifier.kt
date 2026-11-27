package com.metrocast.app.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.metrocast.app.R
import com.metrocast.app.ui.MainActivity

/** Posts MetroCast's two kinds of notifications: "leave now" reminders and train alerts. */
object Notifier {
    private const val ALERTS = "alerts"
    private const val TRIP = "trip"
    private const val WORK = "trackwork"
    private const val UPDATES = "updates"
    const val UPDATE_ID = 4001

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        runCatching { nm.deleteNotificationChannel("leave") } // leave-now reminders were removed
        nm.createNotificationChannel(
            NotificationChannel(ALERTS, "Train alerts", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "WMATA alerts on your lines" }
        )
        nm.createNotificationChannel(
            NotificationChannel(TRIP, "Live trip transfers", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "Heads-up when your train is 2 stops from a transfer" }
        )
        nm.createNotificationChannel(
            NotificationChannel(WORK, "Track work heads-up", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "Planned track work on your trips, a few days ahead" }
        )
        if (com.metrocast.app.data.Updater.enabled) nm.createNotificationChannel(
            NotificationChannel(UPDATES, "App updates", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "A new version is ready on your PC (silent)" }
        )
    }

    /** "MetroCast 1.19 is ready": silent (so fine overnight); tapping it opens Settings at Check for updates. */
    fun updateReady(context: Context, versionName: String) {
        if (!canPost(context)) return
        createChannels(context)
        val open = PendingIntent.getActivity(
            context, 7,
            Intent(context, MainActivity::class.java).putExtra(OPEN, "update")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, UPDATES)
            .setSmallIcon(R.drawable.ic_train)
            .setContentTitle("MetroCast $versionName is ready")
            .setContentText("Tap to install it from your PC")
            .setContentIntent(open)
            .setAutoCancel(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        try { NotificationManagerCompat.from(context).notify(UPDATE_ID, n) } catch (_: SecurityException) {}
    }

    /** "MetroCast 1.27 is ready — tap to install": the tap opens Android's Update button directly. */
    fun updateConfirm(context: Context, versionName: String, confirm: Intent) {
        if (!canPost(context)) return
        createChannels(context)
        val tap = PendingIntent.getActivity(context, 8, confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(context, UPDATES)
            .setSmallIcon(R.drawable.ic_train)
            .setContentTitle("MetroCast $versionName is ready")
            .setContentText("Downloaded from your PC · tap to install")
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        try { NotificationManagerCompat.from(context).notify(UPDATE_ID, n) } catch (_: SecurityException) {}
    }

    /** "MetroCast updated to 1.27" (silent): after an update installed by itself. */
    fun updated(context: Context, versionName: String) {
        if (!canPost(context)) return
        createChannels(context)
        val n = NotificationCompat.Builder(context, UPDATES)
            .setSmallIcon(R.drawable.ic_train)
            .setContentTitle("MetroCast updated to $versionName")
            .setContentText("Tap to see what's new")
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        try { NotificationManagerCompat.from(context).notify(UPDATE_ID, n) } catch (_: SecurityException) {}
    }

    /** The update it announced is installed now (or older): take the notification away. */
    fun clearUpdateIfInstalled(context: Context) {
        val found = com.metrocast.app.data.Prefs(context).updateFound?.let { com.metrocast.app.data.Updater.parse(it) }
        if (found == null || found.versionCode <= com.metrocast.app.BuildConfig.VERSION_CODE) {
            runCatching { NotificationManagerCompat.from(context).cancel(UPDATE_ID) }
            if (found != null) com.metrocast.app.data.Prefs(context).updateFound = null
        }
    }

    /** Extra on the app's start intent: which screen to open ("update"). */
    const val OPEN = "open"

    fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )


    fun trip(context: Context, title: String, text: String, silent: Boolean = false) = post(context, TRIP, 1002, title, text, silent)

    fun cancelTrip(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(1002) }
    }

    fun trackWork(context: Context, id: Int, title: String, text: String) = post(context, WORK, 3000 + (id and 0xFFF), title, text)

    fun alert(context: Context, id: Int, title: String, text: String) = post(context, ALERTS, 2000 + (id and 0xFFF), title, text)

    private fun post(context: Context, channel: String, id: Int, title: String, text: String, silent: Boolean = false) {
        if (!canPost(context)) return
        // Quiet hours: nothing but the live-trip transfer heads-up (you're riding; you want that one).
        if (channel != TRIP && runCatching { com.metrocast.app.data.Prefs(context).inQuietHours(java.time.LocalDateTime.now()) }.getOrDefault(false)) return
        createChannels(context)
        val n = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_train)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .setOnlyAlertOnce(silent)
            .setSilent(silent)
            .setPriority(if (channel == ALERTS) NotificationCompat.PRIORITY_DEFAULT else NotificationCompat.PRIORITY_HIGH)
            .build()
        val allowed = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (allowed) {
            try {
                NotificationManagerCompat.from(context).notify(id, n)
            } catch (_: SecurityException) {
                // Permission was revoked in the meantime; nothing to post.
            }
        }
    }
}
