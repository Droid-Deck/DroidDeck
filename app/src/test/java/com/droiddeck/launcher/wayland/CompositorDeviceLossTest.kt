package com.droiddeck.launcher.wayland

import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import com.droiddeck.launcher.gpu.GpuDeviceState
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, shadows = [CompositorDeviceLossTest.NoNativeLoad::class])
class CompositorDeviceLossTest {
    @Implements(WaylandCompositor::class)
    class NoNativeLoad {
        companion object {
            @JvmStatic @Implementation fun __staticInitializer__() {}
        }
    }

    @Before fun setup() {
        ReflectionHelpers.setStaticField(GpuDeviceState::class.java, "lost", false)
        ReflectionHelpers.setStaticField(WaylandCompositor::class.java, "deviceLostListener", null)
        ReflectionHelpers.setStaticField(WaylandCompositor::class.java, "firstFrameListener", null)
    }

    @After fun cleanup() { setup() }

    @Test fun lossIsStickyEvenWithoutAnAttachedListener() {
        assertFalse(WaylandCompositor.hasDeviceLost())
        WaylandCompositor.onDeviceLost()
        WaylandCompositor.setDeviceLostListener(Runnable {})
        assertTrue(WaylandCompositor.hasDeviceLost())
    }

    @Test fun concurrentFaultReportsNotifyOnlyOnce() {
        val calls = AtomicInteger()
        val start = CountDownLatch(1)
        WaylandCompositor.setDeviceLostListener(Runnable { calls.incrementAndGet() })
        val threads = List(16) { Thread { start.await(); WaylandCompositor.onDeviceLost() }.apply { start() } }
        start.countDown()
        threads.forEach { it.join(5000); assertFalse(it.isAlive) }
        assertEquals(1, calls.get())
    }

    @Test fun anOldServiceCannotClearItsReplacementListener() {
        val old = Runnable { fail("Old listener called") }
        var calls = 0
        WaylandCompositor.setDeviceLostListener(old)
        WaylandCompositor.setDeviceLostListener(Runnable { calls++ })
        WaylandCompositor.clearDeviceLostListener(old)
        WaylandCompositor.onDeviceLost()
        assertEquals(1, calls)
    }

    @Test fun clearingTheCurrentListenerPreventsStaleCallbacks() {
        val listener = Runnable { fail("Destroyed service called") }
        WaylandCompositor.setDeviceLostListener(listener)
        WaylandCompositor.clearDeviceLostListener(listener)
        WaylandCompositor.onDeviceLost()
        assertTrue(WaylandCompositor.hasDeviceLost())
    }

    @Test fun lateFramesCannotHideDeviceLoss() {
        var frames = 0
        WaylandCompositor.setFirstFrameListener(Runnable { frames++ })
        WaylandCompositor.onFirstFramePresented()
        WaylandCompositor.onDeviceLost()
        repeat(10) { WaylandCompositor.onFirstFramePresented() }
        assertEquals(1, frames)
    }
}
