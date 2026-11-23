package com.metrocast.app

import android.app.Application
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import com.metrocast.app.widget.MetroWidgetReceiver
import com.metrocast.app.widget.WidgetRefreshWorker
import com.metrocast.app.widget.WidgetTicker

class MetroCastApp : Application() {
    override fun onCreate() {
        super.onCreate()
        runCatching { com.metrocast.app.data.CrashLog.install(this) } // a crash is noted on the phone (never sent)
        if (runCatching { com.metrocast.app.widget.Widgets.anyInstalled(this) }.getOrDefault(false)) runCatching { WidgetTicker.schedule(this) }
        // Never let notification or background setup stop the app from opening.
        runCatching { com.metrocast.app.notify.Notifier.createChannels(this) }
        runCatching { com.metrocast.app.notify.Background.sync(this) }
        runCatching { com.metrocast.app.notify.UpdateCheckWorker.sync(this) } // nightly update check (updates copy)
        runCatching { com.metrocast.app.notify.Notifier.clearUpdateIfInstalled(this) } // just updated: no stale "ready"
    }
}
