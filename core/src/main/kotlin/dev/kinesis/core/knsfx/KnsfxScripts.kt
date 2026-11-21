package dev.kinesis.core.knsfx

import dev.kinesis.core.anim.anim
import dev.kinesis.core.anim.at
import dev.kinesis.core.math.Color
import dev.kinesis.core.math.Vec2
import dev.kinesis.core.model.Composition
import dev.kinesis.core.model.EffectRegistry
import dev.kinesis.core.model.Layer
import dev.kinesis.core.model.ParamType
import dev.kinesis.core.model.updateShape
import dev.kinesis.core.model.updateText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.Function
import org.mozilla.javascript.NativeJSON
import org.mozilla.javascript.RhinoException
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/**
 * Runs the `animate(env, el, p)` scripts of `.knsfx` effects (see `docs/knsfx.md`).
 *
 * - Rhino, interpreted, with the class shutter closed and only the safe standard objects: no Java, files, network or
 *   Android APIs.
 * - Each script is compiled once per thread (preview and export render on different threads) and cached by the
 *   package's hash.
 * - Every call has a budget of [MAX_MILLIS] ms and [MAX_INSTRUCTIONS] instructions. An exception turns the effect off
 *   on that layer at once; a timeout does so after [MAX_TIMEOUTS] in a row (one slow frame is not an error). Either way
 *   [onFailure] is told, and the app shows a warning; the app never crashes because of a script.
 */
object KnsfxScripts {
    const val MAX_MILLIS = 5L
    const val MAX_INSTRUCTIONS = 2_000_000L
    const val MAX_TIMEOUTS = 3

    /** Called once when an effect is turned off on a layer: layer id, effect id, reason. */
    @Volatile var onFailure: ((layerId: String, effectId: String, reason: String) -> Unit)? = null

    private val failed = ConcurrentHashMap.newKeySet<String>()
    private val timeouts = ConcurrentHashMap<String, Int>()

    private class Limit(val why: String) : Error(why)

    private class RunContext(f: ContextFactory) : Context(f) {
        var used = 0L
        var deadline = Long.MAX_VALUE
    }

    private val factory = object : ContextFactory() {
        override fun makeContext(): Context {
            val cx = RunContext(this)
            cx.optimizationLevel = -1
            cx.languageVersion = Context.VERSION_ES6
            cx.instructionObserverThreshold = 2_000
            cx.maximumInterpreterStackDepth = 200
            cx.setClassShutter { false }
            return cx
        }

        override fun observeInstructionCount(cx: Context, instructionCount: Int) {
            val r = cx as RunContext
            r.used += instructionCount
            if (r.used > MAX_INSTRUCTIONS) throw Limit("o script passou do limite de passos (laço infinito?)")
            if (System.nanoTime() > r.deadline) throw Limit("timeout")
        }

        override fun hasFeature(cx: Context, featureIndex: Int): Boolean =
            if (featureIndex == Context.FEATURE_DYNAMIC_SCOPE) false else super.hasFeature(cx, featureIndex)
    }

    private class Compiled(val scope: ScriptableObject, val fn: Function)

    private val cache = ThreadLocal.withInitial { HashMap<String, Compiled>() }

    private fun newScope(cx: Context): ScriptableObject {
        val scope = cx.initSafeStandardObjects(null, false)
        listOf("Packages", "java", "javax", "org", "com", "getClass", "JavaAdapter", "JavaImporter").forEach { scope.delete(it) }
        return scope
    }

    /** Syntax check on import: the script must compile and define `animate`. Throws [KnsfxException]. */
    fun check(source: String) {
        if (source.length > 256 * 1024) throw KnsfxException("Script grande demais")
        val cx = factory.enterContext() as RunContext
        try {
            cx.deadline = System.nanoTime() + 2_000_000_000L
            val scope = newScope(cx)
            cx.evaluateString(scope, source, "effect.js", 1, null)
            if (ScriptableObject.getProperty(scope, "animate") !is Function) throw KnsfxException("O script não define a função animate(env, el, p)")
        } catch (e: RhinoException) {
            throw KnsfxException("Erro no script, linha ${e.lineNumber()}: ${e.details()}")
        } catch (e: Limit) {
            throw KnsfxException("O script não terminou de carregar: ${e.why}")
        } finally {
            Context.exit()
        }
    }

    /** Forgets the failures (a new project, or the user turned the effect back on). */
    fun reset(layerId: String? = null) {
        if (layerId == null) failed.clear() else failed.removeIf { it.startsWith("$layerId|") }
        timeouts.clear()
    }

    /**
     * Applies the scripts of [layer]'s enabled custom effects at layer-local time [t], in order, and returns the layer
     * with its transform (and fill colour) replaced by the scripts' result. Layers without scripts come back as is.
     */
    fun apply(layer: Layer, t: Double, comp: Composition): Layer {
        if (layer.effects.none { it.enabled && EffectRegistry.get(it.type)?.knsfx?.script != null }) return layer
        if (!layer.hasTransform2D) return layer
        var cur = layer
        for (e in layer.effects) {
            if (!e.enabled) continue
            val pkg = EffectRegistry.get(e.type)?.knsfx ?: continue
            val source = pkg.script ?: continue
            val key = "${layer.id}|${e.type}"
            if (key in failed) continue
            val params = buildJsonObject {
                for (p in pkg.params) {
                    when (p.type) {
                        ParamType.NUMBER, ParamType.INTEGER -> put(p.name, p.clamp(e.number(p.name).at(t)))
                        ParamType.COLOR -> putJsonObject(p.name) { color(e.color(p.name).at(t)) }
                        ParamType.POINT -> putJsonObject(p.name) { val v = e.point(p.name).at(t); put("x", v.x); put("y", v.y) }
                        ParamType.BOOLEAN -> put(p.name, e.flag(p.name))
                        ParamType.CHOICE -> e.choice(p.name).let { v -> val n = v.toDoubleOrNull(); if (n != null) put(p.name, n) else put(p.name, v) }
                        ParamType.STOPS -> {}
                    }
                }
            }
            val duration = layer.duration
            val env = buildJsonObject {
                put("time", t)
                put("frame", (t * comp.fps).roundToInt())
                put("fps", comp.fps)
                put("duration", duration)
                put("progress", if (duration > 0) (t / duration).coerceIn(0.0, 1.0) else 0.0)
                put("width", comp.width)
                put("height", comp.height)
            }
            val result = try {
                call(pkg, source, env, elementOf(cur, t), params)
            } catch (f: Limit) {
                if (f.why == "timeout") {
                    val n = timeouts.merge(key, 1, Int::plus) ?: 1
                    if (n >= MAX_TIMEOUTS) fail(key, layer.id, e.type, "o script passou de $MAX_MILLIS ms por quadro")
                } else {
                    fail(key, layer.id, e.type, f.why)
                }
                continue
            } catch (ex: RhinoException) {
                fail(key, layer.id, e.type, "linha ${ex.lineNumber()}: ${ex.details()}")
                continue
            } catch (ex: Exception) {
                fail(key, layer.id, e.type, ex.message ?: ex.javaClass.simpleName)
                continue
            } catch (ex: StackOverflowError) {
                fail(key, layer.id, e.type, "recursão profunda demais")
                continue
            }
            timeouts.remove(key)
            cur = withElement(cur, t, result)
        }
        return cur
    }

    private fun fail(key: String, layerId: String, effectId: String, reason: String) {
        if (failed.add(key)) onFailure?.invoke(layerId, effectId, reason)
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.color(c: Color) {
        put("r", c.r); put("g", c.g); put("b", c.b); put("a", c.a)
    }

    private fun fillOf(layer: Layer, t: Double): Color? = layer.shape?.fill?.color?.at(t) ?: layer.text?.color?.at(t)

    private fun elementOf(layer: Layer, t: Double): JsonObject {
        val tr = layer.transform
        return buildJsonObject {
            putJsonObject("transform") {
                tr.position.at(t).let { putJsonObject("position") { put("x", it.x); put("y", it.y) } }
                tr.scale.at(t).let { putJsonObject("scale") { put("x", it.x); put("y", it.y) } }
                put("rotation", tr.rotation.at(t))
                tr.anchor.at(t).let { putJsonObject("anchor") { put("x", it.x); put("y", it.y) } }
                put("opacity", tr.opacity.at(t))
            }
            fillOf(layer, t)?.let { putJsonObject("fill") { color(it) } }
        }
    }

    private fun call(pkg: KnsfxPackage, source: String, env: JsonObject, el: JsonObject, p: JsonObject): JsonObject {
        val cx = factory.enterContext() as RunContext
        try {
            val compiled = cache.get().getOrPut(pkg.sha256) {
                cx.deadline = System.nanoTime() + 2_000_000_000L // compiling has a looser budget
                cx.used = 0
                val scope = newScope(cx)
                cx.evaluateString(scope, source, "${pkg.id}.js", 1, null)
                val fn = ScriptableObject.getProperty(scope, "animate") as? Function ?: throw IllegalStateException("animate() não definido")
                Compiled(scope, fn)
            }
            cx.used = 0
            cx.deadline = System.nanoTime() + MAX_MILLIS * 1_000_000L
            val scope = compiled.scope
            fun parse(o: JsonObject): Any = org.mozilla.javascript.json.JsonParser(cx, scope).parseValue(o.toString())
            val elObj = parse(el)
            val out = compiled.fn.call(cx, scope, scope, arrayOf(parse(env), elObj, parse(p)))
            val chosen = if (out is Scriptable) out else elObj
            val text = NativeJSON.stringify(cx, scope, chosen, null, null) as? String ?: return el
            return Json.parseToJsonElement(text) as? JsonObject ?: el
        } finally {
            Context.exit()
        }
    }

    private fun withElement(layer: Layer, t: Double, el: JsonObject): Layer {
        fun JsonElement?.num(): Float? = (this as? JsonPrimitive)?.doubleOrNull?.toFloat()?.takeIf { it.isFinite() }
        fun JsonObject?.vec(old: Vec2): Vec2 = Vec2(this?.get("x").num() ?: old.x, this?.get("y").num() ?: old.y)
        val tr = layer.transform
        val o = el["transform"] as? JsonObject
        val transform = tr.copy(
            position = anim(o?.get("position").let { it as? JsonObject }.vec(tr.position.at(t))),
            scale = anim(o?.get("scale").let { it as? JsonObject }.vec(tr.scale.at(t))),
            rotation = anim(o?.get("rotation").num() ?: tr.rotation.at(t)),
            anchor = anim(o?.get("anchor").let { it as? JsonObject }.vec(tr.anchor.at(t))),
            opacity = anim((o?.get("opacity").num() ?: tr.opacity.at(t)).coerceIn(0f, 1f)),
        )
        var out = layer.copy(transform = transform)
        val fill = el["fill"] as? JsonObject
        val old = fillOf(layer, t)
        if (fill != null && old != null) {
            val c = Color(fill["r"].num() ?: old.r, fill["g"].num() ?: old.g, fill["b"].num() ?: old.b, (fill["a"].num() ?: old.a).coerceIn(0f, 1f))
            out = when {
                out.shape != null -> out.updateShape { it.copy(fill = it.fill.copy(color = anim(c))) }
                out.text != null -> out.updateText { it.copy(color = anim(c)) }
                else -> out
            }
        }
        return out
    }
}
