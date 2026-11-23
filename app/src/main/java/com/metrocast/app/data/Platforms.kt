package com.metrocast.app.data

/**
 * Which side the train doors open on. From WMATA's "Center Platform Stations" list
 * (wmata.com/ride/elevators-escalators/center-platforms.html): a station on a line's list has one
 * platform between the tracks, so — with Metro's trains running on the right-hand track — the doors
 * open on your LEFT as you face the front of the train. Everywhere else the platforms are at the
 * sides, so they open on your RIGHT. Track work that sends a train down the other track
 * (single tracking) can swap this.
 */
object Platforms {
    const val SOURCE = "wmata.com Center Platform Stations list"

    enum class Side(val word: String) { LEFT("left"), RIGHT("right") }

    /** Station codes with a center platform, per line (as WMATA lists them). */
    private val center: Map<String, Set<String>> = mapOf(
        "RD" to setOf("A02", "A04", "A05", "A06", "A07", "A08", "A09", "A10", "A11", "A12", "A13", "A14", "A15", "B03", "B04", "B05", "B06", "B07", "B08", "B09", "B10", "B11", "B35"),
        "OR" to setOf("C01", "C04", "D01", "D03", "D04", "D05", "D06", "D07", "D08", "D09", "D10", "D12", "D13", "K01", "K05", "K07", "K08"),
        "SV" to setOf("C01", "C04", "D01", "D03", "D04", "D05", "D06", "D07", "D08", "K01", "K05", "N01", "N02", "N03", "N04", "N06", "N07", "N08", "N09", "N10", "N11", "N12"),
        "BL" to setOf("C01", "C04", "C12", "C13", "D01", "D03", "D04", "D05", "D06", "D07", "D08", "G01", "G02", "G03", "G04", "G05", "J02", "J03"),
        "YL" to setOf("C12", "C13", "C15", "E01", "F01", "F02"),
        "GR" to setOf("E01", "E02", "E03", "E04", "E05", "E06", "E09", "E10", "F01", "F02", "F04", "F05", "F06", "F07", "F08", "F09", "F10", "F11"),
    )

    /** True if [line] stops at a center platform at this station (either of its platform codes). */
    fun isCenter(line: String, code: String): Boolean {
        val set = center[line] ?: return false
        val codes = StationDirectory.find(code)?.codes ?: listOf(code)
        return code in set || codes.any { it in set }
    }

    fun doorSide(line: String, code: String): Side = if (isCenter(line, code)) Side.LEFT else Side.RIGHT

    /** "🚪 Doors open on your left" — facing the front of the train. */
    fun doorText(line: String, code: String): String = "🚪 Doors open on your ${doorSide(line, code).word}"

    /** For the line's whole list (tests). */
    fun centerCodes(line: String): Set<String> = center[line].orEmpty()
}
