package com.droiddeck.launcher.core

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import java.io.File

object ZramSupport {
    private const val GIB = 1L shl 30

    fun supported(): Boolean = kernelAtLeast(System.getProperty("os.version").orEmpty(), 5, 4) && swapTotalKb() > 0

    fun recommended(context: Context): Boolean {
        if (!supported()) return false
        val memory = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(memory)
        val advertised = if (Build.VERSION.SDK_INT >= 34) memory.advertisedMem else 0L
        return belowTwelveGb(advertised, memory.totalMem)
    }

    fun belowTwelveGb(advertisedBytes: Long, totalBytes: Long): Boolean =
        if (advertisedBytes > 0) advertisedBytes < 11 * GIB else totalBytes < 10 * GIB

    fun kernelAtLeast(release: String, major: Int, minor: Int): Boolean {
        val parts = Regex("^(\\d+)\\.(\\d+)").find(release)?.groupValues ?: return false
        val ma = parts[1].toInt()
        val mi = parts[2].toInt()
        return ma > major || (ma == major && mi >= minor)
    }

    fun swapTotalKb(meminfo: List<String> = runCatching { File("/proc/meminfo").readLines() }.getOrDefault(emptyList())): Long =
        meminfo.firstOrNull { it.startsWith("SwapTotal:") }
            ?.substringAfter(':')?.trim()?.substringBefore(' ')?.toLongOrNull() ?: 0L
}
