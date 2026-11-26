package com.droiddeck.launcher.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.onNodeWithText
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TextEntrySubmissionTest {
    @get:Rule val compose = createRobolectricComposeRule()

    @Test fun duplicateSubmitDuringClosingIsIgnored() {
        var submissions = 0
        compose.setContent {
            DroidDeckTheme {
                TextEntryDialog("PIN", "2468", true, "Submit", { submissions++ }, {}, secret = true)
            }
        }
        val submit = compose.onNodeWithText("Submit").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnIdle { submit(); submit() }
        assertEquals(1, submissions)
    }

    @Test fun submitAfterCancelStartsIsIgnored() {
        var submissions = 0
        compose.setContent {
            DroidDeckTheme {
                TextEntryDialog("PIN", "2468", true, "Submit", { submissions++ }, {}, secret = true)
            }
        }
        val submit = compose.onNodeWithText("Submit").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        val cancel = compose.onNodeWithText("Cancel").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnIdle { cancel(); submit() }
        assertEquals(0, submissions)
    }
}
