package com.droiddeck.launcher.runtime

import android.content.ContextWrapper
import com.droiddeck.launcher.BuildConfig
import com.droiddeck.launcher.core.AppLanguage
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorOutputStream
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class DesktopI18nTest {
    private val dir = Files.createTempDirectory("desktop-i18n").toFile()
    private val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
        override fun getFilesDir() = File(dir, "files").apply { mkdirs() }
        override fun getCacheDir() = File(dir, "cache").apply { mkdirs() }
    }
    private val root = LinuxRuntime.rootDir(context).apply { mkdirs() }
    private val hits = ConcurrentHashMap<String, Int>()
    private var listener: ServerSocket? = null

    @After fun tearDown() {
        try { listener?.close() } catch (e: IOException) { }
        dir.deleteRecursively()
    }

    /** The files are keyed by full URL so they can be built from the base this returns. */
    private fun serve(files: (baseUrl: String) -> Map<String, ByteArray>): String {
        val socket = ServerSocket(0, 0, InetAddress.getLoopbackAddress())
        listener = socket
        val baseUrl = "http://127.0.0.1:${socket.localPort}/desktop-kde.tar.zst"
        val paths = files(baseUrl).mapKeys { URI.create(it.key).path }
        Thread {
            while (true) {
                val connection = try { socket.accept() } catch (e: IOException) { break }
                Thread({
                    connection.use { client ->
                        val input = client.getInputStream().bufferedReader()
                        val request = input.readLine() ?: return@use
                        while (true) { val header = input.readLine() ?: break; if (header.isEmpty()) break }
                        val path = request.split(" ")[1]
                        hits.merge(path, 1) { a, b -> a + b }
                        val body = paths[path]
                        val out = client.getOutputStream()
                        if (body == null) {
                            out.write("HTTP/1.1 404 Not Found\r\nConnection: close\r\nContent-Length: 0\r\n\r\n".toByteArray())
                        } else {
                            out.write(("HTTP/1.1 200 OK\r\nConnection: close\r\nContent-Length: ${body.size}\r\n\r\n").toByteArray())
                            out.write(body)
                        }
                        out.flush()
                    }
                }, "desktop-i18n-test-server").apply { isDaemon = true }.start()
            }
        }.apply { isDaemon = true; name = "desktop-i18n-test-listener" }.start()
        return baseUrl
    }

    private fun fetched(url: String): Int = hits[URI.create(url).path] ?: 0

    private fun archive(entry: String, text: String): ByteArray {
        val tar = ByteArrayOutputStream()
        TarArchiveOutputStream(tar).use { out ->
            val bytes = text.toByteArray()
            out.putArchiveEntry(TarArchiveEntry(entry).apply { size = bytes.size.toLong() })
            out.write(bytes)
            out.closeArchiveEntry()
        }
        val zst = ByteArrayOutputStream()
        ZstdCompressorOutputStream(zst).use { it.write(tar.toByteArray()) }
        return zst.toByteArray()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun localerc(): String? =
        File(root, "root/.config/plasma-localerc").takeIf { it.isFile }?.readText()

    private fun localercLanguage(): String? =
        localerc()?.lineSequence()?.firstOrNull { it.startsWith("LANGUAGE=") }?.substringAfter('=')

    private fun note() = File(root, "root/.config/.droiddeck-desktop-language")

    @Test fun urlsPointAtTheAssetsOfTheDesktopRelease() {
        val base = BuildConfig.DESKTOP_KDE_URL.substringBeforeLast('/')

        assertEquals("$base/desktop-kde.i18n.txt", DesktopI18n.indexUrl())
        assertEquals("$base/desktop-kde-i18n-zh_CN.tar.zst", DesktopI18n.packUrl("zh_CN"))
        assertTrue(DesktopI18n.indexUrl().startsWith("https://github.com/"))
        assertTrue(DesktopI18n.indexUrl().contains(BuildConfig.DESKTOP_KDE_TAG))
    }

    @Test fun indexLinesAreParsedAndBrokenOnesAreDropped() {
        val body = listOf(
            "de ${"a".repeat(64)} 262144",
            "",
            "   ",
            "missing-columns ${"b".repeat(64)}",
            "not-hex ${"g".repeat(64)} 100",
            "not-a-number ${"c".repeat(64)} ten",
            "zero ${"d".repeat(64)} 0",
            "negative ${"e".repeat(64)} -5",
            "four-columns ${"f".repeat(64)} 100 extra",
            "zh_TW ${"b".repeat(64)} 2048",
        ).joinToString("\n")

        val packs = DesktopI18n.parseIndex(body)

        assertEquals(setOf("de", "zh_TW"), packs.keys)
        assertEquals(DesktopI18n.Pack("a".repeat(64), 262144L), packs["de"])
        assertEquals(2048L, packs["zh_TW"]?.size)
    }

    @Test fun everySupportedAppLanguageMapsToAPackDirectoryAndACoreLocale() {
        assertEquals(33, AppLanguage.supported.size)
        for (tag in AppLanguage.supported) {
            assertNotNull("pack for $tag", DesktopI18n.packLocale(tag))
            assertNotNull("locale for $tag", DesktopI18n.posixLocale(tag))
        }

        assertEquals("zh_CN", DesktopI18n.packLocale("zh-CN"))
        assertEquals("zh_CN.UTF-8", DesktopI18n.posixLocale("zh-CN"))
        assertEquals("zh_TW", DesktopI18n.packLocale("zh-TW"))
        assertEquals("zh_TW.UTF-8", DesktopI18n.posixLocale("zh-TW"))
        assertEquals("zh_TW", DesktopI18n.packLocale("zh-HK"))
        assertEquals("zh_TW.UTF-8", DesktopI18n.posixLocale("zh-HK"))

        // gettext ships language-only directories where glibc locale names carry a territory
        assertEquals("ja", DesktopI18n.packLocale("ja"))
        assertEquals("ja_JP.UTF-8", DesktopI18n.posixLocale("ja"))
        assertEquals("ko", DesktopI18n.packLocale("ko"))
        assertEquals("pt_BR", DesktopI18n.packLocale("pt-BR"))
        assertEquals("pt_PT", DesktopI18n.packLocale("pt-PT"))
        assertEquals("pt_BR.UTF-8", DesktopI18n.posixLocale("pt-BR"))
        assertEquals("pt_PT.UTF-8", DesktopI18n.posixLocale("pt-PT"))
        assertEquals("nb", DesktopI18n.packLocale("nb"))
        assertEquals("nb_NO.UTF-8", DesktopI18n.posixLocale("nb"))
        assertEquals("uk_UA.UTF-8", DesktopI18n.posixLocale("uk"))
        assertEquals("id_ID.UTF-8", DesktopI18n.posixLocale("id"))

        // glibc has no es_419 and no pack is published for English
        assertEquals("es", DesktopI18n.packLocale("es-419"))
        assertEquals("es_MX.UTF-8", DesktopI18n.posixLocale("es-419"))
        assertEquals("en", DesktopI18n.packLocale("en"))
        assertEquals("en_US.UTF-8", DesktopI18n.posixLocale("en"))
    }

    @Test fun theLocalercMatchesTheVerifiedTraditionalChineseSampleByteForByte() {
        DesktopI18n.applyLanguage(root, "zh_TW", "zh_TW.UTF-8")

        val expected = listOf(
            "[Formats]",
            "LANG=zh_TW.UTF-8",
            "LC_ADDRESS=zh_TW.UTF-8",
            "LC_MEASUREMENT=zh_TW.UTF-8",
            "LC_MONETARY=zh_TW.UTF-8",
            "LC_NAME=zh_TW.UTF-8",
            "LC_NUMERIC=zh_TW.UTF-8",
            "LC_PAPER=zh_TW.UTF-8",
            "LC_TELEPHONE=zh_TW.UTF-8",
            "LC_TIME=zh_TW.UTF-8",
            "",
            "[Translations]",
            "LANGUAGE=zh_TW",
            "",
        ).joinToString("\n")

        assertArrayEquals(expected.toByteArray(), File(root, "root/.config/plasma-localerc").readBytes())
        assertEquals("zh_TW\n", note().readText())
    }

    @Test fun theDesktopLanguageIsOnlyRewrittenWhileItIsStillOurs() {
        DesktopI18n.applyLanguage(root, "fr", "fr_FR.UTF-8")
        assertEquals("fr", localercLanguage())
        assertEquals("fr\n", note().readText())

        DesktopI18n.applyLanguage(root, "de", "de_DE.UTF-8")
        assertEquals("de", localercLanguage())
        assertEquals("de\n", note().readText())

        val localerc = File(root, "root/.config/plasma-localerc")
        localerc.writeText(localerc.readText().replace("LANGUAGE=de", "LANGUAGE=it"))
        DesktopI18n.applyLanguage(root, "ru", "ru_RU.UTF-8")
        assertEquals("it", localercLanguage())
        assertEquals("de\n", note().readText())

        localerc.delete()
        DesktopI18n.applyLanguage(root, "ru", "ru_RU.UTF-8")
        assertEquals("ru", localercLanguage())
        assertEquals("ru\n", note().readText())
    }

    @Test fun aPlasmaLanguageWeNeverWroteIsLeftAlone() {
        val localerc = File(root, "root/.config/plasma-localerc")
        localerc.parentFile?.mkdirs()
        val user = "[Formats]\nLANG=fr_FR.UTF-8\nLC_TIME=fr_FR.UTF-8\n\n[Translations]\nLANGUAGE=fr\n"
        localerc.writeText(user)

        DesktopI18n.applyLanguage(root, "zh_TW", "zh_TW.UTF-8")

        assertEquals(user, localerc.readText())
        assertFalse(note().exists())

        localerc.writeText("[Formats]\nLANG=fr_FR.UTF-8\nLC_TIME=fr_FR.UTF-8\n")
        DesktopI18n.applyLanguage(root, "zh_TW", "zh_TW.UTF-8")

        assertEquals("zh_TW", localercLanguage())
        assertEquals("zh_TW\n", note().readText())
    }

    @Test fun aLocalercLanguageKeyWrittenWithSpacesIsLeftAlone() {
        val localerc = File(root, "root/.config/plasma-localerc")
        localerc.parentFile?.mkdirs()
        val user = "[Formats]\nLANG=fr_FR.UTF-8\nLC_TIME=fr_FR.UTF-8\n\n[Translations]\nLANGUAGE = fr\n"
        localerc.writeText(user)

        DesktopI18n.applyLanguage(root, "zh_TW", "zh_TW.UTF-8")

        assertEquals(user, localerc.readText())
        assertFalse(note().exists())
    }

    @Test fun everyDuplicateEntryOfAManagedKeyIsUpdated() {
        DesktopI18n.applyLanguage(root, "fr", "fr_FR.UTF-8")
        val localerc = File(root, "root/.config/plasma-localerc")
        localerc.writeText(localerc.readText().replace("LANGUAGE=fr\n", "LANGUAGE=fr\nLANGUAGE=fr\n"))
        assertEquals(2, localerc.readText().lines().count { it == "LANGUAGE=fr" })

        DesktopI18n.applyLanguage(root, "de", "de_DE.UTF-8")

        val entries = localerc.readText().lines().filter { it.startsWith("LANGUAGE=") }
        assertEquals(listOf("LANGUAGE=de", "LANGUAGE=de"), entries)
        assertEquals("de\n", note().readText())
    }

    @Test fun rewritingKeepsSectionsTheAppDoesNotManage() {
        DesktopI18n.applyLanguage(root, "fr", "fr_FR.UTF-8")
        val localerc = File(root, "root/.config/plasma-localerc")
        localerc.writeText(
            localerc.readText()
                .replace("[Formats]\n", "[Formats]\nCustom=yes\n")
                .replace("LANGUAGE=fr\n", "LANGUAGE=fr\nUserKey=stay\n"),
        )

        DesktopI18n.applyLanguage(root, "de", "de_DE.UTF-8")

        val text = localerc.readText()
        assertTrue(text.contains("Custom=yes\n"))
        assertTrue(text.contains("UserKey=stay\n"))
        assertTrue(text.contains("LANG=de_DE.UTF-8\n"))
        assertTrue(text.contains("LC_TIME=de_DE.UTF-8\n"))
        assertFalse(text.contains("fr_FR"))
        assertEquals("de", localercLanguage())
        assertEquals("de\n", note().readText())
    }

    @Test fun aPackCountsAsInstalledOnlyWithThisReleasesMarkerAndItsDirectory() {
        val pack = "zh_TW"
        val marker = File(root, ".droiddeck-pkg-desktop-kde-i18n-$pack")
        assertFalse(DesktopI18n.installed(root, pack))

        marker.writeText("${BuildConfig.DESKTOP_KDE_TAG} $pack")
        assertFalse(DesktopI18n.installed(root, pack))

        File(root, "usr/share/locale/$pack").mkdirs()
        assertTrue(DesktopI18n.installed(root, pack))

        marker.writeText("older-tag $pack")
        assertFalse(DesktopI18n.installed(root, pack))

        marker.writeText("${BuildConfig.DESKTOP_KDE_TAG} zh_CN")
        assertFalse(DesktopI18n.installed(root, pack))
    }

    @Test fun thePackIsInstalledFromTheIndexAndASecondRunFetchesNothing() {
        AppLanguage.setChosen(context, "zh-TW")
        val pack = archive("./usr/share/locale/zh_TW/LC_MESSAGES/messages.mo", "translations")
        val base = serve { b ->
            mapOf(
                DesktopI18n.indexFor(b) to "zh_TW ${sha256(pack)} ${pack.size}\n".toByteArray(),
                DesktopI18n.packFor(b, "zh_TW") to pack,
            )
        }

        assertNull(DesktopI18n.ensure(context, null, base))

        assertEquals("translations", File(root, "usr/share/locale/zh_TW/LC_MESSAGES/messages.mo").readText())
        assertEquals("${BuildConfig.DESKTOP_KDE_TAG} zh_TW", File(root, ".droiddeck-pkg-desktop-kde-i18n-zh_TW").readText())
        assertEquals("zh_TW", localercLanguage())
        assertEquals(1, fetched(DesktopI18n.indexFor(base)))
        assertEquals(1, fetched(DesktopI18n.packFor(base, "zh_TW")))

        assertNull(DesktopI18n.ensure(context, null, base))

        assertEquals(1, fetched(DesktopI18n.indexFor(base)))
        assertEquals(1, fetched(DesktopI18n.packFor(base, "zh_TW")))
    }

    @Test fun reinstallDropsTheMarkerAndDownloadsThePackAgain() {
        AppLanguage.setChosen(context, "zh-TW")
        val pack = archive("./usr/share/locale/zh_TW/LC_MESSAGES/messages.mo", "translations")
        val base = serve { b ->
            mapOf(
                DesktopI18n.indexFor(b) to "zh_TW ${sha256(pack)} ${pack.size}\n".toByteArray(),
                DesktopI18n.packFor(b, "zh_TW") to pack,
            )
        }

        assertNull(DesktopI18n.ensure(context, null, base))
        assertEquals(1, fetched(DesktopI18n.packFor(base, "zh_TW")))

        assertNull(DesktopI18n.reinstall(context, null, base))

        assertEquals(2, fetched(DesktopI18n.indexFor(base)))
        assertEquals(2, fetched(DesktopI18n.packFor(base, "zh_TW")))
        assertEquals("translations", File(root, "usr/share/locale/zh_TW/LC_MESSAGES/messages.mo").readText())
        assertEquals("zh_TW", localercLanguage())
    }

    @Test fun aPackMissingFromTheIndexIsReportedInsteadOfTouchingTheDesktop() {
        AppLanguage.setChosen(context, "zh-TW")
        val base = serve { b ->
            mapOf(DesktopI18n.indexFor(b) to "de ${"a".repeat(64)} 262144\n".toByteArray())
        }

        val problem = DesktopI18n.ensure(context, null, base)

        assertNotNull(problem)
        assertTrue(problem!!, problem.contains("no language pack for zh_TW"))
        assertFalse(File(root, "usr/share/locale/zh_TW").exists())
        assertNull(localerc())
    }

    @Test fun englishShipsWithTheDesktopSoOnlyTheDesktopLanguageIsApplied() {
        AppLanguage.setChosen(context, "en")

        assertNull(DesktopI18n.ensure(context, null, "http://127.0.0.1:1/desktop-kde.tar.zst"))

        assertEquals("LANG=en_US.UTF-8", localerc()?.lineSequence()?.firstOrNull { it.startsWith("LANG=") })
        assertEquals("en", localercLanguage())
        assertEquals("en\n", note().readText())
        assertFalse(File(root, ".droiddeck-pkg-desktop-kde-i18n-en").exists())
        assertTrue(hits.isEmpty())
    }

    @Test fun anUnsupportedAppLanguageFallsBackToEnglishWithoutTheNetwork() {
        AppLanguage.setChosen(context, "sr")

        assertNull(DesktopI18n.ensure(context, null, "http://127.0.0.1:1/desktop-kde.tar.zst"))

        assertEquals("en", localercLanguage())
        assertEquals("en\n", note().readText())
        assertTrue(hits.isEmpty())
    }

    @Test fun applyLocalReportsAMissingPackInsteadOfDownloading() {
        AppLanguage.setChosen(context, "zh-TW")

        assertNotNull(DesktopI18n.applyLocal(context))

        assertNull(localerc())
        assertFalse(note().exists())
    }

    @Test fun applyLocalAppliesEnglishWithoutAPack() {
        AppLanguage.setChosen(context, "en")

        assertNull(DesktopI18n.applyLocal(context))

        assertEquals("LANG=en_US.UTF-8", localerc()?.lineSequence()?.firstOrNull { it.startsWith("LANG=") })
        assertEquals("en", localercLanguage())
        assertEquals("en\n", note().readText())
        assertTrue(hits.isEmpty())
    }

    @Test fun applyLocalAppliesAnInstalledPackWithoutFetchingAgain() {
        AppLanguage.setChosen(context, "zh-TW")
        val pack = archive("./usr/share/locale/zh_TW/LC_MESSAGES/messages.mo", "translations")
        val base = serve { b ->
            mapOf(
                DesktopI18n.indexFor(b) to "zh_TW ${sha256(pack)} ${pack.size}\n".toByteArray(),
                DesktopI18n.packFor(b, "zh_TW") to pack,
            )
        }

        assertNull(DesktopI18n.ensure(context, null, base))
        File(root, "root/.config/plasma-localerc").delete()
        note().delete()

        assertNull(DesktopI18n.applyLocal(context))

        assertEquals("zh_TW", localercLanguage())
        assertEquals(1, fetched(DesktopI18n.indexFor(base)))
        assertEquals(1, fetched(DesktopI18n.packFor(base, "zh_TW")))
    }
}
