package com.droiddeck.launcher.xr

import android.content.Context
import com.droiddeck.launcher.session.QuestExperimental
import com.droiddeck.xr.XrPlatform as Platform

object XrPlatform {
    fun current(context: Context): Platform = Platform.detect(
        hasAndroidOpenXrFeature = context.packageManager.hasSystemFeature(Platform.ANDROID_OPENXR_FEATURE),
        isQuestDevice = QuestExperimental.isSupported(),
    )
}
