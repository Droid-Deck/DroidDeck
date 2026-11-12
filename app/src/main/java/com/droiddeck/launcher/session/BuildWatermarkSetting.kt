package com.droiddeck.launcher.session

import android.content.Context
import android.content.Intent
import android.util.AtomicFile
import java.io.File

internal object BuildWatermarkSetting {
    const val CHANGED = "com.droiddeck.launcher.BUILD_WATERMARK_CHANGED"

    private fun file(context: Context) = AtomicFile(File(context.filesDir, "build-watermark"))

    private val processLock = Any()

    private fun <T> locked(context: Context, action: () -> T): T = synchronized(processLock) {
        File(context.filesDir, "build-watermark.lock").outputStream().channel.use { channel ->
            channel.lock().use { action() }
        }
    }

    fun enabled(context: Context): Boolean = locked(context) {
        // SharedPreferences caches are process-local; read the shared file on every refresh.
        val value = try {
            file(context).readFully().decodeToString()
        } catch (_: java.io.FileNotFoundException) {
            return@locked context.getSharedPreferences("session", Context.MODE_PRIVATE).getBoolean("buildWatermark", true)
        }
        value != "off"
    }

    fun setEnabled(context: Context, on: Boolean) {
        locked(context) {
            val target = file(context)
            val stream = target.startWrite()
            try {
                stream.write((if (on) "on" else "off").toByteArray())
                target.finishWrite(stream)
            } catch (error: Throwable) {
                target.failWrite(stream)
                throw error
            }
        }
        context.sendBroadcast(Intent(CHANGED).setPackage(context.packageName))
    }
}
