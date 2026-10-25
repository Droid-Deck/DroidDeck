package com.droiddeck.launcher.core

import com.droiddeck.launcher.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "b+es+419")
class FileManagerMessageTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test fun existingFileMessageIncludesTheQuotedFilenameWithoutEscapeCharacters() {
        assertEquals("\"part%1.zip\" ya existe", context.getString(R.string.fm_already_exists, "part%1.zip"))
    }

    @Test fun removedFavoriteMessageIncludesTheQuotedFilenameWithoutEscapeCharacters() {
        assertEquals("Se quitó \"part%1.zip\" de Favoritos", context.getString(R.string.fm_favorite_removed, "part%1.zip"))
    }
}
