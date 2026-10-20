package com.droiddeck.launcher.glasses

import com.droiddeck.launcher.core.AddonPack
import android.content.Context
import com.droiddeck.launcher.R
import com.droiddeck.launcher.handtracking.TrackingPack
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import org.opencv.imgcodecs.Imgcodecs
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.ConcurrentLinkedQueue

internal data class HandTrackingDiagnostics(val message: String, val leftCameraHands: Int = 0,
    val rightCameraHands: Int = 0, val matched: Int = 0, val reconstructed: Int = 0, val accepted: Int = 0,
    val preprocessMs: Float = 0f, val inferenceMs: Float = 0f,
    val triggerValid: Int = 0, val gripValid: Int = 0,
    val leftRawHands: Int = 0, val rightRawHands: Int = 0,
    val previousLeftRawHands: Int = -1, val previousRightRawHands: Int = -1,
    val leftInferenceMs: Float = 0f, val rightInferenceMs: Float = 0f,
    val inferenceHandLimit: Int = 2,
    val observationTimeNs: Long = 0, val observationAgeMs: Float = -1f,
    val left: HandObservationDiagnostic? = null, val right: HandObservationDiagnostic? = null,
    val observations: HandTrackingMetricSnapshot? = null) {
    fun json() = JSONObject().put("message",message).put("experimental",true).put("leftCameraHands",leftCameraHands)
        .put("rightCameraHands",rightCameraHands).put("matched",matched).put("reconstructed",reconstructed).put("accepted",accepted)
        .put("preprocessMs",preprocessMs.toDouble()).put("inferenceMs",inferenceMs.toDouble())
        .put("triggerValid",triggerValid).put("gripValid",gripValid)
        .put("leftRawHands",leftRawHands).put("rightRawHands",rightRawHands)
        .put("previousLeftRawHands",previousLeftRawHands).put("previousRightRawHands",previousRightRawHands)
        .put("leftInferenceMs",leftInferenceMs.toDouble()).put("rightInferenceMs",rightInferenceMs.toDouble())
        .put("inferenceHandLimit",inferenceHandLimit)
        .put("observationTimeNs",observationTimeNs).put("observationAgeMs",observationAgeMs.toDouble())
        .put("left",left?.json()).put("right",right?.json()).put("observations",observations?.json())
}

private fun HandObservationDiagnostic.json() = JSONObject().put("outcome",outcome.name.lowercase())
    .put("positionSource",positionSource)
    .put("reconstructedPalmLandmarks",org.json.JSONArray(reconstructedPalmLandmarks))
    .put("accepted",accepted).put("triggerEstimate",triggerEstimate.toDouble())
    .put("triggerGeometryValid",triggerGeometryValid).put("triggerValid",triggerValid)
    .put("gripEstimate",gripEstimate).put("gripGeometryValid",gripGeometryValid).put("gripValid",gripValid)

private fun HandSideMetrics.json() = JSONObject().put("observations",observations).put("accepted",accepted)
    .put("triggerValid",triggerValid).put("gripValid",gripValid)
    .put("triggerAboveClickThreshold",triggerAboveClickThreshold).put("triggerReleased",triggerReleased)
    .put("gripPressed",gripPressed).put("trackingLosses",trackingLosses)
    .put("triggerLosses",triggerLosses).put("gripLosses",gripLosses)
    .put("maximumConsecutiveRejected",maximumConsecutiveRejected)
    .put("outcomes",JSONObject().also { json -> outcomes.forEach { (outcome,count) -> json.put(outcome.name.lowercase(),count) } })

private fun HandTrackingMetricSnapshot.json() = JSONObject().put("processedPairs",processedPairs).put("inferencePairs",inferencePairs)
    .put("scope","Processed camera pairs in this tracking connection, including absent hands and setup; not detection accuracy")
    .put("left",left.json()).put("right",right.json())

internal class StereoHandEngine(private val context: Context, private val token: Long,
    private val generation: Long, private val identity: String?) : AutoCloseable {
    @Volatile private var running=true
    @Volatile private var enabled=false
    @Volatile private var debug=false
    @Volatile var preview: Bitmap? = null; private set
    @Volatile var message=context.getString(R.string.spatial_wait_cameras); private set
    @Volatile var calibrationProgress = SpatialCalibrationProgress(); private set
    @Volatile var handStatus = HandTrackingDiagnostics(context.getString(R.string.spatial_wait_hands)); private set
    val hasStableIdentity get() = identity != null
    private val commands=ConcurrentLinkedQueue<() -> Unit>()
    private var calibrator: StereoCalibrator?=null
    private var calibrationFrame = 0L
    private var calibration: StereoCalibration?=null
    val calibrationSource get() = calibration?.source ?: "none"
    val calibrationVerified get() = calibration?.verified == true
    val lensCorrection get() = calibration?.let { if(it.fisheye) "fisheye-points-only" else "pinhole-points-only" } ?: "none"
    private var capture=false
    private var record=false
    private var verify: Float?=null
    private var validationRequestedAt=0L
    @Volatile var gpu=false; private set
    @Volatile var model=HandModel.DEFAULT; private set
    @Volatile var halfRateInput=true; private set
    @Volatile var adaptiveInput=true; private set
    @Volatile var scheduledTracking=true; private set
    @Volatile var inputRatePermille=500; private set
    @Volatile var cpuParallel=true; private set
    @Volatile var benchmark: JSONObject?=null; private set
    @Volatile var inferenceBackend: String? = null; private set
    private var detectors: DetectorBank?=null
    private var fastDetectors: DetectorBank?=null
    private var rightWorker: StereoInferenceRunner?=null
    private val inferenceSchedule=HandInferenceSchedule()
    private val processingRate=HandProcessingRate()
    private val handMetrics=HandTrackingMetrics()
    private val gestures=Array(2) { HandGestures(rightHand=it==1) }
    private val joyConPositions=Array(2) { JoyConWristPosition() }
    private var joyConFlow: StereoJoyConFlow? = null
    private val profile: File get() {
        val hash=MessageDigest.getInstance("SHA-256").digest((identity ?: "session:$generation").toByteArray()).joinToString("") { "%02x".format(it) }
        return File(context.filesDir,"spatial/calibration-$hash.json")
    }
    private val captures: File get() = File(profile.parentFile,profile.nameWithoutExtension+"-captures.json")
    private fun saveCaptures() {
        try { calibrator?.save(captures,identity ?: "session:$generation",width,height) }
        catch (e: Exception) { android.util.Log.w("StereoCalibration", "Could not save captured views", e) }
    }
    fun setEnabled(on: Boolean) { enabled=on }
    fun diagnostics(on: Boolean) { debug=on }
    fun setGpu(on: Boolean) { commands.add { clearHands();closeDetectors();gpu=on } }
    fun setModel(lite: Boolean) { commands.add {
        clearHands();closeDetectors();model=if(lite) HandModel.LITE else HandModel.FULL
    } }
    fun setHalfRate(on: Boolean) { commands.add { adaptiveInput=false;halfRateInput=on;processingRate.reset() } }
    fun setAdaptiveInput(on: Boolean) { commands.add { adaptiveInput=on;processingRate.reset() } }
    fun setScheduledTracking(on: Boolean) { commands.add { clearHands();closeDetectors();scheduledTracking=on } }
    fun setCpuParallel(on: Boolean) { commands.add { clearHands();closeDetectors();cpuParallel=on } }
    fun benchmarkSavedCapture(id: String) {
        require(id.matches(Regex("[0-9]{1,20}")))
        benchmark=JSONObject().put("state","pending").put("capture",id)
        commands.add {
            try { benchmark=benchmarkCapture(id) }
            catch(e: Exception) { benchmark=JSONObject().put("state","failed").put("error",e.message) }
        }
    }
    fun startCalibration() { debug=true; commands.add {
        check(width > 0 && height > 0) { context.getString(R.string.spatial_wait_previews) }
        calibrator?.close();calibrator=StereoCalibrator(context)
        calibrationFrame = SpatialTracking.session.trackingFrameGeneration
        calibrationProgress = SpatialCalibrationProgress(collecting = true)
        saveCaptures()
        message=context.getString(R.string.spatial_collect_instructions)
    } }
    fun captureCalibration() { commands.add {
        check(calibrationProgress.collecting && calibrationFrame==SpatialTracking.session.trackingFrameGeneration) {
            context.getString(R.string.spatial_saved_views_hint)
        }
        capture=true
        calibrationProgress = calibrationProgress.copy(capturing = true, captureMessage = context.getString(R.string.spatial_capture_pending))
    } }
    fun recordDiagnostic() { commands.add { record=true } }
    fun validateCalibration(distance: Float) { commands.add {
        require(distance.isFinite() && distance in 0.15f..1.5f)
        verify=distance;validationRequestedAt=System.nanoTime()
        calibrationProgress=calibrationProgress.copy(validating=true,
            validationMessage=context.getString(R.string.spatial_validation_pending))
    } }
    private var width=0; private var height=0
    private val imageBuffer=HandImageBuffer()
    private val rightImageBuffer=HandImageBuffer()
    private val worker=Thread({ loop() },"viture-stereo-hands").apply { isDaemon=true; start() }
    fun solveCalibration() { commands.add {
        val c=calibrator ?: error(context.getString(R.string.spatial_start_first))
        saveCaptures()
        calibrationProgress = calibrationProgress.copy(solving = true, validationMessage = null)
        try {
            val fitted=c.solve(identity ?: "session:$generation",width,height)
            clearHands();closeDetectors()
            calibration?.close();calibration=fitted
            calibrationProgress = calibrationProgress.copy(fitted = true)
            if(identity!=null) fitted.save(profile)
            message=context.getString(R.string.spatial_fit_ready, fitted.error)
        } finally { calibrationProgress = calibrationProgress.copy(solving = false) }
    } }
    private fun detector(selected: HandModel=model, useGpu: Boolean=gpu, maxHands: Int=2): HandLandmarker {
        val bytes=TrackingPack.file(context,selected.file).readBytes()
        val hash=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        check(hash==selected.sha256) { context.getString(R.string.spatial_model_checksum_error) }
        return HandLandmarker.createFromOptions(context,HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetBuffer(ByteBuffer.allocateDirect(bytes.size).put(bytes).apply { rewind() })
                .setDelegate(if(useGpu) Delegate.GPU else Delegate.CPU).build())
            .setRunningMode(RunningMode.VIDEO).setNumHands(maxHands).setMinHandDetectionConfidence(0.6f)
            .setMinHandPresenceConfidence(0.6f).setMinTrackingConfidence(0.6f).build())
    }
    private data class Detection(val side: Int,val points: List<Point>)
    private data class Detections(val hands: List<Detection>,val rawCount: Int,val inferenceMs: Float)
    private class DetectorBank(val left: HandLandmarker, val right: HandLandmarker, val maxHands: Int) {
        var previousLeftRaw=-1
        var previousRightRaw=-1
    }
    private fun createBank(selected: HandModel, useGpu: Boolean, maxHands: Int,
                           worker: StereoInferenceRunner?): DetectorBank {
        val left=detector(selected,useGpu,maxHands)
        try {
            val right=worker?.onRight { detector(selected,useGpu,maxHands) } ?: detector(selected,useGpu,maxHands)
            return DetectorBank(left,right,maxHands)
        } catch(e: Throwable) { left.close();throw e }
    }
    private fun closeBank(bank: DetectorBank?, worker: StereoInferenceRunner?) {
        if(bank==null) return
        try { bank.left.close() } finally {
            if(worker!=null) worker.onRight { bank.right.close() } else bank.right.close()
        }
    }
    private fun imagePoints(detection: Detection?) = detection?.points?.map {
        SpatialVector(it.x.toFloat(),it.y.toFloat(),0f)
    }
    private fun detect(detector: HandLandmarker, bitmap: Bitmap, stamp: Long, buffer: HandImageBuffer,
                       wristOnly: Boolean): Detections =
        detect(detector,stamp,wristOnly) { buffer.image(bitmap) }
    private fun detect(detector: HandLandmarker, stamp: Long, wristOnly: Boolean,
                       prepareImage: () -> MPImage): Detections {
        val image=prepareImage()
        try {
            val start=System.nanoTime()
            val result=detector.detectForVideo(image,stamp)
            val elapsed=(System.nanoTime()-start)/1e6f
            val hands=result.landmarks().mapIndexedNotNull { i, hand ->
                val category=result.handedness()[i].firstOrNull() ?: return@mapIndexedNotNull null
                if(category.score()<0.6f) return@mapIndexedNotNull null
                val points=if(wristOnly) hand.take(1) else hand
                Detection(if(category.categoryName()=="Left") 0 else 1,points.map { Point(it.x()*image.width.toDouble(),it.y()*image.height.toDouble()) })
            }
            return Detections(hands,result.landmarks().size,elapsed)
        } finally { image.close() }
    }
    private fun closeDetectors() {
        inferenceBackend=null
        inferenceSchedule.reset();processingRate.reset()
        val search=detectors;val fast=fastDetectors;val worker=rightWorker
        detectors=null;fastDetectors=null;rightWorker=null
        try { closeBank(search,worker) } finally {
            try { closeBank(fast,worker) } finally { worker?.close() }
        }
    }
    private fun detectPair(left: HandLandmarker, right: HandLandmarker, leftImage: Bitmap,
                           rightImage: Bitmap, stamp: Long, worker: StereoInferenceRunner?, wristOnly: Boolean): Pair<Detections,Detections> {
        val l={ detect(left,leftImage,stamp,imageBuffer,wristOnly) };val r={ detect(right,rightImage,stamp,rightImageBuffer,wristOnly) }
        return worker?.pair(l,r) ?: (l() to r())
    }
    private fun detectRawPair(left: HandLandmarker, right: HandLandmarker, leftGray: ByteArray,
                              rightGray: ByteArray, stamp: Long, worker: StereoInferenceRunner?, wristOnly: Boolean): Pair<Detections,Detections> {
        val l={ detect(left,stamp,wristOnly) { imageBuffer.image(leftGray,width,height) } }
        val r={ detect(right,stamp,wristOnly) { rightImageBuffer.image(rightGray,width,height) } }
        return worker?.pair(l,r) ?: (l() to r())
    }
    private fun benchmarkCapture(id: String): JSONObject {
        val folder=File(context.filesDir,"spatial/captures/$id")
        val c=calibration ?: error("Load the saved calibration before benchmarking")
        val wristOnly=SpatialTracking.joyConsEnabled
        val gray=ArrayList<Mat>();val images=ArrayList<Bitmap>();val rows=org.json.JSONArray()
        clearHands();closeDetectors()
        try {
            for(name in listOf("left","right")) {
                val source=Imgcodecs.imread(File(folder,"$name.png").path,Imgcodecs.IMREAD_GRAYSCALE)
                gray.add(source)
                check(source.cols()==width && source.rows()==height) { "Saved pair has incompatible dimensions" }
                val rgba=Mat()
                try {
                    Imgproc.cvtColor(source,rgba,Imgproc.COLOR_GRAY2RGBA)
                    val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
                    images.add(bitmap);Utils.matToBitmap(rgba,bitmap)
                } finally { rgba.release() }
            }
            for((selected,parallel,scheduled) in listOf(Triple(HandModel.FULL,true,false),Triple(HandModel.FULL,true,true),
                Triple(HandModel.LITE,true,true),Triple(HandModel.FULL,true,false))) {
                var search: DetectorBank?=null;var fast: DetectorBank?=null
                val schedule=HandInferenceSchedule()
                val worker=if(parallel) StereoInferenceRunner() else null
                val times=ArrayList<Double>();val samples=org.json.JSONArray()
                try {
                    search=createBank(selected,false,2,worker)
                    if(scheduled) fast=createBank(selected,false,1,worker)
                    repeat(24) { frame ->
                        check(running) { "Tracking stopped during benchmark" }
                        val start=System.nanoTime();val stamp=1000L+frame*80L
                        val bank=if(scheduled && schedule.select(stamp)==1) fast!! else search
                        val (leftResult,rightResult)=detectPair(bank.left,bank.right,images[0],images[1],stamp,worker,wristOnly)
                        val l=leftResult.hands;val r=rightResult.hands
                        if(scheduled) schedule.observe(stamp,l.map { it.side },r.map { it.side },leftResult.rawCount,rightResult.rawCount)
                        val ms=(System.nanoTime()-start)/1e6
                        val hands=org.json.JSONArray()
                        val joyCons=org.json.JSONArray()
                        val wrists=org.json.JSONArray()
                        for(side in 0..1) {
                            val a=l.singleOrNull { it.side==side };val b=r.singleOrNull { it.side==side }
                            val points=if(a!=null && b!=null) c.triangulateLandmarksRaw(a.points,b.points) else null
                            points?.get(0)?.takeIf { it.finite() }?.let { wrist ->
                                wrists.put(JSONObject().put("side",side).put("position",org.json.JSONArray(wrist.array().toList())))
                            }
                            val hand=if(wristOnly) null else points?.let { HandGestures(rightHand=side==1).estimate(it,SpatialPose(),stamp*1_000_000,imagePoints(a),imagePoints(b)) }
                            val joyCon=if(wristOnly) points?.let { JoyConWristPosition().estimate(it,SpatialPose(),stamp*1_000_000) } else null
                            if(joyCon!=null) joyCons.put(JSONObject().put("side",side)
                                .put("position",org.json.JSONArray(joyCon.pose.position.array().toList())))
                            if(hand!=null) hands.put(JSONObject().put("side",side).put("flags",hand.flags())
                                .put("position",org.json.JSONArray(hand.pose.position.array().toList()))
                                .put("trigger",hand.trigger.toDouble()).put("grip",hand.grip))
                        }
                        if(frame>=4) {
                            times.add(ms);samples.put(JSONObject().put("left",l.size).put("right",r.size).put("hands",hands)
                                .put("joyCons",joyCons).put("wrists",wrists)
                                .put("inferenceHandLimit",bank.maxHands)
                                .put("leftRawHands",leftResult.rawCount).put("rightRawHands",rightResult.rawCount)
                                .put("leftInferenceMs",leftResult.inferenceMs.toDouble()).put("rightInferenceMs",rightResult.inferenceMs.toDouble()))
                        }
                    }
                } finally {
                    try { closeBank(search,worker) } finally {
                        try { closeBank(fast,worker) } finally { worker?.close() }
                    }
                }
                val sorted=times.sorted()
                rows.put(JSONObject().put("model",selected.name).put("parallel",parallel).put("medianMs",(sorted[9]+sorted[10])/2)
                    .put("scheduled",scheduled)
                    .put("p95Ms",sorted[18]).put("samples",samples))
            }
            return JSONObject().put("state","completed").put("capture",id).put("backend","CPU")
                .put("wristOnly",wristOnly).put("imagePreprocessing",HandImageBuffer.PREPROCESSING)
                .put("lensCorrection",lensCorrection)
                .put("scope","Saved stereo pair replay; not live tracking or external latency").put("results",rows)
        } finally { gray.forEach { it.release() };images.forEach { it.recycle() };clearHands() }
    }
    private fun clearHands() {
        gestures.forEach { it.reset() }
        joyConPositions.forEach { it.reset() }
        joyConFlow?.reset()
        inferenceSchedule.reset();processingRate.reset()
        SpatialTracking.session.setHands(generation,SpatialHand(),SpatialHand())
    }
    private fun loop() {
        val loaded=TrackingPack.awaitNative(context,{ running }) { state ->
            if(state is AddonPack.State.Downloading)
                message=context.getString(R.string.tracking_pack_downloading,(state.progress*100).toInt())
        }
        if(!loaded) { if(running) message=context.getString(R.string.tracking_pack_failed);return }
        var grayL=Mat();var grayR=Mat();val rgba=Mat()
        var bmpL: Bitmap?=null;var bmpR: Bitmap?=null
        var leftGray=ByteArray(0);var rightGray=ByteArray(0)
        val left=ByteBuffer.allocateDirect(2048*2048);val right=ByteBuffer.allocateDirect(2048*2048)
        val metadata=DoubleArray(9);val headData=FloatArray(7)
        var lastSeq=0L;var dropped=0L;var frames=0L;var previewTime=0L
        val clock=CameraClock()
        var lastSource=0L;var lastStamp=0L;var captureOn=false;var captureRate=1000;var lastInputSeq=0L
        var rate=0f
        try {
            check(TrackingPack.loadNative(context)) { context.getString(R.string.spatial_opencv_load_error) }
            joyConFlow=StereoJoyConFlow()
            while(running) {
                while(true) {
                    val command=commands.poll() ?: break
                    try { command() } catch(e: Exception) { message=e.message ?: e.toString() }
                }
                if (verify!=null && System.nanoTime()-validationRequestedAt>2_000_000_000L) {
                    verify=null
                    calibrationProgress=calibrationProgress.copy(validating=false,
                        validationMessage=context.getString(R.string.spatial_no_camera_frame))
                }
                val want=enabled || debug
                val copyPermille=if(enabled && !calibrationProgress.collecting && !capture && verify==null && !record)
                    if(adaptiveInput && !gpu) processingRate.permille else if(halfRateInput) 500 else 1000
                    else 1000
                inputRatePermille=copyPermille
                if(want!=captureOn || copyPermille!=captureRate) {
                    VitureSdk.nativeCapture(token,want,copyPermille);captureOn=want;captureRate=copyPermille
                    if(!want) clearHands()
                }
                if(!want) { Thread.sleep(20);continue }
                val seq=VitureSdk.nativeStereo(token,left,right,metadata,headData,!enabled || capture)
                if(seq<=0) { Thread.sleep(5);continue }
                val sequenceDelta=if(lastSeq>0) (seq-lastSeq).coerceAtLeast(1) else 1
                if(lastSeq>0) dropped+=(sequenceDelta-1)
                lastSeq=seq;frames++
                val start=System.nanoTime();val source=(metadata[2]*1e9).toLong();val receipt=metadata[3].toLong()
                val inputSequence=metadata[7].toLong()
                if(source<=lastSource) { clearHands();message=context.getString(R.string.spatial_clock_reset);closeDetectors();lastStamp=0 }
                if(lastSource>0 && source>lastSource) {
                    val hz=1e9f*(inputSequence-lastInputSeq).coerceAtLeast(1)/(source-lastSource)
                    rate=if(rate==0f)hz else rate*.9f+hz*.1f
                }
                lastSource=source;lastInputSeq=inputSequence
                val time=clock.map(source,receipt) ?: 0L
                val trackingFrame = SpatialTracking.session.trackingFrameGeneration
                if (calibrator != null && calibrationProgress.collecting && calibrationFrame != trackingFrame) {
                    capture=false
                    message=context.getString(R.string.spatial_frame_changed)
                    calibrationProgress = calibrationProgress.copy(collecting = false, capturing = false, captureMessage = message)
                }
                // The callback pose's basis is undocumented; use canonical GL pose history.
                val observation=HandObservation(time,generation,trackingFrame)
                var captureHead=observation.headPose(SpatialTracking.session,metadata[6]>0)
                val w=metadata[0].toInt();val h=metadata[1].toInt()
                if(w!=width || h!=height) {
                    clearHands();closeDetectors()
                    width=w;height=h;leftGray=ByteArray(w*h);rightGray=ByteArray(w*h)
                    grayL.release();grayR.release();grayL=Mat(h,w,CvType.CV_8UC1);grayR=Mat(h,w,CvType.CV_8UC1)
                    bmpL?.recycle();bmpR?.recycle();bmpL=null;bmpR=null
                    calibration?.close()
                    val selected=StereoCalibrationProfile.select(
                        if(identity!=null) runCatching { profile.readText() }.getOrNull() else null,
                        { runCatching { context.assets.open(StereoCalibrationProfile.BASE_ASSET).bufferedReader().use { it.readText() } }.getOrNull() },
                        identity,w,h)
                    calibration=selected?.let(StereoCalibration::fromProfile)
                    calibrator?.close();calibrator=null
                    calibrationProgress = SpatialCalibrationProgress(fitted = calibration != null)
                    message=context.getString(R.string.spatial_stereo_loaded, w, h, when {
                        calibration?.verified==true -> context.getString(R.string.spatial_device_loaded)
                        calibration?.bundledBase==true -> context.getString(R.string.spatial_base_loaded)
                        else -> context.getString(R.string.spatial_calibration_needed)
                    })
                    if (identity!=null) calibrator=StereoCalibrator.load(context,captures,identity,w,h)
                    calibrator?.let {
                        calibrationFrame=-1
                        val restored=context.getString(R.string.spatial_restored, it.observations.size)
                        calibrationProgress=calibrationProgress.copy(views=it.observations.size,captureMessage=restored)
                        message=restored
                    }
                }
                left.position(0);left.get(leftGray)
                right.position(0);right.get(rightGray)
                val joyConMode=SpatialTracking.joyConsEnabled
                val drawPreview=debug && start-previewTime>150_000_000L
                if(capture || verify!=null || record || drawPreview || joyConMode) {
                    grayL.put(0,0,leftGray);grayR.put(0,0,rightGray)
                }
                if(capture) {
                    capture=false
                    val observation=captureHead?.let { calibrator?.detect(grayL,grayR,it) }
                    val currentTracking = SpatialTracking.session.isCurrentTrackingFrame(generation, trackingFrame)
                    if(observation!=null && currentTracking) {
                        calibrator!!.observations.add(observation)
                        calibrationProgress = calibrationProgress.copy(views = calibrator!!.observations.size)
                        saveCaptures()
                        message=context.getString(R.string.spatial_captured, calibrator!!.observations.size)
                    } else {
                        observation?.let { listOf(it.objects,it.left,it.right).forEach(Mat::release) }
                        message = when {
                            calibrator == null -> context.getString(R.string.spatial_tap_start)
                            SpatialTracking.session.recenterPending -> context.getString(R.string.spatial_capture_recenter_pending)
                            SpatialTracking.session.needsRecenter -> context.getString(R.string.spatial_capture_recenter)
                            captureHead == null || !currentTracking -> context.getString(R.string.spatial_capture_unsynchronized)
                            else -> context.getString(R.string.spatial_capture_corners, calibrator?.commonCorners ?: 0)
                        }
                    }
                    calibrationProgress = calibrationProgress.copy(capturing = false, captureMessage = message)
                }
                val c=calibration
                val distance=verify
                if(distance!=null) {
                    verify=null
                    var temporary: StereoCalibrator?=null
                    var observed: BoardObservation?=null
                    try {
                        check(c!=null) { context.getString(R.string.spatial_align_first) }
                        temporary=StereoCalibrator(context)
                        observed=temporary.detect(grayL,grayR,SpatialPose())
                        val points=observed?.let { c.triangulateRaw(it.left,it.right) }
                        val result=CalibrationDistanceCheck.evaluate(context,distance,temporary.commonCorners,points)
                        if(result.accepted) {
                            c.verified=true;if(identity!=null)c.save(profile)
                            calibrationProgress=calibrationProgress.copy(validationMessage=result.message+
                                if(identity==null) " " + context.getString(R.string.spatial_validation_session) else " " + context.getString(R.string.spatial_validation_saved))
                        } else calibrationProgress=calibrationProgress.copy(validationMessage=result.message)
                    } catch (e: Exception) {
                        android.util.Log.w("StereoCalibration","Distance validation failed",e)
                        calibrationProgress=calibrationProgress.copy(validationMessage=context.getString(R.string.spatial_validation_error, e.message?.substringBefore('\n') ?: context.getString(R.string.spatial_processing_failed)))
                    } finally {
                        calibrationProgress=calibrationProgress.copy(validating=false)
                        observed?.let { listOf(it.objects,it.left,it.right).forEach(Mat::release) };temporary?.close()
                    }
                }
                var dl=emptyList<Detection>();var dr=emptyList<Detection>()
                var trackingHealthy=false
                var inferred=false
                val sideDiagnostics=Array(2) { HandObservationDiagnostic(HandObservationOutcome.NO_MATCHING_DETECTION) }
                val nextStatus: HandTrackingDiagnostics
                if(enabled && c?.readyForTracking==true && time>0 && SpatialTracking.session.isCurrentTrackingFrame(generation, trackingFrame) && System.nanoTime()-time in 0..HandObservation.MAX_AGE_NS) {
                    val preprocessMs=(System.nanoTime()-start)/1e6f
                    if(detectors==null) {
                        try {
                            rightWorker=if(cpuParallel && !gpu) StereoInferenceRunner() else null
                            detectors=createBank(model,gpu,2,rightWorker)
                            if(scheduledTracking && !gpu) fastDetectors=createBank(model,false,1,rightWorker)
                            inferenceBackend=if(gpu) "GPU" else "CPU"
                        }
                        catch(e: Exception) {
                            closeDetectors()
                            enabled=false;clearHands();message=context.getString(R.string.spatial_inference_error, e.message.orEmpty())
                            val unavailable=HandObservationDiagnostic(HandObservationOutcome.INFERENCE_UNAVAILABLE)
                            handMetrics.observe(seq,false,unavailable,unavailable)
                            handStatus=HandTrackingDiagnostics(message,observationTimeNs=time,
                                observationAgeMs=(System.nanoTime()-time)/1e6f,left=unavailable,right=unavailable,observations=handMetrics.snapshot())
                            continue
                        }
                    }
                    val stamp=maxOf(time/1_000_000,lastStamp+1);lastStamp=stamp
                    val inferenceStart=System.nanoTime()
                    val scheduled=scheduledTracking && !gpu
                    val bank=if(scheduled && inferenceSchedule.select(stamp)==1) fastDetectors!! else detectors!!
                    val pair=detectRawPair(bank.left,bank.right,leftGray,rightGray,stamp,rightWorker,joyConMode)
                    inferred=true
                    dl=pair.first.hands;dr=pair.second.hands
                    if(scheduled) inferenceSchedule.observe(stamp,dl.map { it.side },dr.map { it.side },pair.first.rawCount,pair.second.rawCount)
                    val inferenceMs=(System.nanoTime()-inferenceStart)/1e6f
                    // VIO callbacks can arrive during inference; verify this exact camera stamp before publishing.
                    captureHead=observation.headPose(SpatialTracking.session,
                        metadata[6]>0 || VitureSdk.nativeCameraSynchronized(token,metadata[2]))
                    val hands=Array(2) { SpatialHand() }
                    if (joyConMode) joyConFlow?.begin(grayL,grayR,time) else joyConFlow?.reset()
                    var matched=0;var reconstructed=0
                    for(side in 0..1) {
                        val leftCandidates=dl.filter { it.side==side };val rightCandidates=dr.filter { it.side==side }
                        val l=leftCandidates.singleOrNull();val r=rightCandidates.singleOrNull()
                        val detectedPoints=if(l!=null && r!=null) {
                            matched++
                            c.triangulateLandmarksRaw(l.points,r.points)
                        } else null
                        var points=detectedPoints
                        if(points?.any { it != null }==true) reconstructed++
                        var hand=points?.let { p -> captureHead?.let { head ->
                            if (joyConMode) joyConPositions[side].estimate(p,head*c.cameraToHead,time)
                            else gestures[side].estimate(p,head*c.cameraToHead,time,imagePoints(l),imagePoints(r))
                        } }
                        var positionSource=if(hand!=null) "landmarks" else "none"
                        if (joyConMode && captureHead!=null) {
                            if (hand!=null && l!=null && r!=null && detectedPoints!=null)
                                joyConFlow?.seed(side,l.points.first(),r.points.first(),time)
                            else if (leftCandidates.size<=1 && rightCandidates.size<=1) {
                                joyConFlow?.points(side)?.let { tracked ->
                                    val flowed=c.triangulateLandmarksRaw(tracked.left,tracked.right)
                                    val position=flowed?.let { joyConPositions[side].estimate(it,captureHead!!*c.cameraToHead,time) }
                                    if(position!=null) { hand=position;points=flowed;positionSource="stereo_flow" }
                                }
                            }
                        }
                        val outcome=when {
                            hand!=null -> HandObservationOutcome.ACCEPTED
                            leftCandidates.size>1 || rightCandidates.size>1 -> HandObservationOutcome.AMBIGUOUS_HANDEDNESS
                            l==null && r==null -> HandObservationOutcome.NO_MATCHING_DETECTION
                            l==null || r==null -> HandObservationOutcome.ONE_CAMERA_ONLY
                            points?.any { it != null }!=true -> HandObservationOutcome.STEREO_REJECTED
                            captureHead==null -> HandObservationOutcome.CAMERA_NOT_SYNCHRONIZED
                            else -> if (joyConMode) joyConPositions[side].lastOutcome else gestures[side].lastOutcome
                        }
                        sideDiagnostics[side]=HandObservationDiagnostic(outcome,
                            listOf(0,5,9,13,17).filter { points?.getOrNull(it)?.finite()==true },
                            hand?.trigger ?: 0f,hand?.triggerValid ?: false,hand?.grip ?: false,hand?.gripValid ?: false,positionSource)
                        if (hand!=null) hands[side]=hand
                        else if (joyConMode) gestures[side].reset()
                        else gestures[side].resetAfterRejection(outcome)
                    }
                    if (joyConMode) joyConFlow?.end(grayL,grayR,time)
                    val current=SpatialTracking.session.isCurrentTrackingFrame(generation, trackingFrame) &&
                        joyConMode==SpatialTracking.joyConsEnabled
                    val fresh=System.nanoTime()-time in 0..HandObservation.MAX_AGE_NS
                    if (current && fresh)
                        SpatialTracking.session.setHands(generation,hands[0],hands[1])
                    else {
                        clearHands()
                        for(side in 0..1) sideDiagnostics[side]=sideDiagnostics[side].copy(outcome=
                            if(!current) HandObservationOutcome.HEAD_TRACKING_INVALID else HandObservationOutcome.STALE_OBSERVATION)
                    }
                    val accepted=if(current && fresh) hands.count { it.valid } else 0
                    trackingHealthy=accepted>0
                    nextStatus=HandTrackingDiagnostics(when {
                        !current -> context.getString(R.string.spatial_hand_head_changed)
                        !fresh -> context.getString(R.string.spatial_hand_timeout, HandObservation.MAX_AGE_NS / 1_000_000)
                        captureHead==null -> context.getString(R.string.spatial_hand_wait_sync)
                        accepted>0 && joyConMode -> context.getString(R.string.spatial_wrists_tracked, accepted)
                        accepted>0 -> context.getString(R.string.spatial_palms_tracked, accepted, hands.count { it.valid && it.triggerValid }, hands.count { it.valid && it.gripValid })
                        dl.isEmpty() && dr.isEmpty() && joyConMode -> context.getString(R.string.spatial_no_wrists)
                        dl.isEmpty() && dr.isEmpty() -> context.getString(R.string.spatial_no_hands)
                        (dl.isEmpty() || dr.isEmpty()) && joyConMode -> context.getString(R.string.spatial_one_camera_wrists)
                        dl.isEmpty() || dr.isEmpty() -> context.getString(R.string.spatial_one_camera_hands)
                        matched==0 -> context.getString(R.string.spatial_ambiguous_hands)
                        reconstructed==0 -> context.getString(R.string.spatial_inconsistent_depth)
                        joyConMode -> context.getString(R.string.spatial_rejected_wrist)
                        else -> context.getString(R.string.spatial_inconsistent_palm)
                    },dl.size,dr.size,matched,reconstructed,accepted,preprocessMs,inferenceMs,
                        if(current && fresh) hands.count { it.valid && it.triggerValid } else 0,
                        if(current && fresh) hands.count { it.valid && it.gripValid } else 0,
                        pair.first.rawCount,pair.second.rawCount,bank.previousLeftRaw,bank.previousRightRaw,
                        pair.first.inferenceMs,pair.second.inferenceMs,bank.maxHands)
                    bank.previousLeftRaw=pair.first.rawCount;bank.previousRightRaw=pair.second.rawCount
                } else {
                    clearHands()
                    val outcome=when {
                        !enabled -> HandObservationOutcome.DISABLED
                        c?.readyForTracking!=true -> HandObservationOutcome.CALIBRATION_REQUIRED
                        time<=0 -> HandObservationOutcome.CLOCK_NOT_READY
                        !SpatialTracking.session.isCurrentTrackingFrame(generation,trackingFrame) -> HandObservationOutcome.HEAD_TRACKING_INVALID
                        else -> HandObservationOutcome.STALE_OBSERVATION
                    }
                    for(side in 0..1) sideDiagnostics[side]=HandObservationDiagnostic(outcome)
                    nextStatus=HandTrackingDiagnostics(when {
                        !enabled -> context.getString(R.string.spatial_hands_off)
                        c?.readyForTracking!=true -> context.getString(R.string.spatial_hands_need_calibration)
                        captureHead==null -> context.getString(R.string.spatial_hand_wait_sync)
                        !SpatialTracking.session.isCurrentTrackingFrame(generation,trackingFrame) -> context.getString(R.string.spatial_hands_wait_stable)
                        else -> context.getString(R.string.spatial_old_frame)
                    })
                }
                handMetrics.observe(seq,inferred,sideDiagnostics[0],sideDiagnostics[1])
                handStatus=nextStatus.copy(observationTimeNs=time,
                    observationAgeMs=if(time>0) (System.nanoTime()-time)/1e6f else -1f,
                    left=sideDiagnostics[0],right=sideDiagnostics[1],observations=handMetrics.snapshot())
                if(drawPreview) {
                    previewTime=start
                    if(bmpL==null) bmpL=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
                    if(bmpR==null) bmpR=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
                    Imgproc.cvtColor(grayL,rgba,Imgproc.COLOR_GRAY2RGBA);Utils.matToBitmap(rgba,bmpL)
                    Imgproc.cvtColor(grayR,rgba,Imgproc.COLOR_GRAY2RGBA);Utils.matToBitmap(rgba,bmpR)
                    val combined=Bitmap.createBitmap(w*2,h,Bitmap.Config.ARGB_8888);val canvas=Canvas(combined)
                    canvas.drawBitmap(bmpL!!,0f,0f,null);canvas.drawBitmap(bmpR!!,w.toFloat(),0f,null)
                    val paint=Paint().apply { color=Color.GREEN;strokeWidth=3f }
                    for((index,detections) in listOf(dl,dr).withIndex()) for(d in detections) for(p in d.points)
                        canvas.drawCircle(p.x.toFloat()+index*w,p.y.toFloat(),3f,paint)
                    preview=combined
                }
                if(record) {
                    record=false
                    val folder=File(context.filesDir,"spatial/captures/$receipt").apply { mkdirs() }
                    Imgcodecs.imwrite(File(folder,"left.png").path,grayL);Imgcodecs.imwrite(File(folder,"right.png").path,grayR)
                    File(folder,"frame.json").writeText(JSONObject().put("sourceSeconds",metadata[2]).put("receiptNs",receipt)
                        .put("connection",generation).put("width",w).put("height",h).put("pairedPose",captureHead!=null).toString())
                    message=context.getString(R.string.spatial_capture_saved, folder.path)
                }
                if(running && generation==SpatialTracking.session.generation) {
                    val finished=System.nanoTime()
                    if(adaptiveInput && !gpu) processingRate.observe(finished-start,if(time>0) finished-time else -1,trackingHealthy)
                    SpatialTracking.statistics(c?.readyForTracking==true,frames,dropped,(finished-start)/1e6f,
                        if(time>0)(finished-time)/1e6f else -1f,rate,captureHead!=null,
                        when(copyPermille) { 500 -> 2;1000 -> 1;else -> 0 },metadata[8].toLong(),copyPermille)
                    SpatialTracking.publish(System.nanoTime())
                }
            }
        } catch(e: InterruptedException) { /* Shutdown interrupts the idle wait. */ }
        catch(e: Throwable) {
            message=context.getString(R.string.spatial_hand_error, e.message.orEmpty())
            val unavailable=HandObservationDiagnostic(HandObservationOutcome.INFERENCE_UNAVAILABLE)
            handStatus=HandTrackingDiagnostics(message,left=unavailable,right=unavailable,observations=handMetrics.snapshot())
            calibrationProgress = calibrationProgress.copy(capturing = false, captureMessage = message,
                validating = false, validationMessage = if(calibrationProgress.validating) message else calibrationProgress.validationMessage)
            clearHands()
        }
        finally {
            VitureSdk.nativeCapture(token,false,1000)
            joyConFlow?.close();joyConFlow=null
            closeDetectors()
            calibration?.close();calibrator?.close()
            listOf(grayL,grayR,rgba).forEach(Mat::release);bmpL?.recycle();bmpR?.recycle()
        }
    }
    override fun close() {
        running=false;worker.interrupt()
        if(Thread.currentThread()!==worker) worker.join(2000)
    }
}
