package com.fieldcam.bucketcounter

/**
 * Maps coordinates between the analysis buffer (the image ArUco detection ran on)
 * and the overlay view (screen pixels), assuming the PreviewView uses
 * ScaleType.FIT_CENTER (whole frame visible, letterboxed - not cropped).
 * That keeps this mapping exact; FILL_CENTER would additionally need crop math.
 */
data class FrameTransform(
    val analysisWidth: Int,
    val analysisHeight: Int,
    val scale: Float,
    val offsetX: Float,
    val offsetY: Float
) {
    fun toViewX(x: Float): Float = offsetX + x * scale
    fun toViewY(y: Float): Float = offsetY + y * scale
    fun toAnalysisX(x: Float): Float = (x - offsetX) / scale
    fun toAnalysisY(y: Float): Float = (y - offsetY) / scale

    companion object {
        fun fitCenter(analysisWidth: Int, analysisHeight: Int, viewWidth: Int, viewHeight: Int): FrameTransform {
            val safeViewW = viewWidth.coerceAtLeast(1)
            val safeViewH = viewHeight.coerceAtLeast(1)
            val scale = minOf(
                safeViewW / analysisWidth.toFloat(),
                safeViewH / analysisHeight.toFloat()
            )
            val offsetX = (safeViewW - analysisWidth * scale) / 2f
            val offsetY = (safeViewH - analysisHeight * scale) / 2f
            return FrameTransform(analysisWidth, analysisHeight, scale, offsetX, offsetY)
        }
    }
}
