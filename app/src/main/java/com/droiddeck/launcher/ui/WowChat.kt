package com.droiddeck.launcher.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.ImeOptions
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.droiddeck.launcher.R
import com.droiddeck.launcher.session.SessionPrefs

class WowChat(
    private val context: Context,
    private val onSend: (String, Boolean) -> Unit,
    private val onCancel: () -> Unit,
    private val readField: ((String?) -> Unit) -> Unit = {},
) {
    var request by mutableStateOf<TypingRequest?>(null)
        private set
    var asking by mutableStateOf(false)
        private set
    var listening by mutableStateOf(false)
        private set
    var voice by mutableStateOf(false)
        private set
    var remembered by mutableStateOf(SessionPrefs.wowChatInput(context))
        private set
    private var recognizer: SpeechRecognizer? = null
    private var spokenBase = ""
    private var fieldRead = false

    val open: Boolean get() = request != null || asking

    fun chatOpened() {
        if (open) return
        when (val mode = remembered) {
            null -> asking = true
            else -> start(mode)
        }
    }

    fun chatClosed() {
        asking = false
        finish()
    }

    fun pick(mode: String, remember: Boolean) {
        asking = false
        if (remember) choose(mode)
        start(mode)
    }

    fun choose(mode: String?) {
        SessionPrefs.setWowChatInput(context, mode)
        remembered = SessionPrefs.wowChatInput(context)
    }

    fun dismissChoice() {
        asking = false
        onCancel()
    }

    fun send() {
        val text = request?.value?.text.orEmpty()
        val replace = fieldRead
        finish()
        onSend(text, replace)
    }

    fun cancel() {
        finish()
        onCancel()
    }

    private fun start(mode: String) {
        voice = mode == SessionPrefs.WOW_CHAT_VOICE && voiceAvailable(context)
        if (mode == SessionPrefs.WOW_CHAT_VOICE && !voice) Toast.makeText(context, R.string.wow_chat_voice_unavailable, Toast.LENGTH_SHORT).show()
        val opened = TypingRequest(TextFieldValue(""), ImeOptions(singleLine = true, imeAction = ImeAction.Send), {}, {})
        fieldRead = false
        request = opened
        readField { text ->
            if (request === opened && !text.isNullOrEmpty() && opened.value.text.isEmpty()) {
                fieldRead = true
                opened.value = TextFieldValue(text, TextRange(text.length))
                if (recognizer != null) spokenBase = "${text.trimEnd()} "
            }
        }
    }

    private fun finish() {
        stopListening()
        request = null
        voice = false
    }

    fun listen() {
        val active = request ?: return
        stopListening()
        val speech = runCatching { SpeechRecognizer.createSpeechRecognizer(context) }.getOrNull() ?: return
        recognizer = speech
        spokenBase = active.value.text.trimEnd().let { if (it.isEmpty()) it else "$it " }
        speech.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { listening = true }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) { if (recognizer === speech) stopListening() }
            override fun onResults(results: Bundle?) {
                if (recognizer !== speech) return
                heard(results)
                stopListening()
            }
            override fun onPartialResults(partialResults: Bundle?) { if (recognizer === speech) heard(partialResults) }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        runCatching { speech.startListening(intent) }.onFailure { stopListening() }
    }

    private fun heard(results: Bundle?) {
        val spoken = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() } ?: return
        val text = spokenBase + spoken
        request?.value = TextFieldValue(text, TextRange(text.length))
    }

    fun stopListening() {
        listening = false
        val speech = recognizer ?: return
        recognizer = null
        runCatching { speech.cancel() }
        runCatching { speech.destroy() }
    }

    companion object {
        private val PROGRAM = Regex("wow[\\w-]*\\.exe", RegexOption.IGNORE_CASE)

        fun isWow(program: String?): Boolean = program != null && PROGRAM.matches(program)

        fun voiceAvailable(context: Context): Boolean = SpeechRecognizer.isRecognitionAvailable(context)
    }
}

@Composable
internal fun WowChatPanel(chat: WowChat, modifier: Modifier = Modifier.fillMaxWidth()) {
    val request = chat.request ?: return
    val context = LocalContext.current
    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) chat.listen() else Toast.makeText(context, R.string.wow_chat_mic_denied, Toast.LENGTH_SHORT).show()
    }
    val listen = {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) chat.listen()
        else mic.launch(Manifest.permission.RECORD_AUDIO)
    }
    LaunchedEffect(request) { if (chat.voice) listen() }
    DisposableEffect(request) { onDispose { chat.stopListening() } }
    BackHandler(onBack = chat::cancel)
    TypingPanel(
        request, modifier,
        title = stringResource(if (chat.listening) R.string.wow_chat_listening else R.string.wow_chat_title),
        done = stringResource(R.string.wow_chat_send),
        onCancel = chat::cancel,
        showKeyboard = !chat.voice,
        leading = if (WowChat.voiceAvailable(context)) ({
            FilledIconToggleButton(
                checked = chat.listening,
                onCheckedChange = { on -> if (on) listen() else chat.stopListening() },
                modifier = Modifier.testTag("wow-chat-mic"),
            ) { Icon(Icons.Filled.Mic, stringResource(R.string.wow_chat_voice)) }
        }) else null,
        onDone = chat::send,
    )
}

@Composable
internal fun WowChatChoiceDialog(chat: WowChat) {
    var remember by remember { mutableStateOf(true) }
    var picked by remember { mutableStateOf<String?>(null) }
    val shown = rememberShown {
        val mode = picked
        if (mode != null) chat.pick(mode, remember) else chat.dismissChoice()
    }
    val close = { shown.targetState = false }
    val choose = { mode: String -> picked = mode; close() }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    AppDialog(shown, close, "wowChat", wide = false, maxWidth = 520.dp) {
        DialogTitleRow(stringResource(R.string.wow_chat_title), close)
        DialogToggleRow(stringResource(R.string.wow_chat_remember), remember) { remember = it }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp, androidx.compose.ui.Alignment.End), modifier = Modifier.fillMaxWidth()) {
            SecondaryButton(stringResource(R.string.pckb_android), modifier = Modifier.testTag("wow-chat-keyboard")) { choose(SessionPrefs.WOW_CHAT_KEYBOARD) }
            PrimaryButton(stringResource(R.string.wow_chat_voice), icon = Icons.Filled.Mic,
                modifier = Modifier.focusRequester(focus).testTag("wow-chat-voice")) { choose(SessionPrefs.WOW_CHAT_VOICE) }
        }
    }
}
