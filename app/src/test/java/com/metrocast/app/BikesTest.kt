package com.metrocast.app

import com.metrocast.app.data.Bikes
import com.metrocast.app.data.StationDirectory
import com.metrocast.app.data.WmataApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/** Bike rooms (SmarTrip card or fob only) from WMATA's page, and live elevator outages. */
class BikesTest {
    companion object { @BeforeClass @JvmStatic fun load() = StationDirectory.load(File("src/test/resources/live/stations.json").readText()) }

    @Test fun pageMatchesBuiltInList() {
        val rooms = Bikes.parseBikeAndRide(File("src/test/resources/live/b_bike-and-ride.html").readText())!!
        assertEquals(8, rooms.size)   // "free and secure facilities at eight stations"
        assertEquals(setOf("E09", "K05", "A11", "C11", "K08"), rooms.filter { it.kind == Bikes.Kind.BIKE_AND_RIDE_METRO }.map { it.code }.toSet())
        assertEquals(setOf("N08", "N09", "N06"), rooms.filter { it.kind == Bikes.Kind.BIKE_AND_RIDE_FAIRFAX }.map { it.code }.toSet())
        assertEquals(rooms.map { it.code to it.kind }.toSet(), Bikes.all.map { it.code to it.kind }.toSet())
        // Only card or fob access.
        assertTrue(Bikes.all.all { it.kind.access.contains("SmarTrip") || it.kind.access.contains("fob") })
        // Lookup by any platform code.
        assertEquals(Bikes.Kind.BIKE_AND_RIDE_FAIRFAX, Bikes.at("N06").single().kind)
        assertTrue(Bikes.at("C02").isEmpty())
    }

    @Test fun brokenPagesAreIgnored() {
        for (g in listOf("", "<html></html>", "<h2>Locations operated by Metro</h2><ul><li>Nowhere</li></ul>")) assertNull(Bikes.parseBikeAndRide(g))
    }

    @Test fun elevatorOutagesOnly() {
        val all = WmataApi.parseElevatorOutages(File("src/test/resources/live/elevators.json").readText())
        assertTrue(all.isNotEmpty())
        assertTrue(all.any { it.stationCode == "A11" && it.where == "Garage elevator" && it.backBy == "2026-11-23" })
        // Escalators are left out.
        val raw = File("src/test/resources/live/elevators.json").readText()
        assertTrue(raw.contains("ESCALATOR")); assertTrue(all.size < Regex("\"UnitType\"").findAll(raw).count())
        assertTrue(WmataApi.parseElevatorOutages("{}").isEmpty())
    }
}
