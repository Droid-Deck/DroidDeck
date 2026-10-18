package com.droiddeck.launcher.glasses

internal class HandProcessingRate {
    var permille = 500
        private set
    private var processingMs = Double.NaN
    private var headroomSamples = 0

    fun observe(processingNs: Long, observationAgeNs: Long, trackingHealthy: Boolean): Int {
        if (!trackingHealthy || processingNs <= 0 || observationAgeNs < 0) return reset()
        val measuredMs = processingNs / 1_000_000.0
        val ageMs = observationAgeNs / 1_000_000.0
        processingMs = if (processingMs.isNaN()) measuredMs else processingMs + 0.2 * (measuredMs - processingMs)
        if (measuredMs >= 80 || ageMs >= 140) {
            permille = 500
            headroomSamples = 0
            return permille
        }
        val reduced = when {
            permille > 500 && processingMs > 1000.0 / 15 -> 500
            permille == 800 && processingMs > 50 -> 600
            else -> permille
        }
        if (reduced != permille) {
            permille = reduced
            headroomSamples = 0
            return permille
        }
        val enoughHeadroom = when (permille) {
            500 -> processingMs <= 55 && ageMs <= 110
            600 -> processingMs <= 40 && ageMs <= 90
            else -> false
        }
        headroomSamples = if (enoughHeadroom) headroomSamples + 1 else 0
        if (headroomSamples >= 8) {
            permille = if (permille == 500) 600 else 800
            headroomSamples = 0
        }
        return permille
    }

    fun reset(): Int {
        permille = 500
        processingMs = Double.NaN
        headroomSamples = 0
        return permille
    }
}
