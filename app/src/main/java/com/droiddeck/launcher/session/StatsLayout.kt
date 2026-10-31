package com.droiddeck.launcher.session

/** The In-game stats page's top strip, picked on the page (tap or the Layout row) and remembered. */
enum class StatsStrip(val id: String) {
    PANEL("panel"), TILES("tiles"), RINGS("rings");

    fun next(): StatsStrip = entries[(ordinal + 1) % entries.size]

    companion object {
        fun from(id: String?): StatsStrip = entries.firstOrNull { it.id == id } ?: PANEL
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
