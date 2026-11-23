package com.metrocast.app

import com.metrocast.app.data.Planner
import com.metrocast.app.data.Scenic
import com.metrocast.app.data.StationDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/** The scenic picks point at real stations, and each ride really is on the lines it names. */
class ScenicTest {
    companion object { @BeforeClass @JvmStatic fun load() = StressTest.load() }

    @Test fun ridesAreDirectOnTheirLines() {
        for (r in Scenic.rides) {
            val a = StationDirectory.find(r.from); val b = StationDirectory.find(r.to)
            assertNotNull(r.title, a); assertNotNull(r.title, b)
            val plan = Planner.plan(a!!, b!!, StressTest.paths)!!
            assertEquals("${r.title}: no transfer", 0, plan.transfers)
            assertTrue("${r.title}: ${plan.legs[0].options.map { it.line }}", r.lines.all { l -> plan.legs[0].options.any { it.line == l } })
            assertTrue("${r.title}: a short ride", plan.stops in 1..4)
        }
        assertEquals("L'Enfant Plaza", StationDirectory.nameOf("F03")); assertEquals("Pentagon", StationDirectory.nameOf("C07"))
    }

    @Test fun spotsAreRealStations() {
        val names = mapOf("B10" to "Wheaton", "A09" to "Bethesda", "C05" to "Rosslyn", "A03" to "Dupont Circle", "B09" to "Forest Glen",
            "B03" to "Union Station", "D02" to "Smithsonian", "C10" to "Ronald Reagan Washington National Airport", "A01" to "Metro Center", "C06" to "Arlington Cemetery")
        for (s in Scenic.spots) {
            val st = StationDirectory.find(s.code)
            assertNotNull(s.title, st)
            assertTrue("${s.title} → ${st!!.name}", st.name.startsWith(names.getValue(s.code).take(10)))
        }
    }
}
