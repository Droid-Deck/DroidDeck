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
    private val base = "https://github.com/Droid-Deck/DroidDeck-Components/releases/download/system-libraries-v1"

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

    @Test fun installerWrappersInstallHere() {
        // A WiX bundle, a self-extracting cabinet or 7-Zip archive: the packages inside are laid out.
        assertEquals(Support.READY, support(component("vcredist2022",
            step("install_exe", "url" to "$base/vcredist2022__VC_redist.x86.exe", "file_name" to "VC_redist.x86.exe"),
            step("install_exe", "url" to "$base/vcredist2022__VC_redist.x64.exe", "file_name" to "VC_redist.x64.exe"),
            step("override_dll", "dll" to "vcruntime140", "type" to "native,builtin"))))
        // .NET Framework: "uninstall Wine Mono", the Windows version, the installer, a registry key.
        assertEquals(Support.READY, support(component("dotnet40",
            step("uninstall", "file_name" to "Wine Mono"), step("set_windows", "version" to "win7"),
            step("install_exe", "url" to "https://download.microsoft.com/x/dotNetFx40_Full_x86_x64.exe", "file_name" to "dotNetFx40_Full_x86_x64.exe"),
            step("set_windows", "version" to "win10"),
            step("set_register_key", "key" to "HKLM\\\\Software\\\\Microsoft\\\\NET Framework Setup\\\\NDP\\\\v4\\\\Full", "value" to "Install", "data" to "0001", "type" to "REG_DWORD"),
            step("override_dll", "dll" to "mscoree", "type" to "native"))))
    }

    @Test fun nestedCatalogKeysAreRead() {
        // A few .NET entries keep url and file_name under "environment".
        val nested = WinComponents.Step("install_exe", JSONObject().put("environment", JSONObject()
            .put("WINEDLLOVERRIDES", "fusion=b").put("file_name", "ndp48-x86-x64-allos-enu.exe")
            .put("url", "https://download.visualstudio.microsoft.com/x/ndp48-x86-x64-allos-enu.exe")))
        assertEquals("ndp48-x86-x64-allos-enu.exe", nested.str("file_name"))
        assertEquals(Support.READY, support(component("dotnet48", nested)))
    }

    @Test fun cabinetPicksInstallHere() {
        assertEquals(Support.READY, support(component("xact",
            step("download_archive", "url" to "$base/directx_Jun2010_redist.exe", "file_name" to "directx_Jun2010_redist.exe"),
            step("get_from_cab", "source" to "directx_Jun2010_redist.exe", "file_name" to "*XACT_x86*.cab", "dest" to "temp/XACT_x86/"),
            step("get_from_cab", "source" to "XACT_x86/*.cab", "file_name" to "xactengine*.dll", "dest" to "win32/"),
            step("override_dll", "dll" to "xactengine3_7", "type" to "native,builtin"),
            step("register_dll", "dlls" to ""))))
    }

    @Test fun recordedInstallersInstallHere() {
        val snapshot = "https://github.com/Droid-Deck/DroidDeck-Components/releases/download/component-snapshots-v1/K-Lite.snapshot.json"
        // A setup program with a recording of its result installs from the recording.
        val klite = WinComponents.Component("K-Lite", "", "", "ready", emptyList(),
            listOf(step("install_exe", "url" to "$base/K-Lite.exe", "file_name" to "K-Lite_1960.exe")), snapshot)
        assertEquals(Support.READY, WinComponents.support(klite, mapOf(klite.name to klite)))
        // Only a recording on the release counts.
        val elsewhere = WinComponents.Component("K-Lite", "", "", "ready", emptyList(), klite.steps, "http://example.com/K-Lite.snapshot.json")
        assertEquals(Support.NEEDS_INSTALLER, WinComponents.support(elsewhere, mapOf(elsewhere.name to elsewhere)))
        // Its prerequisites still have to be installable.
        val sp1 = WinComponents.Component("dotnet20sp1", "", "", "ready", listOf("dotnet20"), emptyList(), snapshot.replace("K-Lite", "dotnet20sp1"))
        val base20 = WinComponents.Component("dotnet20", "", "", "ready", emptyList(),
            listOf(step("install_exe", "url" to "$base/dotnet20__dotnetfx.exe", "file_name" to "dotnetfx.exe")))
        assertEquals(Support.NEEDS_INSTALLER, WinComponents.support(sp1, mapOf("dotnet20sp1" to sp1, "dotnet20" to base20)))
    }

    @Test fun setupProgramsStillWait() {
        // NSIS / InnoSetup installers hold no package to lay out.
        assertEquals(Support.NEEDS_INSTALLER, support(component("K-Lite",
            step("install_exe", "url" to "$base/K-Lite.exe", "file_name" to "K-Lite_1960.exe"))))
        assertEquals(Support.NEEDS_INSTALLER, support(component("plain", step("install_msi", "url" to "http://example.com/a.msi"))))
    }

    @Test fun whatProtonShipsIsNotOffered() {
        assertEquals(Support.UNSUPPORTED, support(component("gecko", step("install_msi", "url" to "$base/gecko.msi"))))
        assertEquals(Support.UNSUPPORTED, support(component("mono", step("uninstall"), step("install_msi", "url" to "$base/mono.msi"))))
        assertEquals(Support.UNSUPPORTED, support(component("mono-10.4.1", step("install_msi", "url" to "$base/mono.msi"))))
        assertEquals(Support.UNSUPPORTED, support(component("cjkfonts", step("install_fonts", "url" to "temp/SourceHanSans.ttc"))))
        assertEquals(Support.UNSUPPORTED, support(component("powershell", step("copy_file", "file_name" to "profile.ps1", "dest" to "win64"))))
        assertEquals(Support.UNSUPPORTED, support(component("VulkanRT", step("install_exe", "url" to "$base/VulkanRT.exe"))))
        assertEquals(Support.UNSUPPORTED, support(component("ie8_kb2936068", step("install_exe", "url" to "$base/ie8_kb2936068.exe"))))
        assertEquals(Support.UNSUPPORTED, support(component("oalinst", step("install_exe", "url" to "$base/oalinst.exe"))))
        assertEquals(Support.UNSUPPORTED, support(component("webview2", step("install_exe", "url" to "$base/webview2.exe"))))
    }
}
