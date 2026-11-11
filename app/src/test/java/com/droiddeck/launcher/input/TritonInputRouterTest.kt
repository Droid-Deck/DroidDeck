package com.droiddeck.launcher.input

import org.junit.Assert.*
import org.junit.Test

class TritonInputRouterTest {
    private val events = ArrayList<String>()
    private val router = InputRouter(object : InputOutput {
        override fun key(code: Int, down: Boolean) { events += "$code $down" }
        override fun button(code: Int, down: Boolean) {}
        override fun pointer(dx: Float, dy: Float) {}
        override fun scroll(steps: Int) {}
        override fun command(command: InputCommand) {}
        override fun requestMotion() {}
    })

    @Test fun nativeReportIsPassedThroughWithoutCopyingOrReencoding() {
        val adapter = TritonInputRouter(router)
        val bytes = ByteArray(64) { (it * 11).toByte() }
        assertSame(bytes, adapter.route(bytes))
        assertTrue(events.isEmpty())
        adapter.close()
    }

    @Test fun consumedButtonDoesNotChangeOtherBitsAxesPressureMotionOrExtension() {
        router.setProfile(InputProfile.defaults(InputProfile.GAME).withBinding(InputRole.PHYSICAL, InputControl.A, InputAction.Keys(listOf(57))))
        val adapter = TritonInputRouter(router)
        val bytes = ByteArray(64) { (it * 7).toByte() }
        bytes[1] = (bytes[1].toInt() or 1).toByte()
        val expected = bytes.copyOf().apply { this[1] = (this[1].toInt() and 1.inv()).toByte() }
        assertArrayEquals(expected, adapter.route(bytes))
        assertEquals(listOf("57 true"), events)
        adapter.release()
        assertEquals(listOf("57 true", "57 false"), events)
    }

    @Test fun switchingProfileWhileNativeButtonHeldRequiresRelease() {
        val adapter = TritonInputRouter(router)
        val held = ByteArray(45).apply { this[1] = 1 }
        adapter.route(held)
        router.setProfile(InputProfile.defaults(InputProfile.GAME).withBinding(InputRole.PHYSICAL, InputControl.A, InputAction.Keys(listOf(57))))
        adapter.route(held)
        assertTrue(events.isEmpty())
        adapter.route(ByteArray(45)); adapter.route(held)
        assertEquals(listOf("57 true"), events)
    }
}
