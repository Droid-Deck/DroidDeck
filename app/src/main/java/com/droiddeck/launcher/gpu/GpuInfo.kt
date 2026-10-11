package com.droiddeck.launcher.gpu

import android.content.Context
import android.os.Build
import com.droiddeck.launcher.R
import com.droiddeck.launcher.core.FileUtils
import java.io.File

/**
 * What GPU this is, in the terms the driver lists are sorted by. KGSL names the model
 * ("Adreno740v2", "Adreno825"); the family decides which Turnip builds run on it at all, and the
 * support level is what the app has actually been tested on (Adreno 650, 725 and newer) - an
 * Adreno 610 may start, but it is not "supported" just because it is a Qualcomm chip.
 */
data class GpuInfo(
    /** "Adreno 740", or the vendor's own name when this is not an Adreno. */
    val name: String,
    /** The three-digit Adreno model (740, 825), or 0 when KGSL does not say. */
    val model: Int,
    val family: Family,
    /** The SoC as a person would say it ("Snapdragon 8 Gen 2 (QCS8550)"), or "" when the phone does not say. */
    val soc: String,
    /** Samsung's One UI on an 8 Gen 2: its Turnip needs the OneUI build, or frames tear and flicker. */
    val oneUi8Gen2: Boolean,
    /** KGSL's own name for the GPU, as the kernel spells it ("Adreno740v2", "Adreno33v2"). */
    val kgslName: String = "",
    /** Where [model] came from: "kernel", "vulkan" or "platform", "" when unknown. */
    val modelSource: String = "",
) {
    enum class Family(val label: String) {
        A8XX("Adreno 8xx"),
        /** Adreno 710/720/722: gen 7 cores cut down enough to need their own patches. */
        A7XX_LOW("Adreno 710/720/722"),
        A7XX("Adreno 7xx"),
        A6XX("Adreno 6xx"),
        /** An Adreno whose model KGSL does not give: treated as the newest family it could be. */
        ADRENO_UNKNOWN("Adreno"),
        NOT_ADRENO("Not an Adreno GPU");

        /** [label] in the app's language: the Adreno families are names, the rest is words. */
        fun label(context: Context): String = if (this == NOT_ADRENO) context.getString(R.string.gpuinfo_not_adreno) else label
    }

    enum class Support { TESTED, UNTESTED, UNSUPPORTED }

    val support: Support
        get() = when {
            family == Family.NOT_ADRENO -> Support.UNSUPPORTED
            family == Family.A6XX && model == 650 -> Support.TESTED
            family == Family.A8XX -> Support.TESTED
            family == Family.A7XX && model >= 725 -> Support.TESTED
            else -> Support.UNTESTED
        }

    /** One line for the device card and the system check, in English for the device report. */
    val supportText: String
        get() = when (support) {
            Support.TESTED -> "Supported"
            Support.UNTESTED -> if (family == Family.A7XX_LOW) "Experimental: its drivers are test builds"
                else "Outside tested hardware (Adreno 650, 725 and newer): it may not run"
            Support.UNSUPPORTED -> "Not supported: DroidDeck needs an Adreno (Snapdragon) GPU"
        }

    /** [supportText] in the app's language. */
    fun supportText(context: Context): String = context.getString(when (support) {
        Support.TESTED -> R.string.gpuinfo_supported
        Support.UNTESTED -> if (family == Family.A7XX_LOW) R.string.gpuinfo_experimental else R.string.gpuinfo_below_tested
        Support.UNSUPPORTED -> R.string.gpuinfo_unsupported
    })

    /** [name] for the screen: the stand-in for a GPU the device does not name is in the app's language. */
    fun displayName(context: Context): String = if (name == UNNAMED) context.getString(R.string.gpu_this_gpu) else name

    companion object {
        /** [name] when the device names no GPU; English, as the device report shows it. */
        private const val UNNAMED = "this GPU"

        fun detect(): GpuInfo {
            val adreno = com.droiddeck.launcher.core.DeviceSupport.adreno()
            val raw = listOf("/sys/class/kgsl/kgsl-3d0/gpu_model", "/sys/class/kgsl/kgsl-3d0/gpu_chipid")
                .firstNotNullOfOrNull { FileUtils.readString(File(it))?.trim()?.takeIf(String::isNotEmpty) }
            // Where vendors put the chip's model, named when it is known: "Snapdragon 8 Gen 2 (QCS8550)".
            val soc = if (adreno) SocNames.label() else ""
            val fromKernel = raw?.let { parseModel(it) } ?: 0
            // Some kernels name the GPU without its model (AYANEO's Pocket FIT: "Adreno33v2"). The
            // Vulkan driver's own name, when this process has asked it, then the platform's code
            // name stand in - the GPU is the same on every phone of a platform.
            val fromVulkan = if (fromKernel > 0 || !adreno) 0 else VulkanInfo.cachedOrNull()?.get("device")?.let { parseModel(it) } ?: 0
            val fromPlatform = if (fromKernel > 0 || fromVulkan > 0 || !adreno) 0 else platformModel()
            val model = maxOf(fromKernel, fromVulkan, fromPlatform)
            val source = when {
                fromKernel > 0 -> "kernel"
                fromVulkan > 0 -> "vulkan"
                fromPlatform > 0 -> "platform"
                else -> ""
            }
            val family = familyOf(adreno, model)
            val samsung = Build.MANUFACTURER.equals("samsung", ignoreCase = true)
            val isX1 = model == 741 || raw?.contains("X1-", ignoreCase = true) == true
            val gpuDisplayName = when {
                !adreno -> Build.HARDWARE.ifBlank { UNNAMED }
                isX1 -> "Adreno X1-85"
                model > 0 -> "Adreno $model"
                else -> "Adreno"
            }
            return GpuInfo(
                name = gpuDisplayName,
                model = model, family = family, soc = soc,
                oneUi8Gen2 = samsung && model == 740,
                kgslName = raw.orEmpty(), modelSource = source,
            )
        }

        internal fun parseModel(text: String): Int {
            if (Regex("""(?i)X1[-_]85|X1[-_]45|Adreno\s*741""").containsMatchIn(text)) return 741
            return threeDigits(text)
        }

        private fun threeDigits(text: String): Int = Regex("""(\d{3})""").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0

        /** Qualcomm platform code names whose GPU is known for certain. */
        private val PLATFORMS = mapOf(
            "msmnile" to 640, "kona" to 650, "lahaina" to 660, "taro" to 730, "cape" to 730,
            "kalama" to 740, "pineapple" to 750, "sun" to 830,
            "hamoa" to 741, "sc8380xp" to 741, "x1e80100" to 741, "purwa" to 741,
        )

        internal fun platformModel(platform: String = systemProperty("ro.board.platform")): Int =
            PLATFORMS[platform.lowercase()] ?: PLATFORMS[systemProperty("ro.vendor.qti.soc_name").lowercase()] ?: 0

        /** android.os.SystemProperties.get, which apps may read but not call directly. */
        fun systemProperty(name: String): String = try {
            Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, name) as? String ?: ""
        } catch (_: Exception) {
            ""
        }

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
