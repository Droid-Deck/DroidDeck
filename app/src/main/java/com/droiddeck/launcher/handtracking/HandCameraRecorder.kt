package com.droiddeck.launcher.handtracking

import android.content.Context
import android.graphics.Bitmap
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.util.Log
import com.droiddeck.launcher.glasses.Vec3
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.roundToInt

internal class HandCameraRecorder private constructor(private val dir: File, private val traceFlag: File?) {
    class Capture(val exposureNs: Long, val frameDurationNs: Long, val iso: Int)

    class Frame(
        val exposureNs: Long,
        val sensorNs: Long,
        val arrivalNs: Long,
        val inferredNs: Long,
        val doneNs: Long,
        val skipped: Int,
        val thermal: Int,
        val turns: Int,
        val placement: Placement,
        val gravity: Vec3?,
        val intrinsics: Intrinsics?,
        val marks: List<FloatArray>?,
        val world: List<WorldPoint>?,
        val body: BodyFrame?,
        val handsNs: Long = 0L,
        val fixes: Array<HandFix?>? = null,
    )

    private val writer = Executors.newSingleThreadExecutor { Thread(it, "hand-camera-record") }
    private val lines: BufferedWriter = File(dir, "frames.jsonl").bufferedWriter()
    private val busy = AtomicBoolean(false)
    private val captures = object : LinkedHashMap<Long, Capture>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Capture>?) = size > KEEP_CAPTURES
    }
    private var count = 0
    @Volatile private var closed = false

    val callback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            val stamp = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
            val capture = Capture(result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L,
                result.get(CaptureResult.SENSOR_FRAME_DURATION) ?: 0L, result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0)
            synchronized(captures) { captures[stamp] = capture }
        }
    }

    fun frame(frame: Frame, image: Bitmap) {
        if (closed || count >= MAX_FRAMES) return
        val index = ++count
        val capture = synchronized(captures) { captures[frame.sensorNs] }
        val small = if (busy.compareAndSet(false, true)) runCatching { shrink(image) }.getOrNull().also { if (it == null) busy.set(false) } else null
        val name = if (small != null) "%05d.jpg".format(Locale.ROOT, index) else null
        val line = json(index, frame, capture, name)
        val queued = runCatching { writer.execute {
            if (small != null) {
                runCatching { FileOutputStream(File(dir, name!!)).use { small.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) } }
                small.recycle()
                busy.set(false)
            }
            runCatching {
                lines.write(line)
                lines.newLine()
                if (index % FLUSH_EVERY == 0) lines.flush()
            }
        } }
        if (queued.isFailure) {
            small?.recycle()
            busy.set(false)
        }
    }

    fun close() {
        if (closed) return
        closed = true
        traceFlag?.delete()
        writer.execute { runCatching { lines.close() } }
        writer.shutdown()
        Log.i(TAG, "recorded $count frames to $dir")
    }

    private fun shrink(image: Bitmap): Bitmap {
        val scale = SIDE.toFloat() / max(image.width, image.height)
        if (scale >= 1f) return image.copy(Bitmap.Config.ARGB_8888, false)
        return Bitmap.createScaledBitmap(image, (image.width * scale).roundToInt(), (image.height * scale).roundToInt(), true)
    }

    private fun json(index: Int, f: Frame, capture: Capture?, jpg: String?): String = buildString {
        append("{\"i\":").append(index)
        append(",\"exp\":").append(f.exposureNs)
        append(",\"sensor\":").append(f.sensorNs)
        append(",\"arr\":").append(f.arrivalNs)
        append(",\"inf\":").append(f.inferredNs)
        append(",\"done\":").append(f.doneNs)
        append(",\"hand\":").append(f.handsNs)
        append(",\"skip\":").append(f.skipped)
        append(",\"heat\":").append(f.thermal)
        append(",\"turns\":").append(f.turns)
        append(",\"place\":\"").append(f.placement.name).append('"')
        append(",\"g\":").vec(f.gravity)
        f.intrinsics?.let { append(",\"k\":[").num(it.fx).append(',').num(it.cx).append(',').num(it.cy).append(',').append(it.width).append(',').append(it.height).append(']') }
        capture?.let { append(",\"ae\":[").append(it.exposureNs).append(',').append(it.frameDurationNs).append(',').append(it.iso).append(']') }
        append(",\"lm\":")
        if (f.marks == null) append("null") else {
            append('[')
            f.marks.forEachIndexed { i, m ->
                if (i > 0) append(',')
                append('[')
                m.forEachIndexed { j, v -> if (j > 0) append(','); num(v) }
                append(']')
            }
            append(']')
        }
        append(",\"wl\":")
        if (f.world == null) append("null") else {
            append('[')
            f.world.forEachIndexed { i, w ->
                if (i > 0) append(',')
                append('[').num(w.x).append(',').num(w.y).append(',').num(w.z).append(']')
            }
            append(']')
        }
        f.body?.let { b ->
            append(",\"hands\":[").vec(b.hands[0]).append(',').vec(b.hands[1]).append(']')
            append(",\"face\":"); if (b.faceYaw == null) append("null") else num(b.faceYaw)
            append(",\"est\":[").append(b.estimated[0]).append(',').append(b.estimated[1]).append(']')
            append(",\"room\":").num(b.edgeRoom)
            append(",\"dist\":").num(b.distance)
            append(",\"span\":").num(b.shoulderSpan)
            append(",\"joints\":[")
            b.joints.forEachIndexed { i, j ->
                if (i > 0) append(',')
                if (j == null) append("null") else {
                    append('[')
                    j.forEachIndexed { k, v -> if (k > 0) append(','); num(v) }
                    append(']')
                }
            }
            append(']')
        }
        f.fixes?.let { fixes ->
            append(",\"fix\":[")
            fixes.forEachIndexed { i, fix ->
                if (i > 0) append(',')
                if (fix == null) append("null") else {
                    append("{\"palm\":").vec(fix.palm).append(",\"at\":[").num(fix.x).append(',').num(fix.y).append("],\"size\":").num(fix.extent)
                    append(",\"pts\":[")
                    fix.points.forEachIndexed { j, v -> if (j > 0) append(','); num(v) }
                    append("],\"world\":[")
                    fix.world.forEachIndexed { j, w -> if (j > 0) append(','); num(w.x).append(',').num(w.y).append(',').num(w.z) }
                    append("]}")
                }
            }
            append(']')
        }
        append(",\"jpg\":"); if (jpg == null) append("null") else append('"').append(jpg).append('"')
        append('}')
    }

    private fun StringBuilder.num(v: Float): StringBuilder = if (v.isFinite()) append(v) else append("null")

    private fun StringBuilder.vec(v: Vec3?): StringBuilder =
        if (v == null) append("null") else append('[').num(v.x).append(',').num(v.y).append(',').num(v.z).append(']')

    companion object {
        private const val TAG = "HandCamera"
        const val FLAG = "handcam-record"
        const val TRACE_FLAG = "droiddeck-handtrace.on"
        private const val MAX_FRAMES = 5400
        private const val KEEP_CAPTURES = 32
        private const val SIDE = 640
        private const val QUALITY = 85
        private const val FLUSH_EVERY = 30

        fun start(context: Context, output: File?, preview: Boolean, camera: TrackingCamera): HandCameraRecorder? {
            if (!File(context.filesDir, FLAG).exists()) return null
            return runCatching {
                val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
                val dir = File(context.cacheDir, "handcam-rec/$stamp-${if (preview) "check" else "track"}").apply { mkdirs() }
                File(dir, "camera.json").writeText("{\"id\":\"${camera.id}\",\"front\":${camera.front},\"orientation\":${camera.orientation}," +
                    "\"focal\":${camera.focalPixels},\"active\":[${camera.activeWidth},${camera.activeHeight}]," +
                    "\"fps\":${camera.fps?.let { "[${it.first},${it.second}]" } ?: "null"}}")
                val flag = output?.parentFile?.let { File(it, TRACE_FLAG) }?.also { it.createNewFile() }
                Log.i(TAG, "recording hand camera to $dir")
                HandCameraRecorder(dir, flag)
            }.onFailure { Log.w(TAG, "hand camera recording unavailable", it) }.getOrNull()
        }
    }
}
