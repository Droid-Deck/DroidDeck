package com.droiddeck.launcher.session

import com.droiddeck.launcher.session.WinComponents.Support
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WinComponentsSupportTest {
    private val base = "https://github.com/The412Banner/winlator-contents/releases/download/system-libraries-v1"

    private fun step(action: String, vararg pairs: Pair<String, String>) =
        WinComponents.Step(action, JSONObject().apply { pairs.forEach { (k, v) -> put(k, v) } })

    private fun component(name: String, vararg steps: WinComponents.Step) =
        WinComponents.Component(name, "", "", "ready", emptyList(), steps.toList())

    private fun support(c: WinComponents.Component) = WinComponents.support(c, mapOf(c.name to c))

    @Test fun windowsInstallerPackagesInstallHere() {
        assertEquals(Support.READY, support(component("xna40", step("install_msi", "url" to "$base/xna40.msi"))))
        // The catalog names some .msi packages install_exe.
        assertEquals(Support.READY, support(component("XLiveRedist",
            step("install_exe", "url" to "$base/XLiveRedist.msi", "file_name" to "XLiveRedist1.2.0238.msi"))))
        assertEquals(Support.READY, support(component("msxml3", step("delete_dlls", "dest" to "win32"),
            step("override_dll", "dll" to "msxml3", "type" to "native"), step("install_msi", "url" to "$base/msxml3.msi"))))
        assertEquals(Support.READY, support(component("powershell_core",
            step("install_msi", "url" to "$base/ps-x86.msi"), step("install_msi", "url" to "$base/ps-x64.msi"))))
    }

    @Test fun otherInstallersStillWait() {
        assertEquals(Support.NEEDS_INSTALLER, support(component("dotnet48", step("install_exe", "url" to "$base/ndp48.exe"))))
        // An uninstall step first (wine-mono) is not something done here.
        assertEquals(Support.NEEDS_INSTALLER, support(component("mono", step("uninstall"),
            step("install_msi", "url" to "$base/mono.msi"))))
        assertEquals(Support.NEEDS_INSTALLER, support(component("plain", step("install_msi", "url" to "http://example.com/a.msi"))))
    }

    @Test fun whatProtonShipsIsNotOffered() {
        assertEquals(Support.UNSUPPORTED, support(component("gecko", step("install_msi", "url" to "$base/gecko.msi"))))
    }
}
