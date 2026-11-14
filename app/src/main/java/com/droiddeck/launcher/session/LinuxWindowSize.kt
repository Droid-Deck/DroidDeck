package com.droiddeck.launcher.session

data class LinuxWindowSize(val width: Int, val height: Int, val generation: Long) {
    companion object {
        const val MAX_DIMENSION = 8192
        fun accept(width: Int, height: Int, generation: Long): LinuxWindowSize? =
            if (width in 1..MAX_DIMENSION && height in 1..MAX_DIMENSION && generation > 0)
                LinuxWindowSize(width, height, generation) else null
    }
}
