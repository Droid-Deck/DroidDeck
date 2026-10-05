package com.droiddeck.launcher.ui

import android.content.Context
import android.content.res.Configuration
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.core.AppUiPrefs
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class AppScaleTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun clearScale() {
        context.getSharedPreferences("app-ui", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun defaultsToSystemDensityAndCanRestoreItAfterChoosingAnotherScale() {
        assertEquals(100, AppUiPrefs.scale(context))
        AppUiPrefs.setScale(context, 75)
        assertEquals(75, AppUiPrefs.scale(context))
        AppUiPrefs.setScale(context, 100)
        assertEquals(100, AppUiPrefs.scale(context))
        val density = Density(2f, 1.3f)
        val configuration = Configuration(context.resources.configuration)
        assertSame(density, scaledAppDensity(density, 100))
        assertSame(configuration, scaledAppConfiguration(configuration, 100))
    }

    @Test fun invalidStoredOrRequestedScaleFallsBackToTheDefault() {
        context.getSharedPreferences("app-ui", Context.MODE_PRIVATE).edit().putInt("scale", 0).commit()
        assertEquals(100, AppUiPrefs.scale(context))
        AppUiPrefs.setScale(context, 1000)
        assertEquals(100, AppUiPrefs.scale(context))
    }

    @Test fun scalingDoesNotModifySessionPreferencesOrAndroidDisplayMetrics() {
        val session = context.getSharedPreferences("session", Context.MODE_PRIVATE)
        session.edit().putString("resolution.steam", "1280x720").putInt("upscaler", 2).commit()
        val before = session.all.toMap()
        val density = context.resources.displayMetrics.density
        val configuration = Configuration(context.resources.configuration)
        AppUiPrefs.setScale(context, 150)
        scaledAppConfiguration(context.resources.configuration, AppUiPrefs.scale(context))
        assertEquals(before, session.all)
        assertEquals(density, context.resources.displayMetrics.density, 0f)
        assertEquals(configuration, context.resources.configuration)
    }

    @Test fun textAndControlsScaleTogetherWhilePreservingAccessibilityFontScale() {
        val base = Density(2f, 1.3f)
        for (percent in AppUiPrefs.scales) {
            val scaled = scaledAppDensity(base, percent)
            assertEquals(1.3f, scaled.fontScale, 0f)
            assertEquals(base.run { 44.dp.toPx() } * percent / 100f, scaled.run { 44.dp.toPx() }, 0.001f)
            assertEquals(base.run { 16.sp.toPx() } * percent / 100f, scaled.run { 16.sp.toPx() }, 0.001f)
        }
    }

    @Test fun responsiveWidthAndPopupHeightFitTheSamePhysicalWindowAtEveryScale() {
        val base = Configuration().apply {
            densityDpi = 320
            screenWidthDp = 960
            screenHeightDp = 540
            smallestScreenWidthDp = 540
            fontScale = 1.3f
            orientation = Configuration.ORIENTATION_LANDSCAPE
        }
        for (percent in AppUiPrefs.scales) {
            val scaled = scaledAppConfiguration(base, percent)
            val density = scaledAppDensity(Density(2f, 1.3f), percent).density
            assertEquals(1920f, scaled.screenWidthDp * density, 2f)
            assertEquals(1080f, scaled.screenHeightDp * density, 2f)
            assertEquals(1080f, scaled.smallestScreenWidthDp * density, 2f)
            assertEquals(1.3f, scaled.fontScale, 0f)
            assertEquals(base.orientation, scaled.orientation)
        }
        assertEquals(960, base.screenWidthDp)
        assertEquals(540, base.screenHeightDp)
        assertEquals(320, base.densityDpi)
    }
}
