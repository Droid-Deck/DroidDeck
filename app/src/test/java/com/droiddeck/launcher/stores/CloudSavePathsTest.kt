package com.droiddeck.launcher.stores

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CloudSavePathsTest {
    private val root = Files.createTempDirectory("cloud").toFile()
    private val prefix = File(root, "compatdata/3785150007/pfx").apply { mkdirs() }
    private val install = File(root, "Games/Stores/GOG/ELDERBORN").apply { mkdirs() }
    private val profile = File(prefix, "drive_c/users/steamuser")

    @Test fun gogElderbornLivesInLocalLow() {
        val dir = CloudSavePaths.gog("<?APPLICATION_DATA_LOCAL_LOW?>/Hyperstrange/ELDERBORN", prefix, install)
        assertEquals(File(profile, "AppData/LocalLow/Hyperstrange/ELDERBORN").path, dir!!.path)
    }

    @Test fun gogTokensAndEnvironmentStyle() {
        assertEquals(File(profile, "Saved Games/X").path, CloudSavePaths.gog("<?SAVED_GAMES?>/X", prefix, install)!!.path)
        assertEquals(File(profile, "Documents/My Games/Y").path, CloudSavePaths.gog("<?DOCUMENTS?>\\My Games\\Y", prefix, install)!!.path)
        assertEquals(File(profile, "AppData/Roaming/Z").path, CloudSavePaths.gog("%APPDATA%/Z", prefix, install)!!.path)
        assertEquals(File(install, "saves").path, CloudSavePaths.gog("<?INSTALL?>/saves", prefix, install)!!.path)
        assertNull(CloudSavePaths.gog("<?SOMETHING_ELSE?>/x", prefix, install))
        assertNull(CloudSavePaths.gog("no/token", prefix, install))
    }

    @Test fun aPathThatLeavesItsBoundaryIsRefused() {
        assertNull(CloudSavePaths.gog("<?SAVED_GAMES?>/../../../../outside", prefix, install))
        assertNull(CloudSavePaths.epic("{InstallDir}/../other", prefix, install, "acc", "app"))
    }

    @Test fun anExistingFolderIsMatchedWhateverItsCase() {
        File(profile, "AppData/LocalLow/hyperstrange/elderborn").mkdirs()
        val dir = CloudSavePaths.gog("<?APPLICATION_DATA_LOCAL_LOW?>/Hyperstrange/ELDERBORN", prefix, install)
        assertEquals(File(profile, "AppData/LocalLow/hyperstrange/elderborn").path, dir!!.path)
    }

    @Test fun epicTokensAndPlaceholders() {
        assertEquals(File(profile, "AppData/Local/Metalstorm/Saved/SaveGames").path,
            CloudSavePaths.epic("{AppData}/Metalstorm/Saved/SaveGames", prefix, install, "acc", "app")!!.path)
        assertEquals(File(profile, "Saved Games/Studio/acc/app").path,
            CloudSavePaths.epic("{UserSavedGames}/Studio/{EpicId}/{AppName}", prefix, install, "acc", "app")!!.path)
        assertEquals(File(profile, "Documents/G").path, CloudSavePaths.epic("{UserDir}/G", prefix, install, "acc", "app")!!.path)
        assertNull(CloudSavePaths.epic("{Nope}/G", prefix, install, "acc", "app"))
        File(profile, "AppData/Local/My-Game").mkdirs()
        assertEquals(File(profile, "AppData/Local/My-Game").path, CloudSavePaths.epic("{AppData}/MyGame", prefix, install, "acc", "app")!!.path)
    }

    @Test fun gogGalaxySdkEmptyLocationsUseDefaultLocation() {
        val loc = CloudSavePaths.gogSaveFromRemote(true, emptyList(), "1")!!
        assertEquals(
            "%LocalAppData%/GOG.com/Galaxy/Applications/1/Storage/Shared/Files",
            loc.template,
        )
        assertEquals(CloudSavePaths.GOG_DEFAULT_NAMESPACE, loc.name)
        val dir = CloudSavePaths.gog(loc.template, prefix, install)
        assertEquals(
            File(profile, "AppData/Local/GOG.com/Galaxy/Applications/1/Storage/Shared/Files").path,
            dir!!.path,
        )
    }

    @Test fun gogCloudDisabledOrUnknownClientIsNone() {
        assertNull(CloudSavePaths.gogSaveFromRemote(false, emptyList(), "1"))
        assertNull(CloudSavePaths.gogSaveFromRemote(true, emptyList(), ""))
        assertNull(CloudSavePaths.gogSaveFromRemote(true, listOf("saves" to ""), ""))
    }

    @Test fun gogNamedLocationKeepsItsNameAndPreferSaves() {
        val loc = CloudSavePaths.gogSaveFromRemote(
            true,
            listOf("other" to "<?SAVED_GAMES?>/A", "Saves" to "<?APPLICATION_DATA_LOCAL_LOW?>/Hyperstrange/ELDERBORN"),
            "1",
        )!!
        assertEquals("<?APPLICATION_DATA_LOCAL_LOW?>/Hyperstrange/ELDERBORN", loc.template)
        assertEquals("Saves", loc.name)
    }
}
