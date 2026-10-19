package com.droiddeck.launcher.stores.xbox

import android.content.Context
import com.droiddeck.launcher.session.SessionState
import com.droiddeck.launcher.stores.*
import org.json.JSONObject
import java.io.File

internal object XboxRuntime {
    private var child: Xodus.Child? = null
    private var owner: String? = null
    private val activeGames = HashMap<String, Int>()
    @Synchronized fun isActive(id: String): Boolean = activeGames.getOrDefault(id, 0) > 0
    @Synchronized fun released(id: String) {
        val count = activeGames.getOrDefault(id, 0)
        if (count <= 1) activeGames.remove(id) else activeGames[id] = count - 1
    }

    @Synchronized fun prepare(app: Context, id: String): JSONObject {
        check(!XboxContent.busy(id)) { "Wait for Xbox content downloads to finish" }
        check(SessionState.running) { "No running DroidDeck session" }
        check(StoreGameSidecar.find(app, Store.XBOX, id)?.isInstalled == true) { "Xbox game is not installed" }
        val account = StoreAccounts.signedInAs(app, Store.XBOX) ?: error("Sign in to Xbox before playing")
        val socket = File(app.filesDir, "xbox-runtime/xodus.sock")
        if (child?.process?.isAlive != true || owner != account) {
            stop()
            socket.delete()
            val service = Xodus.start(app, emptyList(), service = true)
            child = service; owner = account
            Xodus.diagnostics(service)
            Thread({ runCatching { service.process.inputStream.use { val buffer = ByteArray(8192); while (it.read(buffer) >= 0) {} } } }, "xbox-service-output").start()
            val deadline = android.os.SystemClock.elapsedRealtime() + 90_000
            while (!socket.exists() && service.process.isAlive && SessionState.running && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(100)
            if (!socket.exists() || !service.process.isAlive || !SessionState.running) {
                stop(); error("Xbox account service did not become ready. Check your sign-in and network connection.")
            }
        }
        activeGames[id] = activeGames.getOrDefault(id, 0) + 1
        return JSONObject().put("ok", true).put("socket", socket.path).put("credentials", child!!.credentialSocket)
    }

    @Synchronized fun stop() {
        val process = child; child = null; owner = null; activeGames.clear()
        runCatching { process?.close() }
    }
}
