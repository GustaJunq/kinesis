package dev.kinesis.core.scene

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The maths of the TimeSlice effect: the picture is cut into strips along a direction and strip k shows the layer a
 * number of frames in the past. Everything here is plain arithmetic so it is tested without a GPU; the compositor
 * (preview and export) and `fx/timeSliceStrip.frag` follow the same formulas.
 */
object TimeSlice {
    const val MIN_STRIPS = 2
    const val MAX_STRIPS = 24
    const val MAX_OFFSET = 30

    enum class Order(val id: String) {
        /** The first strip is the present, each next one is further in the past. */
        FORWARD("forward"),

        /** The last strip is the present. */
        BACKWARD("backward"),

        /** The strips in the middle are the present, the edges the furthest in the past. */
        CENTER("center"),
        ;

        companion object {
            fun of(id: String): Order = entries.firstOrNull { it.id == id } ?: FORWARD
        }
    }

    /**
     * What a device can afford: how many strips, how far back the frame cache reaches (in frames) and how many frames
     * may be decoded on the spot in one rendered frame. [EXACT] is the export: nothing is dropped.
     */
    data class Budget(val maxStrips: Int, val maxFramesBack: Int, val maxMissesPerFrame: Int) {
        val isExact: Boolean get() = this == EXACT
    }

    val EXACT = Budget(MAX_STRIPS, Int.MAX_VALUE, Int.MAX_VALUE)

    /**
     * [shifts] are the frames back of each strip (0 = the present); [stride] > 1 means only every stride-th frame is
     * kept in the cache and the past frames are rounded down to those (a lower frame rate for the older strips).
     */
    class Plan(val strips: Int, val shifts: IntArray, val stride: Int) {
        val maxShift: Int get() = shifts.maxOrNull() ?: 0

        /** Frames back that the cache has to hold. */
        val window: Int get() = maxShift + stride
    }

    fun plan(count: Int, order: Order, offsetFrames: Int, budget: Budget = EXACT): Plan {
        val strips = count.coerceIn(MIN_STRIPS, MAX_STRIPS).coerceAtMost(max(MIN_STRIPS, budget.maxStrips))
        val offset = offsetFrames.coerceIn(0, MAX_OFFSET)
        val shifts = IntArray(strips) { k ->
            when (order) {
                Order.FORWARD -> k * offset
                Order.BACKWARD -> (strips - 1 - k) * offset
                Order.CENTER -> Math.round(abs(k - (strips - 1) / 2.0) * offset).toInt()
            }
        }
        val maxShift = shifts.max()
        val stride = if (maxShift <= budget.maxFramesBack) 1 else ceil(maxShift / budget.maxFramesBack.toDouble()).toInt()
        return Plan(strips, shifts, stride)
    }

    /** The frame a strip with [shift] shows while frame [frame] is rendered: never before frame 0. */
    fun frameFor(frame: Int, shift: Int, stride: Int): Int {
        if (shift <= 0) return frame
        return max(0, Math.floorDiv(frame - shift, stride) * stride)
    }

    /** Whether the cache keeps frame [frame] for later strips. */
    fun isStored(frame: Int, stride: Int): Boolean = stride <= 1 || frame % stride == 0

    /**
     * Position (0 = start, 1 = end) of the point ([x], [y]), in pixels from the centre of a [width]×[height] picture
     * (y down), along the direction [angleDeg] (0 = left to right, 90 = top to bottom).
     */
    fun position(x: Float, y: Float, width: Float, height: Float, angleDeg: Float): Float {
        val a = Math.toRadians(angleDeg.toDouble())
        val dx = cos(a).toFloat()
        val dy = sin(a).toFloat()
        val half = (abs(dx) * width + abs(dy) * height) / 2f
        if (half <= 1e-6f) return 0.5f
        return ((x * dx + y * dy) / half * 0.5f + 0.5f).coerceIn(0f, 1f)
    }

    /**
     * How much strip [k] of [strips] counts at [s] (0..1 along the direction). [smooth] 0 = hard edges (steps), 1 = the
     * neighbouring strips are mixed over half a strip on both sides of each edge. The weights of all strips sum to 1.
     */
    fun weight(k: Int, strips: Int, s: Float, smooth: Float): Float {
        val at = (s * strips).coerceIn(0f, strips - 1e-4f)
        val i = floor(at).toInt()
        val pos = at - i
        val h = max(smooth.coerceIn(0f, 1f) * 0.5f, 1e-4f)
        val below = if (pos < h) 0.5f - 0.5f * pos / h else 0f
        val above = if (1f - pos < h) 0.5f - 0.5f * (1f - pos) / h else 0f
        return when (k) {
            i -> 1f - (if (i > 0) below else 0f) - (if (i < strips - 1) above else 0f)
            i - 1 -> if (i > 0) below else 0f
            i + 1 -> if (i < strips - 1) above else 0f
            else -> 0f
        }
    }

    /** Preview budget for a device: low-memory phones keep fewer frames and strips, and decode less per frame. */
    fun previewBudget(lowMemory: Boolean, playing: Boolean, frameBytes: Long, cacheBytes: Long): Budget {
        val frames = (cacheBytes / max(1L, frameBytes)).toInt().coerceAtLeast(MIN_STRIPS)
        return Budget(
            maxStrips = if (lowMemory) 12 else MAX_STRIPS,
            maxFramesBack = min(frames, 600),
            maxMissesPerFrame = if (playing) 1 else if (lowMemory) 2 else 4,
        )
    }
}
