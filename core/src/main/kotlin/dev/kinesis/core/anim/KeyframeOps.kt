package dev.kinesis.core.anim

import kotlin.math.abs

/** Pure editing operations on [Animatable]s. All times are layer-local seconds. */
object KeyframeOps {

    /**
     * Sets the property's value at [time]. If the property is animated, the keyframe at [time] is replaced
     * (keeping its easing) or a new keyframe is inserted with [newEasing]. Static properties just change value.
     */
    fun <T> setValue(anim: Animatable<T>, time: Double, value: T, newEasing: Easing = Easing.EASE_IN_OUT): Animatable<T> {
        if (!anim.isAnimated) return anim.copy(value = value)
        return upsert(anim, time, value, newEasing)
    }

    /** Inserts or replaces the keyframe at [time]. Turns a static property into an animated one. */
    fun <T> upsert(anim: Animatable<T>, time: Double, value: T, newEasing: Easing = Easing.EASE_IN_OUT): Animatable<T> {
        val idx = anim.keyframeIndexAt(time)
        val list = anim.keyframes.toMutableList()
        if (idx >= 0) {
            list[idx] = list[idx].copy(value = value)
        } else {
            list += Keyframe(time, value, newEasing)
            list.sortBy { it.time }
        }
        return anim.copy(value = list.first().value, keyframes = list)
    }

    /** Adds a keyframe at [time] holding the current value, or removes the keyframe that is already there. */
    fun <T> toggle(anim: Animatable<T>, time: Double, interpolate: (T, T, Float) -> T, newEasing: Easing = Easing.EASE_IN_OUT): Animatable<T> {
        val idx = anim.keyframeIndexAt(time)
        return if (idx >= 0) removeAt(anim, idx, time, interpolate) else upsert(anim, time, anim.sample(time, interpolate), newEasing)
    }

    /** Removes keyframe [index]. Removing the last keyframe makes the property static with its value at [time]. */
    fun <T> removeAt(anim: Animatable<T>, index: Int, time: Double, interpolate: (T, T, Float) -> T): Animatable<T> {
        if (index !in anim.keyframes.indices) return anim
        val list = anim.keyframes.toMutableList().also { it.removeAt(index) }
        if (list.isEmpty()) return anim.copy(value = anim.sample(time, interpolate), keyframes = emptyList())
        return anim.copy(value = list.first().value, keyframes = list)
    }

    /** Makes the property static, keeping the value it has at [time]. */
    fun <T> clear(anim: Animatable<T>, time: Double, interpolate: (T, T, Float) -> T): Animatable<T> =
        anim.copy(value = anim.sample(time, interpolate), keyframes = emptyList(), loop = LoopMode.NONE)

    fun <T> setEasing(anim: Animatable<T>, index: Int, easing: Easing): Animatable<T> {
        if (index !in anim.keyframes.indices) return anim
        val list = anim.keyframes.toMutableList()
        list[index] = list[index].copy(easing = easing)
        return anim.copy(keyframes = list)
    }

    /** Moves keyframe [index] to [newTime]; a keyframe already at [newTime] is replaced. */
    fun <T> move(anim: Animatable<T>, index: Int, newTime: Double): Animatable<T> {
        if (index !in anim.keyframes.indices) return anim
        val moving = anim.keyframes[index].copy(time = newTime)
        val rest = anim.keyframes.filterIndexed { i, k -> i != index && abs(k.time - newTime) > Animatable.KEYFRAME_EPSILON }
        val list = (rest + moving).sortedBy { it.time }
        return anim.copy(value = list.first().value, keyframes = list)
    }

    /** Index of the keyframe whose segment contains [time] (the keyframe at or before it), or -1. */
    fun <T> segmentStartIndex(anim: Animatable<T>, time: Double): Int {
        var result = -1
        for ((i, k) in anim.keyframes.withIndex()) {
            if (k.time <= time + Animatable.KEYFRAME_EPSILON) result = i else break
        }
        return result
    }
}
