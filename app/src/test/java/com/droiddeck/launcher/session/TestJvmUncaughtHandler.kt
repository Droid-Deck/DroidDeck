package com.droiddeck.launcher.session

import org.robolectric.pluginapi.TestEnvironmentLifecyclePlugin

/**
 * A plain JVM has no default uncaught-exception handler, so CrashHandler's fallback halts the test JVM (exit 2)
 * on any background-thread error. Each test starts from a handler that only prints, like the JVM's own.
 */
class TestJvmUncaughtHandler : TestEnvironmentLifecyclePlugin {
    override fun onSetupApplicationState() {
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            System.err.print("Exception in thread \"${thread.name}\" ")
            error.printStackTrace(System.err)
        }
    }
}
