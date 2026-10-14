package dev.kinesis.core.anim

import dev.kinesis.core.format.AnimatableSerializer
import dev.kinesis.core.math.Color
import dev.kinesis.core.math.Vec2
import dev.kinesis.core.math.Vec3
import dev.kinesis.core.math.lerp
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.floor

/** What happens after the last keyframe. */
enum class LoopMode(val id: String) {
    NONE("none"),

    /** Repeat the keyframed range from the first keyframe. */
    CYCLE("cycle"),

    /** Play the keyframed range forward, then backward, and so on. */
    PINGPONG("pingpong"),
    ;

    companion object {
        fun fromId(id: String): LoopMode? = entries.firstOrNull { it.id == id }
    }
}

/**
 * A keyframe. [time] is in seconds, relative to the start of the layer that owns the property.
 * [easing] shapes the segment from this keyframe to the next one.
 */
data class Keyframe<T>(val time: Double, val value: T, val easing: Easing = Easing.LINEAR)

/**
 * A property that is either static ([keyframes] empty → [value]) or animated by keyframes sorted by time.
 * Instances are immutable; editing operations return new instances.
 */
@Serializable(with = AnimatableSerializer::class)
data class Animatable<T>(
    val value: T,
    val keyframes: List<Keyframe<T>> = emptyList(),
    val loop: LoopMode = LoopMode.NONE,
) {
    val isAnimated: Boolean get() = keyframes.isNotEmpty()

    /** Maps a time to the time actually sampled, applying [loop]. */
    fun loopedTime(time: Double): Double {
        if (loop == LoopMode.NONE || keyframes.size < 2) return time
        val first = keyframes.first().time
        val last = keyframes.last().time
        val span = last - first
        if (span <= 0.0 || time <= last) return time
        val elapsed = time - first
        val cycle = floor(elapsed / span)
        val local = elapsed - cycle * span
        return if (loop == LoopMode.PINGPONG && cycle.toLong() % 2L == 1L) last - local else first + local
    }

    fun sample(time: Double, interpolate: (T, T, Float) -> T): T {
        if (keyframes.isEmpty()) return value
        if (keyframes.size == 1) return keyframes[0].value
        val t = loopedTime(time)
        val first = keyframes.first()
        if (t <= first.time) return first.value
        val last = keyframes.last()
        if (t >= last.time) return last.value
        val i = segmentIndex(t)
        val k0 = keyframes[i]
        val k1 = keyframes[i + 1]
        val span = k1.time - k0.time
        if (span <= 0.0) return k1.value
        val u = ((t - k0.time) / span).toFloat()
        return interpolate(k0.value, k1.value, k0.easing.transform(u))
    }

    /** Index i of the segment [keyframes[i], keyframes[i+1]) that contains [t]. Requires ≥ 2 keyframes. */
    private fun segmentIndex(t: Double): Int {
        var lo = 0
        var hi = keyframes.size - 2
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (keyframes[mid].time <= t) lo = mid else hi = mid - 1
        }
        return lo
    }

    /** Index of the keyframe at [time] (within [epsilon] seconds), or -1. */
    fun keyframeIndexAt(time: Double, epsilon: Double = KEYFRAME_EPSILON): Int =
        keyframes.indexOfFirst { abs(it.time - time) <= epsilon }

    fun withStatic(newValue: T) = Animatable(newValue)

    fun mapTimes(transform: (Double) -> Double): Animatable<T> =
        copy(keyframes = keyframes.map { it.copy(time = transform(it.time)) }.sortedBy { it.time })

    companion object {
        /** Keyframes closer than this (seconds) are considered to be at the same time. */
        const val KEYFRAME_EPSILON = 1e-4

        fun <T> of(value: T) = Animatable(value)
    }
}

typealias AnimFloat = Animatable<Float>
typealias AnimVec2 = Animatable<Vec2>
typealias AnimVec3 = Animatable<Vec3>
typealias AnimColor = Animatable<Color>

/** Interpolators per value type. */
object Interpolators {
    val float: (Float, Float, Float) -> Float = { a, b, t -> lerp(a, b, t) }
    val vec2: (Vec2, Vec2, Float) -> Vec2 = { a, b, t -> lerp(a, b, t) }
    val vec3: (Vec3, Vec3, Float) -> Vec3 = { a, b, t -> lerp(a, b, t) }
    val color: (Color, Color, Float) -> Color = { a, b, t -> lerp(a, b, t) }
}

@JvmName("atFloat")
fun Animatable<Float>.at(time: Double): Float = sample(time, Interpolators.float)

@JvmName("atVec2")
fun Animatable<Vec2>.at(time: Double): Vec2 = sample(time, Interpolators.vec2)

@JvmName("atVec3")
fun Animatable<Vec3>.at(time: Double): Vec3 = sample(time, Interpolators.vec3)

@JvmName("atColor")
fun Animatable<Color>.at(time: Double): Color = sample(time, Interpolators.color)

fun anim(value: Float) = Animatable(value)
fun anim(value: Vec2) = Animatable(value)
fun anim(value: Vec3) = Animatable(value)
fun anim(value: Color) = Animatable(value)
