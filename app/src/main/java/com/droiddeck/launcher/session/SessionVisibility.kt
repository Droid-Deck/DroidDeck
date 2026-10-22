package com.droiddeck.launcher.session

import android.view.Display

internal fun sessionHasVisibleSurface(panelVisible: Boolean, screenOn: Boolean, xrVisible: Boolean): Boolean =
    xrVisible || (panelVisible && screenOn)

internal fun sessionScreenOn(onDefaultDisplay: Boolean, interactive: Boolean, displayState: Int?): Boolean =
    if (onDefaultDisplay || displayState == null || displayState == Display.STATE_UNKNOWN) interactive
    else displayState == Display.STATE_ON || displayState == Display.STATE_VR || displayState == Display.STATE_ON_SUSPEND
