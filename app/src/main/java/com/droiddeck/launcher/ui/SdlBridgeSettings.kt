package com.droiddeck.launcher.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

// sdl3bridge: SDL3Bridge settings tab
/**
 * DroidDeck's link to the SDL3 bridge app's settings. A connection of its own (not DualSenseBridge's): it never
 * registers for controller frames, it only asks for the settings state (MSG_SETTINGS_GET, once a second while
 * the tab is open) and sends single changes (MSG_SETTINGS_SET); the bridge answers both with MSG_SETTINGS.
 * The bridge sends the choices too (mode names, button names, ...), so nothing here has to match its lists.
 */
internal object SdlBridgeLink {
    private const val ACTION = "com.example.dsedge.BRIDGE"
    private const val SCREEN = "com.example.dsedge.BridgeActivity"
    // the bridge built into this app (SDL3DroidDeck), else the separate SDL3Bridge app
    private fun pkg(ctx: Context) = com.droiddeck.launcher.input.DualSenseBridge.bridgePackage(ctx)
    private const val MSG_SETTINGS_GET = 40
    private const val MSG_SETTINGS_SET = 41
    private const val MSG_SETTINGS = 42

    var snapshot by mutableStateOf<Bundle?>(null)
        private set
    private var server: Messenger? = null
    private var bound = false
    private var users = 0

    private val inbox = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(m: Message) {
            if (m.what == MSG_SETTINGS) snapshot = Bundle(m.data)
        }
    })

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(n: ComponentName, b: IBinder) {
            server = Messenger(b)
            refresh()
        }
        override fun onServiceDisconnected(n: ComponentName) { server = null }
    }

    fun installed(ctx: Context): Boolean = try { ctx.packageManager.getPackageInfo(pkg(ctx), 0); true } catch (_: Exception) { false }

    fun start(ctx: Context) {
        users++
        if (bound) return
        bound = try {
            ctx.applicationContext.bindService(Intent(ACTION).setPackage(pkg(ctx)), conn, Context.BIND_AUTO_CREATE)
        } catch (_: Exception) { false }
    }

    fun stop(ctx: Context) {
        users = maxOf(0, users - 1)
        if (users > 0 || !bound) return
        try { ctx.applicationContext.unbindService(conn) } catch (_: Exception) {}
        bound = false
        server = null
    }

    fun connected(): Boolean = server != null

    fun refresh() = send(Message.obtain(null, MSG_SETTINGS_GET))

    /** One change. Shown at once; the bridge's answer then confirms it. */
    fun set(key: String, p: Int = 0, i: Int = 0, int: Int? = null, bool: Boolean? = null) {
        snapshot?.let { old -> snapshot = Bundle(old).also { applyLocally(it, key, p, i, int, bool) } }
        send(Message.obtain(null, MSG_SETTINGS_SET).apply {
            data = Bundle().apply {
                putString("key", key); putInt("p", p); putInt("i", i)
                if (int != null) putInt("int", int)
                if (bool != null) putBoolean("bool", bool)
            }
        })
    }

    /** The bridge's own screen (controller test, USB access, ...): in this app when built in, else SDL3Bridge. */
    fun open(ctx: Context) {
        val screen = Intent().setClassName(pkg(ctx), SCREEN).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { ctx.startActivity(screen) } catch (_: Exception) {
            val launch = ctx.packageManager.getLaunchIntentForPackage(pkg(ctx)) ?: return
            try { ctx.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
        }
    }

    private fun send(m: Message) {
        val s = server ?: return
        m.replyTo = inbox
        try { s.send(m) } catch (_: RemoteException) { server = null }
    }

    private fun applyLocally(b: Bundle, key: String, p: Int, i: Int, int: Int?, bool: Boolean?) {
        val remapCount = b.getStringArray("remapTargets")?.size ?: 8
        fun setInt(k: String, index: Int, v: Int) {
            val a = b.getIntArray(k)?.clone() ?: return
            if (index in a.indices) a[index] = v
            b.putIntArray(k, a)
        }
        fun setBool(k: String, index: Int, v: Boolean) {
            val a = b.getBooleanArray(k)?.clone() ?: return
            if (index in a.indices) a[index] = v
            b.putBooleanArray(k, a)
        }
        when (key) {
            "presentAs", "pollMs" -> if (int != null) b.putInt(key, int)
            "driverEnabled", "forceUsb", "bubble" -> if (bool != null) b.putBoolean(key, bool)
            "gyroMap" -> if (int != null) setInt(key, p * 3 + i, int)
            "remap" -> if (int != null) setInt(key, p * remapCount + i, int)
            "calibrate" -> b.putInt("calibrating", p)
            "resetCalibration" -> b.getFloatArray("gyroBias")?.clone()?.let { a ->
                for (k in 0 until 3) if (p * 3 + k in a.indices) a[p * 3 + k] = 0f
                b.putFloatArray("gyroBias", a)
            }
            else -> {
                if (int != null) setInt(key, p, int)
                if (bool != null) setBool(key, p, bool)
            }
        }
    }
}

/**
 * The SDL3Bridge settings page: a tab of DroidDeck's Setup screen, and a page of the in-session drawer.
 * [track] gives each control the drawer's focus tracking (controller navigation); Setup passes nothing.
 */
@Composable
internal fun SdlBridgeSettingsTab(host: MenuHost, track: (String) -> Modifier = { Modifier }) {
    val ctx = LocalContext.current
    val installed = remember { SdlBridgeLink.installed(ctx) }
    if (!installed) {
        SettingsGroup("SDL3Bridge") {
            SettingsRow("SDL3Bridge is not installed",
                "Install the SDL3Bridge app to use any SDL3-supported controller with DroidDeck: USB or Bluetooth, " +
                    "re-presented as a Steam Deck / Sony / Steam Controller pad, or handed to Steam as itself.") {}
        }
        return
    }
    var waited by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        SdlBridgeLink.start(ctx)
        onDispose { SdlBridgeLink.stop(ctx) }
    }
    LaunchedEffect(Unit) {
        while (true) {
            SdlBridgeLink.refresh()
            delay(1000)
            waited++
        }
    }
    val s = SdlBridgeLink.snapshot

    var sessionEnabled by remember { mutableStateOf(com.droiddeck.launcher.session.SessionPrefs.sdl3Controllers(ctx)) }
    SettingsGroup("Session integration") {
        ToggleRow(host, "sdl3-session", "Use SDL3 controllers in sessions",
            "Enable USB controller input, controller presentation and Steam Native Hook-up for the next session.",
            sessionEnabled, chipModifier = track("sdl3-session")) {
            sessionEnabled = it
            com.droiddeck.launcher.session.SessionPrefs.setSdl3Controllers(ctx, it)
        }
    }

    // ---------------- status ----------------
    SettingsGroup("SDL3Bridge") {
        if (s == null) {
            SettingsRow(if (SdlBridgeLink.connected()) "Waiting for SDL3Bridge…" else "Connecting to SDL3Bridge…",
                if (waited >= 3) "No answer yet. Update SDL3Bridge (this tab needs a recent build), or open it once." else null) {}
        } else {
            SettingsRow("Status", s.getString("status") ?: "") {}
            val engineError = s.getString("engineError") ?: ""
            val engineMode = s.getString("engineMode") ?: ""
            if (engineError.isNotEmpty()) SettingsRow("SDL3 is not running", engineError) {}
            else if (engineMode.isNotEmpty() && engineMode != "background service") SettingsRow("SDL3 runs in", engineMode) {}
            if (!s.getBoolean("notifications", true)) SettingsRow("Status bar icon is hidden",
                "SDL3Bridge may not show notifications: open it once and tap Allow (or allow it in Android's app settings).") {}
            val padNames = s.getStringArray("padNames") ?: emptyArray()
            val padIds = s.getStringArray("padIds") ?: emptyArray()
            val battery = s.getIntArray("padBattery") ?: IntArray(0)
            val hookNames = s.getStringArray("hookNames") ?: emptyArray()
            val hookIds = s.getStringArray("hookIds") ?: emptyArray()
            val triton = s.getString("triton") ?: ""
            var any = false
            padNames.forEachIndexed { i, name ->
                if (name.isEmpty()) return@forEachIndexed
                any = true
                val bat = battery.getOrElse(i) { -1 }
                SettingsRow("Player ${i + 1}: $name",
                    listOfNotNull(padIds.getOrNull(i), if (bat >= 0) "battery $bat%" else null).joinToString(" · ")) {}
            }
            hookNames.forEachIndexed { i, name ->
                if (name.isEmpty()) return@forEachIndexed
                any = true
                SettingsRow("Handed to Steam: $name",
                    listOfNotNull(hookIds.getOrNull(i), "/dev/hidraw${24 + i}",
                        if (s.getBoolean("fastPath")) "fast path" else null).joinToString(" · ")) {}
            }
            if (triton.isNotEmpty()) {
                any = true
                SettingsRow("Steam Controller (2026)", "$triton · raw passthrough") {}
            }
            if (!any) SettingsRow("No controller", "Plug a controller in with USB-C, or pair it over Bluetooth.") {}
        }
        ActionRow("Open SDL3Bridge", "Controller test screen, USB access, USB audio and everything else.", "Open",
            onClick = { SdlBridgeLink.open(ctx) }, modifier = track("sdl3-open"))
    }
    if (s == null) return

    val ints = { k: String -> s.getIntArray(k) ?: IntArray(0) }
    val bools = { k: String -> s.getBooleanArray(k) ?: BooleanArray(0) }
    val names = { k: String -> s.getStringArray(k) ?: emptyArray() }
    fun indexed(k: String) = names(k).mapIndexed { i, n -> i to n }

    // ---------------- global ----------------
    SettingsGroup("Controller mode") {
        BridgeChoiceRow(host, "sdl3-present", "Present controller as",
            "How Steam sees the controller, in the Steam session and on the desktop. Steam Native Hook-up hands USB " +
                "controllers to Steam as themselves, like on a PC (on the desktop: while Steam runs; other programs " +
                "there see an Xbox 360 controller).",
            indexed("presentNames"), s.getInt("presentAs"), chipModifier = track("sdl3-present")) { SdlBridgeLink.set("presentAs", int = it) }
        ToggleRow(host, "sdl3-driver", "SDL3 driver",
            "On: SDL3 reads the controller (all its features). Off: the controller goes back to Android and DroidDeck.",
            s.getBoolean("driverEnabled"), chipModifier = track("sdl3-driver")) { SdlBridgeLink.set("driverEnabled", bool = it) }
        if (s.getBoolean("embedded")) SettingsRow("Bluetooth and built-in controllers",
            "Handled by DroidDeck's Android input. SDL3 handles USB controllers.") {}
        else BridgeChoiceRow(host, "sdl3-source", "Controller source", "Which controllers SDL3 uses.",
            listOf(false to "USB + Bluetooth (auto)", true to "USB only (forced)"), s.getBoolean("forceUsb"), chipModifier = track("sdl3-source")) {
            SdlBridgeLink.set("forceUsb", bool = it)
        }
        val rateMs = ints("rateMs")
        BridgeChoiceRow(host, "sdl3-rate", "Poll rate", "Higher = lower gyro and trackpad latency, a little more battery. In Steam Native Hook-up it is also how often Steam gets the newest report (lower if games lag).",
            names("rateNames").mapIndexed { i, n -> rateMs.getOrElse(i) { 2 } to n }, s.getInt("pollMs"), chipModifier = track("sdl3-rate")) {
            SdlBridgeLink.set("pollMs", int = it)
        }
        val overlay = s.getBoolean("overlayAllowed")
        ToggleRow(host, "sdl3-bubble", "Floating bubble",
            if (overlay) "Tap: SDL3 driver on/off. Double tap: Steam Native Hook-up. Hold 3 s: open SDL3Bridge."
            else "Needs SDL3Bridge's \"Draw over other apps\" permission: turn the bubble on once in SDL3Bridge.",
            s.getBoolean("bubble"), enabled = overlay || s.getBoolean("bubble"), chipModifier = track("sdl3-bubble")) { SdlBridgeLink.set("bubble", bool = it) }
    }

    // ---------------- per player ----------------
    var player by rememberSaveable { mutableIntStateOf(0) }
    val p = player
    Box(Modifier.fillMaxWidth().padding(top = 16.dp)) {
        SegmentedTabs((0 until 4).map { it to "P${it + 1}" }, player, tabModifier = { track("sdl3-player-$it") }) { player = it }
    }

    SettingsGroup("Gyro · player ${p + 1}") {
        SliderRow("Gyro speed", "100 % = 1:1", ints("gyroGain").getOrElse(p) { 100 }, 5..800, step = 5,
            format = { "$it %" }, modifier = track("sdl3-gyrospeed-$p")) { SdlBridgeLink.set("gyroGain", p, int = it) }
        BridgeChoiceRow(host, "sdl3-gyroinv-$p", "Invert gyro axes", null, indexed("gyroInvNames"),
            ints("gyroInvert").getOrElse(p) { 0 }, chipModifier = track("sdl3-gyroinv-$p")) { SdlBridgeLink.set("gyroInvert", p, int = it) }
        for (i in 0 until 3) {
            BridgeChoiceRow(host, "sdl3-gyromap-$p-$i", "DroidDeck gyro ${"XYZ"[i]} comes from", null, indexed("axisNames"),
                ints("gyroMap").getOrElse(p * 3 + i) { i }, chipModifier = track("sdl3-gyromap-$p-$i")) { SdlBridgeLink.set("gyroMap", p, i, int = it) }
        }
        ToggleRow(host, "sdl3-accel-$p", "Accelerometer follows the axis remap",
            "Keeps gravity consistent with rotation.", bools("accelFollows").getOrElse(p) { true }, chipModifier = track("sdl3-accel-$p")) {
            SdlBridgeLink.set("accelFollows", p, bool = it)
        }
        SliderRow("Gyro deadzone", "Motion slower than this is ignored, so the view doesn't drift while you hold still.",
            ints("gyroDeadzone").getOrElse(p) { 0 }, 0..50, format = { "$it °/s" }, modifier = track("sdl3-gyrodeadzone-$p")) { SdlBridgeLink.set("gyroDeadzone", p, int = it) }
        val bias = s.getFloatArray("gyroBias") ?: FloatArray(0)
        val calibrating = s.getInt("calibrating", -1) == p
        val b = (0 until 3).map { bias.getOrElse(p * 3 + it) { 0f } }
        ActionRow("Calibrate gyro",
            when {
                calibrating -> "Hold player ${p + 1}'s controller flat and still…"
                b.all { it == 0f } -> "Lay the controller flat and still, then tap. Not calibrated yet."
                else -> "Drift removed: x %.2f, y %.2f, z %.2f °/s".format(b[0], b[1], b[2])
            }, "Calibrate", onClick = { SdlBridgeLink.set("calibrate", p) },
            modifier = track("sdl3-calibrate-$p"))
        ActionRow("Reset calibration", null, "Reset", onClick = { SdlBridgeLink.set("resetCalibration", p) },
            modifier = track("sdl3-uncalibrate-$p"))
    }

    SettingsGroup("Touchpad · player ${p + 1}") {
        BridgeChoiceRow(host, "sdl3-touch-$p", "Layout", "How the controller's touchpad maps to the Deck's two trackpads.",
            indexed("touchNames"), ints("touchMode").getOrElse(p) { 0 }, chipModifier = track("sdl3-touch-$p")) {
            SdlBridgeLink.set("touchMode", p, int = it)
        }
        ToggleRow(host, "sdl3-tinvx-$p", "Invert X", null, bools("touchInvX").getOrElse(p) { false }, chipModifier = track("sdl3-tinvx-$p")) { SdlBridgeLink.set("touchInvX", p, bool = it) }
        ToggleRow(host, "sdl3-tinvy-$p", "Invert Y", null, bools("touchInvY").getOrElse(p) { false }, chipModifier = track("sdl3-tinvy-$p")) { SdlBridgeLink.set("touchInvY", p, bool = it) }
        ToggleRow(host, "sdl3-tswap-$p", "Swap X and Y", "Applied before inverting.", bools("touchSwap").getOrElse(p) { false }, chipModifier = track("sdl3-tswap-$p")) {
            SdlBridgeLink.set("touchSwap", p, bool = it)
        }
    }

    SettingsGroup("Button remap · player ${p + 1}") {
        val targets = names("remapTargets")
        val remap = ints("remap")
        val sources = indexed("srcNames")
        targets.forEachIndexed { i, target ->
            BridgeChoiceRow(host, "sdl3-remap-$p-$i", target, "Pressed by", sources, remap.getOrElse(p * targets.size + i) { 0 }, chipModifier = track("sdl3-remap-$p-$i")) {
                SdlBridgeLink.set("remap", p, i, int = it)
            }
        }
    }

    SettingsGroup("Lightbar · player ${p + 1}") {
        SliderRow("Red", null, ints("lightR").getOrElse(p) { 0 }, 0..255, modifier = track("sdl3-red-$p")) { SdlBridgeLink.set("lightR", p, int = it) }
        SliderRow("Green", null, ints("lightG").getOrElse(p) { 0 }, 0..255, modifier = track("sdl3-green-$p")) { SdlBridgeLink.set("lightG", p, int = it) }
        SliderRow("Blue", null, ints("lightB").getOrElse(p) { 255 }, 0..255, modifier = track("sdl3-blue-$p")) { SdlBridgeLink.set("lightB", p, int = it) }
        SliderRow("Brightness", null, ints("brightness").getOrElse(p) { 100 }, 0..100, format = { "$it %" }, modifier = track("sdl3-brightness-$p")) {
            SdlBridgeLink.set("brightness", p, int = it)
        }
        BridgeChoiceRow(host, "sdl3-pled-$p", "Player LEDs", null, indexed("playerLedNames"), ints("playerLed").getOrElse(p) { 0 }, chipModifier = track("sdl3-pled-$p")) {
            SdlBridgeLink.set("playerLed", p, int = it)
        }
    }
}

/** "Steam Native Hook-up (Steam sees the real controller)" -> "Steam Native Hook-up" + "Steam sees the real controller";
 *  "Split (decoupled): left half = ..." -> "Split (decoupled)" + "left half = ...". */
private fun splitName(name: String): Pair<String, String?> {
    val colon = name.indexOf(": ")
    if (colon > 0) return name.substring(0, colon) to name.substring(colon + 2)
    val paren = name.indexOf(" (")
    if (paren > 0 && name.endsWith(")")) return name.substring(0, paren) to name.substring(paren + 2, name.length - 1)
    return name to null
}

/**
 * DroidDeck's ChoiceRow, for the bridge's (long) names. The chip shows the short name and the menu the short name
 * with the rest under it. When the chip would squeeze the label -- the session drawer is narrow, and a long chip
 * left the label no width at all, so the rows looked empty -- the chip goes under the label instead of beside it.
 */
@Composable
private fun <T> BridgeChoiceRow(
    host: MenuHost, key: String, label: String, hint: String?,
    options: List<Pair<T, String>>, selected: T, chipModifier: Modifier = Modifier,
    onPick: (T) -> Unit,
) {
    val open = host.open == key
    val parts = remember(options) { options.map { (value, name) -> splitName(name).let { Triple(value, it.first, it.second) } } }
    val chip = parts.firstOrNull { it.first == selected }?.second ?: "-"
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // ~7 dp per character of the chip's 13 sp text, plus its padding and caret; beside the label only while
        // the label keeps at least half of the row
        val chipWidth = (parts.maxOfOrNull { it.second.length } ?: 0) * 7f + 40f
        val stacked = chipWidth > maxWidth.value * 0.5f
        Column(Modifier.fillMaxWidth()) {
            SettingsRow(label, hint, highlighted = open, stacked = stacked) {
                Box {
                    ValueChip(chip, open, modifier = chipModifier.testTag("setting-$key")) { host.open = if (open) null else key }
                    AnchoredMenu(open, onDismiss = { if (host.open == key) host.open = null }, title = label) { firstItemFocus ->
                        parts.forEachIndexed { index, (value, text, detail) ->
                            MenuItem(text, checked = value == selected, detail = detail,
                                focusRequester = if (index == 0) firstItemFocus else null) {
                                onPick(value)
                                host.open = null
                            }
                        }
                    }
                }
            }
        }
    }
}
