package com.droiddeck.launcher.core

import java.io.File

/** Whether the client's memory can be paged out to compressed swap: MADV_PAGEOUT (Linux 5.4) and a zram or other swap device. */
object ZramSupport {
    fun supported(): Boolean = kernelAtLeast(System.getProperty("os.version").orEmpty(), 5, 4) && swapTotalKb() > 0

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
