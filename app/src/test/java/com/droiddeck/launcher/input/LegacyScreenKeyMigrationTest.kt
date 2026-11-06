package com.droiddeck.launcher.input

import android.content.Context
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class LegacyScreenKeyMigrationTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("input-profiles", Context.MODE_PRIVATE)
    @Before fun reset() {
        for (name in listOf("input-profiles", "input-surfaces", "controller")) context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun upgradeImportsEverySavedSetWithoutChangingExistingProfilesAndIsIdempotent() {
        val original = InputLibraries.read(context).selectProfile(false, "desktop")
        prefs.edit().putString("library", JSONObject(InputLibraries.encode(original)).put("version", 5).toString()).commit()
        ControllerPrefs.prefs(context).edit().putString("screenKeys", "29+33:0.3,0.7,44::Find::0.6:1:1:1")
            .putBoolean("screenKeysShown", false).putString("screenLayout.Editor", "28:0.8,0.6,50::::1.0:2:0:1").commit()
        val migrated = InputLibraries.read(context)
        assertEquals(original.profiles, migrated.profiles.take(original.profiles.size))
        assertEquals(original.selections, migrated.selections)
        assertEquals(2, migrated.profiles.count { it.id.startsWith("migrated.screenkeys.") })
        val imported = migrated.profiles.first { it.name == "Screen keys (imported)" }
        val layout = migrated.layout(imported.id, InputRole.PRIMARY)!!
        assertEquals(InputLayoutScreen.MAIN, layout.screen)
        assertTrue(layout.elements.single().toggle)
        assertEquals(InputAction.Keys(listOf(29, 33)), migrated.compile(imported.id).elements[InputRole.PRIMARY]!!.values.single())
        assertFalse(ControllerPrefs.prefs(context).contains("screenKeys"))
        assertTrue(prefs.contains(LegacyScreenKeyMigration.BACKUP))
        assertEquals(migrated, InputLibraries.read(context))
        assertEquals(migrated, InputLibraries.decode(InputLibraries.encode(migrated)))
    }

    @Test fun conversionRetainsCoordinatesImagesOpacitySymbolsAndIndependentDiagonalKeys() {
        val values = LegacyScreenKeyMigration.convert(context,
            "28:0.25,0.6,50::::0.8:2:0:1;29:0.7,0.8,40::Ctrl:custom.png:0.4:3:1:1;103+30+106+0+108+0+105+0:0.5,0.5,80::::1:6:0:1", 1000, 500, 2f)
        val enter = values[0].first
        assertEquals(.25f, enter.x + enter.width / 2, .0001f)
        assertEquals(.6f, enter.y + enter.height / 2, .0001f)
        assertEquals(.1f, enter.width, .0001f)
        assertEquals(.2f, enter.height, .0001f)
        assertEquals(28, enter.icon)
        assertEquals(InputButtonShape.CIRCLE, enter.buttonShape)
        assertEquals("custom.png", values[1].first.image)
        assertEquals(.4f, values[1].first.opacity)
        assertTrue(values[1].first.toggle)
        assertEquals(InputAction.Directions(103, 106, 108, 105, listOf(30, 0, 0, 0)), values[2].second)
        assertFalse(values[2].first.floating)
    }

    @Test fun invalidMigrationDoesNotEraseTheOriginalSavedLibraryOrKeys() {
        val original = JSONObject(InputLibraries.encode(InputLibraries.read(context))).put("version", 5).toString()
        prefs.edit().putString("library", original).commit()
        ControllerPrefs.prefs(context).edit().putString("screenKeys", "28:NaN,0.5,44").commit()
        InputLibraries.read(context)
        assertEquals(original, prefs.getString("library", null))
        assertEquals("28:NaN,0.5,44", ControllerPrefs.prefs(context).getString("screenKeys", null))
        assertFalse(prefs.contains(LegacyScreenKeyMigration.BACKUP))
    }
}
