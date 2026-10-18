package com.droiddeck.launcher.session

internal fun sessionHasVisibleSurface(panelVisible: Boolean, screenOn: Boolean, xrVisible: Boolean): Boolean =
    xrVisible || (panelVisible && screenOn)
