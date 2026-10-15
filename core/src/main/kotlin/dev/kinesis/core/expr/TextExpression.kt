package dev.kinesis.core.expr

import dev.kinesis.core.anim.Animatable
import dev.kinesis.core.anim.LoopMode
import dev.kinesis.core.math.Vec2
import dev.kinesis.core.model.Composition
import dev.kinesis.core.model.Layer
import dev.kinesis.core.model.TextPropertyAnimator
import dev.kinesis.core.model.TextUnit
import dev.kinesis.core.scene.GlyphState

/** Evaluates the After Effects style text animators of a text layer: one expression run per character (or word/line). */
object TextExpression {

    /** Index (0-based) of the unit of every UTF-16 char of [text] (-1 for characters that belong to no unit) and the unit count. */
    fun units(text: String, unit: TextUnit): Pair<IntArray, Int> {
        val idx = IntArray(text.length) { -1 }
        var n = 0
        when (unit) {
            TextUnit.CHARACTER -> {
                var i = 0
                while (i < text.length) {
                    val cp = Character.codePointAt(text, i)
                    idx[i] = n++
                    i += Character.charCount(cp)
                }
            }
            TextUnit.WORD -> {
                var inWord = false
                var i = 0
                while (i < text.length) {
                    val cp = Character.codePointAt(text, i)
                    val ws = Character.isWhitespace(cp)
                    if (!ws) {
                        if (!inWord) n++
                        inWord = true
                        idx[i] = n - 1
                    } else {
                        inWord = false
                    }
                    i += Character.charCount(cp)
                }
            }
            TextUnit.LINE -> {
                var line = 0
                for (i in text.indices) {
                    idx[i] = line
                    if (text[i] == '\n') line++
                }
                n = line + 1
            }
        }
        return idx to n
    }

    /**
     * Glyph states for every char of the layer's text at [containerTime], or null when the layer has no enabled
     * property animators. [layer] is the layer with its regular expressions already resolved.
     */
    fun evaluate(
        layer: Layer,
        siblings: Map<String, Layer>,
        containerTime: Double,
        comp: Composition,
    ): Array<GlyphState>? {
        val text = layer.text ?: return null
        val animators = text.propertyAnimators.filter { it.enabled }
        if (animators.isEmpty()) return null
        val states = Array(text.content.length) { GlyphState.IDENTITY }
        for ((k, a) in animators.withIndex()) {
            val (unitOf, count) = units(text.content, a.unit)
            if (count == 0) continue
            val perUnit = Array(count) { GlyphState.IDENTITY }
            val weights = unitWeights(layer, siblings, containerTime, comp, a, k, count)
            val local = containerTime - layer.start
            for (u in 0 until count) perUnit[u] = state(a, weights[u], local)
            for (c in states.indices) {
                val u = unitOf[c]
                if (u >= 0) states[c] = combine(states[c], perUnit[u])
            }
        }
        return states
    }

    /** Selector amounts in percent for every unit: `[x or only, y]`. */
    private fun unitWeights(
        layer: Layer,
        siblings: Map<String, Layer>,
        containerTime: Double,
        comp: Composition,
        a: TextPropertyAnimator,
        animatorIndex: Int,
        count: Int,
    ): Array<DoubleArray> {
        val local = containerTime - layer.start
        val plain = a.amount.copy(loop = LoopMode.NONE, expression = null)
        val base = a.amount.copy(expression = null).sample(local) { x, y, u -> x + (y - x) * u }.toDouble()
        val dims = if (a.position != null || a.scale != null) 2 else 1
        if (!a.amount.hasActiveExpression) return Array(count) { doubleArrayOf(base, base) }
        val baseVec = DoubleArray(dims) { base }
        val index = ExpressionResolver.aeIndex(layer, siblings)
        val seed = ExpressionResolver.seedOf(layer.id, "text.animator.$animatorIndex")
        val info = ExpressionResolver.layerInfo(layer, siblings, containerTime, comp)
        val lookup = ExpressionResolver.lookup(siblings, containerTime, comp)
        val expr = ExprComp(comp.width.toDouble(), comp.height.toDouble(), comp.duration, comp.frameDuration, siblings.size)
        return Array(count) { u ->
            val input = ExprInput(
                time = containerTime,
                value = baseVec,
                valueIsArray = dims > 1,
                inPoint = layer.start,
                outPoint = layer.end,
                layerIndex = index,
                comp = expr,
                seed = seed,
                keys = a.amount.keyframes.map { ExprKey(layer.start + it.time, doubleArrayOf(it.value.toDouble())) },
                valueAtTime = { t -> DoubleArray(dims) { plain.sample(t - layer.start) { x, y, f -> x + (y - x) * f }.toDouble() } },
                ownLayer = info,
                layerLookup = lookup,
                textIndex = u + 1,
                textTotal = count,
                selectorValue = baseVec,
            )
            try {
                val r = ExpressionEngine.evaluate(a.amount.expression!!, input).values
                doubleArrayOf(r[0], if (r.size > 1) r[1] else r[0])
            } catch (e: ExpressionError) {
                ExpressionResolver.onError?.invoke(layer.id, "text.animator.$animatorIndex.amount", e)
                doubleArrayOf(base, base)
            }
        }
    }

    private fun state(a: TextPropertyAnimator, w: DoubleArray, local: Double): GlyphState {
        val wx = w[0] / 100.0
        val wy = w[1] / 100.0
        var dx = 0f; var dy = 0f; var scale = 1f; var rotation = 0f; var opacity = 1f
        a.position?.let { p ->
            val v = sampleVec2(p, local)
            dx = (v.x * wx).toFloat(); dy = (v.y * wy).toFloat()
        }
        a.scale?.let { s ->
            val v = sampleVec2(s, local)
            val sx = 1.0 + (v.x / 100.0 - 1.0) * wx
            val sy = 1.0 + (v.y / 100.0 - 1.0) * wy
            scale = ((sx + sy) / 2.0).toFloat()
        }
        a.rotation?.let { r -> rotation = (r.copy(expression = null).sample(local) { x, y, u -> x + (y - x) * u } * wx).toFloat() }
        a.opacity?.let { o ->
            val v = o.copy(expression = null).sample(local) { x, y, u -> x + (y - x) * u }
            opacity = (1.0 + (v / 100.0 - 1.0) * wx).coerceIn(0.0, 1.0).toFloat()
        }
        return GlyphState(dx, dy, scale, rotation, opacity)
    }

    private fun sampleVec2(a: Animatable<Vec2>, local: Double): Vec2 =
        a.copy(expression = null).sample(local) { x, y, u -> Vec2(x.x + (y.x - x.x) * u, x.y + (y.y - x.y) * u) }

    private fun combine(s: GlyphState, o: GlyphState) = GlyphState(
        dx = s.dx + o.dx, dy = s.dy + o.dy, scale = s.scale * o.scale,
        rotation = s.rotation + o.rotation, opacity = s.opacity * o.opacity,
    )
}
