package com.droiddeck.launcher.input

import android.content.Context
import android.os.Bundle
import android.system.Os
import android.util.Log
import com.droiddeck.launcher.runtime.LinuxRuntime
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * SDL3 bridge "Steam Native Hook-up", DroidDeck side.
 *
 * The bridge hands a USB controller over raw: its real identity and every HID report. Here each one becomes
 * /dev/hidraw24..27 -- sysfs description written like the kernel would (real vendor/product id, name, serial,
 * interface, report descriptor) and the reports moved through a file shared with fakeinput_steam.cpp
 * (FAKE_HOOKUP_SHM). Steam's own driver then runs on the real controller, like on a PC.
 *
 * Layout must match the HookupHeader / HookupSlot structs in fakeinput_steam.cpp.
 */
object HookupBridge {
    private const val TAG = "SessionService"
    const val FILE = ".hookup"
    private const val MAGIC = 0x4b4f4f48
    const val SLOTS = 4
    private const val IN_FIFO = 256
    private const val OUT_FIFO = 64
    private const val REPORT_MAX = 128
    private const val REPORT_SIZE = 144
    private const val DESC_MAX = 4096
    private const val HEADER_SIZE = 4096
    private const val SLOT_SIZE = 53248
    private const val SIZE = HEADER_SIZE + SLOTS * SLOT_SIZE
    private const val MINOR_BASE = 24
    private const val MAJOR = 240

    // header
    private const val H_FF_TO_STEAM = 8
    private const val H_STEAM_MS = 16    // the Steam client's heartbeat (CLOCK_MONOTONIC ms), written by fakeinput
    private const val STEAM_GONE_MS = 5000L
    // slot
    private const val S_PRESENT = 0
    private const val S_GENERATION = 4
    private const val S_VENDOR = 8
    private const val S_PRODUCT = 10
    private const val S_VERSION = 12
    private const val S_BUS = 14
    private const val S_IFACE = 16
    private const val S_DESC_LEN = 20
    private const val S_NAME = 24
    private const val S_SERIAL = 152
    private const val S_MANUFACTURER = 216
    private const val S_PRODUCT_NAME = 280
    private const val S_IN_WRITE = 344
    private const val S_OUT_WRITE = 352
    private const val S_OUT_READ = 360
    private const val S_GET_REQUEST = 368
    private const val S_GET_REPLY = 376
    private const val S_GET_REPORT_ID = 384
    private const val S_GET_LENGTH = 388
    private const val S_GET = 392
    private const val S_DIRECT_MS = 520   // SDL3 bridge heartbeat while it serves the slot itself (fast path)
    private const val S_DESC = 1024
    private const val S_IN = 5120
    private const val S_OUT = 41984

    @Volatile private var map: MappedByteBuffer? = null
    /** The shared file; handed to the SDL3 bridge as a descriptor so it can serve slots directly (fast path). */
    @Volatile var shmFile: File? = null
        private set
    @Volatile private var logFile: File? = null

    /** Also written to the session's pad.log, next to the native side's "hookup:" lines. */
    internal fun note(line: String) {
        Log.i(TAG, line)
        val f = logFile ?: return
        try {
            val t = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())
            f.appendText("$t app: $line\n")
        } catch (_: Exception) {}
    }
    private val present = BooleanArray(SLOTS)
    private val getAsked = LongArray(SLOTS)
    @Volatile var tritonPassthrough = false
        set(value) { field = value; updateFfToSteam() }

    /** Creates the shared file; returns it for FAKE_HOOKUP_SHM. Called at session start. */
    @JvmStatic
    fun prepare(fakeInputDir: File, padLog: File? = null): File? = try {
        logFile = padLog
        val file = File(fakeInputDir, FILE)
        file.delete()
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(SIZE.toLong())
            val m = raf.channel.map(FileChannel.MapMode.READ_WRITE, 0, SIZE.toLong())
            m.order(ByteOrder.LITTLE_ENDIAN)
            m.putInt(4, 1)
            m.putInt(0, MAGIC)
            map = m
        }
        shmFile = file
        file.setReadable(true, false)
        file.setWritable(true, false)
        present.fill(false)
        note("hookup: shared file ready (${file.path}); waiting for the SDL3 bridge to hand over controllers")
        file
    } catch (e: Exception) {
        note("hookup: no shared file: $e")
        map = null
        shmFile = null
        null
    }

    private fun slotBase(slot: Int) = HEADER_SIZE + slot * SLOT_SIZE

    /** Inode of the shared file (0 if none): the bridge reports the one it mapped, so a stale mapping is noticed. */
    @JvmStatic fun shmInode(): Long = try { shmFile?.let { Os.stat(it.path).st_ino } ?: 0L } catch (_: Exception) { 0L }

    /**
     * The SDL3 bridge writes this slot's reports into the file itself and takes Steam's output / feature reads
     * from it (its heartbeat is fresh): DroidDeck must leave the slot's FIFOs alone then.
     */
    private fun bridgeServes(m: ByteBuffer, slot: Int): Boolean {
        val beat = m.getLong(slotBase(slot) + S_DIRECT_MS)
        return beat != 0L && android.os.SystemClock.uptimeMillis() - beat < 1500
    }

    /**
     * Is the Steam client running in this session? Its side of the hook-up writes a heartbeat into the shared file
     * every 0.1 s. (No hook-up file this session: treated as running, so nothing waits for it.)
     */
    @JvmStatic fun steamRunning(): Boolean {
        val m = map ?: return true
        val beat = m.getLong(H_STEAM_MS)
        return beat != 0L && android.os.SystemClock.uptimeMillis() - beat < STEAM_GONE_MS
    }

    @JvmStatic fun anyPresent(): Boolean = present.any { it }
    @JvmStatic fun isPresent(slot: Int): Boolean = slot in 0 until SLOTS && present[slot]

    /** True while Steam is handed game rumble (a real controller it drives itself is present). */
    @JvmStatic fun ffToSteam(): Boolean = map != null && (anyPresent() || tritonPassthrough)

    private fun updateFfToSteam() {
        val m = map ?: return
        synchronized(this) {
            FakeInputWriter.storeFence()
            m.putInt(H_FF_TO_STEAM, if (anyPresent() || tritonPassthrough) 1 else 0)
        }
    }

    private fun putString(m: ByteBuffer, at: Int, max: Int, s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        val n = minOf(b.size, max - 1)
        for (i in 0 until max) m.put(at + i, if (i < n) b[i] else 0)
    }

    // ---------------- bridge messages (SdlBridgeClient's worker thread) ----------------

    @Synchronized
    fun add(id: Bundle) {
        val m = map ?: run {
            note("hookup: the bridge handed over a controller but this session has no hook-up file " +
                "(DroidDeck build without it, or the session started without the deck pad)")
            return
        }
        val slot = id.getInt("slot", -1)
        if (slot !in 0 until SLOTS) { note("hookup: the bridge handed over slot $slot, which does not exist"); return }
        val base = slotBase(slot)
        val desc = id.getByteArray("desc") ?: ByteArray(0)
        if (present[slot] && (m.getShort(base + S_VENDOR).toInt() and 0xffff) == id.getInt("vid") &&
            (m.getShort(base + S_PRODUCT).toInt() and 0xffff) == id.getInt("pid") && m.getInt(base + S_IFACE) == id.getInt("iface")) {
            return   // the same controller resent after a reconnect: keep it as it is (no unplug/replug in Steam)
        }
        if (present[slot]) remove(slot)
        describeSysfs(slot, id, desc)
        m.putShort(base + S_VENDOR, id.getInt("vid").toShort())
        m.putShort(base + S_PRODUCT, id.getInt("pid").toShort())
        m.putShort(base + S_VERSION, id.getInt("version").toShort())
        m.putShort(base + S_BUS, id.getInt("bus", 3).toShort())
        m.putInt(base + S_IFACE, id.getInt("iface"))
        putString(m, base + S_NAME, 128, id.getString("name") ?: "")
        putString(m, base + S_SERIAL, 64, id.getString("serial") ?: "")
        putString(m, base + S_MANUFACTURER, 64, id.getString("manufacturer") ?: "")
        putString(m, base + S_PRODUCT_NAME, 64, id.getString("product") ?: "")
        val n = minOf(desc.size, DESC_MAX)
        for (i in 0 until n) m.put(base + S_DESC + i, desc[i])
        m.putInt(base + S_DESC_LEN, n)
        // fresh FIFOs: the readers start at the current write position, so only the counters matter
        m.putLong(base + S_OUT_READ, m.getLong(base + S_OUT_WRITE))
        getAsked[slot] = m.getLong(base + S_GET_REQUEST)
        m.putLong(base + S_GET_REPLY, getAsked[slot])
        m.putInt(base + S_GENERATION, m.getInt(base + S_GENERATION) + 1)
        FakeInputWriter.storeFence()
        m.putInt(base + S_PRESENT, 1)
        present[slot] = true
        updateFfToSteam()
        note("hookup: /dev/hidraw${MINOR_BASE + slot} = %04x:%04x \"%s\" interface %d, descriptor %d bytes (Steam Native Hook-up)"
            .format(id.getInt("vid"), id.getInt("pid"), id.getString("name"), id.getInt("iface"), desc.size))
    }

    @Synchronized
    fun input(slot: Int, data: ByteArray) {
        val m = map ?: return
        if (slot !in 0 until SLOTS || !present[slot] || data.isEmpty()) return
        if (bridgeServes(m, slot)) return   // a late message from before the fast path took over
        val base = slotBase(slot)
        val idx = m.getLong(base + S_IN_WRITE)
        val at = base + S_IN + ((idx % IN_FIFO).toInt()) * REPORT_SIZE
        m.putLong(at, 0L)
        FakeInputWriter.storeFence()
        val n = minOf(data.size, REPORT_MAX)
        m.putInt(at + 8, n)
        m.putInt(at + 12, 0)
        for (i in 0 until n) m.put(at + 16 + i, data[i])
        FakeInputWriter.storeFence()
        m.putLong(at, idx + 1)
        m.putLong(base + S_IN_WRITE, idx + 1)
    }

    @Synchronized
    fun remove(slot: Int) {
        val m = map ?: return
        if (slot !in 0 until SLOTS) return
        m.putInt(slotBase(slot) + S_PRESENT, 0)
        m.putLong(slotBase(slot) + S_DIRECT_MS, 0L)
        FakeInputWriter.storeFence()
        if (present[slot]) note("hookup: /dev/hidraw${MINOR_BASE + slot} unplugged")
        present[slot] = false
        unlinkClass(slot)
        updateFfToSteam()
    }

    @Synchronized
    fun removeAll() { for (s in 0 until SLOTS) if (present[s]) remove(s) }

    @Synchronized
    fun reply(slot: Int, request: Long, data: ByteArray?) {
        val m = map ?: return
        if (slot !in 0 until SLOTS || request != getAsked[slot]) return
        val base = slotBase(slot)
        val n = if (data == null) 0 else minOf(data.size, REPORT_MAX)
        for (i in 0 until n) m.put(base + S_GET + i, data!![i])
        m.putInt(base + S_GET_LENGTH, n)
        FakeInputWriter.storeFence()
        m.putLong(base + S_GET_REPLY, request)
    }

    /**
     * Steam -> controller. Calls [out](slot, kind, report) for each queued output report / feature write and
     * [get](slot, request, reportId, input, length) for a new feature/input read. Run every ~1 ms.
     */
    @Synchronized
    fun poll(out: (Int, Int, ByteArray) -> Unit, get: (Int, Long, Int, Boolean, Int) -> Unit) {
        val m = map ?: return
        for (slot in 0 until SLOTS) {
            if (!present[slot]) continue
            FakeInputWriter.storeFence()
            if (bridgeServes(m, slot)) continue   // the bridge takes Steam's reports from the file itself
            val base = slotBase(slot)
            var read = m.getLong(base + S_OUT_READ)
            val written = m.getLong(base + S_OUT_WRITE)
            while (read < written) {
                val at = base + S_OUT + ((read % OUT_FIFO).toInt()) * REPORT_SIZE
                if (m.getLong(at) != read + 1) break   // still being written
                val n = m.getInt(at + 8).coerceIn(0, REPORT_MAX)
                val kind = m.getInt(at + 12)
                val data = ByteArray(n)
                for (i in 0 until n) data[i] = m.get(at + 16 + i)
                read++
                if (n > 0) out(slot, kind, data)
            }
            m.putLong(base + S_OUT_READ, read)
            val request = m.getLong(base + S_GET_REQUEST)
            if (request != getAsked[slot] && request != m.getLong(base + S_GET_REPLY)) {
                getAsked[slot] = request
                val idWord = m.getInt(base + S_GET_REPORT_ID)
                get(slot, request, idWord and 0xff, (idWord and 0x100) != 0, m.getInt(base + S_GET_LENGTH))
            }
        }
    }

    // ---------------- sysfs, as Linux would describe the device ----------------

    private fun guestHid(slot: Int, id: Bundle): Triple<String, String, String> {
        val bus = 10 + slot
        val usb = "/sys/devices/droiddeck/usb$bus"
        val iface = "$usb/$bus-1:1.${maxOf(0, id.getInt("iface"))}"
        val hid = "$iface/0003:%04X:%04X.%04X".format(id.getInt("vid"), id.getInt("pid"), 0x10 + slot)
        return Triple(usb, iface, hid)
    }

    private fun describeSysfs(slot: Int, id: Bundle, desc: ByteArray) {
        val devices = com.droiddeck.launcher.session.SteamDeckPad.sysDevicesDir
        val classDir = com.droiddeck.launcher.session.SteamDeckPad.sysHidrawClassDir
        val context = com.droiddeck.launcher.session.SteamDeckPad.sysContext
        if (devices == null || classDir == null || context == null) {
            note("hookup: no session sysfs to describe /dev/hidraw${MINOR_BASE + slot} in (deck pad off?)")
            return
        }
        try {
            val (usb, iface, hid) = guestHid(slot, id)
            fun dir(guest: String) = File(devices, guest.removePrefix("/sys/devices/droiddeck/")).apply { mkdirs() }
            fun write(guest: String, name: String, text: String) = File(dir(guest), name).writeText(text)
            fun link(file: File, target: String) {
                file.parentFile?.mkdirs()
                file.delete()
                Os.symlink(target, file.path)
            }
            deleteTree(File(devices, usb.removePrefix("/sys/devices/droiddeck/")))   // the previous description
            val vid = id.getInt("vid")
            val pid = id.getInt("pid")
            val ver = id.getInt("version")
            val vidHex = "%04x".format(vid)
            val pidHex = "%04x".format(pid)
            val product = "%x/%x/%x".format(vid, pid, ver)
            val node = "hidraw${MINOR_BASE + slot}"
            val minor = MINOR_BASE + slot
            val busNum = "%03d".format(10 + slot)
            write(usb, "uevent", "DEVTYPE=usb_device\nPRODUCT=$product\nTYPE=0/0/0\nBUSNUM=$busNum\nDEVNUM=002\n")
            write(usb, "idVendor", "$vidHex\n")
            write(usb, "idProduct", "$pidHex\n")
            write(usb, "bcdDevice", "%04x\n".format(ver))
            write(usb, "manufacturer", "${id.getString("manufacturer") ?: ""}\n")
            write(usb, "product", "${id.getString("product") ?: ""}\n")
            write(usb, "serial", "${id.getString("serial") ?: ""}\n")
            write(usb, "busnum", "${10 + slot}\n")
            write(usb, "devnum", "2\n")
            link(File(dir(usb), "subsystem"), "/sys/bus/usb")
            val cls = id.getInt("ifclass", 3)
            write(iface, "uevent", "DEVTYPE=usb_interface\nPRODUCT=$product\nINTERFACE=$cls/${id.getInt("ifsub")}/${id.getInt("ifproto")}\n")
            write(iface, "bInterfaceNumber", "%02x\n".format(maxOf(0, id.getInt("iface"))))
            write(iface, "bInterfaceClass", "%02x\n".format(cls))
            write(iface, "bInterfaceSubClass", "%02x\n".format(id.getInt("ifsub")))
            write(iface, "bInterfaceProtocol", "%02x\n".format(id.getInt("ifproto")))
            link(File(dir(iface), "subsystem"), "/sys/bus/usb")
            val name = id.getString("name") ?: ""
            val serial = id.getString("serial") ?: ""
            write(hid, "uevent", "DRIVER=hid-generic\nHID_ID=0003:%08X:%08X\nHID_NAME=$name\n".format(vid, pid) +
                "HID_PHYS=usb-droiddeck-${10 + slot}/input${maxOf(0, id.getInt("iface"))}\nHID_UNIQ=$serial\n" +
                "MODALIAS=hid:b0003g0001v%08Xp%08X\n".format(vid, pid))
            File(dir(hid), "report_descriptor").writeBytes(desc)
            link(File(dir(hid), "subsystem"), "/sys/bus/hid")
            val hidraw = "$hid/hidraw/$node"
            write(hidraw, "uevent", "MAJOR=$MAJOR\nMINOR=$minor\nDEVNAME=$node\n")
            write(hidraw, "dev", "$MAJOR:$minor\n")
            link(File(dir(hidraw), "subsystem"), "/sys/class/hidraw")
            link(File(dir(hidraw), "device"), hid)
            link(File(classDir, node), hidraw)
            link(File(LinuxRuntime.liveDir(context), "drm/sys/$MAJOR:$minor"), hidraw)
            val udevData = File(LinuxRuntime.rootDir(context), "run/udev/data").apply { mkdirs() }
            File(udevData, "c$MAJOR:$minor").writeText("I:1\nE:ID_INPUT=1\nE:ID_INPUT_JOYSTICK=1\n")
        } catch (e: Exception) {
            note("hookup: could not describe /dev/hidraw${MINOR_BASE + slot}: $e")
        }
    }

    /**
     * Deletes a folder without following symbolic links. (File.deleteRecursively() follows them: the description
     * links "subsystem" to the phone's real /sys/bus/..., and walking into the real /sys -- full of links back into
     * itself -- never ends, which froze DroidDeck's bridge thread on the second hand-over of a controller.)
     */
    private fun deleteTree(f: File) {
        val st = try { Os.lstat(f.path) } catch (_: Exception) { return }   // gone already
        if (android.system.OsConstants.S_ISDIR(st.st_mode)) f.list()?.forEach { deleteTree(File(f, it)) }
        f.delete()
    }

    private fun unlinkClass(slot: Int) {
        val classDir = com.droiddeck.launcher.session.SteamDeckPad.sysHidrawClassDir ?: return
        try { File(classDir, "hidraw${MINOR_BASE + slot}").delete() } catch (_: Exception) {}
    }

    /** True when DroidDeck should set the hook-up file up this session (the bridge app is installed). */
    @JvmStatic fun wanted(context: Context): Boolean =
        com.droiddeck.launcher.session.SessionPrefs.sdl3Controllers(context) && SdlBridgeClient.isBridgeInstalled(context)
}
