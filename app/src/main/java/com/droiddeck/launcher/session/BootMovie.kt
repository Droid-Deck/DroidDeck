package com.droiddeck.launcher.session

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import com.droiddeck.launcher.core.FileUtils
import java.io.File
import java.nio.ByteBuffer

object BootMovie {
    private const val TAG = "BootMovie"
    const val DROIDDECK_30 = "dd30"
    const val DROIDDECK_60 = "dd60"
    const val CUSTOM = "custom"
    const val MAX_BYTES = 50L shl 20
    const val MAX_CONVERT_SECONDS = 60
    val PICKER_EXTENSIONS = listOf("webm", "mkv", "mp4", "mov", "m4v", "3gp")

    private val ASSETS = mapOf(
        DROIDDECK_30 to "droiddeck-startup.webm",
        DROIDDECK_60 to "droiddeck-startup-60.webm",
    )

    // Steam's CEF player only takes WebM codecs; MediaMuxer's WebM writer cannot take AV1.
    internal val VIDEO_CODECS = setOf(MediaFormat.MIMETYPE_VIDEO_VP8, MediaFormat.MIMETYPE_VIDEO_VP9, MediaFormat.MIMETYPE_VIDEO_AV1)
    private val REMUX_CODECS = setOf(MediaFormat.MIMETYPE_VIDEO_VP8, MediaFormat.MIMETYPE_VIDEO_VP9)
    internal val AUDIO_CODECS = setOf(MediaFormat.MIMETYPE_AUDIO_OPUS, MediaFormat.MIMETYPE_AUDIO_VORBIS)

    sealed class Import {
        data class Done(val name: String, val audioDropped: Boolean) : Import()
        object TooLong : Import()
        data class Unsupported(val codec: String?) : Import()
        object Failed : Import()
    }

    private fun dir(context: Context) = File(context.filesDir, "boot-movie")
    private fun customFile(context: Context) = File(dir(context), "custom.webm")

    fun customName(context: Context): String? =
        if (customFile(context).isFile) SessionPrefs.bootMovieCustomName(context) ?: "custom.webm" else null

    fun choice(context: Context): String {
        val chosen = SessionPrefs.bootMovie(context)
        return when {
            chosen == CUSTOM && customFile(context).isFile -> CUSTOM
            chosen in ASSETS -> chosen
            else -> DROIDDECK_30
        }
    }

    fun prepare(context: Context): File? {
        val choice = choice(context)
        val source = if (choice == CUSTOM) customFile(context) else extractAsset(context, ASSETS.getValue(choice)) ?: return null
        if (SessionPrefs.bootMovieSound(context)) return source
        val muted = File(source.parentFile, source.nameWithoutExtension + "-muted.webm")
        if (muted.isFile && muted.lastModified() >= source.lastModified()) return muted
        if (remux(source, muted, audio = false)) return muted
        if (BootMovieTranscoder.transcode(source, muted, keepAudio = false) {} != null) return muted
        Log.e(TAG, "could not strip audio from ${source.name}; not staging it")
        return null
    }

    private fun extractAsset(context: Context, name: String): File? {
        val target = File(dir(context), name)
        val updated = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime }.getOrDefault(Long.MAX_VALUE)
        if (target.isFile && target.lastModified() >= updated) return target
        val staged = File(target.parentFile, "$name.staged")
        return try {
            target.parentFile?.mkdirs()
            context.assets.open("steam-startup/$name").use { input ->
                staged.outputStream().use { output -> FileUtils.copy(input, output) }
            }
            if (staged.renameTo(target)) target else null
        } catch (e: Exception) {
            Log.w(TAG, "could not extract $name", e)
            null
        } finally {
            staged.delete()
        }
    }

    fun import(context: Context, file: File, onStart: () -> Unit = {}, onProgress: (Int) -> Unit = {}): Import {
        val (format, audio) = probe(file) ?: return Import.Unsupported(null)
        val video = format.getString(MediaFormat.KEY_MIME)
        val audioPlayable = audio == null || audio in AUDIO_CODECS
        val playable = video in VIDEO_CODECS && file.length() <= MAX_BYTES
        val copy = playable && audioPlayable && isWebm(file)
        val remux = !copy && playable && video in REMUX_CODECS
        if (!copy && !remux) {
            if (!BootMovieTranscoder.canDecode(format)) return Import.Unsupported(video)
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            if (durationUs > MAX_CONVERT_SECONDS * 1_000_000L) return Import.TooLong
            onStart()
        }
        val target = customFile(context)
        val staged = File(dir(context), "custom.webm.staged")
        var audioDropped = remux && !audioPlayable
        return try {
            target.parentFile?.mkdirs()
            val ok = when {
                copy -> {
                    file.inputStream().use { input -> staged.outputStream().use { output -> FileUtils.copy(input, output) } }
                    true
                }
                remux -> remux(file, staged, audio = true)
                else -> BootMovieTranscoder.transcode(file, staged, onProgress = onProgress)?.also { audioDropped = it.audioDropped } != null
            }
            if (!ok || !staged.renameTo(target)) return Import.Failed
            File(target.parentFile, "custom-muted.webm").delete()
            SessionPrefs.setBootMovieCustomName(context, file.name)
            SessionPrefs.setBootMovie(context, CUSTOM)
            Import.Done(file.name, audioDropped)
        } catch (e: Exception) {
            Log.w(TAG, "could not import ${file.name}", e)
            Import.Failed
        } finally {
            staged.delete()
        }
    }

    fun removeCustom(context: Context) {
        customFile(context).delete()
        File(dir(context), "custom-muted.webm").delete()
        SessionPrefs.setBootMovieCustomName(context, null)
        if (SessionPrefs.bootMovie(context) == CUSTOM) SessionPrefs.setBootMovie(context, DROIDDECK_30)
    }

    private fun isWebm(file: File): Boolean {
        if (!file.name.endsWith(".webm", ignoreCase = true)) return false
        val magic = ByteArray(4)
        val read = runCatching { file.inputStream().use { it.read(magic) } }.getOrDefault(0)
        return read == 4 && magic.contentEquals(byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte()))
    }

    private fun probe(file: File): Pair<MediaFormat, String?>? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.path)
            var video: MediaFormat? = null
            var audio: String? = null
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (video == null && mime.startsWith("video/")) video = format
                if (audio == null && mime.startsWith("audio/")) audio = mime
            }
            video?.let { it to audio }
        } catch (e: Exception) {
            Log.w(TAG, "could not read ${file.name}", e)
            null
        } finally {
            extractor.release()
        }
    }

    private fun remux(source: File, target: File, audio: Boolean): Boolean {
        val staged = File(target.parentFile, target.name + ".remux")
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        return try {
            extractor.setDataSource(source.path)
            val output = MediaMuxer(staged.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM).also { muxer = it }
            val tracks = HashMap<Int, Int>()
            var hasVideo = false
            var hasAudio = false
            var bufferSize = 8 shl 20
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                val keep = mime in VIDEO_CODECS && !hasVideo || audio && mime in AUDIO_CODECS && !hasAudio
                if (!keep) continue
                if (mime in VIDEO_CODECS) hasVideo = true else hasAudio = true
                extractor.selectTrack(i)
                tracks[i] = output.addTrack(format)
                if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) bufferSize = maxOf(bufferSize, format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
            }
            if (!hasVideo) return false
            output.start()
            val buffer = ByteBuffer.allocate(bufferSize)
            val info = MediaCodec.BufferInfo()
            while (true) {
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val track = tracks[extractor.sampleTrackIndex]
                if (track != null) {
                    val key = extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0
                    info.set(0, size, extractor.sampleTime, if (key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                    output.writeSampleData(track, buffer, info)
                }
                extractor.advance()
            }
            output.stop()
            output.release()
            muxer = null
            staged.renameTo(target)
        } catch (e: Exception) {
            Log.w(TAG, "could not remux ${source.name}", e)
            false
        } finally {
            runCatching { muxer?.release() }
            extractor.release()
            staged.delete()
        }
    }
}
