package com.droiddeck.launcher.session.razer

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import com.droiddeck.launcher.session.RumbleComponent
import com.droiddeck.launcher.session.RumbleProvider
import java.util.Locale

/**
 * Rumble for Razer Kishi pads, whose motors Android cannot drive - an optional [RumbleProvider].
 *
 * In XInput mode (1532:0037) the kernel's xpad driver owns interface 0 for input; in the pad's own
 * HID mode (e.g. 1532:0724 for the Kishi V3 Pro) usbhid owns interface 0 for the gamepad and
 * interface 1 for its touch / consumer-key collections. Neither exposes the motors as a
 * force-feedback device, so Android has no vibrator for the pad. Razer's own app drives them
 * through the vendor interface "Razer Protocol2.5" (interface 3 in every Kishi layout seen):
 * 90-byte feature reports over control transfers, command SetInterhapticsStreamFrame (class 0x16,
 * id 0x0E) carrying a "legacy special mode" frame with the low- and high-frequency motor levels.
 *
 * This claims "Razer Protocol2.5" and sends that frame. The interface has only an OUT endpoint, so
 * no kernel driver ever binds it and claiming it detaches nothing; any interface with an IN
 * endpoint (the gamepad, touch and key interfaces Android reads input from) is never claimed.
 * When Razer Cortex runs it holds the interface; it is then taken over (input is untouched;
 * Cortex's own commands on that interface fail until it reconnects) and that is logged.
 *
 * USB access is asked for with UsbManager.requestPermission only when a session starts or the
 * Settings test runs - DroidDeck is never a USB_DEVICE_ATTACHED handler, so a companion app stays
 * the pad's default app and can still switch its mode. The interface is held only while used:
 * released (releaseInterface + close) right after the Settings test, at session end, on USB
 * detach, and when the app goes to the background with no session running.
 *
 * Everything is logged with a "rumble: usb" prefix.
 */
object RazerKishiRumble : RumbleProvider {
    private const val TAG = "SessionService"
    private const val ACTION_PERMISSION = "com.droiddeck.launcher.USB_RUMBLE_PERMISSION"
    override val name = "Razer Kishi over USB"
    private const val LOG_INTERVAL_MS = 5000L
    const val RETRY_MS = 2000L
    const val PROTOCOL25 = "Razer Protocol2.5"

    /** A pad driven this way. [cortexInterface]: the interface Cortex's per-pid config names. */
    data class KnownPad(val vendor: Int, val product: Int, val label: String, val cortexInterface: Int = 3)

    /**
     * Razer pads with the Protocol2.5 + HD Haptics layout, from Cortex's product catalog
     * (RazerProducts) and its USB config (ControllerKt). XInput (0x0037) and XInput+ (0x0719) ids
     * are shared across the Kishi line; each model has its own HID-mode id. Cortex configures the
     * listed ids with Protocol2.5 on interface 3 and picks it by name on the others (0x0724,
     * 0x0727, 0x0721 use its by-name default config); we do the same, by name first.
     */
    val KNOWN = listOf(
        KnownPad(0x1532, 0x0037, "Razer Kishi (XInput mode)"),
        KnownPad(0x1532, 0x0719, "Razer Kishi (XInput+ mode)"),
        KnownPad(0x1532, 0x0724, "Razer Kishi V3 Pro"),
        KnownPad(0x1532, 0x0727, "Razer Kishi V3 Pro XL"),
        KnownPad(0x1532, 0x0721, "Razer Kishi V3"),
        KnownPad(0x1532, 0x071A, "Razer Kishi Ultra"),
        KnownPad(0x1532, 0x0717, "Razer Kishi V2 Pro"),
        KnownPad(0x1532, 0x0718, "Razer Kishi V2 Pro (XInput+ mode)"),
    )

    fun known(vendor: Int, product: Int): KnownPad? = KNOWN.firstOrNull { it.vendor == vendor && it.product == product }

    private var app: Context? = null
    private var link: Link? = null
    /** Device names permission was asked for while attached, so a refusal is not asked again and again. */
    private val asked = HashSet<String>()
    /** Devices whose interface list was logged. */
    private val described = HashSet<String>()
    private var receiverRegistered = false
    private val lastLog = HashMap<String, Long>()
    private var lastOpenFailAt = 0L
    private var sessionActive = false
    private var retryThread: HandlerThread? = null
    private var retryHandler: Handler? = null
    /** Bumped when a device is attached, detached, permission arrives, or a path opens or closes. */
    @Volatile override var generation = 0
        private set

    private fun bump() { generation++ }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            @Suppress("DEPRECATION")
            val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
            when (intent.action) {
                ACTION_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    Log.i(TAG, "rumble: usb permission ${if (granted) "granted" else "denied"} for ${describe(device)}")
                    if (granted) synchronized(this@RazerKishiRumble) { lastOpenFailAt = 0L }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    Log.i(TAG, "rumble: usb attached ${describe(device)}")
                    synchronized(this@RazerKishiRumble) { device?.let { asked.remove(it.deviceName); described.remove(it.deviceName) }; lastOpenFailAt = 0L }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    Log.i(TAG, "rumble: usb detached ${describe(device)}")
                    synchronized(this@RazerKishiRumble) {
                        device?.let { asked.remove(it.deviceName); described.remove(it.deviceName) }
                        if (device != null && link?.device?.deviceName == device.deviceName) { link?.close("usb detached"); link = null }
                    }
                }
            }
            bump()
        }
    }

    /** Lets go of the pad when the app's UI goes to the background and no session is running. */
    private val backgroundWatcher = object : ComponentCallbacks2 {
        override fun onTrimMemory(level: Int) {
            // UI_HIDDEN and every level above it: no activity of ours is visible any more.
            if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) releaseIfIdle("app in background, no session")
        }
        override fun onConfigurationChanged(newConfig: Configuration) {}
        @Deprecated("Deprecated in Java") override fun onLowMemory() {}
    }

    @Synchronized private fun init(ctx: Context) {
        val appCtx = ctx.applicationContext ?: ctx
        app = appCtx
        if (receiverRegistered) return
        try { appCtx.registerComponentCallbacks(backgroundWatcher) } catch (e: Exception) { Log.w(TAG, "rumble: usb background watcher not registered: $e") }
        try {
            val filter = IntentFilter(ACTION_PERMISSION).apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            }
            // targetSdk 28: no exported flag is needed (or honoured); the permission broadcast is
            // sent with an explicit package, see requestPermission.
            appCtx.registerReceiver(receiver, filter)
            receiverRegistered = true
        } catch (e: Exception) {
            Log.w(TAG, "rumble: usb receiver not registered: $e")
        }
    }

    /**
     * A session started: look for a known pad (asking for USB access once), and keep retrying
     * every [RETRY_MS] while no path is open.
     */
    @Synchronized override fun sessionStart(context: Context) {
        val ctx = context
        init(ctx)
        sessionActive = true
        if (retryThread == null) {
            val t = HandlerThread("rumble-usb-retry").apply { start() }
            retryThread = t
            retryHandler = Handler(t.looper).also { h -> h.post(object : Runnable {
                override fun run() {
                    val c = app ?: return
                    if (!synchronized(this@RazerKishiRumble) { sessionActive }) return
                    try { retry(c) } catch (e: Exception) { Log.w(TAG, "rumble: usb retry: $e") }
                    h.postDelayed(this, RETRY_MS)
                }
            }) }
        }
    }

    /** One retry tick: a known pad attached, no path open yet, so try (this also asks permission once). */
    private fun retry(ctx: Context) {
        val before = synchronized(this) { link?.isOpen == true }
        if (before || !attached(ctx)) return
        if (motors(ctx) != null) {
            Log.i(TAG, "rumble: usb retry: a rumble path is open now")
            bump()
        }
    }

    /**
     * The motors of a known pad: our USB link (opened if needed). Null when none is attached, permission is not (yet) granted (asked once per
     * attach), or the interface is busy and the retry backoff has not run out. Cheap when nothing
     * is attached: one look at the USB device list.
     */
    @Synchronized override fun motors(context: Context): RumbleComponent.Motors? {
        val ctx = context
        init(ctx)
        val usb = ctx.getSystemService(UsbManager::class.java) ?: return null
        val found = try { usb.deviceList.values.mapNotNull { d -> known(d.vendorId, d.productId)?.let { d to it } } } catch (e: Exception) {
            logLimited("list", "rumble: usb device list failed: $e"); emptyList()
        }
        if (found.isEmpty()) {
            if (link != null) { link?.close("pad gone"); link = null }
            logLimited("none", "rumble: usb no known pad attached (attached: ${attachedSummary(usb)}; known: ${KNOWN.joinToString(" ") { vidPid(it.vendor, it.product) }})")
            return null
        }
        for ((device, pad) in found) {
            if (described.add(device.deviceName)) Log.i(TAG, "rumble: usb found ${describe(device)} (${pad.label}); interfaces: ${interfaceList(device)}")
        }
        link?.let { if (it.isOpen) return it.motors else link = null }
        for ((d, p) in found) {
            if (!usb.hasPermission(d)) {
                if (asked.add(d.deviceName)) {
                    Log.i(TAG, "rumble: usb ${describe(d)} (${p.label}) found, no permission yet -> asking")
                    requestPermission(ctx, usb, d)
                } else {
                    logLimited("perm:${d.deviceName}", "rumble: usb ${describe(d)} found, still no permission (asked; grant it in the dialog or re-plug)")
                }
                continue
            }
            val now = SystemClock.elapsedRealtime()
            if (lastOpenFailAt != 0L && now - lastOpenFailAt < RETRY_MS) return null
            // 1. A plain claim: works whenever no other app holds the interface.
            var result = Link.open(usb, d, p, steal = false)
            // 2. Held by another app (Razer Cortex): take the interface over, logged.
            if (result.link == null && result.held) result = Link.open(usb, d, p, steal = true)
            val opened = result.link
            if (opened == null) { lastOpenFailAt = now; continue }
            lastOpenFailAt = 0L
            link = opened
            bump()
            return opened.motors
        }
        return null
    }

    /** Whether a known USB pad is attached (whatever the permission). */
    fun attached(ctx: Context): Boolean = attachedPad(ctx) != null

    fun attachedPad(ctx: Context): UsbDevice? = try {
        ctx.getSystemService(UsbManager::class.java)?.deviceList?.values?.firstOrNull { known(it.vendorId, it.productId) != null }
    } catch (e: Exception) { null }

    /** For the Settings test: the known pad attached (and whether access is granted), or null. */
    override fun describe(context: Context): String? {
        val pad = attachedPad(context) ?: return null
        val granted = try { context.getSystemService(UsbManager::class.java)?.hasPermission(pad) == true } catch (e: Exception) { false }
        return "${pad.productName ?: known(pad.vendorId, pad.productId)?.label} ${vidPid(pad.vendorId, pad.productId)}" +
            if (granted) "" else " (allow USB access in the dialog, then tap Test again)"
    }

    /** Ends any rumble and gives the interface back (session end, detach). */
    @Synchronized override fun release(why: String) {
        sessionActive = false
        retryThread?.quitSafely(); retryThread = null; retryHandler = null
        link?.close(why)
        link = null
        lastOpenFailAt = 0L
    }

    /** After the Settings test, or with the app in the background: let go unless a session is using the pad. */
    @Synchronized override fun releaseIfIdle(why: String) { if (!sessionActive) release(why) }

    /** Whether our USB link currently holds the pad's interface (tests, logs). */
    val holdsInterface get() = link?.isOpen == true

    internal fun onLinkFailed(l: Link) {
        synchronized(this) {
            if (link === l) { link = null; lastOpenFailAt = SystemClock.elapsedRealtime() }
        }
        bump()
    }

    private fun requestPermission(ctx: Context, usb: UsbManager, device: UsbDevice) {
        try {
            val intent = Intent(ACTION_PERMISSION).setPackage(ctx.packageName)
            // Mutable: the USB service adds the device and the grant to it.
            val flags = if (android.os.Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val pending = PendingIntent.getBroadcast(ctx, 0x5242, intent, flags or PendingIntent.FLAG_UPDATE_CURRENT)
            usb.requestPermission(device, pending)
        } catch (e: Exception) {
            Log.w(TAG, "rumble: usb permission request failed: $e")
        }
    }

    @Synchronized private fun logLimited(key: String, message: String) {
        val now = SystemClock.elapsedRealtime()
        val last = lastLog[key]
        if (last != null && now - last < LOG_INTERVAL_MS) return
        lastLog[key] = now
        Log.i(TAG, message)
    }

    fun vidPid(v: Int, p: Int) = String.format(Locale.ROOT, "%04x:%04x", v, p)
    fun describe(d: UsbDevice?) = if (d == null) "(no device)"
        else "\"${d.productName ?: "?"}\" ${vidPid(d.vendorId, d.productId)} at ${d.deviceName}"
    private fun attachedSummary(usb: UsbManager) = try {
        usb.deviceList.values.joinToString(" ") { vidPid(it.vendorId, it.productId) }.ifEmpty { "none" }
    } catch (e: Exception) { "?" }

    // --- Interface choice ---------------------------------------------------------------------

    private fun UsbInterface.endpoints() = (0 until endpointCount).map { getEndpoint(it) }
    /** An interface with an IN endpoint is one a kernel driver binds and Android reads input from. */
    private fun UsbInterface.hasIn() = endpoints().any { it.direction == UsbConstants.USB_DIR_IN }

    fun interfaceList(device: UsbDevice): String = (0 until device.interfaceCount).joinToString("; ") { i ->
        val f = device.getInterface(i)
        val eps = f.endpoints().joinToString(",") { e ->
            String.format(Locale.ROOT, "%s 0x%02x %s %d", if (e.direction == UsbConstants.USB_DIR_IN) "IN" else "OUT", e.address,
                when (e.type) { UsbConstants.USB_ENDPOINT_XFER_INT -> "int"; UsbConstants.USB_ENDPOINT_XFER_BULK -> "bulk"; UsbConstants.USB_ENDPOINT_XFER_CONTROL -> "ctrl"; else -> "iso" }, e.maxPacketSize)
        }
        val role = when {
            f.interfaceClass == 255 && f.interfaceSubclass == 93 -> " [XInput gamepad - input, never claimed]"
            f.hasIn() -> " [has IN endpoint - input/driver, never claimed]"
            f.info().claimable -> " [output-only HID]"
            else -> ""
        }
        "#${f.id} ${f.interfaceClass}/${f.interfaceSubclass}/${f.interfaceProtocol} \"${f.name ?: ""}\" [$eps]$role"
    }

    /** What the interface choice looks at, so it can be tested without a device. */
    data class Iface(val id: Int, val cls: Int, val sub: Int, val proto: Int, val name: String?, val inEndpoints: Int, val outEndpoints: Int) {
        /** Safe to claim: HID class, not interface 0, and nothing to read from it (output-only). */
        val claimable get() = cls == UsbConstants.USB_CLASS_HID && id != 0 && outEndpoints > 0 && inEndpoints == 0
    }

    /**
     * The Protocol2.5 interface: by name as Cortex's default config does; else the interface
     * number from Cortex's per-pid config; else the only output-only HID interface. Always
     * output-only, so never one that input comes from. Returns the position in [all] and why.
     */
    fun pick(all: List<Iface>, pad: KnownPad): Pair<Int, String>? {
        all.indexOfFirst { it.name?.startsWith(PROTOCOL25) == true }.takeIf { it >= 0 }?.let { i ->
            if (all[i].claimable) return i to "named \"${all[i].name}\""
            Log.w(TAG, "rumble: usb interface #${all[i].id} is named \"${all[i].name}\" but is not output-only HID; not claiming it")
        }
        all.indexOfFirst { it.id == pad.cortexInterface && it.claimable }.takeIf { it >= 0 }?.let {
            return it to "number ${pad.cortexInterface} from Razer Cortex's config, output-only HID"
        }
        val outOnly = all.indices.filter { all[it].claimable }
        if (outOnly.size == 1) return outOnly[0] to "the only output-only HID interface"
        return null
    }

    private fun UsbInterface.info() = Iface(id, interfaceClass, interfaceSubclass, interfaceProtocol, name,
        endpoints().count { it.direction == UsbConstants.USB_DIR_IN }, endpoints().count { it.direction != UsbConstants.USB_DIR_IN })

    fun chooseInterface(device: UsbDevice, pad: KnownPad): Pair<UsbInterface, String>? {
        val all = (0 until device.interfaceCount).map { device.getInterface(it) }
        return pick(all.map { it.info() }, pad)?.let { (i, why) -> all[i] to why }
    }

    // --- Our own USB link ---------------------------------------------------------------------

    /** An open, claimed vendor interface and the worker thread that talks to it. */
    internal class Link(
        val device: UsbDevice,
        private val connection: UsbDeviceConnection,
        private val intf: UsbInterface,
        private val txn: Int,
        label: String,
        tookOver: Boolean = false,
    ) {
        @Volatile var isOpen = true
            private set
        private val thread = HandlerThread("rumble-usb").apply { start() }
        private val handler = Handler(thread.looper)
        private var current = 0 to 0
        private var sent = 0
        private var failed = 0
        private var failedInARow = 0
        private val stop = Runnable { write(0, 0) }

        val motors = object : RumbleComponent.Motors {
            override val name = "USB \"${device.productName ?: label}\" ${vidPid(device.vendorId, device.productId)} interface ${intf.id}" +
                if (tookOver) " (taken over from another app)" else ""
            override fun play(strong: Int, weak: Int, ms: Long) {
                if (!isOpen) throw IllegalStateException("closed")
                val low = RazerV25.level(strong)
                val high = RazerV25.level(weak)
                handler.post {
                    handler.removeCallbacks(stop)
                    write(low, high)
                    if (low != 0 || high != 0) handler.postDelayed(stop, ms)
                }
            }
            override fun cancel() {
                if (!isOpen) return
                handler.post { handler.removeCallbacks(stop); write(0, 0) }
            }
        }

        /** Runs on the worker. Repeats of the same strengths are not resent; the stop timer is. */
        private fun write(low: Int, high: Int) {
            if (!isOpen) return
            if (current == (low to high) && sent > 0) return
            val report = RazerV25.report(txn, RazerV25.CLASS_INTERHAPTICS, RazerV25.CMD_SET_STREAM_FRAME, RazerV25.legacyRumbleArgs(low, high))
            val r = setFeature(connection, intf.id, report)
            if (r == report.size) {
                current = low to high
                sent++
                failedInARow = 0
                if (sent <= 3) Log.i(TAG, "rumble: usb sent stream frame low $low high $high -> $r bytes" +
                    if (sent <= 2) " (status ${statusName(getFeatureStatus(connection, intf.id))})" else "")
            } else {
                failed++
                failedInARow++
                if (failed <= 5 || failed % 100 == 0) Log.w(TAG, "rumble: usb stream frame low $low high $high failed: controlTransfer -> $r (failures $failed)")
                if (failedInARow >= 3) {
                    // Most likely another app (Cortex) took the interface back. Let go; the
                    // lookup tries again after the retry delay.
                    Log.w(TAG, "rumble: usb interface ${intf.id} stopped answering ($failedInARow failures in a row) - taken by another app? releasing, retrying in ${RETRY_MS} ms")
                    closeNow("transfers failing")
                    onLinkFailed(this)
                }
            }
        }

        fun close(why: String) {
            if (!isOpen) return
            handler.post { closeNow(why) }
        }

        private fun closeNow(why: String) {
            if (!isOpen) return
            handler.removeCallbacks(stop)
            if (current != (0 to 0) && failedInARow == 0) write(0, 0)
            isOpen = false
            try { connection.releaseInterface(intf) } catch (e: Exception) { }
            try { connection.close() } catch (e: Exception) { }
            Log.i(TAG, "rumble: usb released interface ${intf.id} and closed ${vidPid(device.vendorId, device.productId)} ($why) after $sent frames, $failed failures")
            thread.quitSafely()
        }

        companion object {
            /** [link] when opened; [held] when the interface is claimed by another app. */
            class Result(val link: Link?, val held: Boolean = false)

            fun open(usb: UsbManager, device: UsbDevice, pad: KnownPad, steal: Boolean): Result {
                val chosen = chooseInterface(device, pad)
                if (chosen == null) {
                    Log.w(TAG, "rumble: usb ${describe(device)}: no output-only \"$PROTOCOL25\" interface to use; interfaces: ${interfaceList(device)}")
                    return Result(null)
                }
                val (intf, why) = chosen
                val connection = try { usb.openDevice(device) } catch (e: Exception) { Log.w(TAG, "rumble: usb open failed: $e"); null }
                if (connection == null) {
                    Log.w(TAG, "rumble: usb openDevice returned null for ${describe(device)}")
                    return Result(null)
                }
                // The interface is output-only, so no kernel driver is bound to it: a plain claim
                // succeeds unless another app (Razer Cortex) holds it. Only with [steal] is it
                // taken from that app (force: usbfs drops the other app's claim on this one
                // interface; input interfaces are not touched).
                var how = "claimed"
                var tookOver = false
                if (!connection.claimInterface(intf, false)) {
                    if (!steal) {
                        Log.i(TAG, "rumble: usb interface ${intf.id} \"${intf.name ?: ""}\" is held by another app (Razer Cortex?)")
                        connection.close()
                        return Result(null, held = true)
                    }
                    if (!connection.claimInterface(intf, true)) {
                        Log.w(TAG, "rumble: usb claim of interface ${intf.id} \"${intf.name ?: ""}\" failed even with force; retrying every ${RETRY_MS} ms. Input is unaffected.")
                        connection.close()
                        return Result(null, held = true)
                    }
                    how = "taken over from the app holding it (Razer Cortex)"
                    tookOver = true
                    Log.w(TAG, "rumble: usb interface ${intf.id} was held by another app (Razer Cortex); took it over. Input is unaffected; Cortex's own commands on this interface fail until it reconnects.")
                }
                val txn = probeTransactionId(connection, intf.id)
                Log.i(TAG, String.format(Locale.ROOT,
                    "rumble: usb opened %s, interface %d \"%s\" %s (%s); feature reports via SET_REPORT, transaction id 0x%02x; input interfaces untouched",
                    describe(device), intf.id, intf.name ?: "", how, why, txn))
                return Result(Link(device, connection, intf, txn, pad.label, tookOver))
            }

            /**
             * Razer's pads accept one of a few transaction ids. Sends a harmless "motors off"
             * frame with each until the pad's reply says it was handled; 0x1F if none says so.
             */
            private fun probeTransactionId(connection: UsbDeviceConnection, iface: Int): Int {
                val tried = StringBuilder()
                for (txn in RazerV25.TRANSACTION_IDS) {
                    val report = RazerV25.report(txn, RazerV25.CLASS_INTERHAPTICS, RazerV25.CMD_SET_STREAM_FRAME, RazerV25.legacyRumbleArgs(0, 0))
                    val w = setFeature(connection, iface, report)
                    val status = if (w == report.size) getFeatureStatus(connection, iface) else -1
                    tried.append(String.format(Locale.ROOT, " 0x%02x:%s", txn, if (w != report.size) "write $w" else statusName(status)))
                    if (status == RazerV25.STATUS_OK) {
                        Log.i(TAG, "rumble: usb transaction id probe:$tried")
                        return txn
                    }
                }
                Log.i(TAG, "rumble: usb transaction id probe:$tried -> no clear answer, using 0x1f")
                return RazerV25.TRANSACTION_IDS.first()
            }

            fun setFeature(connection: UsbDeviceConnection, iface: Int, report: ByteArray): Int = try {
                connection.controlTransfer(0x21, 0x09, 0x0300, iface, report, report.size, 100)
            } catch (e: Exception) { -1 }

            /** The status byte of the pad's answer to the last feature report, or -1. */
            fun getFeatureStatus(connection: UsbDeviceConnection, iface: Int): Int {
                val reply = ByteArray(RazerV25.REPORT_LEN)
                val r = try { connection.controlTransfer(0xA1, 0x01, 0x0300, iface, reply, reply.size, 100) } catch (e: Exception) { -1 }
                return if (r > 0) reply[0].toInt() and 0xFF else -1
            }

            fun statusName(s: Int) = when (s) {
                -1 -> "no reply"
                RazerV25.STATUS_OK -> "ok"
                0x01 -> "busy"
                0x03 -> "failed"
                0x04 -> "timeout"
                0x05 -> "not supported"
                else -> String.format(Locale.ROOT, "0x%02x", s)
            }
        }
    }
}

/**
 * Razer's "Protocol 2.5" feature report, as Razer's SDK and openrazer build it: 90 bytes,
 * [0] status, [1] transaction id, [2..3] remaining packets, [4] protocol type, [5] argument
 * length, [6] command class, [7] command id, [8..87] arguments, [88] XOR of bytes 2..87, [89] 0.
 */
internal object RazerV25 {
    const val REPORT_LEN = 90
    const val ARGS_MAX = 80
    const val CLASS_INTERHAPTICS = 0x16
    /** SetInterhapticsStreamFrame. */
    const val CMD_SET_STREAM_FRAME = 0x0E
    const val STATUS_OK = 0x02
    val TRANSACTION_IDS = intArrayOf(0x1F, 0x3F, 0xFF, 0x9F, 0x00)

    fun report(txn: Int, cls: Int, id: Int, args: ByteArray): ByteArray {
        require(args.size <= ARGS_MAX) { "too many arguments: ${args.size}" }
        val b = ByteArray(REPORT_LEN)
        b[1] = txn.toByte()
        b[5] = args.size.toByte()
        b[6] = cls.toByte()
        b[7] = id.toByte()
        args.copyInto(b, 8)
        var crc = 0
        for (i in 2 until 88) crc = crc xor (b[i].toInt() and 0xFF)
        b[88] = crc.toByte()
        return b
    }

    /**
     * The argument of SetInterhapticsStreamFrame in Cortex's "legacy special mode" (its rumble
     * test): a 65-byte chunk, [0] = chunk number 1, then a bit stream, most significant bit
     * first: 7 bits 0, 8 bits 0, 8 bits low-frequency motor, 8 bits high-frequency motor.
     * Both 0 stops the motors.
     */
    /**
     * A 0..65535 evdev / Steam motor level on the pad's 0..255 scale, linear and full range:
     * 65535 -> 255, 32768 -> 128; a nonzero level never becomes 0 (that would be a stop).
     */
    fun level(v: Int): Int {
        if (v <= 0) return 0
        return ((v.coerceAtMost(65535) * 255L + 32767L) / 65535L).toInt().coerceAtLeast(1)
    }

    fun legacyRumbleArgs(low: Int, high: Int): ByteArray {
        val l = low.coerceIn(0, 255)
        val h = high.coerceIn(0, 255)
        val a = ByteArray(65)
        a[0] = 1
        a[1] = 0
        a[2] = (l ushr 7).toByte()
        a[3] = (((l and 0x7F) shl 1) or (h ushr 7)).toByte()
        a[4] = ((h and 0x7F) shl 1).toByte()
        return a
    }
}
