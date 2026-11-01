package com.droiddeck.launcher.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalView
import com.droiddeck.launcher.stores.xbox.XboxMenuEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w960dp-h540dp-land")
class FocusGlideWindowsTest {
    @get:Rule val compose = createRobolectricComposeRule()

    @Test fun standaloneThemedControlsShareTheGlide() {
        lateinit var glide: FocusGlide
        val focus = List(6) { FocusRequester() }
        compose.setContent {
            DroidDeckTheme {
                glide = LocalFocusGlide.current!!
                Column {
                    TextButton(onClick = {}, modifier = Modifier.focusRequester(focus[0]).focusGlide()) { Text("Action") }
                    IconButton(onClick = {}, modifier = Modifier.focusRequester(focus[1]).focusGlide()) { Text("Icon") }
                    Switch(false, {}, modifier = Modifier.focusRequester(focus[2]).focusGlide())
                    Slider(0.5f, {}, modifier = Modifier.focusRequester(focus[3]).focusGlide())
                    OutlinedTextField("Value", {}, modifier = Modifier.focusRequester(focus[4]).focusGlide())
                    XboxMenuEntry("Xbox action", null, focus = focus[5], onClick = {})
                }
            }
        }
        var previous: GlideSource? = null
        for (requester in focus) {
            compose.runOnIdle { requester.requestFocus() }
            compose.runOnIdle {
                val source = glide.current
                assertNotNull(source)
                assertNotSame(previous, source)
                assertEquals(1, glide.hot.size)
                assertNotNull(glide.boundsOf(source!!))
                previous = source
            }
        }
    }

    @Test fun alertDialogButtonsShareTheirOwnWindowGlide() {
        lateinit var parent: FocusGlide
        lateinit var dialog: FocusGlide
        val focus = List(2) { FocusRequester() }
        compose.setContent {
            DroidDeckTheme {
                parent = LocalFocusGlide.current!!
                GlideAlertDialog(
                    onDismissRequest = {},
                    title = { Text("Dialog") },
                    confirmButton = {
                        dialog = LocalFocusGlide.current!!
                        val view = LocalView.current
                        SideEffect { assertSame(view, dialog.view) }
                        TextButton(onClick = {}, modifier = Modifier.focusRequester(focus[0]).focusGlide()) { Text("Confirm") }
                    },
                    dismissButton = {
                        TextButton(onClick = {}, modifier = Modifier.focusRequester(focus[1]).focusGlide()) { Text("Cancel") }
                    },
                )
            }
        }
        assertNotSame(parent, dialog)
        assertWindowHandoff(dialog, focus)
    }

    @Test fun customDialogsHaveAHostInsideTheirWindow() {
        lateinit var parent: FocusGlide
        lateinit var dialog: FocusGlide
        val focus = List(2) { FocusRequester() }
        compose.setContent {
            DroidDeckTheme {
                parent = LocalFocusGlide.current!!
                GlideDialog(onDismissRequest = {}) {
                    dialog = LocalFocusGlide.current!!
                    Column {
                        SecondaryButton("First", modifier = Modifier.focusRequester(focus[0])) {}
                        SecondaryButton("Second", modifier = Modifier.focusRequester(focus[1])) {}
                    }
                }
            }
        }
        assertNotSame(parent, dialog)
        assertWindowHandoff(dialog, focus)
    }

    @Test fun dropdownItemsShareTheirOwnWindowGlide() {
        lateinit var parent: FocusGlide
        lateinit var menu: FocusGlide
        val focus = List(2) { FocusRequester() }
        var expanded by mutableStateOf(true)
        compose.setContent {
            DroidDeckTheme {
                parent = LocalFocusGlide.current!!
                GlideDropdownMenu(expanded = expanded, onDismissRequest = {}) {
                    menu = LocalFocusGlide.current!!
                    for (i in focus.indices) DropdownMenuItem(
                        text = { Text("Item $i") }, onClick = {},
                        modifier = Modifier.focusRequester(focus[i]).focusGlide(),
                    )
                }
            }
        }
        assertNotSame(parent, menu)
        assertWindowHandoff(menu, focus)
        compose.runOnIdle { expanded = false }
        compose.waitForIdle()
        compose.runOnIdle { expanded = true }
        compose.waitForIdle()
        assertWindowHandoff(menu, focus)
    }

    private fun assertWindowHandoff(glide: FocusGlide, focus: List<FocusRequester>) {
        var previous: GlideSource? = null
        for (requester in focus) {
            compose.runOnIdle { requester.requestFocus() }
            compose.runOnIdle {
                assertNotNull(glide.host)
                val current = glide.current
                assertNotNull(current)
                assertNotSame(previous, current)
                assertTrue(glide.boundsOf(current!!)!!.width > 0f)
                previous = current
            }
        }
    }
}
