package com.droiddeck.launcher.session

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.util.Log
import com.android.dx.command.dexer.DxContext
import com.android.dx.command.dexer.Main
import com.droiddeck.launcher.wayland.WaylandCompositor
import dalvik.system.DexClassLoader
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.reflect.Method
import java.util.zip.ZipFile

/**
 * Samsung's Galaxy Performance SDK (com.samsung.sdk.sperf): CPU, GPU and memory-bus clock floors from
 * Samsung's own service. Its licence does not allow passing the jar on, so a Samsung owner downloads
 * it from [DOWNLOAD_URL] and imports it; it is turned into dex on the phone and loaded from there.
 *
 * The presets are renewed every [RENEW_S] seconds with a [HOLD_MS] timeout, so Samsung's service
 * lets go on its own if the app dies.
 */
object SamsungPerf {
    enum class ImportResult { IMPORTED, INVALID, FAILED }

    private const val TAG = "SamsungPerf"
    const val DOWNLOAD_URL = "https://developer.samsung.com/galaxy-performance/download.html"
    private const val SPERF = "com.samsung.sdk.sperf"
    private val REQUIRED = listOf("SPerf", "PerformanceManager").map { "${SPERF.replace('.', '/')}/$it.class" }
    private const val PRESETS = 64 or 128 or 256 // PerformanceManager.PRESET_TYPE_CPU | _GPU | _BUS
    private const val HOLD_MS = 5000
    private const val RENEW_S = 2

    val samsung: Boolean get() = Build.MANUFACTURER.equals("samsung", ignoreCase = true)

    fun offered(context: Context) = samsung || SessionPrefs.samsungPerfAnyPhone(context)

    fun imported(context: Context) = dexFile(context).isFile

    private fun dir(context: Context) = File(context.filesDir, "samsung-perf")

    private fun dexFile(context: Context) = File(dir(context), "perfsdk.dex.jar")

    @Synchronized
    fun import(context: Context, source: File): ImportResult {
        val dir = dir(context).apply { mkdirs() }
        val jar = File(dir, "perfsdk.jar.staged")
        val dex = File(dir, "perfsdk.dex.jar.staged")
        try {
            if (!extractJar(source, jar)) return ImportResult.INVALID
            val log = ByteArrayOutputStream()
            val dx = DxContext(log, log)
            val args = Main.Arguments(dx).apply {
                fileNames = arrayOf(jar.path)
                outName = dex.path
                jarOutput = true
                minSdkVersion = 26
                makeOptionsObjects()
            }
            val status = Main(dx).runDx(args)
            if (status != 0 || !dex.isFile) {
                Log.w(TAG, "dx exit $status: $log")
                return ImportResult.FAILED
            }
            val target = dexFile(context)
            target.setWritable(true)
            target.delete()
            if (!dex.renameTo(target)) return ImportResult.FAILED
            target.setReadOnly()
            sdk = null
            unusable = false
            Log.i(TAG, "SDK from $source ready (${target.length()} bytes of dex)")
            return ImportResult.IMPORTED
        } catch (t: Throwable) {
            Log.w(TAG, "could not import $source", t)
            return ImportResult.FAILED
        } finally {
            jar.delete()
            dex.delete()
        }
    }

    /** Samsung's zip also holds the sample app's different perfsdk.jar; Libs/ is the SDK's own. */
    internal fun extractJar(source: File, target: File): Boolean = try {
        ZipFile(source).use { zip -> extractJar(zip, source, target) }
    } catch (_: java.util.zip.ZipException) {
        false
    }

    private fun extractJar(zip: ZipFile, source: File, target: File): Boolean {
        if (REQUIRED.all { zip.getEntry(it) != null }) {
            source.copyTo(target, overwrite = true)
            return true
        }
        val jars = zip.entries().asSequence()
            .filter { !it.isDirectory && it.name.endsWith(".jar", ignoreCase = true) }
            .sortedByDescending { it.name.contains("Libs/") }
            .toList()
        for (entry in jars) {
            zip.getInputStream(entry).use { input -> target.outputStream().use { input.copyTo(it) } }
            val found = runCatching { ZipFile(target).use { inner -> REQUIRED.all { inner.getEntry(it) != null } } }.getOrDefault(false)
            if (found) return true
        }
        return false
    }

    private class Sdk(val manager: Any, val start: Method, val stop: Method)

    private var sdk: Sdk? = null
    private var unusable = false

    /** SPerf.initialize is false where Samsung's service is missing. */
    @Synchronized
    private fun load(context: Context): Sdk? {
        sdk?.let { return it }
        if (unusable || !imported(context)) return null
        try {
            val loader = DexClassLoader(dexFile(context).path, context.codeCacheDir.path, null, context.classLoader)
            val sperf = loader.loadClass("$SPERF.SPerf")
            runCatching { sperf.getMethod("setDebugModeEnabled", Boolean::class.javaPrimitiveType).invoke(null, false) }
            if (sperf.getMethod("initialize", Context::class.java).invoke(null, context.applicationContext) != true) {
                Log.i(TAG, "SDK loaded, but this phone does not support it")
                unusable = true
                return null
            }
            val pm = loader.loadClass("$SPERF.PerformanceManager")
            val manager = pm.getMethod("getInstance").invoke(null) ?: return null
            val int = Int::class.javaPrimitiveType
            return Sdk(manager, pm.getMethod("start", int, int), pm.getMethod("stop")).also { sdk = it }
        } catch (t: Throwable) {
            Log.w(TAG, "SDK not usable", t)
            unusable = true
            return null
        }
    }

    private var worker: Thread? = null

    /** At a session's start. The line is for the log. */
    @Synchronized
    fun start(context: Context): String {
        if (!offered(context)) return "samsung boost: not a Samsung phone"
        if (!SessionPrefs.performanceBoost(context)) return "samsung boost: off"
        if (!imported(context)) return "samsung boost: SDK not imported"
        val app = context.applicationContext
        worker?.interrupt()
        worker = Thread({ run(app) }, "samsung-boost").apply { isDaemon = true; start() }
        return "samsung boost: watching"
    }

    /** At a session's end. */
    @Synchronized
    fun stop() {
        worker?.interrupt()
        worker = null
    }

    private fun run(context: Context) {
        val sdk = load(context) ?: return
        val pm = context.getSystemService(PowerManager::class.java)
        val frames = ArrayDeque<Long>()
        var pacedFor = 0
        var boosting = false
        var tick = 0
        try {
            while (!Thread.currentThread().isInterrupted) {
                frames.addLast(WaylandCompositor.gameFrameCount())
                if (frames.size > GameThreadHints.PACE_WINDOW_S + 1) frames.removeFirst()
                val fps = (frames.last() - frames.first()).toDouble() / (frames.size - 1).coerceAtLeast(1)
                val paced = frames.size > GameThreadHints.PACE_WINDOW_S && GameThreadHints.holdsPace(fps, SessionState.refreshHz.toDouble())
                pacedFor = if (paced) pacedFor + 1 else 0
                val hot = Build.VERSION.SDK_INT >= 29 && pm.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE
                val want = fps >= 1 && !pm.isPowerSaveMode && !hot && pacedFor < GameThreadHints.PACE_LEAVE_S
                if (want && (!boosting || tick % RENEW_S == 0)) {
                    val result = sdk.start.invoke(sdk.manager, PRESETS, HOLD_MS)
                    if (!boosting) Log.i(TAG, "boosting (game ${fps.toInt()} fps, start $result)")
                } else if (!want && boosting) {
                    sdk.stop.invoke(sdk.manager)
                    Log.i(TAG, "let go (game ${fps.toInt()} fps${if (hot) ", hot" else ""}${if (paced) ", holding pace" else ""})")
                }
                boosting = want
                tick++
                Thread.sleep(1000)
            }
        } catch (_: InterruptedException) {
        } catch (t: Throwable) {
            Log.w(TAG, "stopped", t)
        } finally {
            if (boosting) runCatching { sdk.stop.invoke(sdk.manager) }
        }
    }
}
