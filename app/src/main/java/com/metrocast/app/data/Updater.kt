package com.metrocast.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Updates from a folder on your own PC, over home Wi-Fi (the "updates" copy of the app only).
 * The PC shares a folder holding version.json and the new APK; nothing goes on the internet.
 * Android only installs an update signed with the same key as the app you have, so a different
 * app can't be slipped in this way.
 */
object Updater {
    const val DEFAULT_PORT = 8765

    /** Tests can turn it on; the app follows how it was built (-Pupdates=true). */
    @JvmField var testEnabled: Boolean? = null
    val enabled: Boolean get() = testEnabled ?: com.metrocast.app.BuildConfig.UPDATES

    /** Tests can pretend the phone has these addresses. */
    @JvmField var testPhoneAddresses: List<String>? = null

    /**
     * The phone's own IPv4 addresses on Wi-Fi right now (e.g. 192.168.1.23). Empty on mobile data.
     * Looks at every network, not just the main one: with a VPN on (e.g. Pixel's "VPN by Google") the main
     * connection is the VPN, while the phone is still on your Wi-Fi underneath.
     */
    @Suppress("DEPRECATION")
    fun phoneWifiAddresses(context: Context): List<String> {
        testPhoneAddresses?.let { return it }
        val out = linkedSetOf<String>()
        runCatching {
            val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
            cm.allNetworks.forEach { n ->
                val caps = cm.getNetworkCapabilities(n)
                if (caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true) {
                    cm.getLinkProperties(n)?.linkAddresses.orEmpty()
                        .mapNotNullTo(out) { (it.address as? java.net.Inet4Address)?.hostAddress }
                }
            }
        }
        // Fallback: the Wi-Fi chip's own address.
        if (out.isEmpty()) runCatching {
            java.net.NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback && it.name.startsWith("wlan") }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<java.net.Inet4Address>()
                .mapNotNullTo(out) { it.hostAddress }
        }
        return out.toList()
    }

    /** "This phone is at 192.168.1.23 on Wi-Fi." / "This phone isn't on Wi-Fi right now." — to show next to a problem. */
    fun whereThePhoneIs(phoneAddresses: List<String>): String =
        if (phoneAddresses.isEmpty()) "This phone isn't on Wi-Fi right now (or a VPN is hiding it)."
        else "This phone is at ${phoneAddresses.joinToString(" / ")} on Wi-Fi."

    /**
     * True when the phone is on your home network: on Wi-Fi with an address in the same block as your
     * PC (192.168.1.x for a PC at 192.168.1.20). Anywhere else, the app doesn't try to reach it.
     */
    fun onHomeNetwork(pcAddress: String, phoneAddresses: List<String>): Boolean {
        val host = base(pcAddress)?.substringAfter("://")?.substringBefore(':') ?: return false
        val pc = host.split('.').takeIf { it.size == 4 && it.all { p -> p.toIntOrNull() in 0..255 } } ?: return false
        return phoneAddresses.any { ip -> ip.split('.').let { it.size == 4 && it.take(3) == pc.take(3) } }
    }

    /** "192.168.1" for "192.168.1.20" (what to show people), or null. */
    fun homeBlock(pcAddress: String): String? =
        base(pcAddress)?.substringAfter("://")?.substringBefore(':')?.split('.')?.takeIf { it.size == 4 }?.take(3)?.joinToString(".")

    /** Time from [now] until the next 1:00 AM (the nightly check). */
    fun untilOneAm(now: java.time.LocalDateTime): java.time.Duration {
        var next = now.toLocalDate().atTime(1, 0)
        if (!next.isAfter(now)) next = next.plusDays(1)
        return java.time.Duration.between(now, next)
    }

    fun toJson(i: Info): String = JSONObject().put("versionCode", i.versionCode).put("versionName", i.versionName)
        .put("apk", i.apk).put("sha256", i.sha256).put("notes", i.notes).toString()

    data class Info(val versionCode: Int, val versionName: String, val apk: String, val sha256: String, val notes: String)

    sealed class Result {
        data class Available(val info: Info) : Result()
        data class UpToDate(val versionName: String) : Result()
        data class Problem(val message: String) : Result()
    }

    /** "192.168.1.20" → "http://192.168.1.20:8765/"; keeps a port or http:// you typed. Null if it isn't an address. */
    fun base(address: String): String? {
        var a = address.trim().removeSuffix("/")
        if (a.isEmpty()) return null
        if (!a.startsWith("http://") && !a.startsWith("https://")) a = "http://$a"
        val host = a.substringAfter("://").substringBefore('/')
        if (host.isBlank() || host.any { it.isWhitespace() }) return null
        if (':' !in host) a = a.replaceFirst(host, "$host:$DEFAULT_PORT")
        return "$a/"
    }

    fun parse(json: String): Info? = runCatching {
        val o = JSONObject(json)
        val apk = o.getString("apk")
        // Just a file name in the same folder: no paths, nothing elsewhere.
        if (apk.contains('/') || apk.contains('\\') || !apk.endsWith(".apk")) return@runCatching null
        Info(o.getInt("versionCode"), o.optString("versionName"), apk, o.getString("sha256").lowercase(), o.optString("notes"))
    }.getOrNull()

    fun check(address: String, currentVersion: Int): Result {
        val b = base(address) ?: return Result.Problem("That doesn't look like an address. It's like 192.168.1.20 (shown by the update server on your PC).")
        val body = runCatching { Http.get(b + "version.json") }.getOrElse {
            return Result.Problem("Couldn't reach your PC at ${b.removeSuffix("/")}. Is the update server running, and is the phone on the same Wi-Fi?")
        }
        val info = parse(body) ?: return Result.Problem("Your PC answered, but its version.json isn't right.")
        return if (info.versionCode > currentVersion) Result.Available(info) else Result.UpToDate(info.versionName)
    }

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Downloads the update and checks it's the exact file version.json describes. */
    fun download(context: Context, address: String, info: Info): File {
        val b = base(address) ?: error("bad address")
        val bytes = Http.bytes(b + info.apk, maxBytes = 80_000_000)
        require(sha256(bytes) == info.sha256) { "The download didn't match (it may have been cut off). Try again." }
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() } // only ever the latest
        return File(dir, "MetroCast-update.apk").apply { writeBytes(bytes) }
    }

    /** True when Android lets MetroCast install updates (a one-time switch in Settings). */
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    /** Android's "Install unknown apps" switch for MetroCast. */
    fun allowInstallsIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Opens Android's installer for the downloaded update (you confirm it there). */
    fun installIntent(context: Context, apk: File): Intent {
        val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        return Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
