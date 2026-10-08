package com.droiddeck.launcher.store

import com.droiddeck.launcher.R
import com.droiddeck.launcher.runtime.FlatpakManager
import org.junit.Assert.assertEquals
import org.junit.Test

class FlathubApiTest {
    @Test fun appstreamDescriptionsBecomeLines() {
        val html = "<p>A game &amp; more.</p><ul><li>Fast</li><li>Free</li></ul><p>Enjoy</p>"
        assertEquals("A game & more.\n• Fast\n• Free\n\nEnjoy", FlathubApi.plainText(html))
    }

    @Test fun refsReadAsWords() {
        // The words come from string resources; stand in for them with their English.
        val english = mapOf(
            R.string.flatpakmgr_ref_runtime_branch to "%1\$s runtime %2\$s",
            R.string.flatpakmgr_ref_graphics_drivers to "graphics drivers",
            R.string.flatpakmgr_ref_translations to "translations",
        )
        fun label(ref: String) = FlatpakManager.refLabel(ref) { id, args -> english.getValue(id).format(*args) }
        assertEquals("SuperTux", label("app/org.supertuxproject.SuperTux/aarch64/stable"))
        assertEquals("GNOME runtime 50", label("runtime/org.gnome.Platform/aarch64/50"))
        assertEquals("Freedesktop runtime 26.08", label("runtime/org.freedesktop.Platform/aarch64/26.08"))
        assertEquals("graphics drivers", label("runtime/org.freedesktop.Platform.GL.default/aarch64/26.08"))
        assertEquals("translations", label("runtime/org.gnome.Platform.Locale/aarch64/50"))
    }
}
