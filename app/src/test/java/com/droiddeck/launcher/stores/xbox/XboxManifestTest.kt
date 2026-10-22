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
    @Test fun signInBridgeRejectsUntrustedAndNonHttpsOrigins() {
        assertTrue(XboxLoginActivity.trusted("https://login.live.com/ppsecure/InlineLogin.srf"))
        for (url in listOf("http://login.live.com/", "https://login.live.com.evil.test/", "https://evil.test@login.live.com/", "https://login.live.com:8443/"))
            assertFalse(XboxLoginActivity.trusted(url))
    }
}
