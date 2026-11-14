package com.droiddeck.launcher.stores.rockstar

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class RockstarMetadataTest {
    @Test fun findsRootMetadataAfterManyOtherFiles() {
        val root = java.nio.file.Files.createTempDirectory("rockstar-many-files").toFile()
        try {
            repeat(300) { java.io.File(root, "asset-$it.dat").writeText("asset") }
            java.io.File(root, "title.rgl").writeBytes(container("""{"titleId":"game","rosTitleId":123,"principalFileName":"Game.exe"}"""))
            assertEquals("game", RockstarMetadata.find(root)?.id)
            java.io.File(root, "title.rgl").delete()
            java.io.File(root, "Title.RGL").writeBytes(container("""{"titleId":"game","rosTitleId":123,"principalFileName":"Game.exe"}"""))
            assertEquals("game", RockstarMetadata.find(root)?.id)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun container(json: String): ByteArray {
        val iv = ByteArray(16) { it.toByte() }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(ByteArray(32), "AES"), IvParameterSpec(iv))
        val payload = iv + cipher.doFinal(json.toByteArray())
        val header = ByteBuffer.allocate(80).order(ByteOrder.LITTLE_ENDIAN)
        header.put(byteArrayOf(82, 71, 76, 77)).putInt(1).putInt(payload.size)
        return header.array() + payload
    }

    @Test fun decodesInstalledTitleWithoutAnAppIdAllowlist() {
        val title = RockstarMetadata.decode(container("""{"titleId":"new_title","rosTitleId":123,"principalFileName":"Game/Binaries/Win64/Game.exe"}"""))!!
        assertEquals("new_title", title.id)
        assertEquals(123, title.rosId)
        assertEquals("Game/Binaries/Win64/Game.exe", title.exe)
    }

    @Test fun rejectsBrokenContainersAndEscapingExecutables() {
        val valid = container("""{"titleId":"game","rosTitleId":123,"principalFileName":"Game.exe"}""")
        assertNull(RockstarMetadata.decode(valid.copyOf(90)))
        assertNull(RockstarMetadata.decode(valid.copyOf().also { it[4] = 2 }))
        assertNull(RockstarMetadata.decode(valid + byteArrayOf(0)))
        assertNull(RockstarMetadata.decode(container("""{"titleId":"game","rosTitleId":1.5,"principalFileName":"Game.exe"}""")))
        for (exe in listOf("../outside.exe", "C:/outside.exe", "/outside.exe")) {
            assertNull(RockstarMetadata.decode(container("""{"titleId":"game","rosTitleId":123,"principalFileName":"$exe"}""")))
        }
    }

    @Test fun doesNotFollowMetadataSymlinksOutsideTheGame() {
        val root = java.nio.file.Files.createTempDirectory("rockstar-metadata").toFile()
        val outside = java.io.File.createTempFile("rockstar-external", ".rgl")
        try {
            outside.writeBytes(container("""{"titleId":"game","rosTitleId":123,"principalFileName":"Game.exe"}"""))
            val link = java.io.File(root, "title.rgl").toPath()
            try { java.nio.file.Files.createSymbolicLink(link, outside.toPath()) }
            catch (e: java.nio.file.FileSystemException) { org.junit.Assume.assumeNoException(e) }
            assertNull(RockstarMetadata.find(root))
            java.nio.file.Files.delete(link)
            java.io.File(root, "nested").mkdir()
            outside.copyTo(java.io.File(root, "nested/title.rgl"))
            assertEquals("game", RockstarMetadata.find(root)?.id)
            assertEquals("nested/Game.exe", RockstarMetadata.find(root)?.exe)
        } finally {
            root.deleteRecursively()
            outside.delete()
        }
    }
}
