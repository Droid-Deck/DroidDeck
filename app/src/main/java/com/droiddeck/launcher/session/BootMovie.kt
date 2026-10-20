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
        data class Done(val name: String, val audioDropped: Boolean, val id: String = "") : Import()
        object TooLong : Import()
        data class Unsupported(val codec: String?) : Import()
        object Failed : Import()
    }

    private fun dir(context: Context) = File(context.filesDir, "boot-movie")
    private fun customFile(context: Context) = File(dir(context), "custom.webm")

    /** A movie imported before movies were kept in Steam's own folder. */
    internal fun legacyCustomFile(context: Context) = customFile(context)

    /** Unpacks one of DroidDeck's own movies on demand; null for anything else. */
    internal fun assetFile(context: Context, id: String): File? = ASSETS[id]?.let { extractAsset(context, it) }

    /** Picking a movie here makes it Steam's startup movie at the next session, over whatever Steam had picked. */
    fun select(context: Context, id: String) {
        SessionPrefs.setBootMovie(context, id)
        SessionPrefs.setBootMovieApplyNext(context, true)
        BootMovieReport.note(context, "picked $id here; Steam names ${SessionFiles.startupMoviePath(BootMovieLibrary.steamConfig(context))}")
        // Whatever Steam names right now is about to be replaced, so it is not a pick to adopt later.
        SessionFiles.startupMoviePath(BootMovieLibrary.steamConfig(context))?.let { SessionPrefs.setBootMovieSteamSeen(context, it) }
    }

    fun customName(context: Context): String? =
        if (customFile(context).isFile) SessionPrefs.bootMovieCustomName(context) ?: "custom.webm" else null

    fun choice(context: Context): String {
        val chosen = SessionPrefs.bootMovie(context)
        return when {
            chosen == CUSTOM && customFile(context).isFile -> CUSTOM
            isLibraryId(chosen) && BootMovieLibrary.file(context, chosen) != null -> chosen
            chosen in ASSETS -> chosen
            else -> DROIDDECK_30
        }
    }

    /** The file the current choice plays from, before any volume or length change. */
    fun currentFile(context: Context): File? = sourceOf(context, choice(context))

    private fun sourceOf(context: Context, choice: String): File? = when {
        choice == CUSTOM -> customFile(context)
        isLibraryId(choice) -> BootMovieLibrary.file(context, choice)
        else -> ASSETS[choice]?.let { extractAsset(context, it) }
    }

    private fun isLibraryId(id: String) = id.startsWith(BootMovieLibrary.PREFIX) || id.startsWith(BootMovieLibrary.STEAM_PREFIX)

    fun prepare(context: Context): File? {
        BootMovieLibrary.restore(context)
        val source = sourceOf(context, choice(context)) ?: return null
        return variant(context, source)
    }

    /** The movie as Steam should get it: with the chosen volume and cut off after the chosen number of seconds. */
    private fun variant(context: Context, source: File): File? {
        val volume = if (SessionPrefs.bootMovieSound(context)) SessionPrefs.bootMovieVolume(context) else 0
        val seconds = SessionPrefs.bootMovieSeconds(context)
        val folder = File(dir(context), "variants")
        val plan = if (isOversized(source)) oversizedPlan(volume) else plan(volume, seconds)
        if (plan.passthrough) {
            prune(folder, null)
            return source
        }
        folder.mkdirs()
        val limitUs = if (seconds > 0) seconds * 1_000_000L else Long.MAX_VALUE
        val out = File(folder, BootMovieLibrary.variantPrefix(source) + "v$volume-s$seconds.webm")
        if (out.isFile && out.length() > 0L && out.lastModified() >= source.lastModified()) {
            prune(folder, out.name)
            return out
        }
        val staged = File(folder, out.name + ".staged")
        val ok = runCatching {
            plan.steps.any { step ->
                when (step) {
                    Step.REMUX_SILENT -> remux(source, staged, audio = false, limitUs)
                    Step.REMUX_AUDIO -> remux(source, staged, audio = true, limitUs)
                    Step.TRANSCODE_SILENT -> transcodeSilent(source, staged, limitUs)
                    Step.TRANSCODE_AUDIO -> BootMovieTranscoder.transcode(source, staged, limitUs = limitUs, gain = volume / 100f) {} != null
                    Step.REGAIN -> BootMovieTranscoder.regain(source, staged, volume / 100f, limitUs)
                }
            }
        }.getOrDefault(false)
        if (ok && staged.renameTo(out)) {
            prune(folder, out.name)
            return out
        }
        staged.delete()
        Log.e(TAG, "could not prepare ${source.name} (volume $volume, $seconds s)")
        return if (plan.sourceOnFailure) source else null
    }

    internal enum class Step { REMUX_SILENT, REMUX_AUDIO, TRANSCODE_SILENT, TRANSCODE_AUDIO, REGAIN }

    /**
     * How a movie is processed: the steps tried in order until one works, whether the source goes out untouched, and
     * whether it still plays as it is when every step fails.
     */
    internal class Plan(val passthrough: Boolean, val steps: List<Step>, val sourceOnFailure: Boolean)

    internal fun plan(volume: Int, seconds: Int): Plan = when {
        volume == 100 && seconds == 0 -> Plan(true, emptyList(), true)
        volume == 0 -> Plan(false, listOf(Step.REMUX_SILENT, Step.TRANSCODE_SILENT), false)
        // Only a length that could not be applied plays as it is; a volume that could not be applied plays no louder.
        volume == 100 -> Plan(false, listOf(Step.REMUX_AUDIO, Step.TRANSCODE_AUDIO), true)
        else -> Plan(false, listOf(Step.REGAIN, Step.REMUX_SILENT, Step.TRANSCODE_AUDIO), false)
    }

    /** Frames beyond 1920x1080 (4K and the like) have made Steam crash while it starts, so such a movie is always scaled down. */
    internal const val MAX_LONG_SIDE = 1920
    internal const val MAX_SHORT_SIDE = 1080

    internal fun oversized(width: Int, height: Int) = maxOf(width, height) > MAX_LONG_SIDE || minOf(width, height) > MAX_SHORT_SIDE

    private fun oversized(format: MediaFormat) =
        format.containsKey(MediaFormat.KEY_WIDTH) && format.containsKey(MediaFormat.KEY_HEIGHT) &&
            oversized(format.getInteger(MediaFormat.KEY_WIDTH), format.getInteger(MediaFormat.KEY_HEIGHT))

    private fun isOversized(source: File): Boolean = probe(source)?.first?.let { oversized(it) } == true

    /** A movie that is too large never goes out as it is: it is converted at the chosen volume, and plays nothing if that fails. */
    internal fun oversizedPlan(volume: Int): Plan =
        Plan(false, listOf(if (volume == 0) Step.TRANSCODE_SILENT else Step.TRANSCODE_AUDIO), false)

    private fun transcodeSilent(source: File, target: File, limitUs: Long) =
        BootMovieTranscoder.transcode(source, target, keepAudio = false, limitUs = limitUs) {} != null

    /** Keeps at most the one processed copy in use, so trying settings never leaves movies piling up. */
    private fun prune(folder: File, keep: String?) {
        folder.listFiles()?.forEach { if (it.name != keep) it.delete() }
    }

    /**
     * Takes over a startup movie chosen in Steam's own settings: when Steam's config names a movie it did not name before,
     * that movie becomes the choice here. Steam already plays it, so nothing is staged over it. True when the choice changed.
     */
    fun syncFromSteam(context: Context): Boolean {
        val path = SessionFiles.startupMoviePath(BootMovieLibrary.steamConfig(context)) ?: return false
        if (path == SessionPrefs.bootMovieSteamSeen(context)) return false
        SessionPrefs.setBootMovieSteamSeen(context, path)
        if (path == SessionFiles.BOOT_MOVIE_PATH) return false
        val prefix = "/uioverrides/movies/"
        // The list names a bought movie from the Steam folder (/config/communityitemscache/...), Steam's config from its config folder.
        val id = if (path.startsWith(prefix)) BootMovieLibrary.idOf(path.removePrefix(prefix)) else {
            val listed = BootMovieLibrary.list(context).firstOrNull { it.id.startsWith(BootMovieLibrary.STEAM_PREFIX) && it.id.endsWith(path) }
            listed?.id ?: BootMovieLibrary.STEAM_PREFIX + path
        }
        if (BootMovieLibrary.file(context, id) == null) return false
        val changed = SessionPrefs.bootMovie(context) != id
        BootMovieReport.note(context, "Steam names $path, taken over as $id")
        SessionPrefs.setBootMovie(context, id)
        return changed
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

    fun import(
        context: Context, file: File, onStart: () -> Unit = {}, onProgress: (Int) -> Unit = {},
        title: String? = null, source: String = BootMovieLibrary.SOURCE_IMPORT, repoId: String? = null,
        cover: ByteArray? = null, use: Boolean = true,
    ): Import {
        val (format, audio) = probe(file) ?: return Import.Unsupported(null)
        val video = format.getString(MediaFormat.KEY_MIME)
        val audioPlayable = audio == null || audio in AUDIO_CODECS
        val playable = video in VIDEO_CODECS && file.length() <= MAX_BYTES && !oversized(format)
        val copy = playable && audioPlayable && isWebm(file)
        val remux = !copy && playable && video in REMUX_CODECS
        if (!copy && !remux) {
            if (!BootMovieTranscoder.canDecode(format)) return Import.Unsupported(video)
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            if (durationUs > MAX_CONVERT_SECONDS * 1_000_000L) return Import.TooLong
            onStart()
        }
        val shown = title?.takeIf { it.isNotBlank() } ?: file.nameWithoutExtension
        val library = BootMovieLibrary.libraryDir(context)
        val staged = File(library, ".import-${System.nanoTime()}.staged")
        var audioDropped = remux && !audioPlayable
        return try {
            library.mkdirs()
            val ok = when {
                copy -> {
                    file.inputStream().use { input -> staged.outputStream().use { output -> FileUtils.copy(input, output) } }
                    true
                }
                remux -> remux(file, staged, audio = true)
                else -> BootMovieTranscoder.transcode(file, staged, onProgress = onProgress)?.also { audioDropped = it.audioDropped } != null
            }
            if (!ok || !staged.setReadable(true, false)) return Import.Failed
            val name = BootMovieLibrary.commit(context, staged, shown, source, repoId, cover) ?: return Import.Failed
            if (use) select(context, BootMovieLibrary.idOf(name))
            Import.Done(shown, audioDropped, BootMovieLibrary.idOf(name))
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
        File(dir(context), "variants").listFiles { f -> f.name.startsWith(BootMovieLibrary.variantPrefix(customFile(context))) }?.forEach { it.delete() }
        SessionPrefs.setBootMovieCustomName(context, null)
        if (SessionPrefs.bootMovie(context) == CUSTOM) select(context, DROIDDECK_30)
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

    private fun remux(source: File, target: File, audio: Boolean, limitUs: Long = Long.MAX_VALUE): Boolean {
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
                if (size < 0 || extractor.sampleTime > limitUs) break
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
