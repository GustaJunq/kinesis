package dev.kinesis.core.anim

import dev.kinesis.core.format.EasingSerializer
import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * Easing of the segment that *starts* at a keyframe. Maps normalized time u ∈ [0, 1] to progress
 * (which may overshoot 0..1 for "back" and "elastic" curves).
 */
@Serializable(with = EasingSerializer::class)
sealed class Easing {
    abstract fun transform(u: Float): Float

    /** A named preset (`"easeOutCubic"`, `"hold"`, ...). */
    data class Preset(val preset: EasingPreset) : Easing() {
        override fun transform(u: Float): Float = preset.transform(u)
    }

    /** CSS-style cubic Bézier through (0,0), (x1,y1), (x2,y2), (1,1). x1 and x2 must be within 0..1. */
    data class CubicBezier(val x1: Float, val y1: Float, val x2: Float, val y2: Float) : Easing() {
        private val curve by lazy { UnitBezier(x1.toDouble(), y1.toDouble(), x2.toDouble(), y2.toDouble()) }
        override fun transform(u: Float): Float = when {
            u <= 0f -> 0f
            u >= 1f -> 1f
            else -> curve.solve(u.toDouble(), 1e-6).toFloat()
        }
    }

    /**
     * Wave between the two keyframe values: `0.5 − 0.5·cos(2π · cycles · (u − offset))`. It starts at 0 and, with the
     * default [offset] of 0 and a half-integer [cycles] (1.5, 2.5, 3.5, ...), ends at 1, so the segment reaches both
     * keyframe values without a jump. Segment ends are always exact (u ≤ 0 → 0, u ≥ 1 → 1); another offset or a
     * whole number of cycles makes the wave start or end mid-swing, which shows as a jump at that end.
     *
     * @property offset how far the wave is shifted to the right, as a fraction of the segment (−1..1); a negative
     * value moves it earlier.
     * @property cycles number of full waves across the segment (0.5..64).
     */
    data class Cyclic(val offset: Float = 0f, val cycles: Float = DEFAULT_CYCLES) : Easing() {
        /** The unclamped wave (what the curve editor draws). */
        fun wave(u: Float): Float = (0.5 - 0.5 * cos(2.0 * PI * cycles * (u - offset))).toFloat()

        override fun transform(u: Float): Float = when {
            u <= 0f -> 0f
            u >= 1f -> 1f
            else -> wave(u)
        }

        /** Where the wave first reaches 1 after its start (the handle that moves [offset]). */
        val peak: Float get() = offset + 0.5f / cycles

        /** Where the wave first returns to 0 after [peak] (the handle that sets [cycles]). */
        val trough: Float get() = offset + 1f / cycles

        companion object {
            const val MIN_CYCLES = 0.5f
            const val MAX_CYCLES = 64f
            const val DEFAULT_CYCLES = 3.5f
        }
    }

    /** Bézier control points that best represent this easing in the curve editor, or null if procedural. */
    fun bezierOrNull(): CubicBezier? = when (this) {
        is CubicBezier -> this
        is Preset -> preset.bezier
        is Cyclic -> null
    }

    val isHold: Boolean get() = this is Preset && preset == EasingPreset.HOLD

    companion object {
        // Getters, not fields: EasingPreset's constants build CubicBezier (nested in Easing), so eager fields here
        // would read EasingPreset while it is still initializing and see null.
        val LINEAR: Easing get() = Preset(EasingPreset.LINEAR)
        val HOLD: Easing get() = Preset(EasingPreset.HOLD)
        val EASE_IN_OUT: Easing get() = Preset(EasingPreset.EASE_IN_OUT)
    }
}

/**
 * Easing presets. Polynomial / sine / expo / circ / back presets are the usual cubic-Bézier approximations
 * (easings.net), bounce and elastic are evaluated procedurally.
 */
enum class EasingPreset(val id: String, val bezier: Easing.CubicBezier?) {
    LINEAR("linear", Easing.CubicBezier(0f, 0f, 1f, 1f)),
    HOLD("hold", null),
    EASE("ease", Easing.CubicBezier(0.25f, 0.1f, 0.25f, 1f)),
    EASE_IN("easeIn", Easing.CubicBezier(0.42f, 0f, 1f, 1f)),
    EASE_OUT("easeOut", Easing.CubicBezier(0f, 0f, 0.58f, 1f)),
    EASE_IN_OUT("easeInOut", Easing.CubicBezier(0.42f, 0f, 0.58f, 1f)),
    EASE_IN_SINE("easeInSine", Easing.CubicBezier(0.12f, 0f, 0.39f, 0f)),
    EASE_OUT_SINE("easeOutSine", Easing.CubicBezier(0.61f, 1f, 0.88f, 1f)),
    EASE_IN_OUT_SINE("easeInOutSine", Easing.CubicBezier(0.37f, 0f, 0.63f, 1f)),
    EASE_IN_QUAD("easeInQuad", Easing.CubicBezier(0.11f, 0f, 0.5f, 0f)),
    EASE_OUT_QUAD("easeOutQuad", Easing.CubicBezier(0.5f, 1f, 0.89f, 1f)),
    EASE_IN_OUT_QUAD("easeInOutQuad", Easing.CubicBezier(0.45f, 0f, 0.55f, 1f)),
    EASE_IN_CUBIC("easeInCubic", Easing.CubicBezier(0.32f, 0f, 0.67f, 0f)),
    EASE_OUT_CUBIC("easeOutCubic", Easing.CubicBezier(0.33f, 1f, 0.68f, 1f)),
    EASE_IN_OUT_CUBIC("easeInOutCubic", Easing.CubicBezier(0.65f, 0f, 0.35f, 1f)),
    EASE_IN_QUART("easeInQuart", Easing.CubicBezier(0.5f, 0f, 0.75f, 0f)),
    EASE_OUT_QUART("easeOutQuart", Easing.CubicBezier(0.25f, 1f, 0.5f, 1f)),
    EASE_IN_OUT_QUART("easeInOutQuart", Easing.CubicBezier(0.76f, 0f, 0.24f, 1f)),
    EASE_IN_QUINT("easeInQuint", Easing.CubicBezier(0.64f, 0f, 0.78f, 0f)),
    EASE_OUT_QUINT("easeOutQuint", Easing.CubicBezier(0.22f, 1f, 0.36f, 1f)),
    EASE_IN_OUT_QUINT("easeInOutQuint", Easing.CubicBezier(0.83f, 0f, 0.17f, 1f)),
    EASE_IN_EXPO("easeInExpo", Easing.CubicBezier(0.7f, 0f, 0.84f, 0f)),
    EASE_OUT_EXPO("easeOutExpo", Easing.CubicBezier(0.16f, 1f, 0.3f, 1f)),
    EASE_IN_OUT_EXPO("easeInOutExpo", Easing.CubicBezier(0.87f, 0f, 0.13f, 1f)),
    EASE_IN_CIRC("easeInCirc", Easing.CubicBezier(0.55f, 0f, 1f, 0.45f)),
    EASE_OUT_CIRC("easeOutCirc", Easing.CubicBezier(0f, 0.55f, 0.45f, 1f)),
    EASE_IN_OUT_CIRC("easeInOutCirc", Easing.CubicBezier(0.85f, 0f, 0.15f, 1f)),
    EASE_IN_BACK("easeInBack", Easing.CubicBezier(0.36f, 0f, 0.66f, -0.56f)),
    EASE_OUT_BACK("easeOutBack", Easing.CubicBezier(0.34f, 1.56f, 0.64f, 1f)),
    EASE_IN_OUT_BACK("easeInOutBack", Easing.CubicBezier(0.68f, -0.6f, 0.32f, 1.6f)),
    EASE_IN_BOUNCE("easeInBounce", null),
    EASE_OUT_BOUNCE("easeOutBounce", null),
    EASE_IN_OUT_BOUNCE("easeInOutBounce", null),
    EASE_IN_ELASTIC("easeInElastic", null),
    EASE_OUT_ELASTIC("easeOutElastic", null),
    EASE_IN_OUT_ELASTIC("easeInOutElastic", null),
    ;

    fun transform(u: Float): Float {
        if (this == HOLD) return if (u >= 1f) 1f else 0f
        if (u <= 0f) return 0f
        if (u >= 1f) return 1f
        bezier?.let { return it.transform(u) }
        val x = u.toDouble()
        return when (this) {
            EASE_OUT_BOUNCE -> bounceOut(x)
            EASE_IN_BOUNCE -> 1.0 - bounceOut(1.0 - x)
            EASE_IN_OUT_BOUNCE -> if (x < 0.5) (1.0 - bounceOut(1.0 - 2.0 * x)) / 2.0 else (1.0 + bounceOut(2.0 * x - 1.0)) / 2.0
            EASE_IN_ELASTIC -> {
                val c4 = 2.0 * PI / 3.0
                -(2.0.pow(10.0 * x - 10.0)) * sin((x * 10.0 - 10.75) * c4)
            }
            EASE_OUT_ELASTIC -> {
                val c4 = 2.0 * PI / 3.0
                2.0.pow(-10.0 * x) * sin((x * 10.0 - 0.75) * c4) + 1.0
            }
            EASE_IN_OUT_ELASTIC -> {
                val c5 = 2.0 * PI / 4.5
                if (x < 0.5) -(2.0.pow(20.0 * x - 10.0) * sin((20.0 * x - 11.125) * c5)) / 2.0
                else 2.0.pow(-20.0 * x + 10.0) * sin((20.0 * x - 11.125) * c5) / 2.0 + 1.0
            }
            else -> x
        }.toFloat()
    }

    companion object {
        private val byId = entries.associateBy { it.id }
        fun fromId(id: String): EasingPreset? = byId[id]

        private fun bounceOut(x: Double): Double {
            val n1 = 7.5625
            val d1 = 2.75
            return when {
                x < 1.0 / d1 -> n1 * x * x
                x < 2.0 / d1 -> { val y = x - 1.5 / d1; n1 * y * y + 0.75 }
                x < 2.5 / d1 -> { val y = x - 2.25 / d1; n1 * y * y + 0.9375 }
                else -> { val y = x - 2.625 / d1; n1 * y * y + 0.984375 }
            }
        }
    }
}

/** Cubic Bézier timing function solver (same algorithm as WebKit's UnitBezier). */
internal class UnitBezier(p1x: Double, p1y: Double, p2x: Double, p2y: Double) {
    private val cx = 3.0 * p1x
    private val bx = 3.0 * (p2x - p1x) - cx
    private val ax = 1.0 - cx - bx
    private val cy = 3.0 * p1y
    private val by = 3.0 * (p2y - p1y) - cy
    private val ay = 1.0 - cy - by

    private fun sampleX(t: Double) = ((ax * t + bx) * t + cx) * t
    private fun sampleY(t: Double) = ((ay * t + by) * t + cy) * t
    private fun sampleDerivX(t: Double) = (3.0 * ax * t + 2.0 * bx) * t + cx

    fun solveX(x: Double, epsilon: Double): Double {
        var t2 = x
        repeat(8) {
            val x2 = sampleX(t2) - x
            if (abs(x2) < epsilon) return t2
            val d2 = sampleDerivX(t2)
            if (abs(d2) < 1e-6) return@repeat
            t2 -= x2 / d2
        }
        var t0 = 0.0
        var t1 = 1.0
        t2 = x
        if (t2 < t0) return t0
        if (t2 > t1) return t1
        while (t0 < t1) {
            val x2 = sampleX(t2)
            if (abs(x2 - x) < epsilon) return t2
            if (x > x2) t0 = t2 else t1 = t2
            t2 = (t1 - t0) * 0.5 + t0
            if (t1 - t0 < 1e-9) break
        }
        return t2
    }

    fun solve(x: Double, epsilon: Double): Double = sampleY(solveX(x, epsilon))
}
