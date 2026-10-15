package com.droiddeck.launcher.glasses

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import org.opencv.imgcodecs.Imgcodecs
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.ConcurrentLinkedQueue

internal class StereoHandEngine(private val context: Context, private val token: Long,
    private val generation: Long, private val identity: String?) : AutoCloseable {
    @Volatile private var running=true
    @Volatile private var enabled=false
    @Volatile private var debug=false
    @Volatile var preview: Bitmap? = null; private set
    @Volatile var message="Waiting for stereo cameras"; private set
    @Volatile var calibrationProgress = SpatialCalibrationProgress(); private set
    private val commands=ConcurrentLinkedQueue<() -> Unit>()
    private var calibrator: StereoCalibrator?=null
    private var calibrationFrame = 0L
    private var calibration: StereoCalibration?=null
    private var capture=false
    private var record=false
    private var verify: Float?=null
    @Volatile var gpu=false; private set
    private var leftDetector: HandLandmarker?=null
    private var rightDetector: HandLandmarker?=null
    private val gestures=Array(2) { HandGestures() }
    private val profile: File get() {
        val hash=MessageDigest.getInstance("SHA-256").digest((identity ?: "session:$generation").toByteArray()).joinToString("") { "%02x".format(it) }
        return File(context.filesDir,"spatial/calibration-$hash.json")
    }
    fun setEnabled(on: Boolean) { enabled=on }
    fun diagnostics(on: Boolean) { debug=on }
    fun setGpu(on: Boolean) { commands.add { gpu=on; leftDetector?.close();rightDetector?.close();leftDetector=null;rightDetector=null } }
    fun startCalibration() { debug=true; commands.add {
        check(width > 0 && height > 0) { "Wait for both camera previews before starting calibration." }
        calibrator?.close();calibrator=StereoCalibrator()
        calibrationFrame = SpatialTracking.session.trackingFrameGeneration
        calibrationProgress = SpatialCalibrationProgress(collecting = true)
        message="Keep the 5 × 7 board stationary. Capture 24+ views while moving and rotating the glasses."
    } }
    fun captureCalibration() { commands.add { capture=true } }
    fun recordDiagnostic() { commands.add { record=true } }
    fun validateCalibration(distance: Float) { commands.add { verify=distance } }
    private var width=0; private var height=0
    private val imageBuffer=HandImageBuffer()
    private val worker=Thread({ loop() },"viture-stereo-hands").apply { isDaemon=true; start() }
    fun solveCalibration() { commands.add {
        val c=calibrator ?: error("Start calibration first")
        calibrationProgress = calibrationProgress.copy(solving = true)
        try {
            val fitted=c.solve(identity ?: "session:$generation",width,height)
            calibration?.close();calibration=fitted
            calibrationProgress = calibrationProgress.copy(fitted = true)
            message="Fit %.2f px. Measure your selected distance from the left camera to the board center, then validate.".format(fitted.error)
        } finally { calibrationProgress = calibrationProgress.copy(solving = false) }
    } }
    private fun detector(): HandLandmarker {
        val bytes=context.assets.open("models/hand_landmarker.task").use { it.readBytes() }
        val hash=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        check(hash=="fbc2a30080c3c557093b5ddfc334698132eb341044ccee322ccf8bcf3607cde1") { "Hand model checksum mismatch" }
        return HandLandmarker.createFromOptions(context,HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath("models/hand_landmarker.task")
                .setDelegate(if(gpu) Delegate.GPU else Delegate.CPU).build())
            .setRunningMode(RunningMode.VIDEO).setNumHands(2).setMinHandDetectionConfidence(0.6f)
            .setMinHandPresenceConfidence(0.6f).setMinTrackingConfidence(0.6f).build())
    }
    private data class Detection(val side: Int,val points: List<Point>)
    private fun detect(detector: HandLandmarker, bitmap: Bitmap, stamp: Long): List<Detection> {
        val image=imageBuffer.image(bitmap)
        try {
            val result=detector.detectForVideo(image,stamp)
            return result.landmarks().mapIndexedNotNull { i, hand ->
                val category=result.handedness()[i].firstOrNull() ?: return@mapIndexedNotNull null
                if(category.score()<0.6f) return@mapIndexedNotNull null
                Detection(if(category.categoryName()=="Left") 0 else 1,hand.map { Point(it.x()*bitmap.width.toDouble(),it.y()*bitmap.height.toDouble()) })
            }
        } finally { image.close() }
    }
    private fun clearHands() {
        gestures.forEach { it.reset() }
        SpatialTracking.session.setHands(generation,SpatialHand(),SpatialHand())
    }
    private fun loop() {
        try { check(OpenCVLoader.initLocal()) } catch(e: Throwable) { message="OpenCV unavailable: ${e.message}";return }
        var grayL=Mat();var grayR=Mat();val cleanL=Mat();val cleanR=Mat();val rgba=Mat()
        var bmpL: Bitmap?=null;var bmpR: Bitmap?=null
        var bytes=ByteArray(0)
        val left=ByteBuffer.allocateDirect(2048*2048);val right=ByteBuffer.allocateDirect(2048*2048)
        val metadata=DoubleArray(7);val headData=FloatArray(7)
        var lastSeq=0L;var dropped=0L;var frames=0L;var previewTime=0L
        val clock=CameraClock()
        var lastSource=0L;var lastStamp=0L;var captureOn=false
        var rate=0f
        try {
            check(OpenCVLoader.initLocal()) { "OpenCV could not load" }
            while(running) {
                while(true) {
                    val command=commands.poll() ?: break
                    try { command() } catch(e: Exception) { message=e.message ?: e.toString() }
                }
                val want=enabled || debug
                if(want!=captureOn) { VitureSdk.nativeCapture(token,want);captureOn=want;if(!want) clearHands() }
                if(!want) { Thread.sleep(20);continue }
                val seq=VitureSdk.nativeStereo(token,left,right,metadata,headData)
                if(seq<=0) { Thread.sleep(5);continue }
                if(lastSeq>0) dropped+=(seq-lastSeq-1).coerceAtLeast(0)
                lastSeq=seq;frames++
                val start=System.nanoTime();val source=(metadata[2]*1e9).toLong();val receipt=metadata[3].toLong()
                if(source<=lastSource) { clearHands();message="Camera clock reset";leftDetector?.close();rightDetector?.close();leftDetector=null;rightDetector=null;lastStamp=0 }
                if(lastSource>0 && source>lastSource) {
                    val hz=1e9f/(source-lastSource)
                    rate=if(rate==0f)hz else rate*.9f+hz*.1f
                }
                lastSource=source
                val time=clock.map(source,receipt) ?: 0L
                val trackingFrame = SpatialTracking.session.trackingFrameGeneration
                if (calibrator != null && calibrationFrame != trackingFrame) {
                    calibrator?.close();calibrator=null;capture=false
                    calibrationProgress = SpatialCalibrationProgress(fitted = calibration != null)
                    message="Tracking frame changed. Recenter, then start a new calibration so all views share the same tracking frame."
                }
                // The callback pose's basis is undocumented; use canonical GL pose history.
                val captureHead=if(time>0 && metadata[6]>0) SpatialTracking.session.poseAt(time,generation) else null
                val w=metadata[0].toInt();val h=metadata[1].toInt()
                if(w!=width || h!=height) {
                    width=w;height=h;bytes=ByteArray(w*h)
                    grayL.release();grayR.release();grayL=Mat(h,w,CvType.CV_8UC1);grayR=Mat(h,w,CvType.CV_8UC1)
                    bmpL?.recycle();bmpR?.recycle();bmpL=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);bmpR=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
                    calibration?.close();calibration=if(identity!=null) StereoCalibration.load(profile,identity,w,h) else null
                    calibrator?.close();calibrator=null
                    calibrationProgress = SpatialCalibrationProgress(fitted = calibration != null)
                    message="Stereo ${w}×${h}; ${if(calibration?.verified==true) "calibration loaded" else "calibration required"}"
                }
                left.position(0);left.get(bytes);grayL.put(0,0,bytes)
                right.position(0);right.get(bytes);grayR.put(0,0,bytes)
                if(capture) {
                    capture=false
                    val observation=captureHead?.let { calibrator?.detect(grayL,grayR,it) }
                    if(observation!=null && SpatialTracking.session.valid(start) && trackingFrame == SpatialTracking.session.trackingFrameGeneration) {
                        calibrator!!.observations.add(observation)
                        calibrationProgress = calibrationProgress.copy(views = calibrator!!.observations.size)
                        message="Captured ${calibrator!!.observations.size} / 24 views. Keep the board fixed; move your head."
                    } else {
                        observation?.let { listOf(it.objects,it.left,it.right).forEach(Mat::release) }
                        message = when {
                            calibrator == null -> "Tap Start new calibration first."
                            SpatialTracking.session.recenterPending -> "Recenter is waiting for steady tracking. Hold still for a moment."
                            SpatialTracking.session.needsRecenter -> "Tracking changed. Tap Recenter once, then hold still before capturing."
                            captureHead == null || !SpatialTracking.session.valid(start) -> "Waiting for stable, synchronized head tracking. Face a well-lit area with visible detail."
                            else -> "Board not clear in both cameras. Keep it in view, hold still briefly, then capture again."
                        }
                    }
                }
                val c=calibration
                if(c!=null) { c.undistort(grayL,cleanL,false);c.undistort(grayR,cleanR,true) }
                else { grayL.copyTo(cleanL);grayR.copyTo(cleanR) }
                val distance=verify
                if(distance!=null) {
                    verify=null
                    val temporary=StereoCalibrator()
                    val observed=temporary.detect(cleanL,cleanR,SpatialPose())
                    try {
                        val points=if(c!=null && observed!=null) {
                            val l=MatOfPoint2f(observed.left);val r=MatOfPoint2f(observed.right)
                            try { c.triangulate(l.toList(),r.toList()) } finally { l.release();r.release() }
                        } else null
                        val center=points?.takeIf { it.size==24 }?.reduce { a,b -> a+b }?.times(1f/points.size)
                        if(c!=null && center!=null && kotlin.math.abs(center.length()-distance)<0.05f) {
                            c.verified=true;if(identity!=null)c.save(profile)
                            message="Calibration verified${if(identity==null) " for this connection only (no device serial)" else " and saved"}."
                        } else message="Distance check failed. Show the complete board with its center ${SpatialMeasurements.validation(distance)} from the left camera."
                    } finally { observed?.let { listOf(it.objects,it.left,it.right).forEach(Mat::release) };temporary.close() }
                }
                Imgproc.cvtColor(cleanL,rgba,Imgproc.COLOR_GRAY2RGBA);Utils.matToBitmap(rgba,bmpL)
                Imgproc.cvtColor(cleanR,rgba,Imgproc.COLOR_GRAY2RGBA);Utils.matToBitmap(rgba,bmpR)
                var dl=emptyList<Detection>();var dr=emptyList<Detection>()
                if(enabled && c?.verified==true && captureHead!=null && SpatialTracking.session.valid(start) && start-time in 0..100_000_000L) {
                    if(leftDetector==null) {
                        try { leftDetector=detector();rightDetector=detector() }
                        catch(e: Exception) {
                            leftDetector?.close();rightDetector?.close();leftDetector=null;rightDetector=null
                            enabled=false;clearHands();message="Inference unavailable: ${e.message}. Change delegate or toggle hand controllers to retry."
                            continue
                        }
                    }
                    val stamp=maxOf(time/1_000_000,lastStamp+1);lastStamp=stamp
                    dl=detect(leftDetector!!,bmpL!!,stamp);dr=detect(rightDetector!!,bmpR!!,stamp)
                    val hands=Array(2) { SpatialHand() }
                    for(side in 0..1) {
                        val l=dl.singleOrNull { it.side==side };val r=dr.singleOrNull { it.side==side }
                        val points=if(l!=null && r!=null)c.triangulate(l.points,r.points) else null
                        val hand=points?.let { gestures[side].estimate(it,captureHead*c.cameraToHead,time) }
                        if(hand!=null)hands[side]=hand else gestures[side].reset()
                    }
                    SpatialTracking.session.setHands(generation,hands[0],hands[1])
                } else clearHands()
                if(debug && start-previewTime>150_000_000L) {
                    previewTime=start
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
                    message="Saved diagnostic capture to ${folder.path}"
                }
                if(running && generation==SpatialTracking.session.generation) {
                    val finished=System.nanoTime()
                    SpatialTracking.statistics(c?.verified==true,frames,dropped,(finished-start)/1e6f,
                        if(time>0)(finished-time)/1e6f else -1f,rate,captureHead!=null)
                    SpatialTracking.publish(System.nanoTime())
                }
            }
        } catch(e: InterruptedException) { /* Shutdown interrupts the idle wait. */ }
        catch(e: Throwable) { message="Hand tracking stopped: ${e.message}";clearHands() }
        finally {
            VitureSdk.nativeCapture(token,false)
            leftDetector?.close();rightDetector?.close();calibration?.close();calibrator?.close()
            listOf(grayL,grayR,cleanL,cleanR,rgba).forEach(Mat::release);bmpL?.recycle();bmpR?.recycle()
        }
    }
    override fun close() {
        running=false;worker.interrupt()
        if(Thread.currentThread()!==worker) worker.join(2000)
    }
}
