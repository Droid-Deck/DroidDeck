package com.droiddeck.launcher.gpu

import android.util.AtomicFile
import org.json.JSONObject
import java.io.File

internal object SteamVrRecoverySettings {
    /** After a graphics crash SteamVR can quarantine our HMD, leaving a server with no VR output. */
    fun unblockHeadset(file: File): Boolean {
        if (!file.isFile) return false
        val settings = JSONObject(file.readText())
        val driver = settings.optJSONObject("driver_droiddeck") ?: return false
        if (!driver.optBoolean("blocked_by_safe_mode")) return false
        // Preserve every other setting, including safe mode and blocks on other drivers.
        driver.put("blocked_by_safe_mode", false)
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try {
            output.write(settings.toString(3).toByteArray(Charsets.UTF_8))
            atomic.finishWrite(output)
        } catch (e: Exception) {
            atomic.failWrite(output)
            throw e
        }
        return true
    }
}
