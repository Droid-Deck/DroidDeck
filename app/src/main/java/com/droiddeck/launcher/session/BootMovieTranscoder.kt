package com.droiddeck.launcher.session

import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLExt
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

internal object BootMovieTranscoder {
    private const val TAG = "BootMovieTranscoder"
    private const val TIMEOUT_US = 10_000L
    private const val MAX_FPS = 60
    private const val OPUS_RATE = 48_000

    class Result(val audioDropped: Boolean)

    private class Sample(val data: ByteArray, val timeUs: Long)
    private class Audio(val format: MediaFormat, val samples: List<Sample>)

    fun canDecode(format: MediaFormat): Boolean = runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(format) != null
    }.getOrDefault(false)

    fun transcode(source: File, target: File, keepAudio: Boolean = true, limitUs: Long = Long.MAX_VALUE, gain: Float = 1f, onProgress: (Int) -> Unit): Result? {
        val hadAudio = keepAudio && trackFormat(source, "audio/") != null
        val audio = if (hadAudio) runCatching { encodeAudio(source, gain, limitUs) }
            .onFailure { Log.w(TAG, "could not convert the audio of ${source.name}", it) }.getOrNull() else null
        val muxedAudio = runCatching { encodeVideo(source, target, audio, limitUs, onProgress) }
            .onFailure { Log.w(TAG, "could not convert ${source.name}", it) }.getOrNull() ?: return null
        return Result(audioDropped = hadAudio && !muxedAudio)
    }

    /** Copies the video of [source] untouched and gives it its sound again at [gain] of its level, cut to [limitUs]. */
    fun regain(source: File, target: File, gain: Float, limitUs: Long): Boolean {
        val audio = runCatching { encodeAudio(source, gain, limitUs) }
            .onFailure { Log.w(TAG, "could not change the volume of ${source.name}", it) }.getOrNull() ?: return false
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        val staged = File(target.parentFile, target.name + ".regain")
        return try {
            extractor.setDataSource(source.path)
            var videoTrack = -1
            var videoFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (videoTrack < 0 && (mime == MediaFormat.MIMETYPE_VIDEO_VP8 || mime == MediaFormat.MIMETYPE_VIDEO_VP9)) {
                    videoTrack = i
                    videoFormat = format
                }
            }
            if (videoTrack < 0 || videoFormat == null) return false
            extractor.selectTrack(videoTrack)
            val mux = MediaMuxer(staged.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM).also { muxer = it }
            val outVideo = mux.addTrack(videoFormat)
            val outAudio = mux.addTrack(audio.format)
            mux.start()
            val info = MediaCodec.BufferInfo()
            var audioIndex = 0
            fun writeAudioUpTo(timeUs: Long) {
                while (audioIndex < audio.samples.size && audio.samples[audioIndex].timeUs <= timeUs) {
                    val sample = audio.samples[audioIndex++]
                    info.set(0, sample.data.size, sample.timeUs, 0)
                    mux.writeSampleData(outAudio, ByteBuffer.wrap(sample.data), info)
                }
            }
            var bufferSize = 8 shl 20
            if (videoFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) bufferSize = maxOf(bufferSize, videoFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
            val buffer = ByteBuffer.allocate(bufferSize)
            while (true) {
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0 || extractor.sampleTime > limitUs) break
                val time = extractor.sampleTime
                writeAudioUpTo(time)
                val key = extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0
                info.set(0, size, time, if (key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                mux.writeSampleData(outVideo, buffer, info)
                extractor.advance()
            }
            writeAudioUpTo(Long.MAX_VALUE)
            mux.stop()
            mux.release()
            muxer = null
            staged.renameTo(target)
        } catch (e: Exception) {
            Log.w(TAG, "could not change the volume of ${source.name}", e)
            false
        } finally {
            runCatching { muxer?.release() }
            extractor.release()
            staged.delete()
        }
    }

    private fun trackFormat(source: File, prefix: String): Pair<Int, MediaFormat>? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(source.path)
            (0 until extractor.trackCount).map { it to extractor.getTrackFormat(it) }
                .firstOrNull { it.second.getString(MediaFormat.KEY_MIME)?.startsWith(prefix) == true }
        } finally {
            extractor.release()
        }
    }

    internal class Layout(val width: Int, val height: Int, val x: Int, val y: Int, val contentWidth: Int, val contentHeight: Int)

    // Steam fills the screen with its startup movie, so portrait clips are pillarboxed into 16:9 instead of cropped.
    internal fun layout(width: Int, height: Int, rotation: Int): Layout {
        val (w, h) = if (rotation % 180 != 0) height to width else width to height
        fun even(v: Double) = maxOf(2, (v.toInt() / 2) * 2)
        if (w >= h) {
            val scale = minOf(1.0, 1280.0 / w, 720.0 / h)
            val cw = even(w * scale)
            val ch = even(h * scale)
            return Layout(cw, ch, 0, 0, cw, ch)
        }
        val ch = even(minOf(h, 720).toDouble())
        val cw = even(w * ch.toDouble() / h)
        val canvas = even(ch * 16 / 9.0)
        return Layout(canvas, ch, (canvas - cw) / 2, 0, cw, ch)
    }

    /** [pcm] made quieter by [gain] and cut to its first [limitUs] microseconds. */
    internal fun scale(pcm: ShortArray, channels: Int, gain: Float, limitUs: Long): ShortArray {
        val frames = pcm.size / channels
        val kept = if (limitUs == Long.MAX_VALUE) frames else minOf(frames.toLong(), limitUs * OPUS_RATE / 1_000_000L).toInt()
        if (gain == 1f && kept == frames) return pcm
        return ShortArray(kept * channels) { (pcm[it] * gain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort() }
    }

    internal fun resample(pcm: ShortArray, channels: Int, rate: Int): ShortArray {
        if (rate == OPUS_RATE) return pcm
        val frames = pcm.size / channels
        val outFrames = (frames.toLong() * OPUS_RATE / rate).toInt()
        val out = ShortArray(outFrames * channels)
        for (i in 0 until outFrames) {
            val pos = i.toDouble() * rate / OPUS_RATE
            val i0 = pos.toInt().coerceAtMost(frames - 1)
            val i1 = (i0 + 1).coerceAtMost(frames - 1)
            val frac = pos - i0
            for (c in 0 until channels) {
                val a = pcm[i0 * channels + c]
                val b = pcm[i1 * channels + c]
                out[i * channels + c] = (a + (b - a) * frac).toInt().toShort()
            }
        }
        return out
    }

    /** Most decoded audio held in memory at once: about six minutes of 48 kHz stereo. */
    private const val MAX_PCM_BYTES = 64 shl 20

    private fun encodeAudio(source: File, gain: Float = 1f, limitUs: Long = Long.MAX_VALUE): Audio? {
        val (track, inFormat) = trackFormat(source, "audio/") ?: return null
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        try {
            extractor.setDataSource(source.path)
            extractor.selectTrack(track)
            var rate = inFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = inFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val dec = MediaCodec.createDecoderByType(inFormat.getString(MediaFormat.KEY_MIME)!!).also { decoder = it }
            dec.configure(inFormat, null, null, 0)
            dec.start()
            val pcm = ByteArrayOutputStream()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            while (true) {
                if (!inputDone) {
                    val index = dec.dequeueInputBuffer(TIMEOUT_US)
                    if (index >= 0) {
                        val size = extractor.readSampleData(dec.getInputBuffer(index)!!, 0)
                        if (size < 0) {
                            dec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            dec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val index = dec.dequeueOutputBuffer(info, TIMEOUT_US)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val format = dec.outputFormat
                    rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    if (format.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        format.getInteger(MediaFormat.KEY_PCM_ENCODING) != android.media.AudioFormat.ENCODING_PCM_16BIT) return null
                } else if (index >= 0) {
                    if (info.size > 0) {
                        val bytes = ByteArray(info.size)
                        dec.getOutputBuffer(index)!!.apply { position(info.offset); get(bytes) }
                        pcm.write(bytes)
                    }
                    dec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    // Past the length that will be kept there is nothing more to decode; without a length, a track too big to hold is dropped.
                    if (limitUs != Long.MAX_VALUE && info.presentationTimeUs > limitUs) break
                    if (pcm.size() > MAX_PCM_BYTES) { if (limitUs == Long.MAX_VALUE) return null else break }
                }
            }
            if (channels !in 1..2) return null
            val raw = pcm.toByteArray()
            val shorts = ShortArray(raw.size / 2)
            ByteBuffer.wrap(raw).order(ByteOrder.nativeOrder()).asShortBuffer().get(shorts)
            val samples = scale(resample(shorts, channels, rate), channels, gain, limitUs)

            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, OPUS_RATE, channels)
            format.setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            val name = MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(format) ?: return null
            val enc = MediaCodec.createByCodecName(name).also { encoder = it }
            enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            enc.start()
            val packets = ArrayList<Sample>()
            var outFormat: MediaFormat? = null
            var offset = 0
            var encodeInputDone = false
            while (true) {
                if (!encodeInputDone) {
                    val index = enc.dequeueInputBuffer(TIMEOUT_US)
                    if (index >= 0) {
                        val buffer = enc.getInputBuffer(index)!!
                        buffer.clear()
                        val count = minOf(buffer.remaining() / 2, samples.size - offset) / channels * channels
                        val timeUs = offset.toLong() / channels * 1_000_000L / OPUS_RATE
                        if (count == 0) {
                            enc.queueInputBuffer(index, 0, 0, timeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            encodeInputDone = true
                        } else {
                            buffer.order(ByteOrder.nativeOrder()).asShortBuffer().put(samples, offset, count)
                            enc.queueInputBuffer(index, 0, count * 2, timeUs, 0)
                            offset += count
                        }
                    }
                }
                val index = enc.dequeueOutputBuffer(info, TIMEOUT_US)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    outFormat = enc.outputFormat
                } else if (index >= 0) {
                    if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        val bytes = ByteArray(info.size)
                        enc.getOutputBuffer(index)!!.apply { position(info.offset); get(bytes) }
                        packets.add(Sample(bytes, info.presentationTimeUs))
                    }
                    enc.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
            return outFormat?.let { Audio(it, packets) }
        } finally {
            runCatching { decoder?.stop() }
            decoder?.release()
            runCatching { encoder?.stop() }
            encoder?.release()
            extractor.release()
        }
    }

    private fun MediaFormat.intOrNull(key: String): Int? = if (!containsKey(key)) null else
        runCatching { getInteger(key) }.getOrElse { runCatching { getFloat(key).toInt() }.getOrNull() }

    private fun encodeVideo(source: File, target: File, audio: Audio?, limitUs: Long, onProgress: (Int) -> Unit): Boolean {
        val (track, inFormat) = trackFormat(source, "video/") ?: throw IllegalStateException("no video track")
        val rotation = (inFormat.intOrNull(MediaFormat.KEY_ROTATION) ?: 0).mod(360)
        val layout = layout(inFormat.getInteger(MediaFormat.KEY_WIDTH), inFormat.getInteger(MediaFormat.KEY_HEIGHT), rotation)
        val width = layout.width
        val height = layout.height
        val fps = (inFormat.intOrNull(MediaFormat.KEY_FRAME_RATE) ?: 30).coerceIn(1, MAX_FPS)
        val durationUs = if (inFormat.containsKey(MediaFormat.KEY_DURATION)) inFormat.getLong(MediaFormat.KEY_DURATION) else 0L
        val minIntervalUs = 1_000_000L / MAX_FPS - 2_000

        val encFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_VP8, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, (width.toLong() * height * fps / 10).coerceIn(1_000_000, 8_000_000).toInt())
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
        }
        val encoderName = MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(encFormat)
            ?: throw IllegalStateException("no VP8 encoder")

        val extractor = MediaExtractor()
        var encoder: MediaCodec? = null
        var decoder: MediaCodec? = null
        var egl: EglWindow? = null
        var renderer: FrameRenderer? = null
        var muxer: MediaMuxer? = null
        val staged = File(target.parentFile, target.name + ".convert")
        try {
            extractor.setDataSource(source.path)
            extractor.selectTrack(track)
            val enc = MediaCodec.createByCodecName(encoderName).also { encoder = it }
            enc.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val window = EglWindow(enc.createInputSurface()).also { egl = it }
            val frames = FrameRenderer(rotation).also { renderer = it }
            enc.start()
            inFormat.setInteger(MediaFormat.KEY_ROTATION, 0)
            val dec = MediaCodec.createDecoderByType(inFormat.getString(MediaFormat.KEY_MIME)!!).also { decoder = it }
            dec.configure(inFormat, frames.surface, null, 0)
            dec.start()
            val mux = MediaMuxer(staged.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM).also { muxer = it }

            var videoTrack = -1
            var audioTrack = -1
            var audioIndex = 0
            fun writeAudioUpTo(timeUs: Long) {
                if (audioTrack < 0 || audio == null) return
                val info = MediaCodec.BufferInfo()
                while (audioIndex < audio.samples.size && audio.samples[audioIndex].timeUs <= timeUs) {
                    val sample = audio.samples[audioIndex++]
                    info.set(0, sample.data.size, sample.timeUs, 0)
                    mux.writeSampleData(audioTrack, ByteBuffer.wrap(sample.data), info)
                }
            }

            val decInfo = MediaCodec.BufferInfo()
            val encInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var decoderDone = false
            var lastKept = Long.MIN_VALUE
            var lastProgress = -1
            while (true) {
                if (!inputDone) {
                    val index = dec.dequeueInputBuffer(TIMEOUT_US)
                    if (index >= 0) {
                        val size = extractor.readSampleData(dec.getInputBuffer(index)!!, 0)
                        if (size < 0) {
                            dec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            dec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                if (!decoderDone) {
                    val index = dec.dequeueOutputBuffer(decInfo, TIMEOUT_US)
                    if (index >= 0) {
                        val timeUs = decInfo.presentationTimeUs
                        val pastLimit = timeUs > limitUs
                        val keep = decInfo.size > 0 && !pastLimit && (lastKept == Long.MIN_VALUE || timeUs - lastKept >= minIntervalUs)
                        dec.releaseOutputBuffer(index, keep)
                        if (keep) {
                            if (!frames.awaitFrame()) throw IllegalStateException("decoder frame timed out")
                            frames.draw(layout)
                            window.swap(timeUs * 1000)
                            lastKept = timeUs
                            if (durationUs > 0) {
                                val percent = (timeUs * 100 / durationUs).toInt().coerceIn(0, 99)
                                if (percent != lastProgress) { lastProgress = percent; onProgress(percent) }
                            }
                        }
                        if (pastLimit || decInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            enc.signalEndOfInputStream()
                            decoderDone = true
                            inputDone = true
                        }
                    }
                }
                var encoderDone = false
                while (true) {
                    val index = enc.dequeueOutputBuffer(encInfo, if (decoderDone) TIMEOUT_US else 0)
                    if (index == MediaCodec.INFO_TRY_AGAIN_LATER) break
                    if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        videoTrack = mux.addTrack(enc.outputFormat)
                        if (audio != null) audioTrack = runCatching { mux.addTrack(audio.format) }
                            .onFailure { Log.w(TAG, "WebM muxer refused the Opus track", it) }.getOrDefault(-1)
                        mux.start()
                    } else if (index >= 0) {
                        if (encInfo.size > 0 && encInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            check(videoTrack >= 0) { "encoder produced data before its format" }
                            writeAudioUpTo(encInfo.presentationTimeUs)
                            mux.writeSampleData(videoTrack, enc.getOutputBuffer(index)!!, encInfo)
                        }
                        enc.releaseOutputBuffer(index, false)
                        if (encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) { encoderDone = true; break }
                    }
                }
                if (encoderDone) break
            }
            writeAudioUpTo(Long.MAX_VALUE)
            mux.stop()
            mux.release()
            muxer = null
            if (!staged.renameTo(target)) throw IllegalStateException("could not move the converted movie")
            onProgress(100)
            return audioTrack >= 0
        } finally {
            runCatching { muxer?.release() }
            runCatching { decoder?.stop() }
            decoder?.release()
            runCatching { encoder?.stop() }
            encoder?.release()
            renderer?.release()
            egl?.release()
            extractor.release()
            staged.delete()
        }
    }

    private class EglWindow(private val surface: Surface) {
        private val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        private val context: android.opengl.EGLContext
        private val window: android.opengl.EGLSurface

        init {
            val version = IntArray(2)
            check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }
            val attributes = intArrayOf(
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL_RECORDABLE_ANDROID, 1, EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) && count[0] > 0) { "no recordable EGL config" }
            context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
            window = EGL14.eglCreateWindowSurface(display, configs[0], surface, intArrayOf(EGL14.EGL_NONE), 0)
            check(EGL14.eglMakeCurrent(display, window, window, context)) { "eglMakeCurrent failed" }
        }

        fun swap(timeNs: Long) {
            EGLExt.eglPresentationTimeANDROID(display, window, timeNs)
            EGL14.eglSwapBuffers(display, window)
        }

        fun release() {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, window)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(display)
            surface.release()
        }

        companion object {
            private const val EGL_RECORDABLE_ANDROID = 0x3142
        }
    }

    private class FrameRenderer(rotation: Int) {
        private val thread = HandlerThread("boot-movie-frames").apply { start() }
        private val lock = Object()
        private var available = false
        private val texture = IntArray(1).also { GLES20.glGenTextures(1, it, 0) }[0]
        private val surfaceTexture: SurfaceTexture
        val surface: Surface
        private val program: Int
        private val transform = FloatArray(16)
        private val positions: FloatBuffer
        private val coords: FloatBuffer = floats(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)

        init {
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            surfaceTexture = SurfaceTexture(texture)
            surfaceTexture.setOnFrameAvailableListener({
                synchronized(lock) { available = true; lock.notifyAll() }
            }, Handler(thread.looper))
            surface = Surface(surfaceTexture)
            program = link(VERTEX, FRAGMENT)
            val radians = Math.toRadians(rotation.toDouble())
            val cos = Math.cos(radians).toFloat()
            val sin = Math.sin(radians).toFloat()
            val corners = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
            positions = floats(*FloatArray(8) { i ->
                val x = corners[i - i % 2]
                val y = corners[i - i % 2 + 1]
                if (i % 2 == 0) x * cos + y * sin else -x * sin + y * cos
            })
        }

        fun awaitFrame(): Boolean {
            synchronized(lock) {
                val deadline = System.currentTimeMillis() + 2_500
                while (!available) {
                    val wait = deadline - System.currentTimeMillis()
                    if (wait <= 0) return false
                    lock.wait(wait)
                }
                available = false
            }
            surfaceTexture.updateTexImage()
            return true
        }

        fun draw(layout: Layout) {
            surfaceTexture.getTransformMatrix(transform)
            GLES20.glViewport(0, 0, layout.width, layout.height)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glViewport(layout.x, layout.y, layout.contentWidth, layout.contentHeight)
            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
            val position = GLES20.glGetAttribLocation(program, "aPosition")
            val coord = GLES20.glGetAttribLocation(program, "aCoord")
            GLES20.glEnableVertexAttribArray(position)
            GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, positions)
            GLES20.glEnableVertexAttribArray(coord)
            GLES20.glVertexAttribPointer(coord, 2, GLES20.GL_FLOAT, false, 0, coords)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uTransform"), 1, false, transform, 0)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }

        fun release() {
            surface.release()
            surfaceTexture.release()
            GLES20.glDeleteProgram(program)
            GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
            thread.quitSafely()
        }

        private fun floats(vararg values: Float): FloatBuffer =
            ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(values); position(0) }

        private fun link(vertex: String, fragment: String): Int {
            fun compile(type: Int, code: String): Int = GLES20.glCreateShader(type).also {
                GLES20.glShaderSource(it, code)
                GLES20.glCompileShader(it)
                val ok = IntArray(1)
                GLES20.glGetShaderiv(it, GLES20.GL_COMPILE_STATUS, ok, 0)
                check(ok[0] != 0) { GLES20.glGetShaderInfoLog(it) }
            }
            return GLES20.glCreateProgram().also {
                GLES20.glAttachShader(it, compile(GLES20.GL_VERTEX_SHADER, vertex))
                GLES20.glAttachShader(it, compile(GLES20.GL_FRAGMENT_SHADER, fragment))
                GLES20.glLinkProgram(it)
                val ok = IntArray(1)
                GLES20.glGetProgramiv(it, GLES20.GL_LINK_STATUS, ok, 0)
                check(ok[0] != 0) { GLES20.glGetProgramInfoLog(it) }
            }
        }

        companion object {
            private val VERTEX = """
                attribute vec4 aPosition;
                attribute vec4 aCoord;
                uniform mat4 uTransform;
                varying vec2 vCoord;
                void main() {
                    gl_Position = aPosition;
                    vCoord = (uTransform * aCoord).xy;
                }
            """.trimIndent()
            private val FRAGMENT = """
                #extension GL_OES_EGL_image_external : require
                precision mediump float;
                varying vec2 vCoord;
                uniform samplerExternalOES sTexture;
                void main() {
                    gl_FragColor = texture2D(sTexture, vCoord);
                }
            """.trimIndent()
        }
    }
}
