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
    val verifiedDevice: Boolean = false,
) {
    enum class Family(val label: String) {
        A8XX("Adreno 8xx"),
        A7XX_LOW("Adreno 710/720/722"),
        A7XX("Adreno 7xx"),
        A6XX("Adreno 6xx"),
        ADRENO_UNKNOWN("Adreno"),
        POWERVR("PowerVR"),
        /** Mali on Arm's kbase driver, by the open PanVK build each generation needs. */
        MALI_JM("Mali (G52-G78)"),
        MALI_G610_G710("Mali-G610/G710"),
        MALI_G615("Mali-G615"),
        MALI_G720("Mali-G720"),
        /** Mali-G715/G725/G925: only the one-binary PanVK kbase build covers them (Android 15+). */
        MALI_LATEST("Mali-G715/G725/G925"),
        /** A Mali no open driver build covers: the Vulkan bridge is its only path. */
        MALI_OTHER("Mali"),
        NOT_ADRENO("Not an Adreno GPU");

        val mali: Boolean get() = name.startsWith("MALI_")

        fun label(gpuName: String): String = if (this == POWERVR && gpuName.contains("Xclipse", ignoreCase = true)) "Samsung Xclipse" else label

        fun label(context: Context): String = if (this == NOT_ADRENO) context.getString(R.string.gpuinfo_not_adreno) else label
    }

    enum class Support { TESTED, UNTESTED, UNSUPPORTED }

    private val isA850: Boolean get() = model == 850 || platformGpuModel == 850

    val support: Support
        get() = when {
            family == Family.NOT_ADRENO -> Support.UNSUPPORTED
            isA850 -> Support.UNTESTED
            verifiedDevice -> Support.TESTED
            family == Family.A6XX && model == 650 -> Support.TESTED
            family == Family.A8XX -> Support.TESTED
            family == Family.A7XX && model >= 725 -> Support.TESTED
            else -> Support.UNTESTED
        }

    val supportText: String
        get() = when (support) {
            Support.TESTED -> "Supported"
            Support.UNTESTED -> when {
                family == Family.POWERVR -> if (name.contains("Xclipse", ignoreCase = true)) "Experimental: RADV or the system Xclipse driver"
                    else "System PowerVR driver: compatibility varies by device"
                family == Family.MALI_OTHER -> "Experimental: Mali through the phone's own driver (Venus)"
                family.mali -> "Experimental: open Mali driver (test builds)"
                isA850 -> "Experimental Adreno 850 support: requires compatible experimental display and game drivers; not verified on this device"
                family == Family.A7XX_LOW -> "Experimental: its drivers are test builds"
                else -> "Outside tested hardware (Adreno 650, 725 and newer): it may not run"
            }
            Support.UNSUPPORTED -> "Not supported: no compatible graphics backend is available for this GPU"
        }

    fun supportText(context: Context): String = context.getString(when (support) {
        Support.TESTED -> R.string.gpuinfo_supported
        Support.UNTESTED -> when {
            family == Family.POWERVR -> if (name.contains("Xclipse", ignoreCase = true)) R.string.gpuinfo_xclipse else R.string.gpuinfo_powervr
            isA850 -> R.string.gpuinfo_a850_experimental
            family == Family.A7XX_LOW || family.mali -> R.string.gpuinfo_experimental
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
            // Mali, named by the vendor's Vulkan driver ("Mali-G720-Immortalis MC12") or by kbase.
            val mali = if (adreno || !File("/dev/mali0").exists()) null else maliName()
            val family = familyOf(adreno, model, SystemVulkanDriver.isPowerVr(), mali)
            val samsung = Build.MANUFACTURER.equals("samsung", ignoreCase = true)
            return GpuInfo(
                name = when {
                    family == Family.POWERVR -> SystemVulkanDriver.gpuName().let { gpu ->
                        VulkanInfo.cachedOrNull()?.get("device")?.takeIf { it.contains(gpu, ignoreCase = true) } ?: gpu
                    }
                    mali != null && mali.isNotEmpty() -> mali
                    !adreno -> Build.HARDWARE.ifBlank { UNNAMED }
                    model > 0 -> "Adreno $model"
                    else -> "Adreno"
                },
                model = model, family = family, soc = soc,
                oneUi8Gen2 = samsung && model == 740,
                kgslName = raw.orEmpty(), modelSource = source, kgslChipId = chipId,
                platformGpuModel = platformGpuModel,
                verifiedDevice = adreno && verifiedOn(model, Build.MODEL),
            )
        }

        internal fun verifiedOn(model: Int, device: String): Boolean =
            model == 722 && device.equals("Retroid Pocket Duo", ignoreCase = true)

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

        /**
         * The Mali's name, "" when nothing names it: the vendor Vulkan driver's own name when this
         * process has asked it, kbase's gpuinfo where SELinux lets the app read it (most phones do
         * not), else the GPU the chip is known to carry ([MALI_SOCS]).
         */
        private fun maliName(): String =
            VulkanInfo.cachedOrNull()?.get("device")?.takeIf { it.contains("Mali", ignoreCase = true) }
                ?: FileUtils.readString(File("/sys/class/misc/mali0/device/gpuinfo"))?.trim()?.takeIf { it.contains("Mali", ignoreCase = true) }
                    ?.substringBefore(" ")
                ?: maliOfSoc(socIds())
                ?: ""

        /** The chip's names as the platform gives them: "mt6989", "MT6989", "gs201". */
        private fun socIds(): List<String> = listOfNotNull(
            if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else null,
            systemProperty("ro.board.platform"), systemProperty("ro.soc.model"), Build.HARDWARE, Build.BOARD,
        ).filter { it.isNotBlank() }

        /** Chips whose Mali is known for certain (MediaTek Dimensity / Helio / Kompanio, Google Tensor). */
        private val MALI_SOCS = mapOf(
            "mt6993" to "Mali-G1-Ultra", "mt6991" to "Mali-G925", "mt6989" to "Mali-G720", "mt6899" to "Mali-G720", "mt6985" to "Mali-G715",
            "mt6983" to "Mali-G710", "mt6897" to "Mali-G615", "mt6878" to "Mali-G615", "mt6895" to "Mali-G610",
            "mt6896" to "Mali-G610", "mt6886" to "Mali-G610", "mt6893" to "Mali-G77", "mt6891" to "Mali-G77",
            "mt6889" to "Mali-G77", "mt6885" to "Mali-G77", "mt6877" to "Mali-G68", "mt6853" to "Mali-G57",
            "mt6833" to "Mali-G57", "mt6835" to "Mali-G57", "mt6789" to "Mali-G57", "mt6785" to "Mali-G76",
            "mt6781" to "Mali-G52", "mt6769" to "Mali-G52", "mt8192" to "Mali-G57", "mt8195" to "Mali-G57",
            "gs101" to "Mali-G78", "gs201" to "Mali-G710", "zuma" to "Mali-G715", "zumapro" to "Mali-G715",
            "s5e8835" to "Mali-G68", "s5e8825" to "Mali-G68",
        )

        /** The Mali [ids] name a chip of, "Mali-G720 (by chip mt6989)", or null for an unknown chip. */
        internal fun maliOfSoc(ids: List<String>): String? = ids.firstNotNullOfOrNull { id ->
            MALI_SOCS[id.trim().lowercase()]?.let { "$it (by chip ${id.trim().lowercase()})" }
        }

        /** The number in a Mali's name: 720 for "Mali-G720-Immortalis MC12". */
        fun maliNumber(name: String): Int? =
            Regex("""G(\d{2,3})""", RegexOption.IGNORE_CASE).find(name)?.groupValues?.get(1)?.toIntOrNull()

        /**
         * The Malis the one-binary PanVK kbase build covers (arch v9-v13): G57/G68/G77/G78,
         * G610/G710, G615/G715, G720/G725 and G925. Not the older Bifrost G52/G76.
         */
        fun maliKbaseCovered(name: String): Boolean =
            maliNumber(name) in setOf(57, 68, 77, 78, 610, 710, 615, 715, 720, 725, 925)

        /** The PanVK generation a Mali name belongs to; MALI_OTHER when none of the builds covers it. */
        internal fun maliFamily(name: String): Family {
            val number = maliNumber(name) ?: return Family.MALI_OTHER
            return when (number) {
                31, 51, 52, 57, 68, 71, 72, 76, 77, 78 -> Family.MALI_JM
                610, 710 -> Family.MALI_G610_G710
                615 -> Family.MALI_G615
                720 -> Family.MALI_G720
                715, 725, 925 -> Family.MALI_LATEST
                else -> Family.MALI_OTHER
            }
        }

        internal fun familyOf(adreno: Boolean, model: Int, powerVr: Boolean = false, mali: String? = null): Family = when {
            !adreno && powerVr -> Family.POWERVR
            !adreno && mali != null -> maliFamily(mali)
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
