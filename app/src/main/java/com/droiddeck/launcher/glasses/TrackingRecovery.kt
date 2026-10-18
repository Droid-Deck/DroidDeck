package com.droiddeck.launcher.glasses

internal enum class TrackingRecovery {
    DISCONNECTED, LOST, RECENTER, STABILIZING;

    companion object {
        fun forSession(active: Boolean, connected: Boolean, status: SpatialTrackingStatus): TrackingRecovery? = when {
            !active -> null
            !connected -> DISCONNECTED
            status.trackingValid -> null
            !status.headFresh -> LOST
            status.recenterPending -> STABILIZING
            status.needsRecenter -> RECENTER
            else -> LOST
        }
    }
}
