package com.droiddeck.launcher.glasses

import org.json.JSONArray
import org.json.JSONObject
import org.opencv.calib3d.Calib3d
import org.opencv.core.*
import org.opencv.objdetect.CharucoBoard
import org.opencv.objdetect.CharucoDetector
import org.opencv.objdetect.Objdetect
import java.io.File
import kotlin.math.*

internal data class BoardObservation(val objects: Mat, val left: Mat, val right: Mat, val head: SpatialPose)

internal class StereoCalibration(val identity: String, val width: Int, val height: Int, val fisheye: Boolean,
    val k1: Mat, val d1: Mat, val k2: Mat, val d2: Mat, val r: Mat, val t: Mat,
    val cameraToHead: SpatialPose, val error: Double, var verified: Boolean = false) : AutoCloseable {
    private val p1 = Mat.zeros(3, 4, CvType.CV_64F)
    private val p2 = Mat.zeros(3, 4, CvType.CV_64F)
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
        if (fisheye) Calib3d.fisheye_undistortImage(src, dst, k, d, k, src.size())
        else Calib3d.undistort(src, dst, k, d)
    }
    fun triangulate(left: List<Point>, right: List<Point>): List<SpatialVector>? {
        if (left.size != right.size || left.isEmpty()) return null
        val a = Mat(2, left.size, CvType.CV_64F); val b = Mat(2, right.size, CvType.CV_64F); val out = Mat()
        try {
            a.put(0, 0, *left.map { it.x }.toDoubleArray()); a.put(1, 0, *left.map { it.y }.toDoubleArray())
            b.put(0, 0, *right.map { it.x }.toDoubleArray()); b.put(1, 0, *right.map { it.y }.toDoubleArray())
            Calib3d.triangulatePoints(p1, p2, a, b, out)
            return left.indices.map { i ->
                val w = out.get(3,i)[0]
                if (!w.isFinite() || abs(w) < 1e-8) return null
                val v = DoubleArray(3) { out.get(it,i)[0]/w }
                if (v.any { !it.isFinite() } || v[2] !in 0.15..1.5) return null
                for ((projection, pixel) in listOf(p1 to left[i], p2 to right[i])) {
                    val q = DoubleArray(3) { row -> (0..2).sumOf { projection.get(row,it)[0]*v[it] } + projection.get(row,3)[0] }
                    if (q[2] <= 0 || hypot(q[0]/q[2]-pixel.x, q[1]/q[2]-pixel.y) > 3.0) return null
                }
                SpatialVector(v[0].toFloat(), v[1].toFloat(), v[2].toFloat())
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
    override fun close() { listOf(k1,d1,k2,d2,r,t,p1,p2).forEach { it.release() } }
    companion object {
        fun load(file: File, identity: String, width: Int, height: Int): StereoCalibration? = runCatching {
            val j = JSONObject(file.readText())
            require(j.getInt("version")==1 && j.getString("identity")==identity && j.getInt("width")==width && j.getInt("height")==height)
            fun m(name: String): Mat {
                val v=j.getJSONObject(name); val a=v.getJSONArray("data")
                val rows=v.getInt("rows"); val cols=v.getInt("cols"); require(rows in 1..14 && cols in 1..14 && a.length()==rows*cols)
                val values=DoubleArray(a.length()) { a.getDouble(it).also { n -> require(n.isFinite()) } }
                return Mat(rows,cols,CvType.CV_64F).also { it.put(0,0,*values) }
            }
            val pose=j.getJSONArray("cameraToHead"); require(pose.length()==7)
            val p=SpatialPose.fromSdk(FloatArray(7) { pose.getDouble(it).toFloat() }) ?: error("Invalid camera transform")
            val error=j.getDouble("error"); require(error.isFinite() && error<=2.0)
            val matrices=listOf("k1","d1","k2","d2","r","t").map(::m)
            require(listOf(0,2,4).all { matrices[it].rows()==3 && matrices[it].cols()==3 })
            require(matrices[5].rows()==3 && matrices[5].cols()==1)
            StereoCalibration(identity,width,height,j.getBoolean("fisheye"),matrices[0],matrices[1],matrices[2],matrices[3],matrices[4],matrices[5],p,error,j.getBoolean("verified"))
        }.getOrNull()
    }
}

internal class StereoCalibrator : AutoCloseable {
    val board = CharucoBoard(Size(5.0,7.0),0.03f,0.022f,Objdetect.getPredefinedDictionary(Objdetect.DICT_4X4_50))
    private val detector = CharucoDetector(board)
    val observations = mutableListOf<BoardObservation>()
    fun detect(left: Mat, right: Mat, head: SpatialPose): BoardObservation? {
        val lc=Mat(); val li=Mat(); val rc=Mat(); val ri=Mat()
        try {
            detector.detectBoard(left,lc,li); detector.detectBoard(right,rc,ri)
            val l=(0 until li.rows()).associate { li.get(it,0)[0].toInt() to lc.get(it,0) }
            val r=(0 until ri.rows()).associate { ri.get(it,0)[0].toInt() to rc.get(it,0) }
            val ids=l.keys.intersect(r.keys).sorted(); if (ids.size<12) return null
            val objects=MatOfPoint3f(*ids.map { Point3((it%4+1)*0.03,(it/4+1)*0.03,0.0) }.toTypedArray())
            return BoardObservation(objects,MatOfPoint2f(*ids.map { Point(l[it]!![0],l[it]!![1]) }.toTypedArray()),
                MatOfPoint2f(*ids.map { Point(r[it]!![0],r[it]!![1]) }.toTypedArray()),head)
        } finally { listOf(lc,li,rc,ri).forEach { it.release() } }
    }
    fun solve(identity: String,width: Int,height: Int): StereoCalibration {
        require(observations.size>=24) { "Capture at least 24 varied views of the stationary board." }
        val initial=observations.first().head.rotation
        val axes=observations.map { initial.inverse()*it.head.rotation }.map { SpatialVector(it.x,it.y,it.z) }
        require(axes.any { a -> axes.any { b -> a.cross(b).length()>0.02f } }) { "Rotate the glasses through both yaw and pitch; translation alone cannot calibrate camera alignment." }
        val training=observations.filterIndexed { i,_ -> i%5!=0 }; val held=observations.filterIndexed { i,_ -> i%5==0 }
        val candidates=listOf(false,true).mapNotNull { fish -> runCatching { fit(identity,width,height,fish,training,held) }.getOrNull() }
        val best=candidates.minByOrNull { it.error } ?: error("Calibration failed. Capture more varied, sharp board views.")
        candidates.filter { it !== best }.forEach { it.close() }
        if(best.error>2.0) { best.close(); error("Held-out reprojection error ${best.error} px exceeds 2 px. Recalibrate.") }
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
            if(fish) Calib3d.fisheye_stereoCalibrate(objects,train.map{it.left},train.map{it.right},k1,d1,k2,d2,size,r,t,Calib3d.fisheye_CALIB_FIX_INTRINSIC)
            else {
                val e=Mat(); val f=Mat()
                try { Calib3d.stereoCalibrate(objects,train.map{it.left},train.map{it.right},k1,d1,k2,d2,size,r,t,e,f,Calib3d.CALIB_FIX_INTRINSIC) }
                finally { e.release(); f.release() }
            }
            require(Core.norm(t) in 0.02..0.3) { "Implausible camera baseline" }
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
                require(Core.checkRange(camR) && Core.checkRange(camT) && Core.norm(camT)<0.3) { "Move your head through more varied orientations." }
                val cameraToHead=pose(camR,camT)
                require(abs(Core.determinant(camR)-1.0)<0.01) { "Invalid camera-to-head rotation" }
                val boardWorld=held.map { o ->
                    val rv=Mat();val tv=Mat();val rotation=Mat()
                    try {
                        boardPose(o.objects,o.left,k1,d1,fish,rv,tv);Calib3d.Rodrigues(rv,rotation)
                        o.head*cameraToHead*pose(rotation,tv)
                    } finally { rv.release();tv.release();rotation.release() }
                }
                val center=boardWorld.map { it.position }.reduce { a,b -> a+b }*(1f/boardWorld.size)
                require(boardWorld.all { (it.position-center).length()<0.05f }) { "Stationary-board alignment varies by over 5 cm. Keep the board fixed and capture sharper views." }
                val orientation=boardWorld.first().rotation
                require(boardWorld.all { val q=orientation.inverse()*it.rotation;abs(q.w)>cos(Math.toRadians(8.0)/2) }) { "Camera-to-head alignment is inconsistent." }
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
    companion object {
        fun matrix(q: SpatialRotation)=Mat(3,3,CvType.CV_64F).also { it.put(0,0,*q.matrix().map(Float::toDouble).toDoubleArray()) }
        fun vector(p: SpatialVector)=Mat(3,1,CvType.CV_64F).also { it.put(0,0,p.x.toDouble(),p.y.toDouble(),p.z.toDouble()) }
        fun pose(r: Mat,t: Mat)=SpatialPose(SpatialVector(t.get(0,0)[0].toFloat(),t.get(1,0)[0].toFloat(),t.get(2,0)[0].toFloat()),
            SpatialRotation.fromBasis(column(r,0),column(r,1),column(r,2)))
        private fun column(r: Mat,c: Int)=SpatialVector(r.get(0,c)[0].toFloat(),r.get(1,c)[0].toFloat(),r.get(2,c)[0].toFloat())
    }
}
