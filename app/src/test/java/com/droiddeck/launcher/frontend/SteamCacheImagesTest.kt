package com.droiddeck.launcher.frontend

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SteamCacheImagesTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun image(path: String, bytes: ByteArray = byteArrayOf(1)): File =
        File(tmp.root, path).apply { parentFile!!.mkdirs(); writeBytes(bytes) }

    private class CountingDirectory(directory: File) : File(directory.path) {
        var listings = 0
            private set

        override fun listFiles(): Array<File>? {
            listings++
            return super.listFiles()?.sortedByDescending { it.name }?.toTypedArray()
        }
    }

    @Test fun allArtworkAndTheHashIconShareOneDirectoryListing() {
        val cover = image("620/library_capsule.jpg")
        val hero = image("620/b/library_hero.jpg")
        val logo = image("620/a/logo.png")
        val icon = image("620/${"a".repeat(40)}.png")
        val directory = CountingDirectory(File(tmp.root, "620"))
        val images = Library.SteamCacheImages(directory)

        assertEquals(cover, images.find(listOf("library_capsule.jpg", "library_600x900.jpg")))
        assertEquals(hero, images.find(listOf("library_hero.jpg")))
        assertEquals(logo, images.find(Library.STEAM_LOGOS))
        assertEquals(icon, images.icon())
        assertEquals(1, directory.listings)
    }

    @Test fun preferredFilenamesWinEvenWhenOnlyTheAlternativeIsInTheRoot() {
        image("620/library_600x900.jpg")
        val preferred = image("620/hash/library_capsule.jpg")

        assertEquals(preferred, Library.steamCacheImage(
            tmp.root, 620, listOf("library_capsule.jpg", "library_600x900.jpg"),
        ))
    }

    @Test fun theRootWinsOverHashDirectoriesForTheSameFilename() {
        image("620/hash/logo.png")
        val root = image("620/logo.png")

        assertEquals(root, Library.steamCacheImage(tmp.root, 620, Library.STEAM_LOGOS))
    }

    @Test fun hashDirectoriesAreSearchedByNameNotListingOrder() {
        image("620/z/logo.png")
        val first = image("620/a/logo.png")
        val directory = CountingDirectory(File(tmp.root, "620"))

        assertEquals(first, Library.SteamCacheImages(directory).find(Library.STEAM_LOGOS))
        assertEquals(1, directory.listings)
    }

    @Test fun emptyFilesAndDirectoriesAreNotArtwork() {
        image("620/logo.png", byteArrayOf())
        File(tmp.root, "620/a/logo.png").mkdirs()
        val valid = image("620/b/logo.png")

        assertEquals(valid, Library.steamCacheImage(tmp.root, 620, Library.STEAM_LOGOS))
    }

    @Test fun namedIconsKeepTheirFilenamePriorityOverHashIcons() {
        image("620/${"a".repeat(40)}.png")
        image("620/icon.png")
        val preferred = image("620/hash/icon.jpg")

        assertEquals(preferred, Library.SteamCacheImages(File(tmp.root, "620")).icon())
    }

    @Test fun hashIconsKeepTheOriginalRootListingOrderAndAllowEmptyFiles() {
        image("620/${"0".repeat(40)}.jpg")
        val first = image("620/${"F".repeat(40)}.png", byteArrayOf())
        File(tmp.root, "620/${"z".repeat(40)}.png").mkdirs()
        image("620/${"g".repeat(40)}.png")
        val directory = CountingDirectory(File(tmp.root, "620"))
        val images = Library.SteamCacheImages(directory)

        assertEquals(first, images.icon())
        assertEquals(1, directory.listings)
    }

    @Test fun hashIconsOutsideTheRootAndInvalidHashesAreIgnored() {
        image("620/hash/${"a".repeat(40)}.jpg")
        image("620/${"g".repeat(40)}.png")
        image("620/${"a".repeat(39)}.png")

        assertNull(Library.SteamCacheImages(File(tmp.root, "620")).icon())
    }

    @Test fun missingDirectoriesAreListedOnlyOnce() {
        val directory = CountingDirectory(File(tmp.root, "missing"))
        val images = Library.SteamCacheImages(directory)

        assertNull(images.find(Library.STEAM_LOGOS))
        assertNull(images.icon())
        assertEquals(1, directory.listings)
    }

    @Test fun aNewLookupSeesHashDirectoriesAddedAfterThePreviousScan() {
        image("620/unrelated.txt")
        assertNull(Library.steamCacheImage(tmp.root, 620, Library.STEAM_LOGOS))
        val added = image("620/new-hash/logo.png")

        assertEquals(added, Library.steamCacheImage(tmp.root, 620, Library.STEAM_LOGOS))
    }
}
