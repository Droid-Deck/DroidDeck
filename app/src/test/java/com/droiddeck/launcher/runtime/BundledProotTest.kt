package com.droiddeck.launcher.runtime

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import com.droiddeck.launcher.BuildConfig
import com.droiddeck.launcher.core.AddonPack
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import com.droiddeck.launcher.core.DroidDeckTestRunner as RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 37], manifest = Config.NONE)
class BundledProotTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun context(): Pair<Context, File> {
        val base = RuntimeEnvironment.getApplication()
        val native = temporary.newFolder("native")
        val files = temporary.newFolder("files")
        val info = ApplicationInfo(base.applicationInfo).apply { nativeLibraryDir = native.path }
        return object : ContextWrapper(base) {
            override fun getApplicationInfo() = info
            override fun getFilesDir() = files
        } to native
    }

    @Test fun onlyAMatchedInstalledPairIsReportedAsBundledProot() {
        val (context, native) = context()
        File(native, "libproot.so").writeText("executable")
        assertFalse(LinuxRuntime.patchedProot(context))
        File(native, "libproot-loader.so").writeText("loader")
        assertTrue(LinuxRuntime.patchedProot(context))
        assertEquals(File(native, "libproot.so"), LinuxRuntime.prootBinary(context))
        assertEquals(File(native, "libproot-loader.so"), LinuxRuntime.prootLoader(context))
        assertEquals(LinuxRuntime.PROOT_BUNDLED, LinuxRuntime.prootSource(context))
        assertTrue(ProotFastPath.allows(true, false, LinuxRuntime.prootSource(context)))
    }

    @Test fun downloadedPackCannotReplaceTheKernelExecutableLoaderPair() {
        val (context, native) = context()
        for (name in listOf("libproot.so", "libproot-loader.so")) File(native, name).writeText("installed")
        val pack = File(context.filesDir, "addons/proot/${BuildConfig.PACK_PROOT_SHA256}").apply { mkdirs() }
        for (name in listOf("libproot.so", "libproot-loader.so")) File(pack, name).writeText("downloaded")
        File(pack, AddonPack.COMPLETE).writeText(BuildConfig.PACK_PROOT_SHA256)
        assertTrue(AddonPack.PROOT.installed(context))
        assertEquals(native, LinuxRuntime.prootBinary(context).parentFile)
        assertEquals(native, LinuxRuntime.prootLoader(context).parentFile)
    }
}
