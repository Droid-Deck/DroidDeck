package com.droiddeck.launcher.session

import com.droiddeck.launcher.core.GameEnvironment
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class EsyncPacksTest {
    @get:Rule val temp = TemporaryFolder()

    private val ntdllBytes = "patched ntdll.so".toByteArray()
    private val wineserverBytes = "patched wineserver".toByteArray()
    private val stockNtdll = "a".repeat(64)
    private val stockWineserver = "b".repeat(64)
    private val exports = "c".repeat(64)
    private val toolDir = "/root/.local/share/Steam/compatibilitytools.d/GE-Proton11-7"

    private fun sha(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun packJson(
        id: String,
        rev: Int = 1,
        ntdll: String = stockNtdll,
        wineserver: String = stockWineserver,
        sourceMatch: Boolean = false,
        version: String = "GE-Proton11-7",
        exportsHash: String = exports,
    ): JSONObject = JSONObject()
        .put("format", 1)
        .put("id", id)
        .put("flavor", "ge")
        .put("rev", rev)
        .put("version", version)
        .put("version_line", "1789520806 $version")
        .put("stock", JSONObject().put(EsyncPacks.NTDLL, ntdll).put(EsyncPacks.WINESERVER, wineserver))
        .put("exports", exportsHash)
        .put("source_match", sourceMatch)
        .put("files", JSONObject().put(EsyncPacks.NTDLL, sha(ntdllBytes)).put(EsyncPacks.WINESERVER, sha(wineserverBytes)))

    private fun entryJson(
        id: String,
        rev: Int = 1,
        ntdll: String = stockNtdll,
        wineserver: String = stockWineserver,
        sourceMatch: Boolean = false,
        version: String = "GE-Proton11-7",
        exportsHash: String = exports,
        url: String = "https://github.com/Droid-Deck/DroidDeck/releases/download/droiddeck-esync-ge/$id.tzst",
        revoked: Boolean = false,
    ): JSONObject = packJson(id, rev, ntdll, wineserver, sourceMatch, version, exportsHash)
        .put("asset", JSONObject().put("url", url).put("sha256", "d".repeat(64)).put("size", 123))
        .put("revoked", revoked)
        .apply { remove("format") }

    private fun indexBytes(generated: Long, vararg entries: JSONObject): ByteArray =
        JSONObject().put("schema", 1).put("generated", generated).put("packs", JSONArray(entries.toList()))
            .toString().toByteArray()

    private fun entries(vararg entries: JSONObject): List<EsyncPacks.Entry> =
        EsyncPacks.parseIndex(indexBytes(1, *entries)).packs

    private fun row(
        ntdll: String = stockNtdll,
        wineserver: String = stockWineserver,
        version: String = "GE-Proton11-7",
        exportsHash: String = exports,
    ) = EsyncPacks.Wanted(ntdll, wineserver, version, exportsHash, toolDir, 0L)

    private fun keyPair(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    private fun sign(bytes: ByteArray, pair: KeyPair): ByteArray = Signature.getInstance("SHA256withECDSA").run {
        initSign(pair.private)
        update(bytes)
        sign()
    }

    private fun tar(vararg items: Pair<TarArchiveEntry, ByteArray?>): TarArchiveInputStream {
        val out = ByteArrayOutputStream()
        TarArchiveOutputStream(out).use { stream ->
            stream.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            for ((entry, data) in items) {
                if (data != null) entry.size = data.size.toLong()
                stream.putArchiveEntry(entry)
                if (data != null) stream.write(data)
                stream.closeArchiveEntry()
            }
        }
        return TarArchiveInputStream(ByteArrayInputStream(out.toByteArray()))
    }

    private fun regular(name: String, data: ByteArray) = TarArchiveEntry(name) to data

    private fun directory(name: String) = TarArchiveEntry(name) to null

    private fun packTar(pack: JSONObject, ntdll: ByteArray = ntdllBytes, prefix: String = ""): TarArchiveInputStream = tar(
        directory("${prefix}files/"),
        directory("${prefix}files/bin-arm64/"),
        regular("${prefix}files/bin-arm64/wineserver", wineserverBytes),
        directory("${prefix}files/lib/"),
        directory("${prefix}files/lib/wine/"),
        directory("${prefix}files/lib/wine/aarch64-unix/"),
        regular("${prefix}files/lib/wine/aarch64-unix/ntdll.so", ntdll),
        regular("${prefix}pack.json", pack.toString().toByteArray()),
    )

    private fun rejects(block: () -> Unit) {
        try {
            block()
        } catch (e: IllegalArgumentException) {
            return
        }
        fail("expected the entry to be rejected")
    }

    @Test fun parseIndexReadsEveryField() {
        val index = EsyncPacks.parseIndex(indexBytes(1791234567, entryJson("ge-GE-Proton11-7-1789520806-6ba770ec0df5-r1", rev = 3, sourceMatch = true)))
        assertEquals(1, index.schema)
        assertEquals(1791234567L, index.generated)
        val entry = index.packs.single()
        assertEquals("ge-GE-Proton11-7-1789520806-6ba770ec0df5-r1", entry.id)
        assertEquals("ge", entry.flavor)
        assertEquals(3, entry.rev)
        assertEquals("GE-Proton11-7", entry.version)
        assertEquals("1789520806 GE-Proton11-7", entry.versionLine)
        assertEquals(mapOf(EsyncPacks.NTDLL to stockNtdll, EsyncPacks.WINESERVER to stockWineserver), entry.stock)
        assertEquals(exports, entry.exports)
        assertTrue(entry.sourceMatch)
        assertEquals(sha(ntdllBytes), entry.files[EsyncPacks.NTDLL])
        assertEquals(sha(wineserverBytes), entry.files[EsyncPacks.WINESERVER])
        assertEquals("d".repeat(64), entry.asset.sha256)
        assertEquals(123L, entry.asset.size)
        assertFalse(entry.revoked)
    }

    @Test fun parseIndexDropsEntriesOutsideTheReleasePrefixOrMalformed() {
        val packs = entries(
            entryJson("good-r1"),
            entryJson("other-repo-r1", url = "https://github.com/Someone/DroidDeck/releases/download/droiddeck-esync-ge/other-repo-r1.tzst"),
            entryJson("plain-http-r1", url = "http://github.com/Droid-Deck/DroidDeck/releases/download/droiddeck-esync-ge/plain-http-r1.tzst"),
            entryJson("dotdot-r1", url = "https://github.com/Droid-Deck/DroidDeck/releases/download/droiddeck-esync-ge/../../x/dotdot-r1.tzst"),
            entryJson("other-tag-r1", url = "https://github.com/Droid-Deck/DroidDeck/releases/download/v1/other-tag-r1.tzst"),
            entryJson("query-r1", url = "https://github.com/Droid-Deck/DroidDeck/releases/download/droiddeck-esync-ge/query-r1.tzst?x=1"),
            entryJson("bad/id"),
            entryJson("bad-hash-r1", ntdll = "xyz"),
            entryJson("good-r1", rev = 9),
        )
        assertEquals(listOf("good-r1"), packs.map { it.id })
        assertEquals(1, packs.single().rev)
        assertTrue(EsyncPacks.allowedUrl("https://github.com/Droid-Deck/DroidDeck/releases/download/droiddeck-esync-cachyos/a-r2.tzst"))
        assertFalse(EsyncPacks.allowedUrl("https://github.com/Droid-Deck/DroidDeck/releases/download/droiddeck-esync-ge/.."))
        assertFalse(EsyncPacks.allowedUrl("https://github.com/Droid-Deck/DroidDeck/releases/download/droiddeck-esync-ge/a/b.tzst"))
        assertFalse(EsyncPacks.allowedUrl("https://github.com.evil.example/Droid-Deck/DroidDeck/releases/download/droiddeck-esync-ge/a.tzst"))
    }

    @Test fun parseIndexRejectsAnUnknownSchema() {
        val bytes = JSONObject().put("schema", 2).put("generated", 1).put("packs", JSONArray()).toString().toByteArray()
        try {
            EsyncPacks.parseIndex(bytes)
            fail("schema 2 must not parse")
        } catch (e: IllegalArgumentException) {
        }
    }

    @Test fun signatureVerifiesOnlyTheSignedBytesWithTheRightKey() {
        val pair = keyPair()
        val bytes = indexBytes(5, entryJson("ge-a-r1"))
        val sig = sign(bytes, pair)
        assertTrue(EsyncPacks.verify(bytes, sig, pair.public))
        val tampered = bytes.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }
        assertFalse(EsyncPacks.verify(tampered, sig, pair.public))
        val badSig = sig.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertFalse(EsyncPacks.verify(bytes, badSig, pair.public))
        assertFalse(EsyncPacks.verify(bytes, ByteArray(0), pair.public))
        assertFalse(EsyncPacks.verify(bytes, "not a signature".toByteArray(), pair.public))
        assertFalse(EsyncPacks.verify(bytes, sig, keyPair().public))
    }

    @Test fun theShippedKeyDecodesAndRejectsAForeignSignature() {
        assertEquals("EC", EsyncPacks.key(EsyncPacks.PUBLIC_KEY).algorithm)
        val bytes = indexBytes(5, entryJson("ge-a-r1"))
        assertFalse(EsyncPacks.verify(bytes, sign(bytes, keyPair())))
    }

    @Test fun acceptEnforcesSignatureAndAntiRollback() {
        val pair = keyPair()
        val cached = EsyncPacks.parseIndex(indexBytes(200, entryJson("ge-a-r1")))
        val older = indexBytes(199, entryJson("ge-a-r1"))
        val same = indexBytes(200, entryJson("ge-a-r1"))
        val newer = indexBytes(201, entryJson("ge-a-r2", rev = 2))
        assertNull(EsyncPacks.accept(older, sign(older, pair), cached, pair.public))
        assertNotNull(EsyncPacks.accept(same, sign(same, pair), cached, pair.public))
        assertEquals(201L, EsyncPacks.accept(newer, sign(newer, pair), cached, pair.public)?.generated)
        assertNotNull(EsyncPacks.accept(older, sign(older, pair), null, pair.public))
        assertNull(EsyncPacks.accept(newer, sign(older, pair), null, pair.public))
        assertNull(EsyncPacks.accept(newer, sign(newer, keyPair()), null, pair.public))
        assertNull(EsyncPacks.accept(older, sign(older, pair), null, pair.public, floor = 200))
        assertNotNull(EsyncPacks.accept(same, sign(same, pair), null, pair.public, floor = 200))
        assertNull(EsyncPacks.accept(same, sign(same, pair), cached, pair.public, floor = 201))
        assertEquals(201L, EsyncPacks.accept(newer, sign(newer, pair), null, pair.public, floor = 201)?.generated)
    }

    @Test fun exactMatchBeatsSourceMatchEvenAtALowerRev() {
        val packs = entries(
            entryJson("valve-source-r5", rev = 5, ntdll = "e".repeat(64), wineserver = "f".repeat(64), sourceMatch = true),
            entryJson("ge-exact-r1", rev = 1),
        )
        assertEquals("ge-exact-r1", EsyncPacks.best(packs, row())?.id)
        assertEquals(2, EsyncPacks.kind(packs[1], row()))
        assertEquals(1, EsyncPacks.kind(packs[0], row()))
    }

    @Test fun sourceMatchNeedsTheFlagTheVersionAndTheExports() {
        val other = row(ntdll = "1".repeat(64), wineserver = "2".repeat(64))
        val flagged = entries(entryJson("valve-r1", sourceMatch = true)).single()
        val unflagged = entries(entryJson("valve-r1", sourceMatch = false)).single()
        assertTrue(EsyncPacks.matches(flagged, other))
        assertFalse(EsyncPacks.matches(unflagged, other))
        assertFalse(EsyncPacks.matches(flagged, other.copy(version = "GE-Proton11-8")))
        assertFalse(EsyncPacks.matches(flagged, other.copy(exports = "9".repeat(64))))
        assertFalse(EsyncPacks.matches(flagged, other.copy(exports = "")))
        assertFalse(EsyncPacks.matches(unflagged, row(wineserver = "2".repeat(64))))
    }

    @Test fun higherRevThenGreaterIdWinsAndRevokedNeverMatches() {
        val packs = entries(
            entryJson("ge-a-r1", rev = 1),
            entryJson("ge-a-r3", rev = 3),
            entryJson("ge-b-r3", rev = 3),
            entryJson("ge-z-r9", rev = 9, revoked = true),
        )
        assertEquals("ge-b-r3", EsyncPacks.best(packs, row())?.id)
        assertFalse(EsyncPacks.matches(packs[3], row()))
        assertNull(EsyncPacks.best(listOf(packs[3]), row()))
    }

    @Test fun planSkipsInstalledRevokedAndUnmatchedRows() {
        val index = EsyncPacks.parseIndex(indexBytes(
            1,
            entryJson("ge-a-r1"),
            entryJson("ge-b-r1", ntdll = "1".repeat(64), wineserver = "2".repeat(64)),
            entryJson("ge-c-r1", ntdll = "3".repeat(64), wineserver = "4".repeat(64), revoked = true),
        ))
        val wanted = listOf(
            row(),
            row(ntdll = "1".repeat(64), wineserver = "2".repeat(64)),
            row(ntdll = "3".repeat(64), wineserver = "4".repeat(64)),
            row(ntdll = "5".repeat(64), wineserver = "6".repeat(64)),
        )
        assertEquals(listOf("ge-a-r1", "ge-b-r1"), EsyncPacks.plan(index, wanted, emptySet()).map { it.id })
        assertEquals(listOf("ge-b-r1"), EsyncPacks.plan(index, wanted, setOf("ge-a-r1")).map { it.id })
    }

    @Test fun aNewerRevOfAPackInUseIsPlanned() {
        val index = EsyncPacks.parseIndex(indexBytes(1, entryJson("ge-a-r2", rev = 2), entryJson("ge-x-r1", ntdll = "1".repeat(64), wineserver = "2".repeat(64))))
        val tools = listOf(
            EsyncPacks.ToolState(toolDir, "1789520806 GE-Proton11-7", stockNtdll.uppercase(), stockWineserver, "pack", "ge-a-r1"),
            EsyncPacks.ToolState("/root/b", "1 b", "1".repeat(64), "2".repeat(64), "builtin", null),
            EsyncPacks.ToolState("/root/c", "1 c", "1".repeat(64), "2".repeat(64), "wanted", null),
        )
        val store = temp.newFolder("store")
        File(store, "packs/ge-a-r1").mkdirs()
        File(store, "packs/ge-a-r1/pack.json").writeText(packJson("ge-a-r1").toString())
        File(store, "packs/ge-a-r1/.complete").writeText("")
        val rows = EsyncPacks.upgradeRows(store, tools)
        assertEquals(1, rows.size)
        assertEquals("GE-Proton11-7", rows.single().version)
        assertEquals("", rows.single().exports)
        assertEquals(listOf("ge-a-r2"), EsyncPacks.plan(index, rows, setOf("ge-a-r1")).map { it.id })
        assertTrue(EsyncPacks.plan(index, rows, setOf("ge-a-r2")).isEmpty())
    }

    @Test fun aNewerRevOfASourcePackInUseIsPlanned() {
        val store = temp.newFolder("store")
        val version = "experimental-11.0-20260910b-arm64"
        val r1 = "valve-experimental-11.0-20260910b-arm64-1789159687-aaaaaaaaaaaa-r1"
        val r2 = "valve-experimental-11.0-20260910b-arm64-1789159687-aaaaaaaaaaaa-r2"
        File(store, "packs/$r1").mkdirs()
        File(store, "packs/$r1/pack.json").writeText(packJson(r1, sourceMatch = true, version = version).toString())
        File(store, "packs/$r1/.complete").writeText("")
        val index = EsyncPacks.parseIndex(indexBytes(2, entryJson(r2, rev = 2, sourceMatch = true, version = version)))
        val tool = EsyncPacks.ToolState(
            "/root/.local/share/Steam/steamapps/common/Proton Experimental (ARM64)", "1789159687 $version",
            "1".repeat(64), "2".repeat(64), "pack", r1,
        )
        val rows = EsyncPacks.upgradeRows(store, listOf(tool))
        assertEquals(exports, rows.single().exports)
        assertEquals(version, rows.single().version)
        assertEquals("1".repeat(64), rows.single().ntdll)
        assertEquals(listOf(r2), EsyncPacks.plan(index, rows, setOf(r1)).map { it.id })
        assertTrue(EsyncPacks.plan(index, rows, setOf(r1, r2)).isEmpty())
        val retagged = tool.copy(versionLine = "1789159687 experimental-11.0-20260911-arm64")
        assertTrue(EsyncPacks.plan(index, EsyncPacks.upgradeRows(store, listOf(retagged)), setOf(r1)).isEmpty())
        File(store, "packs/$r1/.complete").delete()
        assertEquals("", EsyncPacks.upgradeRows(store, listOf(tool)).single().exports)
        assertEquals("", EsyncPacks.upgradeRows(store, listOf(tool.copy(packId = "../$r1"))).single().exports)
    }

    @Test fun removeRevokedDeletesOnlyRevokedPacks() {
        val store = temp.newFolder("store")
        for (id in listOf("ge-a-r1", "ge-b-r1")) {
            File(store, "packs/$id/files").mkdirs()
            File(store, "packs/$id/.complete").writeText("")
        }
        val index = EsyncPacks.parseIndex(indexBytes(1, entryJson("ge-a-r1", revoked = true), entryJson("ge-b-r1")))
        assertEquals(listOf("ge-a-r1"), EsyncPacks.removeRevoked(store, index))
        assertFalse(File(store, "packs/ge-a-r1").exists())
        assertTrue(File(store, "packs/ge-b-r1/.complete").isFile)
        assertEquals(setOf("ge-b-r1"), EsyncPacks.installedIds(store))
        assertTrue(File(store, "packs").list()!!.none { it.startsWith(".") })
    }

    @Test fun entryPathAcceptsTheThreeFilesAndTheirDirectories() {
        assertEquals("pack.json", EsyncPacks.entryPath(TarArchiveEntry("pack.json")))
        assertEquals("pack.json", EsyncPacks.entryPath(TarArchiveEntry("./pack.json")))
        assertEquals(EsyncPacks.WINESERVER, EsyncPacks.entryPath(TarArchiveEntry("files/bin-arm64/wineserver")))
        assertEquals(EsyncPacks.NTDLL, EsyncPacks.entryPath(TarArchiveEntry("./files/lib/wine/aarch64-unix/ntdll.so")))
        assertNull(EsyncPacks.entryPath(TarArchiveEntry("./")))
        assertNull(EsyncPacks.entryPath(TarArchiveEntry("files/")))
        assertNull(EsyncPacks.entryPath(TarArchiveEntry("./files/lib/wine/aarch64-unix/")))
    }

    @Test fun entryPathRejectsUnsafeOrUnexpectedEntries() {
        rejects { EsyncPacks.entryPath(TarArchiveEntry("/pack.json", true)) }
        rejects { EsyncPacks.entryPath(TarArchiveEntry("../pack.json")) }
        rejects { EsyncPacks.entryPath(TarArchiveEntry("files/../pack.json")) }
        rejects { EsyncPacks.entryPath(TarArchiveEntry("files/./bin-arm64/wineserver")) }
        rejects { EsyncPacks.entryPath(TarArchiveEntry("pack.json", TarConstants.LF_SYMLINK).apply { linkName = "/etc/passwd" }) }
        rejects { EsyncPacks.entryPath(TarArchiveEntry(EsyncPacks.WINESERVER, TarConstants.LF_LINK).apply { linkName = "pack.json" }) }
        rejects { EsyncPacks.entryPath(TarArchiveEntry(EsyncPacks.NTDLL, TarConstants.LF_CHR)) }
        rejects { EsyncPacks.entryPath(TarArchiveEntry(EsyncPacks.NTDLL, TarConstants.LF_BLK)) }
        rejects { EsyncPacks.entryPath(TarArchiveEntry(EsyncPacks.NTDLL, TarConstants.LF_FIFO)) }
        rejects { EsyncPacks.entryPath(TarArchiveEntry("files/bin-arm64/wine")) }
        rejects { EsyncPacks.entryPath(TarArchiveEntry("files/lib/wine/aarch64-windows/")) }
        rejects { EsyncPacks.entryPath(TarArchiveEntry("etc/")) }
    }

    @Test fun unpackRejectsMissingDuplicateAndOversizedEntries() {
        val pack = packJson("ge-a-r1").toString().toByteArray()
        rejects {
            EsyncPacks.unpack(tar(regular("pack.json", pack), regular(EsyncPacks.NTDLL, ntdllBytes)), temp.newFolder())
        }
        rejects {
            EsyncPacks.unpack(tar(
                regular("pack.json", pack), regular(EsyncPacks.NTDLL, ntdllBytes),
                regular(EsyncPacks.WINESERVER, wineserverBytes), regular("./pack.json", pack),
            ), temp.newFolder())
        }
        rejects {
            EsyncPacks.unpack(tar(
                regular("pack.json", ByteArray((1 shl 20) + 1)), regular(EsyncPacks.NTDLL, ntdllBytes),
                regular(EsyncPacks.WINESERVER, wineserverBytes),
            ), temp.newFolder())
        }
        val dest = temp.newFolder()
        EsyncPacks.unpack(tar(regular("./pack.json", pack), regular(EsyncPacks.NTDLL, ntdllBytes), regular(EsyncPacks.WINESERVER, wineserverBytes)), dest)
        assertTrue(File(dest, EsyncPacks.NTDLL).readBytes().contentEquals(ntdllBytes))
        assertTrue(File(dest, EsyncPacks.WINESERVER).readBytes().contentEquals(wineserverBytes))
    }

    @Test fun placeInstallsAVerifiedPackOnce() {
        val store = temp.newFolder("store")
        val id = "ge-GE-Proton11-7-1789520806-6ba770ec0df5-r1"
        val entry = entries(entryJson(id)).single()
        assertTrue(EsyncPacks.place(store, entry, packTar(packJson(id))))
        val dir = File(store, "packs/$id")
        assertTrue(File(dir, ".complete").isFile)
        assertTrue(File(dir, EsyncPacks.NTDLL).canExecute())
        assertTrue(File(dir, EsyncPacks.WINESERVER).canExecute())
        assertTrue(File(dir, EsyncPacks.NTDLL).readBytes().contentEquals(ntdllBytes))
        assertEquals(setOf(id), EsyncPacks.installedIds(store))
        assertFalse(EsyncPacks.place(store, entry, packTar(packJson(id), prefix = "./")))
        assertTrue(File(dir, ".complete").isFile)
        assertTrue(File(store, "packs").list()!!.none { it.startsWith(".") })
    }

    @Test fun placeReplacesAnIncompleteDirectory() {
        val store = temp.newFolder("store")
        val id = "ge-a-r1"
        File(store, "packs/$id/files").mkdirs()
        assertTrue(EsyncPacks.place(store, entries(entryJson(id)).single(), packTar(packJson(id))))
        assertTrue(File(store, "packs/$id/.complete").isFile)
        assertTrue(File(store, "packs").list()!!.none { it.startsWith(".") })
    }

    @Test fun placeRejectsAPackThatDisagreesWithTheIndex() {
        val store = temp.newFolder("store")
        val id = "ge-a-r1"
        val entry = entries(entryJson(id)).single()
        rejects { EsyncPacks.place(store, entry, packTar(packJson(id, rev = 2))) }
        rejects { EsyncPacks.place(store, entry, packTar(packJson("ge-b-r1"))) }
        rejects { EsyncPacks.place(store, entry, packTar(packJson(id, sourceMatch = true))) }
        rejects { EsyncPacks.place(store, entry, packTar(packJson(id, ntdll = "9".repeat(64)))) }
        rejects { EsyncPacks.place(store, entry, packTar(packJson(id), ntdll = "something else".toByteArray())) }
        assertFalse(File(store, "packs/$id").exists())
        assertTrue(File(store, "packs").list()!!.isEmpty())
    }

    @Test fun placeRejectsAPackJsonTheGuestWouldSkip() {
        val store = temp.newFolder("store")
        val id = "ge-a-r1"
        val entry = entries(entryJson(id)).single()
        fun bad(change: JSONObject.() -> Unit) = rejects { EsyncPacks.place(store, entry, packTar(packJson(id).apply(change))) }
        bad { remove("format") }
        bad { put("format", "1") }
        bad { put("format", true) }
        bad { remove("source_match") }
        bad { put("source_match", "false") }
        bad { put("source_match", 0) }
        bad { put("rev", "1") }
        bad { remove("rev") }
        bad { remove("version") }
        bad { put("version_line", 5) }
        bad { put("version_line", JSONObject.NULL) }
        bad { remove("exports") }
        bad { put("exports", exports.uppercase()) }
        bad { put("stock", JSONObject().put(EsyncPacks.NTDLL, stockNtdll.uppercase()).put(EsyncPacks.WINESERVER, stockWineserver)) }
        bad { put("files", JSONObject().put(EsyncPacks.NTDLL, sha(ntdllBytes).uppercase()).put(EsyncPacks.WINESERVER, sha(wineserverBytes))) }
        bad { put("copy", "files/bin-arm64/wine") }
        bad { put("copy", JSONObject.NULL) }
        bad { put("copy", JSONArray(listOf("files/bin-arm64/wine", "files/bin-arm64/../wine"))) }
        bad { put("copy", JSONArray(listOf(EsyncPacks.NTDLL))) }
        bad { put("revoked", true) }
        bad { put("revoked", "yes") }
        bad { put("ignored", 1) }
        assertTrue(File(store, "packs").list()!!.isEmpty())
        val copy = JSONArray(listOf("files/bin-arm64/wine", "files/lib/wine/aarch64-unix/wine", "files/lib/wine/aarch64-unix/wine-preloader", "proton"))
        assertTrue(EsyncPacks.place(store, entry, packTar(packJson(id).put("copy", copy).put("revoked", false).put("ignored", JSONObject.NULL))))
        assertEquals(setOf(id), EsyncPacks.installedIds(store))
    }

    @Test fun copyPathsFollowTheGuestRules() {
        for (rel in listOf("files/bin-arm64/wine", "files/lib/wine/aarch64-unix/wine-preloader", "proton", "files/extra", "files/lib/wine/x")) {
            assertTrue(rel, EsyncPacks.copyPath(rel))
        }
        for (rel in listOf(
            "", "/proton", "./proton", "files/../proton", "files//wine", "files/bin-arm64/", "files", "files/lib/wine/aarch64-unix",
            EsyncPacks.NTDLL, EsyncPacks.WINESERVER, "dist.lock", "files/steampipe_fixups_mtime", ".droiddeck-esync.json",
            "files/share/wine/x", "files/lib/wine/aarch64-windows/ntdll.dll", "a\u0000b",
        )) {
            assertFalse(rel, EsyncPacks.copyPath(rel))
        }
        assertFalse(EsyncPacks.copyPath(5))
        assertFalse(EsyncPacks.copyPath(null))
    }

    @Test fun failedPackDownloadsBackOffPerAssetChecksum() {
        val minute = 60_000L
        val now = 10_000L * minute
        val entry = entries(entryJson("ge-a-r1")).single()
        val failures = JSONObject()
        assertFalse(EsyncPacks.failedRecently(failures, entry, now))
        failures.put(entry.id, EsyncPacks.failure(failures, entry, now))
        assertEquals(1, failures.getJSONObject(entry.id).getInt("n"))
        assertTrue(EsyncPacks.failedRecently(failures, entry, now))
        assertTrue(EsyncPacks.failedRecently(failures, entry, now + 9 * minute))
        assertFalse(EsyncPacks.failedRecently(failures, entry, now + 10 * minute))
        assertFalse(EsyncPacks.failedRecently(failures, entry, now - minute))
        failures.put(entry.id, EsyncPacks.failure(failures, entry, now))
        assertEquals(2, failures.getJSONObject(entry.id).getInt("n"))
        assertTrue(EsyncPacks.failedRecently(failures, entry, now + 19 * minute))
        assertFalse(EsyncPacks.failedRecently(failures, entry, now + 20 * minute))
        repeat(10) { failures.put(entry.id, EsyncPacks.failure(failures, entry, now)) }
        assertTrue(EsyncPacks.failedRecently(failures, entry, now + 11 * 60 * minute))
        assertFalse(EsyncPacks.failedRecently(failures, entry, now + 12 * 60 * minute))
        val reuploaded = entries(entryJson("ge-a-r1").apply { getJSONObject("asset").put("sha256", "e".repeat(64)) }).single()
        assertFalse(EsyncPacks.failedRecently(failures, reuploaded, now + minute))
        assertEquals(1, EsyncPacks.failure(failures, reuploaded, now).getInt("n"))
        val other = entries(entryJson("ge-b-r1")).single()
        failures.put(other.id, EsyncPacks.failure(failures, other, now))
        failures.put("ge-gone-r1", JSONObject().put("sha", "d".repeat(64)).put("n", 1).put("at", now))
        val index = EsyncPacks.parseIndex(indexBytes(
            2,
            entryJson("ge-a-r1").apply { getJSONObject("asset").put("sha256", "e".repeat(64)) },
            entryJson("ge-b-r1"),
        ))
        val pruned = EsyncPacks.pruneFailures(failures, index)
        assertEquals(listOf("ge-b-r1"), pruned.keys().asSequence().toList())
        assertEquals(0, EsyncPacks.pruneFailures(null, index).length())
    }

    @Test fun aPerGameFallbackOptInKeepsPackFetchingOn() {
        val context = RuntimeEnvironment.getApplication()
        val settings = File(context.filesDir, "game-environment.json")
        settings.delete()
        try {
            SessionPrefs.setSyncFallback(context, false)
            assertFalse(EsyncPacks.enabled(context))
            val root = temp.newFolder("rootfs")
            EsyncPacks.store(root).mkdirs()
            assertEquals(0, EsyncPacks.fetchWanted(context, root))
            GameEnvironmentStore.save(context, GameEnvironment.Config(games = mapOf("42" to mapOf("BL_SYNC_FALLBACK" to "1"))))
            assertTrue(EsyncPacks.enabled(context))
            GameEnvironmentStore.save(context, GameEnvironment.Config(shared = mapOf("BL_SYNC_FALLBACK" to "1")))
            assertTrue(EsyncPacks.enabled(context))
            GameEnvironmentStore.save(context, GameEnvironment.Config(games = mapOf("42" to mapOf("BL_SYNC_FALLBACK" to "0"))))
            assertFalse(EsyncPacks.enabled(context))
            SessionPrefs.setSyncFallback(context, true)
            assertTrue(EsyncPacks.enabled(context))
        } finally {
            SessionPrefs.setSyncFallback(context, true)
            settings.delete()
        }
    }

    @Test fun wantedRowsAreDeduplicatedAndToolsParsed() {
        val wanted = EsyncPacks.parseWanted(
            "$stockNtdll\t$stockWineserver\tGE-Proton11-7\t$exports\t$toolDir\t1789520806\n" +
                "$stockNtdll\t$stockWineserver\tGE-Proton11-7\t$exports\t/elsewhere\t1789520900\n" +
                "short\tline\n" +
                "${"1".repeat(64)}\t${"2".repeat(64)}\texperimental-11.0-20260910b-arm64\t$exports\t/root/.local/share/Steam/steamapps/common/Proton Experimental (ARM64)\t5\r\n" +
                "${"3".repeat(64)}\t${"4".repeat(64)}\t-\t-\t/root/odd\t6\n",
        )
        assertEquals(3, wanted.size)
        assertEquals(toolDir, wanted[0].toolDir)
        assertEquals(1789520806L, wanted[0].firstSeen)
        assertEquals("/root/.local/share/Steam/steamapps/common/Proton Experimental (ARM64)", wanted[1].toolDir)
        assertEquals("", wanted[2].version)
        assertEquals("", wanted[2].exports)
        assertFalse(EsyncPacks.matches(entries(entryJson("valve-r1", sourceMatch = true, version = "")).single(), wanted[2]))
        val tools = EsyncPacks.parseTools(
            "$toolDir\t1789520806 GE-Proton11-7\t$stockNtdll\t$stockWineserver\tpack\tge-a-r1\n" +
                "/root/x\t1 y\t$stockNtdll\t$stockWineserver\twanted\t-\n",
        )
        assertEquals("ge-a-r1", tools[0].packId)
        assertEquals("wanted", tools[1].state)
        assertNull(tools[1].packId)
        assertEquals("pack", EsyncPacks.Status(1, 0, tools).tool("$toolDir/")?.state)
    }

    @Test fun statusCountsCompletePacksAndWantedRows() {
        val root = temp.newFolder("rootfs")
        val store = EsyncPacks.store(root)
        assertEquals(EsyncPacks.Status(0, 0, emptyList()), EsyncPacks.status(root))
        File(store, "packs/ge-a-r1").mkdirs()
        File(store, "packs/ge-a-r1/.complete").writeText("")
        File(store, "packs/ge-b-r1").mkdirs()
        File(store, "wanted.tsv").writeText("${"1".repeat(64)}\t${"2".repeat(64)}\tv\t$exports\t/t\t1\n")
        File(store, "tools.tsv").writeText("/t\t1 v\t${"1".repeat(64)}\t${"2".repeat(64)}\twanted\t-\n")
        val status = EsyncPacks.status(root)
        assertEquals(1, status.installed)
        assertEquals(1, status.wanted)
        assertEquals("wanted", status.tool("/t")?.state)
    }

    @Test fun statusCountsAnInstalledMatchingPackBeforeTheNextReconcile() {
        val root = temp.newFolder("rootfs")
        val store = EsyncPacks.store(root)
        val ntdll = "1".repeat(64)
        val wineserver = "2".repeat(64)
        fun install(id: String, pack: JSONObject, ignored: Boolean = false) {
            val dir = File(store, "packs/$id").apply { mkdirs() }
            File(dir, "pack.json").writeText(pack.toString())
            File(dir, ".complete").writeText("")
            if (ignored) File(dir, ".ignored").writeText("")
        }
        fun stock(n: String, w: String) = JSONObject().put(EsyncPacks.NTDLL, n).put(EsyncPacks.WINESERVER, w)
        store.mkdirs()
        File(store, "wanted.tsv").writeText("$ntdll\t$wineserver\tv-arm64\t$exports\t/t\t1\n")
        File(store, "tools.tsv").writeText(
            "/t\t1 v-arm64\t$ntdll\t$wineserver\twanted\t-\n/u\t1 other\t${"3".repeat(64)}\t${"4".repeat(64)}\twanted\t-\n" +
                "/b\t1 builtin\t${"5".repeat(64)}\t${"6".repeat(64)}\tbuiltin\t-\n",
        )
        install("valve-ignored-r1", JSONObject().put("rev", 1).put("stock", stock(ntdll, wineserver)), ignored = true)
        install("valve-other-r1", JSONObject().put("rev", 1).put("stock", stock("7".repeat(64), "8".repeat(64)))
            .put("source_match", true).put("version", "v-other").put("exports", exports))
        assertEquals(listOf("wanted", "wanted", "builtin"), EsyncPacks.status(root).tools.map { it.state })

        install("valve-source-r2", JSONObject().put("rev", 2).put("stock", stock("7".repeat(64), "8".repeat(64)))
            .put("source_match", true).put("version", "v-arm64").put("exports", exports))
        var tools = EsyncPacks.status(root).tools
        assertEquals("pack", tools[0].state)
        assertEquals("valve-source-r2", tools[0].packId)
        assertEquals("wanted", tools[1].state)
        assertEquals("builtin", tools[2].state)

        install("valve-exact-r1", JSONObject().put("rev", 1).put("stock", stock(ntdll, wineserver)))
        tools = EsyncPacks.status(root).tools
        assertEquals("valve-exact-r1", tools[0].packId)
        assertEquals(4, EsyncPacks.status(root).installed)
    }

    @Test fun unusedPacksArePrunedAfterAMonth() {
        val root = temp.newFolder("rootfs")
        val store = EsyncPacks.store(root)
        val day = 24L * 60 * 60 * 1000
        val now = 400L * day
        fun install(id: String, ageDays: Long) {
            val dir = File(store, "packs/$id").apply { mkdirs() }
            File(dir, ".complete").writeText("")
            dir.setLastModified(now - ageDays * day)
        }
        install("ge-tool-r1", 90)
        install("ge-dist-r1", 90)
        install("ge-bundled-r1", 90)
        install("ge-wanted-r1", 90)
        install("ge-old-r1", 31)
        install("ge-young-r1", 29)
        File(store, "dist/ge-dist-r1~01234567").mkdirs()
        File(store, "dist/ge-dist-r1~01234567/.droiddeck-esync.json").writeText(JSONObject().put("pack", "ge-dist-r1").toString())
        val index = EsyncPacks.parseIndex(indexBytes(1, entryJson("ge-wanted-r1"), entryJson("ge-other-r1", ntdll = "e".repeat(64))))
        val tools = listOf(EsyncPacks.ToolState("/t", "1 v", "1".repeat(64), "2".repeat(64), "pack", "ge-tool-r1"))
        val keep = EsyncPacks.keep(store, index, listOf(row()), tools, setOf("ge-bundled-r1"))
        assertEquals(setOf("ge-tool-r1", "ge-dist-r1", "ge-bundled-r1", "ge-wanted-r1"), keep)
        assertEquals(listOf("ge-old-r1"), EsyncPacks.prune(store, keep, now))
        assertEquals(setOf("ge-tool-r1", "ge-dist-r1", "ge-bundled-r1", "ge-wanted-r1", "ge-young-r1"), EsyncPacks.installedIds(store))
        assertTrue(File(store, "packs").listFiles().orEmpty().none { it.name.startsWith(".") })
        assertTrue(EsyncPacks.prune(store, keep, now).isEmpty())
    }

    @Test fun distPathsMatchTheStockOrTheLaunchedToolPath() {
        val root = temp.newFolder("rootfs")
        val dist = File(EsyncPacks.store(root), "dist")
        File(dist, "ge-a-r1~01234567").mkdirs()
        File(dist, "ge-a-r1~01234567/.droiddeck-esync.json").writeText(JSONObject().put("pack", "ge-a-r1").put("stock", toolDir).put("tool", toolDir).toString())
        File(dist, "ge-b-r1~89abcdef").mkdirs()
        File(dist, "ge-b-r1~89abcdef/.droiddeck-esync.json").writeText(JSONObject().put("pack", "ge-b-r1").put("stock", "/real/GE-Proton11-7").put("tool", "$toolDir/").toString())
        File(dist, "valve-x-r1~00000000").mkdirs()
        File(dist, "valve-x-r1~00000000/.droiddeck-esync.json").writeText(JSONObject().put("pack", "valve-x-r1").put("stock", "/other").put("tool", "/other").toString())
        File(dist, ".ge-c-r1~11111111.tmp-42").mkdirs()
        File(dist, "ge-a-r1~01234567.lock").writeText("")
        assertEquals(
            listOf("${EsyncPacks.GUEST_STORE}/dist/ge-a-r1~01234567", "${EsyncPacks.GUEST_STORE}/dist/ge-b-r1~89abcdef"),
            EsyncPacks.distPathsFor(root, "$toolDir/"),
        )
        assertEquals(listOf("${EsyncPacks.GUEST_STORE}/dist/ge-b-r1~89abcdef"), EsyncPacks.distPathsFor(root, "/real/GE-Proton11-7"))
        assertTrue(EsyncPacks.distPathsFor(root, "").isEmpty())
        assertTrue(EsyncPacks.distPathsFor(temp.newFolder("empty"), toolDir).isEmpty())
    }

    @Test fun refreshIsDueByAgeMissesAndFailures() {
        val hour = 60L * 60 * 1000
        val now = 1_000L * hour
        val cached = EsyncPacks.parseIndex(indexBytes(1, entryJson("ge-a-r1")))
        val unmatched = row(ntdll = "1".repeat(64), wineserver = "2".repeat(64))
        fun state(checked: Long, failed: Long = 0, misses: Int = 0, missAt: Long = 0) = JSONObject().apply {
            put("checkedAt", checked)
            if (failed > 0) put("failedAt", failed)
            if (misses > 0) put("misses", JSONObject().put(unmatched.key, JSONObject().put("n", misses).put("at", missAt)))
        }
        assertTrue(EsyncPacks.due(null, JSONObject(), emptyList(), now))
        assertFalse(EsyncPacks.due(null, state(0, failed = now - 60_000), emptyList(), now))
        assertTrue(EsyncPacks.due(cached, state(now - 13 * hour), emptyList(), now))
        assertFalse(EsyncPacks.due(cached, state(now - hour), emptyList(), now))
        assertFalse(EsyncPacks.due(cached, state(now - hour), listOf(row()), now))
        assertTrue(EsyncPacks.due(cached, state(now - hour), listOf(unmatched), now))
        assertFalse(EsyncPacks.due(cached, state(now - 10 * 60_000), listOf(unmatched), now))
        assertFalse(EsyncPacks.due(cached, state(now - 2 * hour, misses = 2, missAt = now - 40 * 60_000), listOf(unmatched), now))
        assertTrue(EsyncPacks.due(cached, state(now - 2 * hour, misses = 2, missAt = now - 61 * 60_000), listOf(unmatched), now))
        assertFalse(EsyncPacks.due(cached, state(now - 2 * hour, misses = 9, missAt = now - 11 * hour), listOf(unmatched), now))
        assertTrue(EsyncPacks.due(cached, state(now + hour), emptyList(), now))
    }
}
