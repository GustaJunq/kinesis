package dev.kinesis.core

import dev.kinesis.core.anim.Animatable
import dev.kinesis.core.anim.Keyframe
import dev.kinesis.core.format.KnsprojJson
import dev.kinesis.core.format.ProjectValidator
import dev.kinesis.core.model.Effect
import dev.kinesis.core.model.EffectCategory
import dev.kinesis.core.model.EffectRegistry
import dev.kinesis.core.model.EffectValue
import dev.kinesis.core.model.KnsProject
import dev.kinesis.core.model.Layer
import dev.kinesis.core.model.LayerType
import dev.kinesis.core.model.ShapeContent
import dev.kinesis.core.scene.SceneEvaluator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Copy Background (0.1.8-hotfix-2): serialization, keyframes and evaluation. The pixels are checked by the instrumented tests. */
class CopyBackgroundTest {
    private fun project(effects: List<Effect>) = KnsProject(
        layers = listOf(Layer("s", LayerType.SHAPE, shape = ShapeContent(), effects = effects)),
    )

    private val animated = Effect.create("copyBackground")
        .with("amount", EffectValue.Num(Animatable(1f, listOf(Keyframe(0.0, 1f), Keyframe(1.0, 0.25f)))))

    @Test
    fun theEffectIsInTheUtilityCategoryAndNeedsTheBackdrop() {
        val spec = EffectRegistry.require("copyBackground")
        assertEquals(EffectCategory.UTILITY, spec.category)
        assertTrue(spec.needsBackdrop)
        assertEquals(listOf("amount"), spec.params.map { it.name })
    }

    @Test
    fun roundTripsThroughKnsprojAndValidates() {
        for (effects in listOf(listOf(Effect.create("copyBackground")), listOf(animated, Effect.create("invert")))) {
            val json = KnsprojJson.encode(project(effects))
            val back = KnsprojJson.decode(json)
            assertEquals(json, KnsprojJson.encode(back))
            val report = ProjectValidator.validate(back)
            assertTrue(report.errors.joinToString(), report.isValid)
        }
    }

    @Test
    fun theAmountIsKeyframeable() {
        val back = KnsprojJson.decode(KnsprojJson.encode(project(listOf(animated))))
        assertEquals(2, back.layers.single().effects.single().number("amount").keyframes.size)
    }

    @Test
    fun theAmountFollowsTheKeyframes() {
        val p = project(listOf(animated))
        fun amount(t: Double) = SceneEvaluator.evaluate(p, t).nodes.single().effects.single().f("amount")
        assertEquals(amount(0.4), amount(0.4), 0f)
        assertTrue(amount(0.0) > amount(1.0))
    }

    @Test
    fun aVersion10DocumentOpensAsTheCurrentVersion() {
        val v10 = KnsprojJson.encode(project(listOf(Effect.create("invert"))))
            .replace("\"formatVersion\": ${dev.kinesis.core.model.FORMAT_VERSION}", "\"formatVersion\": 10")
        val opened = KnsprojJson.decode(v10)
        assertEquals(dev.kinesis.core.model.FORMAT_VERSION, opened.formatVersion)
        assertEquals("invert", opened.layers.single().effects.single().type)
    }
}
