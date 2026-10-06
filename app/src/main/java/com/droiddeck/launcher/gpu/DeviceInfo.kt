package com.droiddeck.launcher.gpu

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.droiddeck.launcher.core.FileUtils
import com.droiddeck.launcher.session.SessionPrefs
import java.io.File
import java.util.Locale

/**
 * Everything the device info window shows about the phone's chip: SoC, GPU, CPU, memory, system,
 * the phone's own Vulkan driver and the drivers DroidDeck uses. Reads files and asks Vulkan once
 * ([VulkanInfo]), so [collect] belongs off the main thread. Values the phone does not give are
 * left out rather than guessed.
 */
object DeviceInfo {
    class Section(val title: String, val rows: List<Pair<String, String>>)

    fun collect(context: Context): List<Section> {
        val vk = VulkanInfo.query()
        // After Vulkan has been asked, detection can use its name too.
        val gpu = GpuInfo.detect()
        return listOf(
            chip(), gpu(gpu), cpu(), memory(context), system(), vulkan(context, vk), drivers(context, gpu),
        ).filter { it.rows.isNotEmpty() }
    }

    /** The whole window as plain text, for a bug report. */
    fun text(sections: List<Section>): String = buildString {
        append("DroidDeck device info\n")
        for (s in sections) {
            append('\n').append(s.title).append('\n')
            for ((k, v) in s.rows) append("  ").append(k).append(": ").append(v).append('\n')
        }
    }

    private fun MutableList<Pair<String, String>>.add(key: String, value: String?) {
        if (!value.isNullOrBlank() && value != Build.UNKNOWN) add(key to value.trim())
    }

    private fun read(path: String): String? = FileUtils.readString(File(path))?.trim()?.ifEmpty { null }

    private fun prop(name: String): String = GpuInfo.systemProperty(name)

    private fun chip() = Section("Chip (SoC)", buildList<Pair<String, String>> {
        val model = SocNames.model()
        val platform = prop("ro.board.platform")
        add("Name", SocNames.name(model, platform))
        add("Model", model)
        if (Build.VERSION.SDK_INT >= 31) add("Maker", Build.SOC_MANUFACTURER)
        add("Platform", platform)
        add("Hardware", Build.HARDWARE)
        add("Board", Build.BOARD)
        val id = read("/sys/devices/soc0/soc_id")
        val machine = read("/sys/devices/soc0/machine")
        add("SoC id", listOfNotNull(id, machine).joinToString(" · "))
        add("Family", read("/sys/devices/soc0/family"))
    })

    private fun gpu(gpu: GpuInfo) = Section("GPU", buildList<Pair<String, String>> {
        add("GPU", gpu.name)
        add("Family", gpu.family.label)
        add("Model found from", when (gpu.modelSource) {
            "kernel" -> "the kernel's GPU name"
            "vulkan" -> "the Vulkan driver (the kernel's name has no model)"
            "platform" -> "the platform (the kernel's name has no model)"
            else -> "unknown"
        })
        add("Kernel name", gpu.kgslName)
        val kgsl = "/sys/class/kgsl/kgsl-3d0"
        add("Clock range", gpuRange())
        add("Load", read("$kgsl/gpu_busy_percentage")?.let { if (it.endsWith("%")) it else "$it %" })
        add("Temperature", read("$kgsl/temp")?.toLongOrNull()?.let { if (it > 1000) "${it / 1000} °C" else "$it °C" })
        add("Support", gpu.supportText)
    })

    private fun cpu() = Section("CPU", buildList<Pair<String, String>> {
        add("Cores", Runtime.getRuntime().availableProcessors().toString())
        // Each cpufreq policy is one cluster: the cores it covers and their speed range.
        val policies = File("/sys/devices/system/cpu/cpufreq").listFiles { f -> f.name.matches(Regex("policy\\d+")) }
            ?.sortedBy { it.name.removePrefix("policy").toInt() }.orEmpty()
        for (p in policies) {
            val cpus = (read("${p.path}/related_cpus") ?: read("${p.path}/affected_cpus"))?.split(Regex("\\s+"))?.filter { it.isNotEmpty() }.orEmpty()
            val min = read("${p.path}/cpuinfo_min_freq")?.toLongOrNull()
            val max = read("${p.path}/cpuinfo_max_freq")?.toLongOrNull() ?: continue
            val cores = if (cpus.size > 1) "cores ${cpus.first()}-${cpus.last()}" else "core ${cpus.firstOrNull() ?: p.name.removePrefix("policy")}"
            add("Cluster ${p.name.removePrefix("policy")}", "${cpus.size.coerceAtLeast(1)} × $cores · " +
                (if (min != null) ghz(min) + "–" else "") + ghz(max))
        }
        add("ABI", Build.SUPPORTED_ABIS.joinToString(", "))
    })

    private fun ghz(khz: Long) = String.format(Locale.US, "%.2f GHz", khz / 1e6)

    /** The GPU's slowest and fastest step: KGSL's list, else a devfreq GPU node (Mali and others). */
    private fun gpuRange(): String? {
        fun mhz(hz: Long) = "${hz / 1_000_000} MHz"
        val kgsl = read("/sys/class/kgsl/kgsl-3d0/gpu_available_frequencies")
            ?.split(Regex("\\s+"))?.mapNotNull { it.toLongOrNull() }?.filter { it > 0 }.orEmpty()
        if (kgsl.isNotEmpty()) return "${mhz(kgsl.min())}–${mhz(kgsl.max())} (${kgsl.size} steps)"
        read("/sys/class/kgsl/kgsl-3d0/max_gpuclk")?.toLongOrNull()?.let { return "up to ${mhz(it)}" }
        val node = File("/sys/class/devfreq").listFiles()?.firstOrNull { f ->
            val n = f.name.lowercase()
            (n.contains("gpu") || n.contains("mali") || n.contains("kgsl-3d")) && listOf("bus", "bw", "memlat").none { n.contains(it) }
        } ?: return null
        val steps = read("${node.path}/available_frequencies")?.split(Regex("\\s+"))?.mapNotNull { it.toLongOrNull() }?.filter { it > 0 }.orEmpty()
        if (steps.isNotEmpty()) return "${mhz(steps.min())}–${mhz(steps.max())} (${steps.size} steps)"
        val min = read("${node.path}/min_freq")?.toLongOrNull()
        val max = read("${node.path}/max_freq")?.toLongOrNull() ?: return null
        return (if (min != null) "${mhz(min)}–" else "up to ") + mhz(max)
    }

    private fun memory(context: Context) = Section("Memory", buildList<Pair<String, String>> {
        val am = context.getSystemService(ActivityManager::class.java) ?: return@buildList
        val mi = ActivityManager.MemoryInfo().also(am::getMemoryInfo)
        add("Total", FileUtils.sizeToString(mi.totalMem))
        add("Available now", FileUtils.sizeToString(mi.availMem))
    })

    private fun system() = Section("System", buildList<Pair<String, String>> {
        add("Device", "${Build.MANUFACTURER} ${Build.MODEL}")
        add("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        add("Security patch", Build.VERSION.SECURITY_PATCH)
        add("Kernel", System.getProperty("os.version"))
        add("Build", Build.DISPLAY)
    })

    private fun vulkan(context: Context, vk: Map<String, String>) = Section("Vulkan (the phone's own driver)", buildList<Pair<String, String>> {
        vk["error"]?.let { add("Error", it) }
        add("Device", vk["device"])
        add("Type", vk["type"])
        add("API version", vk["api"])
        add("Loader version", vk["loader"])
        add("Driver", listOfNotNull(vk["driver_name"], vk["driver_version"]).joinToString(" "))
        // Qualcomm's build string starts with its own label ("Driver Build: 43c70540de, ...").
        add("Driver build", vk["driver_info"]?.replace(Regex("(?i)^driver build:\\s*"), ""))
        add("Conformance", vk["conformance"])
        add("Vendor / device id", listOfNotNull(vk["vendor_id"], vk["device_id"]).joinToString(" / "))
        add("Max texture size", vk["max_image_2d"])
        add("GPU memory heap", vk["device_heap_mb"]?.let { "$it MB" })
        add("Extensions", vk["extension_count"])
        val features = listOf("geometry_shader" to "Geometry shaders", "tessellation_shader" to "Tessellation", "texture_bc" to "BC textures")
        for ((key, label) in features) add(label, vk[key])
        for ((key, value) in vk) if (key.startsWith("ext.")) add(key.removePrefix("ext."), value)
        // What Android certifies the phone for.
        val pm = context.packageManager
        val hw = pm.systemAvailableFeatures.associate { it.name to it.version }
        hw[PackageManager.FEATURE_VULKAN_HARDWARE_VERSION]?.let {
            add("Android-certified version", "${it shr 22}.${(it shr 12) and 0x3ff}.${it and 0xfff}")
        }
        hw[PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL]?.let { add("Android hardware level", it.toString()) }
    })

    private fun drivers(context: Context, gpu: GpuInfo) = Section("Drivers DroidDeck uses", buildList<Pair<String, String>> {
        add("Driver mode", SessionPrefs.gpuDriverMode(context))
        val turnip = TurnipDriver(context)
        val choice = SessionPrefs.androidDriver(context).ifEmpty { turnip.autoId() }
        add("Display driver", "${turnip.displayName(choice)} ${turnip.driverVersion(choice)}".trim())
        val linux = LinuxVulkanDriverManager(context)
        val id = SessionPrefs.linuxDriver(context)
        add("Game driver", if (id.isEmpty()) "the runtime's own Turnip" else "${linux.getDriverName(id)} ${linux.getDriverVersion(id)}".trim())
        if (gpu.oneUi8Gen2) add("Note", "One UI on an 8 Gen 2: needs the OneUI Turnip build")
    })
}
