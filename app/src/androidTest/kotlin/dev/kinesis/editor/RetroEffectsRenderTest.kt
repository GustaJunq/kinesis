package dev.kinesis.editor

import android.opengl.GLES30
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kinesis.core.anim.anim
import dev.kinesis.core.edit.Factory
import dev.kinesis.core.math.Color
import dev.kinesis.core.math.Vec2
import dev.kinesis.core.model.Effect
import dev.kinesis.core.model.EffectRegistry
import dev.kinesis.core.model.KnsProject
import dev.kinesis.core.model.ShapeKind
import dev.kinesis.core.scene.SceneEvaluator
import dev.kinesis.editor.render.AssetFiles
import dev.kinesis.editor.render.Compositor
import dev.kinesis.editor.render.RenderResources
import dev.kinesis.editor.render.RenderSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The retro and glitch effects of 0.1.8 on the real GLES pipeline: determinism, frozen glitches, transparency. */
@RunWith(AndroidJUnit4::class)
class RetroEffectsRenderTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext
    private val ids = listOf("eightBit", "pixDither", "pixelEncoder", "kernel", "signalAnalog", "glitchify", "dissolveShake")

    /** A star and a text over a transparent background, both carrying [effect] (none when null). */
    private fun scene(effect: Effect?): KnsProject {
        val p = Factory.newProject("retro", 480, 480, 30.0, 4.0, Color.parse("#202030"), null, "test")
        val star = Factory.shape(p, 0.0, ShapeKind.STAR, "star")
        val text = Factory.text(p, 0.0, "Kinesis", "t")
        val fx = listOfNotNull(effect)
        return p.copy(
            layers = listOf(
                star.copy(effects = fx, transform = star.transform.copy(scale = anim(Vec2(0.6f, 0.6f)))),
                text.copy(effects = fx),
            ),
        )
    }

    private fun withCompositor(block: (Compositor) -> Unit) {
        PbufferContext().use {
            val res = RenderResources(app.assets, AssetFiles { null })
            val comp = Compositor(res)
            try {
                block(comp)
            } finally {
                comp.release()
                res.release()
            }
        }
    }

    private fun frame(comp: Compositor, project: KnsProject, time: Double): IntArray {
        val target = comp.render(
            project, SceneEvaluator.evaluate(project, time), 240, 240,
            RenderSettings(motionBlurSamples = 1, transparentBackground = true),
        )
        assertEquals("GL error rendering at $time", GLES30.GL_NO_ERROR, GLES30.glGetError())
        val bmp = comp.readPixels(target)
        return IntArray(bmp.width * bmp.height).also { bmp.getPixels(it, 0, bmp.width, 0, 0, bmp.width, bmp.height) }
    }

    /** Same project, same time, same seed: the same pixels, and a different seed changes the random ones. */
    @Test
    fun sameFrameAndSeedGiveTheSamePixels() = withCompositor { comp ->
        for (id in ids) {
            val seeded = EffectRegistry.require(id).paramOrNull("seed") != null
            val project = scene(if (seeded) Effect.of(id, "seed" to 5) else Effect.create(id))
            assertTrue("$id is not deterministic", frame(comp, project, 0.7).contentEquals(frame(comp, project, 0.7)))
        }
        for (id in listOf("signalAnalog", "glitchify", "dissolveShake")) {
            val a = frame(comp, scene(Effect.of(id, "seed" to 1)), 0.7)
            val b = frame(comp, scene(Effect.of(id, "seed" to 2)), 0.7)
            assertFalse("$id ignores its seed", a.contentEquals(b))
        }
    }

    /** `speed` 0 freezes the random patterns: the frame does not depend on the time any more. */
    @Test
    fun speedZeroFreezesTheGlitches() = withCompositor { comp ->
        for (id in listOf("glitchify", "signalAnalog")) {
            val project = scene(Effect.of(id, "speed" to 0f, "seed" to 9))
            assertTrue("$id moves with speed 0", frame(comp, project, 0.3).contentEquals(frame(comp, project, 2.9)))
        }
        val moving = scene(Effect.of("glitchify", "speed" to 1f, "seed" to 9))
        assertFalse("glitchify with speed 1 does not change over time", frame(comp, moving, 0.3).contentEquals(frame(comp, moving, 2.9)))
    }

    /** Every effect changes the picture, and none paints into the transparent corners of the layer. */
    @Test
    fun effectsChangeTheImageAndKeepTransparencyOutsideTheContent() = withCompositor { comp ->
        val plain = frame(comp, scene(null), 1.0)
        for (id in ids) {
            val withEffect = frame(comp, scene(Effect.create(id)), 1.0)
            assertFalse("$id changes nothing", withEffect.contentEquals(plain))
            for (corner in listOf(0, 239, 239 * 240, 239 * 240 + 239)) {
                assertEquals("$id painted a transparent corner", 0, (withEffect[corner] ushr 24) and 0xFF)
            }
        }
    }

    /** Glitchify can punch holes on purpose: with every block dropped out nothing is left. */
    @Test
    fun glitchifyDropoutMakesBlocksTransparent() = withCompositor { comp ->
        val all = frame(comp, scene(Effect.of("glitchify", "dropoutChance" to 1f)), 1.0)
        assertTrue("some pixel survived a dropout of 100%", all.all { (it ushr 24) and 0xFF == 0 })
        val none = frame(comp, scene(Effect.of("glitchify", "dropoutChance" to 0f)), 1.0)
        assertTrue("a dropout of 0% must leave the picture", none.any { (it ushr 24) and 0xFF != 0 })
    }
}
