package com.droiddeck.launcher

internal object MaliAutoRoute {
    const val NONE = ""
    const val BRIDGE = "bridge"
    const val VENUS = "venus"

    fun initialOwnership(bridgeOn: Boolean, bridgeExplicit: Boolean, venusOn: Boolean, venusExplicit: Boolean): String =
        when {
            venusOn && !venusExplicit -> VENUS
            bridgeOn && !bridgeExplicit -> BRIDGE
            else -> NONE
        }

    fun fallbackOwnership(
        venusAvailable: Boolean,
        bridgeOn: Boolean,
        bridgeExplicit: Boolean,
        venusOn: Boolean,
        venusExplicit: Boolean,
    ): String = if (venusAvailable) {
        if (!venusOn || !venusExplicit) VENUS else NONE
    } else {
        if (!bridgeOn || !bridgeExplicit) BRIDGE else NONE
    }

    fun shouldEnd(ownsRoute: String, mali: Boolean, installedPair: Boolean): Boolean =
        mali && installedPair && ownsRoute != NONE
}
