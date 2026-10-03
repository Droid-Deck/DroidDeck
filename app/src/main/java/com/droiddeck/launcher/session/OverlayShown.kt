package com.droiddeck.launcher.session

import java.io.File

/**
 * Whether Deck mode's performance overlay is on screen. The client sets its level by rewriting
 * mangoapp's config (droiddeck-session makes it, `/tmp/mangohud.*`), and `no_display` is the
 * level that hides it. The feeds whose reads cost something - the fan's tachometer busy-waits in
 * the kernel, GPU memory walks every process's mappings - only run while it shows.
 */
object OverlayShown {
    fun check(root: File): Boolean {
        val config = File(root, "tmp").listFiles { f -> f.name.startsWith("mangohud.") }
            ?.maxByOrNull { it.lastModified() } ?: return false
        return runCatching { !config.readText().contains("no_display") }.getOrDefault(false)
    }
}
