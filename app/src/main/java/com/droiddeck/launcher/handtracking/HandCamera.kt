package com.droiddeck.launcher.handtracking

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.util.Size
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.droiddeck.launcher.glasses.Vec3
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.abs

@androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
internal class HandCamera(
    context: Context,
    private val output: File?,
    private val preview: Boolean,
) : LifecycleOwner {
    class View(
        val image: Bitmap,
        val front: Boolean,
        val landmarks: List<Landmark>?,
        val placement: Placement,
        val advice: Advice,
    )

    private val context = context.applicationContext
    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "hand-camera") }
    private val sensors = this.context.getSystemService(SensorManager::class.java)
    private val power = this.context.getSystemService(PowerManager::class.java)
    private val state = MutableStateFlow<View?>(null)
    val view: StateFlow<View?> = state.asStateFlow()
    private val broken = MutableStateFlow(false)
    val failed: StateFlow<Boolean> = broken.asStateFlow()
    @Volatile private var up: Vec3? = null
    @Volatile private var thermal = 0
    @Volatile private var stopped = false
    @Volatile var bareHands = false
        set(value) {
            if (field != value) Log.i(TAG, if (value) "no controllers: tracking bare hands" else "controllers in use: placing them only")
            field = value
        }
    private var provider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    private var landmarker: PoseLandmarker? = null
    private var hands: HandModel? = null
    private val heldDepth = FloatArray(2) { Float.NaN }
    private val heldAt = LongArray(2)
    private var shm: HandCameraShm? = null
    private var camera: TrackingCamera? = null
    private var zoom = 1f
    private var lastRun = 0L
    private var lastStampMs = 0L
    private var skipped = 0
    private var recorder: HandCameraRecorder? = null
    private var rawFrame: Bitmap? = null
    private var turnedFrame: Bitmap? = null

    private val gravity = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val v = Vec3(event.values[0], event.values[1], event.values[2])
            val last = up
            up = if (event.sensor.type == Sensor.TYPE_GRAVITY || last == null) v else last + (v - last) * ACCEL_SMOOTHING
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
    }

    private val heat: Any? = if (Build.VERSION.SDK_INT >= 29) PowerManager.OnThermalStatusChangedListener { thermal = it } else null

    init {
        registry.currentState = Lifecycle.State.CREATED
    }

    fun start(side: String?) {
        val chosen = TrackingCameras.pick(HandTracking.cameras(context), side)
        if (chosen == null) {
            broken.value = true
            return
        }
        camera = chosen
        zoom = chosen.zoom()
        recorder = HandCameraRecorder.start(context, output, preview, chosen)
        val sensor = sensors?.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (sensor != null) sensors?.registerListener(gravity, sensor, SensorManager.SENSOR_DELAY_UI)
        if (Build.VERSION.SDK_INT >= 29) {
            thermal = power?.currentThermalStatus ?: 0
            power?.addThermalStatusListener(ContextCompat.getMainExecutor(context), heat as PowerManager.OnThermalStatusChangedListener)
        }
        executor.execute { prepare() }
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (stopped) return@addListener
            runCatching { bind(future.get(), chosen) }.onFailure {
                Log.w(TAG, "camera ${chosen.id} could not start", it)
                broken.value = true
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun stop() {
        if (stopped) return
        stopped = true
        sensors?.unregisterListener(gravity)
        if (Build.VERSION.SDK_INT >= 29) power?.removeThermalStatusListener(heat as PowerManager.OnThermalStatusChangedListener)
        analysis?.let {
            it.clearAnalyzer()
            runCatching { provider?.unbind(it) }
        }
        registry.currentState = Lifecycle.State.DESTROYED
        executor.execute {
            runCatching { landmarker?.close() }
            landmarker = null
            hands?.close()
            hands = null
            shm?.close()
            shm = null
            recorder?.close()
            recorder = null
        }
        executor.shutdown()
    }

    private fun bind(cameras: ProcessCameraProvider, chosen: TrackingCamera) {
        val selector = CameraSelector.Builder()
            .addCameraFilter { infos -> infos.filter { Camera2CameraInfo.from(it).cameraId == chosen.id } }
            .build()
        val builder = ImageAnalysis.Builder()
        val extender = Camera2Interop.Extender(builder)
        chosen.fps?.let { extender.setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(it.first, it.second)) }
        recorder?.let { extender.setSessionCaptureCallback(it.callback) }
        val use = builder
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(ResolutionStrategy(Size(ANALYSIS_WIDTH, ANALYSIS_HEIGHT), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                    .build(),
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
        use.setAnalyzer(executor, ::analyze)
        val bound = cameras.bindToLifecycle(this, selector, use)
        if (zoom < 1f) bound.cameraControl.setZoomRatio(zoom)
        provider = cameras
        analysis = use
        registry.currentState = Lifecycle.State.RESUMED
        Log.i(TAG, "camera ${chosen.id} (${if (chosen.front) "front" else "rear"}, ${chosen.fovDegrees.toInt()}°, zoom $zoom, ${chosen.fps} fps) " +
            if (preview) "checking placement" else "tracking hands")
    }

    private fun prepare() {
        if (stopped) return
        runCatching {
            check(TrackingPack.loadNative(context)) { "hand tracking download is not installed" }
            val model = TrackingPack.model(context, TrackingPack.POSE)
            val options = PoseLandmarker.PoseLandmarkerOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetBuffer(model).setDelegate(Delegate.CPU).build())
                .setRunningMode(RunningMode.VIDEO)
                .setNumPoses(1)
                .setMinPoseDetectionConfidence(MIN_CONFIDENCE)
                .setMinPosePresenceConfidence(MIN_CONFIDENCE)
                .setOutputSegmentationMasks(false)
                .build()
            landmarker = PoseLandmarker.createFromOptions(context, options)
            if (output != null) shm = HandCameraShm.open(output)
        }.onFailure {
            Log.w(TAG, "pose model could not load", it)
            broken.value = true
        }
        if (landmarker != null) hands = runCatching { HandModel.open(context) }.onFailure { Log.w(TAG, "hand model could not load", it) }.getOrNull()
    }

    private fun analyze(image: ImageProxy) {
        try {
            val chosen = camera ?: return
            val pose = landmarker ?: return
            val arrival = System.nanoTime()
            if (stopped || arrival - lastRun < interval()) {
                skipped++
                return
            }
            lastRun = arrival
            val exposed = exposure(image.imageInfo.timestamp, arrival, chosen)
            val stampMs = maxOf(exposed / 1_000_000L, lastStampMs + 1)
            lastStampMs = stampMs
            val gravity = up
            val placement = if (gravity != null) CameraGeometry.placement(chosen.front, gravity) else Placement.UNKNOWN
            val turns = if (gravity != null) CameraGeometry.uprightTurns(chosen.front, chosen.orientation, gravity)
            else image.imageInfo.rotationDegrees / 90
            val frame = upright(raw(image), turns)
            val result = pose.detectForVideo(BitmapImageBuilder(frame).build(), stampMs)
            val inferred = System.nanoTime()
            val found = result.landmarks().firstOrNull()
            val marks = found?.map { Landmark(it.x(), it.y(), it.visibility().orElse(0f)) }
            val world = result.worldLandmarks().firstOrNull()?.map { WorldPoint(it.x(), it.y(), it.z()) }
            val k = chosen.intrinsics(image.width, image.height, zoom, turns)
            val model = hands
            val fixes = if (marks != null && model != null) model.find(frame, marks, k, stampMs) else null
            val handsDone = System.nanoTime()
            fixes?.forEachIndexed { side, fix ->
                if (fix != null) {
                    heldDepth[side] = fix.palm.z
                    heldAt[side] = exposed
                }
            }
            val held = FloatArray(2) { if (exposed - heldAt[it] <= HOLD_NS) heldDepth[it] else Float.NaN }
            val body = if (marks != null && world != null && gravity != null) {
                BodyPose.solve(marks, world, k, CameraLevel(CameraGeometry.upInCamera(chosen.front, chosen.orientation, turns, gravity)),
                    if (model != null) fixes ?: arrayOfNulls(2) else null, held)
            } else null
            shm?.write(exposed, body, bareHands)
            if (preview) state.value = View(frame, chosen.front, marks, placement, PlacementAdvice.of(placement, body))
            recorder?.frame(HandCameraRecorder.Frame(exposed, image.imageInfo.timestamp, arrival, inferred, System.nanoTime(), skipped,
                thermal, turns, placement, gravity, k,
                found?.map { floatArrayOf(it.x(), it.y(), it.z(), it.visibility().orElse(0f), it.presence().orElse(0f)) },
                world, body, handsDone, fixes), frame)
            skipped = 0
        } catch (e: Exception) {
            Log.w(TAG, "camera frame skipped", e)
        } finally {
            image.close()
        }
    }

    private fun raw(image: ImageProxy): Bitmap {
        val plane = image.planes[0]
        if (plane.pixelStride != 4 || plane.rowStride != image.width * 4) return image.toBitmap()
        val bitmap = rawFrame?.takeIf { it.width == image.width && it.height == image.height }
            ?: Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888).also { rawFrame = it }
        plane.buffer.rewind()
        bitmap.copyPixelsFromBuffer(plane.buffer)
        return bitmap
    }

    private fun upright(raw: Bitmap, turns: Int): Bitmap {
        val quarter = Math.floorMod(turns, 4)
        if (quarter == 0) return if (preview) raw.copy(Bitmap.Config.ARGB_8888, false) else raw
        val width = if (quarter % 2 == 1) raw.height else raw.width
        val height = if (quarter % 2 == 1) raw.width else raw.height
        val target = if (preview) Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        else turnedFrame?.takeIf { it.width == width && it.height == height }
            ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { turnedFrame = it }
        val matrix = Matrix()
        matrix.postRotate(90f * quarter)
        when (quarter) {
            1 -> matrix.postTranslate(raw.height.toFloat(), 0f)
            2 -> matrix.postTranslate(raw.width.toFloat(), raw.height.toFloat())
            else -> matrix.postTranslate(0f, raw.width.toFloat())
        }
        Canvas(target).drawBitmap(raw, matrix, null)
        return target
    }

    private fun exposure(stamp: Long, arrival: Long, chosen: TrackingCamera): Long {
        val monotonic = if (chosen.realtime) stamp - (SystemClock.elapsedRealtimeNanos() - System.nanoTime()) else stamp
        return if (abs(arrival - monotonic) < MAX_DELAY_NS) monotonic else arrival - TYPICAL_DELAY_NS
    }

    private fun interval(): Long {
        val fps = when {
            thermal >= SEVERE -> HOT_FPS
            thermal >= MODERATE -> WARM_FPS
            else -> FPS
        }
        return 1_000_000_000L / fps - SLACK_NS
    }

    companion object {
        private const val TAG = "HandCamera"
        private const val ANALYSIS_WIDTH = 960
        private const val ANALYSIS_HEIGHT = 720
        private const val MIN_CONFIDENCE = 0.5f
        private const val FPS = 15
        private const val WARM_FPS = 10
        private const val HOT_FPS = 6
        private const val MODERATE = 2
        private const val SEVERE = 3
        private const val SLACK_NS = 8_000_000L
        private const val MAX_DELAY_NS = 1_000_000_000L
        private const val TYPICAL_DELAY_NS = 60_000_000L
        private const val ACCEL_SMOOTHING = 0.1f
        private const val HOLD_NS = 500_000_000L
    }
}
