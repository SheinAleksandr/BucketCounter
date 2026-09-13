package com.fieldcam.bucketcounter

/**
 * Direct port of the BELOW -> ABOVE_CHECK -> WAIT_RETURN state machine from kun_front.py.
 * Call update() once per analyzed frame with the tracked tag's Y center (or null if not found).
 */
class BucketCounterFsm(
    private val confirmFrames: Int = BucketConfig.CONFIRM_FRAMES,
    private val cooldownMs: Long = BucketConfig.COOLDOWN_MS
) {
    enum class State { BELOW, ABOVE_CHECK, WAIT_RETURN }

    var state: State = State.BELOW
        private set
    var aboveStreak: Int = 0
        private set
    var lastCountMs: Long = 0
        private set
    var bucketCount: Int = 0
        private set
    var fsmStatus: String = "Готов к подсчёту"
        private set

    /**
     * @param cy tag center Y in the same coordinate space as [lineY], or null if no tag was found this frame.
     * @param nowMs monotonic clock (e.g. SystemClock.elapsedRealtime()).
     * @return true if this call just counted a new bucket (caller should trigger the flash).
     */
    fun update(cy: Float?, lineY: Float, nowMs: Long): Boolean {
        if (cy == null) {
            fsmStatus = "Готов к подсчёту"
            return false
        }

        fsmStatus = "Готов к подсчёту"
        var counted = false

        when (state) {
            State.BELOW -> {
                if (cy < lineY) {
                    aboveStreak = 1
                    state = State.ABOVE_CHECK
                    fsmStatus = "Подтверждение подъёма"
                }
            }
            State.ABOVE_CHECK -> {
                if (cy < lineY) {
                    aboveStreak++
                    if (aboveStreak >= confirmFrames) {
                        state = State.WAIT_RETURN
                        fsmStatus = "Ожидание возврата"
                    }
                } else {
                    aboveStreak = 0
                    state = State.BELOW
                }
            }
            State.WAIT_RETURN -> {
                if (cy >= lineY) {
                    if (nowMs - lastCountMs > cooldownMs) {
                        bucketCount++
                        lastCountMs = nowMs
                        counted = true
                    }
                    state = State.BELOW
                    aboveStreak = 0
                }
            }
        }

        if (state == State.WAIT_RETURN) {
            fsmStatus = "Ожидание возврата"
        }
        return counted
    }

    fun reset() {
        state = State.BELOW
        aboveStreak = 0
        lastCountMs = 0
        bucketCount = 0
        fsmStatus = "Готов к подсчёту"
    }

    fun manualAdjust(delta: Int) {
        bucketCount = (bucketCount + delta).coerceAtLeast(0)
    }
}
