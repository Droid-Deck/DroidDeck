package com.droiddeck.launcher.stores.epic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EpicSaveTemplateTest {
    @Test fun rootsAreCaseInsensitive() {
        val p = EpicSaveTemplate.parse("{appdata}\\Game\\Saved", "acc", "app")!!
        assertEquals(EpicSaveTemplate.Root.LOCAL_APPDATA, p.root)
        assertEquals("/Game/Saved", p.rest)
        assertEquals(EpicSaveTemplate.Root.LOCAL_APPDATA, EpicSaveTemplate.parse("{LocalAppData}/x", "a", "b")!!.root)
        assertEquals(EpicSaveTemplate.Root.ROAMING_APPDATA, EpicSaveTemplate.parse("{RoamingAppData}/x", "a", "b")!!.root)
        assertEquals(EpicSaveTemplate.Root.DOCUMENTS, EpicSaveTemplate.parse("{UserDir}/x", "a", "b")!!.root)
        assertEquals(EpicSaveTemplate.Root.SAVED_GAMES, EpicSaveTemplate.parse("{UserSavedGames}/x", "a", "b")!!.root)
        assertEquals(EpicSaveTemplate.Root.USER_PROFILE, EpicSaveTemplate.parse("{UserProfile}/x", "a", "b")!!.root)
        assertEquals(EpicSaveTemplate.Root.INSTALL_DIR, EpicSaveTemplate.parse("{InstallDir}/x", "a", "b")!!.root)
    }

    @Test fun placeholdersAreFilledLiterally() {
        val p = EpicSaveTemplate.parse("{UserSavedGames}/S/{epicid}/{APPNAME}/x", "a\$1", "B")!!
        assertEquals("/S/a\$1/B/x", p.rest)
    }

    @Test fun unknownOrMissingRootIsNull() {
        assertNull(EpicSaveTemplate.parse("{Nope}/x", "a", "b"))
        assertNull(EpicSaveTemplate.parse("Saves/x", "a", "b"))
        assertNull(EpicSaveTemplate.parse("", "a", "b"))
    }
}
