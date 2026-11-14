package com.droiddeck.launcher.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log

object DirectAudioTiming {
    private const val TAG = "DirectAudioTiming"
    const val PERIOD_ENV = "AUDIO_DIRECT_PERIOD_MS"
    const val BUFFER_ENV = "AUDIO_DIRECT_MS"
    const val SLOW_OUTPUT_PERIOD_MS = 20
    const val SLOW_OUTPUT_BUFFER_MS = 60

    fun environment(lowLatencyOutput: Boolean): List<String> =
        if (lowLatencyOutput) emptyList()
        else listOf("$PERIOD_ENV=$SLOW_OUTPUT_PERIOD_MS", "$BUFFER_ENV=$SLOW_OUTPUT_BUFFER_MS")

    fun lowLatencyOutput(): Boolean = try {
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build())
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setSampleRate(48000)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .build())
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        try {
            track.performanceMode == AudioTrack.PERFORMANCE_MODE_LOW_LATENCY
        } finally {
            track.release()
        }
    } catch (e: Exception) {
        Log.w(TAG, "audio: could not check for low-latency output; DirectAudio keeps its defaults", e)
        true
    }
}
