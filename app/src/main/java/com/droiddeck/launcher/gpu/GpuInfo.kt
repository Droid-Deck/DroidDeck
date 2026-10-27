package com.droiddeck.launcher.gpu

import android.content.Context
import android.os.Build
import com.droiddeck.launcher.R
import com.droiddeck.launcher.core.FileUtils
import java.io.File

data class GpuInfo(
    val name: String,
    val model: Int,
    val family: Family,
    val soc: String,
    val oneUi8Gen2: Boolean,
    val kgslName: String = "",
    val modelSource: String = "",
    val kgslChipId: String = "",
    val platformGpuModel: Int = 0,
) {
    enum class Family(val label: String) {
        A8XX("Adreno 8xx"),
        A7XX_LOW("Adreno 710/720/722"),
        A7XX("Adreno 7xx"),
        A6XX("Adreno 6xx"),
        ADRENO_UNKNOWN("Adreno"),
        MALI("Arm Mali (experimental)"),
        NOT_ADRENO("Not an Adreno GPU");

        val isAdreno: Boolean get() = this != NOT_ADRENO && this != MALI

        fun label(context: Context): String = when (this) {
            NOT_ADRENO -> context.getString(R.string.gpuinfo_not_adreno)
            MALI -> context.getString(R.string.gpuinfo_mali)
            else -> label
        }
    }

    enum class Support { TESTED, UNTESTED, UNSUPPORTED }

    private val isA850: Boolean get() = model == 850 || platformGpuModel == 850

    val support: Support
        get() = when {
            family == Family.NOT_ADRENO -> Support.UNSUPPORTED
            family == Family.MALI -> Support.UNTESTED
            isA850 -> Support.UNTESTED
            family == Family.A6XX && model == 650 -> Support.TESTED
            family == Family.A8XX -> Support.TESTED
            family == Family.A7XX && model >= 725 -> Support.TESTED
            else -> Support.UNTESTED
        }

    val supportText: String
        get() = when (support) {
            Support.TESTED -> "Supported"
            Support.UNTESTED -> if (family == Family.MALI) "Experimental Mali support: the Linux side renders in software, so games are slow and many will not run"
                else if (isA850) "Experimental Adreno 850 support: requires compatible experimental display and game drivers; not verified on this device"
                else if (family == Family.A7XX_LOW) "Experimental: its drivers are test builds"
                else "Outside tested hardware (Adreno 650, 725 and newer): it may not run"
            Support.UNSUPPORTED -> "Not supported: DroidDeck needs an Adreno (Snapdragon) GPU"
        }

    fun supportText(context: Context): String = context.getString(when (support) {
        Support.TESTED -> R.string.gpuinfo_supported
        Support.UNTESTED -> when {
            family == Family.MALI -> R.string.gpuinfo_mali_experimental
            isA850 -> R.string.gpuinfo_a850_experimental
            family == Family.A7XX_LOW -> R.string.gpuinfo_experimental
            else -> R.string.gpuinfo_below_tested
        }
        Support.UNSUPPORTED -> R.string.gpuinfo_unsupported
    })

    fun displayName(context: Context): String = if (name == UNNAMED) context.getString(R.string.gpu_this_gpu) else name

    companion object {
        private const val UNNAMED = "this GPU"

        fun detect(): GpuInfo {
            val adreno = File("/sys/class/kgsl/kgsl-3d0").exists() || File("/vendor/lib64/hw/vulkan.adreno.so").exists()
            val raw = FileUtils.readString(File("/sys/class/kgsl/kgsl-3d0/gpu_model"))?.trim()
            val chipId = FileUtils.readString(File("/sys/class/kgsl/kgsl-3d0/gpu_chipid"))?.trim().orEmpty()
            val soc = if (adreno) SocNames.label() else ""
            val platformGpuModel = if (adreno) platformModel() else 0
            val (model, source) = if (adreno) modelFromNames(
                raw,
                { VulkanInfo.cachedOrNull()?.get("device") },
                { platformGpuModel },
            ) else 0 to ""
            val vulkanName = if (adreno) null else VulkanInfo.cachedOrNull()?.get("device")
            val family = familyOf(adreno, model, !adreno && maliPresent(vulkanName))
            val samsung = Build.MANUFACTURER.equals("samsung", ignoreCase = true)
            return GpuInfo(
                name = if (family == Family.MALI) vulkanName?.trim()?.ifBlank { null } ?: Build.HARDWARE.ifBlank { UNNAMED }
                    else if (!adreno) Build.HARDWARE.ifBlank { UNNAMED } else if (model > 0) "Adreno $model" else "Adreno",
                model = model, family = family, soc = soc,
                oneUi8Gen2 = samsung && model == 740,
                kgslName = raw.orEmpty(), modelSource = source, kgslChipId = chipId,
                platformGpuModel = platformGpuModel,
            )
        }

        internal fun threeDigits(text: String): Int = Regex("""(?i)\badreno\s*(?:\(TM\)\s*)?(\d{3})(?!\d)""").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0

        internal fun modelFromNames(
            kernelName: String?,
            vulkanName: () -> String?,
            fallback: () -> Int,
        ): Pair<Int, String> {
            kernelName?.let { threeDigits(it) }?.takeIf { it > 0 }?.let { return it to "kernel" }
            vulkanName()?.let { threeDigits(it) }?.takeIf { it > 0 }?.let { return it to "vulkan" }
            return fallback().let { if (it > 0) it to "platform" else 0 to "" }
        }

        private val PLATFORMS = mapOf(
            "msmnile" to 640, "kona" to 650, "lahaina" to 660, "taro" to 730, "cape" to 730,
            "kalama" to 740, "pineapple" to 750, "sun" to 830,
        )

        internal fun platformModel(
            platform: String = systemProperty("ro.board.platform"),
            qtiSocName: String = systemProperty("ro.vendor.qti.soc_name"),
            socModel: String = SocNames.model(),
        ): Int = if (SocNames.normalizedModel(socModel) == "SM8975" || SocNames.normalizedModel(qtiSocName) == "SM8975") 850
            else PLATFORMS[platform.lowercase()] ?: PLATFORMS[qtiSocName.lowercase()] ?: 0

        fun systemProperty(name: String): String = try {
            Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, name) as? String ?: ""
        } catch (_: Exception) {
            ""
        }

        private val MALI_NODES = listOf(
            "/dev/mali0", "/sys/class/misc/mali0", "/vendor/lib64/egl/libGLES_mali.so", "/vendor/lib64/hw/vulkan.mali.so",
        )
        private val MALI_PROPERTIES = listOf("ro.hardware.egl", "ro.hardware.vulkan")
        private val MALI_RENDERER = Regex("""(?i)\b(mali|immortalis)""")

        internal fun maliEvidence(
            exists: (String) -> Boolean,
            property: (String) -> String,
            vulkanName: String?,
        ): Boolean = MALI_NODES.any(exists) ||
            MALI_PROPERTIES.any { property(it).trim().equals("mali", ignoreCase = true) } ||
            (vulkanName != null && MALI_RENDERER.containsMatchIn(vulkanName))

        fun maliPresent(vulkanName: String? = VulkanInfo.cachedOrNull()?.get("device")): Boolean =
            maliEvidence({ File(it).exists() }, ::systemProperty, vulkanName)

        internal fun familyOf(adreno: Boolean, model: Int, mali: Boolean): Family =
            if (!adreno && mali) Family.MALI else familyOf(adreno, model)

        internal fun familyOf(adreno: Boolean, model: Int): Family = when {
            !adreno -> Family.NOT_ADRENO
            model == 0 -> Family.ADRENO_UNKNOWN
            model >= 800 -> Family.A8XX
            model in listOf(710, 720, 722) -> Family.A7XX_LOW
            model >= 700 -> Family.A7XX
            model >= 600 -> Family.A6XX
            else -> Family.ADRENO_UNKNOWN
        }
    }
}
