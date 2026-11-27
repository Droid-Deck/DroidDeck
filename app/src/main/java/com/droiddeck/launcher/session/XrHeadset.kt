package com.droiddeck.launcher.session

import android.content.Context
import android.content.Intent
import com.droiddeck.launcher.OpenXrActivity

object XrHeadset {
    fun isSupported(context: Context): Boolean =
        OpenXrRuntime.isAvailable(context)

    fun intent(context: Context): Intent = Intent(context, OpenXrActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
