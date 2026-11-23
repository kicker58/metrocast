package com.metrocast.app

import com.metrocast.app.data.Platforms
import com.metrocast.app.data.Platforms.Side.LEFT
import com.metrocast.app.data.Platforms.Side.RIGHT
import com.metrocast.app.data.StationDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/** Door sides: the built-in table must match WMATA's Center Platform Stations page exactly. */
class PlatformsTest {
    companion object {
        @BeforeClass @JvmStatic fun load() = StationDirectory.load(File("src/test/resources/live/stations.json").readText())
    }

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    @Test fun tableMatchesWmatasPage() {
        val html = File("src/test/resources/live/center_platforms.html").readText()
        val start = html.indexOf("<h2>Red Line</h2>")
        val lines = mapOf("Red" to "RD", "Orange" to "OR", "Silver" to "SV", "Blue" to "BL", "Yellow" to "YL", "Green" to "GR")
        var checked = 0
        for ((name, code) in lines) {
            val i = html.indexOf("<h2>$name Line</h2>", start)
            val table = html.substring(i, html.indexOf("</table>", i))
            val names = Regex("<td[^>]*>(.*?)</td>", RegexOption.DOT_MATCHES_ALL).findAll(table)
                .map { it.groupValues[1].replace(Regex("<[^>]+>"), "").replace("&nbsp;", "").replace("&#39;", "'").trim() }.filter { it.isNotBlank() }.toList()
            assertEquals("$name Line: stations on WMATA's list", names.size, Platforms.centerCodes(code).size)
            for (n in names) {
                // Each listed station is a center platform on that line in the app.
                val st = StationDirectory.stations.filter { code in it.lines }
                    .firstOrNull { s -> norm(s.name) == norm(n) || norm(n).startsWith(norm(s.name)) || norm(s.name).startsWith(norm(n).take(8)) }
                    ?: throw AssertionError("$name Line: no station for \"$n\"")
                assertEquals("$name Line, ${st.name}", LEFT, Platforms.doorSide(code, st.code)); checked++
            }
        }
        assertTrue(checked > 100)
        // Every other station on each line: side platforms, doors on the right.
        var right = 0
        for ((_, code) in lines) for (s in StationDirectory.stations.filter { code in it.lines }) if (!Platforms.isCenter(code, s.code)) right++
        assertTrue("side-platform stops: $right", right in 40..70)
    }

    @Test fun transferStationsByLevel() {
        // Metro Center: Red upstairs on side platforms; Orange/Silver/Blue downstairs on a center platform.
        assertEquals(RIGHT, Platforms.doorSide("RD", "A01")); assertEquals(LEFT, Platforms.doorSide("OR", "C01")); assertEquals(LEFT, Platforms.doorSide("BL", "A01"))
        // Gallery Place: Red side, Green/Yellow center.
        assertEquals(RIGHT, Platforms.doorSide("RD", "B01")); assertEquals(LEFT, Platforms.doorSide("GR", "F01"))
        // L'Enfant Plaza: Orange/Silver/Blue center, Green/Yellow side.
        assertEquals(LEFT, Platforms.doorSide("SV", "D03")); assertEquals(RIGHT, Platforms.doorSide("GR", "F03")); assertEquals(RIGHT, Platforms.doorSide("YL", "D03"))
        // Fort Totten: both levels center.
        assertEquals(LEFT, Platforms.doorSide("RD", "B06")); assertEquals(LEFT, Platforms.doorSide("GR", "E06"))
        // Your stations.
        assertEquals(LEFT, Platforms.doorSide("SV", "N06")); assertEquals(LEFT, Platforms.doorSide("SV", "C04"))
        assertEquals(RIGHT, Platforms.doorSide("SV", "C02")); assertEquals(RIGHT, Platforms.doorSide("OR", "C05"))
        assertEquals("🚪 Doors open on your left", Platforms.doorText("SV", "N06"))
        // Unknown line or code: no crash.
        assertEquals(RIGHT, Platforms.doorSide("XX", "Z99"))
    }
}
