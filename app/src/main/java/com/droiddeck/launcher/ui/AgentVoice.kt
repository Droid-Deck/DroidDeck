package com.droiddeck.launcher.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

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
    /** Set when the speech service could not open the microphone, which a fresh install of Speech Services by Google cannot until allowed. */
    var serviceMicBlocked by mutableStateOf(false)
    private var recognizer: SpeechRecognizer? = null
    private var typed = ""
    private var base = ""
    private var written = ""
    /** Set between [finish] and the final result, while a send may still be waiting on the last words. */
    var finishing by mutableStateOf(false)
        private set
    private var then: (() -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())

    fun start(typed: String) {
        stop()
        val service = component(context)
        val speech = runCatching { SpeechRecognizer.createSpeechRecognizer(context, service) }.getOrNull() ?: return
        recognizer = speech
        if (service != null && !isDefault(context)) lend(service)
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
                if (!finishing && (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)) {
                    base = continued(written)
                    relisten(speech)
                } else {
                    done()
                    if (error == SpeechRecognizer.ERROR_AUDIO || error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) serviceMicBlocked = true
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
        if (draft() != written) return if (finishing) done() else stop()
        written = base + spoken
        onText(written)
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

    private var lent: ServiceConnection? = null

    // Android passes the microphone only to the default speech service on its own; any other one records silence unless
    // the app in front binds it with its own capabilities for as long as it listens.
    private fun lend(service: ComponentName) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {}
            override fun onServiceDisconnected(name: ComponentName?) {}
        }
        val intent = Intent(RecognitionService.SERVICE_INTERFACE).setComponent(service)
        if (runCatching { context.bindService(intent, connection, Context.BIND_AUTO_CREATE or Context.BIND_INCLUDE_CAPABILITIES) }.getOrDefault(false)) lent = connection
        else runCatching { context.unbindService(connection) }
    }

    fun stop() {
        lent?.let { runCatching { context.unbindService(it) } }
        lent = null
        then = null
        finishing = false
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

        /** The package that turns speech into text, if one is installed. */
        fun service(context: Context): String? = component(context)?.packageName

        const val DEFAULT_SETTING = "voice_recognition_service"

        private fun chosen(context: Context) = android.provider.Settings.Secure.getString(context.contentResolver, DEFAULT_SETTING)
            ?.takeIf { it.isNotBlank() }?.let(ComponentName::unflattenFromString)

        private fun installed(context: Context) = context.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
            .map { ComponentName(it.serviceInfo.packageName, it.serviceInfo.name) }

        // Installing a speech service does not always make it the system's choice, and without one Android refuses to listen.
        fun component(context: Context): ComponentName? {
            val chosen = chosen(context)
            val installed = installed(context)
            return installed.firstOrNull { it == chosen } ?: installed.firstOrNull()
        }

        /**
         * Whether the installed service is the system's chosen one. Android only passes DroidDeck's right to the microphone on to that one;
         * any other service hears silence once DroidDeck is in front.
         */
        fun isDefault(context: Context): Boolean {
            val chosen = chosen(context) ?: return false
            return chosen in installed(context)
        }

        fun serviceHears(context: Context, service: String) =
            context.packageManager.checkPermission(android.Manifest.permission.RECORD_AUDIO, service) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }
}
