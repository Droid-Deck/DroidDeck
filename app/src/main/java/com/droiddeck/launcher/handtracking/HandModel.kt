package com.droiddeck.launcher.handtracking

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.components.containers.Landmark as MpLandmark
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import kotlin.math.hypot
import kotlin.math.max

internal class HandModel private constructor(private val landmarkers: List<HandLandmarker>, private val starter: HandLandmarker) {
    private class Track(val x: Float, val y: Float, val size: Float)

    private val crops = List(2) { Bitmap.createBitmap(CROP, CROP, Bitmap.Config.ARGB_8888) }
    private val startCrop = Bitmap.createBitmap(START_CROP, START_CROP, Bitmap.Config.ARGB_8888)
    private val tracks = arrayOfNulls<Track>(2)
    private val starts = IntArray(2)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val matrix = Matrix()

    fun find(frame: Bitmap, marks: List<Landmark>, k: Intrinsics, stampMs: Long): Array<HandFix?> {
        val fixes = arrayOfNulls<HandFix>(2)
        if (marks.size <= BodyPose.RIGHT_INDEX) return fixes
        val shoulders = hypot((marks[BodyPose.LEFT_SHOULDER].x - marks[BodyPose.RIGHT_SHOULDER].x) * k.width,
            (marks[BodyPose.LEFT_SHOULDER].y - marks[BodyPose.RIGHT_SHOULDER].y) * k.height)
        for (side in 0..1) {
            val wrist = marks[WRISTS[side]]
            val wx = wrist.x * k.width
            val wy = wrist.y * k.height
            val fix = tracks[side]?.let { follow(frame, it, side, k, stampMs) }?.takeIf { belongs(it, marks, side, shoulders, k) }
                ?: start(frame, marks, side, wx, wy, shoulders, k)?.takeIf { belongs(it, marks, side, shoulders, k) }
            if (fix == null) {
                tracks[side] = null
                continue
            }
            fixes[side] = fix
            starts[side] = 0
            tracks[side] = Track(fix.x, fix.y, max(fix.extent, shoulders * MIN_HAND))
        }
        val left = fixes[0]
        val right = fixes[1]
        if (left != null && right != null && hypot(left.x - right.x, left.y - right.y) < max(left.extent, right.extent) * SAME_HAND) {
            val drop = if (gap(left, marks, 0, k) <= gap(right, marks, 1, k)) 1 else 0
            fixes[drop] = null
            tracks[drop] = null
        }
        return fixes
    }

    fun close() {
        landmarkers.forEach { runCatching { it.close() } }
        runCatching { starter.close() }
        crops.forEach { it.recycle() }
        startCrop.recycle()
    }

    private fun follow(frame: Bitmap, track: Track, side: Int, k: Intrinsics, stampMs: Long): HandFix? {
        val size = max(track.size * CROP_HANDS, MIN_CROP)
        val x0 = track.x - size / 2
        val y0 = track.y - size / 2
        draw(frame, crops[side], x0, y0, size)
        val result = landmarkers[side].detectForVideo(BitmapImageBuilder(crops[side]).build(), stampMs)
        val found = result.landmarks().firstOrNull() ?: return null
        val world = result.worldLandmarks().firstOrNull() ?: return null
        return fixOf(found, world, x0, y0, size, k)
    }

    private fun start(frame: Bitmap, marks: List<Landmark>, side: Int, wx: Float, wy: Float, shoulders: Float, k: Intrinsics): HandFix? {
        val wrist = marks[WRISTS[side]]
        if (wrist.visibility < MIN_VISIBILITY || shoulders < 1f) return null
        if (wrist.x < -EDGE || wrist.x > 1f + EDGE || wrist.y < -EDGE || wrist.y > 1f + EDGE) return null
        val wide = starts[side]++ % 2 == 0
        val tx = (marks[PINKIES[side]].x + marks[INDEXES[side]].x) / 2 * k.width - wx
        val ty = (marks[PINKIES[side]].y + marks[INDEXES[side]].y) / 2 * k.height - wy
        val length = hypot(tx, ty)
        val reach = if (wide) 0f else shoulders * TOWARD_FINGERS
        val cx = if (length > 1e-3f) wx + tx / length * reach else wx
        val cy = if (length > 1e-3f) wy + ty / length * reach else wy
        val size = max(shoulders * if (wide) WIDE_SHOULDERS else START_SHOULDERS, MIN_CROP)
        val x0 = cx - size / 2
        val y0 = cy - size / 2
        draw(frame, startCrop, x0, y0, size)
        val result = starter.detect(BitmapImageBuilder(startCrop).build())
        var best: HandFix? = null
        var bestGap = Float.MAX_VALUE
        result.landmarks().forEachIndexed { i, found ->
            val world = result.worldLandmarks().getOrNull(i) ?: return@forEachIndexed
            val fix = fixOf(found, world, x0, y0, size, k) ?: return@forEachIndexed
            val gap = hypot(fix.points[0] - wx, fix.points[1] - wy)
            if (gap < bestGap) {
                best = fix
                bestGap = gap
            }
        }
        return best
    }

    private fun draw(frame: Bitmap, crop: Bitmap, x0: Float, y0: Float, size: Float) {
        crop.eraseColor(Color.BLACK)
        matrix.setTranslate(-x0, -y0)
        matrix.postScale(crop.width / size, crop.height / size)
        Canvas(crop).drawBitmap(frame, matrix, paint)
    }

    private fun fixOf(found: List<NormalizedLandmark>, world: List<MpLandmark>, x0: Float, y0: Float, size: Float, k: Intrinsics): HandFix? {
        if (found.size < BodyPose.HAND_POINTS || world.size < BodyPose.HAND_POINTS) return null
        val points = FloatArray(BodyPose.HAND_POINTS * 2) { i ->
            val p = found[i / 2]
            if (i % 2 == 0) x0 + p.x() * size else y0 + p.y() * size
        }
        return BodyPose.handFix(points, world.map { WorldPoint(it.x(), it.y(), it.z()) }, k)
    }

    private fun belongs(fix: HandFix, marks: List<Landmark>, side: Int, shoulders: Float, k: Intrinsics): Boolean {
        val own = marks[WRISTS[side]]
        if (hypot(fix.x - own.x * k.width, fix.y - own.y * k.height) > max(fix.extent, shoulders) * MAX_WRIST_GAP) return false
        val other = marks[WRISTS[1 - side]]
        if (other.visibility < MIN_VISIBILITY || other.x !in 0f..1f || other.y !in 0f..1f) return true
        return hypot(fix.points[0] - other.x * k.width, fix.points[1] - other.y * k.height) >=
            hypot(fix.points[0] - own.x * k.width, fix.points[1] - own.y * k.height)
    }

    private fun gap(fix: HandFix, marks: List<Landmark>, side: Int, k: Intrinsics): Float =
        hypot(fix.x - marks[WRISTS[side]].x * k.width, fix.y - marks[WRISTS[side]].y * k.height)

    companion object {
        private val WRISTS = intArrayOf(BodyPose.LEFT_WRIST, BodyPose.RIGHT_WRIST)
        private val PINKIES = intArrayOf(BodyPose.LEFT_PINKY, BodyPose.RIGHT_PINKY)
        private val INDEXES = intArrayOf(BodyPose.LEFT_INDEX, BodyPose.RIGHT_INDEX)
        private const val CROP = 256
        private const val START_CROP = 384
        private const val CROP_HANDS = 2.8f
        private const val MIN_CROP = 48f
        private const val START_SHOULDERS = 2.5f
        private const val WIDE_SHOULDERS = 4f
        private const val MIN_HAND = 0.25f
        private const val TOWARD_FINGERS = 0.35f
        private const val MAX_WRIST_GAP = 3f
        private const val SAME_HAND = 0.5f
        private const val EDGE = 0.05f
        private const val MIN_VISIBILITY = 0.5f
        private const val MIN_CONFIDENCE = 0.4f

        fun open(context: Context): HandModel {
            check(TrackingPack.loadNative(context)) { "hand tracking download is not installed" }
            fun landmarker(mode: RunningMode, hands: Int): HandLandmarker {
                val model = TrackingPack.model(context, TrackingPack.HAND)
                return HandLandmarker.createFromOptions(context, HandLandmarker.HandLandmarkerOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setModelAssetBuffer(model).setDelegate(Delegate.CPU).build())
                    .setRunningMode(mode)
                    .setNumHands(hands)
                    .setMinHandDetectionConfidence(MIN_CONFIDENCE)
                    .setMinHandPresenceConfidence(MIN_CONFIDENCE)
                    .setMinTrackingConfidence(MIN_CONFIDENCE)
                    .build())
            }
            return HandModel(List(2) { landmarker(RunningMode.VIDEO, 1) }, landmarker(RunningMode.IMAGE, 2))
        }
    }
}
