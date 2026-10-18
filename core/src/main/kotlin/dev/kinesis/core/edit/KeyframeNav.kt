package dev.kinesis.core.edit

import dev.kinesis.core.model.Layer
import kotlin.math.abs

/**
 * Keyframes of a whole layer, for the "previous / next" buttons and the "+ ◆" button.
 *
 * Every property counts (transform, effects, masks, text, shape, volume...). Keyframes of different properties at
 * the same instant are one stop. The playhead does not have to sit exactly on a keyframe to remove it: it is
 * "on" a keyframe when it is within [tolerance] seconds (half a frame is what the editor uses).
 */
object KeyframeNav {
    /** Keyframes closer than this (seconds) are the same instant. */
    const val SAME_INSTANT = 1e-3

    /** Every animatable property of [layer], in all sections of the properties panel. */
    fun allProps(layer: Layer): List<AnimProp<*>> = LayerProps.sections(layer).flatMap { it.props }

    /** Layer-local times of the stops, sorted, one per instant. */
    fun stops(layer: Layer): List<Double> {
        val times = allProps(layer).flatMap { p -> p.get(layer).keyframes.map { it.time } }.sorted()
        val out = ArrayList<Double>()
        for (t in times) if (out.isEmpty() || t - out.last() > SAME_INSTANT) out += t
        return out
    }

    /** The stop nearest to [localTime] within [tolerance], or null. */
    fun stopNear(layer: Layer, localTime: Double, tolerance: Double): Double? =
        stops(layer).minByOrNull { abs(it - localTime) }?.takeIf { abs(it - localTime) <= tolerance }

    fun hasKeyframeNear(layer: Layer, localTime: Double, tolerance: Double): Boolean = stopNear(layer, localTime, tolerance) != null

    /** What "+" keyframes: the properties already animated, or the first four of the transform when none is. */
    fun addTargets(layer: Layer): List<AnimProp<*>> {
        val all = allProps(layer)
        val animated = all.filter { it.isAnimated(layer) }
        if (animated.isNotEmpty()) return animated
        return LayerProps.sections(layer).firstOrNull()?.props?.take(4).orEmpty()
    }

    /**
     * The "+ ◆" button: when a stop is within [tolerance] of [localTime], removes the keyframes of every property at
     * that stop; otherwise adds a keyframe at [localTime] on [addTargets].
     */
    fun toggle(layer: Layer, localTime: Double, tolerance: Double): Layer {
        val stop = stopNear(layer, localTime, tolerance)
        if (stop != null) {
            return allProps(layer).fold(layer) { acc, prop ->
                if (prop.hasKeyframeAt(acc, stop) || prop.get(acc).keyframes.any { abs(it.time - stop) <= SAME_INSTANT }) {
                    val exact = prop.get(acc).keyframes.first { abs(it.time - stop) <= SAME_INSTANT }.time
                    prop.toggleKeyframe(acc, exact)
                } else acc
            }
        }
        return addTargets(layer).fold(layer) { acc, prop -> if (prop.hasKeyframeAt(acc, localTime)) acc else prop.toggleKeyframe(acc, localTime) }
    }
}
