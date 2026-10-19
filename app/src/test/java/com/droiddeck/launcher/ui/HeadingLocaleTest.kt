package com.droiddeck.launcher.ui

import android.content.res.Configuration
import android.os.LocaleList
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class HeadingLocaleTest {
    @get:Rule val compose = createRobolectricComposeRule()

    @Test fun headingsFollowTheAppLanguageAndRecomposeWhenItChanges() {
        val appLocale = mutableStateOf(Locale.forLanguageTag("tr"))
        compose.setContent {
            val configuration = Configuration(LocalConfiguration.current).apply {
                setLocales(LocaleList(appLocale.value))
            }
            CompositionLocalProvider(LocalConfiguration provides configuration) {
                DroidDeckTheme {
                    Column {
                        Eyebrow("Bileşenler Iİıi")
                        SectionTitle("Bileşenler Iİıi", null)
                    }
                }
            }
        }
        compose.onAllNodesWithText("BİLEŞENLER IİIİ").assertCountEquals(2)
        compose.runOnIdle { appLocale.value = Locale.ENGLISH }
        compose.onAllNodesWithText("BILEŞENLER IİII").assertCountEquals(2)
        compose.runOnIdle { appLocale.value = Locale.forLanguageTag("tr") }
        compose.onAllNodesWithText("BİLEŞENLER IİIİ").assertCountEquals(2)
    }
}
