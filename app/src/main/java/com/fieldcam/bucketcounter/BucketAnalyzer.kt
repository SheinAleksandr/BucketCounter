package com.fieldcam.bucketcounter

import android.graphics.PointF
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.objdetect.ArucoDetector
import org.opencv.objdetect.DetectorParameters
import org.opencv.objdetect.Objdetect

/**
 * CameraX analyzer that ports the AprilTag pick_target() + FSM loop from kun_front.py
 * to OpenCV's ArUco detector, using the built-in AprilTag-36h11 dictionary
 * (Objdetect.DICT_APRILTAG_36h11) - so the same printed tags the MaixCAM script uses
 * work here without reprinting anything.
 *
 * NOTE: this class was written without access to an Android build environment to
 * compile/run against, so treat it as a first-cut port. The most likely things to
 * need on-device fixing:
 *  - exact class/constant names in org.opencv.objdetect can shift a little between
 *    OpenCV point releases (this targets the 4.7+ "aruco merged into objdetect" API).
 *  - image rotation/mirroring: verify rotateForDisplay() actually lines the analysis
 *    frame up with what's on screen for your specific phone/camera orientation.
 */
class BucketAnalyzer(
    private val fsm: BucketCounterFsm,
    private val onResult: (FrameResult) -> Unit
) : ImageAnalysis.Analyzer {

    data class FrameResult(
        val transform: FrameTransform,
        val roiRectAnalysis: RectF,
        val targetRectAnalysis: RectF?,
        val targetCenterAnalysis: PointF?,
        val bucketCount: Int,
        val fsmStatus: String,
        val lastStatus: String,
        val flashUntilMs: Long,
        val flashValue: Int,
        val counted: Boolean
    )

    // Set from MainActivity once the overlay view has a known size.
    @Volatile var viewWidthHint: Int = 1
    @Volatile var viewHeightHint: Int = 1

    private val dictionary = Objdetect.getPredefinedDictionary(Objdetect.DICT_APRILTAG_36h11)
    private val detectorParams = DetectorParameters()
    private val detector = ArucoDetector(dictionary, detectorParams)

    @Volatile private var flashUntilMs: Long = 0
    @Volatile private var flashValue: Int = 0

    override fun analyze(image: ImageProxy) {
        try {
            val gray = imageProxyToGrayMat(image)
            val rotated = rotateForDisplay(gray, image.imageInfo.rotationDegrees)
            gray.release()

            val w = rotated.width()
            val h = rotated.height()

            val roi = RectF(
                BucketConfig.ROI_X_FRAC * w,
                BucketConfig.ROI_Y_FRAC * h,
                (BucketConfig.ROI_X_FRAC + BucketConfig.ROI_W_FRAC) * w,
                (BucketConfig.ROI_Y_FRAC + BucketConfig.ROI_H_FRAC) * h
            )
            val roiRectCv = Rect(
                roi.left.toInt(), roi.top.toInt(),
                roi.width().toInt(), roi.height().toInt()
            )
            val roiMat = Mat(rotated, roiRectCv)

            val corners = ArrayList<Mat>()
            val ids = Mat()
            detector.detectMarkers(roiMat, corners, ids)

            var bestRect: RectF? = null
            var bestCenter: PointF? = null
            var bestPerimeter = -1.0

            for (i in corners.indices) {
                val id = if (ids.rows() > i) ids.get(i, 0)[0].toInt() else -1
                if (id != BucketConfig.TRACK_ID) continue

                val c = corners[i] // 1x4, CV_32FC2: four (x,y) corner points, ROI-local
                val pts = FloatArray(8)
                c.get(0, 0, pts)

                var minX = Float.MAX_VALUE
                var minY = Float.MAX_VALUE
                var maxX = -Float.MAX_VALUE
                var maxY = -Float.MAX_VALUE
                var perimeter = 0.0
                for (k in 0 until 4) {
                    val px = pts[k * 2] + roi.left
                    val py = pts[k * 2 + 1] + roi.top
                    minX = min(minX, px); maxX = max(maxX, px)
                    minY = min(minY, py); maxY = max(maxY, py)
                    val nk = (k + 1) % 4
                    val nx = pts[nk * 2] + roi.left
                    val ny = pts[nk * 2 + 1] + roi.top
                    perimeter += hypot((nx - px).toDouble(), (ny - py).toDouble())
                }
                // Original script scores by decision_margin + size; OpenCV's ArUco API doesn't
                // expose a per-marker confidence the same way, so when several detections share
                // TRACK_ID we just keep the largest one (closest/most reliable in practice).
                if (perimeter > bestPerimeter) {
                    bestPerimeter = perimeter
                    bestRect = RectF(minX, minY, maxX, maxY)
                    bestCenter = PointF((minX + maxX) / 2f, (minY + maxY) / 2f)
                }
            }
            roiMat.release()
            ids.release()
            corners.forEach { it.release() }

            val now = SystemClock.elapsedRealtime()
            val lastStatus: String
            var counted: Boolean

            if (bestCenter != null) {
                lastStatus = "Тег обнаружен"
                val lineYAbs = SharedState.lineYFraction * h
                counted = fsm.update(bestCenter.y, lineYAbs, now)
            } else {
                lastStatus = "Ковш не найден"
                counted = fsm.update(null, 0f, now)
            }

            if (counted) {
                flashUntilMs = now + BucketConfig.FLASH_MS
                flashValue = fsm.bucketCount
            }

            val transform = FrameTransform.fitCenter(w, h, viewWidthHint, viewHeightHint)
            onResult(
                FrameResult(
                    transform = transform,
                    roiRectAnalysis = roi,
                    targetRectAnalysis = bestRect,
                    targetCenterAnalysis = bestCenter,
                    bucketCount = fsm.bucketCount,
                    fsmStatus = fsm.fsmStatus,
                    lastStatus = lastStatus,
                    flashUntilMs = flashUntilMs,
                    flashValue = flashValue,
                    counted = counted
                )
            )
            rotated.release()
        } catch (t: Throwable) {
            Log.e("BucketAnalyzer", "analyze() failed", t)
        } finally {
            image.close()
        }
    }

    /** Y plane only - ArUco detection just needs grayscale, no need to build a full RGB Mat. */
    private fun imageProxyToGrayMat(image: ImageProxy): Mat {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val width = image.width
        val height = image.height
        val data = ByteArray(buffer.remaining())
        buffer.get(data)

        return if (rowStride == width) {
            Mat(height, width, CvType.CV_8UC1).apply { put(0, 0, data) }
        } else {
            // Row stride has padding beyond `width`; build at full stride then crop.
            val full = Mat(height, rowStride, CvType.CV_8UC1)
            full.put(0, 0, data)
            val cropped = Mat(full, Rect(0, 0, width, height)).clone()
            full.release()
            cropped
        }
    }

    private fun rotateForDisplay(mat: Mat, rotationDegrees: Int): Mat {
        return when (rotationDegrees) {
            90 -> Mat().also { Core.rotate(mat, it, Core.ROTATE_90_CLOCKWISE) }
            180 -> Mat().also { Core.rotate(mat, it, Core.ROTATE_180) }
            270 -> Mat().also { Core.rotate(mat, it, Core.ROTATE_90_COUNTERCLOCKWISE) }
            else -> mat.clone()
        }
    }
}
