package com.metrocast.app.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import com.metrocast.app.data.UpdateInstaller
import com.metrocast.app.data.Updater

/** How an update MetroCast handed to Android's installer went. */
class UpdateStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!Updater.enabled) return
        val version = intent.getStringExtra(UpdateInstaller.EXTRA_VERSION).orEmpty()
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // Android wants one tap on its Update button (the first time, or on older Android).
                val confirm = androidx.core.content.IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val shown = UpdateInstaller.foreground && runCatching { context.startActivity(confirm) }.isSuccess
                if (!shown) Notifier.updateConfirm(context, version, confirm)
            }
            PackageInstaller.STATUS_SUCCESS -> Unit // MetroCast restarts as the new version; AppUpdatedReceiver says so
            else -> {
                // Didn't install (cancelled, no space…): the usual "ready, tap to install" stays available.
                com.metrocast.app.data.Prefs(context).updateProblem =
                    intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)?.take(120) ?: "The update didn't install."
                if (!UpdateInstaller.foreground && version.isNotBlank()) Notifier.updateReady(context, version)
            }
        }
    }
}

/** MetroCast was just updated: tidy up, and say so (silently) if it happened by itself. */
class AppUpdatedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED || !Updater.enabled) return
        val p = com.metrocast.app.data.Prefs(context)
        runCatching { UpdateInstaller.cleanUp(context) }
        p.updateProblem = null
        Notifier.clearUpdateIfInstalled(context)
        Notifier.updated(context, com.metrocast.app.BuildConfig.VERSION_NAME)
        UpdateCheckWorker.sync(context)
    }
}
