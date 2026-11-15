package com.droiddeck.launcher.input

import androidx.compose.runtime.MutableState
import com.droiddeck.launcher.SessionActivity
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.session.SessionState
import com.droiddeck.launcher.ui.WowChat
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, shadows = [JoyConPadTest.RecordingWriter::class])
class SessionInputAvailabilityTest {
    private val activity = Robolectric.buildActivity(SessionActivity::class.java).get()
    private val bridge = PadBridge(File(activity.cacheDir, "input-availability"))
    private val commands = mutableListOf<InputCommand>()
    private val guestEvents = mutableListOf<String>()
    private val router = InputRouter(object : InputOutput {
        override fun key(code: Int, down: Boolean) { guestEvents += "key $code $down" }
        override fun button(code: Int, down: Boolean) { guestEvents += "button $code $down" }
        override fun pointer(dx: Float, dy: Float) { guestEvents += "pointer" }
        override fun scroll(steps: Int) { guestEvents += "scroll" }
        override fun command(command: InputCommand) { commands += command }
        override fun requestMotion() { guestEvents += "motion" }
    })
    private val chat = WowChat(activity, { _, _ -> }, {})
    private val previousSuspended = SessionState.suspended

    @Before fun setUp() {
        SessionState.suspended = false
        state("resumed", true)
        bridge.setInputRouter(router)
        ReflectionHelpers.setField(activity, "inputRouter", router)
        ReflectionHelpers.setField(activity, "padBridge", bridge)
        ReflectionHelpers.setField(activity, "wowChat\$delegate", lazy { chat })
    }

    @After fun close() {
        router.releaseAll()
        bridge.stop()
        SessionState.suspended = previousSuspended
    }

    @Test fun keyboardAndOverlaysKeepTouchCommandsWithoutSendingGameInput() {
        for (overlay in listOf("pcKeyboardOpen", "drawerOpenState", "achievementsOpen", "gestureSettingsOpen", "inputSettingsOpen")) {
            state(overlay, true)
            update()
            checkCommandsAndBlockedGameInput()
            state(overlay, false)
        }
    }

    @Test fun appTextFocusKeepsTouchCommandsAndGameInputResumesAfterTyping() {
        chat.pick(SessionPrefs.WOW_CHAT_KEYBOARD, false)
        update()
        checkCommandsAndBlockedGameInput()
        chat.chatClosed()
        update()
        router.channel(InputRole.PHYSICAL, InputControl.A, InputAction.Keys(listOf(30))).usePress()
        assertEquals(listOf("key 30 true", "key 30 false"), guestEvents)
    }

    @Test fun backgroundPipAndSuspensionBlockTouchCommandsAsWellAsGameInput() {
        for (inactive in listOf("resumed", "pipUi", "suspended")) {
            when (inactive) {
                "suspended" -> SessionState.suspended = true
                else -> state(inactive, inactive != "resumed")
            }
            update()
            checkCommandsAndBlockedGameInput(commandsAllowed = false)
            when (inactive) {
                "suspended" -> SessionState.suspended = false
                else -> state(inactive, inactive == "resumed")
            }
        }
    }

    @Test fun suspendingCancelsAHeldToolbarCommandUntilAFreshPress() {
        state("pcKeyboardOpen", true)
        update()
        val menu = router.channel(InputRole.SECONDARY, InputControl.A, InputAction.Command(InputCommand.MENU))
        menu.update(1f)
        SessionState.suspended = true
        update()
        SessionState.suspended = false
        update()
        menu.update(0f)
        assertTrue(commands.isEmpty())
        menu.update(1f)
        menu.update(0f)
        assertEquals(listOf(InputCommand.MENU), commands)
    }

    private fun checkCommandsAndBlockedGameInput(commandsAllowed: Boolean = true) {
        commands.clear()
        for (role in listOf(InputRole.PRIMARY, InputRole.SECONDARY)) {
            for (command in InputCommand.entries) {
                router.channel(role, InputControl.A, InputAction.Command(command)).usePress()
            }
        }
        for (role in InputRole.entries) {
            router.channel(role, InputControl.A, InputAction.Keys(listOf(30))).usePress()
            router.channel(role, InputControl.A, InputAction.MouseButton(272)).usePress()
            router.channel(role, InputControl.LS, InputAction.Pointer()).usePress()
            router.channel(role, InputControl.RS, InputAction.Scroll()).usePress()
            val pad = router.pad(role)
            val raw = PadState().apply { press(PadState.A, true); leftX = .8f }
            val routed = pad.route(raw)
            assertFalse(routed.isDown(PadState.A))
            assertEquals(0f, routed.leftX)
            pad.close()
        }
        router.channel(InputRole.PHYSICAL, InputControl.A, InputAction.Command(InputCommand.MENU)).usePress()
        assertFalse(router.tick(.016f))
        assertTrue(guestEvents.isEmpty())
        assertEquals(if (commandsAllowed) InputCommand.entries + InputCommand.entries else emptyList<InputCommand>(), commands)
    }

    private fun InputRouter.Channel.usePress() {
        try { update(1f); update(0f) } finally { close() }
    }

    private fun state(name: String, value: Boolean) {
        ReflectionHelpers.getField<MutableState<Boolean>>(activity, "$name\$delegate").value = value
    }

    private fun update() = ReflectionHelpers.callInstanceMethod<Unit>(activity, "updateInputAvailability")
}
