package com.droiddeck.launcher.glasses

import android.content.Context
import com.droiddeck.launcher.R
import org.json.JSONArray
import org.json.JSONObject
import org.opencv.calib3d.Calib3d
import org.opencv.core.*
import org.opencv.objdetect.CharucoBoard
import org.opencv.objdetect.CharucoDetector
import org.opencv.objdetect.Objdetect
import org.opencv.imgproc.Imgproc
import java.io.File
import kotlin.math.*

internal data class BoardObservation(val objects: Mat, val left: Mat, val right: Mat, val head: SpatialPose)

internal class CalibrationAlignmentException(context: Context, val deviation: Float) : IllegalArgumentException(
    context.getString(R.string.spatial_alignment_error, SpatialMeasurements.validation(deviation)))

internal class StereoCalibration(val identity: String, val width: Int, val height: Int, val fisheye: Boolean,
    val k1: Mat, val d1: Mat, val k2: Mat, val d2: Mat, val r: Mat, val t: Mat,
    val cameraToHead: SpatialPose, val error: Double, var verified: Boolean = false,
    val bundledBase: Boolean = false) : AutoCloseable {
    val readyForTracking get() = verified || bundledBase
    val source get() = if (bundledBase && !verified) "luma-ultra-base" else "device"
    private val p1 = Mat.zeros(3, 4, CvType.CV_64F)
    private val p2 = Mat.zeros(3, 4, CvType.CV_64F)
    private val undistortionMaps = Array(2) { arrayOf(Mat(),Mat()) }
    init {
        val a = Mat.eye(3, 4, CvType.CV_64F)
        val b = Mat.zeros(3, 4, CvType.CV_64F)
        r.copyTo(b.colRange(0, 3)); t.copyTo(b.col(3))
        val empty = Mat()
        Core.gemm(k1, a, 1.0, empty, 0.0, p1); Core.gemm(k2, b, 1.0, empty, 0.0, p2)
        a.release(); b.release(); empty.release()
    }
    fun undistort(src: Mat, dst: Mat, right: Boolean) {
        val k = if (right) k2 else k1; val d = if (right) d2 else d1
        val maps=undistortionMaps[if(right) 1 else 0]
        if(maps[0].empty()) {
            val identity=Mat.eye(3,3,CvType.CV_64F)
            try {
                if(fisheye) Calib3d.fisheye_initUndistortRectifyMap(k,d,identity,k,src.size(),CvType.CV_16SC2,maps[0],maps[1])
                else Calib3d.initUndistortRectifyMap(k,d,identity,k,src.size(),CvType.CV_16SC2,maps[0],maps[1])
            } finally { identity.release() }
        }
        Imgproc.remap(src,dst,maps[0],maps[1],Imgproc.INTER_LINEAR)
    }
    fun triangulateRaw(left: List<Point>, right: List<Point>): List<SpatialVector>? {
        val points = triangulateLandmarksRaw(left,right) ?: return null
        return if(points.any { it == null }) null else points.filterNotNull()
    }
    fun triangulateLandmarksRaw(left: List<Point>, right: List<Point>): List<SpatialVector?>? {
        if (left.size != right.size || left.isEmpty()) return null
        val l=MatOfPoint2f(*left.toTypedArray()); val r=MatOfPoint2f(*right.toTypedArray())
        try { return triangulateRawPoints(l,r) } finally { l.release();r.release() }
    }
    fun triangulateRaw(left: Mat, right: Mat): List<SpatialVector>? {
        val points = triangulateRawPoints(left,right) ?: return null
        return if(points.any { it == null }) null else points.filterNotNull()
    }
    private fun triangulateRawPoints(left: Mat, right: Mat): List<SpatialVector?>? {
        val l=MatOfPoint2f(); val r=MatOfPoint2f(); val identity=Mat.eye(3,3,CvType.CV_64F)
        try {
            if (fisheye) {
                Calib3d.fisheye_undistortPoints(left,l,k1,d1,identity,k1)
                Calib3d.fisheye_undistortPoints(right,r,k2,d2,identity,k2)
            } else {
                val criteria=TermCriteria(TermCriteria.COUNT+TermCriteria.EPS,50,1e-8)
                Calib3d.undistortPointsIter(left,l,k1,d1,identity,k1,criteria)
                Calib3d.undistortPointsIter(right,r,k2,d2,identity,k2,criteria)
            }
            return triangulatePoints(l.toList(),r.toList())
        } finally { l.release(); r.release(); identity.release() }
    }
    fun triangulate(left: List<Point>, right: List<Point>): List<SpatialVector>? {
        val points = triangulatePoints(left,right) ?: return null
        return if(points.any { it == null }) null else points.filterNotNull()
    }
    private fun triangulatePoints(left: List<Point>, right: List<Point>): List<SpatialVector?>? {
        if (left.size != right.size || left.isEmpty()) return null
        val a = Mat(2, left.size, CvType.CV_64F); val b = Mat(2, right.size, CvType.CV_64F); val out = Mat()
        try {
            a.put(0, 0, *left.map { it.x }.toDoubleArray()); a.put(1, 0, *left.map { it.y }.toDoubleArray())
            b.put(0, 0, *right.map { it.x }.toDoubleArray()); b.put(1, 0, *right.map { it.y }.toDoubleArray())
            Calib3d.triangulatePoints(p1, p2, a, b, out)
            val projection1 = DoubleArray(12); val projection2 = DoubleArray(12)
            p1.get(0,0,projection1); p2.get(0,0,projection2)
            return left.indices.map { i ->
                StereoPointValidation.reconstruct(DoubleArray(4) { out.get(it,i)[0] },
                    doubleArrayOf(left[i].x,left[i].y),doubleArrayOf(right[i].x,right[i].y),projection1,projection2)
            }
        } finally { a.release(); b.release(); out.release() }
    }
    fun save(file: File) {
        fun mat(m: Mat) = JSONArray((0 until m.rows()).flatMap { row -> (0 until m.cols()).map { m.get(row,it)[0] } })
        val j = JSONObject().put("version",1).put("identity",identity).put("width",width).put("height",height)
            .put("fisheye",fisheye).put("error",error).put("verified",verified)
        for ((name,value) in listOf("k1" to k1,"d1" to d1,"k2" to k2,"d2" to d2,"r" to r,"t" to t))
            j.put(name, JSONObject().put("rows",value.rows()).put("cols",value.cols()).put("data",mat(value)))
        val p = cameraToHead.position; val q = cameraToHead.rotation
        j.put("cameraToHead",JSONArray(listOf(p.x,p.y,p.z,q.w,q.x,q.y,q.z)))
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile,file.name+".tmp"); tmp.writeText(j.toString())
        java.nio.file.Files.move(tmp.toPath(),file.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }
    override fun close() { (listOf(k1,d1,k2,d2,r,t,p1,p2)+undistortionMaps.flatMap { it.toList() }).forEach { it.release() } }
    companion object {
        fun load(file: File, identity: String, width: Int, height: Int): StereoCalibration? =
            runCatching { StereoCalibrationProfile.parse(file.readText(), identity, width, height)?.let(::fromProfile) }.getOrNull()

        fun fromProfile(profile: StereoCalibrationProfile): StereoCalibration {
            val matrices = mutableListOf<Mat>()
            try {
                profile.matrices.forEach { data ->
                    val matrix = Mat(data.rows, data.columns, CvType.CV_64F)
                    matrices.add(matrix)
                    matrix.put(0, 0, *data.values)
                }
                return StereoCalibration(profile.identity, profile.width, profile.height, profile.fisheye,
                    matrices[0], matrices[1], matrices[2], matrices[3], matrices[4], matrices[5],
                    profile.cameraToHead, profile.error, profile.verified, profile.bundledBase)
            } catch (e: Exception) {
                matrices.forEach(Mat::release)
                throw e
            }
        }
    }
}

internal class StereoCalibrator(private val context: Context) : AutoCloseable {
    val board = CharucoBoard(Size(5.0,7.0),0.03f,0.022f,Objdetect.getPredefinedDictionary(Objdetect.DICT_4X4_50))
    private val detector = CharucoDetector(board)
    val observations = mutableListOf<BoardObservation>()
    var commonCorners: Int = 0; private set
    fun detect(left: Mat, right: Mat, head: SpatialPose): BoardObservation? {
        val lc=Mat(); val li=Mat(); val rc=Mat(); val ri=Mat()
        try {
            detector.detectBoard(left,lc,li); detector.detectBoard(right,rc,ri)
            val l=(0 until li.rows()).associate { li.get(it,0)[0].toInt() to lc.get(it,0) }
            val r=(0 until ri.rows()).associate { ri.get(it,0)[0].toInt() to rc.get(it,0) }
            val ids=l.keys.intersect(r.keys).sorted()
            commonCorners = ids.size
            if (ids.size<12) return null
            val objects=MatOfPoint3f(*ids.map { Point3((it%4+1)*0.03,(it/4+1)*0.03,0.0) }.toTypedArray())
            return BoardObservation(objects,MatOfPoint2f(*ids.map { Point(l[it]!![0],l[it]!![1]) }.toTypedArray()),
                MatOfPoint2f(*ids.map { Point(r[it]!![0],r[it]!![1]) }.toTypedArray()),head)
        } finally { listOf(lc,li,rc,ri).forEach { it.release() } }
    }
    fun solve(identity: String,width: Int,height: Int): StereoCalibration {
        require(observations.size>=24) { context.getString(R.string.spatial_need_views) }
        val initial=observations.first().head.rotation
        val axes=observations.map { initial.inverse()*it.head.rotation }.map { SpatialVector(it.x,it.y,it.z) }
        require(axes.any { a -> axes.any { b -> a.cross(b).length()>0.02f } }) { context.getString(R.string.spatial_need_rotation) }
        val training=observations.filterIndexed { i,_ -> i%5!=0 }; val held=observations.filterIndexed { i,_ -> i%5==0 }
        val failures = mutableListOf<Pair<String, Exception>>()
        val candidates=listOf(false,true).mapNotNull { fish ->
            try { fit(identity,width,height,fish,training,held) }
            catch (e: Exception) {
                val model = if (fish) "Fisheye" else "Pinhole"
                android.util.Log.w("StereoCalibration", "$model calibration failed", e)
                failures.add(model to e)
                null
            }
        }
        val best=candidates.minByOrNull { it.error } ?: run {
            val alignment=failures.map { it.second }.filterIsInstance<CalibrationAlignmentException>().minByOrNull { it.deviation }
            val reason=alignment?.message ?: failures.joinToString(" ") { (model,e) ->
                "$model: ${e.message?.substringBefore('\n') ?: e.javaClass.simpleName}"
            }
            error(context.getString(R.string.spatial_calibration_error, reason))
        }
        candidates.filter { it !== best }.forEach { it.close() }
        if(best.error>2.0) { best.close(); error(context.getString(R.string.spatial_reprojection_error, best.error)) }
        return best
    }
    private fun fit(identity: String,w: Int,h: Int,fish: Boolean,train: List<BoardObservation>,held: List<BoardObservation>): StereoCalibration {
        val k1=Mat.eye(3,3,CvType.CV_64F); val k2=Mat.eye(3,3,CvType.CV_64F)
        val d1=Mat.zeros(if(fish) 4 else 5,1,CvType.CV_64F); val d2=d1.clone()
        val r=Mat(); val t=Mat(); val objects=train.map { it.objects }; val size=Size(w.toDouble(),h.toDouble())
        fun intrinsic(k: Mat,d: Mat,images: List<Mat>) {
            val rv=mutableListOf<Mat>(); val tv=mutableListOf<Mat>()
            try {
                if(fish) Calib3d.fisheye_calibrate(objects,images,size,k,d,rv,tv,Calib3d.fisheye_CALIB_RECOMPUTE_EXTRINSIC or Calib3d.fisheye_CALIB_FIX_SKEW)
                else Calib3d.calibrateCamera(objects,images,size,k,d,rv,tv)
            } finally { (rv+tv).forEach { it.release() } }
        }
        try {
            intrinsic(k1,d1,train.map { it.left }); intrinsic(k2,d2,train.map { it.right })
            stereoGeometry(objects,train.map{it.left},train.map{it.right},k1,d1,k2,d2,size,fish,r,t)
            require(Core.norm(t) in 0.02..0.3) { context.getString(R.string.spatial_bad_baseline) }
            val hr=mutableListOf<Mat>(); val ht=mutableListOf<Mat>(); val br=mutableListOf<Mat>(); val bt=mutableListOf<Mat>()
            val camR=Mat(); val camT=Mat()
            try {
                for(o in train) {
                    val rv=Mat(); val tv=Mat(); val rotation=Mat()
                    boardPose(o.objects,o.left,k1,d1,fish,rv,tv)
                    Calib3d.Rodrigues(rv,rotation); rv.release(); br.add(rotation); bt.add(tv)
                    hr.add(matrix(o.head.rotation)); ht.add(vector(o.head.position))
                }
                Calib3d.calibrateHandEye(hr,ht,br,bt,camR,camT,Calib3d.CALIB_HAND_EYE_PARK)
                require(Core.checkRange(camR) && Core.checkRange(camT) && Core.norm(camT)<0.3) { context.getString(R.string.spatial_need_orientations) }
                val cameraToHead=pose(camR,camT)
                require(abs(Core.determinant(camR)-1.0)<0.01) { context.getString(R.string.spatial_bad_rotation) }
                val boardWorld=held.map { o ->
                    val rv=Mat();val tv=Mat();val rotation=Mat()
                    try {
                        boardPose(o.objects,o.left,k1,d1,fish,rv,tv);Calib3d.Rodrigues(rv,rotation)
                        o.head*cameraToHead*pose(rotation,tv)
                    } finally { rv.release();tv.release();rotation.release() }
                }
                val center=boardWorld.map { it.position }.reduce { a,b -> a+b }*(1f/boardWorld.size)
                val drift = boardWorld.maxOf { (it.position-center).length() }
                if (drift>=0.05f) throw CalibrationAlignmentException(context,drift)
                val orientation=boardWorld.first().rotation
                require(boardWorld.all { val q=orientation.inverse()*it.rotation;abs(q.w)>cos(Math.toRadians(8.0)/2) }) { context.getString(R.string.spatial_bad_alignment) }
                val error=held.map { reprojection(it,k1,d1,k2,d2,r,t,fish) }.average()
                require(error.isFinite())
                return StereoCalibration(identity,w,h,fish,k1,d1,k2,d2,r,t,cameraToHead,error)
            } finally { (hr+ht+br+bt+listOf(camR,camT)).forEach { it.release() } }
        } catch(e: Exception) { listOf(k1,k2,d1,d2,r,t).forEach { it.release() }; throw e }
    }
    private fun boardPose(objects: Mat,pixels: Mat,k: Mat,d: Mat,fish: Boolean,rv: Mat,tv: Mat) {
        val ok=if(fish) Calib3d.fisheye_solvePnP(objects,pixels,k,d,rv,tv)
            else {
                val obj=MatOfPoint3f(objects); val img=MatOfPoint2f(pixels); val dist=MatOfDouble(d)
                try { Calib3d.solvePnP(obj,img,k,dist,rv,tv) } finally { obj.release(); img.release(); dist.release() }
            }
        require(ok)
    }
    private fun reprojection(o: BoardObservation,k1: Mat,d1: Mat,k2: Mat,d2: Mat,r: Mat,t: Mat,fish: Boolean): Double {
        val rv=Mat(); val tv=Mat(); val rot=Mat(); val rightR=Mat(); val rightT=Mat(); val rightV=Mat(); val empty=Mat()
        val predicted=MatOfPoint2f(); val objectPoints=MatOfPoint3f(o.objects); val dist=MatOfDouble(d2)
        try {
            boardPose(o.objects,o.left,k1,d1,fish,rv,tv); Calib3d.Rodrigues(rv,rot)
            Core.gemm(r,rot,1.0,empty,0.0,rightR); Core.gemm(r,tv,1.0,t,1.0,rightT); Calib3d.Rodrigues(rightR,rightV)
            if(fish) Calib3d.fisheye_projectPoints(o.objects,predicted,rightV,rightT,k2,d2)
            else Calib3d.projectPoints(objectPoints,rightV,rightT,k2,dist,predicted)
            return Core.norm(predicted,o.right,Core.NORM_L2)/sqrt(o.right.rows().toDouble())
        } finally { listOf(rv,tv,rot,rightR,rightT,rightV,empty,predicted,objectPoints,dist).forEach { it.release() } }
    }
    override fun close() { observations.forEach { listOf(it.objects,it.left,it.right).forEach(Mat::release) }; observations.clear(); detector.clear() }
    fun save(file: File, identity: String, width: Int, height: Int) {
        fun points(m: Mat) = JSONArray((0 until m.rows()).map { row -> JSONArray(m.get(row,0).toList()) })
        val views = JSONArray(observations.map { o ->
            val p=o.head.position; val q=o.head.rotation
            JSONObject().put("objects",points(o.objects)).put("left",points(o.left)).put("right",points(o.right))
                .put("head",JSONArray(listOf(p.x,p.y,p.z,q.w,q.x,q.y,q.z)))
        })
        val data=JSONObject().put("version",1).put("identity",identity).put("width",width).put("height",height).put("views",views)
        file.parentFile?.mkdirs()
        val temporary=File(file.parentFile,file.name+".tmp")
        temporary.writeText(data.toString())
        java.nio.file.Files.move(temporary.toPath(),file.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }
    companion object {
        fun load(context: Context, file: File, identity: String, width: Int, height: Int): StereoCalibrator? {
            if (!file.isFile) return null
            val restored=StereoCalibrator(context)
            try {
                val data=JSONObject(file.readText())
                require(data.getInt("version")==1 && data.getString("identity")==identity && data.getInt("width")==width && data.getInt("height")==height)
                val views=data.getJSONArray("views")
                require(views.length() in 1..1000)
                for (i in 0 until views.length()) {
                    val view=views.getJSONObject(i)
                    val points=CalibrationObservationData.parse(view)
                    restored.observations.add(BoardObservation(
                        MatOfPoint3f(*points.objects.map { Point3(it[0],it[1],it[2]) }.toTypedArray()),
                        MatOfPoint2f(*points.left.map { Point(it[0],it[1]) }.toTypedArray()),
                        MatOfPoint2f(*points.right.map { Point(it[0],it[1]) }.toTypedArray()),points.head))
                }
                return restored
            } catch (e: Exception) {
                restored.close()
                android.util.Log.w("StereoCalibration", "Saved captures could not be restored", e)
                return null
            }
        }
        internal fun stereoGeometry(objects: List<Mat>, left: List<Mat>, right: List<Mat>, k1: Mat,d1: Mat,k2: Mat,d2: Mat,
            size: Size,fish: Boolean,r: Mat,t: Mat) {
            val e=Mat(); val f=Mat(); val identity=Mat.eye(3,3,CvType.CV_64F); val zero=Mat.zeros(5,1,CvType.CV_64F)
            val corrected=mutableListOf<Mat>()
            fun images(input: List<Mat>, k: Mat,d: Mat) = if (!fish) input else input.map { pixels ->
                Mat().also { output -> corrected.add(output); Calib3d.fisheye_undistortPoints(pixels,output,k,d,identity,k) }
            }
            try {
                // OpenCV 4.13 fisheye stereo fitting assumes equal corner counts in every view.
                Calib3d.stereoCalibrate(objects,images(left,k1,d1),images(right,k2,d2),k1,if(fish)zero else d1,k2,if(fish)zero else d2,
                    size,r,t,e,f,Calib3d.CALIB_FIX_INTRINSIC)
            } finally { (corrected+listOf(e,f,identity,zero)).forEach(Mat::release) }
        }
        fun matrix(q: SpatialRotation)=Mat(3,3,CvType.CV_64F).also { it.put(0,0,*q.matrix().map(Float::toDouble).toDoubleArray()) }
        fun vector(p: SpatialVector)=Mat(3,1,CvType.CV_64F).also { it.put(0,0,p.x.toDouble(),p.y.toDouble(),p.z.toDouble()) }
        fun pose(r: Mat,t: Mat)=SpatialPose(SpatialVector(t.get(0,0)[0].toFloat(),t.get(1,0)[0].toFloat(),t.get(2,0)[0].toFloat()),
            SpatialRotation.fromBasis(column(r,0),column(r,1),column(r,2)))
        private fun column(r: Mat,c: Int)=SpatialVector(r.get(0,c)[0].toFloat(),r.get(1,c)[0].toFloat(),r.get(2,c)[0].toFloat())
    }
}
