package dev.kinesis.core

import dev.kinesis.core.format.JsonPretty
import dev.kinesis.core.model.EffectCategory
import dev.kinesis.core.model.EffectParam
import dev.kinesis.core.model.EffectRegistry
import dev.kinesis.core.model.EffectSpec
import dev.kinesis.core.model.FORMAT_VERSION
import dev.kinesis.core.model.GradientStop
import dev.kinesis.core.model.ParamType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Files generated from [EffectRegistry] (the single source of truth for effects):
 * - `schema/knsproj-v2.schema.json` — the v1 schema with the format version and the effect definitions replaced;
 * - `tools/knsproj/effects.json` — the registry for the Python validator;
 * - the effects table of `docs/knsproj.md`.
 *
 * The test fails when a file is stale. Regenerate with `KINESIS_UPDATE_FIXTURES=1`.
 */
class RegistryArtifactsTest {
    private val root = TestProjects.repoRoot
    private val update = System.getenv("KINESIS_UPDATE_FIXTURES") == "1"

    private fun check(file: File, expected: String) {
        if (update || !file.exists()) file.writeText(expected)
        assertEquals("${file.name} is stale: run the core tests with KINESIS_UPDATE_FIXTURES=1", expected, file.readText())
    }

    @Test
    fun schemaV2() = check(File(root, "schema/knsproj-v2.schema.json"), JsonPretty.print(buildSchemaV2()) + "\n")

    @Test
    fun pythonRegistry() = check(File(root, "tools/knsproj/effects.json"), JsonPretty.print(registryJson()) + "\n")

    @Test
    fun docsTable() {
        val file = File(root, "docs/knsproj.md")
        val text = file.readText()
        val begin = text.indexOf(BEGIN)
        val end = text.indexOf(END)
        require(begin >= 0 && end > begin) { "docs/knsproj.md must contain the markers $BEGIN and $END" }
        check(file, text.substring(0, begin + BEGIN.length) + "\n" + docsTableMarkdown() + text.substring(end))
    }

    // ------------------------------------------------------------------ schema

    private fun obj(vararg pairs: Pair<String, JsonElement>) = JsonObject(linkedMapOf(*pairs))
    private fun str(s: String) = JsonPrimitive(s)
    private fun ref(name: String) = obj("\$ref" to str("#/\$defs/$name"))

    private fun buildSchemaV2(): JsonObject {
        val v1 = Json.parseToJsonElement(File(root, "schema/knsproj-v1.schema.json").readText()).jsonObject
        val defs = LinkedHashMap(v1.getValue("\$defs").jsonObject)
        defs["effect"] = obj(
            "description" to str("An effect of EffectRegistry; see the effects table in docs/knsproj.md."),
            "oneOf" to JsonArray(EffectRegistry.all.map(::effectSchema)),
        )
        val props = LinkedHashMap(v1.getValue("properties").jsonObject)
        props["formatVersion"] = obj("const" to JsonPrimitive(FORMAT_VERSION))
        val m = LinkedHashMap(v1)
        m["\$id"] = str("https://github.com/gustajunq/kinesis/schema/knsproj-v$FORMAT_VERSION.schema.json")
        m["title"] = str("Kinesis project (.knsproj) — project.json, format version $FORMAT_VERSION")
        m["properties"] = JsonObject(props)
        m["\$defs"] = JsonObject(defs)
        return JsonObject(m)
    }

    private fun effectSchema(s: EffectSpec): JsonObject {
        val props = linkedMapOf<String, JsonElement>("type" to obj("const" to str(s.id)), "enabled" to obj("type" to str("boolean")))
        for (p in s.params) {
            props[p.name] = when (p.type) {
                ParamType.NUMBER, ParamType.INTEGER -> ref("animFloat")
                ParamType.COLOR -> ref("animColor")
                ParamType.POINT -> ref("animVec2")
                ParamType.BOOLEAN -> obj("type" to str("boolean"))
                ParamType.CHOICE -> obj("type" to str("string"), "enum" to JsonArray(p.choices.map { str(it.id) }))
                ParamType.STOPS -> obj(
                    "type" to str("array"), "minItems" to JsonPrimitive(2), "maxItems" to JsonPrimitive(8),
                    "items" to obj(
                        "type" to str("object"),
                        "properties" to obj(
                            "offset" to obj("type" to str("number"), "minimum" to JsonPrimitive(0), "maximum" to JsonPrimitive(1)),
                            "color" to ref("color"),
                        ),
                        "additionalProperties" to JsonPrimitive(false),
                        "required" to JsonArray(listOf(str("offset"), str("color"))),
                    ),
                )
            }
        }
        return obj(
            "type" to str("object"),
            "properties" to JsonObject(props),
            "additionalProperties" to JsonPrimitive(false),
            "required" to JsonArray(listOf(str("type"))),
            "description" to str("${s.nameEn} (${s.category.nameEn}). ${s.descriptionEn}"),
        )
    }

    // ------------------------------------------------------------------ python registry

    private fun registryJson(): JsonObject = obj(
        "comment" to str("Generated from core EffectRegistry by RegistryArtifactsTest. Do not edit."),
        "version" to JsonPrimitive(EffectRegistry.VERSION),
        "categories" to JsonArray(EffectCategory.entries.map { obj("id" to str(it.id), "pt" to str(it.namePt), "en" to str(it.nameEn)) }),
        "effects" to JsonArray(
            EffectRegistry.all.map { s ->
                obj(
                    "id" to str(s.id),
                    "category" to str(s.category.id),
                    "pt" to str(s.namePt),
                    "en" to str(s.nameEn),
                    "target" to str(s.target.name.lowercase()),
                    "needsBackdrop" to JsonPrimitive(s.needsBackdrop),
                    "params" to JsonArray(s.params.map(::paramJson)),
                )
            },
        ),
    )

    private fun paramJson(p: EffectParam): JsonObject {
        val m = linkedMapOf<String, JsonElement>("name" to str(p.name), "type" to str(p.type.id), "default" to defaultJson(p))
        p.min?.let { m["min"] = num(it) }
        p.max?.let { m["max"] = num(it) }
        if (p.choices.isNotEmpty()) m["choices"] = JsonArray(p.choices.map { str(it.id) })
        return JsonObject(m)
    }

    private fun num(f: Float): JsonPrimitive = if (f == Math.round(f).toFloat()) JsonPrimitive(Math.round(f)) else JsonPrimitive(f.toString().toDouble())

    private fun defaultJson(p: EffectParam): JsonElement = when (p.type) {
        ParamType.NUMBER, ParamType.INTEGER -> num(p.defaultFloat)
        ParamType.COLOR -> str(p.defaultColor.toHex())
        ParamType.POINT -> JsonArray(listOf(num(p.defaultPoint.x), num(p.defaultPoint.y)))
        ParamType.BOOLEAN -> JsonPrimitive(p.defaultBoolean)
        ParamType.CHOICE -> str(p.defaultChoice)
        ParamType.STOPS -> JsonArray(p.defaultStops.map { obj("offset" to num(it.offset), "color" to str(it.color.toHex())) })
    }

    // ------------------------------------------------------------------ docs

    private fun fmt(f: Float): String = if (f == Math.round(f).toFloat()) Math.round(f).toString() else f.toString()

    private fun defaultText(p: EffectParam): String = when (p.type) {
        ParamType.NUMBER, ParamType.INTEGER -> "`${fmt(p.defaultFloat)}`"
        ParamType.COLOR -> "`${p.defaultColor.toHex()}`"
        ParamType.POINT -> p.defaultPoint.let { "`[${fmt(it.x)}, ${fmt(it.y)}]`" }
        ParamType.BOOLEAN -> "`${p.defaultBoolean}`"
        ParamType.CHOICE -> "`\"${p.defaultChoice}\"`"
        ParamType.STOPS -> p.defaultStops.joinToString(" → ") { s: GradientStop -> "`${s.color.toHex()}`@${fmt(s.offset)}" }
    }

    private fun rangeText(p: EffectParam): String {
        val unit = p.unit.symbol.let { if (it.isEmpty()) "" else " $it" }
        return when (p.type) {
            ParamType.NUMBER, ParamType.INTEGER -> {
                val lo = p.min?.let(::fmt) ?: "−∞"
                val hi = p.max?.let(::fmt) ?: "∞"
                (if (p.type == ParamType.INTEGER) "inteiro " else "") + "$lo..$hi$unit"
            }
            ParamType.COLOR -> "cor"
            ParamType.POINT -> "`[x, y]` px relativos à origem da camada"
            ParamType.BOOLEAN -> "`true`/`false` (estático)"
            ParamType.CHOICE -> p.choices.joinToString(", ") { "`${it.id}`" } + " (estático)"
            ParamType.STOPS -> "2 a 8 stops `{offset, color}` (estático)"
        } + (p.visibleWhen?.let { (k, v) -> " — com `$k: \"$v\"`" } ?: "")
    }

    private fun docsTableMarkdown(): String = buildString {
        appendLine("| `type` | Categoria | Parâmetro | Padrão | Faixa |")
        appendLine("|---|---|---|---|---|")
        for (s in EffectRegistry.all) {
            s.params.forEachIndexed { i, p ->
                val first = i == 0
                val type = if (first) "`${s.id}` (${s.namePt})" else ""
                val cat = if (first) s.category.namePt else ""
                appendLine("| $type | $cat | `${p.name}` | ${defaultText(p)} | ${rangeText(p)} |")
            }
        }
    }

    companion object {
        const val BEGIN = "<!-- BEGIN effects-table: gerado a partir do EffectRegistry (RegistryArtifactsTest) -->"
        const val END = "<!-- END effects-table -->"
    }
}
