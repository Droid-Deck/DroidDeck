package com.droiddeck.launcher.core

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.util.Log
import com.droiddeck.launcher.gpu.LinuxVulkanDriver
import com.droiddeck.launcher.gpu.LinuxVulkanDriverManager
import com.droiddeck.launcher.gpu.TurnipDriver
import com.droiddeck.launcher.runtime.DesktopCatalog
import com.droiddeck.launcher.runtime.LinuxRuntime
import com.droiddeck.launcher.runtime.LinuxRuntimeInstaller
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.session.SessionService
import com.droiddeck.launcher.session.SessionState
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * `device.txt`: what this device is and what the session was told to do, written the moment a
 * session starts so a log can be read months later without asking the reporter a single question.
 *
 * **Nothing identifying goes in here.** No serial number, no Android ID, no IMEI, no account name,
 * no network names - only what the hardware is, what the ROM is, and which of our own settings
 * were in effect. The one thing that comes close is the build fingerprint, which names the ROM and
 * its build date and is the same string on every unit of that model.
 */
object DeviceReport {
    private const val TAG = "DeviceReport"

    fun write(context: Context, target: File, mode: String) {
        try {
            target.writeText(build(context, mode))
        } catch (e: Exception) {
            Log.w(TAG, "could not write $target", e)
        }
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
        k("Session mode", when (mode) { SessionService.MODE_DESKTOP -> "desktop (labwc/LXQt)"; SessionService.MODE_RUN -> "a program under gamescope"; else -> "Steam client (gamescope)" })

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
        k("Shape setting", SessionPrefs.shapeMode(context))
        k("Custom resolution (Steam)", SessionPrefs.customResolution(context, SessionService.MODE_STEAM)?.let { "${it.first}x${it.second}" } ?: "off")
        k("Custom resolution (Desktop)", SessionPrefs.customResolution(context, SessionService.MODE_DESKTOP)?.let { "${it.first}x${it.second}" } ?: "off")
        k("Foldable", context.packageManager.hasSystemFeature("android.hardware.sensor.hinge_angle"))

        h("Drivers")
        val turnip = TurnipDriver(context)
        val androidChoice = SessionPrefs.androidDriver(context)
        k("Display driver (chosen)", if (androidChoice.isEmpty()) "Auto -> ${turnip.autoId()}" else androidChoice)
        k("  name / version", "${turnip.displayName(if (androidChoice.isEmpty()) turnip.autoId() else androidChoice)} ${turnip.driverVersion(if (androidChoice.isEmpty()) turnip.autoId() else androidChoice)}".trim())
        k("  imported available", turnip.enumerateImported().ifEmpty { listOf("none") }.joinToString(", "))
        val lm = LinuxVulkanDriverManager(context)
        for (m in listOf(SessionService.MODE_STEAM, SessionService.MODE_DESKTOP)) {
            val id = SessionPrefs.linuxDriver(context, m)
            val label = if (id.isEmpty()) "runtime default (the Turnip built into the runtime)"
            else "${lm.getDriverName(id)} ${lm.getDriverVersion(id)}".trim() +
                lm.getMinGlibc(id).let { if (it.isEmpty()) "" else " (glibc $it+)" } +
                if (lm.isInstalled(id)) "" else "  [MISSING - falls back to the runtime's own]"
            k("Linux driver ($m)", label)
        }
        k("Runtime's own ICD", LinuxRuntime.vulkanIcd(context)?.path)

        h("Runtime")
        k("Installed version", LinuxRuntimeInstaller.installedVersion(context))
        k("Runtime ready", LinuxRuntime.isInstalled(context))
        k("Desktop installed", DesktopCatalog.desktopInstalled(context))
        k("Runtime root", LinuxRuntime.rootDir(context).path)

        h("Settings in effect")
        k("Client core override", SessionPrefs.clientCpusOverride(context))
        k("  client cores", CpuCores.listOrAll(SessionPrefs.clientCpus(context)))
        k("  game cores", CpuCores.restrictionOrEmpty(SessionPrefs.gameCpus(context)).ifEmpty { "every core (nothing sent)" })
        k("Turnip sysmem", SessionPrefs.tuSysmem(context))
        k("Zink lazy descriptors", SessionPrefs.zinkLazy(context))
        k("Threaded GL (glthread)", SessionPrefs.glThread(context))
        k("No GL error checks", SessionPrefs.noGlError(context))
        k("Steam Deck mode", SessionPrefs.steamDeckMode(context))
        k("Steam controller", SessionPrefs.steamController(context))
        k("FEX preset", SessionPrefs.fexPreset(context).ifEmpty { "FEX defaults" })
        k("Skip xalia", SessionPrefs.noXalia(context))
        k("gamescope realtime", SessionPrefs.gamescopeRealtime(context))
        k("proot without seccomp", SessionPrefs.prootNoSeccomp(context))
        k("Guest host name", SessionPrefs.guestHostname(context))
        k("DirectAudio for games", SessionPrefs.directAudio(context))
        k("Stretch games to fill", SessionPrefs.forceFullscreen(context))
        k("Client audio", if (SessionPrefs.clientDirectAudio(context)) "DirectAudio" else "classic")
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

        h("Device switch files in Download")
        for (name in listOf("droiddeck-env", "droiddeck-tu-debug", "droiddeck-driver",
                            "droiddeck-osc", "droiddeck-no-pad", "droiddeck-pad-log",
                            "droiddeck-no-hud", "droiddeck-wlr-renderer")) {
            val f = File(Environment.getExternalStorageDirectory(), "Download/$name")
            if (f.isFile) k(name, FileUtils.readString(f)?.trim()?.replace('\n', ' ')?.ifEmpty { "(present, empty)" } ?: "(present)")
        }

        b.append("\nNothing identifying is collected here: no serial number, no device or advertising\n")
        b.append("id, no account name, no network names. Safe to attach to a bug report as it is.\n")
        return b.toString()
    }

    private fun readSys(path: String): String? = FileUtils.readString(File(path))?.trim()?.ifEmpty { null }
}
