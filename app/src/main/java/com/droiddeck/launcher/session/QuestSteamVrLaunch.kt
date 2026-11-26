package com.droiddeck.launcher.session

import android.content.Context
import java.io.File

/**
 * On a Quest, SteamVR has to run inside the immersive activity. A launch from Steam's library happens in the
 * guest, so the guest's SteamVR helper leaves a request in the shared runtime directory for the session to act on.
 */
object QuestSteamVrLaunch {
    private const val DIR = ".wayland-rt/droiddeck-steamvr"
    private const val MARKER = "quest-immersive"
    private const val REQUEST = "xr-request"

    fun publish(context: Context) {
        val dir = File(context.filesDir, DIR)
        val marker = File(dir, MARKER)
        if (!QuestExperimental.isSupported()) {
            marker.delete()
            return
        }
        runCatching {
            dir.mkdirs()
            File(dir, REQUEST).delete()
            marker.writeText("")
        }
    }

    fun takeRequest(context: Context): Boolean =
        QuestExperimental.isSupported() && File(context.filesDir, "$DIR/$REQUEST").delete()
}
