package com.droiddeck.launcher.session

import android.util.Log
import com.droiddeck.launcher.core.SessionPart
import java.io.File
import java.io.IOException

internal class TegraVirglComponent(private val runtimeDir: File, private val log: File) : SessionPart() {
    private var process: Process? = null
    private val socket get() = File(runtimeDir, SOCKET_NAME)

    @Synchronized
    override fun start() {
        if (process?.isAlive == true) return
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
        process?.destroyForcibly()
        process = null
        socket.delete()
    }

    companion object {
        const val SOCKET_NAME = "tegra-vtest.sock"
        private const val TAG = "TegraVirgl"
    }
}
