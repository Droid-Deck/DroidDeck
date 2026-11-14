package com.droiddeck.launcher.agent

import android.content.Context
import android.content.Intent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class AgentCallReceiverTest {
    private val original = AgentCallReceiver.call

    @After fun restore() {
        AgentCallReceiver.call = original
    }

    @Test fun aSlowCallIsAnsweredAfterTheBroadcastReturns() {
        val context: Context = RuntimeEnvironment.getApplication()
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)
        AgentCallReceiver.call = { _, method, _, _ ->
            started.countDown()
            release.await(10, TimeUnit.SECONDS)
            """{"ok":true,"method":"$method"}"""
        }
        val answer = File(context.getExternalFilesDir(null), "agent-calls/slow1.json").apply { delete() }

        AgentCallReceiver().onReceive(context, Intent().putExtra("method", "guest").putExtra("id", "slow1"))

        assertTrue(started.await(5, TimeUnit.SECONDS))
        assertFalse(answer.exists())
        release.countDown()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!answer.exists() && System.nanoTime() < deadline) Thread.sleep(20)
        assertEquals("""{"ok":true,"method":"guest"}""", answer.readText())
    }

    @Test fun anUnsafeIdIsIgnored() {
        val context: Context = RuntimeEnvironment.getApplication()
        var called = false
        AgentCallReceiver.call = { _, _, _, _ -> called = true; "{}" }

        AgentCallReceiver().onReceive(context, Intent().putExtra("method", "state").putExtra("id", "../x"))

        Thread.sleep(100)
        assertFalse(called)
    }
}
