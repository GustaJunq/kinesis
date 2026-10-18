package dev.kinesis.core.edit

import kotlin.math.roundToInt

/**
 * The frame rates the editor offers: any whole number from [MIN] to [MAX]. Older projects may hold other values
 * (24, 25, 50 and 60 were presets; the format accepts 1..120): they open unchanged and are only clamped when edited.
 */
object FrameRate {
    const val MIN = 12
    const val MAX = 60
    const val DEFAULT = 30

    /** The nearest whole frame rate inside the offered range. */
    fun clamp(fps: Double): Double = fps.roundToInt().coerceIn(MIN, MAX).toDouble()

    /** Whether [fps] can be shown on the slider as is. */
    fun inRange(fps: Double): Boolean = fps >= MIN && fps <= MAX
}
