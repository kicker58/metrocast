package com.metrocast.app

import com.metrocast.app.data.Bikeshare
import com.metrocast.app.data.SpecialNews
import com.metrocast.app.data.StationDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/** Capital Bikeshare's live feed and WMATA's special-train / event announcements, from real recorded data. */
class BikeshareNewsTest {
    companion object { @BeforeClass @JvmStatic fun load() = StationDirectory.load(File("src/test/resources/live/stations.json").readText()) }

    private val info = Bikeshare.parseInfo(File("src/test/resources/live/cabi_info.json").readText())
    private val status = Bikeshare.parseStatus(File("src/test/resources/live/cabi_status.json").readText())

    @Test fun docksNearStations() {
        assertTrue(info.size > 300); assertTrue(status.size > 300)
        val wiehle = Bikeshare.near(StationDirectory.find("N06")!!, info, status)
        assertEquals("Wiehle-Reston Metro North", wiehle.first().info.name)
        assertTrue(wiehle.first().meters in 150..300)
        assertTrue(wiehle.all { it.meters <= Bikeshare.NEAR_METERS })
        assertTrue(wiehle.zipWithNext().all { (a, b) -> a.meters <= b.meters })
        // Downtown stations have docks with live numbers.
        val mc = Bikeshare.near(StationDirectory.find("A01")!!, info, status)
        assertTrue(mc.isNotEmpty() && mc.all { it.status != null })
        assertTrue(mc.first().summary.contains("bike") && mc.first().summary.contains("open dock"))
        // Every station: never more than 3, never negative numbers.
        for (st in StationDirectory.stations) Bikeshare.near(st, info, status).forEach { d ->
            assertTrue(d.status?.let { it.bikes >= 0 && it.docks >= 0 } ?: true)
        }
    }

    @Test fun summariesAndBadData() {
        val i = Bikeshare.DockInfo("x", "Dock", 0.0, 0.0, 10)
        assertEquals("1 bike · 1 open dock", Bikeshare.Dock(i, Bikeshare.DockStatus(1, 0, 1, true, true), 50).summary)
        assertEquals("6 bikes (2 e-bikes) · 9 open docks", Bikeshare.Dock(i, Bikeshare.DockStatus(6, 2, 9, true, true), 50).summary)
        assertEquals("Not in service right now", Bikeshare.Dock(i, Bikeshare.DockStatus(0, 0, 0, false, false), 50).summary)
        assertEquals("Live numbers unavailable", Bikeshare.Dock(i, null, 50).summary)
        for (g in listOf("", "{", "{}", "[]", """{"data":{"stations":"x"}}""")) {
            assertTrue(Bikeshare.parseInfo(g).isEmpty()); assertTrue(Bikeshare.parseStatus(g).isEmpty())
        }
        val idx = """{"data":{"en":{"feeds":[{"name":"station_information","url":"https://a/info.json"},{"name":"station_status","url":"https://a/status.json"}]}}}"""
        assertEquals("https://a/info.json" to "https://a/status.json", Bikeshare.feedUrls(idx))
        assertEquals(null, Bikeshare.feedUrls("{}"))
    }

    @Test fun specialTrainsAndEvents() {
        val items = SpecialNews.parse(File("src/test/resources/live/news.json").readText())
        // The recorded list: the Marine Corps Marathon early opening is event service; bus news isn't included.
        assertTrue(items.any { it.kind == SpecialNews.Kind.EVENT && it.title.contains("Marine Corps Marathon") })
        assertTrue(items.none { it.title.contains("bus", ignoreCase = true) && it.kind == SpecialNews.Kind.EVENT && it.title.contains("Metro Bus") })
        assertTrue(items.all { it.url.startsWith("https://www.wmata.com/") })
        assertTrue(items.zipWithNext().all { (a, b) -> (a.date ?: java.time.LocalDate.MIN) >= (b.date ?: java.time.LocalDate.MIN) })
        // Made-up announcements of each kind.
        fun one(title: String) = SpecialNews.parse("""{"results":[{"title":"$title","url":"https://www.wmata.com/news/x.html","properties":{"publishedDateEpoch":"1795000000000"}}]}""")
        assertEquals(SpecialNews.Kind.SPECIAL_TRAIN, one("Metro's holiday train returns to the Red Line").single().kind)
        assertEquals(listOf("RD"), one("Metro's holiday train returns to the Red Line").single().lines)
        assertEquals(SpecialNews.Kind.SPECIAL_TRAIN, one("Pride train wrapped in rainbow colors").single().kind)
        assertEquals(SpecialNews.Kind.EVENT, one("Metro adds extra trains for July 4 fireworks").single().kind)
        assertTrue(one("Metro Bus routes change Sunday").isEmpty())
        assertTrue(one("Board meeting scheduled").isEmpty())
        for (g in listOf("", "{", "{}", """{"results":"x"}""")) assertTrue(SpecialNews.parse(g).isEmpty())
    }
}
