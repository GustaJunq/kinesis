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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Files generated from [EffectRegistry] (the single source of truth for effects):
 * - `schema/knsproj-v2.schema.json` — the v1 schema with the format version and the effect definitions replaced;
 * - `schema/knsproj-v3.schema.json` — the v2 schema plus expressions and text property animators;
 * - `schema/knsproj-v4.schema.json` — the v3 schema plus null layers and independent camera rotation;
 * - `schema/knsproj-v5.schema.json` — the v4 schema plus the cyclic (wave) easing;
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
    fun schemaV3() = check(File(root, "schema/knsproj-v3.schema.json"), JsonPretty.print(buildSchemaV3()) + "\n")

    @Test
    fun schemaV4() = check(File(root, "schema/knsproj-v4.schema.json"), JsonPretty.print(buildSchemaV4()) + "\n")

    @Test
    fun schemaV5() = check(File(root, "schema/knsproj-v5.schema.json"), JsonPretty.print(buildSchemaV5()) + "\n")

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
        props["formatVersion"] = obj("const" to JsonPrimitive(2))
        val m = LinkedHashMap(v1)
        m["\$id"] = str("https://github.com/gustajunq/kinesis/schema/knsproj-v2.schema.json")
        m["title"] = str("Kinesis project (.knsproj) — project.json, format version 2")
        m["properties"] = JsonObject(props)
        m["\$defs"] = JsonObject(defs)
        return JsonObject(m)
    }

    /**
     * Version 3 = version 2 plus expressions on animatable properties (`{"value": …, "expression": "…"}` or keyframes
     * with an `expression`) and text property animators (`text.propertyAnimators`).
     */
    private fun buildSchemaV3(): JsonObject {
        val v2 = buildSchemaV2()
        val defs = LinkedHashMap(v2.getValue("\$defs").jsonObject)
        for (name in listOf("animFloat", "animVec2", "animVec3", "animColor")) {
            val def = defs.getValue(name).jsonObject
            val variants = def.getValue("anyOf").jsonArray
            val plain = variants[0]
            val animated = variants[1].jsonObject
            val props = LinkedHashMap(animated.getValue("properties").jsonObject)
            props["value"] = plain
            props["expression"] = obj(
                "type" to str("string"), "maxLength" to JsonPrimitive(20000),
                "description" to str("JavaScript (After Effects style). The result is the last evaluated statement; `value` is the property's own value. See docs/knsproj.md, expressions."),
            )
            props["expressionEnabled"] = obj("type" to str("boolean"), "description" to str("false switches the expression off without deleting it (default true)."))
            val object3 = LinkedHashMap(animated)
            object3["properties"] = JsonObject(props)
            object3.remove("required")
            object3["anyOf"] = JsonArray(listOf(
                obj("required" to JsonArray(listOf(str("keyframes")))),
                obj("required" to JsonArray(listOf(str("value"), str("expression")))),
            ))
            val d = LinkedHashMap(def)
            d["description"] = str((def.getValue("description") as JsonPrimitive).content.replace(" to animate it.", " to animate it, with an optional \"expression\"."))
            d["anyOf"] = JsonArray(listOf(plain, JsonObject(object3)))
            defs[name] = JsonObject(d)
        }
        defs["textPropertyAnimator"] = obj(
            "description" to str("After Effects style text animator: properties weighted per character by the Expression Selector `amount`."),
            "type" to str("object"),
            "properties" to obj(
                "name" to obj("type" to str("string")),
                "enabled" to obj("type" to str("boolean")),
                "unit" to obj("type" to str("string"), "enum" to JsonArray(listOf(str("character"), str("word"), str("line"))), "description" to str("What textIndex counts (default character).")),
                "position" to ref("animVec2"),
                "scale" to ref("animVec2"),
                "rotation" to ref("animFloat"),
                "opacity" to ref("animFloat"),
                "amount" to ref("animFloat"),
            ),
            "additionalProperties" to JsonPrimitive(false),
        )
        val text = defs.getValue("textContent").jsonObject
        val textProps = LinkedHashMap(text.getValue("properties").jsonObject)
        textProps["propertyAnimators"] = obj("type" to str("array"), "items" to ref("textPropertyAnimator"))
        val newText = LinkedHashMap(text)
        newText["properties"] = JsonObject(textProps)
        defs["textContent"] = JsonObject(newText)
        val props = LinkedHashMap(v2.getValue("properties").jsonObject)
        props["formatVersion"] = obj("const" to JsonPrimitive(3))
        val m = LinkedHashMap(v2)
        m["\$id"] = str("https://github.com/gustajunq/kinesis/schema/knsproj-v3.schema.json")
        m["title"] = str("Kinesis project (.knsproj) — project.json, format version 3")
        m["properties"] = JsonObject(props)
        m["\$defs"] = JsonObject(defs)
        return JsonObject(m)
    }

    /**
     * Version 4 = version 3 plus `null` layers (a transform-only layer, 2D or 3D) and `camera.rotation`/`camera.aim`
     * (aim the camera by its own rotation instead of a target, keeping rotation independent from position).
     */
    private fun buildSchemaV4(): JsonObject {
        val v3 = buildSchemaV3()
        val defs = LinkedHashMap(v3.getValue("\$defs").jsonObject)
        defs["nullContent"] = obj(
            "description" to str("Content of a null layer. A null layer draws nothing; other layers of the same dimension can use it as parent. 2D nulls use `transform`, 3D nulls (`threeD`: true) use `transform3d`."),
            "type" to str("object"),
            "properties" to obj("threeD" to obj("type" to str("boolean"), "description" to str("true for a 3D null object (default false)."))),
            "additionalProperties" to JsonPrimitive(false),
        )
        val camera = defs.getValue("cameraContent").jsonObject
        val cameraProps = LinkedHashMap(camera.getValue("properties").jsonObject)
        cameraProps["rotation"] = obj("\$ref" to str("#/\$defs/animVec3"), "description" to str("Degrees applied X, then Y, then Z; used when aim is \"rotation\". With zero rotation the camera looks along +z (+x right, +y down). Independent from position."))
        cameraProps["aim"] = obj("type" to str("string"), "enum" to JsonArray(listOf(str("target"), str("rotation"))), "description" to str("\"target\" (default): look at `target`. \"rotation\": look along the camera's own axes turned by `rotation`; `target` is ignored."))
        val newCamera = LinkedHashMap(camera)
        newCamera["properties"] = JsonObject(cameraProps)
        defs["cameraContent"] = JsonObject(newCamera)
        val layer = defs.getValue("layer").jsonObject
        val layerProps = LinkedHashMap(layer.getValue("properties").jsonObject)
        val typeDef = LinkedHashMap(layerProps.getValue("type").jsonObject)
        typeDef["enum"] = JsonArray(typeDef.getValue("enum").jsonArray + str("null"))
        layerProps["type"] = JsonObject(typeDef)
        val rebuilt = LinkedHashMap<String, JsonElement>()
        for ((k, v) in layerProps) {
            rebuilt[k] = v
            if (k == "light") rebuilt["null"] = ref("nullContent")
        }
        val newLayer = LinkedHashMap(layer)
        newLayer["properties"] = JsonObject(rebuilt)
        defs["layer"] = JsonObject(newLayer)
        val props = LinkedHashMap(v3.getValue("properties").jsonObject)
        props["formatVersion"] = obj("const" to JsonPrimitive(4))
        val m = LinkedHashMap(v3)
        m["\$id"] = str("https://github.com/gustajunq/kinesis/schema/knsproj-v4.schema.json")
        m["title"] = str("Kinesis project (.knsproj) — project.json, format version 4")
        m["properties"] = JsonObject(props)
        m["\$defs"] = JsonObject(defs)
        return JsonObject(m)
    }

    /** Version 5 = version 4 plus the cyclic easing `{"cyclic": {"offset": 0, "cycles": 3.5}}`. */
    private fun buildSchemaV5(): JsonObject {
        val v4 = buildSchemaV4()
        val defs = LinkedHashMap(v4.getValue("\$defs").jsonObject)
        val easing = defs.getValue("easing").jsonObject
        val cyclic = obj(
            "type" to str("object"),
            "properties" to obj(
                "cyclic" to obj(
                    "type" to str("object"),
                    "description" to str("Wave between the two keyframe values: 0.5 − 0.5·cos(2π·cycles·(u − offset)). With offset 0 and a half-integer number of cycles (1.5, 2.5, 3.5...) it starts at 0 and ends at 1."),
                    "properties" to obj(
                        "offset" to obj("type" to str("number"), "minimum" to JsonPrimitive(-1), "maximum" to JsonPrimitive(1), "description" to str("Shifts the wave to the right by this fraction of the segment (default 0).")),
                        "cycles" to obj("type" to str("number"), "minimum" to JsonPrimitive(0.5), "maximum" to JsonPrimitive(64), "description" to str("Full waves across the segment (default 3.5).")),
                    ),
                    "additionalProperties" to JsonPrimitive(false),
                ),
            ),
            "additionalProperties" to JsonPrimitive(false),
            "required" to JsonArray(listOf(str("cyclic"))),
        )
        val newEasing = LinkedHashMap(easing)
        newEasing["anyOf"] = JsonArray(easing.getValue("anyOf").jsonArray + cyclic)
        defs["easing"] = JsonObject(newEasing)
        val props = LinkedHashMap(v4.getValue("properties").jsonObject)
        props["formatVersion"] = obj("const" to JsonPrimitive(5))
        val m = LinkedHashMap(v4)
        m["\$id"] = str("https://github.com/gustajunq/kinesis/schema/knsproj-v5.schema.json")
        m["title"] = str("Kinesis project (.knsproj) — project.json, format version 5")
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
