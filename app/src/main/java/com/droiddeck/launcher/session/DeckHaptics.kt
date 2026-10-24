package com.droiddeck.launcher.session

/**
 * Steam's trackpad haptics (the Deck's 0x8F / 0xEA reports) for the phone's built-in vibrator.
 *
 * A real Deck plays these on its two trackpad actuators, each with its own Haptics Intensity
 * slider in Steam; the phone stands in for both with its one motor. Every report carries the gain
 * in dB of its trackpad's slider (the UI's 0..12 dB arrives as -5..+7 dB). The latest gain is kept
 * per trackpad and every tick plays at the higher of the two, so a tick is never weaker than
 * either slider asks for.
 */
internal class DeckHaptics {
    private val gainDb = arrayOfNulls<Int>(2)

    /**
     * Records [db] for [side] ([LEFT], [RIGHT] or [BOTH]) and returns the vibrator amplitude
     * (1..255, or 0 for nothing) to play the tick at: the higher of the two trackpads' gains.
     */
    @Synchronized fun amplitude(side: Int, db: Int): Int {
        if (side != RIGHT) gainDb[LEFT] = db
        if (side != LEFT) gainDb[RIGHT] = db
        return amplitudeFor(gainDb.filterNotNull().maxOrNull() ?: db)
    }

    companion object {
        const val LEFT = 0
        const val RIGHT = 1
        const val BOTH = 2
        /** The slider's bottom and top as they arrive. */
        const val LOW_DB = -5
        const val HIGH_DB = 7
        /** A light but perceptible tick at the slider's bottom. */
        const val LOW_AMPLITUDE = 64
        /** A gain this low is silence. */
        const val OFF_DB = -60

        /** Even steps in amplitude per dB from [LOW_AMPLITUDE] at [LOW_DB] to full strength at [HIGH_DB]. */
        fun amplitudeFor(db: Int): Int = when {
            db <= OFF_DB -> 0
            db <= LOW_DB -> LOW_AMPLITUDE
            db >= HIGH_DB -> 255
            else -> LOW_AMPLITUDE + (255 - LOW_AMPLITUDE) * (db - LOW_DB) / (HIGH_DB - LOW_DB)
        }
    }
}
