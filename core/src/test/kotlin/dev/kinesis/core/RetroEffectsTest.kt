package dev.kinesis.core

import dev.kinesis.core.anim.Animatable
import dev.kinesis.core.anim.Keyframe
import dev.kinesis.core.format.KnsprojJson
import dev.kinesis.core.format.ProjectValidator
import dev.kinesis.core.math.Color
import dev.kinesis.core.math.Vec2
import dev.kinesis.core.model.Effect
import dev.kinesis.core.model.EffectCategory
import dev.kinesis.core.model.EffectRegistry
import dev.kinesis.core.model.EffectValue
import dev.kinesis.core.model.KnsProject
import dev.kinesis.core.model.Layer
import dev.kinesis.core.model.LayerType
import dev.kinesis.core.model.ParamType
import dev.kinesis.core.model.PixelEncoderCharsets
import dev.kinesis.core.model.ShapeContent
import dev.kinesis.core.scene.SceneEvaluator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The retro and glitch effects of 0.1.8: serialization, keyframes and evaluation. The pixels are checked by the instrumented tests. */
class RetroEffectsTest {
    private val ids = listOf("eightBit", "pixDither", "pixelEncoder", "kernel", "signalAnalog")

    private fun project(effect: Effect) = KnsProject(
        layers = listOf(Layer("s", LayerType.SHAPE, shape = ShapeContent(), effects = listOf(effect))),
    )

    /** Every animatable parameter gets two keyframes; switches and choices get a value other than the default. */
    private fun animated(id: String): Effect {
        var e = Effect.create(id)
        for (p in EffectRegistry.require(id).params) {
            val v: EffectValue = when (p.type) {
                ParamType.NUMBER, ParamType.INTEGER -> {
                    val a = p.min ?: 0f
                    val b = if (p.type == ParamType.INTEGER) Math.round((a + (p.max ?: 10f)) / 2).toFloat() else (a + (p.max ?: 10f)) / 2
                    EffectValue.Num(Animatable(a, listOf(Keyframe(0.0, a), Keyframe(1.0, b))))
                }
                ParamType.COLOR -> EffectValue.Col(Animatable(Color.BLACK, listOf(Keyframe(0.0, Color.BLACK), Keyframe(1.0, Color.parse("#FF8800")))))
                ParamType.POINT -> EffectValue.Point(Animatable(Vec2.ZERO, listOf(Keyframe(0.0, Vec2.ZERO), Keyframe(1.0, Vec2(10f, 20f)))))
                ParamType.BOOLEAN -> EffectValue.Flag(!p.defaultBoolean)
                ParamType.CHOICE -> EffectValue.Choice(p.choices.last().id)
                ParamType.STOPS -> continue
            }
            e = e.with(p.name, v)
        }
        return e
    }

    @Test
    fun theNewEffectsAreInTheirCategories() {
        for (id in ids) {
            val spec = EffectRegistry.require(id)
            assertTrue("$id: ${spec.category}", spec.category == EffectCategory.STYLIZE || spec.category == EffectCategory.GLITCH)
        }
    }

    @Test
    fun roundTripsThroughKnsprojAndValidates() {
        for (id in ids) {
            for (effect in listOf(Effect.create(id), animated(id))) {
                val json = KnsprojJson.encode(project(effect))
                val back = KnsprojJson.decode(json)
                assertEquals("$id round trip", json, KnsprojJson.encode(back))
                val report = ProjectValidator.validate(back)
                assertTrue("$id: ${report.errors.joinToString()}", report.isValid)
            }
        }
    }

    @Test
    fun everyNumericParameterIsKeyframeable() {
        for (id in ids) {
            val spec = EffectRegistry.require(id)
            val numeric = spec.params.filter { it.type == ParamType.NUMBER || it.type == ParamType.INTEGER }
            assertTrue("$id has no numeric parameter", numeric.isNotEmpty())
            val back = KnsprojJson.decode(KnsprojJson.encode(project(animated(id))))
            for (p in numeric) {
                val n = back.layers.single().effects.single().number(p.name)
                assertEquals("$id.${p.name} keyframes", 2, n.keyframes.size)
            }
        }
    }

    @Test
    fun theSameProjectAndTimeResolveToTheSameValues() {
        for (id in ids) {
            val p = project(animated(id))
            val numeric = EffectRegistry.require(id).params.filter { it.type == ParamType.NUMBER || it.type == ParamType.INTEGER }
            fun values(t: Double) = SceneEvaluator.evaluate(p, t).nodes.single().effects.single().let { e -> numeric.map { e.f(it.name) } }
            assertEquals(id, values(0.37), values(0.37))
            assertTrue("$id values follow the keyframes", values(0.0) != values(1.0))
        }
    }

    @Test
    fun pixelEncoderCharsetsAreOrderedFromBlankAndMatchTheRegistry() {
        val spec = EffectRegistry.require("pixelEncoder")
        for (choice in spec.param("charset").choices) {
            val ramp = PixelEncoderCharsets.ramp(choice.id)
            val levels = PixelEncoderCharsets.levels(choice.id)
            assertTrue("${choice.id}: at least 8 levels", levels >= 8)
            if (ramp != null) {
                assertEquals(levels, ramp.length)
                assertEquals("${choice.id} starts with a blank", ' ', ramp.first())
                assertEquals("${choice.id} has no repeated glyph", ramp.length, ramp.toSet().size)
            } else {
                assertEquals(PixelEncoderCharsets.BLOCK_LEVELS, levels)
            }
        }
        assertEquals(PixelEncoderCharsets.levels("ascii"), PixelEncoderCharsets.levels("unknown"))
    }

    @Test
    fun kernelWeightsAreOnlyShownForTheCustomPreset() {
        val spec = EffectRegistry.require("kernel")
        val weights = spec.params.filter { it.name.startsWith("weight") }
        assertEquals(9, weights.size)
        assertEquals(1f, spec.param("weight4").defaultFloat, 0f)
        for (w in weights) {
            assertTrue(w.isVisible { "custom" })
            assertTrue(!w.isVisible { "sharpen" })
            if (w.name != "weight4") assertEquals(0f, w.defaultFloat, 0f)   // the default kernel is the identity
        }
    }
}
