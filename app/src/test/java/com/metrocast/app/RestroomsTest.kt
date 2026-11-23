package com.metrocast.app

import com.metrocast.app.data.Restrooms
import com.metrocast.app.data.StationDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/** The restroom list must match wmata.com's table station by station (no restroom at the wrong stop). */
class RestroomsTest {
    companion object {
        @BeforeClass @JvmStatic fun load() = StationDirectory.load(File("src/test/resources/live/stations.json").readText())
    }

    // Name as on wmata.com's restroom page → "open" (no station manager needed).
    private val wmataTable = mapOf(
        "Cleveland Park" to false, "Columbia Heights" to false, "Deanwood" to false, "Dupont Circle" to false,
        "Eastern Market" to false, "Foggy Bottom" to false, "Gallery Pl" to false, "L'Enfant Plaza" to false,
        "Metro Center" to false, "Glenmont" to false, "Rockville" to false, "Branch Ave" to false, "Downtown Largo" to false,
        "Greenbelt" to false, "Grosvenor" to false, "New Carrollton" to false, "Shady Grove" to false, "Southern Ave" to false,
        "Ashburn" to true, "Dulles" to true, "Greensboro" to true, "Innovation Center" to true, "Loudoun" to true,
        "McLean" to true, "Potomac Yard" to true, "Spring Hill" to true, "Reston Town Center" to true, "Vienna" to false,
        "Wiehle" to true, "Rosslyn" to false, "Ballston" to false, "Franconia" to false, "Herndon" to true,
        "Huntington" to false, "King St" to false, "Tysons" to true,
    )

    @Test fun everyStationOnWmatasListIsRightAndNothingElse() {
        val withRestroom = StationDirectory.stations.filter { Restrooms.at(it.code) != null }
        assertEquals("stations with restrooms", 36, withRestroom.size)
        for ((name, open) in wmataTable) {
            val st = StationDirectory.stations.filter { it.name.startsWith(name) || it.name.contains(name) }
            assertEquals("exactly one station named like \"$name\": ${st.map { it.name }}", 1, st.size)
            val r = Restrooms.at(st[0].code)
            assertNotNull("${st[0].name} should have a restroom", r)
            assertEquals("${st[0].name}: open without asking?", open, !r!!.askManager)
        }
        // Every code in the list is a real station, and every listed station is in WMATA's table.
        Restrooms.codes.forEach { c -> assertNotNull("unknown code $c", StationDirectory.find(c)) }
        withRestroom.forEach { s -> assertTrue("${s.name} has a restroom but isn't in WMATA's table", wmataTable.keys.any { s.name.contains(it) }) }
        assertEquals(12, withRestroom.count { !Restrooms.at(it.code)!!.askManager })
    }

    @Test fun bothPlatformsAndLabels() {
        assertNotNull(Restrooms.at("A01")); assertNotNull(Restrooms.at("C01"))   // Metro Center, both levels
        assertNotNull(Restrooms.at("B01")); assertNotNull(Restrooms.at("F01"))   // Gallery Place
        assertNull(Restrooms.at("C03"))                                         // Farragut West: none
        assertEquals("🚻 Restroom — open, no need to ask", Restrooms.label(Restrooms.at("N06")!!))
        assertTrue(Restrooms.label(Restrooms.at("C04")!!).contains("ask the station manager"))
        assertTrue(Restrooms.label(Restrooms.at("C04")!!).contains("not yet accessible"))
        assertTrue(Restrooms.label(Restrooms.at("E04")!!).contains("closed for renovation through April 2027"))
    }
}

/** WMATA's restroom page, read the way the monthly update reads it. */
class RestroomsPageTest {
    companion object {
        @BeforeClass @JvmStatic fun load() = StationDirectory.load(File("src/test/resources/live/stations.json").readText())
    }

    @Test fun pageMatchesTheBuiltInListExactly() {
        val builtIn = Restrooms.all.associateBy { it.code }
        val (source, list) = Restrooms.parsePage(File("src/test/resources/live/restrooms.html").readText())!!
        assertEquals("wmata.com, updated 10/30/2026", source)
        assertEquals(36, list.size)
        for (l in list) {
            val b = builtIn[l.code]
            assertNotNull("${l.name} (${l.code}) isn't in the built-in list", b)
            assertEquals(l.name, b!!.region, l.region)
            assertEquals(l.name, b.restroom.askManager, l.restroom.askManager)
            assertEquals(l.name, b.restroom.status, l.restroom.status)
        }
        assertEquals("closed for renovation through April 2027", list.single { it.code == "E04" }.restroom.note)
        // Names WMATA writes differently from the station list.
        assertEquals("B01", Restrooms.codeFor("Gallery Place"))
        assertEquals("N02", Restrooms.codeFor("Tysons Corner"))
        assertEquals("C13", Restrooms.codeFor("King St"))
        assertEquals("N10", Restrooms.codeFor("Dulles"))
        assertNull(Restrooms.codeFor("xy"))
    }

    @Test fun liveListIsUsedThenBuiltInComesBack() {
        val (source, list) = Restrooms.parsePage(File("src/test/resources/live/restrooms.html").readText())!!
        try {
            // Pretend WMATA opened a new walk-in restroom at Metro Center.
            Restrooms.useLive(source, list.map { if (it.code == "A01") it.copy(restroom = Restrooms.Restroom(false, Restrooms.Status.ACCESSIBLE)) else it })
            assertTrue(Restrooms.isLive)
            assertEquals("🚻 Restroom — open, no need to ask", Restrooms.label(Restrooms.at("C01")!!))
            assertEquals("wmata.com, updated 10/30/2026", Restrooms.SOURCE)
        } finally { Restrooms.useLive("", null) }
        assertTrue(!Restrooms.isLive)
        assertTrue(Restrooms.label(Restrooms.at("C01")!!).contains("ask the station manager"))
    }

    @Test fun aRedesignedOrBrokenPageIsIgnored() {
        for (g in listOf("", "<html></html>", "<h3>DC</h3><table><tr><td>x</td></tr></table>", "{\"json\":1}",
            // Too few rows to trust.
            "<h3>DC</h3><table><tr><td>Station</td><td>?</td><td>s</td></tr><tr><td>Rosslyn</td><td>Yes</td><td>ok</td></tr></table>")) {
            assertNull(g.take(30), Restrooms.parsePage(g))
        }
    }
}
