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

    fun userChoice(keyStored: Boolean, owner: String, route: String): Boolean = keyStored && owner != route

    fun venusStandsIn(available: Boolean, hostVulkanOk: Boolean, venusOn: Boolean, venusExplicit: Boolean, gpuName: String): Boolean =
        available && hostVulkanOk && if (venusExplicit) venusOn else !venusStallsOn(gpuName)

    // Steam's web helper dies on its first window through Venus on these (#2879), so the bridge stands in.
    fun venusStallsOn(gpuName: String): Boolean = gpuName.contains("G2-Ultra", ignoreCase = true)

    fun shouldEnd(ownsRoute: String, mali: Boolean, installedPair: Boolean): Boolean =
        mali && installedPair && ownsRoute != NONE
}
