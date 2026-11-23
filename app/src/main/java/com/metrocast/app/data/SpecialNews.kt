package com.metrocast.app.data

import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Special trains (holiday, Pride, anniversary or historic cars, wraps) and event service (early
 * openings, extra trains for marathons, games, July 4th…) from WMATA's news announcements.
 * WMATA's live feed doesn't say which train is wrapped, so these are announcements, not live dots.
 */
object SpecialNews {
    const val LIST_URL = "https://www.wmata.com/news/_jcr_content/root/container/row-container/main-container/main-editable/news_listing_1205407640.results.json"

    enum class Kind(val label: String, val emoji: String) {
        SPECIAL_TRAIN("Special train", "🎄"),
        EVENT("Event service", "🎉"),
    }

    data class Item(val title: String, val url: String, val date: LocalDate?, val kind: Kind, val lines: List<String>) {
        val id: String get() = url
    }

    private val special = Regex(
        """\b(wrap(ped|s)?|holiday train|holiday express|themed train|special train|decorated|historic (rail)?cars?|vintage|pride train|""" +
            """anniversary train|santa|commemorative train|special livery|art train)\b""", RegexOption.IGNORE_CASE,
    )
    private val event = Regex(
        """\b(open(s|ing)? (early|at \d)|close(s)? late|extended hours|stay(s)? open|extra (trains|service)|additional (trains|service)|""" +
            """marathon|parade|fireworks|july 4|fourth of july|inaugura\w*|festival|cherry blossom|game day|concert|""" +
            """nationals|capitals|commanders|wizards|mystics|d\.?c\.? united|caps|super bowl|world cup|half marathon|race)\b""",
        RegexOption.IGNORE_CASE,
    )
    // Bus-only news isn't about trains.
    private val busOnly = Regex("""\b(metro ?bus|bus routes?|bus stop|buseta|bus mechanic)\b""", RegexOption.IGNORE_CASE)
    private val lineWords = mapOf("Red" to "RD", "Orange" to "OR", "Silver" to "SV", "Blue" to "BL", "Yellow" to "YL", "Green" to "GR")

    /** WMATA's news list → only special trains and event service, newest first. Bad data gives an empty list. */
    fun parse(json: String): List<Item> = runCatching {
        val arr = JSONObject(json).getJSONArray("results")
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val title = o.optString("title").trim().ifBlank { return@mapNotNull null }
            val url = o.optString("url").takeIf { it.startsWith("https://www.wmata.com/") } ?: return@mapNotNull null
            val p = o.optJSONObject("properties")
            val text = title + " " + (p?.optString("jcr:description") ?: "")
            val kind = when {
                special.containsMatchIn(text) -> Kind.SPECIAL_TRAIN
                event.containsMatchIn(text) && !busOnly.containsMatchIn(text) -> Kind.EVENT
                else -> return@mapNotNull null
            }
            val date = p?.optString("publishedDateEpoch")?.toLongOrNull()?.takeIf { it > 0 }
                ?.let { Instant.ofEpochMilli(if (it < 10_000_000_000L) it * 1000 else it).atZone(ZoneId.of("America/New_York")).toLocalDate() }
            Item(title, url, date, kind, lineWords.filter { (w, _) -> Regex("""\b$w Line\b""", RegexOption.IGNORE_CASE).containsMatchIn(text) }.values.toList())
        }.sortedByDescending { it.date }
    }.getOrDefault(emptyList())
}
