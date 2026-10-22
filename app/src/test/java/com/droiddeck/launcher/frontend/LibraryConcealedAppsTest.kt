package com.droiddeck.launcher.frontend

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LibraryConcealedAppsTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun steamRoot(): File = tmp.newFolder("Steam").also { root ->
        File(root, "config").mkdirs()
        File(root, "config/loginusers.vdf").writeText(
            "\"users\"\n{\n\t\"76561198352563669\"\n\t{\n\t\t\"AccountName\"\t\t\"a\"\n\t\t\"MostRecent\"\t\t\"1\"\n\t}\n}\n")
    }

    private fun config(root: File) = File(root, "userdata/392297941/config").apply { mkdirs() }

    @Test fun hiddenCollectionAndPrivateAppsAreBothConcealed() {
        val root = steamRoot()
        val config = config(root)
        File(config, "cloudstorage").mkdirs()
        File(config, "cloudstorage/cloud-storage-namespace-1.json").writeText(
            """[["user-collections.favorite",{"key":"user-collections.favorite","value":"{\"id\":\"favorite\",\"added\":[620],\"removed\":[]}"}],""" +
                """["user-collections.hidden",{"key":"user-collections.hidden","value":"{\"id\":\"hidden\",\"name\":\"Hidden\",\"added\":[223470,2931908220,400],\"removed\":[400]}"}]]""")
        File(config, "localconfig.vdf").writeText(
            "\"UserLocalConfigStore\"\n{\n\t\"WebStorage\"\n\t{\n\t\t\"PrivateApps_392297941\"\t\t\"[339800,567090]\"\n\t}\n}\n")
        assertEquals(setOf(223470L, 2931908220L, 339800L, 567090L), Library.concealedSteamApps(root))
    }

    @Test fun deletedHiddenCollectionAndMissingFilesConcealNothing() {
        val root = steamRoot()
        assertEquals(emptySet<Long>(), Library.concealedSteamApps(root))
        val config = config(root)
        File(config, "cloudstorage").mkdirs()
        File(config, "cloudstorage/cloud-storage-namespace-1.json").writeText(
            """[["user-collections.hidden",{"key":"user-collections.hidden","is_deleted":true}]]""")
        assertEquals(emptySet<Long>(), Library.concealedSteamApps(root))
    }
}
