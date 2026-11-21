package com.droiddeck.launcher.gpu

import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@Implements(value = GpuInfo.Companion::class, isInAndroidSdk = false)
class ShadowGpuInfo {
    @Implementation
    fun detect(): GpuInfo = checkNotNull(detectedGpu) { "Set the detected GPU in test setup" }

    companion object {
        private var detectedGpu: GpuInfo? = null

        fun setGpu(family: GpuInfo.Family, name: String, model: Int = 0) {
            detectedGpu = GpuInfo(name, model, family, "", false)
        }

        // Tests call this from @After because Robolectric runs resetters through a generated ShadowProvider.
        fun reset() {
            detectedGpu = null
        }
    }
}
