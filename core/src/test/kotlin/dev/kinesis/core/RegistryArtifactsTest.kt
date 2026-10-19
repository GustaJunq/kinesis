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
 * - `schema/knsproj-v6.schema.json` — the v5 schema plus shading, shadows, depth of field, environment, 3D text, packed alpha;
 * - `schema/knsproj-v7.schema.json` — the v6 schema with `cyclic.offset` as the skew of each cycle and the old shift as `cyclic.phase`;
 * - `schema/knsproj-v8.schema.json` — the v7 schema plus timeline `markers` and the `eq` of audio and video clips;
 * - `schema/knsproj-v9.schema.json` — the v8 schema plus the `editorView` of the viewport (and the TimeSlice effect);
 * - `schema/knsproj-v10.schema.json` — the v9 schema plus the retro and glitch effects of 0.1.8 (8-bit, PixDither, Pixel Encoder, Kernel, Signal Analog, Glitchify, Dissolve Shake);
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
    fun schemaV6() = check(File(root, "schema/knsproj-v6.schema.json"), JsonPretty.print(buildSchemaV6()) + "\n")

    @Test
    fun schemaV7() = check(File(root, "schema/knsproj-v7.schema.json"), JsonPretty.print(buildSchemaV7()) + "\n")

    @Test
    fun schemaV8() = check(File(root, "schema/knsproj-v8.schema.json"), JsonPretty.print(buildSchemaV8()) + "\n")

    @Test
    fun schemaV9() = check(File(root, "schema/knsproj-v9.schema.json"), JsonPretty.print(buildSchemaV9()) + "\n")

    @Test
    fun schemaV10() = check(File(root, "schema/knsproj-v10.schema.json"), JsonPretty.print(buildSchemaV10()) + "\n")

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

    /** Adds [extra] properties to the object definition [name]. */
    private fun withProps(defs: MutableMap<String, JsonElement>, name: String, vararg extra: Pair<String, JsonElement>) {
        val def = defs.getValue(name).jsonObject
        val props = LinkedHashMap(def.getValue("properties").jsonObject)
        for ((k, v) in extra) props[k] = v
        val d = LinkedHashMap(def)
        d["properties"] = JsonObject(props)
        defs[name] = JsonObject(d)
    }

    private fun num(min: Number, max: Number, description: String) =
        obj("type" to str("number"), "minimum" to JsonPrimitive(min), "maximum" to JsonPrimitive(max), "description" to str(description))

    /**
     * Version 6 = version 5 plus material `shading`, light shadows, camera depth of field (`dof`), the composition
     * `environment` (HDRI panorama), extruded 3D text (`primitive: "text"` + `object3d.text`) and `alphaPacked` video.
     */
    private fun buildSchemaV6(): JsonObject {
        val v5 = buildSchemaV5()
        val defs = LinkedHashMap(v5.getValue("\$defs").jsonObject)
        withProps(defs, "material", "shading" to obj(
            "type" to str("string"), "enum" to JsonArray(listOf("pbr", "flat", "toon", "wireframe").map(::str)),
            "description" to str("pbr (default): smooth physically based; flat: one normal per face (low poly); toon: hard light bands; wireframe: mesh edges only."),
        ))
        defs["text3d"] = obj(
            "description" to str("Extruded text of object3d layers with primitive \"text\". Pixels; centred on the layer origin, front face towards the default camera."),
            "type" to str("object"),
            "properties" to obj(
                "content" to obj("type" to str("string"), "description" to str("Text; \\n starts a new line.")),
                "font" to ref("font"),
                "fontSize" to num(4, 2000, "Pixels (default 200)."),
                "depth" to num(1, 2000, "Thickness of the extrusion in pixels (default 60)."),
                "bevel" to num(0, 1000, "Rounded edge width in pixels, at most half the depth (default 4)."),
                "align" to obj("type" to str("string"), "enum" to JsonArray(listOf("left", "center", "right").map(::str))),
                "lineHeight" to obj("type" to str("number"), "exclusiveMinimum" to JsonPrimitive(0), "description" to str("Multiple of the font size (default 1.2).")),
            ),
            "additionalProperties" to JsonPrimitive(false),
        )
        val o3 = defs.getValue("object3dContent").jsonObject
        val o3Props = LinkedHashMap(o3.getValue("properties").jsonObject)
        val prim = LinkedHashMap(o3Props.getValue("primitive").jsonObject)
        prim["enum"] = JsonArray(prim.getValue("enum").jsonArray + str("text"))
        o3Props["primitive"] = JsonObject(prim)
        o3Props["text"] = ref("text3d")
        defs["object3dContent"] = JsonObject(LinkedHashMap(o3).also { it["properties"] = JsonObject(o3Props) })
        defs["depthOfField"] = obj(
            "description" to str("Depth of field of the camera: 3D layers away from focusDistance are blurred."),
            "type" to str("object"),
            "properties" to obj(
                "enabled" to obj("type" to str("boolean")),
                "focusDistance" to ref("animFloat"),
                "range" to ref("animFloat"),
                "blur" to ref("animFloat"),
            ),
            "additionalProperties" to JsonPrimitive(false),
        )
        withProps(defs, "cameraContent", "dof" to ref("depthOfField"))
        withProps(
            defs, "lightContent",
            "castShadows" to obj("type" to str("boolean"), "description" to str("Directional and point lights: 3D objects cast shadows on 3D objects.")),
            "shadowDarkness" to ref("animFloat"),
            "shadowSoftness" to num(0, 8, "Shadow edge softness in shadow-map texels (default 1.5)."),
        )
        defs["environment"] = obj(
            "description" to str("Reflections and ambient light of 3D layers. Without asset a procedural studio is used."),
            "type" to str("object"),
            "properties" to obj(
                "asset" to obj("type" to str("string"), "description" to str("Id of an image asset with an equirectangular (2:1) panorama.")),
                "intensity" to num(0, 10, "Multiplier (default 1)."),
                "rotation" to obj("type" to str("number"), "description" to str("Degrees around the vertical axis.")),
            ),
            "additionalProperties" to JsonPrimitive(false),
        )
        withProps(defs, "composition", "environment" to ref("environment"))
        withProps(defs, "mediaContent", "alphaPacked" to obj("type" to str("boolean"), "description" to str("Video frames hold colour on the left half and alpha (grey) on the right half.")))
        val props = LinkedHashMap(v5.getValue("properties").jsonObject)
        props["formatVersion"] = obj("const" to JsonPrimitive(6))
        val m = LinkedHashMap(v5)
        m["\$id"] = str("https://github.com/gustajunq/kinesis/schema/knsproj-v6.schema.json")
        m["title"] = str("Kinesis project (.knsproj) — project.json, format version 6")
        m["properties"] = JsonObject(props)
        m["\$defs"] = JsonObject(defs)
        return JsonObject(m)
    }

    private fun buildSchemaV7(): JsonObject {
        val v6 = buildSchemaV6()
        val defs = LinkedHashMap(v6.getValue("\$defs").jsonObject)
        val easing = defs.getValue("easing").jsonObject
        val anyOf = easing.getValue("anyOf").jsonArray.map { alt ->
            val props = alt.jsonObject["properties"]?.jsonObject
            if (props == null || "cyclic" !in props) return@map alt
            val cyclic = props.getValue("cyclic").jsonObject
            val cProps = cyclic.getValue("properties").jsonObject
            val newProps = LinkedHashMap(cProps)
            newProps["offset"] = obj(
                "type" to str("number"), "minimum" to JsonPrimitive(-1), "maximum" to JsonPrimitive(1),
                "description" to str("Skew of each cycle (default 0). Negative: the wave rises fast and falls slowly; 0: symmetric; positive: it rises slowly and falls fast. The crest sits at 0.5 + 0.45·offset of the cycle; minimum, maximum and number of cycles do not change."),
            )
            newProps["phase"] = obj(
                "type" to str("number"), "minimum" to JsonPrimitive(-1), "maximum" to JsonPrimitive(1),
                "description" to str("Shifts the whole wave to the right by this fraction of the segment (default 0). Kept from format v5/v6, where this was called offset."),
            )
            val newCyclic = LinkedHashMap(cyclic)
            newCyclic["description"] = str("Wave between the two keyframe values: 0.5 − 0.5·cos(2π·g), where g is the progress of the current cycle skewed by offset. With a half-integer number of cycles (1.5, 2.5, 3.5...) it starts at 0 and ends at 1.")
            newCyclic["properties"] = JsonObject(newProps)
            val newAlt = LinkedHashMap(alt.jsonObject)
            newAlt["properties"] = obj("cyclic" to JsonObject(newCyclic))
            JsonObject(newAlt)
        }
        val newEasing = LinkedHashMap(easing)
        newEasing["anyOf"] = JsonArray(anyOf)
        defs["easing"] = JsonObject(newEasing)
        val props = LinkedHashMap(v6.getValue("properties").jsonObject)
        props["formatVersion"] = obj("const" to JsonPrimitive(7))
        val m = LinkedHashMap(v6)
        m["\$id"] = str("https://github.com/gustajunq/kinesis/schema/knsproj-v7.schema.json")
        m["title"] = str("Kinesis project (.knsproj) — project.json, format version 7")
        m["properties"] = JsonObject(props)
        m["\$defs"] = JsonObject(defs)
        return JsonObject(m)
    }

    private fun buildSchemaV8(): JsonObject {
        val v7 = buildSchemaV7()
        val defs = LinkedHashMap(v7.getValue("\$defs").jsonObject)
        defs["marker"] = obj(
            "description" to str("A named point in time on the timeline ruler, independent of the layers."),
            "type" to str("object"),
            "properties" to obj(
                "id" to obj("type" to str("string"), "minLength" to JsonPrimitive(1)),
                "time" to obj("type" to str("number"), "minimum" to JsonPrimitive(0), "maximum" to JsonPrimitive(3600), "description" to str("Composition seconds.")),
                "name" to obj("type" to str("string")),
                "color" to obj("type" to str("string"), "pattern" to str("^#[0-9a-fA-F]{6}\$"), "description" to str("#RRGGBB; default colour when omitted.")),
            ),
            "required" to JsonArray(listOf(str("id"), str("time"))),
            "additionalProperties" to JsonPrimitive(false),
        )
        defs["eqBand"] = obj(
            "description" to str("One band of the parametric equalizer."),
            "type" to str("object"),
            "properties" to obj(
                "type" to obj("enum" to JsonArray(listOf(str("peak"), str("lowShelf"), str("highShelf"))), "description" to str("Default peak.")),
                "frequency" to num(20, 20000, "Hz (default 1000)."),
                "gain" to num(-24, 24, "dB (default 0)."),
                "q" to num(0.1, 10, "Bandwidth: higher is narrower (default 1)."),
            ),
            "additionalProperties" to JsonPrimitive(false),
        )
        withProps(
            defs, "mediaContent",
            "eq" to obj(
                "type" to str("array"), "maxItems" to JsonPrimitive(8), "items" to ref("eqBand"),
                "description" to str("Parametric equalizer applied after the volume of the clip; omitted or empty = off."),
            ),
        )
        val props = LinkedHashMap<String, JsonElement>()
        for ((k, v) in v7.getValue("properties").jsonObject) {
            props[k] = if (k == "formatVersion") obj("const" to JsonPrimitive(8)) else v
            if (k == "assets") props["markers"] = obj("type" to str("array"), "items" to ref("marker"), "description" to str("Timeline markers (format v8)."))
        }
        val m = LinkedHashMap(v7)
        m["\$id"] = str("https://github.com/gustajunq/kinesis/schema/knsproj-v8.schema.json")
        m["title"] = str("Kinesis project (.knsproj) — project.json, format version 8")
        m["properties"] = JsonObject(props)
        m["\$defs"] = JsonObject(defs)
        return JsonObject(m)
    }

    private fun buildSchemaV9(): JsonObject {
        val v8 = buildSchemaV8()
        val defs = LinkedHashMap(v8.getValue("\$defs").jsonObject)
        defs["editorView"] = obj(
            "description" to str("The free camera of the editor viewport and which view it shows. Editor state: never rendered into the video."),
            "type" to str("object"),
            "properties" to obj(
                "mode" to obj("enum" to JsonArray(listOf(str("camera"), str("editor"), str("inside"))), "description" to str("camera = the active camera's picture (default); editor = the free camera; inside = through the active camera, gestures move it.")),
                "target" to obj("\$ref" to str("#/\$defs/vec3"), "description" to str("Point the editor camera looks at (composition pixels).")),
                "yaw" to num(-360, 360, "Degrees; 0 looks along +z."),
                "pitch" to num(-89, 89, "Degrees; positive looks down from above."),
                "distance" to num(50, 200000, "Pixels from the target."),
                "fov" to num(1, 170, "Vertical field of view in degrees."),
            ),
            "additionalProperties" to JsonPrimitive(false),
        )
        defs["rotoStroke"] = obj(
            "description" to str("A brush stroke of the Roto Brush."),
            "type" to str("object"),
            "properties" to obj(
                "mode" to obj("enum" to JsonArray(listOf(str("add"), str("remove"))), "description" to str("add = part of the object (default); remove = not part of it.")),
                "size" to num(0.005, 0.5, "Brush diameter as a fraction of the frame\'s shorter side (default 0.06)."),
                "points" to obj(
                    "type" to str("array"), "minItems" to JsonPrimitive(1), "maxItems" to JsonPrimitive(2000),
                    "items" to obj("type" to str("array"), "items" to obj("type" to str("number"), "minimum" to JsonPrimitive(0), "maximum" to JsonPrimitive(1)), "minItems" to JsonPrimitive(2), "maxItems" to JsonPrimitive(2)),
                    "description" to str("Points [x, y] of the stroke, normalised 0..1 in the upright frame."),
                ),
            ),
            "required" to JsonArray(listOf(str("points"))),
            "additionalProperties" to JsonPrimitive(false),
        )
        defs["rotoFrame"] = obj(
            "description" to str("The strokes painted on one frame."),
            "type" to str("object"),
            "properties" to obj(
                "time" to num(0, 3600, "Seconds after roto.sourceStart."),
                "strokes" to obj("type" to str("array"), "maxItems" to JsonPrimitive(64), "items" to ref("rotoStroke")),
            ),
            "required" to JsonArray(listOf(str("time"))),
            "additionalProperties" to JsonPrimitive(false),
        )
        defs["rotoBrush"] = obj(
            "description" to str("How a packed-alpha video was cut out with the Roto Brush: the source video and the strokes, so it can be edited again."),
            "type" to str("object"),
            "properties" to obj(
                "source" to obj("type" to str("string"), "description" to str("Asset id of the source video.")),
                "sourceStart" to num(0, 3600, "Seconds into the source where the cut-out starts (default 0)."),
                "duration" to num(0.001, 3600, "Seconds of the source that the cut-out covers."),
                "frames" to obj("type" to str("array"), "maxItems" to JsonPrimitive(240), "items" to ref("rotoFrame")),
            ),
            "required" to JsonArray(listOf(str("source"), str("duration"))),
            "additionalProperties" to JsonPrimitive(false),
        )
        withProps(defs, "mediaContent", "roto" to ref("rotoBrush"))
        val props = LinkedHashMap<String, JsonElement>()
        for ((k, v) in v8.getValue("properties").jsonObject) {
            props[k] = if (k == "formatVersion") obj("const" to JsonPrimitive(9)) else v
            if (k == "markers") props["editorView"] = ref("editorView")
        }
        val m = LinkedHashMap(v8)
        m["\$id"] = str("https://github.com/gustajunq/kinesis/schema/knsproj-v9.schema.json")
        m["title"] = str("Kinesis project (.knsproj) — project.json, format version 9")
        m["properties"] = JsonObject(props)
        m["\$defs"] = JsonObject(defs)
        return JsonObject(m)
    }

    /** Version 10 only adds effects, which come from the registry: the schema differs from v9 in the version only. */
    private fun buildSchemaV10(): JsonObject {
        val v9 = buildSchemaV9()
        val props = LinkedHashMap<String, JsonElement>()
        for ((k, v) in v9.getValue("properties").jsonObject) props[k] = if (k == "formatVersion") obj("const" to JsonPrimitive(10)) else v
        val m = LinkedHashMap(v9)
        m["\$id"] = str("https://github.com/gustajunq/kinesis/schema/knsproj-v10.schema.json")
        m["title"] = str("Kinesis project (.knsproj) — project.json, format version 10")
        m["properties"] = JsonObject(props)
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
