package dev.kinesis.core.alight

import dev.kinesis.core.anim.Animatable
import dev.kinesis.core.anim.Easing
import dev.kinesis.core.anim.Keyframe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** One line of `effects_map.json`: what to do with an Alight effect. */
data class EffectRule(val action: String, val kinesis: String?, val target: String?, val note: String?, val reason: String?)

/**
 * The effect table of the importer, read from `alight/effects_map.json` (data, so a gap can be closed without touching
 * the converter). [rule] returns null for an effect the table does not know.
 */
object AlightEffectsMap {
    private val rules: Map<String, EffectRule> by lazy {
        val text = AlightEffectsMap::class.java.getResourceAsStream("/alight/effects_map.json")?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: return@lazy emptyMap()
        val root = runCatching { Json.parseToJsonElement(text) as JsonObject }.getOrNull() ?: return@lazy emptyMap()
        (root["effects"] as? JsonObject).orEmpty().mapNotNull { (k, v) ->
            val o = v as? JsonObject ?: return@mapNotNull null
            fun s(n: String) = (o[n] as? JsonPrimitive)?.takeIf { it.isString }?.content
            k to EffectRule(s("action") ?: return@mapNotNull null, s("kinesis"), s("target"), s("note"), s("reason"))
        }.toMap()
    }

    fun rule(name: String): EffectRule? = rules[name]
}

/** Keyframes made from a function of time or from two animations at once: the "baking" of procedural effects. */
internal object AlightBake {
    const val MAX_KEYS = 3000

    /** Sample times: every key of [bases] plus a grid of [step] seconds over 0..[dur]. */
    internal fun times(bases: List<Animatable<*>>, dur: Double, step: Double): List<Double> {
        val grid = ArrayList<Double>()
        val n = ceil(dur / step).toInt().coerceIn(1, MAX_KEYS)
        for (i in 0..n) grid += min(dur, i * dur / n)
        val all = (grid + bases.flatMap { b -> b.keyframes.map { it.time } }).sorted()
        val out = ArrayList<Double>()
        for (t in all) if (out.isEmpty() || t - out.last() > 2 * Animatable.KEYFRAME_EPSILON) out += t
        return out
    }

    /** [base] plus a function of the layer's local time (seconds), sampled at least [perPeriod] times per [periodSec]. */
    fun <T> overlay(base: Animatable<T>, dur: Double, periodSec: Double, sample: (Animatable<T>, Double) -> T, add: (T, Double) -> T): Animatable<T> {
        val step = max(dur / MAX_KEYS, min(1.0 / 30.0, periodSec / 12.0))
        val ts = times(listOf(base), dur, step)
        val keys = ts.map { t -> Keyframe(t, add(sample(base, t), t), Easing.LINEAR) }
        return Animatable(keys.first().value, keys)
    }

    /** [a] and [b] combined by [f]. A constant side keeps the other's keyframes and easing; two animated sides are sampled. */
    fun <A, B, T> combine(
        a: Animatable<A>, b: Animatable<B>, dur: Double,
        sa: (Animatable<A>, Double) -> A, sb: (Animatable<B>, Double) -> B, f: (A, B) -> T,
    ): Animatable<T> {
        if (a.keyframes.isEmpty() && b.keyframes.isEmpty()) return Animatable(f(a.value, b.value))
        if (a.keyframes.isEmpty()) return Animatable(f(a.value, b.value), b.keyframes.map { Keyframe(it.time, f(a.value, it.value), it.easing) })
        if (b.keyframes.isEmpty()) return Animatable(f(a.value, b.value), a.keyframes.map { Keyframe(it.time, f(it.value, b.value), it.easing) })
        val marks = (a.keyframes.map { it.time } + b.keyframes.map { it.time }).sorted()
        val ts = ArrayList<Double>()
        for ((i, t) in marks.withIndex()) {
            if (ts.isNotEmpty() && t - ts.last() < 2 * Animatable.KEYFRAME_EPSILON) continue
            if (i > 0 && ts.isNotEmpty()) {
                val from = ts.last(); val gap = t - from
                val n = min(40, max(1, ceil(gap * 8).toInt()))
                for (k in 1 until n) ts += from + gap * k / n
            }
            ts += t
        }
        val keys = ts.map { t -> Keyframe(t, f(sa(a, t), sb(b, t)), Easing.LINEAR) }
        return Animatable(keys.first().value, keys)
    }
}
