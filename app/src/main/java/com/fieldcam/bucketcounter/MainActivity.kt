package com.fieldcam.bucketcounter

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.util.Size
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import org.opencv.android.OpenCVLoader

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var overlayView: BucketOverlayView
    private val fsm = BucketCounterFsm()
    private lateinit var analysisExecutor: ExecutorService

    private lateinit var cropPresets: MutableList<CropPreset>
    private var selectedCropIndex: Int = 0
    private val currentCrop: CropPreset get() = cropPresets[selectedCropIndex]

    private val requestPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startCamera()
        } else {
            Log.e(TAG, "Camera permission denied")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!OpenCVLoader.initLocal()) {
            Log.e(TAG, "OpenCV init failed")
        }

        SharedState.lineYFraction = loadLineYFraction()
        analysisExecutor = Executors.newSingleThreadExecutor()

        val (presets, selected) = CropSettingsStore.load(this)
        cropPresets = presets
        selectedCropIndex = selected

        val root = FrameLayout(this)
        previewView = PreviewView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            // FIT_CENTER (not the CameraX default FILL_CENTER) so the overlay's
            // analysis->view coordinate mapping in FrameTransform stays exact,
            // with no crop math needed.
            scaleType = PreviewView.ScaleType.FIT_CENTER
        }
        overlayView = BucketOverlayView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        root.addView(previewView)
        root.addView(overlayView)
        setContentView(root)

        overlayView.listener = object : BucketOverlayView.Listener {
            override fun onResetRequested() {
                fsm.reset()
            }

            override fun onManualAdjust(delta: Int) {
                fsm.manualAdjust(delta)
            }

            override fun onLineChanged(newLineYFraction: Float) {
                SharedState.lineYFraction = newLineYFraction
                saveLineYFraction(newLineYFraction)
            }

            override fun onSettingsRequested() {
                showBucketSettingsDialog()
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            requestPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            // 720p: through-glass detection at up to ~3m needs the tag to still
            // resolve to a reasonable number of pixels, 640x480 may be too coarse.
            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(1280, 720))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            val analyzer = BucketAnalyzer(fsm) { result ->
                runOnUiThread {
                    overlayView.updateFrame(
                        transform = result.transform,
                        roiRectAnalysis = result.roiRectAnalysis,
                        targetRectAnalysis = result.targetRectAnalysis,
                        targetCenterAnalysis = result.targetCenterAnalysis,
                        bucketCount = result.bucketCount,
                        fsmStatus = result.fsmStatus,
                        lastStatus = result.lastStatus,
                        flashUntilMs = result.flashUntilMs,
                        flashValue = result.flashValue,
                        cropName = currentCrop.name,
                        totalWeightKg = result.bucketCount * currentCrop.weightKg
                    )
                }
            }
            analysis.setAnalyzer(analysisExecutor, analyzer)

            overlayView.post {
                analyzer.viewWidthHint = overlayView.width
                analyzer.viewHeightHint = overlayView.height
            }

            provider.unbindAll()
            provider.bindToLifecycle(
                this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis
            )
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * Simple built-in-code settings screen (no XML layout, matching the rest of the
     * UI) for editing per-crop bucket weight presets used to compute total weight.
     */
    private fun showBucketSettingsDialog() {
        val ctx = this
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(8))
        }

        val listContainer = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        container.addView(listContainer)

        lateinit var refreshList: () -> Unit
        refreshList = {
            listContainer.removeAllViews()
            cropPresets.forEachIndexed { index, preset ->
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, dp(6), 0, dp(6))
                }
                val mark = if (index == selectedCropIndex) "✓ " else "    "
                val label = TextView(ctx).apply {
                    text = "$mark${preset.name} — ${preset.weightKg.toInt()} кг"
                    textSize = 16f
                    if (index == selectedCropIndex) setTypeface(typeface, android.graphics.Typeface.BOLD)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    setOnClickListener {
                        selectedCropIndex = index
                        CropSettingsStore.save(ctx, cropPresets, selectedCropIndex)
                        refreshList()
                    }
                }
                val delBtn = Button(ctx).apply {
                    text = "✕"
                    setOnClickListener {
                        if (cropPresets.size > 1) {
                            cropPresets.removeAt(index)
                            if (selectedCropIndex >= cropPresets.size) {
                                selectedCropIndex = cropPresets.size - 1
                            }
                            CropSettingsStore.save(ctx, cropPresets, selectedCropIndex)
                            refreshList()
                        }
                    }
                }
                row.addView(label)
                row.addView(delBtn)
                listContainer.addView(row)
            }
        }
        refreshList()

        val divider = View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
                topMargin = dp(12); bottomMargin = dp(12)
            }
            setBackgroundColor(Color.LTGRAY)
        }
        container.addView(divider)

        val addLabel = TextView(ctx).apply {
            text = "Добавить культуру"
            textSize = 15f
        }
        container.addView(addLabel)

        val addRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, 0)
        }
        val nameInput = EditText(ctx).apply {
            hint = "Культура"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val weightInput = EditText(ctx).apply {
            hint = "Вес, кг"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            layoutParams = LinearLayout.LayoutParams(dp(90), LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        addRow.addView(nameInput)
        addRow.addView(weightInput)
        container.addView(addRow)

        val addBtn = Button(ctx).apply {
            text = "Добавить"
            setOnClickListener {
                val name = nameInput.text.toString().trim()
                val weight = weightInput.text.toString().replace(',', '.').toDoubleOrNull()
                if (name.isNotEmpty() && weight != null && weight > 0) {
                    cropPresets.add(CropPreset(name, weight))
                    selectedCropIndex = cropPresets.size - 1
                    CropSettingsStore.save(ctx, cropPresets, selectedCropIndex)
                    nameInput.text.clear()
                    weightInput.text.clear()
                    refreshList()
                }
            }
        }
        container.addView(addBtn)

        val scroll = ScrollView(ctx).apply { addView(container) }

        AlertDialog.Builder(ctx)
            .setTitle("Настройки ковша")
            .setView(scroll)
            .setPositiveButton("Готово") { d, _ -> d.dismiss() }
            .show()
    }

    private fun loadLineYFraction(): Float {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getFloat(KEY_LINE_Y, BucketConfig.LINE_Y_DEFAULT_FRAC)
    }

    private fun saveLineYFraction(v: Float) {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putFloat(KEY_LINE_Y, v)
            .apply()
    }

    override fun onDestroy() {
        super.onDestroy()
        analysisExecutor.shutdown()
    }

    companion object {
        private const val TAG = "BucketCounter"
        private const val PREFS = "bucket_counter"
        private const val KEY_LINE_Y = "line_y_fraction"
    }
}
