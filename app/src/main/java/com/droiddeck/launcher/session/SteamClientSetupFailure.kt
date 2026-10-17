package com.droiddeck.launcher.session

class SteamClientSetupFailure(val curlError: String?) {
    val download get() = curlError != null

    companion object {
        const val MARKER = "== steam: the Steam client could not be set up"
        private const val STEP = "checking the Steam client"
        private val CURL_ERROR = Regex("""curl: \(\d+\).*""")

        fun find(lines: Sequence<String>): SteamClientSetupFailure? {
            var curlError: String? = null
            var found: SteamClientSetupFailure? = null
            lines.forEach { line ->
                when {
                    line.contains(STEP) -> {
                        curlError = null
                        found = null
                    }
                    line.contains(MARKER) -> found = SteamClientSetupFailure(curlError)
                    else -> CURL_ERROR.find(line)?.let { curlError = it.value.trim() }
                }
            }
            return found
        }
    }
}
