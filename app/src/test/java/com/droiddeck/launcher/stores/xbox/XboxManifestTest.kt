package com.droiddeck.launcher.stores.xbox

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [33], application = android.app.Application::class)
class XboxManifestTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun resolvesTheManifestExecutableWithWindowsLetterCasing() {
        val root = temporary.newFolder()
        java.io.File(root, "Bin").mkdir()
        java.io.File(root, "Bin/Game.exe").writeText("encrypted")
        java.io.File(root, "MicrosoftGame.Config").writeText("<Game><ExecutableList><Executable Name=\"bin\\GAME.EXE\" TargetDeviceFamily=\"PC\"/></ExecutableList></Game>")
        assertEquals("Bin/Game.exe", XboxInstaller.executable(root))
    }
    @Test fun rejectsTraversalAndExternalEntities() {
        for (xml in listOf("<Game><Executable Name=\"../escape.exe\"/></Game>",
            "<!DOCTYPE Game [<!ENTITY external SYSTEM 'file:///etc/passwd'>]><Game>&external;</Game>",
            "<!DOCTYPE Game SYSTEM 'https://example.test/manifest.dtd'><Game/>",
            "<!DOCTYPE Game [<!ENTITY internal 'test'>]><Game/>")) {
            val root = temporary.newFolder()
            java.io.File(root, "MicrosoftGame.config").writeText(xml)
            assertThrows(Exception::class.java) { XboxInstaller.executable(root) }
        }
    }
    @Test fun androidManifestParserPreservesIdentityAndSelectsTheRetailPcExecutable() {
        val root = temporary.newFolder()
        java.io.File(root, "Retail.exe").writeText("encrypted")
        java.io.File(root, "MicrosoftGame.Config").writeText("""<?xml version="1.0" encoding="utf-8"?>
            <Game configVersion="1"><!-- package comment -->
            <Identity Name="Test.Game" Publisher="CN=A &amp; B" Version="1.2.3.0"/>
            <ExecutableList><Executable Name="Console.exe" TargetDeviceFamily="XboxOne"/>
            <Executable Name="Dev.exe" TargetDeviceFamily="PC" IsDevOnly="true"/>
            <Executable Name="Retail.exe" TargetDeviceFamily="PC" IsDevOnly="false"/></ExecutableList>
            <StoreId>9AAAAAAAAAAA</StoreId><RelatedProduct>9BBBBBBBBBBB</RelatedProduct>
            <MainPackageDependency Name="Test.Base"/></Game>""")
        val info = XboxManifest.read(root)
        assertEquals("9AAAAAAAAAAA", info.storeId)
        assertEquals("Test.Game", info.name)
        assertEquals("CN=A & B", info.publisher)
        assertEquals("1.2.3.0", info.version)
        assertEquals("Test.Base", info.dependency)
        assertEquals(listOf("9BBBBBBBBBBB"), info.related)
        assertEquals(listOf("Retail.exe"), info.executables)
        assertEquals("Retail.exe", XboxManifest.executable(root))
    }
    @Test fun legacyPackageManifestNamesItsExecutableTheSameWay() {
        // A legacy EAppx package carries AppxManifest.xml and has no config; the
        // launcher must still find the identity and the launch path, or an install
        // that completed cannot be registered or started.
        val root = temporary.newFolder()
        java.io.File(root, "ForzaHorizon4.exe").writeText("encrypted")
        java.io.File(root, "AppxManifest.xml").writeText("""<?xml version="1.0" encoding="utf-8"?>
            <Package xmlns="http://schemas.microsoft.com/appx/manifest/foundation/windows10"
                     xmlns:uap="http://schemas.microsoft.com/appx/manifest/uap/windows10">
            <Identity Name="Microsoft.SunriseBaseGame" Publisher="CN=Microsoft Corporation" Version="2023.1122.1704.2"/>
            <Applications><Application Id="Game" Executable="ForzaHorizon4.exe" EntryPoint="Windows.FullTrustApplication">
            <uap:VisualElements DisplayName="Forza Horizon 4"/></Application></Applications></Package>""")
        val info = XboxManifest.read(root)
        assertEquals("Microsoft.SunriseBaseGame", info.name)
        assertEquals("2023.1122.1704.2", info.version)
        assertEquals(listOf("ForzaHorizon4.exe"), info.executables)
        assertEquals("Windows.FullTrustApplication", info.entryPoint)
        assertEquals("ForzaHorizon4.exe", XboxManifest.executable(root))
        assertFalse(XboxManifest.needsAppModel(root))
    }
    @Test fun uwpEntryPointNeedsAnAppModelHost() {
        // Forza Horizon 4's Store build declares Forza.App, so its executable is a view
        // provider rather than a program: starting it directly only crashes.
        val root = temporary.newFolder()
        java.io.File(root, "ForzaHorizon4.exe").writeText("encrypted")
        java.io.File(root, "AppxManifest.xml").writeText("""<?xml version="1.0" encoding="utf-8"?>
            <Package xmlns="http://schemas.microsoft.com/appx/manifest/foundation/windows10">
            <Identity Name="Microsoft.SunriseBaseGame" Publisher="CN=Microsoft Corporation" Version="1.478.564.2"/>
            <Applications><Application Id="SunriseReleaseFinal" Executable="ForzaHorizon4.exe" EntryPoint="Forza.App"/></Applications></Package>""")
        assertEquals("Forza.App", XboxManifest.read(root).entryPoint)
        assertTrue(XboxManifest.needsAppModel(root))
    }
    @Test fun aTitleWithoutALegacyManifestNeverNeedsAnAppModel() {
        val root = temporary.newFolder()
        java.io.File(root, "Game.exe").writeText("encrypted")
        java.io.File(root, "MicrosoftGame.config").writeText("<Game><ExecutableList><Executable Name=\"Game.exe\"/></ExecutableList></Game>")
        assertEquals("", XboxManifest.read(root).entryPoint)
        assertFalse(XboxManifest.needsAppModel(root))
        assertFalse(XboxManifest.needsAppModel(temporary.newFolder()))
    }
    @Test fun signInBridgeRejectsUntrustedAndNonHttpsOrigins() {
        assertTrue(XboxLoginActivity.trusted("https://login.live.com/ppsecure/InlineLogin.srf"))
        for (url in listOf("http://login.live.com/", "https://login.live.com.evil.test/", "https://evil.test@login.live.com/", "https://login.live.com:8443/"))
            assertFalse(XboxLoginActivity.trusted(url))
    }
}
