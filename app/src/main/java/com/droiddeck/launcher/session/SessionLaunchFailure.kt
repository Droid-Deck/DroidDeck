package com.droiddeck.launcher.session

enum class SessionLaunchFailure {
    STEAM_ILLEGAL_INSTRUCTION,
    VULKAN_DEVICE_MISSING;

    companion object {
        private val ANSI = Regex("\u001b\\[[0-?]*[ -/]*[@-~]")
        private val STEAM_EXIT = Regex("== steam exited rc=(\\d+)")

        fun find(lines: Sequence<String>): SessionLaunchFailure? {
            var steamStarted = false
            var illegalInstruction = false
            var missingDevice = false
            lines.forEach { raw ->
                val line = ANSI.replace(raw, "").trim()
                when {
                    line.startsWith("== droiddeck-session ") -> {
                        steamStarted = false
                        illegalInstruction = false
                        missingDevice = false
                    }
                    line.startsWith("== STEP ") && line.contains("starting the Steam client") -> {
                        steamStarted = true
                        illegalInstruction = false
                    }
                    STEAM_EXIT.matches(line) && steamStarted ->
                        illegalInstruction = STEAM_EXIT.matchEntire(line)?.groupValues?.get(1) == "132"
                    line.startsWith("[gamescope]") && line.contains("vulkan:") -> when {
                        line.contains("failed to find physical device") -> missingDevice = true
                        line.contains("selecting physical device") -> missingDevice = false
                    }
                }
            }
            return when {
                illegalInstruction -> STEAM_ILLEGAL_INSTRUCTION
                missingDevice -> VULKAN_DEVICE_MISSING
                else -> null
            }
        }
    }
}
