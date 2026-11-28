package com.droiddeck.launcher

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SessionRelaunchTest {
    // recreate() keeps the window, and Compose content set on it afterwards never recomposes (#2668):
    // a session relaunched that way kept its loading screen up over a running desktop.
    @Test fun sessionAndLauncherActivitiesNeverRecreateThemselves() {
        listOf("SessionActivity.kt", "FrontEndActivity.kt").forEach { name ->
            val code = File("src/main/java/com/droiddeck/launcher/$name").readLines()
                .map { it.substringBefore("//") }
            val calls = code.withIndex().filter { (_, line) -> Regex("""\brecreate\(\)""").containsMatchIn(line) }
            assertTrue("$name calls recreate() at line(s) ${calls.map { it.index + 1 }}", calls.isEmpty())
        }
    }
}
