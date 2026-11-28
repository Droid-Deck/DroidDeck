package com.droiddeck.launcher.ui

import android.os.Environment
import com.droiddeck.launcher.R
import com.droiddeck.launcher.sync.Syncthing
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SyncFolderPickerTest {
    private val context = RuntimeEnvironment.getApplication()
    private val internal = Environment.getExternalStorageDirectory()
    private val music = File(internal, "Music")

    @Test fun sharedStorageListsInternalStorageAndAllowsItsMusicFolder() {
        val locations = syncLocations(context, sharedStorage = true)
        val guard = syncPickerGuard(context, locations, sharedStorage = true)

        assertTrue(locations.any { it.root.path == internal.path && it.label == context.getString(R.string.games_internal) })
        assertTrue(guard.allows(music.path))
        assertTrue(guard.browsable(music))
    }

    @Test fun sharedStorageKeepsTheSyncthingConfigBlocked() {
        val guard = syncPickerGuard(context, syncLocations(context, sharedStorage = true), sharedStorage = true)

        assertFalse(guard.allows(Syncthing.home(context).path))
        assertFalse(guard.browsable(Syncthing.home(context)))
    }

    @Test fun theSyncthingPickerStaysInsideAppStorage() {
        val locations = syncLocations(context)
        val guard = syncPickerGuard(context, locations, sharedStorage = false)

        assertFalse(locations.any { it.root.path == internal.path })
        assertFalse(guard.allows(music.path))
        assertTrue(guard.allows(Syncthing.folderRoot(context).path))
    }
}
