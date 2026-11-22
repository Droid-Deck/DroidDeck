package com.droiddeck.launcher.input

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Looper
import com.droiddeck.launcher.wayland.WaylandCompositor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], shadows = [SessionTextInputTest.ShadowLocalSocket::class, SessionTextInputTest.ShadowWaylandCompositor::class])
@LooperMode(LooperMode.Mode.PAUSED)
class SessionTextInputTest {

    @Before
    fun setUp() {
        ShadowLocalSocket.reset()
        ShadowWaylandCompositor.reset()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @After
    fun tearDown() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun retriesUntilGuestProvesReadyThenCompletes() {
        val input = SessionTextInput(RuntimeEnvironment.getApplication()) { true }
        try {
            input.start()
            assertTrue(ShadowWaylandCompositor.submit(73))
            shadowOf(Looper.getMainLooper()).idle()
            val negative = ShadowWaylandCompositor.completions.poll(5, TimeUnit.SECONDS)
            assertEquals(-73, negative)

            val firstAttempt = ShadowLocalSocket.attemptsQueue.poll(5, TimeUnit.SECONDS)
            assertEquals(1, firstAttempt)
            val retryAttempt = ShadowLocalSocket.attemptsQueue.poll(5, TimeUnit.SECONDS)
            assertEquals(2, retryAttempt)

            ShadowLocalSocket.proofAvailable.set(true)
            shadowOf(Looper.getMainLooper()).idle()

            val completion = ShadowWaylandCompositor.completions.poll(5, TimeUnit.SECONDS)
            assertEquals(73, completion)
            assertEquals(1, ShadowLocalSocket.frameCount.get())
            assertNull(ShadowWaylandCompositor.completions.poll())
        } finally {
            input.stop()
        }
    }

    @Test
    fun stopCancelsPendingRetryAndNewGenerationCanSucceed() {
        val input = SessionTextInput(RuntimeEnvironment.getApplication()) { true }
        try {
            input.start()
            assertTrue(ShadowWaylandCompositor.submit(74))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, ShadowLocalSocket.attemptsQueue.poll(5, TimeUnit.SECONDS))
            val negative = ShadowWaylandCompositor.completions.poll(5, TimeUnit.SECONDS)
            assertEquals(-74, negative)

            input.stop()
            shadowOf(Looper.getMainLooper()).idle()

            val extra = ShadowLocalSocket.attemptsQueue.poll(600, TimeUnit.MILLISECONDS)
            assertNull(extra)

            ShadowLocalSocket.proofAvailable.set(true)
            input.start()
            shadowOf(Looper.getMainLooper()).idle()

            val completion = ShadowWaylandCompositor.completions.poll(5, TimeUnit.SECONDS)
            assertEquals(74, completion)
            assertEquals(1, ShadowLocalSocket.frameCount.get())
        } finally {
            input.stop()
        }
    }

    @Implements(LocalSocket::class)
    class ShadowLocalSocket {
        private val written = ByteArrayOutputStream()

        @Implementation
        fun connect(address: LocalSocketAddress) {}

        @Implementation
        fun setSoTimeout(timeout: Int) {}

        @Implementation
        fun getOutputStream(): OutputStream = object : OutputStream() {
            override fun write(b: Int) { written.write(b) }
            override fun write(b: ByteArray, off: Int, len: Int) { written.write(b, off, len) }
        }

        @Implementation
        fun getInputStream(): InputStream {
            val bytes = written.toByteArray()
            val isProbe = bytes.size == 4 && bytes.all { it == 0.toByte() }
            return if (isProbe) {
                probeCount.incrementAndGet()
                val allowProof = proofAvailable.get()
                attemptsQueue.offer(probeCount.get())
                ByteArrayInputStream(byteArrayOf(if (allowProof) 0 else 42))
            } else {
                frameCount.incrementAndGet()
                ByteArrayInputStream(byteArrayOf(42))
            }
        }

        @Implementation
        fun close() {}

        @Implementation
        fun shutdownOutput() {}

        companion object {
            private val probeCount = AtomicInteger(0)
            val attemptsQueue = LinkedBlockingQueue<Int>()
            val frameCount = AtomicInteger(0)
            val proofAvailable = java.util.concurrent.atomic.AtomicBoolean(false)

            fun reset() {
                probeCount.set(0)
                attemptsQueue.clear()
                frameCount.set(0)
                proofAvailable.set(false)
            }
        }
    }

    @Implements(WaylandCompositor::class, isInAndroidSdk = false)
    class ShadowWaylandCompositor {
        companion object {
            val completions = LinkedBlockingQueue<Int>()
            @Volatile private var listener: WaylandCompositor.TextInputFallbackListener? = null

            fun reset() {
                completions.clear()
                listener = null
            }

            fun submit(token: Int): Boolean = listener!!.onTextInputFallback("abc", token)

            @Implementation
            @JvmStatic
            fun __staticInitializer__() {}

            @Implementation
            @JvmStatic
            fun completeTextInputFallback(token: Int) {
                completions.offer(token)
            }

            @Implementation
            @JvmStatic
            fun setTextInputFallbackListener(l: WaylandCompositor.TextInputFallbackListener?) {
                listener = l
            }

            @Implementation
            @JvmStatic
            fun isTextInputFallbackListener(l: WaylandCompositor.TextInputFallbackListener?): Boolean =
                listener != null && listener === l

            @Implementation
            @JvmStatic
            fun clearTextInputFallbackListener(l: WaylandCompositor.TextInputFallbackListener?) {
                if (listener === l) listener = null
            }
        }
    }
}
