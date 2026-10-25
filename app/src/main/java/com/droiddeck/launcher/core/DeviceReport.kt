package com.droiddeck.launcher.core

import android.app.ActivityManager
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Environment
import android.os.PerformanceHintManager
import android.os.PowerManager
import android.os.StatFs
import android.provider.Settings
import android.util.Log
import android.view.Display
import com.droiddeck.launcher.gpu.LinuxVulkanDriver
import com.droiddeck.launcher.gpu.LinuxVulkanDriverManager
import com.droiddeck.launcher.gpu.TurnipDriver
import com.droiddeck.launcher.runtime.DesktopCatalog
import com.droiddeck.launcher.runtime.LinuxRuntime
import com.droiddeck.launcher.runtime.LinuxRuntimeInstaller
import com.droiddeck.launcher.session.EsyncPacks
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.session.SessionService
import com.droiddeck.launcher.session.SessionState
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DeviceReport {
    private const val TAG = "DeviceReport"

    fun write(context: Context, target: File, mode: String) {
        try {
            target.writeText(build(context, mode))
        } catch (e: Exception) {
            Log.w(TAG, "could not write $target", e)
        }
    }

    /** Name of an ApplicationExitInfo reason code, so a report says "LOW_MEMORY" instead of "3". */
    internal fun exitReasonName(reason: Int): String = when (reason) {
        0 -> "UNKNOWN"
        1 -> "EXIT_SELF"
        2 -> "SIGNALED"
        3 -> "LOW_MEMORY"
        4 -> "CRASH"
        5 -> "CRASH_NATIVE"
        6 -> "ANR"
        7 -> "INITIALIZATION_FAILURE"
        8 -> "PERMISSION_CHANGE"
        9 -> "EXCESSIVE_RESOURCE_USAGE"
        10 -> "USER_REQUESTED"
        11 -> "USER_STOPPED"
        12 -> "DEPENDENCY_DIED"
        13 -> "OTHER"
        14 -> "FREEZER"
        15 -> "PACKAGE_STATE_CHANGE"
        16 -> "PACKAGE_UPDATED"
        else -> "REASON_$reason"
    }

    internal fun thermalStatusName(status: Int): String = when (status) {
        0 -> "NONE"
        1 -> "LIGHT"
        2 -> "MODERATE"
        3 -> "SEVERE"
        4 -> "CRITICAL"
        5 -> "EMERGENCY"
        6 -> "SHUTDOWN"
        else -> "STATUS_$status"
    }

    internal fun gameModeName(mode: Int): String = when (mode) {
        0 -> "UNSUPPORTED"
        1 -> "STANDARD"
        2 -> "PERFORMANCE"
        3 -> "BATTERY"
        4 -> "CUSTOM"
        else -> "MODE_$mode"
    }

    internal fun refreshModesSummary(rates: List<Float>): String =
        rates.map { Math.round(it) }.distinct().sortedDescending().joinToString(", ") { "$it Hz" }

    internal fun exitSummary(processName: String?, reason: Int, status: Int, description: String?): String =
        buildString {
            append(processName ?: "?").append("  ").append(exitReasonName(reason)).append("  status=").append(status)
            if (!description.isNullOrBlank()) append("  ").append(description.trim())
        }

    fun build(context: Context, mode: String): String {
        val b = StringBuilder()
        fun h(title: String) {
            b.append('\n').append(title).append('\n').append("-".repeat(title.length)).append('\n')
        }
        fun k(key: String, value: Any?) {
            b.append(key.padEnd(24)).append(value ?: "unknown").append('\n')
        }

        b.append("DroidDeck session report\n")
        b.append("========================\n")
        k("Written", SimpleDateFormat("yyyy-MM-dd HH:mm:ss zzz", Locale.US).format(Date()))
        k("Session mode", when (mode) { SessionService.MODE_DESKTOP -> "desktop (KDE Plasma)"; SessionService.MODE_RUN -> "a program under gamescope"; else -> "Steam client (gamescope)" })

        h("App")
        runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            k("Version", "${info.versionName} (${info.longVersionCode})")
        }
        k("Package", context.packageName)
        k("targetSdk", context.applicationInfo.targetSdkVersion)
        k("Native lib dir", context.applicationInfo.nativeLibraryDir)

        h("Device")
        k("Model", "${Build.MANUFACTURER} ${Build.MODEL}")
        k("Device / product", "${Build.DEVICE} / ${Build.PRODUCT}")
        k("Board / hardware", "${Build.BOARD} / ${Build.HARDWARE}")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            k("SoC", "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}")
        }
        k("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        k("Security patch", Build.VERSION.SECURITY_PATCH)
        k("Build", Build.DISPLAY)
        k("Fingerprint", Build.FINGERPRINT)
        k("Kernel", System.getProperty("os.version"))
        k("ABIs", Build.SUPPORTED_ABIS.joinToString(", "))
        k("Memory page size", runCatching { android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE) }.getOrNull()?.let { "$it bytes" })

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            h("Recent process exits")
            val exits = runCatching {
                context.getSystemService(android.app.ActivityManager::class.java).getHistoricalProcessExitReasons(context.packageName, 0, 5)
            }.getOrNull().orEmpty()
            if (exits.isEmpty()) k("Exits", "none recorded") else {
                val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                for (e in exits) {
                    k(stamp.format(Date(e.timestamp)), exitSummary(e.processName, e.reason, e.status, e.description))
                }
            }
        }

        h("CPU and memory")
        k("Cores", CpuCores.all.size)
        b.append("Core ceilings          ")
        b.append(CpuCores.all.joinToString(", ") { c ->
            "cpu$c " + (CpuCores.maxGhz(c)?.let { String.format(Locale.US, "%.2f GHz", it) } ?: "?")
        })
        b.append('\n')
        runCatching {
            val mi = ActivityManager.MemoryInfo()
            (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
            k("RAM total", FileUtils.sizeToString(mi.totalMem))
            k("RAM available", FileUtils.sizeToString(mi.availMem))
        }
        val pm = runCatching { context.getSystemService(Context.POWER_SERVICE) as? PowerManager }.getOrNull()
        if (pm != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                k("Thermal status", runCatching { thermalStatusName(pm.currentThermalStatus) }.getOrNull())
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                k("Thermal headroom (10 s)", runCatching {
                    pm.getThermalHeadroom(10).let { if (it.isNaN()) "not reported" else String.format(Locale.US, "%.2f", it) }
                }.getOrNull())
            }
            k("Power saver", runCatching { pm.isPowerSaveMode }.getOrNull())
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val rate = runCatching { context.getSystemService(PerformanceHintManager::class.java)?.preferredUpdateRateNanos }.getOrNull()
            k("ADPF hint sessions", if (rate == null || rate <= 0) "not supported" else "supported (preferred update ${rate / 1000} us)")
            k("Game Mode", runCatching { context.getSystemService(android.app.GameManager::class.java)?.gameMode }.getOrNull()?.let { gameModeName(it) })
        }
        runCatching {
            val fs = StatFs(context.filesDir.path)
            k("App storage free", FileUtils.sizeToString(fs.availableBlocksLong * fs.blockSizeLong))
        }
        runCatching {
            val fs = StatFs(Environment.getExternalStorageDirectory().path)
            k("Shared storage free", FileUtils.sizeToString(fs.availableBlocksLong * fs.blockSizeLong))
        }

        h("GPU")
        k("KGSL gpu_model", readSys("/sys/class/kgsl/kgsl-3d0/gpu_model"))
        k("KGSL chip id", readSys("/sys/class/kgsl/kgsl-3d0/gpu_chipid"))
        k("System Vulkan ICD", if (File("/vendor/lib64/hw/vulkan.adreno.so").exists()) "/vendor/lib64/hw/vulkan.adreno.so" else "not at the usual path")

        h("Display")
        k("Session output", SessionState.outputSize?.let { "${it.first}x${it.second}" })
        k("Session refresh", String.format(Locale.US, "%.2f Hz", SessionState.refreshHz))
        runCatching {
            val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
            k("Panel refresh now", String.format(Locale.US, "%.2f Hz", display.refreshRate))
            k("Panel refresh modes", refreshModesSummary(display.supportedModes.map { it.refreshRate }))
        }
        k("peak_refresh_rate", runCatching { Settings.System.getString(context.contentResolver, "peak_refresh_rate") }.getOrNull() ?: "not set")
        k("refresh_rate_mode", runCatching { Settings.Secure.getString(context.contentResolver, "refresh_rate_mode") }.getOrNull() ?: "not set")
        val panelSize = com.droiddeck.launcher.session.SessionDisplay.panelSize(context)
        for ((label, mode) in listOf("Resolution (Steam)" to SessionService.MODE_STEAM, "Resolution (Desktop)" to SessionService.MODE_DESKTOP)) {
            val choice = SessionPrefs.resolutionChoice(context, mode, panelSize)
            val size = com.droiddeck.launcher.session.SessionDisplay.resolveChoice(panelSize, choice)
            k(label, "$choice (${size.first}x${size.second})")
        }
        k("Foldable", context.packageManager.hasSystemFeature("android.hardware.sensor.hinge_angle"))

        h("Drivers")
        val gpu = com.droiddeck.launcher.gpu.GpuInfo.detect()
        k("GPU", "${gpu.name} · ${gpu.family.label} · ${gpu.supportText}" + if (gpu.oneUi8Gen2) " · One UI 8 Gen 2" else "")
        if (gpu.model == 740) {
            val hint = com.droiddeck.launcher.gpu.TpUbwcHint
            k("TP UBWC hint", hint.forThisDevice(context) ?: "driver default")
            k("TP UBWC mode", hint.activeMode ?: SessionPrefs.TP_UBWC_AUTO)
            if (hint.restartRequired(context)) k("TP UBWC pending", "${SessionPrefs.tpUbwcMode(context)} (restart required)")
        }
        k("Driver mode", SessionPrefs.gpuDriverMode(context) +
            (com.droiddeck.launcher.gpu.DriverPairs.recommendedKey(gpu, com.droiddeck.launcher.gpu.DriverPairs.from(
                com.droiddeck.launcher.gpu.TurnipReleases.cached(context)), com.droiddeck.launcher.gpu.TurnipReleases.autoPicks(context),
                com.droiddeck.launcher.gpu.AutoDriverTrial.bad(context))?.let { " (recommended pair: $it)" } ?: ""))
        val turnip = TurnipDriver(context)
        val androidChoice = SessionPrefs.androidDriver(context)
        k("Display driver (chosen)", if (androidChoice.isEmpty()) "Auto -> ${turnip.autoId()}" else androidChoice)
        k("  name / version", "${turnip.displayName(if (androidChoice.isEmpty()) turnip.autoId() else androidChoice)} ${turnip.driverVersion(if (androidChoice.isEmpty()) turnip.autoId() else androidChoice)}".trim())
        k("  imported available", turnip.enumerateImported().ifEmpty { listOf("none") }.joinToString(", "))
        val lm = LinuxVulkanDriverManager(context)
        run {
            val id = SessionPrefs.linuxDriver(context)
            val label = if (id.isEmpty()) "runtime default (the Turnip built into the runtime)"
            else "${lm.getDriverName(id)} ${lm.getDriverVersion(id)}".trim() +
                lm.getMinGlibc(id).let { if (it.isEmpty()) "" else " (glibc $it+)" } +
                if (lm.isInstalled(id)) "" else "  [MISSING - falls back to the runtime's own]"
            k("Linux driver", label)
        }
        k("Runtime's own ICD", LinuxRuntime.vulkanIcd(context)?.path)

        h("Runtime")
        k("Installed version", LinuxRuntimeInstaller.installedVersion(context))
        k("Runtime ready", LinuxRuntime.isInstalled(context))
        k("Desktop installed", DesktopCatalog.desktopInstalled(context))
        k("Interrupted installs", DesktopCatalog.interrupted(context).joinToString { "${it.id} ${it.version}" }.ifEmpty { "none" })
        val broken = runCatching { com.droiddeck.launcher.runtime.RuntimeHealth.brokenLibraryLinks(context) }.getOrDefault(emptyList())
        k("Broken library links", if (broken.isEmpty()) "none" else "${broken.size}: ${broken.take(8).joinToString()}")
        k("Runtime root", LinuxRuntime.rootDir(context).path)

        h("Settings in effect")
        k("Client core override", SessionPrefs.clientCpusOverride(context))
        k("  client cores", CpuCores.listOrAll(SessionPrefs.clientCpus(context)))
        k("  game cores", CpuCores.restrictionOrEmpty(SessionPrefs.gameCpus(context)).ifEmpty { "every core" })
        k("Turnip sysmem", SessionPrefs.tuSysmem(context))
        k("Zink lazy descriptors", SessionPrefs.zinkLazy(context))
        k("Threaded GL (glthread)", SessionPrefs.glThread(context))
        k("No GL error checks", SessionPrefs.noGlError(context))
        k("Steam controller", SessionPrefs.steamController(context))
        k("Upscaler", (SessionPrefs.upscalerChoices.firstOrNull { it.first == SessionPrefs.upscaler(context) }?.second ?: "Off") +
            " (sharpness ${SessionPrefs.upscaleSharpness(context)}%)")
        k("FEX preset", SessionPrefs.fexPreset(context).ifEmpty { "FEX defaults" })
        k("Skip xalia", SessionPrefs.noXalia(context))
        k("Wine sync", SessionPrefs.syncBackend(context))
        EsyncPacks.status(LinuxRuntime.rootDir(context)).let { k("droiddeck-esync packs", "${it.installed} installed, ${it.wanted} wanted") }
        k("Linux x86 (FEX)", if (com.droiddeck.launcher.runtime.LinuxFex.ready(context)) "ready" else "not set up")
        k("gamescope realtime", SessionPrefs.gamescopeRealtime(context))
        k("zRAM: compress Steam during games", SessionPrefs.zramClient(context))
        k("RAM cap", SessionPrefs.ramCapMb(context).let { if (it > 0) "$it MB" else "off" })
        k("VRAM cap", SessionPrefs.vramCapMb(context).let { if (it > 0) "$it MB" else "driver default" })
        k("proot without seccomp", SessionPrefs.prootNoSeccomp(context))
        k("proot fast path", SessionPrefs.prootFastPath(context))
        k("Guest host name", SessionPrefs.guestHostname(context))
        k("DirectAudio for games", SessionPrefs.directAudio(context))
        k("Stretch 16:9 to panel", SessionPrefs.stretch16x9(context))
        k("Return to launcher after a game", SessionPrefs.returnAfterGame(context))
        k("Microphone", SessionPrefs.micEnabled(context))
        k("On-screen controls", SessionPrefs.oscMode(context))
        k("Touch mode", SessionPrefs.touchMode(context))
        k("Performance HUD", SessionPrefs.hudEnabled(context))
        k("Game storage", when (val g = SessionPrefs.gameStorage(context)) {
            "" -> "automatic (" + (com.droiddeck.launcher.session.GameStorage.effective(context)?.path ?: "no card present") + ")"
            SessionPrefs.GAME_STORAGE_OFF -> "internal only"
            else -> g
        })

        h("Android process limits")
        k("Phantom proc monitor", PhantomProcessLimit.reportValue(PhantomProcessLimit.read(context)))

        h("Device switch files")
        SessionService.envSwitchFile(context)?.takeIf { it.isFile }?.let { f ->
            k("droiddeck-env", FileUtils.readString(f)?.trim()?.replace('\n', ' ')?.ifEmpty { "(present, empty)" } ?: "(present)")
        }
        if (File(Environment.getExternalStorageDirectory(), "Download/droiddeck-env").isFile) {
            k("Download/droiddeck-env", "(ignored - it moved to Android/data/${context.packageName}/files)")
        }
        for (name in listOf("droiddeck-tu-debug", "droiddeck-driver",
                            "droiddeck-osc", "droiddeck-no-pad",
                            "droiddeck-no-hud")) {
            val f = File(Environment.getExternalStorageDirectory(), "Download/$name")
            if (f.isFile) k(name, FileUtils.readString(f)?.trim()?.replace('\n', ' ')?.ifEmpty { "(present, empty)" } ?: "(present)")
        }

        b.append("\nNothing identifying is collected here: no serial number, no device or advertising\n")
        b.append("id, no account name, no network names. Safe to attach to a bug report as it is.\n")
        return b.toString()
    }

    private fun readSys(path: String): String? = FileUtils.readString(File(path))?.trim()?.ifEmpty { null }
}
