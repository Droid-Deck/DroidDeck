package com.droiddeck.launcher.session

import android.content.Context
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.Build
import android.os.IBinder
import android.os.Parcel
import android.os.PowerManager
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.util.Log
import com.droiddeck.launcher.wayland.WaylandCompositor
import com.samsung.sdk.sperf.Boost
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import kotlin.random.Random

/**
 * Samsung's CPU, GPU and memory-bus clock floors, from the performance service in Samsung's firmware.
 * DroidDeck talks to it directly, the way Samsung's Galaxy Performance SDK 1.0.0 does, so the SDK is
 * not needed: the binder service [SERVICE] sends the phone's level table over a local socket, and the
 * firmware library [LIBRARY] ([Boost]) holds a level. Other phones have neither, and nothing happens.
 *
 * The floors are those of the SDK's CPU, GPU and bus presets (the middle of five levels, at least four
 * cores online), renewed every [RENEW_S] seconds with a [HOLD_MS] timeout, so Samsung lets go on its
 * own if the app dies.
 */
object SamsungPerf {
    private const val TAG = "SamsungPerf"
    private const val LIBRARY = "perfsdk.performance.samsung"
    private const val SERVICE = "perfsdkservice"
    private const val DESCRIPTOR = "com.samsung.android.perfsdkservice.IPerfSDKService"
    private const val CALL_INIT = IBinder.FIRST_CALL_TRANSACTION // initPerfSDK(socket name): Int
    private const val CALL_PACKAGE = IBinder.FIRST_CALL_TRANSACTION + 1 // getAllowedPkgName(): String
    private const val ASK_LEVELS = 10
    private const val SOCKET_TIMEOUT_MS = 3000

    // Boost.requestBoost types
    internal const val CPU_MIN = 0
    internal const val GPU_MIN = 2
    internal const val BUS_MIN = 4
    internal const val CPU_CORES_MIN = 6
    private const val LEVEL = 2
    private const val CORES = 4
    internal const val HOLD_MS = 5000
    private const val RENEW_S = 2

    /** The floors the phone offers, five values each from low to high, and the longest timeout (≤ 0: none). */
    internal class Levels(val cpu: IntArray, val gpu: IntArray, val bus: IntArray, val maxTimeoutMs: Int)

    /** The service's answer to [ASK_LEVELS] in little-endian ints: the floors, then the ceilings, unused here. */
    internal fun readLevels(stream: InputStream): Levels {
        val input = DataInputStream(stream)
        fun next() = Integer.reverseBytes(input.readInt())
        fun five() = IntArray(5) { next() }
        repeat(3) { next() }
        val timeout = next()
        val levels = Levels(five(), five(), five(), timeout)
        repeat(3 + 1 + 15) { next() } // level counts, the ceilings' timeout, CPU, GPU and bus ceilings
        return levels
    }

    /** One Boost.requestBoost call. */
    internal data class Floor(val type: Int, val value: Int, val timeoutMs: Int)

    internal fun floors(levels: Levels): List<Floor> {
        val timeout = if (levels.maxTimeoutMs > 0) minOf(HOLD_MS, levels.maxTimeoutMs) else HOLD_MS
        return listOf(
            Floor(CPU_MIN, levels.cpu[LEVEL], timeout),
            Floor(CPU_CORES_MIN, CORES, timeout),
            Floor(GPU_MIN, levels.gpu[LEVEL], timeout),
            Floor(BUS_MIN, levels.bus[LEVEL], timeout),
        ).filter { it.value > 0 }
    }

    private val connectLock = Any()
    private var floors: List<Floor>? = null
    @Volatile private var unavailable: String? = null
    private var socket: LocalSocket? = null // kept open for the process's life, as the SDK does

    /** Once per process. Null where there is no Samsung service, with the reason in [unavailable]. */
    private fun connect(): List<Floor>? = synchronized(connectLock) { connectLocked() }

    private fun connectLocked(): List<Floor>? {
        floors?.let { return it }
        if (unavailable != null) return null
        try {
            System.loadLibrary(LIBRARY)
            val service = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
                .invoke(null, SERVICE) as IBinder? ?: return fail("no $SERVICE service")
            val name = "PERFSDK_SOC_" + Random.nextInt(36 * 36 * 36 * 36 * 36).toString(36).padStart(5, '0')
            val server = LocalServerSocket(name) // the service connects to it; Closeable only from Android 10
            val client = try {
                val init = call(service, CALL_INIT, { writeString(name) }, { readInt() })
                val pkg = call(service, CALL_PACKAGE, {}, { readString() })
                if (!pkg.isNullOrEmpty()) Boost.setPkgName(pkg)
                Log.i(TAG, "service: init $init, package $pkg")
                val poll = StructPollfd().apply { fd = server.fileDescriptor; events = OsConstants.POLLIN.toShort() }
                if (Os.poll(arrayOf(poll), SOCKET_TIMEOUT_MS) < 1) return fail("$SERVICE did not connect")
                server.accept()
            } finally {
                server.close()
            }
            client.soTimeout = SOCKET_TIMEOUT_MS
            DataOutputStream(client.outputStream).apply { writeInt(Integer.reverseBytes(ASK_LEVELS)); flush() }
            val levels = readLevels(client.inputStream)
            socket = client
            Log.i(TAG, "levels: cpu ${levels.cpu.joinToString()} / gpu ${levels.gpu.joinToString()} / " +
                "bus ${levels.bus.joinToString()}, max ${levels.maxTimeoutMs} ms")
            val found = floors(levels)
            if (found.isEmpty()) return fail("no usable levels")
            floors = found
            return found
        } catch (_: UnsatisfiedLinkError) {
            return fail("no Samsung performance library")
        } catch (t: Throwable) {
            Log.w(TAG, "could not connect", t)
            return fail("could not connect (${t.javaClass.simpleName})")
        }
    }

    private fun fail(why: String): List<Floor>? {
        unavailable = why
        Log.i(TAG, "not available: $why")
        return null
    }

    private fun <T> call(service: IBinder, code: Int, write: Parcel.() -> Unit, read: Parcel.() -> T): T {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.write()
            service.transact(code, data, reply, 0)
            reply.readException()
            return reply.read()
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private var worker: Thread? = null

    /** At a session's start. The line is for the log. */
    @Synchronized
    fun start(context: Context): String {
        if (!SessionPrefs.performanceBoost(context)) return "samsung boost: off"
        unavailable?.let { return "samsung boost: $it" }
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
        val floors = connect() ?: return
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
                    val results = floors.map { Boost.requestBoost(it.type, it.value, it.timeoutMs) }
                    if (!boosting) Log.i(TAG, "boosting (game ${fps.toInt()} fps, results $results)")
                } else if (!want && boosting) {
                    Boost.releaseBoost()
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
            if (boosting) runCatching { Boost.releaseBoost() }
        }
    }
}
