package com.metrocast.app.data

/**
 * MetroCast's picks: rides with a view and stations worth seeing. Kept to things that are well
 * documented (escalator lengths and depths as published by WMATA and widely reported).
 */
object Scenic {
    data class Ride(
        val title: String,
        val lines: List<String>,
        val from: String,
        val to: String,
        val what: String,
        val tip: String,
    )

    data class Spot(val code: String, val title: String, val what: String, val tip: String? = null)

    val rides = listOf(
        Ride(
            "Over the Potomac", listOf("YL"), "C07", "F03",
            "The Yellow Line crosses the river on its own bridge, with the Jefferson Memorial and the Washington Monument across the water.",
            "Sit on the side facing the city. Ride between Pentagon and L'Enfant Plaza.",
        ),
        Ride(
            "Past Reagan National Airport", listOf("BL", "YL"), "C09", "C12",
            "Up on an elevated track beside the runways: planes landing, the Potomac and the DC skyline beyond.",
            "Sit on the river side. Ride between Crystal City and Braddock Road.",
        ),
        Ride(
            "High over Tysons", listOf("SV"), "N01", "N04",
            "The Silver Line runs on an elevated track through the middle of Tysons — towers on both sides.",
            "Best after dark, when the skyline is lit up.",
        ),
    )

    val spots = listOf(
        Spot("B10", "Wheaton — a giant escalator", "About 230 ft long: one of the longest escalators in the Western Hemisphere. The ride takes nearly three minutes.", "Stand right, walk left."),
        Spot("A09", "Bethesda — a long way down", "An escalator of about 213 ft from the street to the mezzanine."),
        Spot("C05", "Rosslyn — deep escalators", "About 194 ft of escalator between the street and the trains."),
        Spot("A03", "Dupont Circle — the north entrance", "A 188 ft escalator rises out of the ground under the north entrance canopy."),
        Spot("B09", "Forest Glen — the deepest station", "About 196 ft underground — so deep there are no escalators, only elevators."),
        Spot("B03", "Union Station", "The station sits beneath the 1907 Union Station.", "Go upstairs and look at the Main Hall's gilded ceiling."),
        Spot("D02", "Smithsonian — out onto the Mall", "The Mall exit comes up right on the National Mall, with the Washington Monument in view."),
        Spot("C10", "Reagan National Airport", "Elevated platforms beside the airport, looking out over the runways and the river."),
        Spot("A01", "Metro Center — the waffle vaults", "Harry Weese's coffered concrete vaults, at their most dramatic where two lines cross.", "Also at Gallery Place and L'Enfant Plaza."),
        Spot("C06", "Arlington Cemetery", "Walk out to the Memorial Bridge for the view across the river to the Lincoln Memorial."),
    )
}
