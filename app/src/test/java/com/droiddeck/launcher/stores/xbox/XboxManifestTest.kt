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
    @Test fun aLauncherMenuIsSkippedForTheProgramItNames() {
        val root = temporary.newFolder()
        java.io.File(root, "EmuMenu").mkdir()
        java.io.File(root, "EmuMenu/EmuMenu.exe").writeText("menu")
        java.io.File(root, "Emu.exe").writeText("emulator")
        java.io.File(root, "MicrosoftGame.config").writeText("<Game><ExecutableList><Executable Name=\"EmuMenu\\EmuMenu.exe\"/></ExecutableList></Game>")
        java.io.File(root, "EmuMenu/EmuMenuLaunchArguments.txt").writeText("exe=..\\Emu.exe\r\ntitlename=Game\r\n")
        assertEquals("Emu.exe", XboxInstaller.executable(root))
        java.io.File(root, "EmuMenu/EmuMenuLaunchArguments.txt").writeText("exe=..\\..\\outside.exe\r\n")
        assertEquals("EmuMenu/EmuMenu.exe", XboxInstaller.executable(root))
        java.io.File(root, "EmuMenu/EmuMenuLaunchArguments.txt").writeText("titlename=Game\r\n")
        assertEquals("EmuMenu/EmuMenu.exe", XboxInstaller.executable(root))
        java.io.File(root, "EmuMenu/EmuMenuLaunchArguments.txt").writeText("exe=..\\EMU.EXE\r\n")
        assertEquals("Emu.exe", XboxInstaller.executable(root))
        java.io.File(root, "emu.exe").writeText("other")
        if (root.listFiles().orEmpty().count { it.name.equals("emu.exe", true) } == 2)
            assertEquals("EmuMenu/EmuMenu.exe", XboxInstaller.executable(root))
    }
    @Test fun anUnreadableForwardingFileFallsBackToTheDeclaredProgram() {
        val root = temporary.newFolder()
        java.io.File(root, "EmuMenu").mkdir()
        java.io.File(root, "EmuMenu/EmuMenu.exe").writeText("menu")
        java.io.File(root, "Emu.exe").writeText("emulator")
        java.io.File(root, "MicrosoftGame.config").writeText("<Game><ExecutableList><Executable Name=\"EmuMenu\\EmuMenu.exe\"/></ExecutableList></Game>")
        val arguments = java.io.File(root, "EmuMenu/EmuMenuLaunchArguments.txt").apply { writeText("exe=..\\Emu.exe\r\n") }
        org.junit.Assume.assumeTrue(arguments.setReadable(false, false) && !arguments.canRead())
        try { assertEquals("EmuMenu/EmuMenu.exe", XboxInstaller.executable(root)) } finally { arguments.setReadable(true, false) }
    }
    @Test fun aForwardedProgramStaysRelativeWhenTheFolderIsReachedThroughALink() {
        val real = temporary.newFolder()
        java.io.File(real, "EmuMenu").mkdir()
        java.io.File(real, "EmuMenu/EmuMenu.exe").writeText("menu")
        java.io.File(real, "Emu.exe").writeText("emulator")
        java.io.File(real, "MicrosoftGame.config").writeText("<Game><ExecutableList><Executable Name=\"EmuMenu\\EmuMenu.exe\"/></ExecutableList></Game>")
        java.io.File(real, "EmuMenu/EmuMenuLaunchArguments.txt").writeText("exe=..\\Emu.exe\r\n")
        val link = java.io.File(temporary.root, "linked")
        val linked = runCatching { java.nio.file.Files.createSymbolicLink(link.toPath(), real.toPath()) }.isSuccess
        org.junit.Assume.assumeTrue(linked)
        assertEquals("Emu.exe", XboxInstaller.executable(link))
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
    @Test fun aGameCorePackageIsReadFromItsConfigBesideTheGeneratedAppxManifest() {
        val root = temporary.newFolder()
        java.io.File(root, "Game.exe").writeText("encrypted")
        java.io.File(root, "Other.exe").writeText("encrypted")
        java.io.File(root, "MicrosoftGame.config").writeText("""<Game configVersion="1"><Identity Name="Test.Game" Publisher="CN=Test" Version="1.2.3.0"/>
            <ExecutableList><Executable Name="Game.exe" TargetDeviceFamily="PC"/></ExecutableList><StoreId>9AAAAAAAAAAA</StoreId></Game>""")
        java.io.File(root, "appxmanifest.xml").writeText("""<?xml version="1.0" encoding="utf-8"?>
            <Package xmlns="http://schemas.microsoft.com/appx/manifest/foundation/windows10">
            <Identity Name="Generated.Game" Publisher="CN=Generated" Version="9.9.9.0"/>
            <Applications><Application Id="Game" Executable="Other.exe" EntryPoint="Game.App"/></Applications></Package>""")
        val info = XboxManifest.read(root)
        assertEquals("Test.Game", info.name)
        assertEquals("1.2.3.0", info.version)
        assertEquals("9AAAAAAAAAAA", info.storeId)
        assertEquals("Game.exe", XboxInstaller.executable(root))
        assertFalse(XboxManifest.needsAppModel(root))
    }
    @Test fun aFolderWithoutOneManifestIsStillRefused() {
        val empty = temporary.newFolder()
        java.io.File(empty, "Game.exe").writeText("encrypted")
        assertThrows(IllegalArgumentException::class.java) { XboxManifest.read(empty) }
        val twice = temporary.newFolder()
        java.io.File(twice, "Game.exe").writeText("encrypted")
        java.io.File(twice, "MicrosoftGame.config").writeText("<Game><Executable Name=\"Game.exe\"/></Game>")
        java.io.File(twice, "microsoftgame.CONFIG").writeText("<Game><Executable Name=\"Game.exe\"/></Game>")
        org.junit.Assume.assumeTrue(twice.list().orEmpty().size == 3)
        assertThrows(IllegalArgumentException::class.java) { XboxManifest.read(twice) }
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
    private fun legacy(application: String): java.io.File {
        val root = temporary.newFolder()
        java.io.File(root, "Game.exe").writeText("encrypted")
        java.io.File(root, "AppxManifest.xml").writeText("""<?xml version="1.0" encoding="utf-8"?>
            <Package xmlns="http://schemas.microsoft.com/appx/manifest/foundation/windows10"
                     xmlns:uap10="http://schemas.microsoft.com/appx/manifest/uap/windows10/10">
            <Identity Name="Test.Game" Publisher="CN=Test" Version="1.0.0.0"/>
            <Applications>$application</Applications></Package>""")
        return root
    }
    @Test fun windowsAppRuntimeBehaviorNeedsAnAppModelWithoutAnEntryPoint() {
        val root = legacy("""<Application Id="Game" Executable="Game.exe" uap10:RuntimeBehavior="windowsApp"/>""")
        assertEquals("windowsApp", XboxManifest.read(root).runtimeBehavior)
        assertEquals("", XboxManifest.read(root).entryPoint)
        assertTrue(XboxManifest.needsAppModel(root))
    }
    @Test fun classicRuntimeBehaviorsStartTheExecutableDirectly() {
        for (behavior in listOf("packagedClassicApp", "win32App")) {
            val root = legacy("""<Application Id="Game" Executable="Game.exe" uap10:RuntimeBehavior="$behavior"/>""")
            assertEquals(behavior, XboxManifest.read(root).runtimeBehavior)
            assertFalse(behavior, XboxManifest.needsAppModel(root))
            val declared = legacy("""<Application Id="Game" Executable="Game.exe" EntryPoint="Game.App" uap10:RuntimeBehavior="$behavior"/>""")
            assertFalse(behavior, XboxManifest.needsAppModel(declared))
        }
    }
    @Test fun anEntryPointStillDecidesWithoutARuntimeBehavior() {
        assertTrue(XboxManifest.needsAppModel(legacy("""<Application Id="Game" Executable="Game.exe" EntryPoint="Game.App"/>""")))
        assertFalse(XboxManifest.needsAppModel(legacy("""<Application Id="Game" Executable="Game.exe"/>""")))
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
