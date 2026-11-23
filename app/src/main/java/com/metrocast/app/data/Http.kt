package com.metrocast.app.data

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class HttpException(val code: Int) : IOException("HTTP $code")

object Http {
    /** Tests only: answers requests from recorded data instead of the network. Never set in the app. */
    @Volatile
    var testResponder: ((String) -> String)? = null

    /** Tests only: binary answers (zip files, live train feeds). Never set in the app. */
    @Volatile
    var testBytes: ((String) -> ByteArray)? = null

    /** Raw bytes (schedule zips, GTFS-realtime feeds), at most [maxBytes]. */
    fun bytes(url: String, maxBytes: Int = 40_000_000, headers: Map<String, String> = emptyMap()): ByteArray {
        testBytes?.let { return it(url) }
        if (testResponder != null) throw IOException("tests: no recorded bytes for $url") // never the real network in tests
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", "MetroCast/1.0 (Android)")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw HttpException(code)
            conn.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf); if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > maxBytes) throw IOException("too large")
                }
                return out.toByteArray()
            }
        } finally {
            conn.disconnect()
        }
    }

    fun get(url: String, headers: Map<String, String> = emptyMap()): String {
        testResponder?.let { return it(url) }
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "MetroCast/1.0 (Android)")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw HttpException(code)
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}

/** org.json returns the string "null" for JSON nulls; this returns a real null instead. */
fun JSONObject.str(name: String): String? =
    if (!has(name) || isNull(name)) null else optString(name).takeIf { it.isNotEmpty() }
