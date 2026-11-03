package com.droiddeck.launcher.core

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.droiddeck.launcher.BuildConfig
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.tasks.Task
import java.util.concurrent.TimeUnit
import com.google.android.play.core.assetpacks.AssetPackManagerFactory
import com.google.android.play.core.assetpacks.model.AssetPackStatus
import com.google.android.play.core.splitcompat.SplitCompat
import com.google.android.play.core.splitinstall.SplitInstallManagerFactory
import com.google.android.play.core.splitinstall.SplitInstallRequest
import com.google.android.play.core.splitinstall.model.SplitInstallSessionStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipFile

internal object PlayDelivery {
    data class State(val ready: Boolean = !BuildConfig.PLAY_DISTRIBUTION, val busy: Boolean = false,
                     val failed: Boolean = false, val percent: Int = -1)

    private val mutableState = MutableStateFlow(State())
    val state = mutableState.asStateFlow()
    private lateinit var app: Context
    private val handler = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)
    private var activity = WeakReference<Activity>(null)
    @Volatile private var session = 0
    @Volatile private var confirmation = false
    @Volatile private var attempt = 0
    private var pendingSession: android.content.Intent? = null
    private var server: android.net.LocalServerSocket? = null
    private val clients = java.util.concurrent.Semaphore(2)
    private val splits by lazy { SplitInstallManagerFactory.create(app) }
    private val assets by lazy { AssetPackManagerFactory.getInstance(app) }
    private val definition by lazy {
        app.assets.open("play-components/delivery.json").bufferedReader().use { JSONObject(it.readText()) }
    }
    private fun names(key: String): List<String> = definition.getJSONArray(key).let { list ->
        (0 until list.length()).map { list.getString(it) }
    }

    fun init(context: Context) {
        if (!BuildConfig.PLAY_DISTRIBUTION) return
        app = context.applicationContext
    }

    @Synchronized
    fun defer(intent: android.content.Intent) { pendingSession = android.content.Intent(intent) }

    @Synchronized
    fun peekPendingSession(): android.content.Intent? = pendingSession?.let { android.content.Intent(it) }

    @Synchronized
    fun takePendingSession(): android.content.Intent? = pendingSession.also { pendingSession = null }

    fun start(owner: Activity) {
        if (!BuildConfig.PLAY_DISTRIBUTION) return
        activity = WeakReference(owner)
        if (mutableState.value.ready) return
        if (!running.compareAndSet(false, true)) return
        cancelled.set(false)
        attempt++
        confirmation = false
        mutableState.value = State(busy = true)
        Thread({
            var completed = State(failed = true)
            try {
                installModules()
                confirmation = false
                installAssets()
                checkCancelled()
                startServer()
                completed = State(ready = true, percent = 100)
            } catch (error: Exception) {
                android.util.Log.w("PlayDelivery", "Play component delivery failed", error)
            } finally {
                if (cancelled.get()) cancel()
                running.set(false)
                mutableState.value = completed
            }
        }, "play-components").start()
    }

    fun detach(owner: Activity) {
        if (activity.get() === owner) activity.clear()
    }

    fun cancel() {
        cancelled.set(true)
        synchronized(this) { pendingSession = null }
        if (session > 0) splits.cancelInstall(session)
        if (::app.isInitialized) runCatching { assets.cancel(names("packs")) }
    }

    private fun <T> await(task: Task<T>): T = Tasks.await(task, 30, TimeUnit.SECONDS)

    private fun checkCancelled() {
        if (cancelled.get() || Thread.currentThread().isInterrupted) throw IOException("Play delivery cancelled")
    }

    private fun installModules() {
        val missing = names("modules").filterNot { it in splits.installedModules }
        if (missing.isEmpty()) return
        val previous = await(splits.sessionStates).firstOrNull {
            it.moduleNames().containsAll(missing) && it.status() in setOf(
                SplitInstallSessionStatus.PENDING, SplitInstallSessionStatus.DOWNLOADING,
                SplitInstallSessionStatus.DOWNLOADED, SplitInstallSessionStatus.INSTALLING,
                SplitInstallSessionStatus.REQUIRES_USER_CONFIRMATION)
        }
        session = previous?.sessionId() ?: run {
            val request = attempt
            val task = splits.startInstall(SplitInstallRequest.newBuilder().apply { missing.forEach(::addModule) }.build())
            task.addOnSuccessListener { id -> if (request == attempt && cancelled.get()) splits.cancelInstall(id) }
            await(task)
        }
        while (true) {
            checkCancelled()
            val current = await(splits.getSessionState(session))
            val request = attempt
            when (current.status()) {
                SplitInstallSessionStatus.INSTALLED -> {
                    if (!SplitCompat.install(app) || !splits.installedModules.containsAll(missing))
                        throw IOException("Play modules could not be activated")
                    session = 0
                    return
                }
                SplitInstallSessionStatus.FAILED, SplitInstallSessionStatus.CANCELED ->
                    throw IOException("Play module delivery failed: ${current.errorCode()}")
                SplitInstallSessionStatus.REQUIRES_USER_CONFIRMATION -> handler.post {
                    val owner = activity.get()
                    if (request == attempt && session == current.sessionId() && running.get() && !cancelled.get() &&
                        !confirmation && owner != null && !owner.isFinishing && !owner.isDestroyed) {
                        runCatching { splits.startConfirmationDialogForResult(current, owner, 7619) }
                            .onSuccess { confirmation = it }.onFailure { cancel() }
                    }
                }
            }
            val total = current.totalBytesToDownload()
            mutableState.value = State(busy = true, percent = if (total > 0)
                (current.bytesDownloaded() * 80 / total).toInt().coerceIn(0, 80) else -1)
            Thread.sleep(350)
        }
    }

    private fun installAssets() {
        val packs = names("packs")
        val missing = packs.filter { assets.getPackLocation(it) == null }
        if (missing.isEmpty()) return
        val requestAttempt = attempt
        val fetch = assets.fetch(missing)
        fetch.addOnSuccessListener { if (requestAttempt == attempt && cancelled.get()) assets.cancel(missing) }
        await(fetch)
        while (true) {
            checkCancelled()
            val states = await(assets.getPackStates(missing)).packStates().values
            if (states.any { it.status() == AssetPackStatus.FAILED || it.status() == AssetPackStatus.CANCELED })
                throw IOException("Play data delivery failed")
            if (missing.all { assets.getPackLocation(it) != null }) return
            val request = attempt
            if (states.any { it.status() == AssetPackStatus.WAITING_FOR_WIFI || it.status() == AssetPackStatus.REQUIRES_USER_CONFIRMATION })
                handler.post {
                    val owner = activity.get()
                    if (request == attempt && running.get() && !cancelled.get() &&
                        !confirmation && owner != null && !owner.isFinishing && !owner.isDestroyed) {
                        confirmation = true
                        assets.showConfirmationDialog(owner).addOnSuccessListener { result ->
                            if (request == attempt && result != Activity.RESULT_OK) cancel()
                        }.addOnFailureListener { if (request == attempt) cancel() }
                    }
                }
            val total = states.sumOf { it.totalBytesToDownload() }
            val downloaded = states.sumOf { it.bytesDownloaded() }
            mutableState.value = State(busy = true, percent = if (total > 0)
                (80 + downloaded * 19 / total).toInt().coerceIn(80, 99) else -1)
            Thread.sleep(500)
        }
    }

    @Synchronized
    private fun startServer() {
        if (server != null) return
        val listener = android.net.LocalServerSocket(app.packageName + ".play-components")
        server = listener
        Thread({
            while (true) {
                val client = try { listener.accept() } catch (_: IOException) { break }
                if (!clients.tryAcquire()) { client.close(); continue }
                Thread({
                    try {
                        client.use {
                            client.soTimeout = 30_000
                            if (client.peerCredentials.uid != android.os.Process.myUid()) return@use
                            val input = java.io.DataInputStream(client.inputStream)
                            val length = input.readInt()
                            if (length !in 1..256) return@use
                            val path = ByteArray(length).also(input::readFully).toString(Charsets.UTF_8)
                            if (!Regex("play-components/[0-9a-f]{64}/[0-9]{4,}").matches(path)) return@use
                            val output = client.outputStream
                            open(path).use { source ->
                                output.write(1)
                                source.copyTo(output, 65536)
                            }
                        }
                    } catch (error: IOException) {
                        android.util.Log.w("PlayDelivery", "Component stream closed", error)
                    } finally {
                        clients.release()
                    }
                }, "play-component-reader").apply { isDaemon = true; start() }
            }
        }, "play-component-server").apply { isDaemon = true; start() }
    }

    @Throws(IOException::class)
    fun open(path: String): InputStream {
        try {
            return app.assets.open(path)
        } catch (_: java.io.FileNotFoundException) {
            val info = app.packageManager.getApplicationInfo(app.packageName, 0)
            for (split in info.splitSourceDirs.orEmpty()) {
                val archive = ZipFile(split)
                val entry = archive.getEntry("assets/$path")
                if (entry == null) {
                    archive.close()
                    continue
                }
                return object : FilterInputStream(archive.getInputStream(entry)) {
                    override fun close() { try { super.close() } finally { archive.close() } }
                }
            }
            for (name in names("packs")) {
                val directory = assets.getPackLocation(name)?.assetsPath()?.let(::File) ?: continue
                val file = File(directory, path)
                if (file.canonicalPath.startsWith(directory.canonicalPath + File.separator) && file.isFile)
                    return file.inputStream()
            }
            if (!running.get()) mutableState.value = State(failed = true)
            throw IOException("Play component is not installed: $path")
        }
    }
}
