package com.droiddeck.launcher.core

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import com.droiddeck.launcher.R
import java.util.Locale
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class AppLanguageArabicTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun reset() {
        context.getSharedPreferences("language", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After fun restore() {
        context.getSharedPreferences("language", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun pickerIncludesArabicUnderItsNativeName() {
        assertTrue("ar" in AppLanguage.supported)
        assertEquals("العربية", AppLanguage.nativeName("ar"))
        assertEquals("ar", AppLanguage.supportedTag(Locale.forLanguageTag("ar")))
        assertEquals("ar", AppLanguage.supportedTag(Locale.forLanguageTag("ar-EG")))
        assertEquals("ar", AppLanguage.supportedTag(Locale.forLanguageTag("ar-SA")))
        assertEquals("en", AppLanguage.supportedTag(Locale.forLanguageTag("en-US")))
    }

    @Test fun storedArabicChoiceIsPreservedAndUnknownChoicesFallBack() {
        // chosen() deliberately validates preference entries rather than accepting
        // an arbitrary device locale tag into a display-language selector.
        context.getSharedPreferences("language", Context.MODE_PRIVATE).edit()
            .putString("app_language", "ar").commit()
        assertEquals("ar", AppLanguage.chosen(context))
        assertEquals("ar", AppLanguage.effective(context).language)
        context.getSharedPreferences("language", Context.MODE_PRIVATE).edit()
            .putString("app_language", "ar-EG").commit()
        assertEquals(AppLanguage.SYSTEM, AppLanguage.chosen(context))
    }

    @Test fun arabicResourcesAndScreenDirectionAreAvailable() {
        val configuration = Configuration(context.resources.configuration).apply {
            setLocales(LocaleList(Locale.forLanguageTag("ar")))
        }
        val arabicContext = context.createConfigurationContext(configuration)
        assertEquals("اللغة", arabicContext.getString(R.string.setup_language))
        assertEquals("المكتبة", arabicContext.getString(R.string.stores_tab_library))
        assertEquals(android.view.View.LAYOUT_DIRECTION_RTL, configuration.layoutDirection)
        val englishConfiguration = Configuration(context.resources.configuration).apply {
            setLocales(LocaleList(Locale.forLanguageTag("en")))
        }
        assertEquals(android.view.View.LAYOUT_DIRECTION_LTR, englishConfiguration.layoutDirection)
    }

    @Test fun everyArabicPluralCategoryCanBeSelected() {
        val config = Configuration(context.resources.configuration).apply {
            setLocales(LocaleList(Locale.forLanguageTag("ar")))
        }
        val resources = context.createConfigurationContext(config).resources
        for (value in listOf(0, 1, 2, 3, 5, 11, 21, 100)) {
            assertTrue(resources.getQuantityString(R.plurals.fm_items, value, value).isNotBlank())
        }
    }
}
