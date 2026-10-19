package com.droiddeck.launcher.stores.xbox

import android.content.Context
import com.droiddeck.launcher.session.SessionState
import com.droiddeck.launcher.stores.*
import org.json.JSONObject
import java.io.File

internal object XboxRuntime {
    private val state = Any()
    private val startup = Any()
    private var child: Xodus.Child? = null
    private var owner: String? = null
    private var serviceSocket: File? = null
    private var generation = 0L
    private val launches = XboxLaunchLeases { android.os.SystemClock.uptimeMillis() }
    fun isActive(id: String): Boolean = launches.active(id)
    fun authorize(app: Context, id: String) {
        StoreAccounts.signedInAs(app, Store.XBOX)?.let { launches.authorize(id, it) }
    }
    fun released(token: String) { runCatching { launches.release(token)?.close() } }
    fun renew(app: Context, id: String, token: String): Boolean = launches.renew(token, id,
        StoreAccounts.signedInAs(app, Store.XBOX), SessionState.sessionId.takeIf { SessionState.running })

    fun prepare(app: Context, id: String): JSONObject {
        check(!XboxContent.busy(id)) { "Wait for Xbox content downloads to finish" }
        check(SessionState.running) { "No running DroidDeck session" }
        val session = SessionState.sessionId ?: error("No running DroidDeck session")
        val game = StoreInstallRoot.gameFolders(app).mapNotNull { folder ->
            StoreGameSidecar.read(folder)?.takeIf { it.store == Store.XBOX && it.id == id && it.isInstalled }?.let { folder to it }
        }.firstOrNull() ?: error("Xbox game is not installed")
        val account = StoreAccounts.signedInAs(app, Store.XBOX) ?: error("Sign in to Xbox before playing")
        val token: String
        val current: Long
        synchronized(state) {
            current = generation
            token = launches.begin(id, account, session)
        }
        Thread({
            while (isActive(id)) {
                Thread.sleep(10_000)
                launches.stale(StoreAccounts.signedInAs(app, Store.XBOX), SessionState.sessionId.takeIf { SessionState.running },
                    SessionState.suspended).forEach { runCatching { it.close() } }
            }
        }, "xbox-launch-expiry").start()
        try {
            val socket = service(app, account, current)
            check(synchronized(state) { generation == current }) { "Xbox launch was cancelled" }
            val market = game.second.extra["market"]?.takeIf { it.matches(Regex("[A-Z]{2}")) } ?: Xodus.market()
            val process = Xodus.start(app, listOf("run", game.first.path, "droiddeck:prepare", "--exe", game.second.exe,
                "--materialize-chain", "--market", market))
            if (!launches.attach(token, process)) { process.close(); error("Xbox launch was cancelled") }
            Xodus.diagnostics(process)
            val result = java.util.concurrent.FutureTask {
                val input = process.process.inputStream.buffered()
                var line: String
                do { line = XboxCredentials.readLine(input, 131_072) } while (!line.startsWith("DROIDDECK_LAUNCH_V1 "))
                JSONObject(line.removePrefix("DROIDDECK_LAUNCH_V1 "))
            }
            Thread(result, "xbox-launch-view").start()
            val view = result.get(90, java.util.concurrent.TimeUnit.SECONDS)
            check(renew(app, id, token) && synchronized(state) { generation == current }) { "Xbox launch was cancelled" }
            return JSONObject().put("ok", true).put("socket", socket.path).put("lease", token)
                .put("exe", view.getString("exe")).put("playfab", view.opt("playfab"))
        } catch (e: Exception) { released(token); throw e }
    }

    private fun service(app: Context, account: String, current: Long): File = synchronized(startup) {
        val existing = synchronized(state) {
            check(generation == current) { "Xbox launch was cancelled" }
            serviceSocket?.takeIf { child?.process?.isAlive == true && owner == account }
        }
        val socket = existing ?: File(app.filesDir, "xbox-runtime/service-${java.util.UUID.randomUUID()}/xodus.sock")
        if (existing == null) {
            val old = synchronized(state) { child.also { child = null; owner = null; serviceSocket = null } }
            old?.close()
            socket.delete()
            val service = Xodus.start(app, emptyList(), service = true, runtimeDirectory = socket.parentFile!!)
            val accepted = synchronized(state) {
                (generation == current).also { if (it) { child = service; owner = account; serviceSocket = socket } }
            }
            if (!accepted) { service.close(); error("Xbox launch was cancelled") }
            try {
                Xodus.diagnostics(service)
                Thread({ runCatching { service.process.inputStream.use { input ->
                    val buffer = ByteArray(8192)
                    while (input.read(buffer) >= 0) { Thread.yield() }
                } } }, "xbox-service-output").start()
                val deadline = android.os.SystemClock.elapsedRealtime() + 90_000
                while (!socket.exists() && service.process.isAlive && SessionState.running &&
                    synchronized(state) { generation == current } && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(100)
                check(socket.exists() && service.process.isAlive && SessionState.running) {
                    "Xbox account service did not become ready. Check your sign-in and network connection."
                }
            } catch (e: Exception) {
                synchronized(state) { if (child === service) { child = null; owner = null; serviceSocket = null } }
                service.close()
                throw e
            }
        }
        socket
    }

    fun stop() {
        val processes = synchronized(state) {
            generation++
            val all = launches.clear() + listOfNotNull(child)
            child = null; owner = null; serviceSocket = null
            all
        }
        Thread({ processes.forEach { runCatching { it.close() } } }, "xbox-runtime-stop").start()
    }
}
