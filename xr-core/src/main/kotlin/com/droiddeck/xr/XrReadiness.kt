package com.droiddeck.xr

enum class XrEntryReadiness(val canProbe: Boolean = false) {
    UNSUPPORTED_DEVICE,
    GUEST_NOT_READY,
    READY_TO_PROBE(true);

    companion object {
        fun evaluate(platform: XrPlatform, guestReady: Boolean): XrEntryReadiness = when {
            platform == XrPlatform.NONE -> UNSUPPORTED_DEVICE
            !guestReady -> GUEST_NOT_READY
            else -> READY_TO_PROBE
        }
    }
}
