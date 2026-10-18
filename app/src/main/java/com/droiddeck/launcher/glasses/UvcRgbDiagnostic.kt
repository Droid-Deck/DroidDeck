package com.droiddeck.launcher.glasses

internal object UvcRgbDiagnostic {
    val available: Boolean by lazy {
        runCatching { System.loadLibrary("droiddeck_uvc"); true }
            .onFailure { android.util.Log.w("UvcRgbDiagnostic", "Direct UVC transport unavailable", it) }.getOrDefault(false)
    }
    external fun nativeSnapshot(fd: Int, width: Int, height: Int, fps: Int, durationMs: Int,
                                metadata: LongArray, logFd: Int): ByteArray?
}

internal data class UvcStreamHealth(val observationMs: Double, val observedFps: Double,
                                    val maximumGapMs: Double, val healthy: Boolean) {
    companion object {
        // Health requires the full requested window, 80% of requested FPS, and no gap
        // (including the final silent interval) above max(250 ms, three frame periods).
        fun assess(metadata: LongArray, requestedFps: Int, durationMs: Int): UvcStreamHealth {
            require(metadata.size == 14 && requestedFps > 0 && durationMs > 0)
            val first = metadata[7]
            val last = metadata[6]
            val end = metadata[12]
            val window = if (first > 0 && end > first) end-first else 0
            val frames = metadata[9]
            val fps = if (window > 0 && frames > 1) (frames-1)*1e9/window else 0.0
            val maximumGapMs = maxOf(metadata[8],metadata[13],(end-last).coerceAtLeast(0))/1e6
            val healthy = metadata[4] == 0L && first > 0 && last in first..end && frames >= 2 &&
                window >= durationMs*1_000_000L && fps >= requestedFps*0.8 &&
                maximumGapMs <= maxOf(250.0,3000.0/requestedFps)
            return UvcStreamHealth(window/1e6,fps,maximumGapMs,healthy)
        }
    }
}
