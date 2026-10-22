package com.droiddeck.launcher.session

import android.util.Log
import com.droiddeck.launcher.core.SessionPart
import java.io.File

/** Steam's Remote Play V4L2 encoder in the guest talks to this MediaCodec encoder (venc.c, venc_server.cpp). */
class VideoEncodeBridge(val socket: File) : SessionPart() {
    override fun start() {
        socket.parentFile?.mkdirs()
        val ok = runCatching { System.loadLibrary("ddvenc"); nativeStart(socket.absolutePath) }
            .onFailure { Log.w(TAG, "hardware video encoding unavailable", it) }
            .getOrDefault(false)
        Log.i(TAG, if (ok) "Remote Play hardware encoding on at $socket" else "Remote Play hardware encoding off")
    }

    override fun stop() {
        runCatching { nativeStop() }
        socket.delete()
    }

    private external fun nativeStart(path: String): Boolean
    private external fun nativeStop()

    private companion object {
        const val TAG = "VideoEncodeBridge"
    }
}
