package com.droiddeck.launcher.agent

import android.content.ComponentName
import android.content.ContextWrapper
import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class AgentHostServiceTest {
    private class ServiceContext : ContextWrapper(RuntimeEnvironment.getApplication()) {
        var stops = 0
        var reject = false
        override fun startForegroundService(service: Intent): ComponentName {
            if (reject) throw IllegalStateException("start rejected")
            return ComponentName(packageName, AgentHostService::class.java.name)
        }
        override fun stopService(service: Intent): Boolean { stops++; return true }
    }

    @Test fun setupCompletionKeepsBackgroundWaitUntilItEnds() {
        val context = ServiceContext()
        AgentHostService.setupStarted(context)
        val service = Robolectric.buildService(AgentHostService::class.java).create().get()
        service.onStartCommand(Intent(context, AgentHostService::class.java), 0, 1)
        assertNull(AgentHostService.waitForBackground(context))
        AgentHostService.setupEnded(context)
        assertEquals(0, context.stops)
        AgentHostService.backgroundDone(context)
        assertEquals(1, context.stops)
    }

    @Test fun rejectedForegroundStartIsReportedAndCanBeRetried() {
        val context = ServiceContext()
        context.reject = true
        assertNotNull(AgentHostService.start(context))
        context.reject = false
        assertNull(AgentHostService.start(context))
        val service = Robolectric.buildService(AgentHostService::class.java).create().get()
        service.onStartCommand(Intent(context, AgentHostService::class.java), 0, 1)
        AgentHostService.stop(context)
    }
}
