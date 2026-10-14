package com.droiddeck.launcher.gpu

import android.content.Context
import com.droiddeck.launcher.runtime.LinuxRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class LosslessTest {
    private lateinit var context: Context
    private lateinit var flag: File

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("lossless", Context.MODE_PRIVATE).edit().clear().commit()
        flag = File(LinuxRuntime.rootDir(context), "root/.config/droiddeck/lossless-owned")
        flag.delete()
    }

    @Test fun nothingIsOwnedOrReadyUntilTheSessionSaysSo() {
        assertEquals(Lossless.State.NONE, Lossless.state(context))
        assertNull(Lossless.cacheFile(context))
    }

    @Test fun theSessionsOwnershipFlagIsRememberedForGood() {
        flag.parentFile!!.mkdirs()
        flag.writeText("")
        assertTrue(Lossless.owned(context))
        flag.delete()
        assertTrue(Lossless.owned(context))
    }

    @Test fun importIsRefusedWithoutOwnership() {
        val dll = File(context.cacheDir, "Lossless.dll").apply { writeText("not a dll") }
        assertFalse(Lossless.owned(context))
        assertEquals(Lossless.ImportResult.NOT_OWNED, Lossless.import(context, dll))
    }
}
