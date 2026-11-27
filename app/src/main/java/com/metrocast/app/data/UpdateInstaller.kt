package com.metrocast.app.data

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import java.io.File

/**
 * Updates copy only: gets a new version ready ahead of time and installs it with Android's installer.
 *
 * The first update MetroCast installs itself needs one tap on Android's "Update" button. After that,
 * Android 12 and later let MetroCast update itself without asking (MetroCast is then the app that
 * installed it). When Android does want a tap, the confirmation opens right away if you're in the app,
 * or comes as a notification that opens it directly.
 */
object UpdateInstaller {
    const val ACTION_STATUS = "com.metrocast.app.UPDATE_STATUS"
    const val EXTRA_VERSION = "metrocast_version"

    /** Tests only: stands in for Android's installer. */
    @JvmField var testInstall: ((File) -> Boolean)? = null

    /** True while MetroCast is on screen (a confirmation can open right away). */
    @Volatile @JvmField var foreground = false

    private fun dir(context: Context) = File(context.filesDir, "updates")

    /** Where the update for [info] is kept once it's downloaded and checked. */
    fun readyFile(context: Context, info: Updater.Info) = File(dir(context), "MetroCast-${info.versionCode}.apk")

    /** The download for [info], if it's already here and is exactly the file version.json describes. */
    fun ready(context: Context, info: Updater.Info): File? =
        readyFile(context, info).takeIf { f -> f.exists() && runCatching { Updater.sha256(f.readBytes()) == info.sha256 }.getOrDefault(false) }

    /** Downloads the update from your PC (if it isn't here yet), checks it, and keeps it until it's installed. */
    fun prepare(context: Context, address: String, info: Updater.Info): File {
        ready(context, info)?.let { return it }
        val b = Updater.base(address) ?: error("bad address")
        val bytes = Http.bytes(b + info.apk, maxBytes = 80_000_000)
        require(Updater.sha256(bytes) == info.sha256) { "The download didn't match (it may have been cut off). Try again." }
        val d = dir(context).apply { mkdirs() }
        d.listFiles()?.forEach { it.delete() } // only ever the latest
        val f = readyFile(context, info)
        val tmp = File(d, f.name + ".part")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        return f
    }

    /** Removes downloads of versions that are installed now (or older). */
    fun cleanUp(context: Context) {
        dir(context).listFiles()?.forEach { f ->
            val code = Regex("""MetroCast-(\d+)\.apk""").matchEntire(f.name)?.groupValues?.get(1)?.toIntOrNull()
            if (code == null || code <= com.metrocast.app.BuildConfig.VERSION_CODE) f.delete()
        }
        // The older way (cache/updates) kept one copy there too.
        File(context.cacheDir, "updates").listFiles()?.forEach { it.delete() }
    }

    /**
     * Hands [apk] to Android's installer, asking for no confirmation where Android allows it.
     * The outcome arrives in [com.metrocast.app.notify.UpdateStatusReceiver]. False if it couldn't start.
     */
    fun install(context: Context, apk: File, versionName: String): Boolean {
        testInstall?.let { return it(apk) }
        return runCatching {
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(context.packageName)
                setSize(apk.length())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            val id = installer.createSession(params)
            installer.openSession(id).use { session ->
                session.openWrite("metrocast.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                val status = Intent(context, com.metrocast.app.notify.UpdateStatusReceiver::class.java)
                    .setAction(ACTION_STATUS).putExtra(EXTRA_VERSION, versionName)
                // Mutable: Android fills in how it went.
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
                session.commit(PendingIntent.getBroadcast(context, 77, status, flags).intentSender)
            }
            true
        }.getOrDefault(false)
    }
}
