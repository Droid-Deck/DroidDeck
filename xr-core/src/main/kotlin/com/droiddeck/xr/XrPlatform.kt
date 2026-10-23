package com.droiddeck.xr

enum class XrPlatform {
    NONE, QUEST, ANDROID_XR;

    companion object {
        const val ANDROID_OPENXR_FEATURE = "android.software.xr.api.openxr"

        fun detect(hasAndroidOpenXrFeature: Boolean, isQuestDevice: Boolean): XrPlatform = when {
            isQuestDevice -> QUEST
            hasAndroidOpenXrFeature -> ANDROID_XR
            else -> NONE
        }
    }
}
