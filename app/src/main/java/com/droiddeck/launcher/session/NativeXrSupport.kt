package com.droiddeck.launcher.session

import android.content.Context

object NativeXrSupport {
    fun isSupported(context: Context): Boolean = QuestExperimental.isSupported() ||
        context.packageManager.hasSystemFeature("android.software.xr.api.openxr")
}
