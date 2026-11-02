package com.droiddeck.launcher.core

import org.junit.runners.model.FrameworkMethod
import org.robolectric.RobolectricTestRunner
import org.robolectric.internal.bytecode.InstrumentationConfiguration

open class DroidDeckTestRunner(testClass: Class<*>) : RobolectricTestRunner(testClass) {
    override fun createClassLoaderConfig(method: FrameworkMethod): InstrumentationConfiguration =
        InstrumentationConfiguration.Builder(super.createClassLoaderConfig(method))
            .doNotAcquirePackage("com.github.luben.zstd.")
            .doNotAcquirePackage("org.apache.commons.compress.")
            .doNotAcquirePackage("org.apache.commons.io.")
            .doNotAcquirePackage("org.tukaani.xz.")
            .build()
}
