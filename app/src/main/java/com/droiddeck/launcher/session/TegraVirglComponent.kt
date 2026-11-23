package com.droiddeck.launcher.session

import android.util.Log
import com.droiddeck.launcher.core.SessionPart
import java.io.File
import java.io.IOException

internal class TegraVirglComponent(private val socket: File, private val log: File) : SessionPart() {
    private var process: Process? = null
    private var stopped = false

    @Synchronized
    override fun start() {
        if (stopped || process?.isAlive == true) return
        val libraries = app().applicationInfo.nativeLibraryDir
        val server = File(libraries, "libdroiddeck_tegra_vtest.so")
        if (!server.isFile) return
        if (socket.exists() && !socket.delete()) {
            Log.w(TAG, "Cannot replace the old VirGL socket")
            return
        }
        try {
            val builder = ProcessBuilder(server.path, "--no-fork", "--multi-clients", "--socket-path", socket.path)
            builder.environment()["LD_LIBRARY_PATH"] = libraries
            process = builder.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(log)).start()
        } catch (error: IOException) {
            Log.w(TAG, "Cannot start the VirGL renderer", error)
        }
    }

    @Synchronized
    override fun stop() {
        stopped = true
        process?.destroyForcibly()
        process = null
        socket.delete()
    }

    private companion object {
        const val TAG = "TegraVirgl"
    }
}
