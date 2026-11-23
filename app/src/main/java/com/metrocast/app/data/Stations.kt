package com.metrocast.app.data

/**
 * Built-in Silver Line station order (Ashburn → Downtown Largo), so the default trips
 * work even before the app has downloaded line data from WMATA.
 */
object Stations {
    val SILVER: List<Station> = listOf(
        Station("N12", "Ashburn"),
        Station("N11", "Loudoun Gateway"),
        Station("N10", "Washington Dulles International Airport"),
        Station("N09", "Innovation Center"),
        Station("N08", "Herndon"),
        Station("N07", "Reston Town Center"),
        Station("N06", "Wiehle-Reston East"),
        Station("N04", "Spring Hill"),
        Station("N03", "Greensboro"),
        Station("N02", "Tysons"),
        Station("N01", "McLean"),
        Station("K05", "East Falls Church"),
        Station("K04", "Ballston-MU"),
        Station("K03", "Virginia Square-GMU"),
        Station("K02", "Clarendon"),
        Station("K01", "Court House"),
        Station("C05", "Rosslyn"),
        Station("C04", "Foggy Bottom-GWU"),
        Station("C03", "Farragut West"),
        Station("C02", "McPherson Square"),
        Station("C01", "Metro Center"),
        Station("D01", "Federal Triangle"),
        Station("D02", "Smithsonian"),
        Station("D03", "L'Enfant Plaza"),
        Station("D04", "Federal Center SW"),
        Station("D05", "Capitol South"),
        Station("D06", "Eastern Market"),
        Station("D07", "Potomac Ave"),
        Station("D08", "Stadium-Armory"),
        Station("G01", "Benning Road"),
        Station("G02", "Capitol Heights"),
        Station("G03", "Addison Road"),
        Station("G04", "Morgan Boulevard"),
        Station("G05", "Downtown Largo"),
    )

    /** Tests can swap in their own starting trips; the app never sets this. */
    @JvmField var testDefaults: List<Trip>? = null

    /** The starting trips before setup picks yours (set per build in build.gradle.kts). */
    val DEFAULT_TRIPS: List<Trip> get() = testDefaults ?: SHIPPED_DEFAULTS

    /** line, from code, from name, to code, to name, place, lat, lon. */
    internal val START: List<String> = com.metrocast.app.BuildConfig.START_DEFAULTS.split("|")

    private val SHIPPED_DEFAULTS: List<Trip> = listOf(
        Trip(id = "default-going", label = Trip.GOING, lineCode = START[0],
            stationCode = START[1], stationName = START[2], towardCode = START[3], towardName = START[4]),
        Trip(id = "default-back", label = Trip.BACK, lineCode = START[0],
            stationCode = START[3], stationName = START[4], towardCode = START[1], towardName = START[2]),
    )
}
