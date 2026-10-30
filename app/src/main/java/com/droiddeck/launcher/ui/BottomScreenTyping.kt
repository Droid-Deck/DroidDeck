package com.droiddeck.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.CommitTextCommand
import androidx.compose.ui.text.input.DeleteAllCommand
import androidx.compose.ui.text.input.EditCommand
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.ImeOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.PlatformTextInputService
import androidx.compose.ui.text.input.SetSelectionCommand
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TextInputService
import androidx.compose.ui.text.input.TextInputSession
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.R

class TypingRequest(
    initial: TextFieldValue,
    val imeOptions: ImeOptions,
    private val onValue: (TextFieldValue) -> Unit,
    private val onImeAction: (ImeAction) -> Unit,
    private val onClose: () -> Unit = {},
) {
    var value by mutableStateOf(initial)

    fun send(next: TextFieldValue) {
        value = next
        onValue(next)
    }

    fun action() = onImeAction(imeOptions.imeAction)

    fun close() = onClose()

    companion object {
        internal fun editsFor(next: TextFieldValue): List<EditCommand> = listOf(
            DeleteAllCommand(),
            CommitTextCommand(next.text, 1),
            SetSelectionCommand(next.selection.start, next.selection.end),
        )
    }
}

internal val LocalBottomTyping = staticCompositionLocalOf { false }

internal class BottomScreenTextInput(private val fallback: TextInputService) : PlatformTextInputService {
    private var session: TextInputSession? = null

    override fun startInput(
        value: TextFieldValue, imeOptions: ImeOptions,
        onEditCommand: (List<EditCommand>) -> Unit, onImeActionPerformed: (ImeAction) -> Unit,
    ) {
        session?.let { fallback.stopInput(it) }
        session = null
        if (DualScreenPanel.attached) DualScreenPanel.startTyping(TypingRequest(value, imeOptions, { onEditCommand(TypingRequest.editsFor(it)) }, onImeActionPerformed))
        else session = fallback.startInput(value, imeOptions, onEditCommand, onImeActionPerformed)
    }

    override fun stopInput() {
        session?.let { fallback.stopInput(it) }
        session = null
        DualScreenPanel.stopTyping()
    }

    override fun showSoftwareKeyboard() {
        val active = session
        if (active != null) active.showSoftwareKeyboard() else DualScreenPanel.showTyping()
    }

    override fun hideSoftwareKeyboard() {
        val active = session
        if (active != null) active.hideSoftwareKeyboard() else DualScreenPanel.hideTyping()
    }

    override fun updateState(oldValue: TextFieldValue?, newValue: TextFieldValue) {
        val active = session
        if (active != null) active.updateState(oldValue, newValue) else DualScreenPanel.updateTyping(newValue)
    }
}

@Composable
internal fun TypingPanel(request: TypingRequest, onDone: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    var field by remember(request) { mutableStateOf(request.value) }
    LaunchedEffect(request.value) {
        if (request.value.text != field.text) field = request.value
    }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(request) {
        kotlinx.coroutines.delay(120)
        runCatching { focus.requestFocus() }
        keyboard?.show()
    }
    val options = request.imeOptions
    val secret = options.keyboardType == KeyboardType.Password || options.keyboardType == KeyboardType.NumberPassword
    Column(
        Modifier.fillMaxSize().background(colors.background).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.dual_typing_title), fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                color = colors.onSurfaceVariant, modifier = Modifier.weight(1f))
            Button(onClick = onDone, modifier = Modifier.testTag("dual-typing-done")) { Text(stringResource(R.string.dual_typing_done)) }
        }
        OutlinedTextField(
            value = field,
            onValueChange = { next ->
                field = next
                request.send(next)
            },
            singleLine = options.singleLine,
            maxLines = if (options.singleLine) 1 else 4,
            textStyle = MaterialTheme.typography.titleLarge,
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(
                capitalization = options.capitalization, autoCorrect = options.autoCorrect,
                keyboardType = options.keyboardType, imeAction = options.imeAction,
            ),
            keyboardActions = KeyboardActions(onAny = {
                request.action()
                onDone()
            }),
            modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("dual-typing-field"),
        )
    }
}
