package com.metrocast.app

import com.metrocast.app.ui.moved
import org.junit.Assert.assertEquals
import org.junit.Test

/** Settings' move arrows, tapped faster than the screen redraws. */
class MoveRowsTest {
    private val rows = listOf("a", "b", "c", "d")

    @Test fun movesTheRowFromWhereItIsNow() {
        // Two quick taps on "c ▼": the second finds c where the first put it.
        val once = rows.moved(1) { it == "c" }
        assertEquals(listOf("a", "b", "d", "c"), once)
        assertEquals(once, once.moved(1) { it == "c" }) // already last: nothing happens, no crash
        assertEquals(listOf("c", "a", "b", "d"), rows.moved(-1) { it == "c" }.moved(-1) { it == "c" })
    }

    @Test fun neverPastTheEndsOrAboveTheFixedRows() {
        assertEquals(rows, rows.moved(-1) { it == "a" })
        assertEquals(rows, rows.moved(1) { it == "d" })
        assertEquals(rows, rows.moved(-1, lowest = 2) { it == "c" }) // trips: the first two stay put
        assertEquals(rows, rows.moved(1) { it == "gone" })
        assertEquals(emptyList<String>(), emptyList<String>().moved(1) { true })
    }
}
