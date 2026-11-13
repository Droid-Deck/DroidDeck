package com.droiddeck.launcher.ui

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.droiddeck.launcher.R
import com.droiddeck.launcher.glasses.GlassesState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class VitureSdkImportControlsTest {
    @get:Rule val compose = createRobolectricComposeRule()

    @Test fun importOpensAndroidDocumentPickerAndRemainsAvailableAcrossTabs() {
        val displayTab = mutableStateOf(false)
        compose.setContent {
            Column { VitureSdkImportControls(GlassesState(vitureDetected = true), visible = displayTab.value) }
        }
        compose.onNodeWithTag("viture-sdk-import").assertDoesNotExist()
        compose.runOnIdle { displayTab.value = true }
        compose.onNodeWithTag("viture-sdk-import").performClick()
        val intent = shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, intent.action)
        assertEquals("*/*", intent.type)
        assertTrue(intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)!!.contains("application/zip"))
    }

    @Test fun importAppearsOnlyForDetectedVitureWithoutAnSdk() {
        val detected = mutableStateOf(false)
        val available = mutableStateOf(false)
        var imports = 0
        compose.setContent {
            Column { VitureSdkImportRow(detected.value, available.value, false) { imports++ } }
        }
        compose.onNodeWithTag("viture-sdk-import").assertDoesNotExist()
        compose.runOnIdle { detected.value = true }
        compose.onNodeWithTag("viture-sdk-import").performClick()
        assertEquals(1, imports)
        compose.runOnIdle { available.value = true }
        compose.onNodeWithTag("viture-sdk-import").assertDoesNotExist()
        compose.onNodeWithText(text(R.string.viture_sdk_ready)).assertExists()
        compose.runOnIdle { detected.value = false }
        compose.onNodeWithText(text(R.string.viture_sdk_ready)).assertDoesNotExist()
    }

    @Test fun checkingAndInstallingPreventDuplicateImports() {
        val installing = mutableStateOf(false)
        compose.setContent {
            Column { VitureSdkImportRow(true, false, true, installing.value) {} }
        }
        compose.onNodeWithTag("viture-sdk-import").assertDoesNotExist()
        compose.onNodeWithTag("viture-sdk-progress").assertExists()
        compose.onNodeWithText(text(R.string.viture_sdk_checking)).assertExists()
        compose.runOnIdle { installing.value = true }
        compose.onNodeWithText(text(R.string.viture_sdk_installing)).assertExists()
        compose.onNodeWithTag("viture-sdk-import").assertDoesNotExist()
    }

    @Test fun validationRequiresConfirmationAndCancelDoesNotInstall() {
        val shown = mutableStateOf(true)
        var installs = 0
        var cancellations = 0
        compose.setContent {
            if (shown.value) VitureSdkConfirmation("2.4.0", "24 MB",
                onDismiss = { cancellations++; shown.value = false },
                onConfirm = { installs++; shown.value = false })
        }
        compose.onNodeWithText(text(R.string.viture_sdk_confirm_trust)).assertExists()
        assertEquals(0, installs)
        compose.onNodeWithText(text(R.string.common_cancel)).performClick()
        assertEquals(1, cancellations)
        assertEquals(0, installs)
        compose.runOnIdle { shown.value = true }
        compose.onNodeWithTag("viture-sdk-confirm").performClick()
        assertEquals(1, installs)
    }

    private fun text(id: Int) = RuntimeEnvironment.getApplication().getString(id)
}
