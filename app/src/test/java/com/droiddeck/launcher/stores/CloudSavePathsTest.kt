package com.droiddeck.launcher.stores

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A store's save-location template, made concrete inside the game's prefix (Bannerlator's cases). */
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
        // Epic's folder names are matched by letters and digits alone when that names exactly one.
        File(profile, "AppData/Local/My-Game").mkdirs()
        assertEquals(File(profile, "AppData/Local/My-Game").path, CloudSavePaths.epic("{AppData}/MyGame", prefix, install, "acc", "app")!!.path)
    }
}
