package dev.kinesis.core.expr

import dev.kinesis.core.anim.Animatable
import dev.kinesis.core.anim.LoopMode
import dev.kinesis.core.edit.AnimProp
import dev.kinesis.core.edit.ColorKind
import dev.kinesis.core.edit.FloatKind
import dev.kinesis.core.edit.LayerProps
import dev.kinesis.core.edit.Vec2Kind
import dev.kinesis.core.edit.Vec3Kind
import dev.kinesis.core.math.Color
import dev.kinesis.core.math.Vec2
import dev.kinesis.core.math.Vec3
import dev.kinesis.core.model.Composition
import dev.kinesis.core.model.Effect
import dev.kinesis.core.model.EffectValue
import dev.kinesis.core.model.Layer
import dev.kinesis.core.model.ParamType
import java.util.IdentityHashMap

/** The composition being evaluated on this thread; set by the scene evaluator so helper calls can resolve expressions. */
object ExprScope {
    private val current = ThreadLocal<Composition?>()
    val comp: Composition? get() = current.get()

    inline fun <R> with(comp: Composition, block: () -> R): R {
        val previous = enter(comp)
        try {
            return block()
        } finally {
            exit(previous)
        }
    }

    fun enter(comp: Composition): Composition? = current.get().also { current.set(comp) }
    fun exit(previous: Composition?) = current.set(previous)
}

/**
 * Replaces every property of a layer that has an active expression by its value at the evaluated time, so the rest of
 * the evaluator keeps sampling plain static properties. Units follow After Effects inside expressions: percentages for
 * scale and opacity (100 = full), position measured from the top-left corner of the composition, colours as 0..1 RGBA.
 */
object ExpressionResolver {
    /** Called when an expression fails while a frame is evaluated; the property then keeps its keyframed value. */
    @Volatile var onError: ((layerId: String, propertyId: String, error: ExpressionError) -> Unit)? = null

    private val scanCache = IdentityHashMap<Layer, Boolean>()

    fun hasExpressions(layer: Layer): Boolean {
        synchronized(scanCache) {
            scanCache[layer]?.let { return it }
        }
        val result = LayerProps.all(layer).any { it.get(layer).hasActiveExpression } ||
            (layer.text?.propertyAnimators?.any { it.enabled && it.amount.hasActiveExpression } == true)
        synchronized(scanCache) {
            if (scanCache.size > 512) scanCache.clear()
            scanCache[layer] = result
        }
        return result
    }

    /** True when the layer has an expression on one of its regular properties (not counting text animators). */
    fun hasPropertyExpressions(layer: Layer): Boolean = LayerProps.all(layer).any { it.get(layer).hasActiveExpression }

    fun resolve(layer: Layer, siblings: Map<String, Layer>, containerTime: Double, comp: Composition): Layer {
        if (!hasExpressions(layer) || !hasPropertyExpressions(layer)) return layer
        var out = layer
        val index = aeIndex(layer, siblings)
        for (prop in LayerProps.all(layer)) {
            if (!prop.get(layer).hasActiveExpression) continue
            try {
                out = resolveProp(out, layer, prop, siblings, containerTime, comp, index)
            } catch (e: ExpressionError) {
                onError?.invoke(layer.id, prop.id, e)
            }
        }
        return out
    }

    /** After Effects layer index: 1 is the top layer of the container (the last in paint order). */
    fun aeIndex(layer: Layer, siblings: Map<String, Layer>): Int {
        val ids = siblings.keys.toList()
        val i = ids.indexOf(layer.id)
        return if (i < 0) 1 else ids.size - i
    }

    fun displayName(layer: Layer): String = layer.name.ifBlank { layer.type.name.lowercase().replaceFirstChar { it.uppercase() } }

    fun layerInfo(layer: Layer, siblings: Map<String, Layer>, containerTime: Double, comp: Composition): ExprLayerInfo {
        val t = containerTime - layer.start
        val cx = comp.width / 2.0
        val cy = comp.height / 2.0
        val pos: DoubleArray
        val scale: DoubleArray
        val rot: Double
        val opacity: Double
        if (layer.type.isRendered3D) {
            val tr = layer.transform3d
            val p = sample(tr.position, t); val s = sample(tr.scale, t); val r = sample(tr.rotation, t)
            pos = doubleArrayOf(p.x + cx, p.y + cy); scale = doubleArrayOf(s.x * 100.0, s.y * 100.0); rot = r.z.toDouble()
            opacity = sample(tr.opacity, t) * 100.0
        } else {
            val tr = layer.transform
            val p = sample(tr.position, t); val s = sample(tr.scale, t)
            pos = doubleArrayOf(p.x + cx, p.y + cy); scale = doubleArrayOf(s.x * 100.0, s.y * 100.0)
            rot = sample(tr.rotation, t).toDouble(); opacity = sample(tr.opacity, t) * 100.0
        }
        return ExprLayerInfo(
            displayName(layer), aeIndex(layer, siblings), layer.start, layer.end, pos, scale, rot, opacity,
            effectValue = { eff, par -> effectParamValue(layer, eff, par, t) },
        )
    }

    /**
     * Value of parameter [param] (name or 1-based index) of the effect [effect] (name or 1-based index) of [layer] at
     * layer-local time [t], in the units an expression sees (percent parameters as shown, colours as 0..1 RGBA,
     * switches as 0/1, choices as their 1-based index). Keyframes apply; expressions on the parameter do not (yet).
     * Null when the effect or parameter does not exist.
     */
    fun effectParamValue(layer: Layer, effect: Any, param: Any, t: Double): DoubleArray? {
        val e = findEffect(layer.effects, effect) ?: return null
        val spec = e.spec
        val p = when (param) {
            is Int -> spec.params.getOrNull(param - 1)
            else -> spec.params.firstOrNull { sameName(it.name, param.toString()) || sameName(it.labelEn, param.toString()) || sameName(it.labelPt, param.toString()) }
        } ?: return null
        val lerp1 = { x: Float, y: Float, u: Float -> x + (y - x) * u }
        return when (val v = e.value(p.name)) {
            is EffectValue.Num -> {
                val x = v.anim.copy(expression = null).sample(t, lerp1)
                doubleArrayOf((if (p.type == ParamType.INTEGER) Math.round(x).toFloat() else x).toDouble() * p.unit.display)
            }
            is EffectValue.Point -> sample(v.anim, t).let { doubleArrayOf(it.x.toDouble(), it.y.toDouble()) }
            is EffectValue.Col -> v.anim.copy(expression = null).sample(t) { a, b, u ->
                Color(a.r + (b.r - a.r) * u, a.g + (b.g - a.g) * u, a.b + (b.b - a.b) * u, a.a + (b.a - a.a) * u)
            }.let { doubleArrayOf(it.r.toDouble(), it.g.toDouble(), it.b.toDouble(), it.a.toDouble()) }
            is EffectValue.Flag -> doubleArrayOf(if (v.value) 1.0 else 0.0)
            is EffectValue.Choice -> doubleArrayOf((p.choices.indexOfFirst { it.id == v.value } + 1).toDouble())
            is EffectValue.Stops -> null
        }
    }

    /** The effect called [ref] on [effects]: a 1-based index, a user name, or (when unnamed) its default name or id. */
    fun findEffect(effects: List<Effect>, ref: Any): Effect? {
        if (ref is Int) return effects.getOrNull(ref - 1)
        val n = ref.toString()
        effects.firstOrNull { it.name != null && sameName(it.name, n) }?.let { return it }
        return effects.firstOrNull { it.name == null && (sameName(it.type, n) || sameName(it.spec.nameEn, n) || sameName(it.spec.namePt, n)) }
    }

    private fun sameName(a: String?, b: String) = a != null && a.trim().equals(b.trim(), ignoreCase = true)

    private fun sample(a: Animatable<Float>, t: Double): Float = a.copy(expression = null).sample(t) { x, y, u -> x + (y - x) * u }
    private fun sample(a: Animatable<Vec2>, t: Double): Vec2 = a.copy(expression = null).sample(t) { x, y, u -> Vec2(x.x + (y.x - x.x) * u, x.y + (y.y - x.y) * u) }
    private fun sample(a: Animatable<Vec3>, t: Double): Vec3 =
        a.copy(expression = null).sample(t) { x, y, u -> Vec3(x.x + (y.x - x.x) * u, x.y + (y.y - x.y) * u, x.z + (y.z - x.z) * u) }

    fun lookup(siblings: Map<String, Layer>, containerTime: Double, comp: Composition): (Any) -> ExprLayerInfo? = { ref ->
        val layer = when (ref) {
            is Int -> siblings.values.firstOrNull { aeIndex(it, siblings) == ref }
            else -> siblings.values.firstOrNull { it.name == ref.toString() || displayName(it) == ref.toString() }
        }
        layer?.let { layerInfo(it, siblings, containerTime, comp) }
    }

    /** Converts values between the stored form of a property and the After Effects form seen by expressions. */
    private class Codec(val kind: Any, val propId: String, val comp: Composition) {
        private val center = if (propId == "transform.position") doubleArrayOf(comp.width / 2.0, comp.height / 2.0) else doubleArrayOf(0.0, 0.0)
        val isArray: Boolean get() = kind !is FloatKind

        fun toAe(v: Any?): DoubleArray = when (kind) {
            is FloatKind -> doubleArrayOf((v as Float) * kind.display.toDouble())
            is Vec2Kind -> (v as Vec2).let { doubleArrayOf(it.x * kind.display + center[0], it.y * kind.display + center[1]) }
            is Vec3Kind -> (v as Vec3).let { doubleArrayOf((it.x * kind.display).toDouble(), (it.y * kind.display).toDouble(), (it.z * kind.display).toDouble()) }
            is ColorKind -> (v as Color).let { doubleArrayOf(it.r.toDouble(), it.g.toDouble(), it.b.toDouble(), it.a.toDouble()) }
            else -> error("unsupported property kind")
        }

        fun fromAe(r: DoubleArray, base: Any?): Any = when (kind) {
            is FloatKind -> (r[0] / kind.display).toFloat()
            is Vec2Kind -> {
                val x = r[0]; val y = if (r.size > 1) r[1] else r[0]
                Vec2(((x - center[0]) / kind.display).toFloat(), ((y - center[1]) / kind.display).toFloat())
            }
            is Vec3Kind -> {
                val b = toAe(base)
                val x = r[0]; val y = if (r.size > 1) r[1] else r[0]; val z = if (r.size > 2) r[2] else if (r.size == 1) r[0] else b[2]
                Vec3((x / kind.display).toFloat(), (y / kind.display).toFloat(), (z / kind.display).toFloat())
            }
            is ColorKind -> {
                val b = base as Color
                when (r.size) {
                    3 -> Color(r[0].toFloat(), r[1].toFloat(), r[2].toFloat(), b.a)
                    4 -> Color(r[0].toFloat(), r[1].toFloat(), r[2].toFloat(), r[3].toFloat())
                    else -> throw ExpressionError(0, "A colour expression must return [red, green, blue] or [red, green, blue, alpha] (0 to 1)")
                }
            }
            else -> error("unsupported property kind")
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun inputFor(
        layer: Layer,
        prop: AnimProp<*>,
        siblings: Map<String, Layer>,
        containerTime: Double,
        comp: Composition,
        index: Int,
    ): Triple<ExprInput, Codec, AnimProp<Any?>> {
        val p = prop as AnimProp<Any?>
        val anim = p.get(layer)
        val codec = Codec(p.kind, p.id, comp)
        val local = containerTime - layer.start
        val plain = anim.copy(loop = LoopMode.NONE, expression = null)
        val input = ExprInput(
            time = containerTime,
            value = codec.toAe(anim.copy(expression = null).sample(local, p.interpolate)),
            valueIsArray = codec.isArray,
            inPoint = layer.start,
            outPoint = layer.end,
            layerIndex = index,
            comp = ExprComp(comp.width.toDouble(), comp.height.toDouble(), comp.duration, comp.frameDuration, siblings.size),
            seed = seedOf(layer.id, p.id),
            keys = anim.keyframes.map { ExprKey(layer.start + it.time, codec.toAe(it.value)) },
            valueAtTime = { t -> codec.toAe(plain.sample(t - layer.start, p.interpolate)) },
            ownLayer = layerInfo(layer, siblings, containerTime, comp),
            layerLookup = lookup(siblings, containerTime, comp),
        )
        return Triple(input, codec, p)
    }

    private fun resolveProp(
        out: Layer,
        layer: Layer,
        prop: AnimProp<*>,
        siblings: Map<String, Layer>,
        containerTime: Double,
        comp: Composition,
        index: Int,
    ): Layer {
        val (input, codec, p) = inputFor(layer, prop, siblings, containerTime, comp, index)
        val anim = p.get(layer)
        val result = ExpressionEngine.evaluate(anim.expression!!, input)
        val local = containerTime - layer.start
        val value = codec.fromAe(result.values, anim.copy(expression = null).sample(local, p.interpolate))
        return p.set(out, Animatable(value))
    }

    /**
     * Runs [source] once as if it were the expression of [prop] at [containerTime] (for the editor: syntax and runtime
     * errors with their line). Returns null when it works and yields a value valid for the property.
     */
    fun diagnose(layer: Layer, prop: AnimProp<*>, source: String, siblings: Map<String, Layer>, containerTime: Double, comp: Composition): ExpressionError? {
        ExpressionEngine.check(source)?.let { return it }
        return try {
            val (input, codec, p) = inputFor(layer, prop, siblings, containerTime, comp, aeIndex(layer, siblings))
            val result = ExpressionEngine.evaluate(source, input)
            codec.fromAe(result.values, p.get(layer).copy(expression = null).sample(containerTime - layer.start, p.interpolate))
            null
        } catch (e: ExpressionError) {
            e
        }
    }

    fun seedOf(layerId: String, propertyId: String): Long {
        var h = 1469598103934665603L
        for (c in layerId) h = (h xor c.code.toLong()) * 1099511628211L
        h = (h xor 0x2F) * 1099511628211L
        for (c in propertyId) h = (h xor c.code.toLong()) * 1099511628211L
        return h
    }
}
