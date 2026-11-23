package com.metrocast.app.data

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * If the app ever crashes, a note about it is kept on the phone (never sent anywhere), so it can be
 * looked at or copied from Settings. Only the last few, and only what went wrong in the app's code.
 */
object CrashLog {
    private const val MAX_ENTRIES = 5
    private const val MAX_CHARS = 60_000
    private const val SEP = "\n\n========\n\n"

    private fun file(context: Context) = File(context.applicationContext.filesDir, "crash-log.txt")

    /** Called once at app start: notes a crash, then lets Android handle it as usual. */
    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { record(app, error, thread.name) }
            previous?.uncaughtException(thread, error)
        }
    }

    fun record(context: Context, error: Throwable, where: String = "main", now: LocalDateTime = LocalDateTime.now()) {
        val sw = StringWriter(); error.printStackTrace(PrintWriter(sw))
        val entry = "MetroCast ${com.metrocast.app.BuildConfig.VERSION_NAME} · Android ${android.os.Build.VERSION.RELEASE} · " +
            now.format(DateTimeFormatter.ofPattern("EEE MMM d yyyy, h:mm:ss a")) + " · thread $where\n" + sw.toString().take(12_000)
        val all = (listOf(entry) + entries(context)).take(MAX_ENTRIES)
        file(context).writeText(all.joinToString(SEP).take(MAX_CHARS))
    }

    /** Newest first. */
    fun entries(context: Context): List<String> =
        file(context).takeIf { it.exists() }?.readText()?.split(SEP)?.filter { it.isNotBlank() }.orEmpty()

    fun text(context: Context): String = entries(context).joinToString(SEP)

    fun clear(context: Context) { file(context).delete() }
}
