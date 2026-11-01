package com.droiddeck.launcher.session

/** The In-game stats page's top strip, picked on the page (tap or the Layout row) and remembered. */
enum class StatsStrip(val id: String) {
    PANEL("panel"), TILES("tiles"), RINGS("rings");

    fun next(): StatsStrip = entries[(ordinal + 1) % entries.size]

    companion object {
        fun from(id: String?): StatsStrip = entries.firstOrNull { it.id == id } ?: PANEL
    }
}

/** The bottom-screen HUD shown while no game sends dashboard data; a tap moves to the next one. */
enum class StatsHud(val id: String) {
    BIG_FPS("bigfps"), FULL("full"), TILES("tiles"), RINGS("rings"), GRAPHS("graphs"), CORES("cores"),
    HEAT("heat"), GAME("game"), PACING("pacing"), NIGHT("night"), MEGA("mega");

    fun next(): StatsHud = entries[(ordinal + 1) % entries.size]

    companion object {
        fun from(id: String?): StatsHud = entries.firstOrNull { it.id == id } ?: BIG_FPS
    }
}

/** Speeds on the racing dashes; telemetry arrives in km/h. */
enum class SpeedUnit(val id: String, val label: String, val perKmh: Float) {
    AUTO("auto", "", 1f), KMH("kmh", "km/h", 1f), MPH("mph", "mph", 0.621371f);

    /** Auto is mph where road speed limits are signed in mph, by the device's region, else km/h. */
    fun resolve(country: String): SpeedUnit = if (this != AUTO) this else if (country.uppercase() in MPH_REGIONS) MPH else KMH

    fun next(): SpeedUnit = entries[(ordinal + 1) % entries.size]

    companion object {
        /** The United States and its territories, the United Kingdom and its Crown Dependencies, Liberia, the Bahamas, Belize. */
        private val MPH_REGIONS = setOf("US", "PR", "GU", "VI", "AS", "MP", "GB", "IM", "JE", "GG", "LR", "BS", "BZ")

        fun from(id: String?): SpeedUnit = entries.firstOrNull { it.id == id } ?: AUTO
    }
}

/** The racing dash under the strip; Auto picks one by the game's data format. */
enum class StatsDash(val id: String) {
    AUTO("auto"), SPEEDOMETER("speedometer"), CIRCUIT("circuit"), TRACE("trace"), DIALS("dials"), GLANCE("glance");

    /** The dash to draw for data from [source] (Telemetry.source). */
    fun resolve(source: String): StatsDash = if (this != AUTO) this else when (source) {
        "Forza" -> CIRCUIT
        "OutGauge" -> DIALS
        else -> SPEEDOMETER
    }

    companion object {
        private val DRAWN = listOf(SPEEDOMETER, CIRCUIT, TRACE, DIALS, GLANCE)

        fun from(id: String?): StatsDash = entries.firstOrNull { it.id == id } ?: AUTO

        /** A tap on the dash moves on from the one on screen, so Auto never looks like a tap did nothing. */
        fun tapped(shown: StatsDash): StatsDash = DRAWN[(DRAWN.indexOf(shown) + 1).mod(DRAWN.size)]
    }
}
