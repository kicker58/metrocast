package com.metrocast.app.data

/**
 * WMATA sometimes sends predictions without a destination code, only the platform sign's
 * abbreviation ("Fed Ctr SW", "New Crlton", "Shady Grv", "MtVern Sq"). This turns those back
 * into station codes so direction and "does it get me there" still work.
 */
object DestinationCodes {
    // Common sign abbreviations (normalized: lower case, letters/digits only).
    private val known = mapOf(
        "fedctrsw" to "D04", "federalcentersw" to "D04",
        "newcrlton" to "D13", "newcarrollton" to "D13",
        "branchav" to "F11", "branchave" to "F11",
        "greenbelt" to "E10",
        "huntington" to "C15",
        "mtvernsq" to "E01", "mtvernonsq" to "E01",
        "shadygrv" to "A15", "shadygrove" to "A15",
        "glenmont" to "B11",
        "vienna" to "K08", "viennafairfaxgmu" to "K08",
        "largo" to "G05", "downtownlargo" to "G05",
        "ashburn" to "N12",
        "franconia" to "J03", "frnconia" to "J03", "franconiaspringfield" to "J03",
        "silvrspg" to "B08", "silverspring" to "B08",
        "grsvnor" to "A11", "grosvenor" to "A11", "grosvenorstrathmore" to "A11",
        "ftotten" to "E06", "forttotten" to "E06",
        "nocarrollton" to "D13",
        "wiehle" to "N06", "wiehlereston" to "N06",
        "stadarmry" to "D08", "stadiumarmory" to "D08",
        "mtvernonsq7thstconventioncenter" to "E01",
    )

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    /** True if every character of [abbr] appears in [full] in order ("fedctrsw" in "federalcentersw"). */
    private fun isAbbreviationOf(abbr: String, full: String): Boolean {
        var i = 0
        for (c in full) if (i < abbr.length && c == abbr[i]) i++
        return i == abbr.length
    }

    fun resolve(name: String?, line: String?): String? {
        val n = norm(name ?: return null)
        if (n.isBlank() || n == "nopassenger" || n == "train" || n == "ssenger") return null
        known[n]?.let { return it }
        // Otherwise match against the station list, preferring stations on this train's line.
        val stations = StationDirectory.stations
        if (stations.isEmpty()) return null
        val matches = stations.filter { s -> norm(s.name).let { it == n || (n.firstOrNull() == it.firstOrNull() && isAbbreviationOf(n, it)) } }
        val onLine = matches.filter { line != null && line in it.lines }
        val best = (onLine.ifEmpty { matches }).minByOrNull { it.name.length } ?: return null
        // Prefer the platform code that belongs to this line at two-platform stations.
        return best.codes.firstOrNull { c -> line == null || (c.first() in lineLetters(line)) } ?: best.code
    }

    // Station code letters used by each line's platforms (e.g. Red: A/B, Green/Yellow: F/E…).
    private fun lineLetters(line: String): Set<Char> = when (line) {
        "RD" -> setOf('A', 'B')
        "OR" -> setOf('K', 'C', 'D')
        "SV" -> setOf('N', 'K', 'C', 'D', 'G')
        "BL" -> setOf('J', 'C', 'D', 'G')
        "YL" -> setOf('C', 'F', 'E')
        "GR" -> setOf('F', 'E')
        else -> emptySet()
    }
}
