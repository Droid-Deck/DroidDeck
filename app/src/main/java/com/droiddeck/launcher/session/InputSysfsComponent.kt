package com.droiddeck.launcher.session

import android.os.FileObserver
import android.os.SystemClock
import android.system.Os
import android.util.Log
import com.droiddeck.launcher.core.FileUtils
import com.droiddeck.launcher.core.SessionPart
import com.droiddeck.launcher.input.FakeInputWriter
import java.io.File

class InputSysfsComponent(
    private val nodes: File,
    base: File,
    private val udevData: File,
    private val deckPad: Boolean,
    private val steamVirtual: Boolean,
) : SessionPart() {
    private val devices = File(base, "devices")
    private val classDir = File(base, "class")
    private val shown = HashSet<Int>()
    private var observer: FileObserver? = null
    private var running = false

    fun prepare(): Boolean = try {
        FileUtils.clear(devices)
        FileUtils.clear(classDir)
        devices.mkdirs()
        classDir.mkdirs()
        udevData.mkdirs()
        udevData.listFiles()?.forEach { if (isPadRecord(it.name)) it.delete() }
        devices.isDirectory && classDir.isDirectory
    } catch (e: Exception) {
        Log.w(TAG, "pads: could not prepare the input sysfs: $e")
        false
    }

    fun binds() = listOf(devices.path + ":" + GUEST_DEVICES, classDir.path + ":" + GUEST_CLASS)

    override fun start() {
        synchronized(this) { running = true }
        active = this
        @Suppress("DEPRECATION")
        observer = object : FileObserver(nodes.path, CREATE or DELETE or MOVED_TO or MOVED_FROM or CLOSE_WRITE) {
            override fun onEvent(event: Int, path: String?) {
                if (path != null && NODE_FILE.matches(path)) sync()
            }
        }.also { it.startWatching() }
        sync()
    }

    override fun stop() {
        observer?.stopWatching()
        observer = null
        if (active === this) active = null
        synchronized(this) {
            running = false
            for (node in shown.toList()) hide(node)
        }
    }

    @Synchronized
    fun sync() {
        if (!running) return
        val wanted = (0 until NODE_LIMIT).filter { node ->
            File(nodes, "event$node").exists() && if (node >= FIRST_VIRTUAL) File(nodes, ".uinput-event$node.uevent").isFile
            else !ringWithdrawn(deckPad, padType)
        }.toSet()
        for (node in shown - wanted) hide(node)
        for (node in wanted - shown) show(node)
    }

    private fun show(node: Int) {
        val pad = (if (node >= FIRST_VIRTUAL) runCatching { File(nodes, ".uinput-event$node.uevent").readText() }.getOrNull()?.let(::virtualPad)
        else ringPad(node, steamVirtual)) ?: return
        try {
            val input = File(devices, "input$node")
            val event = File(input, "event$node")
            event.mkdirs()
            File(input, "uevent").writeText(pad.uevent())
            File(input, "name").writeText(pad.name + "\n")
            File(input, "phys").writeText(pad.phys + "\n")
            File(input, "uniq").writeText(pad.uniq + "\n")
            File(input, "properties").writeText("0\n")
            File(input, "id").mkdirs()
            File(input, "id/bustype").writeText("%04x\n".format(pad.bus))
            File(input, "id/vendor").writeText("%04x\n".format(pad.vendor))
            File(input, "id/product").writeText("%04x\n".format(pad.product))
            File(input, "id/version").writeText("%04x\n".format(pad.version))
            File(input, "capabilities").mkdirs()
            for (name in CAPABILITIES) File(input, "capabilities/$name").writeText("0\n")
            link(File(input, "subsystem"), GUEST_CLASS)
            File(event, "uevent").writeText("MAJOR=$MAJOR\nMINOR=${MINOR_BASE + node}\nDEVNAME=input/event$node\n")
            File(event, "dev").writeText("$MAJOR:${MINOR_BASE + node}\n")
            link(File(event, "subsystem"), GUEST_CLASS)
            link(File(event, "device"), "$GUEST_DEVICES/input$node")
            val record = udevRecord(SystemClock.elapsedRealtimeNanos() / 1000)
            File(udevData, "+input:input$node").writeText(record)
            File(udevData, "c$MAJOR:${MINOR_BASE + node}").writeText(record)
            link(File(classDir, "input$node"), "$GUEST_DEVICES/input$node")
            link(File(classDir, "event$node"), "$GUEST_DEVICES/input$node/event$node")
            shown.add(node)
            Log.i(TAG, "pads: /dev/input/event$node listed for udev as \"${pad.name}\" (%04x:%04x)".format(pad.vendor, pad.product))
        } catch (e: Exception) {
            Log.w(TAG, "pads: could not list /dev/input/event$node for udev: $e")
            hide(node)
        }
    }

    private fun hide(node: Int) {
        File(classDir, "event$node").delete()
        File(classDir, "input$node").delete()
        File(udevData, "c$MAJOR:${MINOR_BASE + node}").delete()
        File(udevData, "+input:input$node").delete()
        File(devices, "input$node").let { FileUtils.clear(it); it.delete() }
        if (shown.remove(node)) Log.i(TAG, "pads: /dev/input/event$node no longer listed for udev")
    }

    private fun link(file: File, target: String) {
        file.delete()
        Os.symlink(target, file.path)
    }

    data class Pad(
        val bus: Int,
        val vendor: Int,
        val product: Int,
        val version: Int,
        val name: String,
        val phys: String,
        val uniq: String,
    ) {
        fun uevent(): String =
            "PRODUCT=%x/%x/%x/%x\nNAME=\"$name\"\nPHYS=\"$phys\"\nUNIQ=\"$uniq\"\nPROP=0\n".format(bus, vendor, product, version)
    }

    companion object {
        private const val TAG = "SessionService"
        const val GUEST_DEVICES = "/sys/devices/virtual/input"
        const val GUEST_CLASS = "/sys/class/input"
        private const val MAJOR = 13
        private const val MINOR_BASE = 64
        private const val FIRST_VIRTUAL = 16
        private const val NODE_LIMIT = 64
        private val NODE_FILE = Regex("(\\.uinput-)?event\\d+(\\.uevent)?")
        private val CAPABILITIES = listOf("ev", "key", "rel", "abs", "msc", "sw", "led", "snd", "ff")

        @Volatile private var active: InputSysfsComponent? = null
        @Volatile private var padType = -1

        fun padTypeChanged(type: Int) {
            padType = type
            active?.let { Thread(it::sync, "input-sysfs").start() }
        }

        internal fun ringWithdrawn(deckPad: Boolean, type: Int) = deckPad && type != FakeInputWriter.PAD_TYPE_XBOX

        internal fun isPadRecord(name: String) =
            Regex("c$MAJOR:(\\d+)").matchEntire(name)?.groupValues?.get(1)?.toInt()?.let { it in MINOR_BASE until MINOR_BASE + NODE_LIMIT } == true ||
                Regex("\\+input:input(\\d+)").matchEntire(name)?.groupValues?.get(1)?.toInt()?.let { it < NODE_LIMIT } == true

        internal fun udevRecord(initializedUs: Long) =
            "I:$initializedUs\nE:ID_INPUT=1\nE:ID_INPUT_JOYSTICK=1\nE:ID_SERIAL=noserial\nG:seat\nG:uaccess\nQ:seat\nQ:uaccess\nV:1\n"

        internal fun ringPad(node: Int, steamVirtual: Boolean) = if (steamVirtual) {
            Pad(3, 0x28de, 0x11ff, 0x110, "Microsoft X-Box 360 pad $node", "usb-fakeinput/input$node", "0000000000%02d".format(node))
        } else {
            Pad(3, 0x045e, 0x028e, 0x110, "Xbox 360 Controller ($node)", "usb-fakeinput/input$node", "0000000000%02d".format(node))
        }

        internal fun virtualPad(uevent: String): Pad? {
            val values = uevent.lines().mapNotNull { line ->
                line.indexOf('=').takeIf { it > 0 }?.let { line.substring(0, it) to line.substring(it + 1).trim('"') }
            }.toMap()
            val id = values["PRODUCT"]?.split('/')?.mapNotNull { it.toIntOrNull(16) }?.takeIf { it.size == 4 } ?: return null
            return Pad(id[0], id[1], id[2], id[3], values["NAME"] ?: return null, values["PHYS"].orEmpty(), values["UNIQ"].orEmpty())
        }
    }
}
