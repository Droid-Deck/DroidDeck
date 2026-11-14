package com.droiddeck.launcher.session

enum class StatsStrip(val id: String) {
    LINE("line"), PANEL("panel"), TILES("tiles"), RINGS("rings");

    fun next(): StatsStrip = entries[(ordinal + 1) % entries.size]

    companion object {
        fun from(id: String?): StatsStrip = entries.firstOrNull { it.id == id } ?: LINE
    }
}

enum class StripMetric(val id: String, val defaultOn: Boolean = true) {
    ENGINE("engine"), GRAPH("graph"), LOWS("lows"), LOW001("low001"), GPU("gpu"), GPU_TEMP("gputemp", false),
    CPU("cpu"), CPU_TEMP("cputemp"), VRAM("vram"), RAM("ram"), CLOCK("clock");

    companion object {
        val defaults: Set<StripMetric> = entries.filter { it.defaultOn }.toSet()

        fun parse(saved: String?): Set<StripMetric> =
            if (saved == null) defaults
            else saved.split(',').mapNotNull { id -> entries.firstOrNull { it.id == id.trim() } }.toSet()

        fun encode(on: Set<StripMetric>): String = entries.filter { it in on }.joinToString(",") { it.id }
    }
}

enum class StatsHud(val id: String) {
    BIG_FPS("bigfps"), FULL("full"), TILES("tiles"), RINGS("rings"), GRAPHS("graphs"), CORES("cores"),
    HEAT("heat"), GAME("game"), PACING("pacing"), NIGHT("night"), MEGA("mega");

    fun next(): StatsHud = entries[(ordinal + 1) % entries.size]

    companion object {
        fun from(id: String?): StatsHud = entries.firstOrNull { it.id == id } ?: BIG_FPS
    }
}

enum class SpeedUnit(val id: String, val label: String, val perKmh: Float) {
    AUTO("auto", "", 1f), KMH("kmh", "km/h", 1f), MPH("mph", "mph", 0.621371f);

    fun resolve(country: String): SpeedUnit = if (this != AUTO) this else if (country.uppercase() in MPH_REGIONS) MPH else KMH

    fun next(): SpeedUnit = entries[(ordinal + 1) % entries.size]

    companion object {
        private val MPH_REGIONS = setOf("US", "PR", "GU", "VI", "AS", "MP", "GB", "IM", "JE", "GG", "LR", "BS", "BZ")

        fun from(id: String?): SpeedUnit = entries.firstOrNull { it.id == id } ?: AUTO
    }
}

enum class StatsDash(val id: String) {
    AUTO("auto"), SPEEDOMETER("speedometer"), CIRCUIT("circuit"), TRACE("trace"), DIALS("dials"), GLANCE("glance");

    fun resolve(source: String): StatsDash = if (this != AUTO) this else when (source) {
        "Forza" -> CIRCUIT
        "OutGauge" -> DIALS
        else -> SPEEDOMETER
    }

    companion object {
        private val DRAWN = listOf(SPEEDOMETER, CIRCUIT, TRACE, DIALS, GLANCE)

        fun from(id: String?): StatsDash = entries.firstOrNull { it.id == id } ?: AUTO

        fun tapped(shown: StatsDash): StatsDash = DRAWN[(DRAWN.indexOf(shown) + 1).mod(DRAWN.size)]
    }
}
