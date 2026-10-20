package com.droiddeck.launcher.ui

import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import com.droiddeck.launcher.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w960dp-h540dp-land", application = android.app.Application::class)
class ModeHeaderFocusTest {
    @get:Rule val compose = createRobolectricComposeRule()

    @Test fun reenteringCompositionWithNonEmptyQueryDoesNotStealFocus() {
        lateinit var root: View
        var show by mutableStateOf(true)
        var query by mutableStateOf("celeste")
        compose.setContent {
            DroidDeckTheme {
                val view = LocalView.current
                SideEffect { root = view.rootView }
                if (show) {
                    ModeHeader(
                        title = "Library",
                        query = query,
                        onQuery = { query = it },
                        placeholder = "Search",
                        trailing = {},
                    )
                }
            }
        }
        compose.waitForIdle()
        val initialField = editText(root)!!
        compose.runOnIdle {
            assertEquals("celeste", initialField.text.toString())
            assertFalse("Pre-populated search field must not auto-focus on mount", initialField.isFocused)
            show = false
        }
        compose.waitForIdle()
        compose.runOnIdle { show = true }
        compose.waitForIdle()
        val remountedField = editText(root)!!
        compose.runOnIdle {
            assertEquals("celeste", remountedField.text.toString())
            assertFalse("Remounted search field must not steal focus when scrolled back into view", remountedField.isFocused)
        }
    }

    @Test fun clickingSearchIconFocusesField() {
        lateinit var root: View
        var query by mutableStateOf("")
        compose.setContent {
            DroidDeckTheme {
                val view = LocalView.current
                SideEffect { root = view.rootView }
                ModeHeader(
                    title = "Library",
                    query = query,
                    onQuery = { query = it },
                    placeholder = "Search",
                    trailing = {},
                )
            }
        }
        val context = RuntimeEnvironment.getApplication()
        compose.onNodeWithContentDescription(context.getString(R.string.stores_search_open)).performClick()
        compose.waitForIdle()
        val field = editText(root)!!
        compose.runOnIdle {
            assertTrue("Opening search via icon must focus the text field", field.isFocused)
        }
    }

    private fun editText(view: View): EditText? {
        if (view is EditText) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) {
            editText(view.getChildAt(i))?.let { return it }
        }
        return null
    }
}
