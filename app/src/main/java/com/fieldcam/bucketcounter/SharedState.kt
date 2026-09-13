package com.fieldcam.bucketcounter

/**
 * Line position is written from the UI thread (touch drag) and read from the
 * analyzer's background thread every frame. A single @Volatile float is enough
 * here since it's a simple set/get, never a read-modify-write across threads.
 */
object SharedState {
    @Volatile
    var lineYFraction: Float = BucketConfig.LINE_Y_DEFAULT_FRAC
}
