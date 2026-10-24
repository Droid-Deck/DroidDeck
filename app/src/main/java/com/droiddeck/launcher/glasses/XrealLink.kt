package com.droiddeck.launcher.glasses

import android.content.Context
import android.util.Log
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** One connection owns two TCP channels. All control requests are serialized off the UI thread. */
internal class XrealLink internal constructor(
    private val connector: XrealConnector,
    private val listener: Listener,
    private val model: XrealModel = XrealModel.ONE_S,
) : GlassesLink {
    interface Listener {
        fun onReady()
        fun onValue(message: Int, value: Int)
        fun onPose(raw: ByteArray, pose: FloatArray, timeNs: Long)
        fun onUnavailable(networkMissing: Boolean)
    }

    private data class Command(val message: Int, val value: Int)
    private val commands = ArrayBlockingQueue<Command>(16)
    private val connection = AtomicReference<XrealChannels?>()
    @Volatile private var running = true
    @Volatile private var tracking = false
    @Volatile override var ready = false
        private set
    override val hasImu = true
    private val worker = Thread(::run, "xreal-control").apply { isDaemon = true; start() }

    companion object {
        fun open(context: Context, listener: Listener, model: XrealModel) =
            XrealLink(AndroidXrealConnector(context), listener, model)
    }

    override fun send(msgId: Int, payload: ByteArray): Boolean {
        if (!running || !ready) return false
        val value = payload.firstOrNull()?.toInt()?.and(255) ?: -1
        if (msgId == VitureProtocol.MSG_IMU) {
            if (value !in 0..1) return false
            tracking = value == 1
            return true
        }
        if (msgId == VitureProtocol.MSG_GET_IMU) return true
        val valid = when (msgId) {
            VitureProtocol.MSG_GET_DISPLAY -> true
            VitureProtocol.MSG_DISPLAY -> model.supportsDisplayMode(value)
            VitureProtocol.MSG_BRIGHTNESS -> model.brightnessMax > 0 && value in 0..model.brightnessMax
            VitureProtocol.MSG_FILM -> model.hasSunblock && value in 0..1
            else -> false // No guessed volume, sensor-rate, or VITURE wire commands.
        }
        return valid && commands.offer(Command(msgId, value))
    }

    private fun run() {
        var lastError: String? = null
        while (running) {
            var channels: XrealChannels? = null
            var reports: Thread? = null
            val failure = AtomicReference<Exception?>()
            try {
                channels = connector.connect()
                connection.set(channels)
                if (!running) break
                val control = XrealControl(channels, model = model)
                listener.onValue(VitureProtocol.MSG_GET_DISPLAY, control.display())
                val current = channels
                reports = Thread({
                    try {
                        var filter = XrealOrientationFilter()
                        var wasTracking = false
                        var lastSensor = System.nanoTime()
                        while (running && connection.get() === current) {
                            val frame = XrealProtocol.read(current.reports)
                            val sample = XrealProtocol.sample(frame)
                            if (sample != null) {
                                lastSensor = System.nanoTime()
                                val enabled = tracking
                                if (enabled && !wasTracking) filter = XrealOrientationFilter()
                                wasTracking = enabled
                                if (enabled) filter.update(sample)?.let {
                                    if (running && ready && tracking && connection.get() === current)
                                        listener.onPose(frame.body, it, System.nanoTime())
                                }
                            } else if (System.nanoTime() - lastSensor > 3_000_000_000L) {
                                throw IOException("No valid XREAL IMU reports")
                            }
                        }
                    } catch (e: Exception) {
                        failure.set(e)
                        current.close() // Wake a control read immediately.
                    }
                }, "xreal-reports").apply { isDaemon = true; start() }
                ready = true
                lastError = null
                listener.onReady()
                var heartbeat = System.nanoTime()
                while (running && failure.get() == null) {
                    val command = commands.poll(250, TimeUnit.MILLISECONDS)
                    if (command != null) {
                        when (command.message) {
                            VitureProtocol.MSG_GET_DISPLAY ->
                                listener.onValue(command.message, control.display())
                            VitureProtocol.MSG_DISPLAY -> {
                                listener.onValue(VitureProtocol.MSG_GET_DISPLAY, control.changeDisplay(command.value))
                            }
                            VitureProtocol.MSG_BRIGHTNESS -> {
                                control.set(XrealProtocol.SET_BRIGHTNESS, command.value)
                                listener.onValue(command.message, command.value)
                            }
                            VitureProtocol.MSG_FILM -> {
                                control.set(XrealProtocol.SET_DIMMER, if (command.value == 1) 2 else 0)
                                listener.onValue(command.message, command.value)
                            }
                        }
                    }
                    if (System.nanoTime() - heartbeat > 5_000_000_000L) {
                        listener.onValue(VitureProtocol.MSG_GET_DISPLAY, control.display())
                        heartbeat = System.nanoTime()
                    }
                }
                failure.get()?.let { throw it }
            } catch (e: Exception) {
                ready = false
                if (running && lastError != e.message) {
                    lastError = e.message
                    Log.w("XrealLink", "Connection unavailable: ${e.message}")
                    listener.onUnavailable(e is XrealNetworkMissing)
                }
            } finally {
                ready = false
                tracking = false
                commands.clear()
                connection.compareAndSet(channels, null)
                channels?.close()
                try { reports?.join(2500) } catch (_: InterruptedException) { }
            }
            if (running) try { Thread.sleep(2000) } catch (_: InterruptedException) { }
        }
    }

    override fun close() {
        running = false
        ready = false
        tracking = false
        commands.clear()
        connection.getAndSet(null)?.close()
        connector.close() // Includes sockets still inside connect().
        worker.interrupt()
    }
}

internal class XrealControl(
    private val channels: XrealChannels,
    private val settleDelayMs: Long = 150,
    private val model: XrealModel = XrealModel.ONE_S,
) {
    private var transaction = 0
    private fun exchange(command: Int, value: Int? = null): ByteArray {
        transaction = if (transaction == Int.MAX_VALUE) 1 else transaction + 1
        channels.controlOutput.write(XrealProtocol.request(command, transaction, value))
        channels.controlOutput.flush()
        val deadline = System.nanoTime() + 3_000_000_000L
        repeat(32) { // Bound unsolicited button/status packets and stale responses.
            if (System.nanoTime() > deadline) throw IOException("XREAL control timed out")
            XrealProtocol.response(XrealProtocol.read(channels.controlInput), command, transaction)?.let { return it }
        }
        throw IOException("XREAL control response was not received")
    }
    fun display(): Int = awaitDisplay { true }
    fun changeDisplay(mode: Int): Int {
        require(model.supportsDisplayMode(mode)) { "Unsupported ${model.name} display mode: $mode" }
        val stereo = mode == 5
        set(XrealProtocol.SET_INPUT, if (stereo) 1 else 0)
        // One firmware can restore mode 9 and reject the legacy 2/3/4 EDIDs.
        // Let SET_INPUT restore its native mode; Android selects advertised refresh rates.
        if (model === XrealModel.ONE && !stereo) return awaitDisplay { it != 5 }
        var requested = false
        return awaitDisplay { current ->
            if (current == mode) true else {
                // 1S switches EDID with SET_INPUT; One may require SET_DISPLAY.
                if (!requested && (model === XrealModel.ONE || (current == 5) == stereo)) {
                    val reply = exchange(XrealProtocol.SET_DISPLAY, mode)
                    if (XrealProtocol.acknowledged(reply)) requested = true
                    else if (!XrealProtocol.busy(reply)) commandFailed(XrealProtocol.SET_DISPLAY, reply)
                }
                false
            }
        }
    }
    private fun awaitDisplay(matches: (Int) -> Boolean): Int {
        val deadline = System.nanoTime() + 8_000_000_000L
        repeat(40) {
            val reply = exchange(XrealProtocol.GET_DISPLAY)
            val value = XrealProtocol.numeric(reply)
            if (value != null && matches(value)) return value
            if (value == null && !XrealProtocol.acknowledged(reply))
                throw IOException("Invalid XREAL display response: ${hex(reply)}")
            if (System.nanoTime() > deadline) throw IOException("XREAL display transition timed out")
            Thread.sleep(settleDelayMs)
        }
        throw IOException("XREAL display transition did not complete")
    }
    fun set(command: Int, value: Int) {
        val reply = exchange(command, value)
        if (!XrealProtocol.acknowledged(reply)) commandFailed(command, reply)
    }
    private fun commandFailed(command: Int, reply: ByteArray): Nothing =
        throw IOException("XREAL command %04x was not acknowledged: %s".format(command, hex(reply)))

    private fun hex(reply: ByteArray) = reply.take(32).joinToString("") { "%02x".format(it) }
}
