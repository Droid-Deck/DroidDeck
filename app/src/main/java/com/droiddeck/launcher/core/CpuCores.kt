package com.droiddeck.launcher.core

import java.io.File

/**
 * The device's cores as taskset names them, for the two masks a Steam session can carry.
 *
 * A list is stored the way taskset's `-c` takes it - comma-separated core numbers - so what the
 * dialog produces is what the session script and the Proton wrappers consume unchanged.
 */
object CpuCores {
    /** Core numbers present on this device, in order. */
    val all: List<Int> by lazy { (0 until Runtime.getRuntime().availableProcessors()).toList() }

    /** The core's ceiling in GHz for the dialog's labels, or null where the kernel hides it. */
    fun maxGhz(core: Int): Double? =
        FileUtils.readString(File("/sys/devices/system/cpu/cpu$core/cpufreq/cpuinfo_max_freq"))
            ?.trim()?.toLongOrNull()?.let { it / 1_000_000.0 }

    fun parse(list: String): Set<Int> =
        list.split(',').mapNotNull { it.trim().toIntOrNull() }.filter { it in all }.toSet()

    fun format(cores: Set<Int>): String = cores.sorted().joinToString(",")

    /** The stored list, or every core when nothing (or nothing valid) is stored. */
    fun listOrAll(list: String): String {
        val cores = parse(list)
        return format(if (cores.isEmpty()) all.toSet() else cores)
    }

    /** An all-core game must undo the affinity inherited from a pinned Steam client. */
    fun gameLaunchList(list: String, clientPinned: Boolean): String =
        restrictionOrEmpty(list).ifEmpty { if (clientPinned) listOrAll("") else "" }

    /**
     * The list only when it is a real restriction, else "". Empty, absent, or naming every core
     * all mean "no preference": pinning a process to all cores is what the scheduler does
     * unaided, and passing it would only put a meaningless line in the session log. Bannerlator's
     * rule, kept for the game mask.
     */
    fun restrictionOrEmpty(list: String): String {
        val cores = parse(list)
        return if (cores.isEmpty() || cores.size >= all.size) "" else format(cores)
    }
}
