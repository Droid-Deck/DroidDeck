package com.droiddeck.launcher.gpu

import org.junit.Assert.assertEquals
import org.junit.Test

class GpuModelFallbackTest {
    @Test fun aPlatformCodeNameGivesItsAdreno() {
        assertEquals(750, GpuInfo.platformModel("pineapple"))
        assertEquals(740, GpuInfo.platformModel("KALAMA"))
        assertEquals(830, GpuInfo.platformModel("sun"))
        assertEquals(0, GpuInfo.platformModel("somethingelse"))
        assertEquals(GpuInfo.Family.A7XX, GpuInfo.familyOf(true, GpuInfo.platformModel("pineapple")))
    }
    @Test fun sm8975IsTheOnlyNewSocFallback() {
        assertEquals(850, GpuInfo.platformModel("unknown", "", "SM8975"))
        assertEquals(850, GpuInfo.platformModel("unknown", "", "QTI SM8975"))
        assertEquals(850, GpuInfo.platformModel("unknown", "QTI SM8975", ""))
        assertEquals(850, GpuInfo.platformModel("unknown", "", " qti sm8975 "))
        assertEquals(0, GpuInfo.platformModel("unknown", "", "SM89750"))
        assertEquals(0, GpuInfo.platformModel("unknown", "", "SM8975-PRO"))
        assertEquals(0, GpuInfo.platformModel("unknown", "", "Qualcomm SM8975"))
        assertEquals(0, GpuInfo.platformModel("unknown", "", "SM8850"))
        assertEquals(850, GpuInfo.platformModel("sun", "", "SM8975"))
        assertEquals(830, GpuInfo.platformModel("sun", "", "unknown"))
    }

    @Test fun aKernelModelDoesNotNeedVulkanOrSocFallback() {
        assertEquals(850 to "kernel", GpuInfo.modelFromNames("Adreno850v2",
            { error("Vulkan must not be queried when the kernel identifies the GPU") },
            { error("SoC must not override the kernel model") }))
    }

    @Test fun aMissingKernelModelUsesVulkanBeforeSocFallback() {
        assertEquals(830 to "vulkan", GpuInfo.modelFromNames("Adreno", { "Adreno (TM) 830" },
            { error("SoC must not override the Vulkan model") }))
        assertEquals(850 to "vulkan", GpuInfo.modelFromNames(null, { "Adreno850v2" }, { 0 }))
    }

    @Test fun numericChipIdsCannotHideTheSocFallback() {
        for (name in listOf(null, "", "Adreno", "44051431", "0x2a02be7")) {
            assertEquals(850 to "platform", GpuInfo.modelFromNames(name, { "Adreno" },
                { GpuInfo.platformModel("unknown", "", "QTI SM8975") }))
        }
        assertEquals(0 to "", GpuInfo.modelFromNames(null, { null }, { 0 }))
    }

    @Test fun sm8975StaysExperimentalWhenTheDriverReportsA840() {
        val fallback = GpuInfo.platformModel("sun", "", "SM8975")
        for (kernel in listOf("Adreno", "Adreno (TM) 840")) {
            val (model, source) = GpuInfo.modelFromNames(kernel, { "Adreno (TM) 840" }, { fallback })
            val gpu = GpuInfo("Adreno $model", model, GpuInfo.familyOf(true, model), "SM8975", false,
                modelSource = source, platformGpuModel = fallback)
            assertEquals(840, gpu.model)
            assertEquals(if (kernel == "Adreno") "vulkan" else "kernel", gpu.modelSource)
            assertEquals(GpuInfo.Support.UNTESTED, gpu.support)
            assertEquals("Experimental Adreno 850 support: requires compatible experimental display and game drivers; not verified on this device", gpu.supportText)
        }
    }

    @Test fun anA840WithoutSm8975KeepsItsExistingSupportStatus() {
        for (fallback in listOf(0, 830, 840)) {
            val gpu = GpuInfo("Adreno 840", 840, GpuInfo.Family.A8XX, "", false,
                platformGpuModel = fallback)
            assertEquals(GpuInfo.Support.TESTED, gpu.support)
            assertEquals("Supported", gpu.supportText)
        }
        val otherGpu = GpuInfo("Mali", 0, GpuInfo.Family.NOT_ADRENO, "", false,
            platformGpuModel = 850)
        assertEquals(GpuInfo.Support.UNSUPPORTED, otherGpu.support)
    }
}
