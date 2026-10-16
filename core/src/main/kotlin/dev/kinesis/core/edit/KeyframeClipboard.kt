package dev.kinesis.core.edit

import dev.kinesis.core.anim.Animatable
import dev.kinesis.core.math.Color
import dev.kinesis.core.math.Vec2
import dev.kinesis.core.math.Vec3
import dev.kinesis.core.model.Layer

/**
 * Keyframes copied from a layer: per property id, the animation shifted so the earliest copied keyframe is at 0.
 * [span] is the time from the first to the last copied keyframe.
 */
class KeyframeClip(val entries: Map<String, Animatable<*>>, val span: Double) {
    val isEmpty: Boolean get() = entries.isEmpty()
}

/** Copy and paste of keyframes between properties and layers. Times are layer-local seconds. */
object KeyframeClipboard {

    /** Copies every animated property of [layer] (or only [propIds]). Returns null when nothing is animated. */
    fun copy(layer: Layer, propIds: Set<String>? = null): KeyframeClip? {
        val animated = LayerProps.all(layer)
            .filter { (propIds == null || it.id in propIds) && it.isAnimated(layer) }
            .associate { it.id to it.get(layer) }
        if (animated.isEmpty()) return null
        val first = animated.values.minOf { it.keyframes.first().time }
        val last = animated.values.maxOf { it.keyframes.last().time }
        return KeyframeClip(animated.mapValues { (_, a) -> shift(a, -first) }, last - first)
    }

    /**
     * Pastes [clip] into [target] starting at layer-local time [at]. Properties are matched by id and value type;
     * keyframes of the target inside the pasted time range are replaced, the others are kept.
     * Returns the new layer and how many properties received keyframes.
     */
    fun paste(target: Layer, clip: KeyframeClip, at: Double): Pair<Layer, Int> {
        var layer = target
        var count = 0
        for ((id, source) in clip.entries) {
            val prop = LayerProps.find(layer, id) ?: continue
            val current = prop.get(layer)
            if (!sameType(current.value, source.value)) continue
            layer = merge(prop, layer, source, at, clip.span)
            count++
        }
        return layer to count
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> merge(prop: AnimProp<T>, layer: Layer, source: Animatable<*>, at: Double, span: Double): Layer {
        val incoming = shift(source as Animatable<T>, at)
        val current = prop.get(layer)
        val eps = Animatable.KEYFRAME_EPSILON
        val kept = current.keyframes.filter { it.time < at - eps || it.time > at + span + eps }
        val keys = (kept + incoming.keyframes).sortedBy { it.time }
        val loop = if (current.isAnimated) current.loop else incoming.loop
        return prop.set(layer, current.copy(value = keys.first().value, keyframes = keys, loop = loop))
    }

    private fun <T> shift(a: Animatable<T>, dt: Double): Animatable<T> = a.copy(keyframes = a.keyframes.map { it.copy(time = it.time + dt) })

    private fun sameType(a: Any?, b: Any?): Boolean = when (a) {
        is Float -> b is Float
        is Vec2 -> b is Vec2
        is Vec3 -> b is Vec3
        is Color -> b is Color
        else -> false
    }
}
