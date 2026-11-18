package com.droiddeck.launcher.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.droiddeck.launcher.R

/**
 * Dictation into the Agent editor: what is heard so far follows the text already typed, until the user edits the draft.
 * Listening carries on across pauses until [finish], which can send once the last words are written.
 */
internal class AgentVoice(private val context: Context, private val draft: () -> String, private val onText: (String) -> Unit) {
    var listening by mutableStateOf(false)
        private set
    /** Whether listening goes on without the button held. */
    var locked by mutableStateOf(false)
    /** How loud the speech is, from 0 to 1. */
    var level by mutableFloatStateOf(0f)
        private set
    private var recognizer: SpeechRecognizer? = null
    private var typed = ""
    private var base = ""
    private var written = ""
    private var finishing = false
    private var then: (() -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())

    fun start(typed: String) {
        stop()
        val speech = runCatching { SpeechRecognizer.createSpeechRecognizer(context) }.getOrNull() ?: return
        recognizer = speech
        this.typed = typed
        base = continued(typed)
        written = typed
        finishing = false
        listening = true
        speech.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) { if (recognizer === speech) level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f) }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { level = 0f }
            override fun onError(error: Int) {
                if (recognizer !== speech) return
                // The recognizer gives up on a pause; the user is still talking until they let go or stop.
                if (!finishing && (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)) relisten(speech) else {
                    done()
                    if (error == SpeechRecognizer.ERROR_AUDIO || error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) micBlocked()
                }
            }
            override fun onResults(results: Bundle?) {
                if (recognizer !== speech) return
                heard(results)
                if (recognizer !== speech) return
                if (finishing) done() else {
                    base = continued(written)
                    relisten(speech)
                }
            }
            override fun onPartialResults(partialResults: Bundle?) { if (recognizer === speech) heard(partialResults) }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        listen(speech)
    }

    private fun continued(text: String) = text.trimEnd().let { if (it.isEmpty()) it else "$it " }

    private fun listen(speech: SpeechRecognizer) {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        runCatching { speech.startListening(intent) }.onFailure { stop() }
    }

    private fun relisten(speech: SpeechRecognizer) {
        main.post { if (recognizer === speech && !finishing) listen(speech) }
    }

    private fun heard(results: Bundle?) {
        val spoken = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() } ?: return
        if (draft() != written) return stop()
        written = base + spoken
        onText(written)
    }

    // Speech Services by Google does not ask for the microphone itself, so a fresh install fails until it is allowed in its settings.
    private fun micBlocked() {
        val service = context.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0).firstOrNull()?.serviceInfo?.packageName ?: return
        Toast.makeText(context, R.string.agent_voice_service_mic, Toast.LENGTH_LONG).show()
        val settings = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", service, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(settings) }
    }

    /** Ends listening but keeps the recognizer until its final result arrives, then runs [then]. */
    fun finish(then: (() -> Unit)? = null) {
        val speech = recognizer ?: return
        finishing = true
        this.then = then
        listening = false
        locked = false
        level = 0f
        runCatching { speech.stopListening() }.onFailure { done() }
        // Some recognizers never answer a stop with nothing heard.
        main.postDelayed({ if (recognizer === speech) done() }, FINAL_RESULT_TIMEOUT_MS)
    }

    private fun done() {
        val next = then
        stop()
        next?.invoke()
    }

    /** Stops and puts back the text typed before listening began. */
    fun cancel() {
        val restore = draft() == written
        stop()
        if (restore) onText(typed)
    }

    fun stop() {
        then = null
        listening = false
        locked = false
        level = 0f
        val speech = recognizer ?: return
        recognizer = null
        runCatching { speech.cancel() }
        runCatching { speech.destroy() }
    }

    companion object {
        private const val FINAL_RESULT_TIMEOUT_MS = 3000L

        fun available(context: Context) = SpeechRecognizer.isRecognitionAvailable(context)
    }
}
