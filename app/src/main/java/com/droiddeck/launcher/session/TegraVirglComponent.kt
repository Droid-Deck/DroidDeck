package com.droiddeck.launcher.session

import android.util.Log
import com.droiddeck.launcher.core.SessionPart
import java.io.File
import java.io.IOException

internal class TegraVirglComponent(private val log: File) : SessionPart() {
    private var process: Process? = null
    private val socket get() = File(app().filesDir, "tegra-vtest.sock")
    val socketPath get() = socket.absolutePath

    @Synchronized
    override fun start() {
        if (process?.isAlive == true) return
        val libraries = app().applicationInfo.nativeLibraryDir
        val server = File(libraries, "libvirgl_test_server.so")
        if (!server.isFile) return
        if (socket.exists() && !socket.delete()) {
            Log.w(TAG, "Cannot replace the old VirGL socket")
            return
        }
        try {
            val builder = ProcessBuilder(server.path, "--no-fork", "--multi-clients",
                "--use-egl-surfaceless", "--use-gles", "--socket-path", socketPath)
            builder.environment()["LD_LIBRARY_PATH"] = libraries
            process = builder.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(log)).start()
        } catch (error: IOException) {
            Log.w(TAG, "Cannot start the VirGL renderer", error)
        }
    }

    @Synchronized
    override fun stop() {
        process?.destroyForcibly()
        process = null
        socket.delete()
    }

    private companion object {
        const val TAG = "TegraVirgl"
    }
}
