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
) {
    enum class Family(val label: String) {
        A8XX("Adreno 8xx"),
        A7XX_LOW("Adreno 710/720/722"),
        A7XX("Adreno 7xx"),
        A6XX("Adreno 6xx"),
        ADRENO_UNKNOWN("Adreno"),
        POWERVR("PowerVR"),
        XCLIPSE("Xclipse"),
        MALI("Mali"),
        NOT_ADRENO("Not an Adreno GPU");

        val systemProfile: SystemVulkanDriver.Profile?
            get() = when (this) {
                POWERVR -> SystemVulkanDriver.Profile.POWERVR
                XCLIPSE -> SystemVulkanDriver.Profile.XCLIPSE
                MALI -> SystemVulkanDriver.Profile.MALI
                else -> null
            }

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

    val supportText: String
        get() = when (support) {
            Support.TESTED -> "Supported"
            Support.UNTESTED -> when (family) {
                Family.POWERVR -> "System ${family.systemProfile!!.gpuName} driver: compatibility varies by device"
                Family.XCLIPSE, Family.MALI -> "Experimental system ${family.systemProfile!!.gpuName} driver: compatibility varies by device"
                Family.A7XX_LOW -> "Experimental: its drivers are test builds"
                else -> "Outside tested hardware (Adreno 650, 725 and newer): it may not run"
            }
            Support.UNSUPPORTED -> "Not supported: no compatible graphics backend is available for this GPU"
        }

    fun supportText(context: Context): String = context.getString(when (support) {
        Support.TESTED -> R.string.gpuinfo_supported
        Support.UNTESTED -> when (family) {
            Family.POWERVR -> R.string.gpuinfo_system_driver
            Family.XCLIPSE, Family.MALI -> R.string.gpuinfo_system_experimental
            Family.A7XX_LOW -> R.string.gpuinfo_experimental
            else -> R.string.gpuinfo_below_tested
        }
        Support.UNSUPPORTED -> R.string.gpuinfo_unsupported
    }, family.systemProfile?.gpuName.orEmpty())

    fun displayName(context: Context): String = if (name == UNNAMED) context.getString(R.string.gpu_this_gpu) else name

    companion object {
        private const val UNNAMED = "this GPU"

        fun detect(): GpuInfo {
            val adreno = File("/sys/class/kgsl/kgsl-3d0").exists() || File("/vendor/lib64/hw/vulkan.adreno.so").exists()
            val raw = listOf("/sys/class/kgsl/kgsl-3d0/gpu_model", "/sys/class/kgsl/kgsl-3d0/gpu_chipid")
                .firstNotNullOfOrNull { FileUtils.readString(File(it))?.trim()?.takeIf(String::isNotEmpty) }
            val soc = if (adreno) SocNames.label() else ""
            val fromKernel = raw?.let { threeDigits(it) } ?: 0
            val fromVulkan = if (fromKernel > 0 || !adreno) 0 else VulkanInfo.cachedOrNull()?.get("device")?.let { threeDigits(it) } ?: 0
            val fromPlatform = if (fromKernel > 0 || fromVulkan > 0 || !adreno) 0 else platformModel()
            val model = maxOf(fromKernel, fromVulkan, fromPlatform)
            val source = when {
                fromKernel > 0 -> "kernel"
                fromVulkan > 0 -> "vulkan"
                fromPlatform > 0 -> "platform"
                else -> ""
            }
            val family = familyOf(adreno, model, SystemVulkanDriver.profile())
            val systemGpuName = family.systemProfile?.gpuName
            val samsung = Build.MANUFACTURER.equals("samsung", ignoreCase = true)
            return GpuInfo(
                name = when {
                    systemGpuName != null -> VulkanInfo.cachedOrNull()?.get("device")
                        ?.takeIf { it.contains(systemGpuName, ignoreCase = true) } ?: systemGpuName
                    !adreno -> Build.HARDWARE.ifBlank { UNNAMED }
                    model > 0 -> "Adreno $model"
                    else -> "Adreno"
                },
                model = model, family = family, soc = soc,
                oneUi8Gen2 = samsung && model == 740,
                kgslName = raw.orEmpty(), modelSource = source,
            )
        }

        internal fun threeDigits(text: String): Int = Regex("""(\d{3})""").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0

        private val PLATFORMS = mapOf(
            "msmnile" to 640, "kona" to 650, "lahaina" to 660, "taro" to 730, "cape" to 730,
            "kalama" to 740, "pineapple" to 750, "sun" to 830,
        )

        internal fun platformModel(platform: String = systemProperty("ro.board.platform")): Int =
            PLATFORMS[platform.lowercase()] ?: PLATFORMS[systemProperty("ro.vendor.qti.soc_name").lowercase()] ?: 0

        fun systemProperty(name: String): String = try {
            Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, name) as? String ?: ""
        } catch (_: Exception) {
            ""
        }

        internal fun familyOf(adreno: Boolean, model: Int, systemProfile: SystemVulkanDriver.Profile? = null): Family = when {
            !adreno && systemProfile != null -> when (systemProfile) {
                SystemVulkanDriver.Profile.POWERVR -> Family.POWERVR
                SystemVulkanDriver.Profile.XCLIPSE -> Family.XCLIPSE
                SystemVulkanDriver.Profile.MALI -> Family.MALI
            }
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
