package com.droiddeck.launcher.session

import android.content.Context
import com.droiddeck.launcher.runtime.LinuxRuntime
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class PowerVrQueuedComponentsTest {
    @Test fun managedDxvkQueueRemainsVisibleAndCanBeCancelled() {
        val context: Context = RuntimeEnvironment.getApplication()
        val root = LinuxRuntime.rootDir(context)
        root.deleteRecursively()
        val data = File(context.filesDir, "components")
        data.deleteRecursively()
        data.mkdirs()
        val proton = File(root, "root/.local/share/Steam/compatibilitytools.d/test-proton")
        File(proton, "files/lib/wine").mkdirs()
        File(proton, "proton").writeText("# test Proton\n")
        File(proton, "version").writeText("1 test-build\n")
        File(data, "state.json").writeText(JSONObject().put("queued", JSONObject().put("test-proton",
            JSONObject().put("dxvk", JSONObject().put("kind", "package").put("file", "chosen.wcp").put("label", "chosen DXVK"))
        )).toString())

        assertTrue(ComponentsManager.applyQueued(context) { ComponentsManager.runtimeManaged(it, true) }.isEmpty())
        val deferred = ComponentsManager.snapshot(context).protons.single().components.getValue("dxvk")
        assertEquals("chosen DXVK", deferred.queued)

        ComponentsManager.cancelQueued(context, "test-proton", "dxvk")
        assertNull(ComponentsManager.snapshot(context).protons.single().components.getValue("dxvk").queued)
    }
}
