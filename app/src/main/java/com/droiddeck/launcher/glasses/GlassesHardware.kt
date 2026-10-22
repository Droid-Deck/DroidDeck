package com.droiddeck.launcher.glasses

/** Hardware properties; display refresh is selected from Android's advertised modes. */
interface GlassesModel {
    val name: String
    val controllable: Boolean
    val brightnessMax: Int
    val volumeMax: Int
    val diagonalFovDeg: Float
    val sbs90: Boolean
    val rates2d: List<Int> get() = listOf(60, 90, 120)
    val hasSunblock: Boolean get() = true
    val resolutions: List<GlassesResolution> get() = emptyList()
    fun mode(hz: Int, sbs: Boolean, resolution: GlassesResolution?): Int = mode(hz, sbs)
    fun mode(hz: Int, sbs: Boolean): Int
    fun modeForStep(step: Int): Int?
}

/** The command IDs here are the existing glasses controller's internal vocabulary.
 * Each transport translates them to its own wire protocol; unsupported controls return false.
 */
internal interface GlassesLink {
    val hasImu: Boolean
    val badFrames: Long get() = 0L
    val ready: Boolean get() = true
    fun send(msgId: Int, payload: ByteArray = ByteArray(0)): Boolean
    fun close()
}
