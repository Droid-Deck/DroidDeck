package com.droiddeck.launcher.session

import java.io.File

object OverlayShown {
    fun check(root: File): Boolean {
        val config = File(root, "tmp").listFiles { f -> f.name.startsWith("mangohud.") }
            ?.maxByOrNull { it.lastModified() } ?: return false
        return runCatching { !config.readText().contains("no_display") }.getOrDefault(false)
    }
}
