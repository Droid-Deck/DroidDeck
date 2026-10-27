package com.droiddeck.launcher.stores.gog

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GogGameExeTest {
    private fun withFolder(block: (File) -> Unit) {
        val root = kotlin.io.path.createTempDirectory("gog-exe").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun File.exe(path: String, size: Int = 1024) {
        val file = File(this, path)
        file.parentFile?.mkdirs()
        file.writeBytes(ByteArray(size))
    }

    // #680: Blood Omen installs dxconfig.exe next to kain.exe, and the config tool won.
    @Test fun theGameExecutableWinsOverTheConfigToolInTheSameFolder() {
        withFolder { root ->
            root.exe("dxconfig.exe")
            root.exe("kain.exe", 4096)
            assertEquals("kain.exe", GogDownloadManager.pickExe(root, null, null, "Blood Omen: Legacy of Kain"))
        }
    }

    @Test fun aConfigToolInTheGameFolderIsNeverTheFoundExecutable() {
        withFolder { root ->
            root.exe("dxconfig.exe")
            root.exe("bin/kain.exe", 4096)
            assertEquals("bin/kain.exe", GogDownloadManager.pickExe(root, null, null, "Blood Omen: Legacy of Kain"))
        }
    }

    @Test fun theLauncherInTheGameRootLosesToTheGameExecutable() {
        withFolder { root ->
            root.exe("Launcher.exe")
            root.exe("bin/witcher2.exe", 4096)
            assertEquals(
                "bin/witcher2.exe",
                GogDownloadManager.pickExe(root, null, null, "The Witcher 2: Assassins of Kings Enhanced Edition"),
            )
        }
    }

    @Test fun theInstallerHintWinsOverTheSearch() {
        withFolder { root ->
            root.exe("dxconfig.exe")
            root.exe("bin/game.exe", 4096)
            assertEquals("bin/game.exe", GogDownloadManager.pickExe(root, null, "bin/game.exe", "Blood Omen: Legacy of Kain"))
        }
    }

    @Test fun aFolderWithoutAGameExecutableYieldsNoPath() {
        withFolder { root ->
            root.exe("redist/vcredist_x64.exe")
            assertEquals("", GogDownloadManager.pickExe(root, null, null, "Blood Omen: Legacy of Kain"))
        }
    }

    @Test fun theManifestsGameTaskIsTheExecutable() {
        withFolder { root ->
            root.exe("dxconfig.exe")
            root.exe("bin/omen.exe", 4096)
            File(root, "goggame-1207659641.info").writeText(
                """{"gameId":"1207659641","playTasks":[
                    {"category":"tool","path":"dxconfig.exe","type":"FileTask"},
                    {"category":"game","isPrimary":true,"path":"bin\\omen.exe","type":"FileTask"},
                    {"category":"document","path":"manual.pdf","type":"FileTask"}
                ]}""",
            )
            assertEquals("bin/omen.exe", GogDownloadManager.pickExe(root, "1207659641", null, "Blood Omen: Legacy of Kain"))
        }
    }

    @Test fun aManifestWithOnlyAToolTaskLeavesTheSearchInCharge() {
        withFolder { root ->
            root.exe("dxconfig.exe")
            root.exe("kain.exe", 4096)
            File(root, "goggame-1207659641.info")
                .writeText("""{"playTasks":[{"category":"tool","path":"dxconfig.exe","type":"FileTask"}]}""")
            assertEquals("kain.exe", GogDownloadManager.pickExe(root, null, null, "Blood Omen: Legacy of Kain"))
        }
    }

    @Test fun aManifestPathOutsideTheGameFolderIsIgnored() {
        withFolder { root ->
            root.exe("kain.exe", 4096)
            val outside = File(root.parentFile, "outside-${System.nanoTime()}.exe").apply { writeBytes(ByteArray(8)) }
            try {
                File(root, "goggame-1207659641.info").writeText(
                    """{"playTasks":[{"category":"game","isPrimary":true,"path":"../${outside.name}","type":"FileTask"}]}""",
                )
                assertEquals("kain.exe", GogDownloadManager.pickExe(root, null, null, "Blood Omen: Legacy of Kain"))
            } finally {
                outside.delete()
            }
        }
    }

    @Test fun aPrimaryLauncherTaskIsTheGameOfLauncherGames() {
        withFolder { root ->
            root.exe("run.exe")
            root.exe("game.exe", 4096)
            File(root, "goggame-7.info").writeText(
                """{"playTasks":[
                    {"category":"launcher","isPrimary":true,"path":"run.exe","type":"FileTask"},
                    {"category":"game","path":"game.exe","type":"FileTask"}
                ]}""",
            )
            assertEquals("run.exe", GogDownloadManager.pickExe(root, "7", null, "Some Game"))
        }
    }

    @Test fun aPrimaryGameTaskBeatsAPrimaryLauncherTask() {
        withFolder { root ->
            root.exe("run.exe")
            root.exe("game.exe", 4096)
            File(root, "goggame-7.info").writeText(
                """{"playTasks":[
                    {"category":"launcher","isPrimary":true,"path":"run.exe","type":"FileTask"},
                    {"category":"game","isPrimary":true,"path":"game.exe","type":"FileTask"}
                ]}""",
            )
            assertEquals("game.exe", GogDownloadManager.pickExe(root, "7", null, "Some Game"))
        }
    }

    @Test fun theProductsOwnManifestDecidesOverADlcManifest() {
        withFolder { root ->
            root.exe("base.exe")
            root.exe("dlc.exe", 4096)
            File(root, "goggame-7.info")
                .writeText("""{"playTasks":[{"category":"game","isPrimary":true,"path":"base.exe","type":"FileTask"}]}""")
            File(root, "goggame-9.info")
                .writeText("""{"playTasks":[{"category":"game","isPrimary":true,"path":"dlc.exe","type":"FileTask"}]}""")
            assertEquals("base.exe", GogDownloadManager.pickExe(root, "7", null, "Some Game"))
        }
    }

    @Test fun aDlcManifestSuppliesTheGameWhenTheProductsOwnHasNone() {
        withFolder { root ->
            root.exe("dxconfig.exe")
            root.exe("dlc.exe", 4096)
            File(root, "goggame-7.info")
                .writeText("""{"playTasks":[{"category":"tool","path":"dxconfig.exe","type":"FileTask"}]}""")
            File(root, "goggame-9.info")
                .writeText("""{"playTasks":[{"category":"game","isPrimary":true,"path":"dlc.exe","type":"FileTask"}]}""")
            assertEquals("dlc.exe", GogDownloadManager.pickExe(root, "7", null, "Some Game"))
        }
    }

    @Test fun theManifestBeatsTheInstallerHint() {
        withFolder { root ->
            root.exe("dxconfig.exe")
            root.exe("kain.exe", 4096)
            File(root, "goggame-7.info")
                .writeText("""{"playTasks":[{"category":"game","isPrimary":true,"path":"kain.exe","type":"FileTask"}]}""")
            assertEquals(
                "kain.exe",
                GogDownloadManager.pickExe(root, "7", "dxconfig.exe", "Blood Omen: Legacy of Kain"),
            )
        }
    }
}
