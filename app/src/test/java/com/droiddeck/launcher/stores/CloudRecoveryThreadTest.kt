package com.droiddeck.launcher.stores

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class CloudRecoveryThreadTest {
    private val uncaught = mutableListOf<Throwable>()
    private var previous: Thread.UncaughtExceptionHandler? = null

    @Before fun setUp() {
        previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, error -> synchronized(uncaught) { uncaught += error } }
    }

    @After fun tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(previous)
    }

    @Test fun aFailingRecoveryUploadDoesNotReachTheCrashHandler() {
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                if (name == "stores.cloud") throw IllegalStateException("preferences are gone") else super.getSharedPreferences(name, mode)
        }
        StoresState.init(context)
        Thread.getAllStackTraces().keys.filter { it.name == "cloud-recovery" }.forEach { it.join(5000) }
        assertEquals(emptyList<Throwable>(), synchronized(uncaught) { uncaught.toList() })
    }
}
