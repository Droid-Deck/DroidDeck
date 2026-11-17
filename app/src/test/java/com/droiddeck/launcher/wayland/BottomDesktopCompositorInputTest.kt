package com.droiddeck.launcher.wayland

import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.os.Message
import android.os.Messenger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, shadows = [BottomDesktopCompositorInputTest.CompositorShadow::class])
class BottomDesktopCompositorInputTest {
    @Before fun setUp() {
        CompositorShadow.calls.clear()
    }

    @Test fun chineseTextThenEnterUsesTheOrderedHostQueue() {
        withService { messenger ->
            messenger.send(text("中文输入"))
            messenger.send(key(KEY_ENTER, PRESSED))
            messenger.send(key(KEY_ENTER, RELEASED))
            idle()

            assertEquals(listOf("text:中文输入", "key:28:1", "key:28:0"), CompositorShadow.calls)
            assertTrue(CompositorShadow.calls.none { it.startsWith("unordered") })
        }
    }

    @Test fun editingKeysAndTextShareOneOrderedQueue() {
        withService { messenger ->
            messenger.send(text("ab"))
            messenger.send(key(KEY_BACKSPACE, PRESSED))
            messenger.send(key(KEY_BACKSPACE, RELEASED))
            messenger.send(key(KEY_DELETE, PRESSED))
            messenger.send(key(KEY_DELETE, RELEASED))
            messenger.send(key(KEY_TAB, PRESSED))
            messenger.send(key(KEY_TAB, RELEASED))
            messenger.send(text("cd"))
            messenger.send(key(KEY_DOWN, PRESSED))
            messenger.send(key(KEY_DOWN, RELEASED))
            messenger.send(key(KEY_UP, PRESSED))
            messenger.send(key(KEY_UP, RELEASED))
            idle()

            assertEquals(
                listOf(
                    "text:ab",
                    "key:14:1", "key:14:0",
                    "key:111:1", "key:111:0",
                    "key:15:1", "key:15:0",
                    "text:cd",
                    "key:108:1", "key:108:0",
                    "key:103:1", "key:103:0",
                ),
                CompositorShadow.calls,
            )
            assertTrue(CompositorShadow.calls.none { it.startsWith("unordered") })
        }
    }

    private fun withService(block: (Messenger) -> Unit) {
        val controller = Robolectric.buildService(BottomDesktopCompositorService::class.java).create()
        try {
            val messenger = Messenger(controller.get().onBind(Intent()))
            block(messenger)
        } finally {
            controller.destroy()
        }
    }

    private fun text(value: String) = Message.obtain(null, BottomDesktopCompositorService.MSG_TEXT).apply {
        data = Bundle().apply { putString(BottomDesktopCompositorService.KEY_TEXT, value) }
    }

    private fun key(code: Int, state: Int) =
        Message.obtain(null, BottomDesktopCompositorService.MSG_KEY, code, state)

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    companion object {
        private const val KEY_ENTER = 28
        private const val KEY_BACKSPACE = 14
        private const val KEY_DELETE = 111
        private const val KEY_TAB = 15
        private const val KEY_DOWN = 108
        private const val KEY_UP = 103
        private const val PRESSED = 1
        private const val RELEASED = 0
    }

    @Implements(WaylandCompositor::class, isInAndroidSdk = false)
    class CompositorShadow {
        companion object {
            val calls = mutableListOf<String>()

            @JvmStatic @Implementation fun __staticInitializer__() {}

            @JvmStatic @Implementation fun textInputCommit(text: String) {
                calls += "text:$text"
            }

            @JvmStatic @Implementation fun nativeSendKey(evdev: Int, state: Int) {
                calls += "unordered:$evdev:$state"
            }

            @JvmStatic @Implementation fun nativeSendKeyOrdered(evdev: Int, state: Int) {
                calls += "key:$evdev:$state"
            }
        }
    }
}
