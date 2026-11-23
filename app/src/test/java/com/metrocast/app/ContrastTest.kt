package com.metrocast.app

import com.metrocast.app.data.MetroLine
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every coloured pill with text (train times, line chips, live-trip cards) is readable: at least 4.5:1. */
class ContrastTest {
    private fun lum(c: Long): Double {
        fun ch(v: Long) = (v / 255.0).let { if (it <= 0.03928) it / 12.92 else Math.pow((it + 0.055) / 1.055, 2.4) }
        return 0.2126 * ch((c shr 16) and 0xFF) + 0.7152 * ch((c shr 8) and 0xFF) + 0.0722 * ch(c and 0xFF)
    }
    private fun ratio(a: Long, b: Long): Double { val (x, y) = listOf(lum(a), lum(b)).sortedDescending(); return (x + 0.05) / (y + 0.05) }

    @Test fun lineCardsAreReadable() {
        for (l in MetroLine.entries) {
            val r = ratio(l.cardColor, l.onColor)
            assertTrue("${l.displayName}: ${"%.2f".format(r)}:1", r >= 4.5)
        }
    }

    @Test fun statusColoursAreReadable() {
        for ((name, c) in listOf("elevators OK" to 0xFF1B5E20L, "elevator out" to 0xFFB71C1CL, "home outage card" to 0xFF8E1B1BL, "run" to 0xFFD32F2FL, "take your time" to 0xFF2E7D32L)) {
            val r = ratio(c, 0xFFFFFFFFL)
            assertTrue("$name: ${"%.2f".format(r)}:1", r >= 4.5)
        }
    }
}
