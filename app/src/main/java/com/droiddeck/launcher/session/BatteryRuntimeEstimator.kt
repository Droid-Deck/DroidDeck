package com.droiddeck.launcher.session

import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.roundToLong

class BatteryRuntimeEstimator {
    private val currentSamples = ArrayDeque<Double>()

    @Synchronized
    fun reset() {
        currentSamples.clear()
    }

    @Synchronized
    fun estimateHours(chargeNowUah: Double, currentSampleUa: Double): Double? {
        if (currentSampleUa.isFinite() && currentSampleUa != 0.0) {
            currentSamples.addLast(abs(currentSampleUa))
            while (currentSamples.size > MAX_SAMPLES) currentSamples.removeFirst()
        }
        if (!chargeNowUah.isFinite() || chargeNowUah <= 0.0 || currentSamples.isEmpty()) return null
        var sum = 0.0
        for (sample in currentSamples) sum += sample
        val averageCurrentUa = sum / currentSamples.size
        if (!averageCurrentUa.isFinite() || averageCurrentUa <= 0.0) return null
        val hours = chargeNowUah / averageCurrentUa
        return hours.takeIf { it.isFinite() && it > 0.0 }
    }

    companion object {
        private const val MAX_SAMPLES = 25
        private const val MILLIAMP_CEILING = 10_000L

        @JvmStatic
        fun toMicroamps(raw: Long): Long {
            if (raw == Long.MIN_VALUE) return 0L
            return if (abs(raw) < MILLIAMP_CEILING) raw * 1_000L else raw
        }

        @JvmStatic
        fun chargeMicroampHoursFromEnergy(energyUwh: Double, voltageUv: Double): Double? {
            if (!energyUwh.isFinite() || energyUwh <= 0.0 || !voltageUv.isFinite() || voltageUv <= 0.0) return null
            return (energyUwh * 1_000_000.0 / voltageUv).takeIf { it.isFinite() && it > 0.0 }
        }

        @JvmStatic
        fun currentMicroampsFromPower(powerUw: Double, voltageUv: Double): Double? {
            if (!powerUw.isFinite() || voltageUv <= 0.0 || !voltageUv.isFinite()) return null
            return (abs(powerUw) * 1_000_000.0 / voltageUv).takeIf { it.isFinite() && it > 0.0 }
        }

        @JvmStatic
        fun secondsFromHours(hours: Double?): Long {
            if (hours == null || !hours.isFinite() || hours <= 0.0) return 0L
            val seconds = hours * 3_600.0
            return if (seconds.isFinite() && seconds < Long.MAX_VALUE.toDouble()) seconds.toLong() else 0L
        }

        @JvmStatic
        fun formatDuration(hours: Double?): String? {
            if (hours == null || !hours.isFinite() || hours <= 0.0) return null
            val totalMinutes = (hours * 60.0).roundToLong().coerceAtLeast(1L)
            val wholeHours = totalMinutes / 60L
            val minutes = totalMinutes % 60L
            val hourUnit = if (wholeHours == 1L) "hr" else "hrs"
            val minuteUnit = if (minutes == 1L) "min" else "mins"
            return when {
                wholeHours > 0L && minutes > 0L -> "$wholeHours $hourUnit $minutes $minuteUnit"
                wholeHours > 0L -> "$wholeHours $hourUnit"
                else -> "$minutes $minuteUnit"
            }
        }
    }
}
