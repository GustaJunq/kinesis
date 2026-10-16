package dev.kinesis.core

import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import dev.kinesis.core.anim.Animatable
import dev.kinesis.core.anim.Keyframe
import dev.kinesis.core.anim.anim
import dev.kinesis.core.format.KnsprojJson
import dev.kinesis.core.format.Migrations
import dev.kinesis.core.format.ProjectValidator
import dev.kinesis.core.math.Color
import dev.kinesis.core.math.Vec2
import dev.kinesis.core.model.Effect
import dev.kinesis.core.model.EffectCategory
import dev.kinesis.core.model.EffectRegistry
import dev.kinesis.core.model.EffectValue
import dev.kinesis.core.model.GradientStop
import dev.kinesis.core.model.KnsProject
import dev.kinesis.core.model.Layer
import dev.kinesis.core.model.LayerType
import dev.kinesis.core.model.Object3DContent
import dev.kinesis.core.model.ParamType
import dev.kinesis.core.model.ShapeContent
import dev.kinesis.core.scene.SceneEvaluator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EffectsTest {
    private val schema by lazy {
        JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
            .getSchema(File(TestProjects.repoRoot, "schema/knsproj-v6.schema.json").readText())
    }
    private val mapper = ObjectMapper()

    private fun projectWith(vararg effects: Effect, type: LayerType = LayerType.SHAPE) = KnsProject(
        layers = listOf(
            if (type == LayerType.SHAPE) Layer("s", LayerType.SHAPE, shape = ShapeContent(), effects = effects.toList())
            else Layer("c", LayerType.OBJECT3D, object3d = Object3DContent(), effects = effects.toList()),
        ),
    )

    /** A non-default value for every parameter, to exercise the serializer and the schema. */
    private fun everythingSet(type: String): Effect {
        val spec = EffectRegistry.require(type)
        var e = Effect.create(type)
        for (p in spec.params) {
            val v: EffectValue = when (p.type) {
                ParamType.NUMBER, ParamType.INTEGER -> {
                    val lo = p.min ?: -100f
                    val hi = p.max ?: 100f
                    val a = if (p.type == ParamType.INTEGER) Math.round(lo).toFloat() else lo
                    val b = if (p.type == ParamType.INTEGER) Math.round((lo + hi) / 2).toFloat() else (lo + hi) / 2
                    EffectValue.Num(Animatable(a, listOf(Keyframe(0.0, a), Keyframe(1.0, b))))
                }
                ParamType.COLOR -> EffectValue.Col(anim(Color.parse("#336699CC")))
                ParamType.POINT -> EffectValue.Point(Animatable(Vec2(1f, 2f), listOf(Keyframe(0.0, Vec2(1f, 2f)), Keyframe(2.0, Vec2(-30f, 40f)))))
                ParamType.BOOLEAN -> EffectValue.Flag(!p.defaultBoolean)
                ParamType.CHOICE -> EffectValue.Choice(p.choices.last().id)
                ParamType.STOPS -> EffectValue.Stops(listOf(GradientStop(0f, Color.parse("#102030")), GradientStop(0.4f, Color.parse("#FF8800")), GradientStop(1f, Color.WHITE)))
            }
            e = e.with(p.name, v)
        }
        return e
    }

    @Test
    fun registryCoversEveryCategoryButText() {
        val used = EffectRegistry.all.map { it.category }.toSet()
        val expected = EffectCategory.entries.toSet() - EffectCategory.ALL - EffectCategory.TEXT
        assertEquals(expected, used)
        assertEquals(13, EffectCategory.entries.size)
        assertEquals("Todos os efeitos", EffectCategory.entries.first().namePt)
    }

    @Test
    fun everyEffectTypeIsSchemaValidAndRoundTrips() {
        for (spec in EffectRegistry.all) {
            for (effect in listOf(Effect.create(spec.id), everythingSet(spec.id), everythingSet(spec.id).copy(enabled = false))) {
                val project = projectWith(effect)
                val json = KnsprojJson.encode(project)
                val errors = schema.validate(mapper.readTree(json)).map { it.message }
                assertEquals("${spec.id} schema: $json", emptyList<String>(), errors)
                val back = KnsprojJson.decode(json)
                assertEquals(spec.id, KnsprojJson.encode(back), json)
                // Evaluating must not fail and must yield one resolved effect per enabled effect.
                val scene = SceneEvaluator.evaluate(back, 0.5)
                if (effect.enabled && spec.id != "posterizeTime") assertEquals(spec.id, 1, scene.nodes.single().effects.size)
            }
        }
    }

    @Test
    fun defaultsAreNotWritten() {
        val json = KnsprojJson.json.encodeToJsonElement(Effect.serializer(), Effect.create("glass")).jsonObject
        assertEquals(setOf("type"), json.keys)
    }

    @Test
    fun unknownTypesAndBadChoicesAreRejected() {
        fun decode(effect: String) = runCatching {
            KnsprojJson.decode("""{"format":"knsproj","formatVersion":2,"layers":[{"id":"a","type":"shape","shape":{},"effects":[$effect]}]}""")
        }
        assertTrue(decode("""{"type":"noSuchEffect"}""").exceptionOrNull()!!.message!!.contains("unknown effect type"))
        assertTrue(decode("""{"type":"warp","mode":"spiral"}""").exceptionOrNull()!!.message!!.contains("warp.mode"))
        assertTrue(decode("""{"type":"warp","mode":"fractal"}""").isSuccess)
    }

    @Test
    fun v1ColorAdjustIsSplitPreservingKeyframes() {
        val v1 = """
            {"format":"knsproj","formatVersion":1,"layers":[
              {"id":"g","type":"group","layers":[
                {"id":"a","type":"shape","shape":{},"effects":[
                  {"type":"blur","radius":4},
                  {"type":"colorAdjust","enabled":false,"brightness":0.2,"hue":{"keyframes":[{"time":0,"value":0},{"time":1,"value":90,"easing":"easeOutCubic"}]},"saturation":-0.5},
                  {"type":"colorAdjust"}
                ]}
              ]}
            ]}
        """.trimIndent()
        val migrated = Migrations.v1ToV2(Json.parseToJsonElement(v1).jsonObject)
        assertEquals(2, migrated["formatVersion"]!!.jsonPrimitive.content.toInt())
        val effects = migrated["layers"]!!.jsonArray[0].jsonObject["layers"]!!.jsonArray[0].jsonObject["effects"]!!.jsonArray
        assertEquals(listOf("blur", "hueShift", "brightnessContrast", "saturation", "brightnessContrast"), effects.map { (it as JsonObject)["type"]!!.jsonPrimitive.content })
        val hue = effects[1].jsonObject
        assertEquals("false", hue["enabled"]!!.jsonPrimitive.content)
        assertEquals(2, hue["hue"]!!.jsonObject["keyframes"]!!.jsonArray.size)
        assertEquals("easeOutCubic", (hue["hue"]!!.jsonObject["keyframes"] as JsonArray)[1].jsonObject["easing"]!!.jsonPrimitive.content)
        assertEquals("0.2", effects[2].jsonObject["brightness"]!!.jsonPrimitive.content)
        assertFalse("contrast" in effects[2].jsonObject)
        assertEquals("-0.5", effects[3].jsonObject["saturation"]!!.jsonPrimitive.content)

        // Decoding a v1 document goes through the migration and gives a valid v2 project.
        val project = KnsprojJson.decode(v1)
        val layer = project.layers[0].layers[0]
        assertEquals(5, layer.effects.size)
        assertEquals(90f, layer.effects[1].number("hue").keyframes[1].value)
        assertFalse(layer.effects[1].enabled)
        val errors = schema.validate(mapper.readTree(KnsprojJson.encode(project))).map { it.message }
        assertEquals(emptyList<String>(), errors)
    }

    @Test
    fun validatorReportsRangesAndLayerKind() {
        fun codes(p: KnsProject) = ProjectValidator.validate(p, null).issues.map { it.code }
        assertTrue("effect.range" in codes(projectWith(Effect.of("pinchBulge", "amount" to 2f))))
        val keyed = Effect.create("vignette").withNumber("amount", Animatable(0f, listOf(Keyframe(0.0, 0f), Keyframe(1.0, 3f))))
        assertTrue("effect.range" in codes(projectWith(keyed)))
        assertTrue("effect.layerKind" in codes(projectWith(Effect.create("depthMap"))))
        assertFalse("effect.layerKind" in codes(projectWith(Effect.create("depthMap"), type = LayerType.OBJECT3D)))
        assertTrue(codes(projectWith(*EffectRegistry.all.filter { it.id != "depthMap" }.map { Effect.create(it.id) }.toTypedArray())).isEmpty())
    }

    @Test
    fun evaluationIsDeterministicAndClamped() {
        val effects = listOf(
            Effect.of("autoShake", "seed" to 42, "amplitude" to 30f),
            Effect.of("oscillate", "frequency" to 2f),
            Effect.of("spin", "speed" to 90f, "center" to Vec2(100f, 0f)),
            Effect.of("blockGlitch", "intensity" to 5f),
        )
        val p = projectWith(*effects.toTypedArray())
        for (t in listOf(0.0, 0.37, 1.5)) {
            val a = SceneEvaluator.evaluate(p, t).nodes.single().effects
            val b = SceneEvaluator.evaluate(p, t).nodes.single().effects
            for (i in a.indices) {
                assertEquals(a[i].transform, b[i].transform)
                assertEquals(a[i].all, b[i].all)
            }
        }
        val r = SceneEvaluator.evaluate(p, 1.0).nodes.single().effects
        assertEquals(1f, r[3].f("intensity"))
        // Spin: 90 °/s for 1 s around (100, 0) moves the origin to (100, -100).
        val moved = r[2].transform!!.apply(Vec2.ZERO)
        assertEquals(100f, moved.x, 1e-3f)
        assertEquals(-100f, moved.y, 1e-3f)
        // Different seeds shake differently.
        val other = SceneEvaluator.evaluate(projectWith(Effect.of("autoShake", "seed" to 7, "amplitude" to 30f)), 1.0).nodes.single().effects[0]
        assertFalse(other.transform == r[0].transform)
    }

    @Test
    fun posterizeTimeHoldsFramesAndEchoAddsCopies() {
        val layer = Layer(
            "s", LayerType.SHAPE, duration = 4.0, shape = ShapeContent(),
            transform = dev.kinesis.core.model.Transform2D(position = Animatable(Vec2.ZERO, listOf(Keyframe(0.0, Vec2.ZERO), Keyframe(4.0, Vec2(400f, 0f))))),
        )
        val posterized = KnsProject(layers = listOf(layer.copy(effects = listOf(Effect.of("posterizeTime", "fps" to 2f)))))
        fun x(p: KnsProject, t: Double) = (SceneEvaluator.evaluate(p, t).nodes.single() as dev.kinesis.core.scene.Node2D).matrix.tx
        assertEquals(x(posterized, 0.5), x(posterized, 0.9), 1e-4f)
        assertTrue(x(posterized, 1.0) > x(posterized, 0.9))

        val echo = KnsProject(layers = listOf(layer.copy(effects = listOf(Effect.create("blur"), Effect.of("echo", "count" to 3, "delay" to 0.5f), Effect.create("glow")))))
        val e = SceneEvaluator.evaluate(echo, 2.0).nodes.single().effects[1]
        assertEquals(3, e.echoes.size)
        assertEquals(0.6f * 0.6f, e.echoes[1].opacity, 1e-5f)
        // Echo copies keep only the effects placed before the echo.
        assertEquals(listOf("blur"), e.echoes[0].node.effects.map { it.type })
        assertNotNull(e.echoes[2].node)
    }
}
