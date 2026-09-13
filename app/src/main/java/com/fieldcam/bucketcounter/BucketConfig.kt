package com.fieldcam.bucketcounter

/**
 * Ported constants from the MaixCAM kun_front.py script.
 * All spatial values are stored as fractions of the analysis frame size
 * so the app works across different phone camera resolutions/aspect ratios.
 */
object BucketConfig {

    // ROI (search area for the tag), same proportions as ROI_X,ROI_Y,ROI_W,ROI_H = 80,0,480,260 on a 640x480 frame.
    const val ROI_X_FRAC = 80f / 640f
    const val ROI_Y_FRAC = 0f
    const val ROI_W_FRAC = 480f / 640f
    const val ROI_H_FRAC = 260f / 480f

    // Default counting line: 80% down the ROI, same as LINE_Y_DEFAULT in the original script.
    const val LINE_Y_DEFAULT_FRAC = ROI_Y_FRAC + ROI_H_FRAC * 0.80f

    // Margin from ROI top/bottom edges the line can be dragged to (10px out of a 260px-tall ROI originally).
    const val LINE_MARGIN_OF_ROI_HEIGHT = 10f / 260f

    // Counting state machine
    const val CONFIRM_FRAMES = 5
    const val COOLDOWN_MS = 2000L
    const val FLASH_MS = 900L

    // AprilTag 36h11 id being tracked (matches TRACK_ID in kun_front.py).
    const val TRACK_ID = 0
}
