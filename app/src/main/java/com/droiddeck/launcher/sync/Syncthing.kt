package com.droiddeck.launcher.sync

import android.app.Activity
import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.droiddeck.launcher.core.HostProcess
import com.droiddeck.launcher.runtime.LinuxRuntime
import com.droiddeck.launcher.session.OrphanReaper
import com.droiddeck.launcher.session.SessionState
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

object Syncthing {
    enum class Phase { OFF, STARTING, RUNNING, STOPPING, FAILED }
    enum class AutoPause { WIFI, GAME }

    const val FAILURE_MISSING = "binary-missing"
    private const val TAG = "Syncthing"
    private const val GRACE_MS = 60_000L
    private const val LOG_LIMIT = 4L shl 20
    private const val FOLDER_ID_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789"

    var enabled by mutableStateOf(false); private set
    var phase by mutableStateOf(Phase.OFF); private set
    var snapshot by mutableStateOf<SyncSnapshot?>(null); private set
    var failure by mutableStateOf<String?>(null); private set
    var ports by mutableStateOf<SyncthingPorts?>(null); private set
    var apiKeyTail by mutableStateOf<String?>(null); private set
    var userPaused by mutableStateOf(false); private set
    var autoPause by mutableStateOf<AutoPause?>(null); private set
    /** Bumped to ask the Setup page to show the Sync tab. */
    var openRequests by mutableStateOf(0)
        private set
    var openPending = false

    fun requestOpen() {
        openPending = true
        openRequests++
    }

    val needsAttention get() = phase == Phase.FAILED || snapshot?.needsAttention == true

    /** Called on the main thread whenever the state above changes; the background service follows it. */
    @Volatile var onChange: (() -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "syncthing") }
    private lateinit var app: Context
    @Volatile private var process: Process? = null
    @Volatile private var api: SyncthingApi? = null
    @Volatile private var generation = 0
    @Volatile private var onWifi = true
    private var visible = 0
    private var foreground = false
    private val watchers = AtomicInteger()
    private val leaveForeground = Runnable { foreground = false; evaluate() }

    fun home(context: Context) = File(context.filesDir, "syncthing")
    fun folderRoot(context: Context) = File(context.getExternalFilesDir(null) ?: context.filesDir, "Sync")
    fun logFile(context: Context) = File(home(context), "syncthing.log")
    private fun config(context: Context) = File(home(context), "config.xml")
    private fun binary(context: Context) = File(context.applicationInfo.nativeLibraryDir, "libsyncthingnative.so")

    /** DroidDeck's own storage: its private data directory and its folder on each shared volume. */
    fun storageRoots(context: Context): List<File> =
        listOf(context.dataDir) + context.getExternalFilesDirs(null).filterNotNull().mapNotNull { it.parentFile }

    fun guard(context: Context) = SyncthingGuard(folderRoot(context), storageRoots(context), listOf(home(context)))

    fun init(application: Application) {
        app = application.applicationContext
        userPaused = SyncPrefs.userPaused(app)
        enabled = SyncPrefs.enabled(app)
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                visible++
                main.removeCallbacks(leaveForeground)
                if (!foreground) { foreground = true; evaluate() }
            }
            override fun onActivityStopped(activity: Activity) {
                visible = (visible - 1).coerceAtLeast(0)
                if (visible == 0) main.postDelayed(leaveForeground, GRACE_MS)
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
        val connectivity = app.getSystemService(ConnectivityManager::class.java)
        onWifi = connectivity?.activeNetwork?.let { connectivity.getNetworkCapabilities(it) }?.let(::isWifi) ?: false
        runCatching {
            connectivity?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { networkChanged(isWifi(caps)) }
                override fun onLost(network: Network) { networkChanged(false) }
            })
        }
        evaluate()
    }

    private fun isWifi(caps: NetworkCapabilities) =
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)

    private fun networkChanged(wifi: Boolean) {
        if (wifi == onWifi) return
        onWifi = wifi
        worker.execute { api?.let { applyPause(it) } }
    }

    /** Starts or stops Syncthing to match the settings and whether DroidDeck is on screen. */
    fun evaluate() {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { evaluate() }; return }
        val enabled = SyncPrefs.enabled(app)
        val background = SyncPrefs.runMode(app) == SyncPrefs.RUN_BACKGROUND
        if (enabled && background) SyncthingService.start(app) else SyncthingService.stop(app)
        if (enabled && (background || foreground)) start() else stop()
    }

    fun setEnabled(context: Context, on: Boolean, forget: Boolean = false) {
        if (on && !LinuxRuntime.isInstalled(context)) return
        SyncPrefs.setEnabled(context, on)
        enabled = on
        if (on && phase == Phase.FAILED) phase = Phase.OFF
        evaluate()
        if (!on && forget) worker.execute {
            home(context).deleteRecursively()
            SyncPrefs.clearIdentity(context)
            SyncPrefs.setPauseApplied(context, false)
        }
    }

    fun setPaused(context: Context, paused: Boolean) {
        SyncPrefs.setUserPaused(context, paused)
        userPaused = paused
        changed()
        worker.execute { api?.let { applyPause(it) } }
    }

    fun settingsChanged() {
        evaluate()
        worker.execute { api?.let { applyPause(it) } }
    }

    fun restart() {
        stop()
        evaluate()
    }

    fun reset(context: Context) {
        stop()
        worker.execute {
            home(context).deleteRecursively()
            SyncPrefs.clearIdentity(context)
            SyncPrefs.setPauseApplied(context, false)
            main.post { evaluate() }
        }
    }

    fun regenerateApiKey() {
        worker.execute {
            val current = api ?: return@execute
            val key = SyncPrefs.randomToken()
            runCatching { current.setApiKey(key) }.onSuccess {
                api = SyncthingApi(current.port, key)
                main.post { apiKeyTail = key.takeLast(4); changed() }
            }.onFailure { Log.w(TAG, "API key change failed", it) }
        }
    }

    /** Adds a folder at [path] shared with [devices]; pass the offered folder's [id] to accept a share. */
    fun addFolder(context: Context, path: File, label: String, devices: List<String>, id: String = newFolderId(), onDone: (Boolean) -> Unit) {
        worker.execute {
            val ok = runCatching {
                val client = api ?: error("Syncthing is not running")
                check(guard(context).allows(path.path)) { "${path.path} is outside DroidDeck's storage" }
                path.mkdirs()
                client.addFolder(client.defaultFolder().apply {
                    put("id", id)
                    put("label", label)
                    put("path", path.path)
                    put("devices", JSONArray(devices.map { JSONObject().put("deviceID", it) }))
                })
            }.onFailure { Log.w(TAG, "add folder failed", it) }.isSuccess
            main.post { onDone(ok) }
        }
    }

    private fun newFolderId(): String {
        val random = SecureRandom()
        fun part() = (1..5).map { FOLDER_ID_CHARS[random.nextInt(FOLDER_ID_CHARS.length)] }.joinToString("")
        return "${part()}-${part()}"
    }

    fun removeFolder(id: String) {
        worker.execute { api?.let { a -> runCatching { a.removeFolder(id) }.onFailure { Log.w(TAG, "remove $id failed", it) } } }
    }

    /** Address and Basic credentials the in-app web UI signs in with; Syncthing turns them into a session cookie. */
    fun webUi(context: Context): Pair<String, String>? {
        val port = ports?.gui ?: return null
        if (phase != Phase.RUNNING) return null
        val token = Base64.encodeToString("${SyncthingConfig.GUI_USER}:${SyncPrefs.guiPassword(context)}".toByteArray(), Base64.NO_WRAP)
        return "http://127.0.0.1:$port/" to "Basic $token"
    }

    /** UI screens showing live status call this so the status refreshes faster while they're visible. */
    fun watch(): () -> Unit {
        watchers.incrementAndGet()
        return { watchers.decrementAndGet() }
    }

    private fun start() {
        if (phase == Phase.STARTING || phase == Phase.RUNNING) return
        if (phase == Phase.FAILED && failure == FAILURE_MISSING) return
        val gen = ++generation
        phase = Phase.STARTING
        failure = null
        changed()
        worker.execute { launch(gen) }
    }

    private fun stop() {
        if (phase == Phase.OFF) return
        val gen = ++generation
        phase = if (process != null || phase == Phase.STARTING) Phase.STOPPING else Phase.OFF
        changed()
        worker.execute {
            shutdown()
            main.post {
                if (gen == generation) { phase = Phase.OFF; snapshot = null; ports = null; changed() }
            }
        }
    }

    private fun launch(gen: Int) {
        val context = app
        try {
            if (!binary(context).isFile) throw SyncthingFailure(FAILURE_MISSING)
            home(context).mkdirs()
            folderRoot(context).mkdirs()
            if (!config(context).isFile) generate(context)
            val picked = SyncthingPortPicker.pick()
            val guard = guard(context)
            val paused = SyncPrefs.userPaused(context) || autoPauseReason() != null
            SyncthingConfig.apply(config(context), picked, folderRoot(context), paused, guard::allows)
            if (paused) SyncPrefs.setPauseApplied(context, true)
            val key = SyncthingConfig.apiKey(config(context)) ?: throw SyncthingFailure("config.xml has no API key")
            if (gen != generation) return
            rotateLog(context)
            val child = ProcessBuilder(binary(context).path, "serve", "--no-browser", "--no-restart")
                .redirectErrorStream(true)
                .also { environment(context, it.environment()) }
                .start()
            process = child
            OrphanReaper.keep(HostProcess.pidOf(child))
            pump(gen, child, logFile(context))
            val client = SyncthingApi(picked.gui, key)
            val deadline = System.currentTimeMillis() + 60_000
            while (!client.ping()) {
                if (gen != generation) return
                if (!child.isAlive) throw SyncthingFailure("Syncthing exited during start (${child.exitValue()})")
                if (System.currentTimeMillis() > deadline) throw SyncthingFailure("Syncthing did not answer on 127.0.0.1:${picked.gui}")
                Thread.sleep(250)
            }
            api = client
            main.post {
                if (gen == generation) { ports = picked; apiKeyTail = key.takeLast(4); phase = Phase.RUNNING; changed() }
            }
            Thread({ poll(gen, guard) }, "syncthing-status").start()
            Thread({ enforce(gen, guard) }, "syncthing-guard").start()
        } catch (e: Exception) {
            Log.w(TAG, "start failed", e)
            shutdown()
            val reason = (e as? SyncthingFailure)?.message ?: e.toString()
            main.post { if (gen == generation) { phase = Phase.FAILED; failure = reason; changed() } }
        }
    }

    private fun generate(context: Context) {
        val output = File(home(context), "generate.log")
        val child = ProcessBuilder(binary(context).path, "generate", "--gui-user", SyncthingConfig.GUI_USER, "--gui-password", "-")
            .redirectErrorStream(true)
            .redirectOutput(output)
            .also { environment(context, it.environment()) }
            .start()
        val pid = HostProcess.pidOf(child)
        OrphanReaper.keep(pid)
        try {
            child.outputStream.bufferedWriter().use { it.write(SyncPrefs.guiPassword(context) + "\n") }
            if (!child.waitFor(60, TimeUnit.SECONDS) || child.exitValue() != 0 || !config(context).isFile) {
                child.destroy()
                val last = runCatching { output.readLines().lastOrNull { it.isNotBlank() } }.getOrNull().orEmpty()
                throw SyncthingFailure("syncthing generate failed: $last")
            }
        } finally {
            output.delete()
            OrphanReaper.release(pid)
        }
    }

    private fun environment(context: Context, env: MutableMap<String, String>) {
        listOf("LD_PRELOAD", "LD_AUDIT", "LD_LIBRARY_PATH").forEach { env.remove(it) }
        env["STHOMEDIR"] = home(context).path
        env["HOME"] = folderRoot(context).path
        env["STNOUPGRADE"] = "1"
        env["STNODEFAULTFOLDER"] = "1"
        env["STMONITORED"] = "1"
        env["SQLITE_TMPDIR"] = context.cacheDir.path
        env["STVERSIONEXTRA"] = "DroidDeck"
    }

    private fun rotateLog(context: Context) {
        val log = logFile(context)
        if (log.length() > 0) log.renameTo(File(log.parentFile, "syncthing.old.log"))
    }

    private fun pump(gen: Int, child: Process, log: File) {
        Thread({
            var last = ""
            runCatching {
                log.appendText("")
                child.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        if (line.isNotBlank()) last = line
                        if (log.length() < LOG_LIMIT) log.appendText(line + "\n")
                    }
                }
            }
            val code = runCatching { child.waitFor() }.getOrDefault(-1)
            OrphanReaper.release(HostProcess.pidOf(child))
            main.post {
                if (gen == generation && process === child && phase == Phase.RUNNING) {
                    process = null; api = null
                    phase = Phase.FAILED; failure = "Syncthing stopped ($code): $last"; snapshot = null; changed()
                }
            }
        }, "syncthing-output").start()
    }

    private fun shutdown() {
        val child = process ?: return
        api?.let { runCatching { it.shutdown() } }
        api = null
        if (!child.waitFor(10, TimeUnit.SECONDS)) {
            child.destroy()
            child.waitFor(3, TimeUnit.SECONDS)
        }
        process = null
    }

    private fun poll(gen: Int, guard: SyncthingGuard) {
        var previous: Pair<Long, Pair<Long, Long>>? = null
        var myId: String? = null
        while (gen == generation) {
            val client = api ?: return
            applyPause(client)
            try {
                val id = myId ?: client.myId().also { myId = it }
                val connections = client.connections()
                val total = connections.optJSONObject("total")
                val now = System.currentTimeMillis()
                val bytes = (total?.optLong("inBytesTotal") ?: 0L) to (total?.optLong("outBytesTotal") ?: 0L)
                val rates = previous?.let { (at, was) ->
                    val seconds = ((now - at) / 1000.0).coerceAtLeast(0.5)
                    ((bytes.first - was.first).coerceAtLeast(0) / seconds).toLong() to ((bytes.second - was.second).coerceAtLeast(0) / seconds).toLong()
                } ?: (0L to 0L)
                previous = now to bytes
                val snap = collect(client, guard, id, connections, rates)
                main.post { if (gen == generation) { snapshot = snap; changed() } }
            } catch (e: Exception) {
                Log.d(TAG, "status poll failed: $e")
            }
            Thread.sleep(if (watchers.get() > 0) 2_000 else 10_000)
        }
    }

    private fun collect(client: SyncthingApi, guard: SyncthingGuard, myId: String, connections: org.json.JSONObject, rates: Pair<Long, Long>): SyncSnapshot {
        val names = SyncthingParse.deviceNames(client.devices(), myId)
        val config = client.folders()
        val folders = (0 until config.length()).map { config.getJSONObject(it) }.map { f ->
            val id = f.getString("id")
            val path = f.optString("path")
            val blocked = !guard.allows(path)
            var paused = f.optBoolean("paused")
            if (blocked && !paused) { runCatching { client.pauseFolder(id) }; paused = true }
            val status = if (paused) null else runCatching { client.folderStatus(id) }.getOrNull()
            val shared = f.optJSONArray("devices")?.let { d -> (0 until d.length()).mapNotNull { names[d.getJSONObject(it).optString("deviceID")] } }.orEmpty()
            SyncFolder(
                id, f.optString("label").ifEmpty { id }, path, paused, blocked,
                status?.optString("state").orEmpty(), status?.optLong("globalBytes") ?: 0, status?.optLong("needBytes") ?: 0,
                (status?.optInt("errors") ?: 0) + (status?.optInt("pullErrors") ?: 0), shared,
            )
        }
        val conns = connections.optJSONObject("connections")
        val stats = runCatching { client.deviceStats() }.getOrNull()
        val devices = names.map { (id, name) ->
            val c = conns?.optJSONObject(id)
            val seen = stats?.optJSONObject(id)?.optString("lastSeen")?.let(::parseTime)
            SyncDevice(id, name, c?.optBoolean("connected") == true, c?.optString("address")?.takeIf { it.isNotEmpty() }, seen)
        }
        return SyncSnapshot(
            myId, folders, devices,
            runCatching { SyncthingParse.pendingFolders(client.pendingFolders(), names) }.getOrDefault(emptyList()),
            runCatching { SyncthingParse.pendingDevices(client.pendingDevices()) }.getOrDefault(emptyList()),
            rates.first, rates.second,
        )
    }

    private fun parseTime(s: String): Long? = runCatching {
        java.time.OffsetDateTime.parse(s).toInstant().toEpochMilli().takeIf { it > 86_400_000L }
    }.getOrNull()

    /** Pauses any folder outside DroidDeck's storage as soon as Syncthing saves its config. */
    private fun enforce(gen: Int, guard: SyncthingGuard) {
        var since = 0L
        while (gen == generation) {
            val client = api ?: return
            try {
                val (last, saved) = client.waitForConfigSaved(since, 60)
                since = last
                if (saved && gen == generation) {
                    val folders = client.folders()
                    for (i in 0 until folders.length()) {
                        val f = folders.getJSONObject(i)
                        if (!f.optBoolean("paused") && !guard.allows(f.optString("path"))) client.pauseFolder(f.getString("id"))
                    }
                }
            } catch (e: Exception) {
                Thread.sleep(2_000)
            }
        }
    }

    private fun applyPause(client: SyncthingApi) {
        val context = app
        val auto = autoPauseReason()
        if (auto != autoPause) main.post { autoPause = auto; changed() }
        val want = SyncPrefs.userPaused(context) || auto != null
        if (want == SyncPrefs.pauseApplied(context)) return
        runCatching { if (want) client.pauseAll() else client.resumeAll() }
            .onSuccess { SyncPrefs.setPauseApplied(context, want) }
            .onFailure { Log.w(TAG, "pause change failed", it) }
    }

    private fun autoPauseReason(): AutoPause? = when {
        SyncPrefs.wifiOnly(app) && !onWifi -> AutoPause.WIFI
        SyncPrefs.pauseInGames(app) && SessionState.running -> AutoPause.GAME
        else -> null
    }

    private fun changed() { onChange?.invoke() }

    private class SyncthingFailure(message: String) : Exception(message)
}
