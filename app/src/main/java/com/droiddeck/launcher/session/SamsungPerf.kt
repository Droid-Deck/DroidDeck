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
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random

/**
 * Samsung's CPU, GPU and memory-bus clock floors, from the performance service in Samsung's firmware.
 * DroidDeck talks to it directly, the way Samsung's Galaxy Performance SDK 1.0.0 does, so the SDK is
 * not needed: the binder service [SERVICE] sends the phone's level table over a local socket, and the
 * firmware library [LIBRARY] ([Boost]) holds a level. Other phones have neither, and nothing happens.
 *
 * The floors are those of the SDK's CPU, GPU and bus presets (the middle of five levels, at least four
 * cores online), asked for with a timeout of at most [HOLD_MS], so Samsung lets go on its own if the
 * app dies. Samsung may hold a request for less than asked (1 s on an SM8850 Galaxy Z Fold), so they
 * are renewed every [renewMs].
 *
 * A phone without the firmware library is not asked again until the app restarts. Where the library
 * is there, a failed connection is tried again [TRIES] times a session, each failure with its reason
 * in [Tries.failed], and again at the next session.
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
    private const val SEEN_HOLD_MS = 1000
    internal const val TRIES = 4
    private val RETRY_S = intArrayOf(5, 15, 30)

    /** Half the hold, and no later than half the shortest hold Samsung has been seen to use. */
    internal fun renewMs(holdMs: Int): Long = (minOf(holdMs, SEEN_HOLD_MS) / 2).coerceAtLeast(100).toLong()

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

    /** One session's tries to connect: why each failed, and whether all [TRIES] did. */
    internal class Tries {
        val failed = CopyOnWriteArrayList<String>()
        @Volatile var gaveUp = false
    }

    /** What the log says after try [n] failed. */
    internal fun retryNote(n: Int): String =
        if (n < TRIES) "trying again in ${RETRY_S[n - 1]} s" else "no more tries until the next game"

    /** A failed try's reason: the step it was on and what went wrong there. */
    internal fun failure(step: String, t: Throwable): String {
        val cause = (t as? InvocationTargetException)?.targetException ?: t
        return "failed while $step (${cause.javaClass.simpleName}${cause.message?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""})"
    }

    private val connectLock = Any()
    @Volatile private var floors: List<Floor>? = null
    @Volatile private var unavailable: String? = null
    private var socket: LocalSocket? = null // kept open for the process's life, as the SDK does
    @Volatile internal var tries = Tries()

    private class Failed(val why: String) : Exception(why)

    /** Null where there is no Samsung service (the reason in [unavailable]) or this try failed (the reason in [t]). */
    private fun connect(t: Tries): List<Floor>? = synchronized(connectLock) { connectLocked(t) }

    private fun connectLocked(t: Tries): List<Floor>? {
        floors?.let { return it }
        if (unavailable != null) return null
        try {
            System.loadLibrary(LIBRARY)
        } catch (_: UnsatisfiedLinkError) {
            unavailable = "no Samsung performance library"
            offWhy = unavailable
            Log.i(TAG, "not available: $unavailable")
            return null
        }
        var step = "looking up Samsung's performance service"
        var client: LocalSocket? = null
        try {
            val service = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
                .invoke(null, SERVICE) as IBinder? ?: throw Failed("Samsung's performance service ($SERVICE) is not running")
            val name = "PERFSDK_SOC_" + Random.nextInt(36 * 36 * 36 * 36 * 36).toString(36).padStart(5, '0')
            step = "opening a socket for the service"
            val server = LocalServerSocket(name) // the service connects to it; Closeable only from Android 10
            val accepted = try {
                step = "asking the service to connect"
                val init = call(service, CALL_INIT, { writeString(name) }, { readInt() })
                step = "asking the service for the package name"
                val pkg = call(service, CALL_PACKAGE, {}, { readString() })
                if (!pkg.isNullOrEmpty()) Boost.setPkgName(pkg)
                Log.i(TAG, "service: init $init, package $pkg")
                step = "waiting for the service to connect"
                val poll = StructPollfd().apply { fd = server.fileDescriptor; events = OsConstants.POLLIN.toShort() }
                if (Os.poll(arrayOf(poll), SOCKET_TIMEOUT_MS) < 1) {
                    throw Failed("the service did not connect within ${SOCKET_TIMEOUT_MS / 1000} s (it answered $init, package ${pkg ?: "none"})")
                }
                server.accept()
            } finally {
                server.close()
            }
            client = accepted
            step = "reading the service's level table"
            accepted.soTimeout = SOCKET_TIMEOUT_MS
            DataOutputStream(accepted.outputStream).apply { writeInt(Integer.reverseBytes(ASK_LEVELS)); flush() }
            val levels = readLevels(accepted.inputStream)
            Log.i(TAG, "levels: cpu ${levels.cpu.joinToString()} / gpu ${levels.gpu.joinToString()} / " +
                "bus ${levels.bus.joinToString()}, max ${levels.maxTimeoutMs} ms")
            val found = floors(levels)
            if (found.isEmpty()) {
                throw Failed("the level table has no usable floors (cpu ${levels.cpu.joinToString()} / gpu ${levels.gpu.joinToString()} / bus ${levels.bus.joinToString()})")
            }
            socket = accepted
            client = null
            floors = found
            return found
        } catch (e: Throwable) {
            val why = (e as? Failed)?.why ?: failure(step, e)
            t.failed.add(why)
            offWhy = "could not connect"
            Log.w(TAG, "try ${t.failed.size} of $TRIES failed: $why", e.takeIf { it !is Failed })
            return null
        } finally {
            client?.let { runCatching { it.close() } }
        }
    }

    private fun <T> call(service: IBinder, code: Int, write: Parcel.() -> Unit, read: Parcel.() -> T): T {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.write()
            if (!service.transact(code, data, reply, 0)) throw Failed("the service does not take call $code")
            reply.readException()
            return reply.read()
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private var worker: Thread? = null

    /** For BoostReport: whether the floors are held now, why not, and what the library answered when they were asked for. */
    @Volatile internal var holding = false
    @Volatile internal var offWhy: String? = null
    @Volatile internal var lastResults = emptyList<Int>()

    internal val connected get() = floors != null

    /** For BoostReport. */
    internal val state: String
        get() = unavailable?.let { "not available: $it" }
            ?: floors?.let { f ->
                val hold = f.minOf { it.timeoutMs }
                "connected, floors " + f.joinToString { "${TYPE_NAMES[it.type]} ${it.value}" } +
                    ", asked for $hold ms at a time, renewed every ${renewMs(hold)} ms"
            }
            ?: tries.let { t ->
                when {
                    t.gaveUp -> "could not connect in $TRIES tries (last: ${t.failed.lastOrNull()})"
                    t.failed.isNotEmpty() -> "connecting, try ${t.failed.size + 1} of $TRIES"
                    else -> "connecting"
                }
            }

    internal fun floorValue(type: Int): Int? = floors?.firstOrNull { it.type == type }?.value

    private val TYPE_NAMES = mapOf(CPU_MIN to "CPU", GPU_MIN to "GPU", BUS_MIN to "bus", CPU_CORES_MIN to "cores")

    /** At a session's start. The line is for the log. */
    @Synchronized
    fun start(context: Context): String {
        if (!SessionPrefs.performanceBoost(context)) return "samsung boost: off"
        unavailable?.let { return "samsung boost: $it" }
        val app = context.applicationContext
        worker?.interrupt()
        offWhy = "starting"
        val t = Tries()
        tries = t
        worker = Thread({ run(app, t) }, "samsung-boost").apply { isDaemon = true; start() }
        return "samsung boost: watching"
    }

    /** At a session's end. */
    @Synchronized
    fun stop() {
        worker?.interrupt()
        worker = null
    }

    private fun run(context: Context, t: Tries) {
        var boosting = false
        try {
            var found = connect(t)
            while (found == null && unavailable == null && t.failed.size < TRIES) {
                Thread.sleep(RETRY_S[t.failed.size - 1] * 1000L)
                found = connect(t)
            }
            if (found == null) {
                if (unavailable == null) {
                    t.gaveUp = true
                    offWhy = "could not connect in $TRIES tries"
                }
                return
            }
            val floors: List<Floor> = found
            val renew = renewMs(floors.minOf { it.timeoutMs })
            val pm = context.getSystemService(PowerManager::class.java)
            val frames = ArrayDeque<Long>()
            var pacedFor = 0
            while (!Thread.currentThread().isInterrupted) {
                frames.addLast(WaylandCompositor.gameFrameCount())
                if (frames.size > GameThreadHints.PACE_WINDOW_S + 1) frames.removeFirst()
                val fps = (frames.last() - frames.first()).toDouble() / (frames.size - 1).coerceAtLeast(1)
                val paced = frames.size > GameThreadHints.PACE_WINDOW_S && GameThreadHints.holdsPace(fps, SessionState.refreshHz.toDouble())
                pacedFor = if (paced) pacedFor + 1 else 0
                val hot = Build.VERSION.SDK_INT >= 29 && pm.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE
                val why = when {
                    BoostReport.paused -> "check pause"
                    fps < 1 -> "no frames"
                    pm.isPowerSaveMode -> BoostReport.BATTERY_SAVER
                    hot -> BoostReport.TOO_HOT
                    pacedFor >= GameThreadHints.PACE_LEAVE_S -> BoostReport.KEEPS_UP
                    else -> null
                }
                offWhy = why
                val want = why == null
                if (want) {
                    val results = request(floors)
                    holding = true
                    if (!boosting) {
                        lastResults = results
                        Log.i(TAG, "boosting (game ${fps.toInt()} fps, results $results)")
                    }
                } else if (boosting) {
                    holding = false // first, so BoostReport does not read the release as Samsung letting go
                    Boost.releaseBoost()
                    BoostReport.letGo(why!!)
                    Log.i(TAG, "let go ($why, game ${fps.toInt()} fps)")
                }
                boosting = want
                var slept = 0L
                while (slept + renew < 1000) {
                    Thread.sleep(renew)
                    slept += renew
                    if (boosting) request(floors)
                }
                Thread.sleep(1000 - slept)
            }
        } catch (_: InterruptedException) {
        } catch (e: Throwable) {
            Log.w(TAG, "stopped", e)
        } finally {
            if (boosting) runCatching { Boost.releaseBoost() }
            holding = false
        }
    }

    private fun request(floors: List<Floor>) = floors.map { Boost.requestBoost(it.type, it.value, it.timeoutMs) }
}
