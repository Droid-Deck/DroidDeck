package com.droiddeck.launcher.store

import com.droiddeck.launcher.runtime.FlatpakManager
import org.junit.Assert.assertEquals
import org.junit.Test

class FlathubApiTest {
    @Test fun appstreamDescriptionsBecomeLines() {
        val html = "<p>A game &amp; more.</p><ul><li>Fast</li><li>Free</li></ul><p>Enjoy</p>"
        assertEquals("A game & more.\n• Fast\n• Free\n\nEnjoy", FlathubApi.plainText(html))
    }

    @Test fun refsReadAsWords() {
        assertEquals("SuperTux", FlatpakManager.refLabel("app/org.supertuxproject.SuperTux/aarch64/stable"))
        assertEquals("GNOME runtime 50", FlatpakManager.refLabel("runtime/org.gnome.Platform/aarch64/50"))
        assertEquals("Freedesktop runtime 26.08", FlatpakManager.refLabel("runtime/org.freedesktop.Platform/aarch64/26.08"))
        assertEquals("graphics drivers", FlatpakManager.refLabel("runtime/org.freedesktop.Platform.GL.default/aarch64/26.08"))
        assertEquals("translations", FlatpakManager.refLabel("runtime/org.gnome.Platform.Locale/aarch64/50"))
    }
}
