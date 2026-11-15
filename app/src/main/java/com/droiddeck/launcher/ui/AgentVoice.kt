package com.droiddeck.launcher.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Dictation into the Agent editor: what is heard so far follows the text already typed, until the user edits the draft. */
internal class AgentVoice(private val context: Context, private val draft: () -> String, private val onText: (String) -> Unit) {
    var listening by mutableStateOf(false)
        private set
    private var recognizer: SpeechRecognizer? = null
    private var base = ""
    private var written = ""

    fun start(typed: String) {
        stop()
        val speech = runCatching { SpeechRecognizer.createSpeechRecognizer(context) }.getOrNull() ?: return
        recognizer = speech
        base = typed.trimEnd().let { if (it.isEmpty()) it else "$it " }
        written = typed
        speech.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { listening = true }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) { if (recognizer === speech) stop() }
            override fun onResults(results: Bundle?) {
                if (recognizer !== speech) return
                heard(results)
                stop()
            }
            override fun onPartialResults(partialResults: Bundle?) { if (recognizer === speech) heard(partialResults) }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        runCatching { speech.startListening(intent) }.onFailure { stop() }
    }

    private fun heard(results: Bundle?) {
        val spoken = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() } ?: return
        if (draft() != written) return stop()
        written = base + spoken
        onText(written)
    }

    /** Ends listening but keeps the recognizer until its final result arrives. */
    fun finish() {
        val speech = recognizer ?: return
        listening = false
        runCatching { speech.stopListening() }.onFailure { stop() }
    }

    fun stop() {
        listening = false
        val speech = recognizer ?: return
        recognizer = null
        runCatching { speech.cancel() }
        runCatching { speech.destroy() }
    }

    companion object {
        fun available(context: Context) = SpeechRecognizer.isRecognitionAvailable(context)
    }
}
