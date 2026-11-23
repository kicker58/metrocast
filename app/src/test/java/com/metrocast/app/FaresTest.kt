package com.metrocast.app

import com.metrocast.app.data.Fares
import com.metrocast.app.data.Fares.Period
import com.metrocast.app.data.WmataApi
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.time.LocalDateTime

/** Peak (weekdays 5 am–9:30 pm) vs off-peak (weekday nights and weekends), × the distance fare. */
class FaresTest {
    // Nov 2026: 16 Mon … 20 Fri, 21 Sat, 22 Sun.
    private fun t(day: Int, h: Int, m: Int = 0) = LocalDateTime.of(2026, 11, day, h, m)

    @Test fun peakOrOffPeakByDayAndTime() {
        assertEquals(Period.OFF_PEAK, Fares.period(t(16, 4, 59)))   // Monday before 5 am
        assertEquals(Period.PEAK, Fares.period(t(16, 5, 0)))
        assertEquals(Period.PEAK, Fares.period(t(17, 8, 30)))
        assertEquals(Period.PEAK, Fares.period(t(18, 12, 0)))
        assertEquals(Period.PEAK, Fares.period(t(19, 17, 30)))
        assertEquals(Period.PEAK, Fares.period(t(20, 21, 29)))       // Friday 9:29 pm
        assertEquals(Period.OFF_PEAK, Fares.period(t(20, 21, 30)))   // Friday 9:30 pm
        assertEquals(Period.OFF_PEAK, Fares.period(t(20, 23, 45)))
        assertEquals(Period.OFF_PEAK, Fares.period(t(21, 0, 30)))    // after midnight Friday = Saturday
        assertEquals(Period.OFF_PEAK, Fares.period(t(21, 9, 0)))     // Saturday morning
        assertEquals(Period.OFF_PEAK, Fares.period(t(22, 17, 0)))    // Sunday afternoon
    }

    @Test fun exactCostByDistanceAndTime() {
        // Wiehle-Reston East → Foggy Bottom: WMATA's distance fares for the pair.
        val (_, peak, off) = WmataApi.parseRideInfo(File("src/test/resources/live/ride.json").readText())!!
        assertEquals(6.75, Fares.quote(peak!!, off!!, t(16, 8)).total, 1e-9)    // Monday 8 am: peak
        assertEquals(2.50, Fares.quote(peak, off, t(16, 22)).total, 1e-9)       // Monday 10 pm: off-peak
        assertEquals(2.50, Fares.quote(peak, off, t(21, 12)).total, 1e-9)       // Saturday noon: off-peak
        assertEquals(20.25, Fares.quote(peak, off, t(17, 8), people = 3).total, 1e-9)
        assertEquals(7.50, Fares.quote(peak, off, t(22, 14), people = 3).total, 1e-9)
        // A short ride: both prices the same minimum.
        assertEquals(2.25, Fares.quote(2.25, 2.25, t(16, 8)).total, 1e-9)
    }
}
