package com.droiddeck.launcher.session

import android.content.Context
import android.os.Build
import android.system.Os
import com.droiddeck.launcher.core.DeviceSupport
import com.droiddeck.launcher.core.FileUtils
import com.droiddeck.launcher.gpu.DriverBundle
import com.droiddeck.launcher.gpu.DriverPairs
import com.droiddeck.launcher.gpu.GpuInfo
import com.droiddeck.launcher.gpu.SystemVulkanDriver
import com.droiddeck.launcher.gpu.TurnipReleases
import com.droiddeck.launcher.gpu.VulkanInfo
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * graphics-report.txt in each session's folder: which graphics path the session drew with and why,
 * the GPU and its device nodes, the environment the guest got, and at the end what the compositor
 * said about its driver. Written for a non-Adreno test report, where a black screen otherwise comes
 * with nothing to read; the Share logs zip takes the whole folder, so it travels with the logs.
 * Read-only: nothing here opens a GPU node or changes a setting.
 */
object GraphicsReport {
    const val FILE = "graphics-report.txt"
    const val GAMES_DIR = "games"

    /** One file in games/ past this is cut to its first part at the session's end. */
    private const val GAME_FILE_MAX = 20L * 1024 * 1024
    /** All of games/ past this loses its oldest launches. */
    private const val GAMES_TOTAL_MAX = 120L * 1024 * 1024

    /** The guest keys the report repeats: everything that decides which Vulkan driver loads. */
    private val ENV_PREFIXES = listOf(
        "VK_", "BL_VK", "BL_DRM", "BL_VKBRIDGE", "VKBRIDGE", "MESA_", "GALLIUM_", "ZINK", "TU_", "PANVK",
        "DROIDDECK_POWERVR", "DROIDDECK_HYBRIS", "BL_GAME_LOGS", "LIBGL_", "GAMESCOPE_DISABLE", "XWAYLAND",
    )

    /** Per-game graphics logs and the guest's Vulkan summary: on for a non-Adreno GPU, or by Setup's switch. */
    fun detailed(context: Context): Boolean = !DeviceSupport.adreno() || SessionPrefs.detailedGraphicsLogs(context)

    /** For finish(), which runs from the session's end without a context of its own. */
    @Volatile private var appContext: Context? = null

    /** Writes the start of the report; never throws. */
    fun begin(context: Context, dir: File, guest: List<String>) {
        appContext = context.applicationContext
        runCatching { File(dir, FILE).writeText(start(context, guest)) }
            .onFailure { android.util.Log.w("GraphicsReport", "could not write $FILE", it) }
    }

    private fun start(context: Context, guest: List<String>): String = buildString {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        appendLine("== graphics report, session start $time")
        appendLine("build: ${com.droiddeck.launcher.BuildConfig.BUILD_LABEL} (source ${com.droiddeck.launcher.BuildConfig.SOURCE_COMMIT})")
        appendLine()
        val gpu = runCatching { GpuInfo.detect() }.getOrNull()
        appendLine("-- path")
        appendLine("path: " + path(context))
        appendLine("driver mode: " + SessionPrefs.gpuDriverMode(context))
        appendLine("display driver (compositor): " + SessionPrefs.androidDriver(context).ifEmpty {
            if (DeviceSupport.adreno()) "(auto: bundled Turnip)" else "(auto: the phone's own Vulkan driver, ${SystemVulkanDriver.hal() ?: "vendor HAL"})"
        })
        val display = SessionPrefs.androidDriver(context)
        appendLine("display driver chosen by: " + when {
            display.isEmpty() -> "auto (or a pair on a non-Adreno GPU: the phone's own driver)"
            DriverBundle.containing(context, display, false) != null -> "pair (its display half)"
            else -> "user (picked on its own)"
        })
        val linux = SessionPrefs.linuxDriver(context)
        appendLine("runtime driver: " + linux.ifEmpty { "(runtime default)" })
        if (linux.isNotEmpty()) {
            val dir = com.droiddeck.launcher.gpu.LinuxVulkanDriverManager(context).getDriverDir(linux)
            appendLine("runtime driver dir: ${dir.path} (" + (dir.listFiles()?.filter { it.name.endsWith(".so") }?.joinToString { it.name } ?: "missing") + ")")
        }
        val prefs = context.getSharedPreferences("graphics_report", Context.MODE_PRIVATE)
        val previous = prefs.getString("lastDrivers", null)
        val now = "display ${SessionPrefs.androidDriver(context).ifEmpty { "(auto)" }}, runtime ${linux.ifEmpty { "(default)" }}, bridge ${DeviceSupport.vkBridge(context)}, venus ${VenusComponent.wanted(context)}"
        appendLine("previous session's drivers: " + (previous ?: "(none recorded)") + if (previous != null && previous != now) "  << CHANGED" else "")
        prefs.edit().putString("lastDrivers", now).apply()
        DriverBundle.active(context)?.let { b ->
            appendLine("pair: ${b.name} ${b.version} (bundle ${b.id})")
            release(context, b.id)?.let { appendLine("pair release: $it") }
        }
        if (gpu != null) {
            val pairs = DriverPairs.from(TurnipReleases.cached(context))
            appendLine("auto would pick: " + (DriverPairs.recommendedKey(gpu, pairs) ?: "(no pair: system driver or bridge)"))
        }
        appendLine("frame generation: " + runCatching { com.droiddeck.launcher.gpu.FrameGen.mode(context).toString() }.getOrDefault("?") +
            " (engine=off means it is switched off in Play settings; a driver that fails LSFG's gates logs \"device gates\" below)")
        appendLine("mali bridge setting: " + SessionPrefs.vkBridge(context) + ", in use: " + DeviceSupport.vkBridge(context))
        val standIn = runCatching { com.droiddeck.launcher.runtime.LinuxRuntime.gpuStandIn(context) }.getOrNull()
        appendLine("render node for the guest: " + (standIn?.let { node ->
            com.droiddeck.launcher.runtime.LinuxRuntime.deviceNumbers(node)?.let { n -> "/dev/dri/renderD${n.substringAfter(':')} -> $node ($n)" } ?: node
        } ?: if (SystemVulkanDriver.isPowerVr()) "the system GPU's own /dev/dri node (#570)" else "none"))
        appendLine()
        appendLine("-- gpu")
        if (gpu != null) {
            appendLine("family: ${gpu.family} (${gpu.supportText})")
            appendLine("name: ${gpu.name}" + if (gpu.model > 0) ", model ${gpu.model} (${gpu.modelSource})" else "")
        }
        appendLine("vulkan device (app): " + (VulkanInfo.cachedOrNull()?.get("device") ?: "(not probed)"))
        appendLine("system vulkan hal: " + (SystemVulkanDriver.hal() ?: "none"))
        read("/sys/class/misc/mali0/device/gpuinfo")?.let { appendLine("mali gpuinfo: $it") }
        appendLine("android: ${Build.VERSION.RELEASE} (sdk ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
        val soc = if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else Build.HARDWARE
        appendLine("soc: $soc, board ${Build.BOARD}, hardware ${Build.HARDWARE}")
        appendLine("kernel: " + (System.getProperty("os.version") ?: "?"))
        appendLine()
        appendLine("-- gpu nodes (from the app; not opened)")
        val dri = File("/dev/dri").list()?.sorted()?.map { "/dev/dri/$it" }.orEmpty()
        for (path in listOf("/dev/kgsl-3d0", "/dev/mali0") + dri) appendLine(node(path))
        if (dri.isEmpty()) appendLine("/dev/dri: " + if (File("/dev/dri").exists()) "not listable" else "absent")
        appendLine()
        appendLine("-- guest environment (graphics)")
        guest.filter { e -> ENV_PREFIXES.any { e.startsWith(it) } }.sorted().forEach { appendLine(it) }
        appendLine()
    }

    /** Which backend this session's Vulkan goes through, as the session decides it. */
    private fun path(context: Context): String = when {
        DeviceSupport.adreno() -> "Turnip (Adreno, KGSL)"
        VenusComponent.wanted(context) -> "Mali (Venus): Mesa Venus over vtest to the phone's own Vulkan driver, with the Mali compatibility layer"
        DeviceSupport.vkBridge(context) -> "Mali bridge (#517): the phone's own Vulkan driver through the bridge"
        DeviceSupport.openDriverActive(context) -> "open driver pair (PanVK / RADV)"
        SystemVulkanDriver.usesDefault(SessionPrefs.linuxDriver(context)) && SystemVulkanDriver.isAvailable() ->
            "system Vulkan (#570): ${SystemVulkanDriver.gpuName()} driver through libhybris"
        SessionPrefs.linuxDriver(context).isNotEmpty() -> "imported runtime driver ${SessionPrefs.linuxDriver(context)}"
        else -> "none for this GPU (no Adreno, no system HAL, no pair, bridge off)"
    }

    /** The release tag and download a pair bundle came from, when the last release check knows it. */
    private fun release(context: Context, bundleId: String): String? {
        val bundles = TurnipReleases.cached(context)?.assets?.filter { it.bundle }.orEmpty()
        val asset = bundles.firstOrNull { it.name.removeSuffix("-Pair.zip") == bundleId }
            ?: bundles.firstOrNull { bundleId.startsWith(it.name.removeSuffix("-Pair.zip") + "-") }
            ?: return null
        val installed = TurnipReleases.installedBuiltOn(context, asset)
        return "${asset.tag} ${asset.url}" +
            (if (installed.isNotEmpty()) ", installed build uploaded $installed" else "") +
            (if (asset.builtOn.isNotEmpty()) ", listed build uploaded ${asset.builtOn}" else "") +
            (if (TurnipReleases.updateAvailable(context, asset)) "  << NEWER BUILD AVAILABLE" else "")
    }

    private fun node(path: String): String {
        val f = File(path)
        if (!f.exists()) return "$path: absent"
        val access = (if (f.canRead()) "r" else "-") + (if (f.canWrite()) "w" else "-")
        val rdev = runCatching { Os.stat(path).st_rdev }.getOrNull()
        val numbers = rdev?.let { " ${major(it)}:${minor(it)}" }.orEmpty()
        val label = runCatching { String(Os.getxattr(path, "security.selinux")).trimEnd('\u0000') }.getOrNull()
        return "$path: $access$numbers" + (label?.let { " $it" } ?: "")
    }

    /** Linux's dev_t split (glibc's gnu_dev_major / gnu_dev_minor). */
    internal fun major(dev: Long): Long = ((dev ushr 8) and 0xfff) or ((dev ushr 32) and 0xfffff000L)
    internal fun minor(dev: Long): Long = (dev and 0xff) or ((dev ushr 12) and 0xffffff00L)

    private fun read(path: String): String? = FileUtils.readString(File(path))?.trim()?.takeIf { it.isNotEmpty() }

    /** The compositor's driver lines from the app's own log. */
    private val COMPOSITOR = Regex(
        """vk_present|waylandcomp|vk_loader|compositor|vkCreateDevice|extension|adrenotools|TurnipDriver|""" +
            """graphics driver|display driver|DD_PRESENT_FAULT|SystemVulkan|vkbridge|VkBridge|mali_gralloc|panvk|PanVK|radv|MESA|native buffer|first frame|present|""" +
            """lsfg|FrameGen|device gates|AAudio|directaudio|pulse|gralloc|dma.?buf|modifier|import""",
        RegexOption.IGNORE_CASE,
    )

    /** The session script's driver lines: what it exported, the bridge's self-test, vulkaninfo, gamescope's Vulkan errors. */
    private val GUEST = Regex("""== vulkan driver|== vkbridge|== vulkaninfo|vulkan:|panvk|PanVK|radv|primary node|No compatible|DRM format|kbase|AAUDIO|client sink|Xwayland|\(EE\)|glamor|XWAYLAND_NO_GLAMOR""", RegexOption.IGNORE_CASE)

    /**
     * The end of the report, from the finished folder: the compositor's driver lines out of
     * app.log, and games/ held to its size limits. Never throws.
     */
    fun finish(dir: File) {
        runCatching {
            val report = File(dir, FILE)
            if (!report.isFile) return
            val lines = File(dir, "app.log").takeIf { it.isFile }?.useLines { seq -> seq.filter { COMPOSITOR.containsMatchIn(it) }.take(400).toList() }.orEmpty()
            val games = File(dir, GAMES_DIR).listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }.orEmpty()
            val guestLines = File(dir, "session.log").takeIf { it.isFile }?.useLines { seq ->
                seq.filter { GUEST.containsMatchIn(it) }.take(200).toList()
            }.orEmpty()
            val fallbackLines = File(dir, "vkbridge-fallback").takeIf { it.isFile }?.readLines()?.map { it.trim() }?.filter { it.isNotEmpty() }
            val fellBack = fallbackLines?.firstOrNull()
            val fallbackReason = fallbackLines?.getOrNull(1).orEmpty()
            runCatching { SessionPrefs.setBridgeFellBack(appContext ?: return@runCatching, fellBack != null, fallbackReason) }
            val limits = File(dir, "limits.txt").takeIf { it.isFile }?.readText()?.trim()
            val audio = File(dir, "audio.log").takeIf { it.isFile }?.readText().orEmpty()
            report.appendText(buildString {
                limits?.let { appendLine("open files limit: $it") }
                fellBack?.let {
                    if (fallbackReason == "memory") appendLine("!! MALI BRIDGE FELL BACK: memory sharing = none (no method passed the server's self-test); the session ran on $it")
                    else appendLine("!! MALI BRIDGE FELL BACK: the self-test failed images or draws; the session ran on $it")
                }
                com.droiddeck.launcher.wayland.WaylandCompositor.compositorFault().takeIf { it.isNotEmpty() }?.let {
                    appendLine("!! DISPLAY FAILED: $it")
                }
                com.droiddeck.launcher.wayland.WaylandCompositor.displayDriver().takeIf { it.isNotEmpty() }?.let {
                    appendLine("display driver (as loaded): $it")
                }
                appContext?.let { ctx ->
                    if (SessionPrefs.venus(ctx)) VenusComponent.hostVulkanTooOld(ctx)?.let {
                        appendLine("!! VENUS OFF: the phone's driver reports Vulkan $it; gamescope needs 1.2 or newer, so the session did not use Venus")
                    }
                }
                if (audio.contains("AAUDIO_ERROR_DISCONNECTED")) {
                    appendLine("!! SOUND: Android refused the audio output (AAUDIO_ERROR_DISCONNECTED) - rebooting the phone usually fixes this")
                }
                if (guestLines.any { it.contains("vkbridge", ignoreCase = true) && it.contains("FAIL") }) {
                    appendLine("!! MALI BRIDGE SELF-TEST FAILED: this GPU's driver did not pass the bridge's checks (see below)")
                }
                if (guestLines.any { it.contains("MEM_EXEC_INIT") }) {
                    appendLine("note: kbase refused KBASE_IOCTL_MEM_EXEC_INIT with EPERM. That answer comes from the kbase driver")
                    appendLine("      itself (proot does not filter ioctls; Android's seccomp would kill or return ENOSYS; SELinux")
                    appendLine("      would return EACCES), so it is the kernel's policy for this context, not the session's.")
                }
                appendLine("-- driver lines from the session log (${guestLines.size})")
                guestLines.forEach { appendLine(it) }
                appendLine()
                appendLine("-- compositor and driver lines from app.log (${lines.size})")
                lines.forEach { appendLine(it) }
                appendLine()
                appendLine("-- game launches with detailed logs: ${games.size}")
                games.forEach { appendLine(it.name) }
                appendLine("== session end " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
            })
            capGames(File(dir, GAMES_DIR))
        }.onFailure { android.util.Log.w("GraphicsReport", "could not finish $FILE", it) }
    }

    /** Cuts each file in [games] to [GAME_FILE_MAX] and drops the oldest launches past [GAMES_TOTAL_MAX]. */
    internal fun capGames(games: File, fileMax: Long = GAME_FILE_MAX, totalMax: Long = GAMES_TOTAL_MAX) {
        if (!games.isDirectory) return
        games.walkTopDown().filter { it.isFile && it.length() > fileMax }.forEach { f ->
            runCatching { java.io.RandomAccessFile(f, "rw").use { it.setLength(fileMax) } }
        }
        val launches = games.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }.orEmpty().toMutableList()
        fun size(d: File) = d.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        var total = launches.sumOf { size(it) }
        while (total > totalMax && launches.size > 1) {
            val oldest = launches.removeAt(0)
            total -= size(oldest)
            FileUtils.delete(oldest)
        }
    }
}
