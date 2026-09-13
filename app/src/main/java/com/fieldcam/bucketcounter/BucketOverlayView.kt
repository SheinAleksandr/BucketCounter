package com.fieldcam.bucketcounter

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * Draws the counting line, ROI, detected-tag box, big counter, status text and the
 * LINE / RESET / -1 / +1 buttons on top of the camera preview, and handles touch
 * for those buttons plus dragging the line while in adjust mode.
 * Layout proportions mirror the button placement in kun_front.py.
 */
class BucketOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    interface Listener {
        fun onResetRequested()
        fun onManualAdjust(delta: Int)
        fun onLineChanged(newLineYFraction: Float)
        fun onSettingsRequested()
    }

    var listener: Listener? = null

    private var transform: FrameTransform? = null
    private var roiRectAnalysis: RectF? = null
    private var targetRectAnalysis: RectF? = null
    private var targetCenterAnalysis: PointF? = null

    private var bucketCount: Int = 0
    private var fsmStatus: String = "Готов к подсчёту"
    private var lastStatus: String = "Ковш не найден"
    private var flashUntilMs: Long = 0
    private var flashValue: Int = 0
    private var cropName: String = ""
    private var totalWeightKg: Double = 0.0

    var adjustMode: Boolean = false
        private set

    private val btnLine = RectF()
    private val btnReset = RectF()
    private val btnPlus = RectF()
    private val btnMinus = RectF()
    private val btnSettings = RectF()

    private val paintLine = Paint().apply { color = Color.YELLOW; strokeWidth = 4f }
    private val paintRoi = Paint().apply { color = Color.GRAY; style = Paint.Style.STROKE; strokeWidth = 3f }
    private val paintTarget = Paint().apply { color = Color.RED; style = Paint.Style.STROKE; strokeWidth = 4f }
    private val paintBtnLineOff = Paint().apply { color = Color.BLUE }
    private val paintBtnLineOn = Paint().apply { color = Color.GREEN }
    private val paintBtnReset = Paint().apply { color = Color.RED }
    private val paintBtnAdj = Paint().apply { color = Color.rgb(255, 140, 0) }
    private val paintBtnSettings = Paint().apply { color = Color.rgb(56, 142, 60) }
    private val paintText = Paint().apply { color = Color.WHITE; textSize = 34f; isFakeBoldText = true; isAntiAlias = true }
    private val paintTextSmall = Paint().apply { color = Color.WHITE; textSize = 26f; isFakeBoldText = true; isAntiAlias = true }
    private val paintCounterOutline = Paint().apply { color = Color.RED; textSize = 160f; isFakeBoldText = true; isAntiAlias = true }
    private val paintCounterFill = Paint().apply { color = Color.WHITE; textSize = 160f; isFakeBoldText = true; isAntiAlias = true }
    private val paintStatus = Paint().apply { color = Color.GREEN; textSize = 32f; isAntiAlias = true }
    private val paintWeight = Paint().apply { color = Color.YELLOW; textSize = 38f; isFakeBoldText = true; isAntiAlias = true }
    private val paintFlashBg = Paint().apply { color = Color.BLACK }
    private val paintFlashText = Paint().apply { color = Color.WHITE; textSize = 260f; isFakeBoldText = true; isAntiAlias = true }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val btnW = w * 0.20f
        val btnH = h * 0.09f
        btnLine.set(8f, 8f, 8f + btnW, 8f + btnH)
        btnReset.set(w - btnW - 8f, 8f, w - 8f, 8f + btnH)

        val setW = w * 0.26f
        btnSettings.set(w / 2f - setW / 2f, 8f, w / 2f + setW / 2f, 8f + btnH)

        val adjW = w * 0.15f
        val adjH = h * 0.13f
        val adjY = h * 0.75f - adjH / 2f
        btnMinus.set(10f, adjY, 10f + adjW, adjY + adjH)
        btnPlus.set(w - adjW - 10f, adjY, w - 10f, adjY + adjH)
    }

    private fun formatKg(v: Double): String {
        val rounded = Math.round(v)
        val s = rounded.toString()
        val sb = StringBuilder()
        var count = 0
        for (i in s.length - 1 downTo 0) {
            sb.append(s[i])
            count++
            if (count % 3 == 0 && i != 0) sb.append(' ')
        }
        return sb.reverse().toString()
    }

    fun updateFrame(
        transform: FrameTransform,
        roiRectAnalysis: RectF,
        targetRectAnalysis: RectF?,
        targetCenterAnalysis: PointF?,
        bucketCount: Int,
        fsmStatus: String,
        lastStatus: String,
        flashUntilMs: Long,
        flashValue: Int,
        cropName: String,
        totalWeightKg: Double
    ) {
        this.transform = transform
        this.roiRectAnalysis = roiRectAnalysis
        this.targetRectAnalysis = targetRectAnalysis
        this.targetCenterAnalysis = targetCenterAnalysis
        this.bucketCount = bucketCount
        this.fsmStatus = fsmStatus
        this.lastStatus = lastStatus
        this.flashUntilMs = flashUntilMs
        this.flashValue = flashValue
        this.cropName = cropName
        this.totalWeightKg = totalWeightKg
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val t = transform ?: return

        val lineViewY = t.toViewY(SharedState.lineYFraction * t.analysisHeight)
        canvas.drawLine(0f, lineViewY, width.toFloat(), lineViewY, paintLine)

        if (adjustMode) {
            roiRectAnalysis?.let { r ->
                canvas.drawRect(
                    t.toViewX(r.left), t.toViewY(r.top),
                    t.toViewX(r.right), t.toViewY(r.bottom),
                    paintRoi
                )
            }
        }

        targetRectAnalysis?.let { r ->
            canvas.drawRect(
                t.toViewX(r.left), t.toViewY(r.top),
                t.toViewX(r.right), t.toViewY(r.bottom),
                paintTarget
            )
        }
        targetCenterAnalysis?.let { p ->
            val cx = t.toViewX(p.x)
            val cy = t.toViewY(p.y)
            canvas.drawLine(cx - 12, cy, cx + 12, cy, paintTarget)
            canvas.drawLine(cx, cy - 12, cx, cy + 12, paintTarget)
        }

        canvas.drawRect(btnLine, if (adjustMode) paintBtnLineOn else paintBtnLineOff)
        canvas.drawText("ЛИНИЯ", btnLine.left + 10, btnLine.centerY() + 12, paintText)

        canvas.drawRect(btnReset, paintBtnReset)
        canvas.drawText("СБРОС", btnReset.left + 10, btnReset.centerY() + 12, paintText)

        canvas.drawRect(btnSettings, paintBtnSettings)
        val settingsLabel = "НАСТРОЙКИ"
        val settingsLabelW = paintTextSmall.measureText(settingsLabel)
        canvas.drawText(settingsLabel, btnSettings.centerX() - settingsLabelW / 2f, btnSettings.centerY() + 10, paintTextSmall)

        canvas.drawRect(btnMinus, paintBtnAdj)
        canvas.drawText("-1", btnMinus.centerX() - 18, btnMinus.centerY() + 16, paintText)

        canvas.drawRect(btnPlus, paintBtnAdj)
        canvas.drawText("+1", btnPlus.centerX() - 18, btnPlus.centerY() + 16, paintText)

        val counterText = bucketCount.toString()
        val cx = width / 2f - paintCounterFill.measureText(counterText) / 2f
        val cy = height - 40f
        canvas.drawText(counterText, cx + 3, cy + 3, paintCounterOutline)
        canvas.drawText(counterText, cx, cy, paintCounterFill)

        canvas.drawText(lastStatus, 16f, height - 90f, paintStatus)
        canvas.drawText(fsmStatus, 16f, height - 140f, paintStatus)

        if (cropName.isNotEmpty()) {
            val weightText = "$cropName: ${formatKg(totalWeightKg)} кг"
            val ww = paintWeight.measureText(weightText)
            canvas.drawText(weightText, width - ww - 16f, height - 90f, paintWeight)
        }

        val now = SystemClock.elapsedRealtime()
        if (now < flashUntilMs) {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paintFlashBg)
            val big = flashValue.toString()
            val bw = paintFlashText.measureText(big)
            canvas.drawText(big, (width - bw) / 2f, height / 2f, paintFlashText)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_DOWN) return true
        val x = event.x
        val y = event.y

        when {
            btnLine.contains(x, y) -> {
                adjustMode = !adjustMode
                invalidate()
                return true
            }
            btnReset.contains(x, y) -> {
                listener?.onResetRequested()
                return true
            }
            btnSettings.contains(x, y) -> {
                listener?.onSettingsRequested()
                return true
            }
            btnPlus.contains(x, y) -> {
                listener?.onManualAdjust(1)
                return true
            }
            btnMinus.contains(x, y) -> {
                listener?.onManualAdjust(-1)
                return true
            }
            adjustMode -> {
                val t = transform ?: return true
                val roi = roiRectAnalysis ?: return true
                val analysisY = t.toAnalysisY(y)
                val margin = roi.height() * BucketConfig.LINE_MARGIN_OF_ROI_HEIGHT
                val minY = roi.top + margin
                val maxY = roi.bottom - margin
                val clamped = min(max(analysisY, minY), maxY)
                val frac = clamped / t.analysisHeight
                listener?.onLineChanged(frac)
                invalidate()
            }
        }
        return true
    }
}
