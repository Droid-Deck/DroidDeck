package com.droiddeck.launcher.core

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.droiddeck.launcher.gpu.AutoDriverTrial
import com.droiddeck.launcher.session.SessionPrefs
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 28, 33], manifest = Config.NONE)
class SettingsPersistenceTest {
    private val previousLocale = Locale.getDefault()
    private val context = RecordingContext(RuntimeEnvironment.getApplication())

    @After fun restoreLocale() { Locale.setDefault(previousLocale) }

    @Test fun languageChoiceIsImmediatelyVisibleWithoutSynchronousDiskWrites() {
        AppLanguage.setChosen(context, "ar")
        assertEquals("ar", AppLanguage.chosen(context))
        assertEquals("ar", Locale.getDefault().language)
        assertEquals(0, context.commits)
        assertEquals(1, context.applies)
    }

    @Test fun anUnsupportedLanguageStillUsesTheSystemChoice() {
        AppLanguage.setChosen(context, "unsupported")
        assertEquals(AppLanguage.SYSTEM, AppLanguage.chosen(context))
        assertEquals(0, context.commits)
        assertEquals(1, context.applies)
    }

    @Test fun compressionChoicesRemainImmediatelyVisibleWithoutChangingDrivers() {
        SessionPrefs.setAndroidDriver(context, "display")
        SessionPrefs.setLinuxDriver(context, "runtime")
        context.resetCounts()
        for (mode in listOf(SessionPrefs.TP_UBWC_ON, SessionPrefs.TP_UBWC_OFF, SessionPrefs.TP_UBWC_AUTO)) {
            SessionPrefs.setTpUbwcMode(context, mode)
            assertEquals(mode, SessionPrefs.tpUbwcMode(context))
            assertEquals("display", SessionPrefs.androidDriver(context))
            assertEquals("runtime", SessionPrefs.linuxDriver(context))
        }
        assertEquals(0, context.commits)
        assertEquals(3, context.applies)
    }

    @Test fun concurrentOrdinarySettingsDoNotLoseCrashCountsOrRollbackChoices() {
        SessionPrefs.setAndroidDriver(context, "new-display")
        SessionPrefs.setLinuxDriver(context, "new-linux")
        context.resetCounts()
        AutoDriverTrial.begin(context, "trial@r1", "Trial", "new-display", "new-linux", "old-display", "old-linux")
        assertEquals("trial must be saved before loading a driver", 1, context.commits)
        context.resetCounts()
        val start = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val writer = Thread {
            try {
                assertTrue(start.await(5, TimeUnit.SECONDS))
                repeat(200) { SessionPrefs.setTpUbwcMode(context, if (it % 2 == 0) SessionPrefs.TP_UBWC_ON else SessionPrefs.TP_UBWC_OFF) }
            } catch (error: Throwable) { failure.set(error) }
        }
        writer.start()
        start.countDown()
        AutoDriverTrial.displayStarting(context, "new-display")
        AutoDriverTrial.displayStarting(context, "new-display")
        writer.join(10_000)
        assertFalse("settings writer did not finish", writer.isAlive)
        failure.get()?.let { throw AssertionError("settings writer failed", it) }
        assertEquals("both crash counts must be saved synchronously", 2, context.commits)
        val trial = AutoDriverTrial.rollbackDue(context)!!
        assertEquals(2, trial.starts)
        context.resetCounts()
        AutoDriverTrial.rollBack(context, trial)
        assertTrue("rollback must retain synchronous persistence", context.commits > 0)
        assertEquals("old-display", SessionPrefs.androidDriver(context))
        assertEquals("old-linux", SessionPrefs.linuxDriver(context))
        assertEquals(SessionPrefs.TP_UBWC_OFF, SessionPrefs.tpUbwcMode(context))
        assertTrue("trial@r1" in AutoDriverTrial.bad(context))
        assertNull(AutoDriverTrial.current(context))
    }

    private class RecordingContext(base: Context) : ContextWrapper(base) {
        @Volatile var commits = 0
        @Volatile var applies = 0
        fun resetCounts() { commits = 0; applies = 0 }
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val preferences = super.getSharedPreferences(name, mode)
            return object : SharedPreferences by preferences {
                override fun edit(): SharedPreferences.Editor {
                    val editor = preferences.edit()
                    return object : SharedPreferences.Editor by editor {
                        override fun putString(key: String?, value: String?): SharedPreferences.Editor { editor.putString(key, value); return this }
                        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor { editor.putStringSet(key, values); return this }
                        override fun putInt(key: String?, value: Int): SharedPreferences.Editor { editor.putInt(key, value); return this }
                        override fun putLong(key: String?, value: Long): SharedPreferences.Editor { editor.putLong(key, value); return this }
                        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor { editor.putFloat(key, value); return this }
                        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor { editor.putBoolean(key, value); return this }
                        override fun remove(key: String?): SharedPreferences.Editor { editor.remove(key); return this }
                        override fun clear(): SharedPreferences.Editor { editor.clear(); return this }
                        override fun commit(): Boolean { commits++; return editor.commit() }
                        override fun apply() { applies++; editor.apply() }
                    }
                }
            }
        }
    }
}
