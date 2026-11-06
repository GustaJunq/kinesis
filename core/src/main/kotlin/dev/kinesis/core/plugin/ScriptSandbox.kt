package dev.kinesis.core.plugin

import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.Function
import org.mozilla.javascript.NativeJSON
import org.mozilla.javascript.RhinoException
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined

/** An error of a plugin script: syntax, runtime, limit or a bad result. [line] is 1-based (0 when unknown). */
class PluginScriptError(val line: Int, val reason: String) : Exception(if (line > 0) "Linha $line: $reason" else reason)

/** Per-run limits of a plugin script. */
data class ScriptLimits(
    val maxInstructions: Long = 200_000_000L,
    val maxMillis: Long = 10_000L,
    val maxSourceChars: Int = 512 * 1024,
    val maxResultChars: Int = 32 * 1024 * 1024,
    val maxLogLines: Int = 200,
)

/**
 * Runs JavaScript of plugins in Rhino, isolated from everything else:
 *
 * - No Java classes (class shutter closed, safe standard objects only), so no files, network, processes or reflection.
 * - The only host object is `kinesis` (see [install]): logging, reading the plugin's own text files, new ids.
 * - Time and instruction budgets end endless loops; the result size is capped. Memory has no cap inside Rhino, so the
 *   app runs scripts in a separate process that is killed when it misbehaves (see `docs/plugins.md`).
 *
 * Data goes in and out as JSON text: the script gets a plain object and returns a plain object (or nothing).
 */
object ScriptSandbox {
    const val API_VERSION = 1
    private const val CHECK_EVERY = 10_000

    private class LimitError(val why: String) : Error(why)

    private class RunContext(factory: ContextFactory) : Context(factory) {
        var used = 0L
        var deadline = Long.MAX_VALUE
        var limit = Long.MAX_VALUE
    }

    private val factory = object : ContextFactory() {
        override fun makeContext(): Context {
            val cx = RunContext(this)
            cx.optimizationLevel = -1
            cx.languageVersion = Context.VERSION_ES6
            cx.instructionObserverThreshold = CHECK_EVERY
            cx.maximumInterpreterStackDepth = 400
            cx.setClassShutter { false }
            return cx
        }

        override fun observeInstructionCount(cx: Context, instructionCount: Int) {
            val r = cx as RunContext
            r.used += instructionCount
            if (r.used > r.limit) throw LimitError("o script excedeu o limite de passos (laço infinito?)")
            if (System.currentTimeMillis() > r.deadline) throw LimitError("o script passou do tempo limite")
        }

        override fun hasFeature(cx: Context, featureIndex: Int): Boolean =
            if (featureIndex == Context.FEATURE_DYNAMIC_SCOPE) false else super.hasFeature(cx, featureIndex)
    }

    /**
     * Calls [function] of [source] with [inputJson] parsed as its only argument and returns the JSON text of what it
     * returned (`null` when it returned nothing). [files] are the plugin's text files, readable with
     * `kinesis.readText(path)`. Throws [PluginScriptError].
     */
    fun run(
        source: String,
        function: String,
        inputJson: String,
        files: Map<String, String> = emptyMap(),
        limits: ScriptLimits = ScriptLimits(),
        log: (String) -> Unit = {},
    ): String {
        if (source.length > limits.maxSourceChars) throw PluginScriptError(0, "script grande demais")
        val cx = factory.enterContext() as RunContext
        try {
            cx.used = 0
            cx.limit = limits.maxInstructions
            cx.deadline = System.currentTimeMillis() + limits.maxMillis
            val scope = cx.initSafeStandardObjects(null, false)
            // Nothing in the standard library reaches outside, but remove what is not deterministic or not needed.
            listOf("Packages", "java", "javax", "org", "com", "getClass", "JavaAdapter", "JavaImporter").forEach { scope.delete(it) }
            install(cx, scope, files, limits, log)
            try {
                cx.evaluateString(scope, source, "plugin.js", 1, null)
                val fn = ScriptableObject.getProperty(scope, function) as? Function
                    ?: throw PluginScriptError(0, "função \"$function\" não encontrada no script")
                val input = try {
                    org.mozilla.javascript.json.JsonParser(cx, scope).parseValue(inputJson)
                } catch (e: org.mozilla.javascript.json.JsonParser.ParseException) {
                    throw PluginScriptError(0, "entrada inválida: ${e.message}")
                }
                val result = fn.call(cx, scope, scope, arrayOf(input))
                if (result == null || result is Undefined) return "null"
                val text = NativeJSON.stringify(cx, scope, result, null, null) as? String
                    ?: throw PluginScriptError(0, "o resultado não pôde ser convertido em JSON")
                if (text.length > limits.maxResultChars) throw PluginScriptError(0, "resultado grande demais")
                return text
            } catch (e: LimitError) {
                throw PluginScriptError(0, e.why)
            } catch (e: PluginScriptError) {
                throw e
            } catch (e: RhinoException) {
                throw PluginScriptError(e.lineNumber(), e.details().orEmpty().ifBlank { e.message.orEmpty() })
            } catch (e: StackOverflowError) {
                throw PluginScriptError(0, "recursão profunda demais")
            }
        } finally {
            Context.exit()
        }
    }

    private fun install(cx: Context, scope: ScriptableObject, files: Map<String, String>, limits: ScriptLimits, log: (String) -> Unit) {
        val api = cx.newObject(scope) as ScriptableObject
        var lines = 0
        var ids = 0
        fun fn(name: String, body: (Array<Any?>) -> Any?) {
            api.put(name, api, object : BaseFunction() {
                override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? = body(args)
                override fun getArity() = 1
            })
        }
        api.put("apiVersion", api, API_VERSION)
        fn("log") { args ->
            if (lines++ < limits.maxLogLines) log(args.joinToString(" ") { Context.toString(it).take(2000) })
            Undefined.instance
        }
        fn("readText") { args ->
            val path = args.getOrNull(0)?.let { Context.toString(it) }.orEmpty()
            files[path] ?: throw Context.reportRuntimeError("arquivo \"$path\" não existe no plugin")
        }
        fn("newId") { args ->
            val prefix = args.getOrNull(0)?.let { Context.toString(it) }?.filter { it.isLetterOrDigit() }?.take(12).orEmpty().ifEmpty { "layer" }
            "${prefix}_p${java.lang.Long.toString(System.nanoTime() % 1_000_000_000L, 36)}${ids++}"
        }
        api.sealObject()
        ScriptableObject.defineProperty(scope, "kinesis", api, ScriptableObject.READONLY or ScriptableObject.PERMANENT)
    }
}
