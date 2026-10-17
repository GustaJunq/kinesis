package dev.kinesis.core.edit

import kotlin.math.max
import kotlin.math.min

/** Where a clip lands in the visible part of the track, in track pixels (0 = left edge of the track). */
data class ClipSpan(
    /** Left edge of the clip's real extent; negative when it starts before the visible area. */
    val trueLeft: Float,
    /** Real width of the clip at this zoom (never limited by the viewport). */
    val trueWidth: Float,
    /** Visible part: [left, left + width), already inside [0, viewport]. */
    val left: Float,
    val width: Float,
    /** The clip continues past the left / right edge of the track. */
    val cutStart: Boolean,
    val cutEnd: Boolean,
    val visible: Boolean,
) {
    /** How far the visible part starts inside the clip, in pixels of the clip. */
    val shift: Float get() = left - trueLeft
}

/**
 * The one mapping between timeline seconds and track pixels: [pxPerSecond] is the zoom and [scrollPx] how far the
 * track is scrolled to the right. The ruler, clips, waveforms, keyframes, markers and the playhead all use it, so they
 * cannot disagree. Zoom changes the length of every clip in pixels (and nothing else); scrolling only moves them.
 */
data class TimelineScale(val pxPerSecond: Float, val scrollPx: Float = 0f) {
    init {
        require(pxPerSecond > 0f) { "pxPerSecond must be positive" }
    }

    fun xOf(time: Double): Float = (time * pxPerSecond - scrollPx).toFloat()

    fun timeAt(x: Float): Double = (x + scrollPx).toDouble() / pxPerSecond

    /** Seconds visible in a track [viewport] pixels wide: from [timeAt] 0 to [timeAt] viewport. */
    fun visibleSeconds(viewport: Float): ClosedFloatingPointRange<Double> = timeAt(0f)..timeAt(viewport)

    /** The span of a clip starting at absolute [start] seconds and lasting [duration]; at least [minWidth] wide. */
    fun span(start: Double, duration: Double, viewport: Float, minWidth: Float = 0f): ClipSpan {
        val left = xOf(start)
        val width = max((duration * pxPerSecond).toFloat(), minWidth)
        val right = left + width
        val visLeft = max(left, 0f)
        val visRight = min(right, viewport)
        return ClipSpan(left, width, visLeft, max(visRight - visLeft, 0f), left < 0f, right > viewport, visRight > visLeft)
    }

    /** Zoom by [factor] keeping the time under [focusX] where it is; the scroll never goes below 0. */
    fun zoomedAround(focusX: Float, factor: Float, minPx: Float = MIN_PX, maxPx: Float = MAX_PX): TimelineScale {
        val t = timeAt(focusX)
        val px = (pxPerSecond * factor).coerceIn(minPx, maxPx)
        return TimelineScale(px, (t * px - focusX).toFloat().coerceAtLeast(0f))
    }

    /** Largest useful scroll: the end of the composition can reach about 60% into the track. */
    fun maxScroll(duration: Double, viewport: Float): Float = max((duration * pxPerSecond).toFloat() - viewport * 0.6f, 0f)

    fun scrolledTo(px: Float, duration: Double, viewport: Float) = copy(scrollPx = px.coerceIn(0f, maxScroll(duration, viewport)))

    companion object {
        const val MIN_PX = 8f
        const val MAX_PX = 2000f

        /** Whole composition visible with a small margin. */
        fun fit(duration: Double, viewport: Float): TimelineScale =
            TimelineScale((viewport / duration.toFloat() * 0.95f).coerceIn(MIN_PX, MAX_PX), 0f)
    }
}
