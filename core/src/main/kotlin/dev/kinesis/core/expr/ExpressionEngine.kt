package dev.kinesis.core.expr

import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.NativeArray
import org.mozilla.javascript.RhinoException
import org.mozilla.javascript.Script
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.floor

/** A syntax or runtime error of an expression, with the 1-based [line] (0 when unknown). */
class ExpressionError(val line: Int, val reason: String) : Exception(if (line > 0) "Line $line: $reason" else reason)

/** A keyframe of the evaluated property, in composition time. */
class ExprKey(val time: Double, val value: DoubleArray)

/** What `thisLayer` / `thisComp.layer()` expose. Position is in After Effects space (origin at the top-left corner). */
class ExprLayerInfo(
    val name: String,
    val index: Int,
    val inPoint: Double,
    val outPoint: Double,
    val position: DoubleArray,
    val scale: DoubleArray,
    val rotation: Double,
    val opacity: Double,
)

class ExprComp(val width: Double, val height: Double, val duration: Double, val frameDuration: Double, val numLayers: Int)

/** Everything one evaluation can see. Times are composition seconds; values use After Effects units. */
class ExprInput(
    val time: Double,
    val value: DoubleArray,
    val valueIsArray: Boolean,
    val inPoint: Double,
    val outPoint: Double,
    val layerIndex: Int,
    val comp: ExprComp,
    /** Stable per property; makes `wiggle()` and `random()` differ between properties but not between runs. */
    val seed: Long,
    val keys: List<ExprKey>,
    /** The property sampled from its keyframes (or static value) at a composition time, ignoring the expression. */
    val valueAtTime: (Double) -> DoubleArray,
    val ownLayer: ExprLayerInfo,
    val layerLookup: (Any) -> ExprLayerInfo? = { null },
    val textIndex: Int = 0,
    val textTotal: Int = 0,
    val selectorValue: DoubleArray? = null,
)

class ExprResult(val values: DoubleArray, val isArray: Boolean)

private class ExpressionLimitError : Error("limit")

private class BudgetContext(factory: ContextFactory) : Context(factory) {
    var used = 0L
}

/**
 * Embedded JavaScript interpreter for property expressions (Rhino in interpreted mode).
 *
 * - Offline and isolated: no Java classes are reachable, there are no files, network or processes in the language;
 *   `Date` is removed and `Math.random` is replaced by the seeded, per-frame `random()` so results are deterministic.
 * - Limits: [MAX_SOURCE] characters per expression and [MAX_INSTRUCTIONS] interpreted instructions per evaluation
 *   (an endless loop ends with an error). There is no hard memory cap (Rhino has none); the instruction budget bounds
 *   what an expression can allocate.
 * - Compiled scripts are cached by source text.
 */
object ExpressionEngine {
    const val MAX_SOURCE = 20_000
    const val MAX_INSTRUCTIONS = 400_000L
    private const val CHECK_EVERY = 1_000
    private const val MAX_STACK_DEPTH = 200
    private const val CACHE_LIMIT = 512

    private val factory = object : ContextFactory() {
        override fun makeContext(): Context {
            val cx = BudgetContext(this)
            cx.optimizationLevel = -1 // interpreted: required on Android, and keeps the instruction counter exact
            cx.languageVersion = Context.VERSION_ES6
            cx.instructionObserverThreshold = CHECK_EVERY
            cx.maximumInterpreterStackDepth = MAX_STACK_DEPTH
            cx.setClassShutter { false }
            return cx
        }

        override fun observeInstructionCount(cx: Context, instructionCount: Int) {
            val b = cx as BudgetContext
            b.used += instructionCount
            if (b.used > MAX_INSTRUCTIONS) throw ExpressionLimitError()
        }

        override fun hasFeature(cx: Context, featureIndex: Int): Boolean =
            if (featureIndex == Context.FEATURE_DYNAMIC_SCOPE) false else super.hasFeature(cx, featureIndex)
    }

    private val scripts = ConcurrentHashMap<String, Script>()
    @Volatile private var prelude: Script? = null
    @Volatile private var shared: ScriptableObject? = null

    private fun enter(): BudgetContext = factory.enterContext() as BudgetContext

    private fun ensureShared(cx: Context): ScriptableObject {
        shared?.let { return it }
        synchronized(this) {
            shared?.let { return it }
            val s = cx.initSafeStandardObjects(null, true)
            prelude = cx.compileString(PRELUDE, "prelude", 1, null)
            shared = s
            return s
        }
    }

    private fun compile(cx: Context, source: String): Script {
        scripts[source]?.let { return it }
        if (scripts.size >= CACHE_LIMIT) scripts.clear()
        val s = try {
            cx.compileString(source, "expression", 1, null)
        } catch (e: RhinoException) {
            throw ExpressionError(e.lineNumber(), cleanMessage(e))
        }
        scripts[source] = s
        return s
    }

    /** Returns the error of [source] (syntax or a failing run against a neutral sample), or null when it is valid. */
    fun check(source: String, sample: ExprInput? = null): ExpressionError? {
        if (source.length > MAX_SOURCE) return ExpressionError(0, "The expression is too long (limit $MAX_SOURCE characters)")
        return try {
            val cx = enter()
            try {
                ensureShared(cx)
                compile(cx, source)
            } finally {
                Context.exit()
            }
            if (sample != null) evaluate(source, sample)
            null
        } catch (e: ExpressionError) {
            e
        }
    }

    /** Evaluates [source]; throws [ExpressionError]. The result is the value of the last evaluated statement. */
    fun evaluate(source: String, input: ExprInput): ExprResult {
        if (source.length > MAX_SOURCE) throw ExpressionError(0, "The expression is too long (limit $MAX_SOURCE characters)")
        val cx = enter()
        try {
            cx.used = 0
            val base = ensureShared(cx)
            val script = compile(cx, source)
            val run = Run(input)
            val scope = cx.newObject(base) as ScriptableObject
            scope.prototype = base
            scope.parentScope = null
            run.install(cx, scope, base)
            try {
                prelude!!.exec(cx, scope)
                val result = script.exec(cx, scope)
                return run.convert(result)
            } catch (e: ExpressionLimitError) {
                throw ExpressionError(0, "The expression ran too long (more than $MAX_INSTRUCTIONS steps). Is there an endless loop?")
            } catch (e: ExpressionError) {
                throw e
            } catch (e: RhinoException) {
                throw ExpressionError(e.lineNumber(), cleanMessage(e))
            } catch (e: StackOverflowError) {
                throw ExpressionError(0, "The expression nests too deeply")
            } catch (e: OutOfMemoryError) {
                throw ExpressionError(0, "The expression used too much memory")
            }
        } finally {
            Context.exit()
        }
    }

    private fun cleanMessage(e: RhinoException): String = e.details().orEmpty().ifBlank { e.message.orEmpty() }

    private class Run(val input: ExprInput) {
        private var rng = splitMix(input.seed xor (floor(input.time / input.comp.frameDuration.coerceAtLeast(1e-6)).toLong() * 0x9E3779B97F4A7C15uL.toLong()))
        private var noiseSeed = input.seed

        fun install(cx: Context, scope: ScriptableObject, base: ScriptableObject) {
            fun num(name: String, v: Double) = ScriptableObject.putProperty(scope, name, v)
            ScriptableObject.putProperty(scope, "__Math", ScriptableObject.getProperty(base, "Math"))
            num("time", input.time)
            ScriptableObject.putProperty(scope, "value", toJs(cx, scope, input.value, input.valueIsArray))
            num("inPoint", input.inPoint)
            num("outPoint", input.outPoint)
            num("index", input.layerIndex.toDouble())
            num("textIndex", input.textIndex.toDouble())
            num("textTotal", input.textTotal.toDouble())
            val sel = input.selectorValue
            ScriptableObject.putProperty(scope, "selectorValue", if (sel == null) Undefined.instance else toJs(cx, scope, sel, sel.size > 1))
            val c = input.comp
            ScriptableObject.putProperty(
                scope, "__comp",
                cx.newArray(scope, arrayOf<Any>(c.width, c.height, c.duration, c.frameDuration, c.numLayers.toDouble())),
            )
            fun fn(name: String, body: (Array<Any?>) -> Any?) {
                ScriptableObject.putProperty(scope, name, object : BaseFunction() {
                    override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? = body(args)
                })
            }
            fn("__rand") { nextDouble() }
            fn("__seed") { a ->
                val seed = Context.toNumber(a.getOrNull(0) ?: 0.0).let { if (it.isFinite()) it else 0.0 }
                val timeless = Context.toNumber(a.getOrNull(1) ?: 0.0) != 0.0
                val frame = if (timeless) 0L else floor(input.time / c.frameDuration.coerceAtLeast(1e-6)).toLong()
                val s = seed.toRawBits() xor (frame * 0x9E3779B97F4A7C15uL.toLong())
                rng = splitMix(s)
                noiseSeed = s
                Undefined.instance
            }
            fn("__noise") { a -> noise(Context.toNumber(a.getOrNull(0) ?: 0.0), Context.toNumber(a.getOrNull(1) ?: 0.0).toLong()) }
            fn("__valueAt") { a ->
                val t = Context.toNumber(a.getOrNull(0) ?: input.time)
                val v = input.valueAtTime(t)
                toJs(cx, scope, v, input.valueIsArray)
            }
            fn("__numKeys") { input.keys.size.toDouble() }
            fn("__key") { a ->
                val i = Context.toNumber(a.getOrNull(0) ?: 0.0).toInt()
                if (i < 1 || i > input.keys.size) throw org.mozilla.javascript.EvaluatorException("Keyframe index $i is out of range (1..${input.keys.size})")
                val k = input.keys[i - 1]
                cx.newArray(scope, Array<Any>(k.value.size + 1) { if (it == 0) k.time else k.value[it - 1] })
            }
            fn("__layerInfo") { a ->
                val ref = a.getOrNull(0)
                val info = if (ref is Number && ref.toInt() == input.ownLayer.index) input.ownLayer
                else input.layerLookup(if (ref is Number) ref.toInt() else Context.toString(ref ?: ""))
                if (info == null) null else cx.newArray(
                    scope,
                    arrayOf<Any>(
                        info.name, info.index.toDouble(), info.inPoint, info.outPoint,
                        info.position[0], info.position[1], info.scale[0], info.scale[1], info.rotation, info.opacity,
                    ),
                )
            }
        }

        private fun nextDouble(): Double {
            rng = splitMix(rng)
            return ((rng ushr 11).toDouble()) / (1L shl 53).toDouble()
        }

        /** Smooth value noise in [-1, 1] at [x] for the stream [salt]. */
        private fun noise(x: Double, salt: Long): Double {
            val i = floor(x)
            val f = x - i
            val a = lattice(i.toLong(), salt)
            val b = lattice(i.toLong() + 1, salt)
            val u = f * f * (3 - 2 * f)
            return a + (b - a) * u
        }

        private fun lattice(i: Long, salt: Long): Double {
            val h = splitMix(noiseSeed xor splitMix(salt * 0x632BE59BD9B4E019L + i))
            return ((h ushr 11).toDouble() / (1L shl 53).toDouble()) * 2.0 - 1.0
        }

        fun convert(result: Any?): ExprResult {
            when (result) {
                is Number -> return check(doubleArrayOf(result.toDouble()), false)
                is NativeArray -> {
                    val n = result.length.toInt()
                    if (n == 0 || n > 4) throw ExpressionError(0, "The expression must return a number or an array of 1 to 4 numbers")
                    val out = DoubleArray(n)
                    for (i in 0 until n) {
                        val e = result.get(i, result)
                        out[i] = (e as? Number)?.toDouble() ?: throw ExpressionError(0, "The array returned by the expression contains something that is not a number")
                    }
                    return check(out, true)
                }
                is Undefined, null -> throw ExpressionError(0, "The expression returned no value")
                else -> throw ExpressionError(0, "The expression must return a number or an array of numbers (it returned ${Context.toString(result)})")
            }
        }

        private fun check(v: DoubleArray, array: Boolean): ExprResult {
            if (v.any { !it.isFinite() }) throw ExpressionError(0, "The expression returned an invalid number (NaN or infinity)")
            return ExprResult(v, array)
        }

        private fun toJs(cx: Context, scope: Scriptable, v: DoubleArray, array: Boolean): Any =
            if (array || v.size > 1) cx.newArray(scope, Array<Any>(v.size) { v[it] }) else v[0]
    }

    private fun splitMix(x: Long): Long {
        var z = x + -0x61c8864680b583ebL
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return z xor (z ushr 31)
    }
}
